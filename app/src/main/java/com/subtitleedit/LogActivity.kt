package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.ui.LogScreen
import com.subtitleedit.ui.LogSection
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.RuntimeLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogActivity : AppCompatActivity() {

    private var displayMode by mutableStateOf(RuntimeLogManager.DisplayMode.SIMPLE)
    private var hasLoadedLog = false
    private var isRefreshing by mutableStateOf(false)
    private var isExportEnabled by mutableStateOf(false)
    private var refreshGeneration = 0
    private var allSections by mutableStateOf(emptyList<LogSection>())
    private var pageFilter by mutableStateOf("全部页面")
    private var pageOptions by mutableStateOf(listOf("全部页面"))
    private var infoText by mutableStateOf("")

    private val exportDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let(::exportLogToDirectory)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SubtitleEditComposeTheme {
                LogScreen(
                    sections = allSections,
                    pageOptions = pageOptions,
                    pageFilter = pageFilter,
                    displayMode = displayMode,
                    infoText = infoText,
                    isRefreshing = isRefreshing,
                    isExportEnabled = isExportEnabled,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRefresh = { refreshLog() },
                    onExport = ::requestExport,
                    onClear = ::clearLog,
                    onDisplayModeChange = { mode ->
                        if (displayMode != mode) {
                            displayMode = mode
                            refreshLog()
                        }
                    },
                    onPageFilterChange = { pageFilter = it }
                )
            }
        }
        refreshLog()
    }

    private fun refreshLog(onComplete: (() -> Unit)? = null) {
        val generation = ++refreshGeneration
        val mode = displayMode
        isRefreshing = true
        isExportEnabled = false
        infoText = "正在读取本应用最近 1 小时日志..."
        allSections = emptyList()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    RuntimeLogManager.captureRecent(this@LogActivity, mode)
                }
            }
            if (generation != refreshGeneration) return@launch

            result.onSuccess { snapshot ->
                allSections = buildLogSections(snapshot.content)
                pageOptions = listOf("全部页面") + allSections
                    .map { it.title.substringBefore(" - ") }
                    .distinct()
                if (pageFilter !in pageOptions) pageFilter = "全部页面"
                hasLoadedLog = true
                infoText = buildString {
                    append("${snapshot.packageName} 最近 1 小时，显示 ${snapshot.matchedLineCount} 行")
                    if (snapshot.isPreviewTruncated) append("（预览已限制）")
                }
                isExportEnabled = true
                onComplete?.invoke()
            }.onFailure { error ->
                infoText = "读取日志失败：${error.message ?: "未知错误"}"
                isExportEnabled = true
                OverwritingToast.makeText(
                    this@LogActivity,
                    infoText,
                    Toast.LENGTH_LONG
                ).show()
            }
            isRefreshing = false
        }
    }

    private fun clearLog() {
        refreshGeneration++
        RuntimeLogManager.clear(this)
        hasLoadedLog = false
        isRefreshing = false
        isExportEnabled = true
        allSections = emptyList()
        pageOptions = listOf("全部页面")
        pageFilter = "全部页面"
        infoText = "$packageName 最近 1 小时"
        OverwritingToast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
    }

    private fun requestExport() {
        if (!hasLoadedLog) {
            refreshLog { openExportDirectoryPicker() }
        } else {
            openExportDirectoryPicker()
        }
    }

    private fun openExportDirectoryPicker() {
        exportDirLauncher.launch(null)
    }

    private fun exportLogToDirectory(uri: Uri) {
        val mode = displayMode
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
                        RuntimeLogManager.exportRecent(this@LogActivity, mode, output)
                    } ?: throw IllegalStateException("无法写入日志文件")
                    fileName
                }
            }

            result.onSuccess { fileName ->
                OverwritingToast.makeText(this@LogActivity, "已导出：$fileName", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                OverwritingToast.makeText(this@LogActivity, "导出失败：${error.message}", Toast.LENGTH_LONG).show()
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

    private fun activitySectionTitle(activity: String): String = when (activity) {
        "SpeechToSubtitleActivity" -> "语音转字幕"
        "SenseVoiceSettingsActivity" -> "SenseVoice 配置"
        "ParakeetSettingsActivity" -> "Parakeet 配置"
        "SpeechToSubtitleSettingsActivity" -> "语音转字幕配置"
        "AutoTimestampActivity" -> "自动打轴"
        "EditorActivity" -> "字幕编辑"
        "SettingsActivity" -> "应用设置"
        "LogActivity" -> "运行日志"
        else -> activity.removeSuffix("Activity")
    }
}
