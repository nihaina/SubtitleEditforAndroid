package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.databinding.ActivityAutoTranslateBinding
import com.subtitleedit.repository.AiTranslationService
import com.subtitleedit.repository.DefaultAiTranslationService
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.AiTranslationConversation
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SemanticSubtitleMerger
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
class AutoTranslateActivity : AppCompatActivity() {

    companion object {
        private const val OUTPUT_DIRECTORY_KEY = "auto_translate"
        private const val MAX_CONSECUTIVE_ERRORS = 5
        const val EXTRA_INITIAL_FILE_URIS = "auto_translate_initial_file_uris"
    }

    private lateinit var binding: ActivityAutoTranslateBinding
    private lateinit var settingsManager: SettingsManager
    private val aiTranslationService: AiTranslationService
        get() = (application as SubtitleEditApplication).dependencies.aiTranslationService
    private lateinit var adapter: AutoTranslateAdapter
    private val files = mutableListOf<AutoTranslateFile>()
    private val activeJobs = mutableMapOf<String, Job>()
    private var queueRunning = false
    private var outputDirectoryUri: Uri? = null

    private data class TranslationConfig(
        val provider: String,
        val apiKey: String,
        val model: String,
        val targetLanguage: String,
        val customPrompt: String,
        val baseUrl: String,
        val contextWindowTokens: Int,
        val reasoningLevel: AiProviderConfig.ReasoningLevel
    )

    private enum class FileStatus { WAITING, RUNNING, COMPLETED, STOPPED }

