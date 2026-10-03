package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.feature.ui.AutoTranslateFileUi
import com.subtitleedit.feature.ui.AutoTranslateScreen
import com.subtitleedit.feature.ui.AutoTranslateUiState
import com.subtitleedit.repository.AiTranslationService
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.AiTranslationConversation
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleOutputWriter
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitlePunctuationPredictor
import com.subtitleedit.util.subtitle.SubtitleDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Multiple-file AI subtitle translation. Each source file owns one history session. */
class AutoTranslateActivity : AppComposeActivity() {

    companion object {
        private const val OUTPUT_DIRECTORY_KEY = "auto_translate"
        private const val MAX_CONSECUTIVE_ERRORS = 5
        const val EXTRA_INITIAL_FILE_URIS = "auto_translate_initial_file_uris"
    }

    private lateinit var settingsManager: SettingsManager
    private val aiTranslationService: AiTranslationService
        get() = (application as SubtitleEditApplication).dependencies.aiTranslationService
    private val files = mutableListOf<AutoTranslateFile>()
    private val activeJobs = mutableMapOf<String, Job>()
    private var queueRunning = false
    private var outputDirectoryUri: Uri? = null
    private var outputDirectoryLabel: String? = null
    private var uiState by mutableStateOf(AutoTranslateUiState())
    private var pendingConfig: TranslationConfig? = null
    private var pendingOutputUri: Uri? = null
    private var pendingRemovalKey: String? = null

    private data class TranslationConfig(
        val provider: String,
        val apiKey: String,
        val model: String,
        val targetLanguage: String,
        val customPrompt: String,
        val baseUrl: String,
        val reasoningLevel: AiProviderConfig.ReasoningLevel
    )

    private enum class FileStatus { WAITING, RUNNING, COMPLETED, STOPPED }

    private enum class ProcessingStage(val displayName: String, val progressLabel: String? = null) {
        READING("读取字幕中"),
        PUNCTUATION_PREDICTION("标点预测处理中", "标点预测"),
        TRANSLATION("翻译中", "翻译"),
        SAVING("保存中")
    }

