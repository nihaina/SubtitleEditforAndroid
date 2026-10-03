package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import com.subtitleedit.feature.ui.SpeechToSubtitleDialogUi
import com.subtitleedit.feature.ui.SpeechToSubtitleUiState
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.nativebridge.NativeMediaOperation
import com.subtitleedit.nativebridge.PcmFormat
import com.subtitleedit.task.LongTaskController
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.util.ByteSizeFormat
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleOutputWriter
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TaskLogBuffer
import com.subtitleedit.util.TokenTimestampGenerator
import com.subtitleedit.util.UriDisplayName
import com.subtitleedit.util.WhisperRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal sealed interface SpeechToSubtitleEvent {
    data object OpenAsrSettings : SpeechToSubtitleEvent
    data class OpenAutoTranslate(val files: List<Uri>) : SpeechToSubtitleEvent
}

// 语言选项
private val languageOptions = SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS.map { AppOption(it, it) }

// 输出格式选项
private val formatOptions = listOf("SRT", "LRC", "TXT").map { AppOption(it, it) }

/**
 * 语音转字幕页面状态与任务
 * 使用 sherpa-onnx + Whisper 进行离线语音识别
 */
internal class SpeechToSubtitleViewModel(
    application: Application
) : AppViewModel<SpeechToSubtitleUiState, SpeechToSubtitleEvent>(
    application,
    SpeechToSubtitleUiState(
        languageOptions = languageOptions,
        formatOptions = formatOptions,
        selectedLanguage = languageOptions.firstOrNull()?.id.orEmpty(),
        selectedFormat = formatOptions.firstOrNull()?.id.orEmpty()
    )
) {
    private companion object {
        const val OUTPUT_DIRECTORY_KEY = "speech_to_subtitle"
        const val LOG_RENDER_INTERVAL_MS = 100L
    }

    private data class SelectedMediaFile(
        val uri: Uri,
        val fileName: String
    )

    private val settingsManager = SettingsManager.getInstance(application)
    private val speechRecognitionService get() = dependencies.speechRecognitionService
    private val taskController = LongTaskController(dependencies.taskStateStore, "speech-to-subtitle")
    private val isCancelled: Boolean get() = taskController.isCancellationRequested

    private var selectedMediaFiles: List<SelectedMediaFile> = emptyList()
    private var encoderPath: String = ""
    private var decoderPath: String = ""
    private var joinerPath: String = ""
    private var tokensPath: String = ""
    private var modelType: String = SettingsManager.ASR_MODEL_WHISPER
    private var vadModelPath: String = ""
    @Volatile private var mediaOperation: NativeMediaOperation? = null
    private var conversionJob: Job? = null

    /** Current output directory, used as the picker's initial location. */
    var outputDirUri: Uri? = null
        private set

    private val realtimeResults = TaskLogBuffer()
    private val lastProgressLog = AtomicReference("")
    private val logRenderScheduled = AtomicBoolean(false)
    private var logRenderJob: Job? = null

    private val isConverting: Boolean get() = currentState.isConverting

    private val selectedFormat: String
        get() = currentState.selectedFormat.ifBlank { formatOptions.first().id }

    private val selectedLanguage: String
        get() = currentState.selectedLanguage.ifBlank { languageOptions.first().id }

    init {
        loadSavedModel()
    }

    /** Reloads model configuration; called on every resume while idle. */
    fun refresh() {
        if (!isConverting) loadSavedModel(resetOutputDirectory = false)
    }

    fun openSettings() {
        if (!isConverting) sendEvent(SpeechToSubtitleEvent.OpenAsrSettings)
    }

    fun selectLanguage(language: String) = setState { copy(selectedLanguage = language) }

    fun selectFormat(format: String) {
        setState { copy(selectedFormat = format) }
        updateVadOptionState()
    }

    fun setAddToAutoTranslate(enabled: Boolean) = setState { copy(addToAutoTranslate = enabled) }

    fun setDisableVadForTxt(enabled: Boolean) = setState { copy(disableVadForTxt = enabled) }

    fun dismissDialog() = setState { copy(dialog = null) }

    /** Returns true when the page may close immediately. */
    fun requestBack(): Boolean {
        if (isConverting) {
            setState { copy(dialog = SpeechToSubtitleDialogUi.BackConfirmation) }
            return false
        }
        dismissDialog()
        return true
    }

    fun confirmBack() {
        dismissDialog()
        cancelConversion()
    }

    /**
     * 加载已保存的模型路径
     */
    private fun loadSavedModel(resetOutputDirectory: Boolean = true) {
        modelType = settingsManager.getAsrModelType()
        when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE -> {
                encoderPath = settingsManager.getSenseVoiceModelPath()
                decoderPath = ""
                joinerPath = ""
                tokensPath = settingsManager.getSenseVoiceTokensPath()
            }
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> {
                encoderPath = settingsManager.getParakeetTdtEncoderPath()
                decoderPath = settingsManager.getParakeetTdtDecoderPath()
                joinerPath = settingsManager.getParakeetTdtJoinerPath()
                tokensPath = settingsManager.getParakeetTdtTokensPath()
            }
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> {
                encoderPath = settingsManager.getParakeetCtcModelPath()
                decoderPath = ""
                joinerPath = ""
                tokensPath = settingsManager.getParakeetCtcTokensPath()
            }
            SettingsManager.ASR_MODEL_QWEN3_ASR -> {
                val variant = settingsManager.getQwen3AsrModelVariant()
                encoderPath = settingsManager.getQwen3AsrEncoderPath(variant)
                decoderPath = settingsManager.getQwen3AsrDecoderPath(variant)
                joinerPath = settingsManager.getQwen3AsrConvFrontendPath(variant)
                tokensPath = settingsManager.getQwen3AsrTokenizerPath(variant)
            }
            else -> {
                encoderPath = settingsManager.getWhisperEncoderPath()
                decoderPath = settingsManager.getWhisperDecoderPath()
                joinerPath = ""
                tokensPath = settingsManager.getWhisperTokensPath()
            }
        }
        vadModelPath = settingsManager.getVadModelPath()

        if (resetOutputDirectory) {
            setupDefaultOutputDir()
        }

        updateStartButtonState()
    }

    /**
     * 处理选择的音频/视频文件
     */
    fun selectFiles(uris: List<Uri>) {
        selectedMediaFiles = uris.map { uri ->
            SelectedMediaFile(uri, UriDisplayName.of(app, uri, string(R.string.unknown_file)))
        }
        setState { copy(selectedFiles = selectedMediaFiles.map(SelectedMediaFile::fileName)) }
        updateStartButtonState()
    }

    /**
     * 处理选择的输出目录
     */
    fun selectOutputDirectory(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            outputDirUri = uri
            setState { copy(outputDirectory = DirectoryDisplayPath.fromUri(app, uri)) }
            settingsManager.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        } catch (e: Exception) {
            toast(R.string.directory_select_failed, e.message.toString(), duration = Toast.LENGTH_LONG)
        }
    }

    /**
     * 设置默认输出目录
     */
    private fun setupDefaultOutputDir() {
        val savedUri = settingsManager.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)
            ?.let(Uri::parse)
        if (savedUri != null) {
            outputDirUri = savedUri
            setState { copy(outputDirectory = DirectoryDisplayPath.fromUri(app, savedUri)) }
            return
        }

        try {
            val defaultPath = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SubtitleEdit/Convert"
            )

            if (!defaultPath.exists()) {
                defaultPath.mkdirs()
            }

            outputDirUri = Uri.fromFile(defaultPath)
            setState { copy(outputDirectory = defaultPath.absolutePath) }
        } catch (e: Exception) {
            Log.e("SpeechToSubtitle", "设置默认输出目录失败", e)
        }
    }

    /**
     * 更新模型提示与开始按钮状态
     */
    private fun updateStartButtonState() {
        val hasAsrModel = isCurrentAsrModelComplete()
        setState {
            copy(
                asrModelReady = hasAsrModel,
                startEnabled = selectedMediaFiles.isNotEmpty() && hasAsrModel
            )
        }
    }

    /**
     * 开始转换
     */
    fun start() {
        if (
            shouldUseTokenTimestampExperiment() &&
            !isTokenTimestampExperimentConfigured()
        ) {
            toast(
                if (modelType == SettingsManager.ASR_MODEL_QWEN3_ASR) {
                    R.string.speech_to_subtitle_qwen_aligner_required
                } else {
                    R.string.speech_to_subtitle_timestamp_experiment_required
                },
                duration = Toast.LENGTH_LONG
            )
            return
        }
        if (selectedMediaFiles.isEmpty() || !isCurrentAsrModelComplete()) {
            toast(R.string.speech_to_subtitle_missing_inputs)
            return
        }
        if (shouldUseVad() && !settingsManager.isVadUseBuiltInModel() && vadModelPath.isBlank()) {
            toast(R.string.speech_to_subtitle_vad_model_required)
            return
        }
        val outputDir = outputDirUri ?: run {
            toast(R.string.output_directory_not_set)
            return
        }
        val extension = selectedFormat.lowercase()

        if (selectedMediaFiles.any { file ->
                SubtitleOutputWriter.exists(app, outputDir, file.fileName.substringBeforeLast("."), extension)
            }) {
            setState { copy(dialog = SpeechToSubtitleDialogUi.OutputConflict) }
            return
        }

        startConversion(overwriteOutput = false)
    }

    fun resolveOutputConflict(overwriteOutput: Boolean) {
        dismissDialog()
        startConversion(overwriteOutput)
    }

    private fun startConversion(overwriteOutput: Boolean) {
        if (taskController.isRunning) return
        mediaOperation?.cancel()
        mediaOperation = dependencies.nativeMediaEngine.openOperation()
        val files = selectedMediaFiles
        realtimeResults.clear()
        lastProgressLog.set("")
        logRenderJob?.cancel()
        logRenderScheduled.set(false)
        setState {
            copy(
                isConverting = true,
                showProcessingPanel = true,
                progressVisible = true,
                progress = 0,
                progressStatus = string(R.string.task_preparing),
                logText = "",
                startEnabled = false
            )
        }
        conversionJob = taskController.launch(viewModelScope) { task ->
            task.onCancel { mediaOperation?.cancel() }
            try {
                showProgress(string(R.string.task_preparing), 0)
                appendRuntimeLog(string(R.string.speech_to_subtitle_log_start))
                appendRuntimeLog(string(R.string.task_pending_files, files.size))
                appendRuntimeLog(string(R.string.task_output_format, selectedFormat))
                appendRuntimeLog(string(R.string.speech_to_subtitle_log_source_language, selectedLanguage))
                appendRuntimeLog(
                    string(
                        R.string.task_output_directory,
                        currentState.outputDirectory ?: string(R.string.activity_auto_timestamp_text_07)
                    )
                )
                appendSpeechModelConfig()

                var successCount = 0
                val failedFiles = mutableListOf<String>()
                val writtenOutputBaseNames = mutableSetOf<String>()
                val autoTranslateFiles = mutableListOf<Uri>()

                for ((index, file) in files.withIndex()) {
                    if (isCancelled) break

                    val outputBaseName = file.fileName.substringBeforeLast(".").lowercase()
                    val shouldOverwrite = overwriteOutput && writtenOutputBaseNames.add(outputBaseName)
                    val result = convertFile(file, index + 1, files.size, shouldOverwrite)
                    if (isCancelled) break

                    result.onSuccess { outputUri ->
                        successCount++
                        autoTranslateFiles += outputUri
                    }
                        .onFailure { error ->
                            failedFiles.add(file.fileName)
                            appendRuntimeLog(
                                string(
                                    R.string.task_file_failed_with_reason,
                                    error.message ?: string(R.string.error_unknown)
                                )
                            )
                        }
                }

                if (!isCancelled) {
                    showProgress(string(R.string.task_all_done), 100)
                    val summary = if (failedFiles.isEmpty()) {
                        string(R.string.speech_to_subtitle_summary, successCount, files.size)
                    } else {
                        string(R.string.speech_to_subtitle_summary_with_failures, successCount, files.size, failedFiles.size)
                    }
                    appendRuntimeLog(summary)
                    toast(summary, Toast.LENGTH_LONG)
                    if (currentState.addToAutoTranslate && autoTranslateFiles.isNotEmpty()) {
                        sendEvent(SpeechToSubtitleEvent.OpenAutoTranslate(autoTranslateFiles.toList()))
                    }
                }
            } catch (e: Exception) {
                if (!isCancelled) {
                    task.recordFailure(e)
                    showError(e.message)
                }
            } finally {
                mediaOperation?.cancel()
                mediaOperation = null
                setState { copy(isConverting = false, progressVisible = false) }
                updateStartButtonState()
            }
        }
    }

    /**
     * 取消转换：先弹出确认框
     */
    fun confirmCancelConversion() {
        if (!isConverting) return
        setState { copy(dialog = SpeechToSubtitleDialogUi.CancelConfirmation) }
    }

    fun onConfirmCancel() {
        dismissDialog()
        cancelConversion()
    }

    private fun cancelConversion() {
        if (!isConverting) return
        taskController.cancel()
        mediaOperation?.cancel()
        toast(R.string.task_cancelled)
    }

    /**
     * 复制 URI 到缓存目录
     */
    private fun copyUriToCache(uri: Uri, fileName: String, taskCacheDir: File): File? {
        return try {
            val cacheFile = File(taskCacheDir, "input_$fileName")
            app.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            cacheFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 转换为 16kHz PCM WAV
     */
    private suspend fun convertToPcm(inputFile: File, taskCacheDir: File): File? {
        return try {
            val outputFile = File(taskCacheDir, "${inputFile.nameWithoutExtension}_16k.wav")
            if (outputFile.exists()) outputFile.delete()

            if (mediaOperation?.convertToPcm(inputFile, outputFile, PcmFormat.SPEECH_WAV_16K_MONO) == true && outputFile.exists()) {
                outputFile
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 生成字幕内容
     */
    private fun generateSubtitle(segments: List<WhisperRecognizer.SubtitleSegment>): String {
        val entries = segments.mapIndexed { index, segment ->
            SubtitleEntry(
                index = index + 1,
                startTime = segment.startTime,
                endTime = segment.endTime,
                text = segment.text
            )
        }

        return when (selectedFormat) {
            "SRT" -> SubtitleParser.toSRT(entries)
            "LRC" -> SubtitleParser.toLRC(entries)
            "TXT" -> SubtitleParser.toTXT(entries)
            else -> SubtitleParser.toSRT(entries)
        }
    }

    private fun shouldUseVad(): Boolean {
        return !shouldUseTokenTimestampExperiment() &&
            settingsManager.isAsrVadTimestampEnabled(modelType) &&
            shouldPrepareTimeline()
    }

    private fun shouldPrepareTimeline(): Boolean {
        if (selectedFormat != "TXT") {
            return true
        }
        return !currentState.disableVadForTxt
    }

    private fun updateVadOptionState() {
        val isTxt = selectedFormat == "TXT"
        setState { copy(isTxtSelected = isTxt) }
    }

    /**
     * 保存字幕文件
     */
    private fun saveSubtitleFile(
        sourceFileName: String,
        content: String,
        overwrite: Boolean,
        segmentCount: Int
    ): String {
        val outputDir = outputDirUri ?: throw IllegalStateException(string(R.string.output_directory_not_set))
        val baseFileName = sourceFileName.substringBeforeLast(".")
        val extension = selectedFormat.lowercase()
        val fileName = SubtitleOutputWriter.writeText(app, outputDir, baseFileName, extension, content, overwrite)
        toast(R.string.speech_to_subtitle_toast_saved, fileName, segmentCount, duration = Toast.LENGTH_LONG)
        return fileName
    }

    private suspend fun convertFile(
        file: SelectedMediaFile,
        fileIndex: Int,
        fileCount: Int,
        overwriteOutput: Boolean
    ): Result<Uri> {
        val taskCacheDir = File(
            app.cacheDir,
            "speech_to_subtitle_${System.currentTimeMillis()}_${System.nanoTime()}"
        ).apply { mkdirs() }
        val progressPrefix = string(R.string.task_file_progress_prefix, fileIndex, fileCount)

        return try {
            showProgress(string(R.string.speech_to_subtitle_progress_preparing, progressPrefix), 0)
            appendRuntimeLog(string(R.string.speech_to_subtitle_log_file_start, progressPrefix, file.fileName))
            appendRuntimeLog(string(R.string.speech_to_subtitle_log_copy_to_cache))
            val cachedFile = withContext(Dispatchers.IO) {
                copyUriToCache(file.uri, file.fileName, taskCacheDir)
            } ?: return Result.failure(Exception(string(R.string.speech_to_subtitle_error_copy_failed)))
            appendRuntimeLog(
                string(R.string.speech_to_subtitle_log_cache_file, cachedFile.name, ByteSizeFormat.format(cachedFile.length()))
            )

            if (isCancelled) return userCancelled()

            showProgress(string(R.string.speech_to_subtitle_progress_extract, progressPrefix), 5)
            appendRuntimeLog(string(R.string.speech_to_subtitle_log_extract_pcm))
            val pcmFile = withContext(Dispatchers.IO) {
                convertToPcm(cachedFile, taskCacheDir)
            } ?: return Result.failure(Exception(string(R.string.speech_to_subtitle_error_audio_convert_failed)))
            appendRuntimeLog(
                string(R.string.speech_to_subtitle_log_pcm_file, pcmFile.name, ByteSizeFormat.format(pcmFile.length()))
            )

            if (isCancelled) return userCancelled()

            showProgress(string(R.string.speech_to_subtitle_progress_recognize, progressPrefix), 10)
            val selectedLanguage = this.selectedLanguage
            val timestampExperiment = isTokenTimestampExperimentConfigured()
            val recognitionResult = if (timestampExperiment) {
                recognizeWithTokenTimestampTimeline(
                    pcmFile = pcmFile,
                    selectedLanguage = selectedLanguage,
                    progressPrefix = progressPrefix
                )
            } else {
                appendRuntimeLog(string(R.string.speech_to_subtitle_log_init_recognizer, currentAsrModelDisplayName()))
                if (modelType == SettingsManager.ASR_MODEL_SENSEVOICE) {
                    appendSenseVoiceLanguage(selectedLanguage)
                }
                val recognizer = speechRecognitionService.createRecognizer(
                    encoderPath = encoderPath,
                    decoderPath = decoderPath,
                    joinerPath = joinerPath,
                    tokensPath = tokensPath,
                    vadModelPath = getActiveVadModelPath(),
                    useVad = shouldUseVad(),
                    language = selectedLanguage,
                    contentResolver = app.contentResolver,
                    context = app,
                    modelType = modelType
                )
                withContext(Dispatchers.IO) {
                    recognizer.recognize(
                        audioFile = pcmFile,
                        progressCallback = { progress, status, segmentResult ->
                            showProgress(
                                string(R.string.speech_to_subtitle_progress_status, progressPrefix, status),
                                10 + (progress * 0.8).toInt()
                            )
                            segmentResult?.let(::appendRecognizedSegment)
                        },
                        isCancelled = { isCancelled }
                    )
                }
            }

            if (isCancelled) return userCancelled()
            val segments = recognitionResult.getOrElse { return Result.failure(it) }
            if (segments.isEmpty()) {
                return Result.failure(Exception(string(R.string.speech_to_subtitle_error_no_speech)))
            }

            showProgress(string(R.string.speech_to_subtitle_progress_generate, progressPrefix), 95)
            appendRuntimeLog(string(R.string.speech_to_subtitle_log_recognized, segments.size))
            val subtitleContent = generateSubtitle(segments)
            val outputFileName = saveSubtitleFile(file.fileName, subtitleContent, overwriteOutput, segments.size)
            appendRuntimeLog(string(R.string.speech_to_subtitle_log_saved, outputFileName))
            showProgress(string(R.string.speech_to_subtitle_progress_done, progressPrefix), 100)
            Result.success(resolveOutputFileUri(outputFileName))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                taskCacheDir.deleteRecursively()
            }
        }
    }

    private fun <T> userCancelled(): Result<T> =
        Result.failure(Exception(string(R.string.speech_to_subtitle_error_user_cancelled)))

    private suspend fun recognizeWithTokenTimestampTimeline(
        pcmFile: File,
        selectedLanguage: String,
        progressPrefix: String
    ): Result<List<WhisperRecognizer.SubtitleSegment>> {
        val timelineName = string(
            if (modelType == SettingsManager.ASR_MODEL_QWEN3_ASR) {
                R.string.speech_to_subtitle_timeline_forced_alignment
            } else {
                R.string.speech_to_subtitle_timeline_experiment
            }
        )
        appendRuntimeLog(string(R.string.speech_to_subtitle_log_timeline_init, timelineName, currentAsrModelDisplayName()))
        if (modelType == SettingsManager.ASR_MODEL_SENSEVOICE) {
            appendSenseVoiceLanguage(selectedLanguage)
        }
        val timelineResult = withContext(Dispatchers.IO) {
            TokenTimestampGenerator(app).generateSegments(
                pcmFile = pcmFile,
                language = selectedLanguage,
                progressCallback = { progress, status ->
                    showProgress(
                        string(R.string.speech_to_subtitle_progress_timeline, progressPrefix, timelineName, status),
                        10 + (progress * 0.4).toInt()
                    )
                },
                isCancelled = { isCancelled }
            )
        }
        if (isCancelled) return userCancelled()

        val timelineSegments = timelineResult.getOrElse { return Result.failure(it) }
        appendRuntimeLog(string(R.string.speech_to_subtitle_log_timeline_done, timelineName, timelineSegments.size))
        return Result.success(
            timelineSegments.map { segment ->
                WhisperRecognizer.SubtitleSegment(
                    startTime = segment.startTime,
                    endTime = segment.endTime,
                    text = segment.text
                )
            }.also { segments ->
                segments.forEach(::appendRecognizedSegment)
            }
        )
    }

    private fun appendSenseVoiceLanguage(language: String) {
        appendRuntimeLog(
            string(R.string.speech_to_subtitle_log_sensevoice_language, language, senseVoiceLanguageCode(language))
        )
    }

    /**
     * 显示进度（任意线程）
     */
    private fun showProgress(status: String, progress: Int) {
        setState {
            copy(
                showProcessingPanel = true,
                progressVisible = true,
                progress = progress.coerceIn(0, 100),
                progressStatus = status
            )
        }
        if (lastProgressLog.getAndSet(status) != status) {
            appendRuntimeLog(status)
        }
    }

    /**
     * 显示错误
     */
    private fun showError(message: String?) {
        setState {
            copy(dialog = SpeechToSubtitleDialogUi.Error(message ?: string(R.string.error_unknown)))
        }
    }

    private fun appendRuntimeLog(message: String) {
        realtimeResults.append(message)
        scheduleLogRender()
    }

    private fun appendRecognizedSegment(segment: WhisperRecognizer.SubtitleSegment) {
        realtimeResults.appendRaw(
            "\n[${formatSubtitleTime(segment.startTime)} --> ${formatSubtitleTime(segment.endTime)}]\n${segment.text}"
        )
        scheduleLogRender()
    }

    /** Throttles log rendering to one state update per [LOG_RENDER_INTERVAL_MS]; thread-safe. */
    private fun scheduleLogRender() {
        if (!logRenderScheduled.compareAndSet(false, true)) return
        logRenderJob = viewModelScope.launch {
            delay(LOG_RENDER_INTERVAL_MS)
            logRenderScheduled.set(false)
            val text = realtimeResults.text()
            setState { copy(logText = text) }
        }
    }

    private fun appendConfigLog(message: String) = appendRuntimeLog("  $message")

    private fun appendConfigLog(name: String, value: String) =
        appendConfigLog(string(R.string.speech_to_subtitle_config_named_path, name, value))

    private fun enabledText(enabled: Boolean, detail: () -> String): String =
        if (enabled) detail() else string(R.string.speech_to_subtitle_disabled)

    private fun dynamicPaddingText(): String =
        enabledText(settingsManager.isSpeechVadDynamicPaddingEnabled()) {
            string(R.string.speech_to_subtitle_config_dynamic_padding_enabled)
        }

    private fun hotwordsText(): String =
        if (settingsManager.isSpeechHotwordsEnabled()) {
            string(R.string.speech_to_subtitle_config_hotwords_enabled, settingsManager.getSpeechHotwordsScore().toString())
        } else {
            string(R.string.speech_to_subtitle_not_enabled)
        }

    private fun appendSpeechModelConfig() {
        appendRuntimeLog(string(R.string.speech_to_subtitle_config_header))
        appendConfigLog(string(R.string.speech_to_subtitle_config_type, currentAsrModelDisplayName()))
        val singleFileModel = modelType == SettingsManager.ASR_MODEL_SENSEVOICE ||
            modelType == SettingsManager.ASR_MODEL_PARAKEET_CTC_JA
        if (singleFileModel) {
            appendConfigLog(string(R.string.speech_to_subtitle_config_model, displayModelPath(encoderPath)))
        } else {
            appendConfigLog("Encoder", displayModelPath(encoderPath))
            appendConfigLog("Decoder", displayModelPath(decoderPath))
        }
        if (modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT) {
            appendConfigLog("Joiner", displayModelPath(joinerPath))
        }
        if (modelType == SettingsManager.ASR_MODEL_QWEN3_ASR) {
            appendConfigLog("Conv Frontend", displayModelPath(joinerPath))
            appendConfigLog(string(R.string.speech_to_subtitle_config_qwen_chunking))
        }
        appendConfigLog(
            if (modelType == SettingsManager.ASR_MODEL_QWEN3_ASR) "Tokenizer" else "Tokens",
            displayModelPath(tokensPath)
        )
        if (modelType != SettingsManager.ASR_MODEL_SENSEVOICE) {
            appendConfigLog(string(R.string.speech_to_subtitle_config_threads, settingsManager.getSpeechWhisperThreads()))
        }
        if (isTokenTimestampExperimentConfigured()) {
            val qwenForcedAlignment = modelType == SettingsManager.ASR_MODEL_QWEN3_ASR
            val timelineName = string(
                if (qwenForcedAlignment) R.string.speech_to_subtitle_forced_alignment
                else R.string.speech_to_subtitle_timeline_experiment
            )
            appendConfigLog(string(R.string.speech_to_subtitle_config_vad_disabled_by_timeline, timelineName))
            if (settingsManager.isSpeechFixedVadSegmentationEnabled(modelType)) {
                appendConfigLog(string(R.string.speech_to_subtitle_config_fixed_vad_cut))
            }
            appendConfigLog(
                string(
                    R.string.speech_to_subtitle_config_timeline_model,
                    timelineName,
                    TokenTimestampGenerator.modelDisplayName(settingsManager),
                    displayModelPath(tokenTimestampModelPath())
                )
            )
            val tokensText = displayModelPath(tokenTimestampTokensPath())
            appendConfigLog(
                if (qwenForcedAlignment) string(R.string.speech_to_subtitle_config_named_path, "Tokenizer", tokensText)
                else string(R.string.speech_to_subtitle_config_experiment_tokens, tokensText)
            )
            appendConfigLog(
                string(
                    R.string.speech_to_subtitle_config_merge_segments,
                    enabledText(settingsManager.isSpeechTokenTimestampMergeEnabled()) {
                        string(R.string.speech_to_subtitle_enabled_with, settingsManager.speechTokenTimestampMergeSummary())
                    }
                )
            )
            appendConfigLog(string(R.string.speech_to_subtitle_config_dynamic_padding, dynamicPaddingText()))
            if (
                modelType == SettingsManager.ASR_MODEL_WHISPER ||
                modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT
            ) {
                appendConfigLog(string(R.string.speech_to_subtitle_config_hotwords, hotwordsText()))
            }
            return
        }
        val useVad = shouldUseVad()
        val fixedSegmentText = if (
            modelType == SettingsManager.ASR_MODEL_SENSEVOICE &&
            settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU
        ) {
            string(R.string.speech_to_subtitle_config_fixed_segment_npu, settingsManager.getSenseVoiceNpuDurationSeconds())
        } else if (modelType == SettingsManager.ASR_MODEL_QWEN3_ASR) {
            string(R.string.speech_to_subtitle_config_fixed_segment_qwen)
        } else {
            string(R.string.speech_to_subtitle_config_fixed_segment, settingsManager.getSpeechFixedSegmentSeconds())
        }
        appendConfigLog(
            string(
                R.string.speech_to_subtitle_config_vad,
                if (useVad) string(R.string.speech_to_subtitle_enabled)
                else string(R.string.speech_to_subtitle_config_vad_disabled_with, fixedSegmentText)
            )
        )
        if (!useVad && settingsManager.isSpeechFixedVadSegmentationEnabled(modelType)) {
            appendConfigLog(string(R.string.speech_to_subtitle_config_fixed_vad_cut))
        }
        if (useVad) {
            appendConfigLog(
                string(
                    R.string.speech_to_subtitle_config_vad_model,
                    if (settingsManager.isVadUseBuiltInModel()) string(R.string.speech_to_subtitle_config_vad_builtin)
                    else displayModelPath(vadModelPath)
                )
            )
            appendConfigLog(
                string(
                    R.string.speech_to_subtitle_config_vad_params,
                    settingsManager.getVadThreshold().toString(),
                    settingsManager.getVadMinSilenceDuration().toString(),
                    settingsManager.getVadMinSpeechDuration().toString(),
                    settingsManager.getVadMaxSpeechDuration().toString()
                )
            )
            appendConfigLog(string(R.string.speech_to_subtitle_config_dynamic_padding, dynamicPaddingText()))
            appendConfigLog(
                string(
                    R.string.speech_to_subtitle_config_vad_merge,
                    enabledText(settingsManager.isSpeechVadMergeEnabled()) {
                        string(R.string.speech_to_subtitle_config_merge_gap, settingsManager.getSpeechVadMergeGapMs())
                    }
                )
            )
            val secondaryVadMode = settingsManager.getSpeechSecondaryVadMode()
            val secondaryVadText = when (secondaryVadMode) {
                SettingsManager.SECONDARY_VAD_MODE_UNCOVERED -> string(R.string.speech_to_subtitle_config_secondary_vad_uncovered)
                SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS -> string(R.string.speech_to_subtitle_config_secondary_vad_within)
                else -> string(R.string.speech_to_subtitle_disabled)
            }
            appendConfigLog(string(R.string.speech_to_subtitle_config_secondary_vad, secondaryVadText))
            if (secondaryVadMode != SettingsManager.SECONDARY_VAD_MODE_NONE) {
                appendConfigLog(
                    string(
                        R.string.speech_to_subtitle_config_secondary_vad_params,
                        settingsManager.getSpeechSecondaryVadThreshold().toString(),
                        settingsManager.getSpeechSecondaryVadMinSilenceDuration().toString(),
                        settingsManager.getSpeechSecondaryVadMinSpeechDuration().toString(),
                        settingsManager.getSpeechSecondaryVadMaxSpeechDuration().toString()
                    )
                )
                appendConfigLog(
                    string(
                        R.string.speech_to_subtitle_config_secondary_vad_merge,
                        enabledText(settingsManager.isSpeechSecondaryVadMergeEnabled()) {
                            string(R.string.speech_to_subtitle_config_merge_gap, settingsManager.getSpeechSecondaryVadMergeGapMs())
                        }
                    )
                )
            }
        }
        if (modelType == SettingsManager.ASR_MODEL_WHISPER || modelType == SettingsManager.ASR_MODEL_PARAKEET_TDT) {
            appendConfigLog(string(R.string.speech_to_subtitle_config_hotwords, hotwordsText()))
        }
    }

    private fun isCurrentAsrModelComplete(): Boolean {
        if (encoderPath.isBlank() || tokensPath.isBlank()) return false
        return when (modelType) {
            SettingsManager.ASR_MODEL_SENSEVOICE,
            SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> true
            SettingsManager.ASR_MODEL_PARAKEET_TDT -> decoderPath.isNotBlank() && joinerPath.isNotBlank()
            SettingsManager.ASR_MODEL_QWEN3_ASR ->
                decoderPath.isNotBlank() && joinerPath.isNotBlank() && tokensPath.isNotBlank()
            else -> decoderPath.isNotBlank()
        }
    }

    private fun currentAsrModelDisplayName(): String = when (modelType) {
        SettingsManager.ASR_MODEL_SENSEVOICE -> "SenseVoice"
        SettingsManager.ASR_MODEL_PARAKEET_TDT -> "Parakeet TDT 0.6B v3"
        SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> string(R.string.speech_to_subtitle_model_parakeet_ctc_ja)
        SettingsManager.ASR_MODEL_QWEN3_ASR -> "Qwen3-ASR"
        else -> "Whisper"
    }

    private fun isTokenTimestampExperimentConfigured(): Boolean {
        return shouldUseTokenTimestampExperiment() &&
            TokenTimestampGenerator.isConfigured(app)
    }

    private fun resolveOutputFileUri(fileName: String): Uri {
        val directoryUri = outputDirUri ?: throw IllegalStateException(string(R.string.output_directory_not_set))
        if (directoryUri.scheme == "file") {
            val path = directoryUri.path
                ?: throw IllegalStateException(string(R.string.speech_to_subtitle_error_output_dir_invalid))
            return Uri.fromFile(File(path, fileName))
        }
        return DocumentFile.fromTreeUri(app, directoryUri)
            ?.findFile(fileName)
            ?.uri
            ?: throw IllegalStateException(string(R.string.speech_to_subtitle_error_output_file_not_found))
    }

    private fun shouldUseTokenTimestampExperiment(): Boolean =
        settingsManager.isSpeechTokenTimestampEnabled() &&
            modelType != SettingsManager.ASR_MODEL_WHISPER &&
            (modelType != SettingsManager.ASR_MODEL_QWEN3_ASR ||
                TokenTimestampGenerator.isQwen3ForcedAlignerConfigured(app, settingsManager)) &&
            shouldPrepareTimeline()

    private fun tokenTimestampModelPath(): String =
        TokenTimestampGenerator.modelPath(settingsManager)

    private fun tokenTimestampTokensPath(): String =
        TokenTimestampGenerator.tokensPath(settingsManager)

    private fun getActiveVadModelPath(): String {
        return if (settingsManager.isVadUseBuiltInModel()) "" else vadModelPath
    }

    private fun displayModelPath(path: String): String {
        return if (path.isBlank()) string(R.string.speech_to_subtitle_path_not_set) else Uri.parse(path).lastPathSegment ?: path
    }

    // 与 SettingsManager.TRANSCRIPTION_LANGUAGE_OPTIONS 的取值对应，非展示文本
    private fun senseVoiceLanguageCode(language: String): String = when (language) {
        "中文" -> "zh"
        "英语" -> "en"
        "日语" -> "ja"
        "韩语" -> "ko"
        else -> "auto"
    }

    private fun formatSubtitleTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = timeMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    override fun onCleared() {
        // Page is finishing for real (not a configuration change): stop native work.
        taskController.cancel()
        mediaOperation?.cancel()
        conversionJob?.cancel()
        logRenderJob?.cancel()
        super.onCleared()
    }
}
