package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.subtitleedit.feature.ui.AutoTimestampDialog
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.nativebridge.NativeMediaOperation
import com.subtitleedit.nativebridge.PcmFormat
import com.subtitleedit.task.LongTaskController
import com.subtitleedit.util.ByteSizeFormat
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleOutputWriter
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TaskLogBuffer
import com.subtitleedit.util.TokenTimestampGenerator
import com.subtitleedit.util.UriDisplayName
import com.subtitleedit.util.VadTimestampGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Render state of the auto-timestamp page; fields map 1:1 to [com.subtitleedit.feature.ui.AutoTimestampScreen]. */
internal data class AutoTimestampUiState(
    val audioFilesText: String = "",
    val subtitleFileText: String = "",
    val outputDirectoryText: String = "",
    val secondaryProcessingEnabled: Boolean = false,
    val secondaryProcessingAvailable: Boolean = true,
    val secondaryProcessingHint: String = "",
    val outputFormat: String = "SRT",
    val isGenerating: Boolean = false,
    val canGenerate: Boolean = false,
    val statusText: String = "",
    val previewText: String = "",
    val dialog: AutoTimestampDialog = AutoTimestampDialog.NONE
)

/**
 * 自动打轴页面状态与任务 - 自动检测语音段并生成时间轴
 */