    private enum class ProcessingStage(val displayName: String, val progressLabel: String? = null) {
        READING("读取字幕中"),
        SEMANTIC_MERGE("语义合并处理中", "语义合并"),
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
        var semanticMergeCompleted = false
        var punctuationPredictionCompleted = false
        var semanticSession: SemanticSubtitleMerger.Session? = null
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
        adapter.notifyDataSetChanged()
        updateFileListVisibility()
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
        binding.tvOutputDir.text = DirectoryDisplayPath.fromUri(this, uri)
        settingsManager.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoTranslateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsManager = SettingsManager.getInstance(this)
        setupToolbar()
        setupList()
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("text/*", "application/*"))
        }
        binding.btnSelectOutputDir.setOnClickListener {
            directoryPickerLauncher.launch(outputDirectoryUri)
        }
        binding.btnStartTranslate.setOnClickListener { startQueuedFiles() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!queueRunning) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                confirmExitWhileTranslating()
            }
        })
        restoreOutputDirectory()
        addInitialFiles(initialFileUris())
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_auto_translate, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_auto_translate_settings -> {
            startActivity(Intent(this, AiSettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun setupList() {
        adapter = AutoTranslateAdapter(
            onItemClick = { file ->
                if (file.status == FileStatus.STOPPED) startFile(file, retry = true)
            },
            onRemoveClick = ::confirmRemoveFile
        )
        binding.rvFileList.layoutManager = LinearLayoutManager(this)
        binding.rvFileList.adapter = adapter
        updateFileListVisibility()
    }

    private fun restoreOutputDirectory() {
        val uri = settingsManager.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)
            ?.let(Uri::parse)
            ?: return
        outputDirectoryUri = uri
        binding.tvOutputDir.text = DirectoryDisplayPath.fromUri(this, uri)
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
        adapter.notifyDataSetChanged()
        updateFileListVisibility()
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
        val config = if (binding.switchOneClickTranslation.isChecked) {
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
            AlertDialog.Builder(this)
                .setTitle("文件名冲突")
                .setMessage("输出目录中已存在同名字幕文件。请选择处理方式。")
                .setPositiveButton("覆盖") { _, _ ->
                    beginQueuedFiles(config, outputUri, overwriteOutput = true)
                }
                .setNeutralButton("自动重命名") { _, _ ->
                    beginQueuedFiles(config, outputUri, overwriteOutput = false)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            beginQueuedFiles(config, outputUri, overwriteOutput = false)
        }
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
        config: TranslationConfig? = if (binding.switchOneClickTranslation.isChecked) readTranslationConfig() else null,
        outputUri: Uri = outputDirectoryUri ?: Uri.fromFile(getTranslateOutputDirectory()),
        overwriteOutput: Boolean = file.overwriteOutput
    ) {
        if (activeJobs[file.sessionId]?.isActive == true) return
        if (!validateSelectedFeatures(config)) return
        queueRunning = true
        updateTranslationControls()
        if (retry) file.message = ""
        file.cancellationRequested = false
        file.status = FileStatus.RUNNING
        file.processingStage = ProcessingStage.READING
        adapter.notifyItemChanged(files.indexOf(file))
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

            if (isFeatureEnabled(Feature.SEMANTIC_MERGE) && !file.semanticMergeCompleted) {
                document = applySemanticMerge(file, document)
                file.document = document
                file.semanticMergeCompleted = true
            }
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

    private enum class Feature { SEMANTIC_MERGE, PUNCTUATION_PREDICTION, TRANSLATION }

    private suspend fun isFeatureEnabled(feature: Feature): Boolean = withContext(kotlinx.coroutines.Dispatchers.Main) {
        when (feature) {
            Feature.SEMANTIC_MERGE -> binding.switchSemanticMerge.isChecked
            Feature.PUNCTUATION_PREDICTION -> binding.switchPunctuationPrediction.isChecked
            Feature.TRANSLATION -> binding.switchOneClickTranslation.isChecked
        }
    }

    private suspend fun applySemanticMerge(
        file: AutoTranslateFile,
        document: SubtitleDocument
    ): SubtitleDocument {
        val session = file.semanticSession ?: SemanticSubtitleMerger.Session(
            SemanticSubtitleMerger.prepareSubtitleEntriesForAi(document.entries)
        ).also { file.semanticSession = it }
        updateProcessingStage(file, ProcessingStage.SEMANTIC_MERGE, session.processedCount, session.totalCount)
        if (session.totalCount == 0) return document
        val provider = settingsManager.getAiSemanticProvider()
        val apiKey = settingsManager.getAiApiKey(provider)
        val model = settingsManager.getAiSemanticModel(provider)
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
            customPrompt = settingsManager.getAiSemanticCustomPrompt(),
            baseUrl = baseUrl,
            contextWindowTokens = settingsManager.getAiSemanticContextWindowTokens(provider),
            subtitleFormat = document.format,
            reasoningLevel = settingsManager.getAiSemanticReasoningLevel(provider),
            historySessionId = "semantic_${file.sessionId}",
            historyTitle = "语义合并 · ${file.fileName}"
        )
        file.activeConversation = conversation
        val mergedEntries = session.run(onProgress = { processed, total ->
            postFileUpdate(file) {
                file.processedLines = processed
                file.totalLines = total
            }
        }) { text ->
            currentCoroutineContext().ensureActive()
            if (file.cancellationRequested) throw CancellationException("语义合并已取消")
            conversation.restorePunctuation(text) { file.cancellationRequested }
                .getOrElse { throw it }
        }
        return document.copy(entries = mergedEntries)
    }

    private suspend fun applyPunctuationPrediction(
        file: AutoTranslateFile,
        document: SubtitleDocument
    ): SubtitleDocument {
        val session = file.punctuationSession ?: SubtitlePunctuationPredictor.Session(
            SemanticSubtitleMerger.prepareSubtitleEntriesForAi(document.entries)
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
            contextWindowTokens = settingsManager.getAiPunctuationContextWindowTokens(provider),
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
            contextWindowTokens = config.contextWindowTokens,
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
        val semanticMergeSelected = binding.switchSemanticMerge.isChecked
        val punctuationPredictionSelected = binding.switchPunctuationPrediction.isChecked
        val translationSelected = binding.switchOneClickTranslation.isChecked
        if (!semanticMergeSelected && !punctuationPredictionSelected && !translationSelected) {
            OverwritingToast.makeText(this, "请至少选择一项处理功能", Toast.LENGTH_SHORT).show()
            return false
        }
        if ((semanticMergeSelected && !isSemanticAiConfigured()) ||
            (punctuationPredictionSelected && !isPunctuationAiConfigured()) ||
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
            contextWindowTokens = settingsManager.getAiContextWindowTokens(provider),
            reasoningLevel = settingsManager.getAiReasoningLevel(provider)
        )
    }

    private fun isSemanticAiConfigured(): Boolean {
        val provider = settingsManager.getAiSemanticProvider()
        return settingsManager.getAiApiKey(provider).isNotBlank() &&
            settingsManager.getAiSemanticModel(provider).isNotBlank() &&
            settingsManager.getAiBaseUrl(provider).isNotBlank()
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
            val index = files.indexOf(file)
            if (index < 0) return@runOnUiThread
            update()
            adapter.notifyItemChanged(index)
            updateFileListVisibility()
            updateTranslationProgress()
        }
    }

    private fun confirmRemoveFile(file: AutoTranslateFile) {
        if (file.status != FileStatus.RUNNING) {
            removeFile(file)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("移除文件")
            .setMessage("当前文件正在处理，是否移除？")
            .setPositiveButton("移除") { _, _ -> removeFile(file) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun cancelFile(file: AutoTranslateFile) {
        file.cancellationRequested = true
        activeJobs[file.sessionId]?.cancel()
        file.activeConversation?.cancel()
    }

    private fun removeFile(file: AutoTranslateFile) {
        cancelFile(file)
        activeJobs.remove(file.sessionId)
        val index = files.indexOf(file)
        if (index >= 0) {
            files.removeAt(index)
            adapter.notifyItemRemoved(index)
            updateFileListVisibility()
        }
        queueRunning = activeJobs.values.any { it.isActive }
        updateTranslationControls()
    }

    private fun updateFileListVisibility() {
        binding.tvSelectedFile.text = if (files.isEmpty()) {
            getString(R.string.activity_media_convert_text_03)
        } else {
            getString(R.string.batch_convert_selected_file_count, files.size)
        }
        binding.rvFileList.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateTranslationControls() {
        binding.btnStartTranslate.isEnabled = !queueRunning
        binding.tvTranslationProgress.visibility = if (queueRunning) View.VISIBLE else View.GONE
        updateTranslationProgress()
    }

    private fun updateTranslationProgress() {
        if (!queueRunning) return
        val activeCount = files.count { it.status == FileStatus.RUNNING }
        val completedFiles = files.count { it.status == FileStatus.COMPLETED }
        binding.tvTranslationProgress.text = buildString {
            append("正在处理：$activeCount 个文件 · 已完成 $completedFiles/${files.size} 个文件")
            for (stage in ProcessingStage.entries.filter { it.progressLabel != null }) {
                val stageFiles = files.filter { it.progressStage == stage }
                if (stageFiles.isEmpty()) continue
                val totalLines = stageFiles.sumOf { it.totalLines }
                val processedLines = stageFiles.sumOf { it.processedLines }
                append(" · ${stage.progressLabel} 已处理 $processedLines/$totalLines 条字幕")
            }
        }
    }

    private fun confirmExitWhileTranslating() {
        AlertDialog.Builder(this)
            .setTitle("处理进行中")
            .setMessage("退出将停止正在进行的处理，已完成的文件会保留。确定退出吗？")
            .setPositiveButton("停止并退出") { _, _ ->
                files.forEach(::cancelFile)
                queueRunning = false
                finish()
            }
            .setNegativeButton("继续处理", null)
            .show()
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

    private inner class AutoTranslateAdapter(
        private val onItemClick: (AutoTranslateFile) -> Unit,
        private val onRemoveClick: (AutoTranslateFile) -> Unit
    ) : RecyclerView.Adapter<AutoTranslateAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val name: TextView = view.findViewById(R.id.tvFileName)
            private val size: TextView = view.findViewById(R.id.tvFilePath)
            private val stats: TextView = view.findViewById(R.id.tvFileStats)
            private val statusText: TextView = view.findViewById(R.id.tvFileStatus)
            private val remove: ImageButton = view.findViewById(R.id.btnRemove)

            fun bind(file: AutoTranslateFile) {
                name.text = file.fileName
                size.text = FileUtils.formatFileSize(file.fileSize)
                val status = when (file.status) {
                    FileStatus.WAITING -> "等待"
                    FileStatus.RUNNING -> file.processingStage.displayName
                    FileStatus.COMPLETED -> "已完成"
                    FileStatus.STOPPED -> "已停止，点击重试"
                }
                stats.text = if (file.progressStage != null) {
                    "字幕 ${file.totalLines} · 已处理 ${file.processedLines}"
                } else {
                    "字幕 ${file.totalLines}"
                }
                statusText.text = if (file.message.isBlank()) status else "$status：${file.message}"
                itemView.setOnClickListener { onItemClick(file) }
                remove.setOnClickListener { onRemoveClick(file) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_auto_translate, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(files[position])
        override fun getItemCount(): Int = files.size
    }
}
