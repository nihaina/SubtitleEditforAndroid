package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import com.subtitleedit.feature.ui.AutoTranslateFileUi
import com.subtitleedit.feature.ui.AutoTranslateUiState
import com.subtitleedit.repository.AiTranslationService
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.AiTranslationConversation
import com.subtitleedit.util.DEFAULT_AI_SUBTITLES_PER_REQUEST
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.LOCAL_AI_SUBTITLES_PER_REQUEST
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleOutputWriter
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitlePunctuationPredictor
import com.subtitleedit.util.subtitle.SubtitleDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal sealed interface AutoTranslateEvent {
    data object OpenSettings : AutoTranslateEvent
    data object Exit : AutoTranslateEvent
}

/** Owns the multi-file translation queue so work survives Activity recreation. */
internal class AutoTranslateViewModel(application: Application) :
    AppViewModel<AutoTranslateUiState, AutoTranslateEvent>(application, AutoTranslateUiState()) {
    companion object {
        private const val OUTPUT_DIRECTORY_KEY = "auto_translate"
        private const val MAX_CONSECUTIVE_ERRORS = 5
        private const val STREAM_UI_UPDATE_INTERVAL_MS = 120L
        private const val STREAM_PREVIEW_MAX_CHARS = 180
    }

    private lateinit var settingsManager: SettingsManager
    private val aiTranslationService: AiTranslationService get() = dependencies.aiTranslationService
    private val files = CopyOnWriteArrayList<AutoTranslateFile>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private var queueRunning = false
    private var outputDirectoryUri: Uri? = null
    private var outputDirectoryLabel: String = ""
    private var pendingConfig: TranslationConfig? = null
    private var pendingOutputUri: Uri? = null
    private var pendingRemovalKey: String? = null
    private var initialized = false

    private data class TranslationConfig(
        val provider: String, val apiKey: String, val model: String, val targetLanguage: String,
        val customPrompt: String, val baseUrl: String, val reasoningLevel: AiProviderConfig.ReasoningLevel,
        val thinkingEnabled: Boolean
    )
    private enum class FileStatus { WAITING, RUNNING, COMPLETED, STOPPED }
    private enum class ProcessingStage(@StringRes val displayNameRes: Int, @StringRes val progressLabelRes: Int? = null) {
        READING(R.string.auto_translate_stage_reading),
        PUNCTUATION_PREDICTION(R.string.auto_translate_stage_punctuation, R.string.auto_translate_stage_punctuation_short),
        TRANSLATION(R.string.auto_translate_stage_translation, R.string.auto_translate_stage_translation_short),
        SAVING(R.string.auto_translate_stage_saving)
    }
    private class AutoTranslateFile(val uri: Uri, val fileName: String, val fileSize: Long, val sessionId: String = UUID.randomUUID().toString()) {
        var status = FileStatus.WAITING
        var processingStage = ProcessingStage.READING
        var totalLines = 0
        var processedLines = 0
        var progressStage: ProcessingStage? = null
        var message = ""
        var document: SubtitleDocument? = null
        var punctuationPredictionCompleted = false
        var punctuationSession: SubtitlePunctuationPredictor.Session? = null
        @Volatile var cancellationRequested = false
        @Volatile var activeConversation: AiTranslationConversation? = null
        val translatedTexts = mutableListOf<String>()
        var overwriteOutput = false
        var lastStreamPublishedAt = 0L
        var lastStreamPreview = ""
    }

    init {
        settingsManager = SettingsManager.getInstance(application)
        outputDirectoryLabel = defaultTranslateOutputDirectory().absolutePath
        restoreOutputDirectory()
        publish()
    }

    val currentOutputDirectoryUri: Uri? get() = outputDirectoryUri
    val isRunning: Boolean get() = queueRunning

    fun initialize(initialUris: List<Uri>) {
        if (initialized) return
        initialized = true
        addFiles(initialUris)
    }

    fun addFiles(uris: List<Uri>) {
        uris.forEach { uri ->
            if (files.any { it.uri == uri }) return@forEach
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            files += AutoTranslateFile(uri, getFileNameFromUri(uri) ?: string(R.string.unknown_file), getFileSizeFromUri(uri))
        }
        publish()
    }

    fun setPunctuationPrediction(enabled: Boolean) = setState { copy(punctuationPredictionEnabled = enabled) }
    fun setTranslation(enabled: Boolean) = setState { copy(translationEnabled = enabled) }

    fun selectOutputDirectory(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        outputDirectoryUri = uri
        outputDirectoryLabel = DirectoryDisplayPath.fromUri(app, uri)
        settingsManager.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        publish()
    }

    fun openSettings() = sendEvent(AutoTranslateEvent.OpenSettings)

    fun startQueuedFiles() {
        if (files.isEmpty()) return toast(R.string.auto_translate_no_files)
        if (files.none { it.status == FileStatus.WAITING }) return
        val needsLocalModel =
            (currentState.translationEnabled && settingsManager.getAiTranslationProvider() == AiProviderConfig.LOCAL) ||
                (currentState.punctuationPredictionEnabled && settingsManager.getAiPunctuationProvider() == AiProviderConfig.LOCAL)
        if (needsLocalModel) {
            val modelPath = settingsManager.getLlmModelPath()
            val repackEnabled = settingsManager.isLlmRepackEnabled()
            viewModelScope.launch(Dispatchers.IO) {
                val loaded = com.subtitleedit.localllm.LocalLlmEngine.isModelLoaded(app, modelPath, repackEnabled)
                withContext(Dispatchers.Main) {
                    if (!loaded) {
                        toast(R.string.ai_local_model_not_loaded)
                    } else {
                        startQueuedFilesValidated()
                    }
                }
            }
            return
        }
        startQueuedFilesValidated()
    }

    private fun startQueuedFilesValidated() {
        val config = if (currentState.translationEnabled) readTranslationConfig() else null
        if (!validateSelectedFeatures(config)) return
        val outputUri = outputDirectoryUri ?: Uri.fromFile(getTranslateOutputDirectory())
        val hasConflict = files.filter { it.status == FileStatus.WAITING }.any { file ->
            SubtitleOutputWriter.exists(app, outputUri, file.fileName.substringBeforeLast("."), outputExtension(file))
        }
        if (hasConflict) {
            pendingConfig = config; pendingOutputUri = outputUri
            setState { copy(showOutputConflict = true) }
        } else beginQueuedFiles(config, outputUri, false)
    }

    fun resolveOutputConflict(overwrite: Boolean) {
        val config = pendingConfig; val output = pendingOutputUri
        pendingConfig = null; pendingOutputUri = null
        setState { copy(showOutputConflict = false) }
        if (output != null) beginQueuedFiles(config, output, overwrite)
    }
    fun dismissOutputConflict() {
        pendingConfig = null; pendingOutputUri = null
        setState { copy(showOutputConflict = false) }
    }

    private fun beginQueuedFiles(config: TranslationConfig?, output: Uri, overwrite: Boolean) {
        files.filter { it.status == FileStatus.WAITING }.forEach { file ->
            file.overwriteOutput = overwrite
            startFile(file, config = config, output = output, overwrite = overwrite)
        }
    }

    private fun startFile(file: AutoTranslateFile, retry: Boolean = false, config: TranslationConfig? = if (currentState.translationEnabled) readTranslationConfig() else null, output: Uri = outputDirectoryUri ?: Uri.fromFile(getTranslateOutputDirectory()), overwrite: Boolean = file.overwriteOutput) {
        if (activeJobs[file.sessionId]?.isActive == true || !validateSelectedFeatures(config)) return
        queueRunning = true
        if (retry) file.message = ""
        file.cancellationRequested = false
        file.status = FileStatus.RUNNING
        file.processingStage = ProcessingStage.READING
        publish()
        activeJobs[file.sessionId] = viewModelScope.launch(Dispatchers.IO) { processFile(file, config, output, overwrite) }
    }

    fun retry(key: String) { files.firstOrNull { it.sessionId == key }?.let { startFile(it, retry = true) } }

    private suspend fun processFile(file: AutoTranslateFile, initialConfig: TranslationConfig?, outputUri: Uri, overwriteOutput: Boolean) {
        try {
            var document = file.document ?: loadDocument(file).also { file.document = it }
            val entries = document.entries
            if (file.progressStage == null) file.totalLines = entries.size
            publish()
            if (entries.isEmpty()) throw IllegalArgumentException(string(R.string.auto_translate_empty_subtitles))
            if (isFeatureEnabled(Feature.PUNCTUATION_PREDICTION) && !file.punctuationPredictionCompleted) {
                document = applyPunctuationPrediction(file, document)
                file.document = document
                file.punctuationPredictionCompleted = true
            }
            if (isFeatureEnabled(Feature.TRANSLATION)) {
                val config = initialConfig ?: readTranslationConfig() ?: throw IllegalArgumentException(string(R.string.ai_processing_configuration_required))
                document = translateDocument(file, document, config)
                file.document = document
            }
            updateProcessingStage(file, ProcessingStage.SAVING)
            val outputText = SubtitleParser.serialize(document)
            currentCoroutineContext().ensureActive()
            SubtitleOutputWriter.writeText(app, outputUri, file.fileName.substringBeforeLast("."), outputExtension(file), outputText, overwrite = overwriteOutput)
            file.status = FileStatus.COMPLETED
            file.message = string(R.string.task_all_done)
            publish()
        } catch (_: CancellationException) {
            file.status = FileStatus.STOPPED; file.message = string(R.string.task_cancelled); publish()
        } catch (error: Exception) {
            file.status = FileStatus.STOPPED; file.message = error.message ?: string(R.string.auto_translate_processing_failed); publish()
        } finally {
            file.activeConversation = null
            withContext(NonCancellable + Dispatchers.Main) {
                activeJobs.remove(file.sessionId)
                if (activeJobs.values.none { it.isActive }) queueRunning = false
                publish()
            }
        }
    }

    private enum class Feature { PUNCTUATION_PREDICTION, TRANSLATION }
    private fun isFeatureEnabled(feature: Feature) = when (feature) {
        Feature.PUNCTUATION_PREDICTION -> currentState.punctuationPredictionEnabled
        Feature.TRANSLATION -> currentState.translationEnabled
    }

    private suspend fun applyPunctuationPrediction(file: AutoTranslateFile, document: SubtitleDocument): SubtitleDocument {
        val provider = settingsManager.getAiPunctuationProvider()
        val entriesPerBatch = if (provider == AiProviderConfig.LOCAL) {
            LOCAL_AI_SUBTITLES_PER_REQUEST
        } else {
            DEFAULT_AI_SUBTITLES_PER_REQUEST
        }
        val session = file.punctuationSession ?: SubtitlePunctuationPredictor.Session(
            SubtitlePunctuationPredictor.prepareEntries(document.entries),
            entriesPerBatch = entriesPerBatch,
            includeBlockMarkers = provider != AiProviderConfig.LOCAL,
            bracketSequence = provider == AiProviderConfig.LOCAL
        ).also { file.punctuationSession = it }
        updateProcessingStage(file, ProcessingStage.PUNCTUATION_PREDICTION, session.processedCount, session.totalCount)
        if (session.totalCount == 0) return document
        val apiKey = settingsManager.getAiApiKey(provider); val model = settingsManager.getAiPunctuationModel(provider); val baseUrl = settingsManager.getAiBaseUrl(provider)
        if (provider == AiProviderConfig.LOCAL) {
            if (settingsManager.getLlmModelPath().isBlank()) {
                throw IllegalArgumentException(string(R.string.ai_processing_configuration_required))
            }
        } else if (apiKey.isBlank() || model.isBlank() || baseUrl.isBlank()) {
            throw IllegalArgumentException(string(R.string.ai_processing_configuration_required))
        }
        val conversation = aiTranslationService.createConversation(app, provider, apiKey, model, "", settingsManager.getAiPunctuationCustomPrompt(), baseUrl, document.format, settingsManager.getAiPunctuationReasoningLevel(provider), "punctuation_${file.sessionId}", string(R.string.auto_translate_punctuation_history_title, file.fileName), settingsManager.isAiLocalPunctuationThinkingEnabled())
        file.activeConversation = conversation
        val punctuated = session.run(
            onProgress = { done, total ->
                file.processedLines = done
                file.totalLines = total
                publish()
            },
            onStreamProgress = { done, total ->
                file.processedLines = done
                file.totalLines = total
                publish()
            },
            requestStreamingPrediction = { text, onStream ->
                currentCoroutineContext().ensureActive()
                if (file.cancellationRequested) {
                    throw CancellationException(string(R.string.auto_translate_punctuation_cancelled))
                }
                resetAiStream(file)
                conversation.predictPunctuation(
                    text = text,
                    streamCallback = { content ->
                        publishAiStream(file, content)
                        onStream(content)
                    },
                    isCancelled = { file.cancellationRequested }
                ).getOrElse { throw it }
                    .also {
                        file.message = ""
                        publish()
                    }
            },
            requestPrediction = { text ->
                currentCoroutineContext().ensureActive()
                if (file.cancellationRequested) {
                    throw CancellationException(string(R.string.auto_translate_punctuation_cancelled))
                }
                resetAiStream(file)
                conversation.predictPunctuation(
                    text = text,
                    isCancelled = { file.cancellationRequested }
                ).getOrElse { throw it }
            }
        )
        return document.copy(entries = punctuated)
    }

    private suspend fun translateDocument(file: AutoTranslateFile, document: SubtitleDocument, config: TranslationConfig): SubtitleDocument {
        val entries = document.entries
        updateProcessingStage(file, ProcessingStage.TRANSLATION, file.translatedTexts.size, entries.size)
        val translator = aiTranslationService.createConversation(app, config.provider, config.apiKey, config.model, config.targetLanguage, config.customPrompt, config.baseUrl, document.format, config.reasoningLevel, file.sessionId, string(R.string.auto_translate_translation_history_title, file.fileName, config.targetLanguage), config.thinkingEnabled)
        file.activeConversation = translator
        var consecutiveErrors = 0
        while (file.translatedTexts.size < entries.size) {
            currentCoroutineContext().ensureActive()
            resetAiStream(file)
            publish()
            val completed = file.translatedTexts.size
            val result = translator.translateSubtitles(
                subtitles = entries.drop(completed),
                startPosition = completed + 1,
                progressCallback = { current, _ ->
                    file.processedLines = completed + current
                    publish()
                },
                streamCallback = { content -> publishAiStream(file, content) },
                streamProgressCallback = { current, _ ->
                    file.processedLines = completed + current
                    publish()
                },
                isCancelled = { file.cancellationRequested }
            )
            file.message = ""
            file.translatedTexts += result.translations; file.processedLines = file.translatedTexts.size; publish()
            if (result.isComplete && result.translations.isNotEmpty()) { consecutiveErrors = 0; continue }
            if (result.error != null) {
                consecutiveErrors++; file.message = result.error.message ?: string(R.string.auto_translate_translation_failed)
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) throw result.error
                publish(); delay((consecutiveErrors * 500L).coerceAtMost(3_000L))
            } else if (result.translations.isEmpty()) {
                consecutiveErrors++; if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) throw IllegalStateException(string(R.string.auto_translate_no_translation_result))
            }
        }
        return document.copy(entries = entries.mapIndexed { index, entry -> entry.copy(text = file.translatedTexts[index]) })
    }

    private suspend fun loadDocument(file: AutoTranslateFile): SubtitleDocument {
        val content = FileUtils.readUri(app, file.uri, settingsManager.getConfiguredEncoding())
            .ifEmpty { throw IllegalArgumentException(string(R.string.auto_translate_read_failed)) }
        return SubtitleParser.parseDocument(content, file.fileName, SubtitleParser.detectFormat(content, file.fileName))
    }

    private fun validateSelectedFeatures(config: TranslationConfig?): Boolean {
        val punctuation = currentState.punctuationPredictionEnabled; val translation = currentState.translationEnabled
        if (!punctuation && !translation) { toast(R.string.auto_translate_select_feature); return false }
        if ((punctuation && !isPunctuationAiConfigured()) || (translation && config == null)) { toast(R.string.ai_processing_configuration_required, duration = android.widget.Toast.LENGTH_LONG); return false }
        return true
    }
    private fun readTranslationConfig(): TranslationConfig? {
        val provider = settingsManager.getAiTranslationProvider(); val apiKey = settingsManager.getAiApiKey(provider); val model = settingsManager.getAiModel(provider); val target = settingsManager.getAiTargetLanguage(); val base = settingsManager.getAiBaseUrl(provider)
        if (provider == AiProviderConfig.LOCAL) {
            if (settingsManager.getLlmModelPath().isBlank() || target.isBlank()) return null
        } else if (apiKey.isBlank() || model.isBlank() || target.isBlank() || base.isBlank()) {
            return null
        }
        return TranslationConfig(provider, apiKey, model, target, settingsManager.getAiCustomPrompt(), base, settingsManager.getAiReasoningLevel(provider), settingsManager.isAiLocalTranslationThinkingEnabled())
    }
    private fun isPunctuationAiConfigured(): Boolean {
        val provider = settingsManager.getAiPunctuationProvider()
        return if (provider == AiProviderConfig.LOCAL) {
            settingsManager.getLlmModelPath().isNotBlank()
        } else {
            settingsManager.getAiApiKey(provider).isNotBlank() && settingsManager.getAiPunctuationModel(provider).isNotBlank() && settingsManager.getAiBaseUrl(provider).isNotBlank()
        }
    }

    private fun publishAiStream(file: AutoTranslateFile, content: String) {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) return
        val preview = if (normalized.length <= STREAM_PREVIEW_MAX_CHARS) {
            normalized
        } else {
            "…" + normalized.takeLast(STREAM_PREVIEW_MAX_CHARS)
        }
        val now = SystemClock.uptimeMillis()
        if (now - file.lastStreamPublishedAt < STREAM_UI_UPDATE_INTERVAL_MS) return
        if (preview == file.lastStreamPreview) return
        file.lastStreamPublishedAt = now
        file.lastStreamPreview = preview
        file.message = "AI 输出：$preview"
        publish()
    }

    private fun resetAiStream(file: AutoTranslateFile) {
        file.lastStreamPublishedAt = 0L
        file.lastStreamPreview = ""
        file.message = ""
    }

    private suspend fun updateProcessingStage(file: AutoTranslateFile, stage: ProcessingStage, processedLines: Int = 0, totalLines: Int = 0) {
        file.processingStage = stage; file.message = ""
        if (stage.progressLabelRes != null) { file.progressStage = stage; file.processedLines = processedLines; file.totalLines = totalLines }
        publish()
    }

    fun requestRemove(key: String) {
        val file = files.firstOrNull { it.sessionId == key } ?: return
        if (file.status != FileStatus.RUNNING) removeFile(file) else { pendingRemovalKey = key; setState { copy(removeFileName = file.fileName) } }
    }
    fun confirmRemove() { val file = files.firstOrNull { it.sessionId == pendingRemovalKey }; pendingRemovalKey = null; setState { copy(removeFileName = null) }; file?.let(::removeFile) }
    fun dismissRemove() { pendingRemovalKey = null; setState { copy(removeFileName = null) } }
    private fun cancelFile(file: AutoTranslateFile) { file.cancellationRequested = true; activeJobs[file.sessionId]?.cancel(); file.activeConversation?.cancel() }
    private fun removeFile(file: AutoTranslateFile) { cancelFile(file); activeJobs.remove(file.sessionId); files.remove(file); queueRunning = activeJobs.values.any { it.isActive }; publish() }

    fun requestBack(): Boolean {
        if (!queueRunning) return true
        setState { copy(showExitConfirmation = true) }
        return false
    }
    fun dismissExit() = setState { copy(showExitConfirmation = false) }
    fun stopAndExit() { setState { copy(showExitConfirmation = false) }; files.forEach(::cancelFile); queueRunning = false; sendEvent(AutoTranslateEvent.Exit) }

    private fun restoreOutputDirectory() {
        val uri = settingsManager.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)?.let(Uri::parse) ?: return
        outputDirectoryUri = uri; outputDirectoryLabel = DirectoryDisplayPath.fromUri(app, uri)
    }
    private fun publish() {
        val items = files.map { file ->
            val status = when (file.status) {
                FileStatus.WAITING -> string(R.string.auto_translate_status_waiting)
                FileStatus.RUNNING -> string(file.processingStage.displayNameRes)
                FileStatus.COMPLETED -> string(R.string.auto_translate_status_completed)
                FileStatus.STOPPED -> string(R.string.auto_translate_status_stopped)
            }
            AutoTranslateFileUi(file.sessionId, file.fileName, FileUtils.formatFileSize(file.fileSize), status, file.message, file.totalLines, file.processedLines, file.progressStage?.progressLabelRes?.let(::string), file.status == FileStatus.RUNNING && file.processingStage == file.progressStage, file.status == FileStatus.STOPPED)
        }
        val summary = if (!queueRunning) "" else buildString {
            val active = files.count { it.status == FileStatus.RUNNING }; val done = files.count { it.status == FileStatus.COMPLETED }
            append(string(R.string.auto_translate_progress_summary, active, done, files.size))
            for (stage in ProcessingStage.entries.filter { it.progressLabelRes != null }) {
                val group = files.filter { it.progressStage == stage }
                if (group.isNotEmpty()) append(string(R.string.auto_translate_stage_progress, string(stage.progressLabelRes!!), group.sumOf { it.processedLines }, group.sumOf { it.totalLines }))
            }
        }
        val hasWaitingFiles = files.any { it.status == FileStatus.WAITING }
        setState {
            copy(
                files = items,
                outputDirectory = outputDirectoryLabel,
                queueRunning = queueRunning,
                hasWaitingFiles = hasWaitingFiles,
                progressSummary = summary
            )
        }
    }
    private fun outputExtension(file: AutoTranslateFile): String = when (file.document?.format) { SubtitleParser.SubtitleFormat.SRT -> "srt"; SubtitleParser.SubtitleFormat.LRC -> "lrc"; SubtitleParser.SubtitleFormat.VTT -> "vtt"; SubtitleParser.SubtitleFormat.TXT -> "txt"; SubtitleParser.SubtitleFormat.ASS -> "ass"; SubtitleParser.SubtitleFormat.SSA -> "ssa"; else -> file.fileName.substringAfterLast('.', "srt").lowercase() }
    private fun defaultTranslateOutputDirectory() = File(com.subtitleedit.util.ModelDirectoryManager.softwareDirectory(), "Translate")
    private fun getTranslateOutputDirectory() = defaultTranslateOutputDirectory().apply { if (!exists()) mkdirs() }
    private fun getFileNameFromUri(uri: Uri): String? = runCatching { app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null } }.getOrNull() ?: when (uri.scheme) { "file" -> uri.path?.let(::File)?.name; else -> DocumentFile.fromSingleUri(app, uri)?.name }
    private fun getFileSizeFromUri(uri: Uri): Long = runCatching { app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L } ?: 0L }.getOrDefault(0L).let { size -> if (size > 0) size else if (uri.scheme == "file") uri.path?.let(::File)?.length() ?: 0L else 0L }

    override fun onCleared() { files.forEach(::cancelFile); activeJobs.clear(); super.onCleared() }
}
