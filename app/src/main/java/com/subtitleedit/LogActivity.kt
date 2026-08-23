package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.subtitleedit.adapter.LogSection
import com.subtitleedit.adapter.LogSectionAdapter
import com.subtitleedit.databinding.ActivityLogBinding
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.RuntimeLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogActivity : AppCompatActivity() {

    private companion object {
        const val MENU_CLEAR_LOG = 1
    }

    private lateinit var binding: ActivityLogBinding
    private var displayMode = RuntimeLogManager.DisplayMode.SIMPLE
    private var hasLoadedLog = false
    private var refreshGeneration = 0
    private var allSections: List<LogSection> = emptyList()
    private var pageFilter = "全部页面"

    private val exportDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { exportLogToDirectory(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupButtons()
        setupDisplayMode()
        setupPageFilter()
        binding.logSectionList.layoutManager = LinearLayoutManager(this)
        refreshLog()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "运行日志"
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_CLEAR_LOG, Menu.NONE, "清空")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CLEAR_LOG -> {
                clearLog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupButtons() {
        binding.btnRefresh.setOnClickListener {
            refreshLog()
        }
        binding.btnExport.setOnClickListener {
            if (!hasLoadedLog) {
                refreshLog { openExportDirectoryPicker() }
            } else {
                openExportDirectoryPicker()
            }
        }
    }

    private fun setupDisplayMode() {
        binding.logDisplayMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            displayMode = if (checkedId == R.id.btnSimpleLog) {
                RuntimeLogManager.DisplayMode.SIMPLE
            } else {
                RuntimeLogManager.DisplayMode.DETAILED
            }
            refreshLog()
        }
    }

    private fun setupPageFilter() {
        binding.spinnerLogPageFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                pageFilter = parent.getItemAtPosition(position) as String
                renderSections()
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }

    private fun refreshLog(onComplete: (() -> Unit)? = null) {
        val generation = ++refreshGeneration
        binding.btnRefresh.isEnabled = false
        binding.btnExport.isEnabled = false
        binding.tvLogInfo.text = "正在读取本应用最近 1 小时日志..."
        binding.logSectionList.adapter = null

        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                RuntimeLogManager.captureRecent(this@LogActivity, displayMode)
            }
            if (generation != refreshGeneration) return@launch
            allSections = buildLogSections(snapshot.content)
            updatePageFilterOptions()
            hasLoadedLog = true
            binding.tvLogInfo.text = buildString {
                append("${snapshot.packageName} 最近 1 小时，显示 ${snapshot.matchedLineCount} 行")
                if (snapshot.isPreviewTruncated) append("（预览已限制）")
            }
            binding.btnRefresh.isEnabled = true
            binding.btnExport.isEnabled = true
            onComplete?.invoke()
        }
    }

    private fun clearLog() {
        RuntimeLogManager.clear(this)
        hasLoadedLog = false
        binding.logSectionList.adapter = LogSectionAdapter(
            listOf(LogSection("暂无可读取的日志", "", "", 0))
        )
        allSections = emptyList()
        binding.tvLogInfo.text = "${packageName} 最近 1 小时"
        OverwritingToast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
    }

    private fun openExportDirectoryPicker() {
        exportDirLauncher.launch(null)
    }

    private fun exportLogToDirectory(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val dir = DocumentFile.fromTreeUri(this@LogActivity, uri)
                        ?: throw IllegalStateException("无法访问所选目录")
                    val fileName = uniqueFileName(dir, RuntimeLogManager.exportFileName())
                    val file = dir.createFile("text/plain", fileName)
                        ?: throw IllegalStateException("无法创建日志文件")
                    contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
                        RuntimeLogManager.exportRecent(this@LogActivity, displayMode, output)
                    } ?: throw IllegalStateException("无法写入日志文件")
                    fileName
                }
            }

            result.onSuccess { fileName ->
                OverwritingToast.makeText(this@LogActivity, "已导出：$fileName", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                OverwritingToast.makeText(this@LogActivity, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun uniqueFileName(dir: DocumentFile, originalName: String): String {
        val name = originalName.substringBeforeLast(".")
        val extension = originalName.substringAfterLast(".", "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var fileName = originalName
        var index = 1
        while (dir.findFile(fileName) != null) {
            fileName = "$name ($index)$suffix"
            index++
        }
        return fileName
    }

    private fun buildLogSections(content: String): List<LogSection> {
        val sections = mutableListOf<LogSection>()
        val sectionLines = mutableListOf<String>()
        var title = "应用启动与后台日志"
        var startedAt = ""
        val pageBoundary = Regex("^(.{19}).*INFO/Navigation: ([A-Za-z]+Activity) resumed$")

        fun addSection() {
            if (sectionLines.isNotEmpty()) {
                sections.add(LogSection(title, startedAt, sectionLines.joinToString("\n"), sectionLines.size))
                sectionLines.clear()
            }
        }
        content.lineSequence().forEach { line ->
            val match = pageBoundary.matchEntire(line)
            if (match != null) {
                addSection()
                startedAt = match.groupValues[1]
                title = activitySectionTitle(match.groupValues[2])
            } else if (title == "语音转字幕" && isSpeechRecognitionLine(line)) {
                addSection()
                title = "语音转字幕 - 识别过程"
                startedAt = line.take(19)
            }
            sectionLines.add(line)
        }
        addSection()
        return sections
    }

    private fun isSpeechRecognitionLine(line: String): Boolean =
        line.contains("ffmpeg-kit") ||
            line.contains("WhisperRecognizer") ||
            line.contains("sherpa-onnx") ||
            line.contains("Pcm16Wav")

    private fun updatePageFilterOptions() {
        val options = listOf("全部页面") + allSections
            .map { it.title.substringBefore(" - ") }
            .distinct()
        if (pageFilter !in options) pageFilter = "全部页面"
        binding.spinnerLogPageFilter.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            options
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerLogPageFilter.setSelection(options.indexOf(pageFilter))
        renderSections()
    }

    private fun renderSections() {
        val sections = if (pageFilter == "全部页面") {
            allSections
        } else {
            allSections.filter { it.title.substringBefore(" - ") == pageFilter }
        }
        binding.logSectionList.adapter = LogSectionAdapter(sections)
    }

    private fun activitySectionTitle(activity: String): String = when (activity) {
        "SpeechToSubtitleActivity" -> "语音转字幕"
        "AutoTimestampActivity" -> "自动打轴"
        "EditorActivity" -> "字幕编辑"
        "ModelSettingsActivity" -> "模型配置"
        "SettingsActivity" -> "应用设置"
        "LogActivity" -> "运行日志"
        else -> activity.removeSuffix("Activity")
    }
}
