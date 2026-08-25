package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.databinding.ActivityAutoTranslateBinding
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.AiTranslationConversation
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleOutputWriter
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.SubtitleDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        val baseUrl: String,
        val contextWindowTokens: Int,
        val reasoningLevel: AiProviderConfig.ReasoningLevel
    )

    private enum class FileStatus { WAITING, RUNNING, COMPLETED, STOPPED }

    private class AutoTranslateFile(
        val uri: Uri,
        val fileName: String,
        val fileSize: Long,
        val sessionId: String = UUID.randomUUID().toString()
    ) {
        var status = FileStatus.WAITING
        var totalLines = 0
        var translatedLines = 0
        var message = ""
        var document: SubtitleDocument? = null
        val translatedTexts = mutableListOf<String>()
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
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
        binding.tvOutputDir.text = "输出目录：${DirectoryDisplayPath.fromUri(this, uri)}"
        settingsManager.setPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY, uri.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoTranslateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsManager = SettingsManager.getInstance(this)
        setupToolbar()
        setupList()
        binding.btnAddFiles.setOnClickListener { filePickerLauncher.launch("*/*") }
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
        addInitialFiles(intent.getParcelableArrayListExtra<Uri>(EXTRA_INITIAL_FILE_URIS).orEmpty())
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupList() {
        adapter = AutoTranslateAdapter(
            onItemClick = { file ->
                if (file.status == FileStatus.STOPPED) startFile(file, retry = true)
            },
            onRemoveClick = ::removeFile
        )
        binding.rvFileList.layoutManager = LinearLayoutManager(this)
        binding.rvFileList.adapter = adapter
    }

    private fun restoreOutputDirectory() {
        val uri = settingsManager.getPersistedOutputDirectory(OUTPUT_DIRECTORY_KEY)
            ?.let(Uri::parse)
            ?: return
        outputDirectoryUri = uri
        binding.tvOutputDir.text = "输出目录：${DirectoryDisplayPath.fromUri(this, uri)}"
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

    private fun startQueuedFiles() {
        if (files.isEmpty()) {
            OverwritingToast.makeText(this, "请先添加要翻译的文件", Toast.LENGTH_SHORT).show()
            return
        }
        val config = readTranslationConfig() ?: return
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
                .setMessage("输出目录中已存在同名文件。是否自动重命名（例如添加 (1)）并继续？")
                .setPositiveButton("继续") { _, _ -> beginQueuedFiles(config, outputUri) }
                .setNegativeButton("取消", null)
                .show()
        } else {
            beginQueuedFiles(config, outputUri)
        }
    }

    private fun beginQueuedFiles(config: TranslationConfig, outputUri: Uri) {
        val queuedFiles = files.filter {
            it.status == FileStatus.WAITING || it.status == FileStatus.STOPPED
        }
        if (queuedFiles.isEmpty()) return
        queueRunning = true
        updateTranslationControls()
        queuedFiles
            .forEach { startFile(it, retry = it.status == FileStatus.STOPPED, config, outputUri) }
    }

    private fun startFile(
        file: AutoTranslateFile,
        retry: Boolean = false,
        config: TranslationConfig? = readTranslationConfig(),
        outputUri: Uri = outputDirectoryUri ?: Uri.fromFile(getTranslateOutputDirectory())
    ) {
        if (activeJobs[file.sessionId]?.isActive == true || config == null) return
        queueRunning = true
        updateTranslationControls()
        if (retry) file.message = ""
        file.status = FileStatus.RUNNING
        adapter.notifyItemChanged(files.indexOf(file))
        activeJobs[file.sessionId] = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            processFile(file, config, outputUri)
        }
    }

    private suspend fun processFile(
        file: AutoTranslateFile,
        config: TranslationConfig,
        outputUri: Uri
    ) {
        try {
            val document = file.document ?: loadDocument(file).also { file.document = it }
            val entries = document.entries
            file.totalLines = entries.size
            postFileUpdate(file) { }
            if (entries.isEmpty()) throw IllegalArgumentException("未检测到可翻译的字幕行")

            val translator = AiTranslationConversation(
                context = this,
                provider = config.provider,
                apiKey = config.apiKey,
                model = config.model,
                targetLanguage = config.targetLanguage,
                baseUrl = config.baseUrl,
                contextWindowTokens = config.contextWindowTokens,
                subtitleFormat = document.format,
                reasoningLevel = config.reasoningLevel,
                historySessionId = file.sessionId,
                historyTitle = "自动翻译 · ${file.fileName} · ${config.targetLanguage}"
            )
            var consecutiveErrors = 0
            while (file.translatedTexts.size < entries.size) {
                val completed = file.translatedTexts.size
                val result = translator.translateSubtitles(
                    subtitles = entries.drop(completed),
                    startPosition = completed + 1,
                    progressCallback = { current, _ ->
                        postFileUpdate(file) { file.translatedLines = completed + current }
                    }
                )
                file.translatedTexts += result.translations
                file.translatedLines = file.translatedTexts.size
                postFileUpdate(file) { }
                if (result.isComplete && result.translations.isNotEmpty()) {
                    consecutiveErrors = 0
                    continue
                }
                if (result.error != null) {
                    consecutiveErrors++
                    file.message = result.error.message ?: "翻译失败"
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        file.status = FileStatus.STOPPED
                        postFileUpdate(file) { }
                        return
                    }
                    postFileUpdate(file) { }
                    delay((consecutiveErrors * 500L).coerceAtMost(3_000L))
                } else if (result.translations.isEmpty()) {
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        file.status = FileStatus.STOPPED
                        file.message = "翻译未返回结果"
                        postFileUpdate(file) { }
                        return
                    }
                }
            }

            val translatedEntries = entries.mapIndexed { index, entry ->
                entry.copy(text = file.translatedTexts[index])
            }
            val translatedDocument = document.copy(entries = translatedEntries)
            SubtitleOutputWriter.writeText(
                this,
                outputUri,
                file.fileName.substringBeforeLast("."),
                outputExtension(file),
                SubtitleParser.serialize(translatedDocument)
            )
            file.status = FileStatus.COMPLETED
            file.message = "翻译完成"
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
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                activeJobs.remove(file.sessionId)
                if (activeJobs.values.none { it.isActive }) {
                    queueRunning = false
                }
                updateTranslationControls()
            }
        }
    }

    private suspend fun loadDocument(file: AutoTranslateFile): SubtitleDocument {
        val content = contentResolver.openInputStream(file.uri)?.use {
            it.bufferedReader(settingsManager.getDefaultEncoding()).readText()
        } ?: throw IllegalArgumentException("无法读取文件")
        val format = SubtitleParser.detectFormat(content, file.fileName)
        return SubtitleParser.parseDocument(content, file.fileName, format)
    }

    private fun readTranslationConfig(showError: Boolean = true): TranslationConfig? {
        val provider = settingsManager.getAiProvider()
        val apiKey = settingsManager.getAiApiKey()
        val model = settingsManager.getAiModel()
        val targetLanguage = settingsManager.getAiTargetLanguage()
        val baseUrl = settingsManager.getAiBaseUrl(provider)
        val error = when {
            apiKey.isBlank() -> "请先在设置中配置 ${AiProviderConfig.getProvider(provider).displayName} API Key"
            targetLanguage.isBlank() -> "请先设置目标语言"
            baseUrl.isBlank() -> "请先在 AI 翻译设置中填写 API 请求地址"
            else -> null
        }
        if (error != null) {
            if (showError) OverwritingToast.makeText(this, error, Toast.LENGTH_LONG).show()
            return null
        }
        return TranslationConfig(
            provider = provider,
            apiKey = apiKey,
            model = model,
            targetLanguage = targetLanguage,
            baseUrl = baseUrl,
            contextWindowTokens = settingsManager.getAiContextWindowTokens(provider),
            reasoningLevel = settingsManager.getAiReasoningLevel(provider)
        )
    }

    private fun postFileUpdate(file: AutoTranslateFile, update: () -> Unit) {
        runOnUiThread {
            update()
            val index = files.indexOf(file)
            if (index >= 0) adapter.notifyItemChanged(index)
            updateFileListVisibility()
            updateTranslationProgress()
        }
    }

    private fun removeFile(file: AutoTranslateFile) {
        activeJobs[file.sessionId]?.cancel()
        val index = files.indexOf(file)
        if (index >= 0) {
            files.removeAt(index)
            adapter.notifyItemRemoved(index)
            updateFileListVisibility()
        }
    }

    private fun updateFileListVisibility() {
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
        val totalLines = files.sumOf { it.totalLines }
        val translatedLines = files.sumOf { it.translatedLines }
        binding.tvTranslationProgress.text =
            "正在翻译：$activeCount 个文件 · 已完成 $completedFiles/${files.size} 个文件 · " +
                "已翻译 $translatedLines/$totalLines 条字幕"
    }

    private fun confirmExitWhileTranslating() {
        AlertDialog.Builder(this)
            .setTitle("翻译进行中")
            .setMessage("退出将停止正在进行的翻译，已完成的文件会保留。确定退出吗？")
            .setPositiveButton("停止并退出") { _, _ ->
                activeJobs.values.toList().forEach { it.cancel() }
                queueRunning = false
                finish()
            }
            .setNegativeButton("继续翻译", null)
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

    private fun getFileNameFromUri(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun getFileSizeFromUri(uri: Uri): Long = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L }
            ?: 0L
    }.getOrDefault(0L)

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
                    FileStatus.RUNNING -> "翻译中"
                    FileStatus.COMPLETED -> "已完成"
                    FileStatus.STOPPED -> "已停止，点击重试"
                }
                stats.text = "字幕 ${file.totalLines} · 已翻译 ${file.translatedLines}"
                statusText.text = if (file.message.isBlank()) status else "$status：${file.message}"
                itemView.setOnClickListener { onItemClick(file) }
                remove.setOnClickListener { onRemoveClick(file) }
                remove.isEnabled = file.status != FileStatus.RUNNING
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_auto_translate, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(files[position])
        override fun getItemCount(): Int = files.size
    }
}
