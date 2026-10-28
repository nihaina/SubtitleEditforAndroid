package com.subtitleedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import com.subtitleedit.chat.ChatBackend
import com.subtitleedit.chat.ChatHistoryStore
import com.subtitleedit.feature.ui.AutoTranslateFileUi
import com.subtitleedit.feature.ui.AutoTranslateUiState
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.repository.AiTranslationService
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.AiTranslationConversation
import com.subtitleedit.util.AiNoOutputTimeoutException
import com.subtitleedit.util.AI_NO_OUTPUT_MAX_RETRIES
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleOutputWriter
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleFormatConverter
import com.subtitleedit.util.SubtitlePunctuationPredictor
import com.subtitleedit.util.SubtitleTextSplitOps
import com.subtitleedit.util.SubtitleTranslationResumePolicy
import com.subtitleedit.util.TxtTranslationSession
import com.subtitleedit.util.TxtPunctuationSession
import com.subtitleedit.util.InvalidTxtAiResponseException
import com.subtitleedit.util.requestTxtAiWithTagRetries
import com.subtitleedit.util.restoreTxtLineLayout
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
    private val queueStore = AutoTranslateQueueStore(application)
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
    private enum class FileStatus { WAITING, RUNNING, PAUSED, COMPLETED, STOPPED }
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
        var translationSourceEntries: List<SubtitleEntry>? = null
        var originalText: String? = null
        var textTranslationSession: TxtTranslationSession? = null
        var textPunctuationSession: TxtPunctuationSession? = null
        var punctuationPredictionCompleted = false
        var punctuationSession: SubtitlePunctuationPredictor.Session? = null
        @Volatile var cancellationRequested = false
        @Volatile var pauseRequested = false
        @Volatile var activeConversation: AiTranslationConversation? = null
        var punctuationConversation: AiTranslationConversation? = null
        var punctuationResumeConversationPending = false
        var punctuationProvider: String? = null
        var translationConversation: AiTranslationConversation? = null
        var translationConfig: TranslationConfig? = null
        var translationBatchSize: Int? = null
        var translationResumeHistoryPending = false
        var punctuationEnabledForRun = false
        var translationEnabledForRun = false
        var translationResumeStart: Int? = null
        var textTranslationCompletedTexts = mutableListOf<String>()
        var textPunctuationCompletedTexts = mutableListOf<String>()
        var punctuationCompletedTexts = mutableListOf<String>()
        var punctuationHistoryMessages: List<ChatBackend.ChatMessage> = emptyList()
        var translationHistoryMessages: List<ChatBackend.ChatMessage> = emptyList()
        var historyLoaded = true
        var historyLoadRequested = false
        var resumeAfterHistoryLoad = false
        val translatedTexts = mutableListOf<String>()
        var overwriteOutput = false
        var lastStreamPublishedAt = 0L
        var lastStreamPreview = ""
    }

    init {
        settingsManager = SettingsManager.getInstance(application)
        outputDirectoryLabel = defaultTranslateOutputDirectory().absolutePath
        restoreOutputDirectory()
        restoreQueue()
        publish()
    }

    val currentOutputDirectoryUri: Uri? get() = outputDirectoryUri
    val isRunning: Boolean get() = queueRunning

    fun initialize(initialUris: List<Uri>) {
        if (initialized) return
        initialized = true
        addFiles(initialUris)
    }

    private fun restoreQueue() {
        val snapshot = queueStore.load() ?: return
        setState {
            copy(
                punctuationPredictionEnabled = snapshot.punctuationPredictionEnabled,
                translationEnabled = snapshot.translationEnabled
            )
        }
        snapshot.files.forEach { persisted ->
            val persistedStatus = runCatching { FileStatus.valueOf(persisted.status) }
                .getOrDefault(FileStatus.WAITING)
            if (persistedStatus == FileStatus.COMPLETED) return@forEach
            val file = AutoTranslateFile(
                uri = Uri.parse(persisted.uri),
                fileName = persisted.fileName,
                fileSize = persisted.fileSize,
                sessionId = persisted.sessionId
            )
            file.status = if (persistedStatus == FileStatus.RUNNING) FileStatus.PAUSED else persistedStatus
            file.processingStage = runCatching { ProcessingStage.valueOf(persisted.processingStage) }
                .getOrDefault(ProcessingStage.READING)
            file.totalLines = persisted.totalLines
            file.processedLines = persisted.processedLines
            file.progressStage = persisted.progressStage?.let { value ->
                runCatching { ProcessingStage.valueOf(value) }.getOrNull()
            }
            file.document = persisted.documentContent?.let { content ->
                runCatching {
                    val format = SubtitleParser.detectFormat(content, file.fileName)
                    SubtitleParser.parseDocument(content, file.fileName, format)
                }.getOrNull()
            }
            file.translationSourceEntries = file.document?.entries
            file.originalText = persisted.originalText
            file.punctuationPredictionCompleted = persisted.punctuationPredictionCompleted
            file.translatedTexts += persisted.translatedTexts
            file.translationResumeStart = persisted.translationResumeStart
            file.translationBatchSize = persisted.translationBatchSize
            file.translationConfig = persisted.translationConfig?.toRuntimeConfig()
            file.punctuationProvider = persisted.punctuationProvider
            file.punctuationEnabledForRun = persisted.punctuationEnabled
            file.translationEnabledForRun = persisted.translationEnabled
            file.overwriteOutput = persisted.overwriteOutput
            file.punctuationCompletedTexts += persisted.punctuationCompletedTexts
            file.textPunctuationCompletedTexts += persisted.textPunctuationPredictions
            file.textTranslationCompletedTexts += persisted.textTranslations
            file.punctuationResumeConversationPending = persisted.punctuationResumeHistory
            file.translationResumeHistoryPending = persisted.translationResumeHistory
            file.historyLoaded = !persisted.punctuationResumeHistory && !persisted.translationResumeHistory
            files += file
            if (!file.historyLoaded) loadRestoredHistory(file)
        }
    }

    private fun PersistedTranslationConfig.toRuntimeConfig(): TranslationConfig = TranslationConfig(
        provider = provider,
        apiKey = settingsManager.getAiApiKey(provider),
        model = model,
        targetLanguage = targetLanguage,
        customPrompt = customPrompt,
        baseUrl = baseUrl,
        reasoningLevel = runCatching { AiProviderConfig.ReasoningLevel.valueOf(reasoningLevel) }
            .getOrDefault(AiProviderConfig.defaultReasoningLevel(provider)),
        thinkingEnabled = thinkingEnabled
    )

    private fun loadRestoredHistory(file: AutoTranslateFile) {
        if (file.historyLoadRequested) return
        file.historyLoadRequested = true
        viewModelScope.launch(Dispatchers.IO) {
            val store = ChatHistoryStore(app)
            val punctuation = store.load("punctuation_${file.sessionId}")?.messages.orEmpty()
            val translation = store.load(file.sessionId)?.messages.orEmpty()
            withContext(Dispatchers.Main) {
                if (files.contains(file)) {
                    file.punctuationHistoryMessages = punctuation
                    file.translationHistoryMessages = translation
                    file.historyLoaded = true
                    publish()
                    if (file.resumeAfterHistoryLoad && file.status == FileStatus.PAUSED) {
                        file.resumeAfterHistoryLoad = false
                        startFile(file, retry = true)
                    }
                }
            }
        }
    }

    fun addFiles(uris: List<Uri>) {
        uris.forEach { uri ->
            if (files.any { it.uri == uri }) return@forEach
            val fileName = getFileNameFromUri(uri) ?: string(R.string.unknown_file)
            if (!SubtitleFormatConverter.isSupportedSourceFileName(fileName)) {
                toast(R.string.auto_translate_unsupported_file, fileName)
                return@forEach
            }
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            files += AutoTranslateFile(uri, fileName, getFileSizeFromUri(uri))
        }
        publish()
        persistQueueSnapshot()
    }

    fun setPunctuationPrediction(enabled: Boolean) {
        setState { copy(punctuationPredictionEnabled = enabled) }
        persistQueueSnapshot()
    }

    fun setTranslation(enabled: Boolean) {
        setState { copy(translationEnabled = enabled) }
        persistQueueSnapshot()
    }

    fun selectOutputDirectory(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        outputDirectoryUri = uri
        outputDirectoryLabel = DirectoryDisplayPath.fromUri(app, uri)
        settingsManager.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        publish()
        persistQueueSnapshot()
    }

    fun openSettings() = sendEvent(AutoTranslateEvent.OpenSettings)

    fun startQueuedFiles() {
        if (files.isEmpty()) return toast(R.string.auto_translate_no_files)
        val runnable = files.filter { it.status == FileStatus.WAITING }
        if (runnable.isEmpty()) return
        val needsLocalModel = runnable.any { file ->
            (currentState.translationEnabled && settingsManager.getAiTranslationProvider() == AiProviderConfig.LOCAL) ||
                (currentState.punctuationPredictionEnabled && settingsManager.getAiPunctuationProvider() == AiProviderConfig.LOCAL)
        }
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
        val hasWaitingFiles = files.any { it.status == FileStatus.WAITING }
        val config = if (hasWaitingFiles && currentState.translationEnabled) readTranslationConfig() else null
        if (hasWaitingFiles && !validateSelectedFeatures(config)) return
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
        val resuming = file.status == FileStatus.PAUSED
        if (activeJobs[file.sessionId]?.isActive == true || (!resuming && !validateSelectedFeatures(config))) return
        if (resuming && !file.historyLoaded) {
            file.resumeAfterHistoryLoad = true
            loadRestoredHistory(file)
            return
        }
        queueRunning = true
        if (retry) file.message = ""
        file.cancellationRequested = false
        file.pauseRequested = false
        file.status = FileStatus.RUNNING
        if (!resuming) file.processingStage = ProcessingStage.READING
        if (!resuming) {
            file.translationConfig = config
            file.translationBatchSize = config?.provider?.let(settingsManager::getAiSubtitlesPerRequest)
            file.translationEnabledForRun = currentState.translationEnabled
            file.punctuationEnabledForRun = currentState.punctuationPredictionEnabled
            file.punctuationProvider = settingsManager.getAiPunctuationProvider()
        }
        publish()
        val effectiveConfig = if (resuming) file.translationConfig ?: config else config
        activeJobs[file.sessionId] = viewModelScope.launch(Dispatchers.IO) { processFile(file, effectiveConfig, output, overwrite) }
        persistQueueSnapshot()
    }

    fun retry(key: String) { files.firstOrNull { it.sessionId == key }?.let { startFile(it, retry = true) } }

    fun handleFileClick(key: String) {
        val file = files.firstOrNull { it.sessionId == key } ?: return
        if (file.status == FileStatus.RUNNING) pause(key) else if (file.status == FileStatus.PAUSED || file.status == FileStatus.STOPPED) retry(key)
    }

    fun pause(key: String) {
        val file = files.firstOrNull { it.sessionId == key } ?: return
        if (file.status != FileStatus.RUNNING || file.processingStage == ProcessingStage.SAVING || file.pauseRequested) return
        file.pauseRequested = true
        file.message = string(R.string.auto_translate_pause_requested)
        publish()
        persistQueueSnapshot()
        file.activeConversation?.cancel()
    }

    private suspend fun processFile(file: AutoTranslateFile, initialConfig: TranslationConfig?, outputUri: Uri, overwriteOutput: Boolean) {
        try {
            var document = file.document ?: loadDocument(file).also { file.document = it }
            val entries = document.entries
            if (file.progressStage == null) file.totalLines = entries.size
            publish()
            if (file.pauseRequested) throw CancellationException("字幕处理已暂停")
            if (entries.isEmpty()) throw IllegalArgumentException(string(R.string.auto_translate_empty_subtitles))
            if (isFeatureEnabled(file, Feature.PUNCTUATION_PREDICTION) && !file.punctuationPredictionCompleted) {
                document = applyPunctuationPrediction(file, document)
                file.document = document
                file.punctuationPredictionCompleted = true
            }
            if (isFeatureEnabled(file, Feature.TRANSLATION)) {
                val config = initialConfig ?: readTranslationConfig() ?: throw IllegalArgumentException(string(R.string.ai_processing_configuration_required))
                document = translateDocument(file, document, config)
                file.document = document
            }
            if (file.pauseRequested) {
                if (file.translationEnabledForRun && document.format != SubtitleParser.SubtitleFormat.TXT) {
                    pauseTranslation(file, file.translatedTexts.size, emptyList())
                }
                throw CancellationException("字幕处理已暂停")
            }
            updateProcessingStage(file, ProcessingStage.SAVING)
            val outputText = file.textTranslationSession?.translatedText
                ?: file.textPunctuationSession?.predictedText
                ?: SubtitleParser.serialize(document)
            currentCoroutineContext().ensureActive()
            SubtitleOutputWriter.writeText(app, outputUri, file.fileName.substringBeforeLast("."), outputExtension(file), outputText, overwrite = overwriteOutput)
            file.status = FileStatus.COMPLETED
            file.message = string(R.string.task_all_done)
            publish()
        } catch (_: CancellationException) {
            if (file.pauseRequested) {
                if (file.processingStage == ProcessingStage.PUNCTUATION_PREDICTION) {
                    file.punctuationResumeConversationPending = file.punctuationConversation != null
                }
                if (file.processingStage == ProcessingStage.TRANSLATION &&
                    file.document?.format != SubtitleParser.SubtitleFormat.TXT &&
                    file.translationResumeStart == null
                ) {
                    checkpointTranslationForResume(file)
                }
                file.status = FileStatus.PAUSED
                file.message = string(R.string.auto_translate_status_paused)
            } else {
                file.status = FileStatus.STOPPED
                file.message = string(R.string.task_cancelled)
            }
            publish()
            persistQueueSnapshot()
        } catch (error: Exception) {
            file.status = FileStatus.STOPPED; file.message = error.message ?: string(R.string.auto_translate_processing_failed); publish()
            persistQueueSnapshot()
        } finally {
            if (file.status != FileStatus.PAUSED) {
                file.activeConversation = null
                file.punctuationConversation = null
                file.punctuationResumeConversationPending = false
                file.translationConversation = null
                file.translationResumeStart = null
            }
            withContext(NonCancellable + Dispatchers.Main) {
                activeJobs.remove(file.sessionId)
                if (activeJobs.values.none { it.isActive }) queueRunning = false
                publish()
                persistQueueSnapshot()
            }
        }
    }

    private enum class Feature { PUNCTUATION_PREDICTION, TRANSLATION }
    private fun isFeatureEnabled(file: AutoTranslateFile, feature: Feature) = when (feature) {
        Feature.PUNCTUATION_PREDICTION -> file.punctuationEnabledForRun
        Feature.TRANSLATION -> file.translationEnabledForRun
    }

    private suspend fun applyPunctuationPrediction(file: AutoTranslateFile, document: SubtitleDocument): SubtitleDocument {
        if (document.format == SubtitleParser.SubtitleFormat.TXT) {
            return predictTxtPunctuation(file, document)
        }
        val provider = file.punctuationProvider ?: settingsManager.getAiPunctuationProvider().also { file.punctuationProvider = it }
        val preparedEntries = SubtitlePunctuationPredictor.prepareEntries(document.entries)
        val entriesPerBatch = settingsManager.getAiSubtitlesPerRequest(provider)
        val session = file.punctuationSession ?: SubtitlePunctuationPredictor.Session(
            preparedEntries,
            entriesPerBatch = entriesPerBatch,
            completedTexts = file.punctuationCompletedTexts
        ).also { file.punctuationSession = it }
        updateProcessingStage(file, ProcessingStage.PUNCTUATION_PREDICTION, session.processedCount, session.totalCount)
        if (session.totalCount == 0) return document
        val apiKey = settingsManager.getAiApiKey(provider); val model = settingsManager.getAiPunctuationModel(provider); val baseUrl = settingsManager.getAiBaseUrl(provider)
        if (file.punctuationConversation == null && provider == AiProviderConfig.LOCAL) {
            if (settingsManager.getLlmModelPath().isBlank()) {
                throw IllegalArgumentException(string(R.string.ai_processing_configuration_required))
            }
        } else if (file.punctuationConversation == null && (apiKey.isBlank() || model.isBlank() || baseUrl.isBlank())) {
            throw IllegalArgumentException(string(R.string.ai_processing_configuration_required))
        }
        val conversation = file.punctuationConversation ?: aiTranslationService.createConversation(app, provider, apiKey, model, "", settingsManager.getAiPunctuationCustomPrompt(), baseUrl, document.format, settingsManager.getAiPunctuationReasoningLevel(provider), "punctuation_${file.sessionId}", string(R.string.auto_translate_punctuation_history_title, file.fileName), settingsManager.isAiLocalPunctuationThinkingEnabled(), file.punctuationHistoryMessages).also { file.punctuationConversation = it }
        file.activeConversation = conversation
        var preserveConversation = file.punctuationResumeConversationPending
        val punctuated = session.run(
            onProgress = { done, total ->
                file.processedLines = done
                file.totalLines = total
                publish()
                file.punctuationCompletedTexts = session.completedTextsSnapshot().toMutableList()
                persistQueueSnapshot()
            },
            onStreamProgress = { done, total ->
                file.processedLines = done
                file.totalLines = total
                publish()
            },
            requestStreamingPredictionWithRetry = { text, isRetry, onStream, shouldStop ->
                currentCoroutineContext().ensureActive()
                if (file.cancellationRequested || file.pauseRequested) {
                    throw CancellationException(string(R.string.auto_translate_punctuation_cancelled))
                }
                resetAiStream(file)
                conversation.predictPunctuation(
                    text = text,
                    clearConversation = provider == AiProviderConfig.LOCAL && !isRetry && !preserveConversation,
                    recordInterruptedTurn = { shouldStop() || file.pauseRequested },
                    streamCallback = { content ->
                        publishAiStream(file, content)
                        onStream(content)
                        if (shouldStop() || file.pauseRequested) conversation.cancel()
                    },
                    isPaused = { file.pauseRequested },
                    isCancelled = { file.cancellationRequested || file.pauseRequested || shouldStop() }
                ).getOrElse { throw it }
                    .also {
                        preserveConversation = false
                        file.punctuationResumeConversationPending = false
                        file.message = ""
                        publish()
                    }
            },
            requestPredictionWithRetry = { text, isRetry ->
                currentCoroutineContext().ensureActive()
                if (file.cancellationRequested || file.pauseRequested) {
                    throw CancellationException(string(R.string.auto_translate_punctuation_cancelled))
                }
                resetAiStream(file)
                conversation.predictPunctuation(
                    text = text,
                    clearConversation = provider == AiProviderConfig.LOCAL && !isRetry && !preserveConversation,
                    isPaused = { file.pauseRequested },
                    isCancelled = { file.cancellationRequested || file.pauseRequested }
                ).getOrElse { throw it }.also {
                    preserveConversation = false
                    file.punctuationResumeConversationPending = false
                }
            }
        )
        file.punctuationCompletedTexts = file.punctuationSession?.completedTextsSnapshot()?.toMutableList() ?: mutableListOf()
        persistQueueSnapshot()
        return document.copy(entries = punctuated)
    }

    private suspend fun predictTxtPunctuation(
        file: AutoTranslateFile,
        document: SubtitleDocument
    ): SubtitleDocument {
        val source = file.originalText ?: document.entries.joinToString("\n") { it.text }
        val session = file.textPunctuationSession ?: TxtPunctuationSession(
            source,
            { text, offset -> SubtitleTextSplitOps.wordStartAt(app, text, offset) },
            file.textPunctuationCompletedTexts
        ).also { file.textPunctuationSession = it }
        val provider = file.punctuationProvider ?: settingsManager.getAiPunctuationProvider().also { file.punctuationProvider = it }
        updateProcessingStage(
            file,
            ProcessingStage.PUNCTUATION_PREDICTION,
            session.completedCount,
            session.totalCount
        )
        if (session.totalCount == 0) return document
        val apiKey = settingsManager.getAiApiKey(provider)
        val model = settingsManager.getAiPunctuationModel(provider)
        val baseUrl = settingsManager.getAiBaseUrl(provider)
        if (file.punctuationConversation == null && provider == AiProviderConfig.LOCAL) {
            if (settingsManager.getLlmModelPath().isBlank()) {
                throw IllegalArgumentException(string(R.string.ai_processing_configuration_required))
            }
        } else if (file.punctuationConversation == null && (apiKey.isBlank() || model.isBlank() || baseUrl.isBlank())) {
            throw IllegalArgumentException(string(R.string.ai_processing_configuration_required))
        }
        val conversation = file.punctuationConversation ?: aiTranslationService.createConversation(
            app,
            provider,
            apiKey,
            model,
            "",
            settingsManager.getAiPunctuationCustomPrompt(),
            baseUrl,
            document.format,
            settingsManager.getAiPunctuationReasoningLevel(provider),
            "punctuation_${file.sessionId}",
            string(R.string.auto_translate_punctuation_history_title, file.fileName),
            settingsManager.isAiLocalPunctuationThinkingEnabled(),
            file.punctuationHistoryMessages
        ).also { file.punctuationConversation = it }
        file.activeConversation = conversation
        var preserveConversation = file.punctuationResumeConversationPending
        var consecutiveErrors = 0
        while (session.completedCount < session.totalCount) {
            currentCoroutineContext().ensureActive()
            if (file.cancellationRequested || file.pauseRequested) {
                throw CancellationException(string(R.string.auto_translate_punctuation_cancelled))
            }
            resetAiStream(file)
            publish()
            try {
                val predicted = requestTxtAiWithTagRetries(
                    request = {
                        conversation.predictPunctuation(
                            text = session.nextText,
                            clearConversation = provider == AiProviderConfig.LOCAL && !preserveConversation,
                            streamCallback = { publishAiStream(file, it) },
                            isPaused = { file.pauseRequested },
                            isCancelled = { file.cancellationRequested || file.pauseRequested }
                        ).getOrElse { throw it }
                    },
                    onRetry = { retries, error ->
                        file.message = error.message ?: string(R.string.auto_translate_processing_failed)
                        publish()
                        delay((retries * 500L).coerceAtMost(3_000L))
                    }
                )
                currentCoroutineContext().ensureActive()
                session.appendPrediction(predicted)
                preserveConversation = false
                file.punctuationResumeConversationPending = false
                file.processedLines = session.completedCount
                file.message = ""
                consecutiveErrors = 0
                publish()
                file.textPunctuationCompletedTexts = session.completedPredictionsSnapshot().toMutableList()
                persistQueueSnapshot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (file.pauseRequested) throw CancellationException("字幕处理已暂停")
                if (error is InvalidTxtAiResponseException) throw error
                consecutiveErrors++
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) throw error
                file.message = error.message ?: string(R.string.auto_translate_processing_failed)
                publish()
                delay((consecutiveErrors * 500L).coerceAtMost(3_000L))
            }
        }
        file.textPunctuationCompletedTexts = session.completedPredictionsSnapshot().toMutableList()
        persistQueueSnapshot()
        return document.copy(entries = listOf(document.entries.first().copy(text = session.predictedText)))
    }

    private suspend fun translateDocument(file: AutoTranslateFile, document: SubtitleDocument, config: TranslationConfig): SubtitleDocument {
        if (document.format == SubtitleParser.SubtitleFormat.TXT) {
            return translateTextDocument(file, document, config)
        }
        val entries = file.translationSourceEntries ?: document.entries.also { file.translationSourceEntries = it }
        val resumeStart = file.translationResumeStart
        val resumingConversation = file.translationConversation != null || file.translationResumeHistoryPending
        var requestStart = resumeStart ?: file.translatedTexts.size
        file.translationResumeStart = null
        updateProcessingStage(file, ProcessingStage.TRANSLATION, requestStart, entries.size)
        val translator = file.translationConversation ?: aiTranslationService.createConversation(
            app,
            config.provider,
            config.apiKey,
            config.model,
            config.targetLanguage,
            config.customPrompt,
            config.baseUrl,
            document.format,
            config.reasoningLevel,
            file.sessionId,
            string(R.string.auto_translate_translation_history_title, file.fileName, config.targetLanguage),
            config.thinkingEnabled,
            file.translationHistoryMessages
        ).also { file.translationConversation = it }
        file.activeConversation = translator
        var consecutiveErrors = 0
        try {
            var preserveConversation = resumingConversation
            while (requestStart < entries.size) {
                currentCoroutineContext().ensureActive()
                if (file.pauseRequested) {
                    pauseTranslation(file, requestStart, emptyList())
                }
                resetAiStream(file)
                publish()
                val resultStart = requestStart
                val result = translator.translateSubtitles(
                    subtitles = entries.drop(requestStart),
                    startPosition = requestStart + 1,
                    progressCallback = { current, _ ->
                        file.processedLines = requestStart + current
                        publish()
                    },
                    completedTranslationsCallback = { completedTranslations ->
                        replaceTranslatedTail(file, resultStart, completedTranslations)
                        file.processedLines = resultStart + completedTranslations.size
                        persistQueueSnapshot()
                    },
                    streamCallback = { content -> publishAiStream(file, content) },
                    streamProgressCallback = { current, _ ->
                        file.processedLines = requestStart + current
                        publish()
                    },
                    retryOnNoOutput = true,
                    onNoOutputRetry = { retries -> updateNoOutputRetryStatus(file, retries) },
                    preserveConversationOnFirstBatch = preserveConversation,
                    maxSubtitlesPerBatch = file.translationBatchSize,
                    isPaused = { file.pauseRequested },
                    isCancelled = { file.cancellationRequested }
                )
                preserveConversation = false
                file.translationResumeHistoryPending = false
                file.message = ""
                if (result.translations.isNotEmpty()) {
                    replaceTranslatedTail(file, requestStart, result.translations)
                    requestStart = file.translatedTexts.size
                }
                file.processedLines = requestStart
                publish()
                persistQueueSnapshot()
                if (file.pauseRequested) pauseTranslation(file, resultStart, result.translations)
                if (result.isComplete && result.translations.isNotEmpty()) {
                    consecutiveErrors = 0
                    continue
                }
                if (result.error != null) {
                    if (result.error is AiNoOutputTimeoutException) throw result.error
                    consecutiveErrors++
                    file.message = result.error.message ?: string(R.string.auto_translate_translation_failed)
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) throw result.error
                    publish()
                    delay((consecutiveErrors * 500L).coerceAtMost(3_000L))
                } else if (result.translations.isEmpty()) {
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        throw IllegalStateException(string(R.string.auto_translate_no_translation_result))
                    }
                }
            }
        } catch (cancelled: AiTranslationConversation.TranslationCancelledException) {
            pauseTranslation(file, requestStart, cancelled.translations)
        }
        return document.copy(entries = entries.mapIndexed { index, entry -> entry.copy(text = file.translatedTexts[index]) })
    }

    private fun replaceTranslatedTail(file: AutoTranslateFile, start: Int, translations: List<String>) {
        if (start < file.translatedTexts.size) {
            file.translatedTexts.subList(start, file.translatedTexts.size).clear()
        }
        file.translatedTexts += translations
    }

    private fun pauseTranslation(file: AutoTranslateFile, start: Int, translations: List<String>): Nothing {
        replaceTranslatedTail(file, start, translations)
        checkpointTranslationForResume(file)
        throw CancellationException("翻译已暂停")
    }

    private fun checkpointTranslationForResume(file: AutoTranslateFile) {
        val resumeIndex = SubtitleTranslationResumePolicy.resumeIndex(file.translatedTexts.size)
        file.translatedTexts.subList(resumeIndex, file.translatedTexts.size).clear()
        file.translationResumeStart = resumeIndex
        file.processedLines = resumeIndex
    }

    private suspend fun translateTextDocument(file: AutoTranslateFile, document: SubtitleDocument, config: TranslationConfig): SubtitleDocument {
        val session = file.textTranslationSession ?: TxtTranslationSession(
            if (file.punctuationPredictionCompleted) {
                file.textPunctuationSession?.predictedText
                    ?: file.originalText?.let { source ->
                        restoreTxtLineLayout(source, document.entries.associate { it.index to it.text })
                    }
                    ?: document.entries.joinToString("\n") { it.text }
            } else {
                file.originalText ?: document.entries.joinToString("\n") { it.text }
            },
            { text, offset -> SubtitleTextSplitOps.wordStartAt(app, text, offset) },
            file.textTranslationCompletedTexts
        ).also { file.textTranslationSession = it }
        updateProcessingStage(file, ProcessingStage.TRANSLATION, session.completedCount, session.totalCount)
        val resumingTranslationConversation = file.translationConversation != null || file.translationResumeHistoryPending
        val translator = file.translationConversation ?: aiTranslationService.createConversation(
            app,
            config.provider,
            config.apiKey,
            config.model,
            config.targetLanguage,
            config.customPrompt,
            config.baseUrl,
            document.format,
            config.reasoningLevel,
            file.sessionId,
            string(R.string.auto_translate_translation_history_title, file.fileName, config.targetLanguage),
            config.thinkingEnabled,
            file.translationHistoryMessages
        ).also { file.translationConversation = it }
        file.activeConversation = translator
        var consecutiveErrors = 0
        var preserveConversation = resumingTranslationConversation
        while (session.completedCount < session.totalCount) {
            currentCoroutineContext().ensureActive()
            if (file.cancellationRequested || file.pauseRequested) throw CancellationException(string(R.string.task_cancelled))
            resetAiStream(file)
            publish()
            try {
                val translated = requestTxtAiWithTagRetries(
                    request = {
                        translator.translateText(
                            text = session.nextText,
                            streamCallback = { publishAiStream(file, it) },
                            retryOnNoOutput = true,
                            onNoOutputRetry = { retries -> updateNoOutputRetryStatus(file, retries) },
                            preserveConversation = preserveConversation,
                            isPaused = { file.pauseRequested },
                            isCancelled = { file.cancellationRequested }
                        ).getOrElse { throw it }
                    },
                    onRetry = { retries, error ->
                        file.message = error.message ?: string(R.string.auto_translate_translation_failed)
                        publish()
                        delay((retries * 500L).coerceAtMost(3_000L))
                    }
                )
                currentCoroutineContext().ensureActive()
                session.appendTranslation(translated)
                preserveConversation = false
                file.translationResumeHistoryPending = false
                file.processedLines = session.completedCount
                file.message = ""
                consecutiveErrors = 0
                publish()
                file.textTranslationCompletedTexts = session.completedTranslationsSnapshot().toMutableList()
                persistQueueSnapshot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (file.pauseRequested) throw CancellationException("字幕处理已暂停")
                if (error is InvalidTxtAiResponseException || error is AiNoOutputTimeoutException) throw error
                consecutiveErrors++
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) throw error
                file.message = error.message ?: string(R.string.auto_translate_translation_failed)
                publish()
                delay((consecutiveErrors * 500L).coerceAtMost(3_000L))
            }
        }
        return document.copy(entries = listOf(document.entries.first().copy(text = session.translatedText)))
    }

    private suspend fun loadDocument(file: AutoTranslateFile): SubtitleDocument {
        val content = FileUtils.readUri(app, file.uri, settingsManager.getConfiguredEncoding())
            .ifEmpty { throw IllegalArgumentException(string(R.string.auto_translate_read_failed)) }
        val format = SubtitleParser.detectFormat(content, file.fileName)
        if (format == SubtitleParser.SubtitleFormat.TXT) file.originalText = content
        return SubtitleParser.parseDocument(content, file.fileName, format)
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

    private fun updateNoOutputRetryStatus(file: AutoTranslateFile, retries: Int) {
        file.lastStreamPublishedAt = 0L
        file.lastStreamPreview = ""
        file.message = string(R.string.auto_translate_translation_no_output_retry, retries, AI_NO_OUTPUT_MAX_RETRIES)
        publish()
    }

    private suspend fun updateProcessingStage(file: AutoTranslateFile, stage: ProcessingStage, processedLines: Int = 0, totalLines: Int = 0) {
        file.processingStage = stage; file.message = ""
        if (stage.progressLabelRes != null) { file.progressStage = stage; file.processedLines = processedLines; file.totalLines = totalLines }
        publish()
    }

    private fun persistQueueSnapshot() {
        runCatching {
            queueStore.save(
                AutoTranslateQueueSnapshot(
                    punctuationPredictionEnabled = currentState.punctuationPredictionEnabled,
                    translationEnabled = currentState.translationEnabled,
                    files = files.filter { it.status != FileStatus.COMPLETED }.map { file ->
                        val snapshotResumeStart = if (
                            file.status == FileStatus.RUNNING &&
                            file.processingStage == ProcessingStage.TRANSLATION &&
                            file.document?.format != SubtitleParser.SubtitleFormat.TXT
                        ) {
                            SubtitleTranslationResumePolicy.resumeIndex(file.translatedTexts.size)
                        } else {
                            file.translationResumeStart
                        }
                        AutoTranslateFileSnapshot(
                            uri = file.uri.toString(),
                            fileName = file.fileName,
                            fileSize = file.fileSize,
                            sessionId = file.sessionId,
                            status = if (file.status == FileStatus.RUNNING) FileStatus.PAUSED.name else file.status.name,
                            processingStage = file.processingStage.name,
                            totalLines = file.totalLines,
                            processedLines = file.processedLines,
                            progressStage = file.progressStage?.name,
                            documentContent = file.document?.let { document ->
                                runCatching { SubtitleParser.serialize(document) }.getOrNull()
                            },
                            originalText = file.originalText,
                            punctuationPredictionCompleted = file.punctuationPredictionCompleted,
                            translatedTexts = file.translatedTexts.toList(),
                            translationResumeStart = snapshotResumeStart,
                            translationBatchSize = file.translationBatchSize,
                            translationConfig = file.translationConfig?.let { config ->
                                PersistedTranslationConfig(
                                    provider = config.provider,
                                    model = config.model,
                                    targetLanguage = config.targetLanguage,
                                    customPrompt = config.customPrompt,
                                    baseUrl = config.baseUrl,
                                    reasoningLevel = config.reasoningLevel.name,
                                    thinkingEnabled = config.thinkingEnabled
                                )
                            },
                            punctuationProvider = file.punctuationProvider,
                            punctuationEnabled = file.punctuationEnabledForRun,
                            translationEnabled = file.translationEnabledForRun,
                            overwriteOutput = file.overwriteOutput,
                            punctuationCompletedTexts = file.punctuationSession?.completedTextsSnapshot()
                                ?: file.punctuationCompletedTexts.toList(),
                            textPunctuationPredictions = file.textPunctuationSession?.completedPredictionsSnapshot()
                                ?: file.textPunctuationCompletedTexts.toList(),
                            textTranslations = file.textTranslationSession?.completedTranslationsSnapshot()
                                ?: file.textTranslationCompletedTexts.toList(),
                            punctuationResumeHistory = file.punctuationResumeConversationPending ||
                                file.punctuationConversation != null,
                            translationResumeHistory = file.translationResumeHistoryPending ||
                                file.translationConversation != null
                        )
                    }
                )
            )
        }
    }

    private fun pauseRunningFilesForPersistence() {
        files.filter { it.status == FileStatus.RUNNING }.forEach { file ->
            file.pauseRequested = true
            if (file.processingStage == ProcessingStage.PUNCTUATION_PREDICTION) {
                file.punctuationResumeConversationPending = file.punctuationConversation != null
            }
            if (file.processingStage == ProcessingStage.TRANSLATION) {
                if (file.document?.format == SubtitleParser.SubtitleFormat.TXT) {
                    file.translationResumeHistoryPending = file.translationConversation != null
                } else {
                    checkpointTranslationForResume(file)
                }
            }
            file.status = FileStatus.PAUSED
            file.message = string(R.string.auto_translate_status_paused)
            file.activeConversation?.cancel()
        }
        persistQueueSnapshot()
    }

    fun requestRemove(key: String) {
        val file = files.firstOrNull { it.sessionId == key } ?: return
        if (file.status == FileStatus.RUNNING || file.status == FileStatus.PAUSED) {
            pendingRemovalKey = key
            setState { copy(removeFileName = file.fileName) }
        } else {
            removeFile(file)
        }
    }
    fun confirmRemove() { val file = files.firstOrNull { it.sessionId == pendingRemovalKey }; pendingRemovalKey = null; setState { copy(removeFileName = null) }; file?.let(::removeFile) }
    fun dismissRemove() { pendingRemovalKey = null; setState { copy(removeFileName = null) } }
    private fun cancelFile(file: AutoTranslateFile) { file.cancellationRequested = true; activeJobs[file.sessionId]?.cancel(); file.activeConversation?.cancel() }
    private fun removeFile(file: AutoTranslateFile) { cancelFile(file); activeJobs.remove(file.sessionId); files.remove(file); queueRunning = activeJobs.values.any { it.isActive }; publish(); persistQueueSnapshot() }

    fun requestBack(): Boolean {
        if (!queueRunning) return true
        setState { copy(showExitConfirmation = true) }
        return false
    }
    fun dismissExit() = setState { copy(showExitConfirmation = false) }
    fun stopAndExit() {
        setState { copy(showExitConfirmation = false) }
        pauseRunningFilesForPersistence()
        activeJobs.values.forEach(Job::cancel)
        queueRunning = false
        sendEvent(AutoTranslateEvent.Exit)
    }

    private fun restoreOutputDirectory() {
        val uri = settingsManager.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)?.let(Uri::parse) ?: return
        outputDirectoryUri = uri; outputDirectoryLabel = DirectoryDisplayPath.fromUri(app, uri)
    }
    private fun publish() {
        val items = files.map { file ->
            val status = when (file.status) {
                FileStatus.WAITING -> string(R.string.auto_translate_status_waiting)
                FileStatus.RUNNING -> string(file.processingStage.displayNameRes)
                FileStatus.PAUSED -> string(R.string.auto_translate_status_paused)
                FileStatus.COMPLETED -> string(R.string.auto_translate_status_completed)
                FileStatus.STOPPED -> string(R.string.auto_translate_status_stopped)
            }
            AutoTranslateFileUi(file.sessionId, file.fileName, FileUtils.formatFileSize(file.fileSize), status, file.message, file.totalLines, file.processedLines, file.progressStage?.progressLabelRes?.let(::string), file.status == FileStatus.RUNNING && file.processingStage == file.progressStage, file.status == FileStatus.STOPPED || file.status == FileStatus.PAUSED, file.status == FileStatus.RUNNING && file.processingStage != ProcessingStage.SAVING && !file.pauseRequested)
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

    override fun onCleared() {
        pauseRunningFilesForPersistence()
        activeJobs.values.forEach(Job::cancel)
        activeJobs.clear()
        super.onCleared()
    }
}