internal class AutoTimestampViewModel(
    application: Application
) : AppViewModel<AutoTimestampUiState, Nothing>(application, initialState(application)) {

    private enum class TimelineSource { VAD, TOKEN, ASR }

    private data class AsrModelPaths(
        val modelType: String,
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String
    ) {
        fun isComplete(): Boolean = encoder.isNotBlank() && tokens.isNotBlank() &&
            when (modelType) {
                SettingsManager.ASR_MODEL_SENSEVOICE,
                SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> true
                SettingsManager.ASR_MODEL_PARAKEET_TDT,
                SettingsManager.ASR_MODEL_QWEN3_ASR ->
                    decoder.isNotBlank() && joiner.isNotBlank()
                else -> decoder.isNotBlank()
            }
    }

    private data class SelectedMediaFile(
        val uri: Uri,
        val fileName: String
    )

    private data class MergeableSubtitleEntry(
        val entry: SubtitleEntry,
        val generatedPlaceholder: Boolean
    )

    private companion object {
        const val OUTPUT_DIRECTORY_KEY = "auto_timestamp"

        fun initialState(application: Application) = AutoTimestampUiState(
            audioFilesText = application.getString(R.string.activity_auto_timestamp_text_04),
            subtitleFileText = application.getString(R.string.activity_auto_timestamp_text_16),
            outputDirectoryText = application.getString(R.string.activity_auto_timestamp_text_07),
            previewText = application.getString(R.string.activity_auto_timestamp_text_11)
        )
    }

    private val settingsManager = SettingsManager.getInstance(application)
    private val taskController = LongTaskController(dependencies.taskStateStore, "auto-timestamp")
    private val isCancelled: Boolean get() = taskController.isCancellationRequested
    private val operationLog = TaskLogBuffer(maxChars = Int.MAX_VALUE)
    private var mediaOperation: NativeMediaOperation? = null
    private var generationJob: Job? = null
    private var selectedMediaFiles: List<SelectedMediaFile> = emptyList()
    private var selectedSubtitleFile: SelectedMediaFile? = null

    /** Current output directory, used as the picker's initial location. */
    var outputDirUri: Uri? = null
        private set

    /** Settings button target: VAD settings when VAD timeline is active, otherwise ASR settings. */
    val opensVadSettings: Boolean get() = settingsManager.isAsrVadTimestampEnabled()

    init {
        setupDefaultOutputDir()
        updateSecondaryProcessingAvailability()
    }

    /** Re-reads timeline settings; called on every resume. */
    fun refresh() = updateSecondaryProcessingAvailability()

    fun selectAudios(uris: List<Uri>) {
        try {
            selectedMediaFiles = emptyList()
            selectedMediaFiles = uris.map { uri ->
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                SelectedMediaFile(uri, fileName(uri))
            }
            val text = buildString {
                append(string(R.string.files_selected_header, selectedMediaFiles.size))
                selectedMediaFiles.forEachIndexed { index, file ->
                    append('\n').append(string(R.string.files_selected_item, index + 1, file.fileName))
                }
            }
            setState { copy(audioFilesText = text).withGenerateState() }
        } catch (e: Exception) {
            toast(R.string.auto_timestamp_select_files_failed, e.message.toString(), duration = Toast.LENGTH_LONG)
        }
    }

    fun selectSubtitle(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val fileName = fileName(uri)
            val extension = fileName.substringAfterLast('.', "").lowercase()
            if (extension !in setOf("srt", "lrc")) {
                toast(R.string.auto_timestamp_subtitle_type_required, duration = Toast.LENGTH_LONG)
                return
            }
            selectedSubtitleFile = SelectedMediaFile(uri, fileName)
            setState { copy(subtitleFileText = fileName).withGenerateState() }
        } catch (e: Exception) {
            toast(R.string.auto_timestamp_select_subtitle_failed, e.message.toString(), duration = Toast.LENGTH_LONG)
        }
    }

    fun selectOutputDirectory(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            outputDirUri = uri
            setState { copy(outputDirectoryText = DirectoryDisplayPath.fromUri(app, uri)) }
            settingsManager.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        } catch (e: Exception) {
            toast(R.string.directory_select_failed, e.message.toString(), duration = Toast.LENGTH_LONG)
        }
    }

    fun setSecondaryProcessingEnabled(enabled: Boolean) =
        setState { copy(secondaryProcessingEnabled = enabled).withGenerateState() }

    fun setOutputFormat(format: String) = setState { copy(outputFormat = format) }

    fun requestCancel() = setState { copy(dialog = AutoTimestampDialog.CANCEL_GENERATION) }

    fun dismissDialog() = setState { copy(dialog = AutoTimestampDialog.NONE) }

    /** Returns true when the page may close immediately. */
    fun requestBack(): Boolean {
        if (!currentState.isGenerating) return true
        setState { copy(dialog = AutoTimestampDialog.BACK_WHILE_GENERATING) }
        return false
    }

    /**
     * Confirms the cancel / back-while-generating dialog. Returns true when the
     * dialog was the back confirmation and the page should now close.
     */
    fun confirmCancel(): Boolean {
        val shouldLeave = currentState.dialog == AutoTimestampDialog.BACK_WHILE_GENERATING
        dismissDialog()
        cancelGeneration(showToast = !shouldLeave)
        return shouldLeave
    }

    fun resolveOutputConflict(overwrite: Boolean) {
        dismissDialog()
        generateTimestamps(overwriteOutput = overwrite)
    }

    fun generate() {
        if (selectedMediaFiles.isEmpty()) return
        if (currentState.secondaryProcessingEnabled) {
            if (selectedMediaFiles.size != 1) {
                toast(R.string.auto_timestamp_secondary_single_media)
                return
            }
            if (selectedSubtitleFile == null) {
                toast(R.string.auto_timestamp_secondary_subtitle_required)
                return
            }
        }
        val timelineSource = timelineSource()
        if (timelineSource == TimelineSource.ASR && !currentAsrModelPaths().isComplete()) {
            toast(R.string.auto_timestamp_asr_model_required)
            return
        }
        if (
            timelineSource == TimelineSource.TOKEN &&
            !TokenTimestampGenerator.isConfigured(app)
        ) {
            toast(
                if (settingsManager.getAsrModelType() == SettingsManager.ASR_MODEL_QWEN3_ASR) {
                    R.string.auto_timestamp_qwen3_models_required
                } else {
                    R.string.auto_timestamp_token_model_required
                },
                duration = Toast.LENGTH_LONG
            )
            return
        }
        if (
            timelineSource == TimelineSource.VAD &&
            !settingsManager.isVadUseBuiltInModel() &&
            settingsManager.getVadModelPath().isBlank()
        ) {
            toast(R.string.auto_timestamp_vad_model_required)
            return
        }
        val outputDir = outputDirUri ?: run {
            toast(R.string.auto_timestamp_output_directory_required)
            return
        }
        val extension = currentState.outputFormat.lowercase()

        if (selectedMediaFiles.any { file ->
                SubtitleOutputWriter.exists(
                    app,
                    outputDir,
                    outputSourceFileName(file).substringBeforeLast("."),
                    extension
                )
            }) {
            setState { copy(dialog = AutoTimestampDialog.OUTPUT_CONFLICT) }
            return
        }

        generateTimestamps(overwriteOutput = false)
    }

    private fun generateTimestamps(overwriteOutput: Boolean) {
        if (taskController.isRunning) return
        val outputDir = outputDirUri ?: run {
            toast(R.string.auto_timestamp_output_directory_required)
            return
        }
        val refinementSubtitle = selectedSubtitleFile.takeIf {
            currentState.secondaryProcessingEnabled
        }
        val files = selectedMediaFiles
        val format = currentState.outputFormat

        operationLog.clear()
        setState {
            copy(
                isGenerating = true,
                canGenerate = false,
                statusText = string(R.string.auto_timestamp_status_processing),
                previewText = ""
            )
        }
        appendOperationLog(string(R.string.auto_timestamp_log_start))
        appendOperationLog(string(R.string.task_pending_files, files.size))
        appendOperationLog(string(R.string.task_output_format, format))
        appendOperationLog(string(R.string.task_output_directory, currentState.outputDirectoryText))
        appendOperationLog(string(R.string.auto_timestamp_log_preprocess_config))
        if (refinementSubtitle != null) {
            appendOperationLog(string(R.string.auto_timestamp_log_secondary_on))
            appendOperationLog(string(R.string.auto_timestamp_log_reference_subtitle, refinementSubtitle.fileName))
        } else {
            appendOperationLog(string(R.string.auto_timestamp_log_secondary_off))
        }
        appendTimelineConfig(refinementSubtitle != null)
        mediaOperation?.cancel()
        mediaOperation = dependencies.nativeMediaEngine.openOperation()
        generationJob = taskController.launch(viewModelScope) { task ->
            task.onCancel { mediaOperation?.cancel() }
            try {
                var successCount = 0
                val failedFiles = mutableListOf<String>()
                val writtenOutputBaseNames = mutableSetOf<String>()

                for ((index, file) in files.withIndex()) {
                    if (isCancelled) break

                    val outputBaseName = outputSourceFileName(file, refinementSubtitle)
                        .substringBeforeLast(".")
                        .lowercase()
                    val shouldOverwrite = overwriteOutput && writtenOutputBaseNames.add(outputBaseName)
                    val result = generateTimestampForFile(
                        file,
                        index + 1,
                        files.size,
                        outputDir,
                        format,
                        shouldOverwrite,
                        refinementSubtitle
                    )
                    if (isCancelled) break

                    result.onSuccess { successCount++ }
                        .onFailure { error ->
                            failedFiles.add(file.fileName)
                            appendOperationLog(
                                string(
                                    R.string.task_file_failed_with_reason,
                                    error.message ?: string(R.string.error_unknown)
                                )
                            )
                        }
                }

                if (!isCancelled) {
                    val summary = if (failedFiles.isEmpty()) {
                        string(R.string.auto_timestamp_summary, successCount, files.size)
                    } else {
                        string(R.string.auto_timestamp_summary_with_failures, successCount, files.size, failedFiles.size)
                    }
                    setState { copy(statusText = summary) }
                    appendOperationLog(summary)
                    toast(summary, Toast.LENGTH_LONG)
                }

            } catch (e: Exception) {
                if (!isCancelled) task.recordFailure(e)
                val generating = currentState.isGenerating
                setState {
                    copy(
                        statusText = string(
                            if (generating) R.string.auto_timestamp_status_failed else R.string.task_cancelled
                        )
                    )
                }
                if (generating) {
                    toast(R.string.task_file_failed_with_reason, e.message.toString(), duration = Toast.LENGTH_LONG)
                }
            } finally {
                mediaOperation?.cancel()
                mediaOperation = null
                setState { copy(isGenerating = false).withGenerateState() }
                generationJob = null
            }
        }
        if (generationJob == null && !taskController.isRunning) {
            setState { copy(isGenerating = false).withGenerateState() }
        }
    }

    private fun cancelGeneration(showToast: Boolean = true) {
        if (!currentState.isGenerating) return
        taskController.cancel()
        mediaOperation?.cancel()
        setState { copy(statusText = string(R.string.auto_timestamp_status_cancelling)) }
        if (showToast) {
            toast(R.string.task_cancelled)
        }
    }

    private suspend fun generateTimestampForFile(
        file: SelectedMediaFile,
        fileIndex: Int,
        fileCount: Int,
        outputDir: Uri,
        format: String,
        overwriteOutput: Boolean,
        refinementSubtitle: SelectedMediaFile?
    ): Result<Unit> {
        val taskCacheDir = File(
            app.cacheDir,
            "auto_timestamp_${System.currentTimeMillis()}_${System.nanoTime()}"
        ).apply { mkdirs() }
        val progressPrefix = string(R.string.task_file_progress_prefix, fileIndex, fileCount)

        return try {
            showStatus(string(R.string.auto_timestamp_status_copying, progressPrefix))
            appendOperationLog(string(R.string.auto_timestamp_log_file_start, progressPrefix, file.fileName))
            appendOperationLog(string(R.string.auto_timestamp_log_copy_to_cache))
            val cachedFile = withContext(Dispatchers.IO) {
                copyUriToCache(file.uri, file.fileName, taskCacheDir)
            } ?: return Result.failure(Exception(string(R.string.auto_timestamp_error_copy_failed)))
            appendOperationLog(
                string(R.string.auto_timestamp_log_cache_file, cachedFile.name, ByteSizeFormat.format(cachedFile.length()))
            )

            if (isCancelled) return Result.failure(Exception(string(R.string.auto_timestamp_error_user_cancelled)))

            showStatus(string(R.string.auto_timestamp_status_extracting, progressPrefix))
            appendOperationLog(string(R.string.auto_timestamp_log_ffmpeg_convert))
            val pcmFile = withContext(Dispatchers.IO) {
                convertToPcm(cachedFile, taskCacheDir)
            } ?: return Result.failure(Exception(string(R.string.auto_timestamp_error_convert_failed)))
            appendOperationLog(
                string(R.string.auto_timestamp_log_pcm_file, pcmFile.name, ByteSizeFormat.format(pcmFile.length()))
            )

            if (isCancelled) return Result.failure(Exception(string(R.string.auto_timestamp_error_user_cancelled)))

            val timelineSource = timelineSource()
            showStatus(
                if (timelineSource == TimelineSource.VAD) {
                    string(R.string.auto_timestamp_status_detecting, progressPrefix)
                } else {
                    string(R.string.auto_timestamp_status_recognizing, progressPrefix)
                }
            )
            val originalEntries = if (refinementSubtitle != null) {
                appendOperationLog(string(R.string.auto_timestamp_log_read_reference, refinementSubtitle.fileName))
                withContext(Dispatchers.IO) {
                    loadSubtitleEntries(refinementSubtitle)
                }.getOrElse { return Result.failure(it) }
            } else {
                emptyList()
            }

            val segmentsResult = withContext(Dispatchers.IO) {
                if (timelineSource == TimelineSource.TOKEN) {
                    val generator = TokenTimestampGenerator(app)
                    val modelName = TokenTimestampGenerator.modelDisplayName(settingsManager)
                    val onProgress: (Int, String) -> Unit = { progress, status ->
                        showStatus(
                            string(R.string.auto_timestamp_status_token_progress, progressPrefix, modelName, status, progress)
                        )
                    }
                    val result = if (refinementSubtitle != null) {
                        generator.generateUncoveredSegments(
                            pcmFile = pcmFile,
                            occupiedTimeRangesMs = originalEntries.map { entry ->
                                entry.startTime to entry.endTime
                            },
                            progressCallback = onProgress,
                            isCancelled = { isCancelled }
                        )
                    } else {
                        generator.generateSegments(
                            pcmFile = pcmFile,
                            progressCallback = onProgress,
                            isCancelled = { isCancelled }
                        )
                    }
                    result.map { generatedSegments ->
                        generatedSegments.map { segment ->
                            VadTimestampGenerator.VadSegment(
                                startTime = segment.startTime,
                                endTime = segment.endTime
                            )
                        }
                    }
                } else if (timelineSource == TimelineSource.ASR) {
                    generateAsrTimeline(pcmFile, progressPrefix)
                } else {
                    runCatching {
                        val generator = VadTimestampGenerator(app)
                        if (refinementSubtitle != null) {
                            generator.generateUncoveredSegments(
                                pcmFile = pcmFile,
                                occupiedTimeRangesMs = originalEntries.map { entry ->
                                    entry.startTime to entry.endTime
                                }
                            )
                        } else {
                            generator.generateSegments(pcmFile)
                        }
                    }
                }
            }
            val segments = segmentsResult.getOrElse { return Result.failure(it) }
            if (refinementSubtitle == null && segments.isEmpty()) {
                return Result.failure(Exception(string(R.string.auto_timestamp_error_no_speech)))
            }
            val timelineName = if (settingsManager.getAsrModelType() == SettingsManager.ASR_MODEL_QWEN3_ASR) {
                string(R.string.auto_timestamp_timeline_forced_align)
            } else {
                string(R.string.auto_timestamp_timeline_token)
            }
            appendOperationLog(
                if (timelineSource == TimelineSource.TOKEN && refinementSubtitle != null) {
                    string(R.string.auto_timestamp_log_token_uncovered, timelineName, segments.size)
                } else if (timelineSource == TimelineSource.TOKEN) {
                    string(R.string.auto_timestamp_log_token_segments, timelineName, segments.size)
                } else if (timelineSource == TimelineSource.ASR) {
                    string(R.string.auto_timestamp_log_asr_segments, segments.size)
                } else if (refinementSubtitle != null) {
                    string(R.string.auto_timestamp_log_secondary_vad_segments, segments.size)
                } else {
                    string(R.string.auto_timestamp_log_vad_segments, segments.size)
                }
            )
            appendVadSegments(segments)

            appendOperationLog(string(R.string.auto_timestamp_log_generate_subtitle, format))
            val outputEntries = if (refinementSubtitle != null) {
                buildRefinedSubtitleEntries(originalEntries, segments)
            } else {
                buildGeneratedSubtitleEntries(segments)
            }
            val subtitleContent = generateSubtitle(outputEntries, format)

            showStatus(string(R.string.auto_timestamp_status_saving, progressPrefix))
            appendOperationLog(string(R.string.auto_timestamp_log_save))
            val outputFileName = withContext(Dispatchers.IO) {
                saveToOutputDir(
                    outputDir,
                    outputSourceFileName(file, refinementSubtitle),
                    subtitleContent,
                    format,
                    overwriteOutput
                )
            }
            appendOperationLog(string(R.string.auto_timestamp_log_saved, outputFileName))
            operationLog.appendRaw(
                "\n" + string(R.string.auto_timestamp_log_result_header, outputSourceFileName(file, refinementSubtitle))
            )
            val text = operationLog.appendRaw(subtitleContent.removeSuffix("\n"))
            setState { copy(previewText = text) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                taskCacheDir.deleteRecursively()
            }
        }
    }

    private fun generateAsrTimeline(
        pcmFile: File,
        progressPrefix: String
    ): Result<List<VadTimestampGenerator.VadSegment>> {
        val model = currentAsrModelPaths()
        if (!model.isComplete()) {
            return Result.failure(IllegalStateException(string(R.string.auto_timestamp_asr_model_required)))
        }
        val recognizer = dependencies.speechRecognitionService.createRecognizer(
            encoderPath = model.encoder,
            decoderPath = model.decoder,
            joinerPath = model.joiner,
            tokensPath = model.tokens,
            vadModelPath = "",
            useVad = false,
            // Protocol value understood by WhisperRecognizer, not display text.
            language = "自动检测",
            contentResolver = app.contentResolver,
            context = app,
            modelType = model.modelType
        )
        return recognizer.recognize(
            audioFile = pcmFile,
            progressCallback = { progress, status, _ ->
                showStatus(string(R.string.auto_timestamp_status_asr_progress, progressPrefix, status, progress))
            },
            isCancelled = { isCancelled }
        ).map { subtitles ->
            // Let the model finish its configured transcription flow, then retain only timing.
            subtitles.map { subtitle ->
                VadTimestampGenerator.VadSegment(subtitle.startTime, subtitle.endTime)
            }
        }
    }

    private fun currentAsrModelPaths(): AsrModelPaths = when (val type = settingsManager.getAsrModelType()) {
        SettingsManager.ASR_MODEL_SENSEVOICE -> AsrModelPaths(
            type, settingsManager.getSenseVoiceModelPath(), "", "",
            settingsManager.getSenseVoiceTokensPath()
        )
        SettingsManager.ASR_MODEL_PARAKEET_TDT -> AsrModelPaths(
            type, settingsManager.getParakeetTdtEncoderPath(),
            settingsManager.getParakeetTdtDecoderPath(),
            settingsManager.getParakeetTdtJoinerPath(),
            settingsManager.getParakeetTdtTokensPath()
        )
        SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> AsrModelPaths(
            type, settingsManager.getParakeetCtcModelPath(), "", "",
            settingsManager.getParakeetCtcTokensPath()
        )
        SettingsManager.ASR_MODEL_QWEN3_ASR -> {
            val variant = settingsManager.getQwen3AsrModelVariant()
            AsrModelPaths(
                type, settingsManager.getQwen3AsrEncoderPath(variant),
                settingsManager.getQwen3AsrDecoderPath(variant),
                settingsManager.getQwen3AsrConvFrontendPath(variant),
                settingsManager.getQwen3AsrTokenizerPath(variant)
            )
        }
        else -> AsrModelPaths(
            type, settingsManager.getWhisperEncoderPath(),
            settingsManager.getWhisperDecoderPath(), "",
            settingsManager.getWhisperTokensPath()
        )
    }

    private fun loadSubtitleEntries(file: SelectedMediaFile): Result<List<SubtitleEntry>> {
        return try {
            val charset = settingsManager.getConfiguredEncoding()
                ?: FileUtils.detectEncoding(app, file.uri)
            val content = FileUtils.readUri(app, file.uri, charset)
            val detectedFormat = SubtitleParser.detectFormat(content)
            if (
                detectedFormat != SubtitleParser.SubtitleFormat.SRT &&
                detectedFormat != SubtitleParser.SubtitleFormat.LRC
            ) {
                return Result.failure(Exception(string(R.string.auto_timestamp_error_reference_invalid)))
            }
            val entries = SubtitleParser.parse(content, charset)
                .filter { entry -> entry.endTime > entry.startTime }
            if (entries.isEmpty()) {
                Result.failure(Exception(string(R.string.auto_timestamp_error_reference_empty)))
            } else {
                Result.success(entries)
            }
        } catch (e: Exception) {
            Result.failure(Exception(string(R.string.auto_timestamp_error_reference_read_failed, e.message.toString()), e))
        }
    }

    private fun buildGeneratedSubtitleEntries(
        segments: List<VadTimestampGenerator.VadSegment>
    ): List<SubtitleEntry> {
        val placeholder = string(R.string.auto_timestamp_placeholder_text)
        return segments.mapIndexed { index, segment ->
            SubtitleEntry(
                index = index + 1,
                startTime = segment.startTime,
                endTime = segment.endTime,
                text = placeholder
            )
        }
    }

    private fun buildRefinedSubtitleEntries(
        originalEntries: List<SubtitleEntry>,
        detectedSegments: List<VadTimestampGenerator.VadSegment>
    ): List<SubtitleEntry> {
        val placeholder = string(R.string.auto_timestamp_placeholder_text)
        val mergeableEntries = originalEntries.map { entry ->
            MergeableSubtitleEntry(entry.copy(), generatedPlaceholder = false)
        } + detectedSegments.map { segment ->
            MergeableSubtitleEntry(
                entry = SubtitleEntry(
                    startTime = segment.startTime,
                    endTime = segment.endTime,
                    text = placeholder
                ),
                generatedPlaceholder = true
            )
        }

        val sortedEntries = mergeableEntries.sortedWith(
            compareBy<MergeableSubtitleEntry> { it.entry.startTime }
                .thenBy { it.entry.endTime }
        )
        val outputEntries = if (
            !isTokenTimestampExperimentEnabled() &&
            settingsManager.isSpeechSecondaryVadMergeEnabled()
        ) {
            mergeSubtitleEntries(
                sortedEntries,
                settingsManager.getSpeechSecondaryVadMergeGapMs()
            )
        } else {
            sortedEntries.map { it.entry }
        }
        return outputEntries
            .mapIndexed { index, entry -> entry.apply { this.index = index + 1 } }
    }

    private fun mergeSubtitleEntries(
        entries: List<MergeableSubtitleEntry>,
        maxGapMs: Int
    ): List<SubtitleEntry> {
        if (entries.isEmpty()) return emptyList()

        val merged = mutableListOf<SubtitleEntry>()
        var group = mutableListOf(entries.first())
        var groupStart = entries.first().entry.startTime
        var groupEnd = entries.first().entry.endTime
        var groupTailIsGenerated = entries.first().generatedPlaceholder

        fun flushGroup() {
            if (group.size == 1) {
                merged.add(group.first().entry.copy())
            } else {
                val text = group.joinToString(separator = "") { item ->
                    if (item.generatedPlaceholder) {
                        string(R.string.auto_timestamp_merged_placeholder, item.entry.text)
                    } else {
                        item.entry.text
                    }
                }
                merged.add(
                    SubtitleEntry(
                        startTime = groupStart,
                        endTime = groupEnd,
                        text = text
                    )
                )
            }
        }

        for (next in entries.drop(1)) {
            if (
                groupTailIsGenerated != next.generatedPlaceholder &&
                next.entry.startTime - groupEnd <= maxGapMs
            ) {
                group.add(next)
                groupEnd = maxOf(groupEnd, next.entry.endTime)
                groupTailIsGenerated = next.generatedPlaceholder
            } else {
                flushGroup()
                group = mutableListOf(next)
                groupStart = next.entry.startTime
                groupEnd = next.entry.endTime
                groupTailIsGenerated = next.generatedPlaceholder
            }
        }
        flushGroup()
        return merged
    }

    private fun generateSubtitle(entries: List<SubtitleEntry>, format: String): String {
        return when (format) {
            "SRT" -> SubtitleParser.toSRT(entries)
            "LRC" -> SubtitleParser.toLRC(entries)
            "TXT" -> SubtitleParser.toTXT(entries)
            else -> SubtitleParser.toSRT(entries)
        }
    }

    private fun outputSourceFileName(
        mediaFile: SelectedMediaFile,
        subtitleFile: SelectedMediaFile? = selectedSubtitleFile.takeIf {
            currentState.secondaryProcessingEnabled
        }
    ): String {
        return subtitleFile?.fileName ?: mediaFile.fileName
    }

    /**
     * 保存到输出目录
     */
    private fun saveToOutputDir(
        dirUri: Uri,
        sourceFileName: String,
        content: String,
        format: String,
        overwrite: Boolean
    ): String {
        try {
            val baseFileName = sourceFileName.substringBeforeLast(".")
            val extension = format.lowercase()
            return SubtitleOutputWriter.writeText(app, dirUri, baseFileName, extension, content, overwrite)
        } catch (e: Exception) {
            throw Exception(string(R.string.auto_timestamp_error_save_failed, e.message.toString()))
        }
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
            Log.e("AutoTimestamp", "复制文件失败", e)
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
                Log.e("AutoTimestamp", "FFmpeg 转换失败")
                null
            }
        } catch (e: Exception) {
            Log.e("AutoTimestamp", "音频转换失败", e)
            null
        }
    }

    private fun isTokenTimestampExperimentEnabled(): Boolean =
        settingsManager.isSpeechTokenTimestampEnabled() &&
            TokenTimestampGenerator.isSupported(app, settingsManager)

    private fun timelineSource(): TimelineSource = when {
        settingsManager.isAsrVadTimestampEnabled() -> TimelineSource.VAD
        isTokenTimestampExperimentEnabled() -> TimelineSource.TOKEN
        else -> TimelineSource.ASR
    }

    private fun appendTimelineConfig(secondaryProcessing: Boolean) {
        if (timelineSource() == TimelineSource.ASR) {
            val model = currentAsrModelPaths()
            appendOperationLog(string(R.string.auto_timestamp_log_asr_timeline, asrModelDisplayName(model.modelType)))
            appendOperationLog(string(R.string.auto_timestamp_log_asr_timeline_detail))
            if (model.modelType == SettingsManager.ASR_MODEL_QWEN3_ASR) {
                appendOperationLog(string(R.string.auto_timestamp_log_qwen3_chunking))
            } else {
                val segmentSeconds = if (
                    model.modelType == SettingsManager.ASR_MODEL_SENSEVOICE &&
                    settingsManager.getSenseVoiceProvider() == SettingsManager.SENSEVOICE_PROVIDER_NPU
                ) {
                    settingsManager.getSenseVoiceNpuDurationSeconds()
                } else {
                    settingsManager.getSpeechFixedSegmentSeconds()
                }
                appendOperationLog(string(R.string.auto_timestamp_log_fixed_segment, segmentSeconds))
                if (settingsManager.isSpeechFixedVadSegmentationEnabled(model.modelType)) {
                    appendOperationLog(string(R.string.auto_timestamp_log_fixed_vad_cut))
                }
            }
            return
        }
        if (timelineSource() == TimelineSource.TOKEN) {
            appendOperationLog(
                if (settingsManager.getAsrModelType() == SettingsManager.ASR_MODEL_QWEN3_ASR) {
                    string(R.string.auto_timestamp_log_qwen3_config)
                } else {
                    string(R.string.auto_timestamp_log_token_config)
                }
            )
            appendOperationLog(
                string(
                    R.string.auto_timestamp_log_model_with_file,
                    TokenTimestampGenerator.modelDisplayName(settingsManager),
                    Uri.parse(TokenTimestampGenerator.modelPath(settingsManager)).lastPathSegment.toString()
                )
            )
            appendOperationLog(
                string(
                    R.string.auto_timestamp_log_tokens,
                    Uri.parse(TokenTimestampGenerator.tokensPath(settingsManager)).lastPathSegment.toString()
                )
            )
            appendOperationLog(
                string(
                    R.string.auto_timestamp_log_merge,
                    if (settingsManager.isSpeechTokenTimestampMergeEnabled()) {
                        string(
                            R.string.auto_timestamp_merge_enabled_summary,
                            settingsManager.speechTokenTimestampMergeSummary()
                        )
                    } else {
                        string(R.string.auto_timestamp_off)
                    }
                )
            )
            appendOperationLog(
                if (settingsManager.isSpeechFixedVadSegmentationEnabled()) {
                    string(R.string.auto_timestamp_log_fixed_vad_cut)
                } else {
                    string(R.string.auto_timestamp_log_vad_not_used)
                }
            )
            if (secondaryProcessing) {
                appendOperationLog(string(R.string.auto_timestamp_log_exclude_covered))
            }
            return
        }
        appendOperationLog(string(R.string.auto_timestamp_log_vad_config))
        appendOperationLog(string(R.string.auto_timestamp_log_model, getVadModelDisplayText()))
        appendOperationLog(string(R.string.auto_timestamp_log_vad_runtime))
        if (secondaryProcessing) {
            appendOperationLog(string(R.string.auto_timestamp_log_secondary_scheme))
            appendOperationLog(
                string(
                    R.string.auto_timestamp_log_secondary_threshold,
                    settingsManager.getSpeechSecondaryVadThreshold(),
                    settingsManager.getSpeechSecondaryVadMinSilenceDuration()
                )
            )
            appendOperationLog(
                string(
                    R.string.auto_timestamp_log_speech_duration,
                    settingsManager.getSpeechSecondaryVadMinSpeechDuration(),
                    settingsManager.getSpeechSecondaryVadMaxSpeechDuration()
                )
            )
            appendSecondaryVadMergeConfig()
        } else {
            appendOperationLog(
                string(
                    R.string.auto_timestamp_log_threshold,
                    settingsManager.getVadThreshold(),
                    settingsManager.getVadMinSilenceDuration()
                )
            )
            appendOperationLog(
                string(
                    R.string.auto_timestamp_log_speech_duration,
                    settingsManager.getVadMinSpeechDuration(),
                    settingsManager.getVadMaxSpeechDuration()
                )
            )
            val secondaryMode = settingsManager.getSpeechSecondaryVadMode()
            val secondaryModeText = when (secondaryMode) {
                SettingsManager.SECONDARY_VAD_MODE_UNCOVERED -> string(R.string.auto_timestamp_secondary_mode_uncovered)
                SettingsManager.SECONDARY_VAD_MODE_WITHIN_SEGMENTS -> string(R.string.auto_timestamp_secondary_mode_within)
                else -> string(R.string.auto_timestamp_off)
            }
            appendOperationLog(string(R.string.auto_timestamp_log_secondary_vad_mode, secondaryModeText))
            if (secondaryMode != SettingsManager.SECONDARY_VAD_MODE_NONE) {
                appendOperationLog(
                    string(
                        R.string.auto_timestamp_log_secondary_threshold,
                        settingsManager.getSpeechSecondaryVadThreshold(),
                        settingsManager.getSpeechSecondaryVadMinSilenceDuration()
                    )
                )
                appendOperationLog(
                    string(
                        R.string.auto_timestamp_log_secondary_speech_duration,
                        settingsManager.getSpeechSecondaryVadMinSpeechDuration(),
                        settingsManager.getSpeechSecondaryVadMaxSpeechDuration()
                    )
                )
                appendSecondaryVadMergeConfig()
            }
        }
    }

    private fun asrModelDisplayName(type: String): String = when (type) {
        SettingsManager.ASR_MODEL_SENSEVOICE -> "SenseVoice"
        SettingsManager.ASR_MODEL_PARAKEET_TDT -> "Parakeet TDT"
        SettingsManager.ASR_MODEL_PARAKEET_CTC_JA -> string(R.string.auto_timestamp_asr_model_parakeet_ctc_ja)
        SettingsManager.ASR_MODEL_QWEN3_ASR -> "Qwen3-ASR"
        else -> "Whisper"
    }

    private fun appendSecondaryVadMergeConfig() {
        appendOperationLog(
            string(
                R.string.auto_timestamp_log_merge,
                if (settingsManager.isSpeechSecondaryVadMergeEnabled()) {
                    string(R.string.auto_timestamp_merge_enabled_gap, settingsManager.getSpeechSecondaryVadMergeGapMs())
                } else {
                    string(R.string.auto_timestamp_off)
                }
            )
        )
    }

    private fun getVadModelDisplayText(): String {
        if (settingsManager.isVadUseBuiltInModel()) {
            return string(R.string.auto_timestamp_vad_model_builtin)
        }
        val path = settingsManager.getVadModelPath()
        return if (path.isBlank()) {
            string(R.string.auto_timestamp_vad_model_external_unset)
        } else {
            string(R.string.auto_timestamp_vad_model_external, Uri.parse(path).lastPathSegment ?: path)
        }
    }

    private fun appendVadSegments(segments: List<VadTimestampGenerator.VadSegment>) {
        var text = operationLog.text()
        for ((index, segment) in segments.withIndex()) {
            text = operationLog.appendRaw(
                string(
                    R.string.auto_timestamp_log_segment_line,
                    index + 1,
                    formatSubtitleTime(segment.startTime),
                    formatSubtitleTime(segment.endTime),
                    (segment.endTime - segment.startTime) / 1000.0
                )
            )
        }
        setState { copy(previewText = text) }
    }

    private fun formatSubtitleTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = timeMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    private fun setupDefaultOutputDir() {
        val savedUri = settingsManager.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)
            ?.let(Uri::parse)
        if (savedUri != null) {
            outputDirUri = savedUri
            setState { copy(outputDirectoryText = DirectoryDisplayPath.fromUri(app, savedUri)) }
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
            setState { copy(outputDirectoryText = defaultPath.absolutePath) }
        } catch (e: Exception) {
            Log.e("AutoTimestamp", "设置默认输出目录失败", e)
        }
    }

    private fun updateSecondaryProcessingAvailability() {
        val asrTimelineEnabled = !settingsManager.isAsrVadTimestampEnabled()
        val hint = string(
            if (asrTimelineEnabled) {
                R.string.activity_auto_timestamp_text_17
            } else {
                R.string.activity_auto_timestamp_text_13
            }
        )
        setState {
            copy(
                secondaryProcessingEnabled = secondaryProcessingEnabled && !asrTimelineEnabled,
                secondaryProcessingAvailable = !asrTimelineEnabled,
                secondaryProcessingHint = hint
            ).withGenerateState()
        }
    }

    /** Recomputes [AutoTimestampUiState.canGenerate] from the current selection. */
    private fun AutoTimestampUiState.withGenerateState(): AutoTimestampUiState {
        val subtitleReady = !secondaryProcessingEnabled || selectedSubtitleFile != null
        return copy(canGenerate = selectedMediaFiles.isNotEmpty() && subtitleReady && !isGenerating)
    }

    private fun showStatus(status: String) = setState { copy(statusText = status) }

    private fun appendOperationLog(message: String) {
        val text = operationLog.append(message)
        setState { copy(previewText = text) }
    }

    private fun fileName(uri: Uri): String =
        UriDisplayName.of(app, uri, fallback = string(R.string.unknown_file))

    override fun onCleared() {
        // Page is finishing for real (not a configuration change): stop native work.
        taskController.cancel()
        mediaOperation?.cancel()
        generationJob?.cancel()
        super.onCleared()
    }
}
