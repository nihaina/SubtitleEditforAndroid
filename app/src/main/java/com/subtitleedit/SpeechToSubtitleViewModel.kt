package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.subtitleedit.feature.ui.SpeechToSubtitleDialogUi
import com.subtitleedit.feature.ui.SpeechToSubtitleFileUi
import com.subtitleedit.feature.ui.SpeechToSubtitleUiState
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.PickedUriAccess
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SourceOutputDirectory
import com.subtitleedit.util.SubtitleOutputWriter
import com.subtitleedit.util.TokenTimestampGenerator
import com.subtitleedit.util.UriDisplayName
import com.subtitleedit.work.SpeechToSubtitleFile
import com.subtitleedit.work.SpeechToSubtitleRequest
import com.subtitleedit.work.SpeechToSubtitleTaskStore
import com.subtitleedit.work.SpeechToSubtitleWorker
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    application: Application,
    private val savedStateHandle: SavedStateHandle,
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
        const val KEY_WORK_ID = "speech_to_subtitle_work_id"
    }

    private data class SelectedMediaFile(
        val uri: Uri,
        val fileName: String,
        val fileSize: Long?
    )

    private val settingsManager = SettingsManager.getInstance(application)
    private val scheduler get() = dependencies.taskWorkScheduler

    private var selectedMediaFiles: List<SelectedMediaFile> = emptyList()
    private var encoderPath: String = ""
    private var decoderPath: String = ""
    private var joinerPath: String = ""
    private var tokensPath: String = ""
    private var modelType: String = SettingsManager.ASR_MODEL_WHISPER
    private var vadModelPath: String = ""
    private var workId: UUID? = null
    private var workObserver: Job? = null
    private var restoringWork = true
    private var cancelWhenEnqueued = false
    private var resumed = false
    private var pendingResult: CompletedTask? = null

    /** Current output directory, used as the picker's initial location. */
    var outputDirUri: Uri? = null
        private set

    private data class CompletedTask(
        val store: SpeechToSubtitleTaskStore,
        val status: WorkInfo.State,
        val summary: String?,
        val error: String?,
        val translationFiles: List<Uri>,
    )

    private val isConverting: Boolean get() = currentState.isConverting

    private val selectedFormat: String
        get() = currentState.selectedFormat.ifBlank { formatOptions.first().id }

    private val selectedLanguage: String
        get() = currentState.selectedLanguage.ifBlank { languageOptions.first().id }

    init {
        setState { copy(saveToSourceDirectory = settingsManager.isSaveToSourceDirectoryEnabled(OUTPUT_DIRECTORY_KEY)) }
        loadSavedModel()
        restoreWork()
    }

    fun onResume() {
        resumed = true
        if (!isConverting) loadSavedModel(resetOutputDirectory = false)
        deliverResult()
    }

    fun onPause() {
        resumed = false
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

    fun setSaveToSourceDirectory(enabled: Boolean) {
        settingsManager.setSaveToSourceDirectoryEnabled(OUTPUT_DIRECTORY_KEY, enabled)
        setState { copy(saveToSourceDirectory = enabled) }
    }

    fun dismissDialog() = setState { copy(dialog = null) }

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
        if (isConverting || currentState.dialog == SpeechToSubtitleDialogUi.OutputConflict) return
        selectedMediaFiles = uris.distinct().map { uri ->
            SelectedMediaFile(uri, UriDisplayName.of(app, uri, string(R.string.unknown_file)), fileSize(uri))
        }
        publishSelectedFiles()
    }

    fun removeFile(key: String) {
        if (isConverting || currentState.dialog == SpeechToSubtitleDialogUi.OutputConflict) return
        selectedMediaFiles = selectedMediaFiles.filterNot { it.uri.toString() == key }
        publishSelectedFiles()
    }

    private fun publishSelectedFiles() {
        val files = selectedMediaFiles.map { file ->
            SpeechToSubtitleFileUi(
                key = file.uri.toString(),
                fileName = file.fileName,
                fileSizeLabel = file.fileSize?.let(FileUtils::formatFileSize) ?: string(R.string.file_size_unknown)
            )
        }
        setState { copy(selectedFiles = files) }
        updateStartButtonState()
    }

    private fun fileSize(uri: Uri): Long? = runCatching {
        PickedUriAccess.file(uri)?.let { file ->
            return@runCatching file.takeIf { it.isFile }?.length()
        }
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) {
                cursor.getLong(column).takeIf { it >= 0L }
            } else null
        }
    }.getOrNull()

    /**
     * 处理选择的输出目录
     */
    fun selectOutputDirectory(uri: Uri) {
        try {
            PickedUriAccess.retainPermission(app,
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
                com.subtitleedit.util.ModelDirectoryManager.softwareDirectory(),
                "Convert"
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
                startEnabled = !restoringWork && !isConverting && selectedMediaFiles.isNotEmpty() && hasAsrModel,
            )
        }
    }

    /**
     * 开始转换
     */
    fun start() {
        if (restoringWork || isConverting) return
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

        val hasConflict = runCatching {
            selectedMediaFiles.any { file ->
                SubtitleOutputWriter.exists(app, outputDirectoryFor(file.uri, outputDir), file.fileName.substringBeforeLast("."), extension)
            }
        }.getOrElse { error ->
            toast(R.string.save_to_source_folder_failed, error.message.orEmpty(), duration = Toast.LENGTH_LONG)
            return
        }
        if (hasConflict) {
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
        if (restoringWork || isConverting) return
        val outputDirectory = outputDirUri ?: return
        val saveToSource = currentState.saveToSourceDirectory
        val files = try {
            selectedMediaFiles.map { file ->
                SpeechToSubtitleFile(file.uri, file.fileName, file.fileSize, outputDirectoryFor(file.uri, outputDirectory))
            }
        } catch (error: Exception) {
            toast(R.string.save_to_source_folder_failed, error.message.orEmpty(), duration = Toast.LENGTH_LONG)
            return
        }
        val request = SpeechToSubtitleRequest(
            files = files,
            language = selectedLanguage,
            format = selectedFormat,
            modelType = modelType,
            encoderPath = encoderPath,
            decoderPath = decoderPath,
            joinerPath = joinerPath,
            tokensPath = tokensPath,
            vadModelPath = if (settingsManager.isVadUseBuiltInModel()) "" else vadModelPath,
            forcedAlignerPath = settingsManager.getQwen3ForcedAlignerPath(),
            useVad = shouldUseVad(),
            useTokenTimeline = shouldUseTokenTimestampExperiment(),
            saveToSourceDirectory = saveToSource,
            outputDirectory = outputDirectory,
            overwriteOutput = overwriteOutput,
            addToAutoTranslate = currentState.addToAutoTranslate,
            disableVadForTxt = currentState.disableVadForTxt,
        )
        pendingResult = null
        workId = null
        cancelWhenEnqueued = false
        setState {
            copy(
                isConverting = true,
                showProcessingPanel = true,
                progressVisible = true,
                progress = 0,
                progressStatus = string(R.string.speech_to_subtitle_queued),
                logText = "",
                startEnabled = false,
            )
        }
        viewModelScope.launch {
            try {
                val id = withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        files.forEach { file ->
                            PickedUriAccess.retainPermission(app, file.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    val enqueuedId = scheduler.enqueueSpeechToSubtitle(request)
                    if (cancelWhenEnqueued) scheduler.cancel(enqueuedId)
                    enqueuedId
                }
                observeWork(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message)
                finishWork()
            }
        }
    }

    private fun restoreWork() {
        viewModelScope.launch {
            try {
                val preferredId = savedStateHandle.get<String>(KEY_WORK_ID)?.let(UUID::fromString)
                scheduler.findSpeechToSubtitleWork(preferredId)?.let(::observeWork)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message)
            } finally {
                restoringWork = false
                updateStartButtonState()
            }
        }
    }

    private fun observeWork(id: UUID) {
        workObserver?.cancel()
        workId = id
        savedStateHandle[KEY_WORK_ID] = id.toString()
        setState { copy(isConverting = true, startEnabled = false) }
        workObserver = viewModelScope.launch {
            val store = SpeechToSubtitleTaskStore(app, id)
            try {
                val request = withContext(Dispatchers.IO) { store.readRequest() }
                restoreSelection(request)
                scheduler.observeSpeechToSubtitle(id).first { info ->
                    if (info == null) return@first true
                    updateWorkProgress(info, store)
                    if (info.state.isFinished) prepareResult(info, request, store)
                    info.state.isFinished
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message)
            } finally {
                if (workId == id) finishWork()
            }
        }
    }

    private fun restoreSelection(request: SpeechToSubtitleRequest) {
        outputDirUri = request.outputDirectory
        selectedMediaFiles = request.files.map { SelectedMediaFile(it.uri, it.fileName, it.fileSize) }
        setState {
            copy(
                outputDirectory = DirectoryDisplayPath.fromUri(app, request.outputDirectory),
                saveToSourceDirectory = request.saveToSourceDirectory,
                selectedLanguage = request.language,
                selectedFormat = request.format,
                isTxtSelected = request.format == "TXT",
                disableVadForTxt = request.disableVadForTxt,
                addToAutoTranslate = request.addToAutoTranslate,
            )
        }
        publishSelectedFiles()
    }

    private suspend fun updateWorkProgress(info: WorkInfo, store: SpeechToSubtitleTaskStore) {
        val data = if (info.state.isFinished) info.outputData else info.progress
        val status = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> string(R.string.speech_to_subtitle_queued)
            WorkInfo.State.RUNNING -> data.getString(SpeechToSubtitleWorker.KEY_MESSAGE) ?: string(R.string.task_preparing)
            WorkInfo.State.SUCCEEDED -> string(R.string.task_all_done)
            WorkInfo.State.FAILED -> data.getString(SpeechToSubtitleWorker.KEY_ERROR)
                ?: data.getString(SpeechToSubtitleWorker.KEY_SUMMARY)
                ?: string(R.string.error_unknown)
            WorkInfo.State.CANCELLED -> string(R.string.task_cancelled)
        }
        val log = withContext(Dispatchers.IO) { store.readLog() }
        setState {
            copy(
                showProcessingPanel = true,
                progressVisible = !info.state.isFinished,
                progress = data.getInt(SpeechToSubtitleWorker.KEY_PROGRESS, 0),
                progressStatus = status,
                logText = log,
            )
        }
    }

    private suspend fun prepareResult(
        info: WorkInfo,
        request: SpeechToSubtitleRequest,
        store: SpeechToSubtitleTaskStore,
    ) {
        pendingResult = withContext(Dispatchers.IO) {
            if (store.isAcknowledged()) return@withContext null
            val outputs = if (info.state == WorkInfo.State.SUCCEEDED && request.addToAutoTranslate) {
                request.files.indices.mapNotNull { store.readOutcome(it)?.output?.uri }
            } else emptyList()
            CompletedTask(
                store,
                info.state,
                info.outputData.getString(SpeechToSubtitleWorker.KEY_SUMMARY),
                info.outputData.getString(SpeechToSubtitleWorker.KEY_ERROR),
                outputs,
            )
        }
        deliverResult()
    }

    private fun deliverResult() {
        if (!resumed) return
        val result = pendingResult ?: return
        pendingResult = null
        when {
            result.status == WorkInfo.State.CANCELLED -> toast(R.string.task_cancelled)
            result.error != null -> showError(result.error)
            result.summary != null -> toast(result.summary, Toast.LENGTH_LONG)
            result.status == WorkInfo.State.FAILED -> showError(null)
        }
        if (result.translationFiles.isNotEmpty()) {
            sendEvent(SpeechToSubtitleEvent.OpenAutoTranslate(result.translationFiles))
        }
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { result.store.acknowledge() }
                    .onFailure { Log.e("SpeechToSubtitle", "Failed to acknowledge completed work", it) }
            }
        }
    }

    private fun finishWork() {
        setState {
            copy(
                isConverting = false,
                progressVisible = false,
                dialog = dialog.takeUnless { it == SpeechToSubtitleDialogUi.CancelConfirmation },
            )
        }
        updateStartButtonState()
    }

    fun confirmCancelConversion() {
        if (isConverting) setState { copy(dialog = SpeechToSubtitleDialogUi.CancelConfirmation) }
    }

    fun onConfirmCancel() {
        dismissDialog()
        if (!isConverting) return
        val id = workId
        if (id == null) {
            cancelWhenEnqueued = true
            return
        }
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { scheduler.cancel(id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message)
            }
        }
    }

    private fun shouldUseVad(): Boolean =
        !shouldUseTokenTimestampExperiment() &&
            settingsManager.isAsrVadTimestampEnabled(modelType) &&
            shouldPrepareTimeline()

    private fun shouldPrepareTimeline(): Boolean = selectedFormat != "TXT" || !currentState.disableVadForTxt

    private fun outputDirectoryFor(sourceUri: Uri, fallback: Uri): Uri =
        if (currentState.saveToSourceDirectory) SourceOutputDirectory.resolve(app, sourceUri) else fallback

    private fun updateVadOptionState() {
        setState { copy(isTxtSelected = selectedFormat == "TXT") }
    }

    private fun showError(message: String?) {
        setState { copy(dialog = SpeechToSubtitleDialogUi.Error(message ?: string(R.string.error_unknown))) }
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

    private fun isTokenTimestampExperimentConfigured(): Boolean {
        return shouldUseTokenTimestampExperiment() &&
            TokenTimestampGenerator.isConfigured(app)
    }

    private fun shouldUseTokenTimestampExperiment(): Boolean =
        settingsManager.isSpeechTokenTimestampEnabled() &&
            modelType != SettingsManager.ASR_MODEL_WHISPER &&
            (modelType != SettingsManager.ASR_MODEL_QWEN3_ASR ||
                TokenTimestampGenerator.isQwen3ForcedAlignerConfigured(app, settingsManager)) &&
            shouldPrepareTimeline()

}
