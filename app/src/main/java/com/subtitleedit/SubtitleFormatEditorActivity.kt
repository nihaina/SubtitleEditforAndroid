package com.subtitleedit

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.feature.ui.SubtitleFormatEditorOptions
import com.subtitleedit.feature.ui.SubtitleFormatEditorRow
import com.subtitleedit.feature.ui.SubtitleFormatEditorScreen
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleFormattingOptions
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleTextFormatter
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubtitleFormatEditorActivity : AppCompatActivity() {
    private lateinit var sourceUri: Uri
    private lateinit var charset: Charset
    private var fileName = "字幕文件"
    private var format = SubtitleParser.SubtitleFormat.UNKNOWN
    private var entries = emptyList<SubtitleEntry>()
    private var previewItems by mutableStateOf<List<SubtitleFormatEditorRow>>(emptyList())
    private var fileInfo by mutableStateOf("")
    private var isLoading by mutableStateOf(true)
    private var isApplying by mutableStateOf(false)
    private var showSaveConfirmation by mutableStateOf(false)
    private var showDiscardConfirmation by mutableStateOf(false)
    private var hasChanges = false
    private var formattingJob: Job? = null

    companion object {
        const val EXTRA_URI = "subtitle_format_uri"
        const val EXTRA_FILE_NAME = "subtitle_format_file_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriText = intent.getStringExtra(EXTRA_URI)
        if (uriText.isNullOrBlank()) {
            finish()
            return
        }
        sourceUri = Uri.parse(uriText)
        fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty().ifBlank { "字幕文件" }
        charset = SettingsManager.getInstance(this).getDefaultEncoding()

        setupBackHandling()
        setContent {
            SubtitleEditComposeTheme {
                SubtitleFormatEditorScreen(
                    fileName = fileName,
                    fileInfo = fileInfo,
                    items = previewItems,
                    isLoading = isLoading,
                    isApplying = isApplying,
                    showSaveConfirmation = showSaveConfirmation,
                    showDiscardConfirmation = showDiscardConfirmation,
                    onBack = ::handleBack,
                    onSaveRequest = ::confirmSave,
                    onConfirmSave = {
                        showSaveConfirmation = false
                        saveToSource()
                    },
                    onDismissSaveConfirmation = { showSaveConfirmation = false },
                    onConfirmDiscard = {
                        showDiscardConfirmation = false
                        finish()
                    },
                    onDismissDiscardConfirmation = { showDiscardConfirmation = false },
                    onSelectAll = ::selectAll,
                    onSelectRange = ::selectRange,
                    onSelectionChanged = ::setSelection,
                    onEditItem = ::editItem,
                    onInvalidRange = { showMessage("请输入有效的起止行号") },
                    onApply = ::applyFormatting
                )
            }
        }
        loadSubtitle()
    }

    private fun loadSubtitle() {
        lifecycleScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    val content = FileUtils.readUri(this@SubtitleFormatEditorActivity, sourceUri, charset)
                    SubtitleParser.parseDocument(content, fileName)
                }
                format = document.format
                entries = document.entries
                if (format == SubtitleParser.SubtitleFormat.UNKNOWN || entries.isEmpty()) {
                    throw IllegalArgumentException("未识别到有效字幕内容")
                }
                previewItems = entries.mapIndexed { index, entry ->
                    SubtitleFormatEditorRow(entryPosition = index, text = entry.text)
                }
                updateFileInfo()
                isLoading = false
            } catch (e: Exception) {
                OverwritingToast.makeText(
                    this@SubtitleFormatEditorActivity,
                    "读取字幕失败：${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun updateFileInfo() {
        fileInfo = "$fileName · ${previewItems.size} 行 · ${format.name}"
    }

    private fun selectAll() {
        val selected = previewItems.isNotEmpty() && previewItems.any { !it.selected }
        previewItems = previewItems.map { it.copy(selected = selected) }
    }

    private fun selectRange(startInclusive: Int, endInclusive: Int) {
        previewItems = previewItems.mapIndexed { index, item ->
            item.copy(selected = index in startInclusive..endInclusive)
        }
    }

    private fun setSelection(position: Int, selected: Boolean) {
        val item = previewItems.getOrNull(position) ?: return
        previewItems = previewItems.toMutableList().also {
            it[position] = item.copy(selected = selected)
        }
    }

    private fun editItem(position: Int, text: String) {
        val item = previewItems.getOrNull(position) ?: return
        if (item.text == text) return
        previewItems = previewItems.toMutableList().also {
            it[position] = item.copy(text = text)
        }
        hasChanges = true
    }

    private fun applyFormatting(options: SubtitleFormatEditorOptions) {
        if (isLoading || formattingJob?.isActive == true) return
        val selected = previewItems.filter { it.selected }
        if (selected.isEmpty()) {
            showMessage("请先勾选要格式化的字幕")
            return
        }
        if (!options.removeSpaces && options.innerPunctuation.isEmpty() &&
            options.startPunctuation.isEmpty() && options.endPunctuation.isEmpty() &&
            options.replaceFrom.isEmpty() && options.addEndPunctuation.isEmpty()
        ) {
            showMessage("请先选择格式化项目")
            return
        }
        val formattingOptions = SubtitleFormattingOptions(
            removeSpaces = options.removeSpaces,
            innerPunctuation = options.innerPunctuation,
            startPunctuation = options.startPunctuation,
            endPunctuation = options.endPunctuation,
            replaceFrom = options.replaceFrom,
            replaceTo = options.replaceTo,
            replacementScope = options.replacementScope,
            addEndPunctuation = options.addEndPunctuation
        )
        isApplying = true
        formattingJob = lifecycleScope.launch {
            try {
                val formattedTexts = withContext(Dispatchers.Default) {
                    selected.associate { item ->
                        item.entryPosition to SubtitleTextFormatter.format(item.text, formattingOptions)
                    }
                }
                var changed = 0
                var removed = 0
                previewItems = previewItems.mapNotNull { item ->
                    val formatted = formattedTexts[item.entryPosition] ?: return@mapNotNull item
                    if (formatted == item.text) return@mapNotNull item
                    changed++
                    if (formatted.isBlank()) {
                        removed++
                        null
                    } else {
                        item.copy(text = formatted)
                    }
                }
                hasChanges = hasChanges || changed > 0
                updateFileInfo()
                val message = if (removed > 0) {
                    "已格式化 $changed 条字幕，删除 $removed 条空字幕"
                } else {
                    "已格式化 $changed 条字幕"
                }
                showMessage(message)
            } finally {
                isApplying = false
            }
        }
    }

    private fun confirmSave() {
        if (isLoading || entries.isEmpty()) {
            showMessage("字幕尚未加载完成")
            return
        }
        if (formattingJob?.isActive == true) {
            showMessage("格式化尚未完成，请稍候")
            return
        }
        showSaveConfirmation = true
    }

    private fun saveToSource() {
        if (isLoading || entries.isEmpty()) return
        try {
            val outputEntries = previewItems.mapIndexed { index, item ->
                entries[item.entryPosition].copy(index = index + 1, text = item.text)
            }
            val content = when (format) {
                SubtitleParser.SubtitleFormat.SRT -> SubtitleParser.toSRT(outputEntries)
                SubtitleParser.SubtitleFormat.LRC -> SubtitleParser.toLRC(outputEntries)
                SubtitleParser.SubtitleFormat.TXT -> SubtitleParser.toTXT(outputEntries)
                SubtitleParser.SubtitleFormat.VTT -> {
                    val document = SubtitleParser.parseDocument(
                        FileUtils.readUri(this, sourceUri, charset),
                        fileName,
                        SubtitleParser.SubtitleFormat.VTT
                    )
                    SubtitleParser.toVTT(outputEntries, document.header, document.footer)
                }
                else -> throw IllegalStateException("不支持的字幕格式")
            }
            contentResolver.openOutputStream(sourceUri, "wt")?.use {
                it.write(content.toByteArray(charset))
                it.flush()
            } ?: throw IllegalStateException("无法打开原文件进行写入")
            entries = outputEntries
            previewItems = previewItems.mapIndexed { index, item ->
                item.copy(entryPosition = index)
            }
            hasChanges = false
            updateFileInfo()
            showMessage("已保存到 $fileName")
        } catch (e: Exception) {
            showMessage("保存失败：${e.message}", Toast.LENGTH_LONG)
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
    }

    private fun handleBack() {
        if (!hasChanges) {
            finish()
            return
        }
        showDiscardConfirmation = true
    }

    private fun showMessage(message: String, duration: Int = Toast.LENGTH_SHORT) {
        OverwritingToast.makeText(this, message, duration).show()
    }
}