    private class AutoTranslateFile(
        val uri: Uri,
        val fileName: String,
        val fileSize: Long,
        val sessionId: String = UUID.randomUUID().toString()
    ) {
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
        // Keep the output policy with the file so a retry uses the same choice made
        // in the conflict dialog instead of falling back to the startFile default.
        var overwriteOutput = false
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            if (files.any { it.uri == uri }) return@forEach
            files += AutoTranslateFile(
                uri = uri,
                fileName = getFileNameFromUri(uri) ?: "未知文件",
                fileSize = getFileSizeFromUri(uri)
            )
        }
        updateScreenState()
    }

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        outputDirectoryUri = uri
        outputDirectoryLabel = DirectoryDisplayPath.fromUri(this, uri)
        settingsManager.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
        updateScreenState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager.getInstance(this)
        setContent {
            SubtitleEditComposeTheme {
                AutoTranslateScreen(
                    state = uiState,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() },
                    onSettings = { startActivity(Intent(this, AiSettingsActivity::class.java)) },
                    onSelectFiles = { filePickerLauncher.launch(arrayOf("text/*", "application/*")) },
                    onPunctuationPredictionChange = { enabled ->
                        uiState = uiState.copy(punctuationPredictionEnabled = enabled)
                    },
                    onTranslationChange = { enabled ->
                        uiState = uiState.copy(translationEnabled = enabled)
                    },
                    onSelectOutputDirectory = { directoryPickerLauncher.launch(outputDirectoryUri) },
                    onStart = ::startQueuedFiles,
                    onRetry = { key ->
                        files.firstOrNull { it.sessionId == key }?.let { startFile(it, retry = true) }
                    },
                    onRemove = ::requestRemoveFile,
                    onConfirmRemove = ::confirmPendingRemoval,
                    onDismissRemove = ::dismissPendingRemoval,
                    onDismissOutputConflict = ::dismissOutputConflict,
                    onOverwriteOutput = { resolveOutputConflict(overwrite = true) },
                    onRenameOutput = { resolveOutputConflict(overwrite = false) },
                    onConfirmExit = ::stopAndExit,
                    onDismissExit = { uiState = uiState.copy(showExitConfirmation = false) }
                )
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!queueRunning) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                uiState = uiState.copy(showExitConfirmation = true)
            }
        })
        restoreOutputDirectory()
        addInitialFiles(initialFileUris())
        updateScreenState()
    }

    private fun restoreOutputDirectory() {
        val uri = settingsManager.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)
            ?.let(Uri::parse)
            ?: return
        outputDirectoryUri = uri
        outputDirectoryLabel = DirectoryDisplayPath.fromUri(this, uri)
    }

    private fun addInitialFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach { uri ->
            if (files.any { it.uri == uri }) return@forEach
            files += AutoTranslateFile(
                uri = uri,
                fileName = getFileNameFromUri(uri) ?: "未知文件",
                fileSize = getFileSizeFromUri(uri)
            )
        }
        updateScreenState()
    }

    @Suppress("DEPRECATION")
    private fun initialFileUris(): List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableArrayListExtra(EXTRA_INITIAL_FILE_URIS, Uri::class.java).orEmpty()
    } else {
        intent.getParcelableArrayListExtra<Uri>(EXTRA_INITIAL_FILE_URIS).orEmpty()
    }

    private fun startQueuedFiles() {
        if (files.isEmpty()) {
            OverwritingToast.makeText(this, "请先添加要翻译的文件", Toast.LENGTH_SHORT).show()
            return
        }
        val config = if (uiState.translationEnabled) {
            readTranslationConfig()
        } else {
            null
        }
        if (!validateSelectedFeatures(config)) return
        val outputUri = outputDirectoryUri ?: Uri.fromFile(getTranslateOutputDirectory())
        val hasConflict = files.any { file ->
            val extension = outputExtension(file)
            SubtitleOutputWriter.exists(
                this,
                outputUri,
                file.fileName.substringBeforeLast("."),
                extension
            )
        }
        if (hasConflict) {
            pendingConfig = config
            pendingOutputUri = outputUri
            uiState = uiState.copy(showOutputConflict = true)
        } else {
            beginQueuedFiles(config, outputUri, overwriteOutput = false)
        }
    }

    private fun resolveOutputConflict(overwrite: Boolean) {
        val config = pendingConfig
        val outputUri = pendingOutputUri
        dismissOutputConflict()
        if (outputUri != null) beginQueuedFiles(config, outputUri, overwrite)
    }

    private fun dismissOutputConflict() {
        pendingConfig = null
        pendingOutputUri = null
        uiState = uiState.copy(showOutputConflict = false)
    }

    private fun beginQueuedFiles(
        config: TranslationConfig?,
        outputUri: Uri,
        overwriteOutput: Boolean
    ) {
        val queuedFiles = files.filter {
            it.status == FileStatus.WAITING || it.status == FileStatus.STOPPED
        }
        if (queuedFiles.isEmpty()) return
        queuedFiles
            .forEach {
                it.overwriteOutput = overwriteOutput
                startFile(
                    file = it,
                    retry = it.status == FileStatus.STOPPED,
                    config = config,
                    outputUri = outputUri,
                    overwriteOutput = overwriteOutput
                )
            }
    }

    private fun startFile(
        file: AutoTranslateFile,
        retry: Boolean = false,
        config: TranslationConfig? = if (uiState.translationEnabled) readTranslationConfig() else null,
        outputUri: Uri = outputDirectoryUri ?: Uri.fromFile(getTranslateOutputDirectory()),
        overwriteOutput: Boolean = file.overwriteOutput
    ) {
        if (activeJobs[file.sessionId]?.isActive == true) return
        if (!validateSelectedFeatures(config)) return
        queueRunning = true
        if (retry) file.message = ""
        file.cancellationRequested = false
        file.status = FileStatus.RUNNING
        file.processingStage = ProcessingStage.READING
        updateTranslationControls()
        activeJobs[file.sessionId] = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            processFile(file, config, outputUri, overwriteOutput)
        }
    }

    private suspend fun processFile(
        file: AutoTranslateFile,
        initialConfig: TranslationConfig?,
        outputUri: Uri,
        overwriteOutput: Boolean
    ) {
        try {
            var document = file.document ?: loadDocument(file).also { file.document = it }
            val entries = document.entries
            if (file.progressStage == null) file.totalLines = entries.size
            postFileUpdate(file) { }
            if (entries.isEmpty()) throw IllegalArgumentException("未检测到可翻译的字幕行")

            if (isFeatureEnabled(Feature.PUNCTUATION_PREDICTION) && !file.punctuationPredictionCompleted) {
                document = applyPunctuationPrediction(file, document)
                file.document = document
                file.punctuationPredictionCompleted = true
            }
            if (isFeatureEnabled(Feature.TRANSLATION)) {
                val config = initialConfig ?: readTranslationConfig()
                    ?: throw IllegalArgumentException(getString(R.string.ai_processing_configuration_required))
                document = translateDocument(file, document, config)
                file.document = document
            }

            updateProcessingStage(file, ProcessingStage.SAVING)
            val outputText = SubtitleParser.serialize(document)
            currentCoroutineContext().ensureActive()
            SubtitleOutputWriter.writeText(
                this,
                outputUri,
                file.fileName.substringBeforeLast("."),
                outputExtension(file),
                outputText,
                overwrite = overwriteOutput
            )
            file.status = FileStatus.COMPLETED
            file.message = "处理完成"
            postFileUpdate(file) { }
        } catch (_: CancellationException) {
            file.status = FileStatus.STOPPED
            file.message = "已停止"
            postFileUpdate(file) { }
        } catch (error: Exception) {
            file.status = FileStatus.STOPPED
            file.message = error.message ?: "处理失败"
            postFileUpdate(file) { }
        } finally {
            file.activeConversation = null
            withContext(NonCancellable + kotlinx.coroutines.Dispatchers.Main) {
                activeJobs.remove(file.sessionId)
                if (activeJobs.values.none { it.isActive }) {
                    queueRunning = false
                }
                updateTranslationControls()
            }
        }
    }

    private enum class Feature { PUNCTUATION_PREDICTION, TRANSLATION }

    private suspend fun isFeatureEnabled(feature: Feature): Boolean = withContext(kotlinx.coroutines.Dispatchers.Main) {
        when (feature) {
            Feature.PUNCTUATION_PREDICTION -> uiState.punctuationPredictionEnabled
            Feature.TRANSLATION -> uiState.translationEnabled
        }
    }

    private suspend fun applyPunctuationPrediction(
        file: AutoTranslateFile,
        document: SubtitleDocument
    ): SubtitleDocument {
        val session = file.punctuationSession ?: SubtitlePunctuationPredictor.Session(
            SubtitlePunctuationPredictor.prepareEntries(document.entries)
        ).also { file.punctuationSession = it }
        updateProcessingStage(file, ProcessingStage.PUNCTUATION_PREDICTION, session.processedCount, session.totalCount)
        if (session.totalCount == 0) return document
        val provider = settingsManager.getAiPunctuationProvider()
        val apiKey = settingsManager.getAiApiKey(provider)
        val model = settingsManager.getAiPunctuationModel(provider)
        val baseUrl = settingsManager.getAiBaseUrl(provider)
        if (apiKey.isBlank() || model.isBlank() || baseUrl.isBlank()) {
            throw IllegalArgumentException(getString(R.string.ai_processing_configuration_required))
        }
        val conversation = aiTranslationService.createConversation(
            context = this,
            provider = provider,
            apiKey = apiKey,
            model = model,
            targetLanguage = "",
            customPrompt = settingsManager.getAiPunctuationCustomPrompt(),
            baseUrl = baseUrl,
            subtitleFormat = document.format,
            reasoningLevel = settingsManager.getAiPunctuationReasoningLevel(provider),
            historySessionId = "punctuation_${file.sessionId}",
            historyTitle = "标点预测 · ${file.fileName}"
        )
        file.activeConversation = conversation
        val punctuatedEntries = session.run(onProgress = { processed, total ->
            postFileUpdate(file) {
                file.processedLines = processed
                file.totalLines = total
            }
        }) { text ->
            currentCoroutineContext().ensureActive()
            if (file.cancellationRequested) throw CancellationException("标点预测已取消")
            conversation.predictPunctuation(text) { file.cancellationRequested }
                .getOrElse { throw it }
        }
        return document.copy(entries = punctuatedEntries)
    }

    private suspend fun translateDocument(
        file: AutoTranslateFile,
        document: SubtitleDocument,
        config: TranslationConfig
    ): SubtitleDocument {
        val entries = document.entries
        updateProcessingStage(file, ProcessingStage.TRANSLATION, file.translatedTexts.size, entries.size)
        val translator = aiTranslationService.createConversation(
            context = this,
            provider = config.provider,
            apiKey = config.apiKey,
            model = config.model,
            targetLanguage = config.targetLanguage,
            customPrompt = config.customPrompt,
            baseUrl = config.baseUrl,
            subtitleFormat = document.format,
            reasoningLevel = config.reasoningLevel,
            historySessionId = file.sessionId,
            historyTitle = "AI字幕处理 · ${file.fileName} · ${config.targetLanguage}"
        )
        file.activeConversation = translator
        var consecutiveErrors = 0
        while (file.translatedTexts.size < entries.size) {
            currentCoroutineContext().ensureActive()
            postFileUpdate(file) { file.message = "" }
            val completed = file.translatedTexts.size
            val result = translator.translateSubtitles(
                subtitles = entries.drop(completed),
                startPosition = completed + 1,
                progressCallback = { current, _ ->
                    postFileUpdate(file) { file.processedLines = completed + current }
                },
                isCancelled = { file.cancellationRequested }
            )
            file.translatedTexts += result.translations
            file.processedLines = file.translatedTexts.size
            postFileUpdate(file) { }
            if (result.isComplete && result.translations.isNotEmpty()) {
                consecutiveErrors = 0
                continue
            }
            if (result.error != null) {
                consecutiveErrors++
                file.message = result.error.message ?: "翻译失败"
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    throw result.error
                }
                postFileUpdate(file) { }
                delay((consecutiveErrors * 500L).coerceAtMost(3_000L))
            } else if (result.translations.isEmpty()) {
                consecutiveErrors++
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    throw IllegalStateException("翻译未返回结果")
                }
            }
        }
        return document.copy(
            entries = entries.mapIndexed { index, entry ->
                entry.copy(text = file.translatedTexts[index])
            }
        )
    }

    private suspend fun loadDocument(file: AutoTranslateFile): SubtitleDocument {
        val content = contentResolver.openInputStream(file.uri)?.use {
            it.bufferedReader(settingsManager.getDefaultEncoding()).readText()
        } ?: throw IllegalArgumentException("无法读取文件")
        val format = SubtitleParser.detectFormat(content, file.fileName)
        return SubtitleParser.parseDocument(content, file.fileName, format)
    }

    private fun validateSelectedFeatures(translationConfig: TranslationConfig?): Boolean {
        val punctuationPredictionSelected = uiState.punctuationPredictionEnabled
        val translationSelected = uiState.translationEnabled
        if (!punctuationPredictionSelected && !translationSelected) {
            OverwritingToast.makeText(this, "请至少选择一项处理功能", Toast.LENGTH_SHORT).show()
            return false
        }
        if ((punctuationPredictionSelected && !isPunctuationAiConfigured()) ||
            (translationSelected && translationConfig == null)
        ) {
            OverwritingToast.makeText(
                this,
                getString(R.string.ai_processing_configuration_required),
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        return true
    }

    private fun readTranslationConfig(): TranslationConfig? {
        val provider = settingsManager.getAiTranslationProvider()
        val apiKey = settingsManager.getAiApiKey(provider)
        val model = settingsManager.getAiModel(provider)
        val targetLanguage = settingsManager.getAiTargetLanguage()
        val baseUrl = settingsManager.getAiBaseUrl(provider)
        if (apiKey.isBlank() || model.isBlank() || targetLanguage.isBlank() || baseUrl.isBlank()) {
            return null
        }
        return TranslationConfig(
            provider = provider,
            apiKey = apiKey,
            model = model,
            targetLanguage = targetLanguage,
            customPrompt = settingsManager.getAiCustomPrompt(),
            baseUrl = baseUrl,
            reasoningLevel = settingsManager.getAiReasoningLevel(provider)
        )
    }

    private fun isPunctuationAiConfigured(): Boolean {
        val provider = settingsManager.getAiPunctuationProvider()
        return settingsManager.getAiApiKey(provider).isNotBlank() &&
            settingsManager.getAiPunctuationModel(provider).isNotBlank() &&
            settingsManager.getAiBaseUrl(provider).isNotBlank()
    }

    private suspend fun updateProcessingStage(
        file: AutoTranslateFile,
        stage: ProcessingStage,
        processedLines: Int = 0,
        totalLines: Int = 0
    ) {
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            postFileUpdate(file) {
                file.processingStage = stage
                file.message = ""
                if (stage.progressLabel != null) {
                    file.progressStage = stage
                    file.processedLines = processedLines
                    file.totalLines = totalLines
                }
            }
        }
    }

    private fun postFileUpdate(file: AutoTranslateFile, update: () -> Unit) {
        runOnUiThread {
            if (file !in files) return@runOnUiThread
            update()
            updateScreenState()
        }
    }

    private fun requestRemoveFile(key: String) {
        val file = files.firstOrNull { it.sessionId == key } ?: return
        if (file.status != FileStatus.RUNNING) {
            removeFile(file)
            return
        }
        pendingRemovalKey = file.sessionId
        uiState = uiState.copy(removeFileName = file.fileName)
    }

    private fun confirmPendingRemoval() {
        val file = files.firstOrNull { it.sessionId == pendingRemovalKey }
        pendingRemovalKey = null
        uiState = uiState.copy(removeFileName = null)
        file?.let(::removeFile)
    }

    private fun dismissPendingRemoval() {
        pendingRemovalKey = null
        uiState = uiState.copy(removeFileName = null)
    }

    private fun cancelFile(file: AutoTranslateFile) {
        file.cancellationRequested = true
        activeJobs[file.sessionId]?.cancel()
        file.activeConversation?.cancel()
    }

    private fun removeFile(file: AutoTranslateFile) {
        cancelFile(file)
        activeJobs.remove(file.sessionId)
        files.remove(file)
        queueRunning = activeJobs.values.any { it.isActive }
        updateTranslationControls()
    }

    private fun updateTranslationControls() {
        updateScreenState()
    }

    private fun updateScreenState() {
        val fileItems = files.map { file ->
            val status = when (file.status) {
                FileStatus.WAITING -> "等待"
                FileStatus.RUNNING -> file.processingStage.displayName
                FileStatus.COMPLETED -> "已完成"
                FileStatus.STOPPED -> "已停止，点击重试"
            }
            AutoTranslateFileUi(
                key = file.sessionId,
                fileName = file.fileName,
                fileSizeLabel = FileUtils.formatFileSize(file.fileSize),
                status = status,
                statusMessage = file.message,
                totalLines = file.totalLines,
                processedLines = file.processedLines,
                progressLabel = file.progressStage?.progressLabel,
                showStageProgress = file.status == FileStatus.RUNNING &&
                    file.processingStage == file.progressStage,
                canRetry = file.status == FileStatus.STOPPED
            )
        }
        val progressSummary = if (!queueRunning) "" else buildString {
            val activeCount = files.count { it.status == FileStatus.RUNNING }
            val completedFiles = files.count { it.status == FileStatus.COMPLETED }
            append("正在处理：$activeCount 个文件 · 已完成 $completedFiles/${files.size} 个文件")
            for (stage in ProcessingStage.entries.filter { it.progressLabel != null }) {
                val stageFiles = files.filter { it.progressStage == stage }
                if (stageFiles.isEmpty()) continue
                val totalLines = stageFiles.sumOf { it.totalLines }
                val processedLines = stageFiles.sumOf { it.processedLines }
                append(" · ${stage.progressLabel} 已处理 $processedLines/$totalLines 条字幕")
            }
        }
        uiState = uiState.copy(
            files = fileItems,
            outputDirectory = outputDirectoryLabel,
            queueRunning = queueRunning,
            progressSummary = progressSummary
        )
    }

    private fun stopAndExit() {
        uiState = uiState.copy(showExitConfirmation = false)
        files.forEach(::cancelFile)
        queueRunning = false
        finish()
    }

    private fun outputExtension(file: AutoTranslateFile): String = when (file.document?.format) {
        SubtitleParser.SubtitleFormat.SRT -> "srt"
        SubtitleParser.SubtitleFormat.LRC -> "lrc"
        SubtitleParser.SubtitleFormat.VTT -> "vtt"
        SubtitleParser.SubtitleFormat.TXT -> "txt"
        SubtitleParser.SubtitleFormat.ASS -> "ass"
        SubtitleParser.SubtitleFormat.SSA -> "ssa"
        else -> file.fileName.substringAfterLast('.', "srt").lowercase()
    }

    private fun getTranslateOutputDirectory(): File {
        val dir = File(FileUtils.getDownloadDirectory(), "SubtitleEdit/Translate")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        val displayName = runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
        if (!displayName.isNullOrBlank()) return displayName

        return when (uri.scheme) {
            "file" -> uri.path?.let(::File)?.name
            else -> DocumentFile.fromSingleUri(this, uri)?.name
        }
    }

    private fun getFileSizeFromUri(uri: Uri): Long {
        val size = runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        if (size > 0L) return size
        return if (uri.scheme == "file") uri.path?.let(::File)?.length() ?: 0L else 0L
    }

}
