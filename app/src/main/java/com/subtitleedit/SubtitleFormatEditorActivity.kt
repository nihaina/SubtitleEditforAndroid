package com.subtitleedit

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.subtitleedit.adapter.SubtitleFormatPreviewAdapter
import com.subtitleedit.adapter.SubtitleFormatPreviewItem
import com.subtitleedit.databinding.ActivitySubtitleFormatEditorBinding
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.PunctuationReplacementScope
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
    private lateinit var binding: ActivitySubtitleFormatEditorBinding
    private lateinit var adapter: SubtitleFormatPreviewAdapter
    private lateinit var sourceUri: Uri
    private lateinit var charset: Charset
    private var fileName = "字幕文件"
    private var format = SubtitleParser.SubtitleFormat.UNKNOWN
    private var entries: List<SubtitleEntry> = emptyList()
    private var hasChanges = false
    private var formattingJob: Job? = null
    private val innerPunctuationChecks = linkedMapOf<Char, TextView>()
    private val endPunctuationChecks = linkedMapOf<Char, TextView>()
    private val addEndPunctuationValues = listOf("", "。", ".", "！", "!", "？", "?", "，", ",", "…")

    companion object {
        const val EXTRA_URI = "subtitle_format_uri"
        const val EXTRA_FILE_NAME = "subtitle_format_file_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubtitleFormatEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val uriText = intent.getStringExtra(EXTRA_URI)
        if (uriText.isNullOrBlank()) {
            finish()
            return
        }
        sourceUri = Uri.parse(uriText)
        fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty().ifBlank { "字幕文件" }
        charset = SettingsManager.getInstance(this).getDefaultEncoding()

        setupToolbar()
        setupBackHandling()
        setupFormattingControls()
        loadSubtitle()
        setupActions()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.subtitle = fileName
        binding.toolbar.setNavigationOnClickListener { handleBack() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_subtitle_format_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_format_select_all -> {
                if (::adapter.isInitialized) adapter.selectAll(!adapter.areAllSelected())
                true
            }
            R.id.menu_format_select_range -> {
                if (::adapter.isInitialized) showRangeDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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
                val items = entries.mapIndexed { index, entry ->
                    SubtitleFormatPreviewItem(index, entry.text)
                }
                adapter = SubtitleFormatPreviewAdapter(items) { item, position ->
                    showTextEditDialog(item, position)
                }
                binding.rvPreview.layoutManager = LinearLayoutManager(this@SubtitleFormatEditorActivity)
                binding.rvPreview.adapter = adapter
                binding.tvFileInfo.text = "$fileName · ${entries.size} 行 · ${format.name}"
            } catch (e: Exception) {
                OverwritingToast.makeText(this@SubtitleFormatEditorActivity, "读取字幕失败：${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setupActions() {
        binding.btnApplyFormat.setOnClickListener { applyFormatting() }
        binding.btnSave.setOnClickListener { confirmSave() }
    }

    private fun setupFormattingControls() {
        val punctuation = listOf(
            '，', ',', '、', '。', '．', '.', '？', '?', '！', '!', '：', ':', '；', ';',
            '“', '”', '‘', '’', '「', '」', '『', '』', '"', '\'',
            '（', '）', '(', ')', '【', '】', '[', ']', '—', '–', '-', '…', '·'
        )
        addPunctuationChecks(binding.gridInnerPunctuation, punctuation, innerPunctuationChecks, emptySet())
        addPunctuationChecks(
            binding.gridEndPunctuation,
            punctuation,
            endPunctuationChecks,
            "。．.，,、？?！!：:；;…".toSet()
        )

        binding.spinnerReplaceScope.adapter = ArrayAdapter(
            this,
            R.layout.item_spinner_compact,
            listOf("句内替换", "句末替换")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerAddEndPunctuation.adapter = ArrayAdapter(
            this,
            R.layout.item_spinner_compact,
            listOf("不添加", "。", ".", "！", "!", "？", "?", "，", ",", "…")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun addPunctuationChecks(
        grid: GridLayout,
        punctuation: List<Char>,
        destination: MutableMap<Char, TextView>,
        checkedByDefault: Set<Char>
    ) {
        punctuation.forEachIndexed { index, char ->
            val checkBox = TextView(this).apply {
                text = char.toString()
                gravity = android.view.Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = "标点 $char"
                minWidth = 0
                minimumWidth = 0
                minHeight = (40 * resources.displayMetrics.density).toInt()
                textSize = 17f
                setPadding(0, 0, 0, 0)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(index % 3, 1, 1f)
                    rowSpec = GridLayout.spec(index / 3)
                    val margin = (2 * resources.displayMetrics.density).toInt()
                    setMargins(margin, margin, margin, margin)
                }
                isSelected = char in checkedByDefault
                updatePunctuationCheckAppearance(this)
                setOnClickListener { view ->
                    view.isSelected = !view.isSelected
                    updatePunctuationCheckAppearance(view as TextView)
                }
            }
            destination[char] = checkBox
            grid.addView(checkBox)
        }
    }

    private fun updatePunctuationCheckAppearance(checkBox: TextView) {
        val fillColor = ContextCompat.getColor(
            this,
            if (checkBox.isSelected) R.color.primary else R.color.surface
        )
        val strokeColor = ContextCompat.getColor(
            this,
            if (checkBox.isSelected) R.color.primary else R.color.on_surface_variant
        )
        checkBox.setTextColor(
            ContextCompat.getColor(this, if (checkBox.isSelected) R.color.white else R.color.on_surface)
        )
        checkBox.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6 * resources.displayMetrics.density
            setColor(fillColor)
            setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    private fun applyFormatting() {
        if (!::adapter.isInitialized || formattingJob?.isActive == true) return
        val selected = adapter.items.filter { it.selected }
        if (selected.isEmpty()) {
            OverwritingToast.makeText(this, "请先勾选要格式化的字幕", Toast.LENGTH_SHORT).show()
            return
        }
        val options = collectOptions()
        if (!options.removeSpaces && options.innerPunctuation.isEmpty() &&
            options.endPunctuation.isEmpty() && options.replaceFrom.isEmpty() &&
            options.addEndPunctuation.isEmpty()
        ) {
            OverwritingToast.makeText(this, "请先选择格式化项目", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnApplyFormat.isEnabled = false
        formattingJob = lifecycleScope.launch {
            try {
                val changed = withContext(Dispatchers.Default) {
                    var count = 0
                    selected.forEach { item ->
                        val formatted = SubtitleTextFormatter.format(item.text, options)
                        if (formatted != item.text) {
                            item.text = formatted
                            count++
                        }
                    }
                    count
                }
                adapter.notifyDataSetChanged()
                hasChanges = hasChanges || changed > 0
                OverwritingToast.makeText(this@SubtitleFormatEditorActivity, "已格式化 $changed 条字幕", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnApplyFormat.isEnabled = true
            }
        }
    }

    private fun collectOptions(): SubtitleFormattingOptions {
        val inner = mutableSetOf<Char>()
        inner += innerPunctuationChecks.filterValues { it.isSelected }.keys

        val end = mutableSetOf<Char>()
        end += endPunctuationChecks.filterValues { it.isSelected }.keys
        return SubtitleFormattingOptions(
            removeSpaces = binding.cbRemoveSpaces.isChecked,
            innerPunctuation = inner,
            endPunctuation = end,
            replaceFrom = binding.etReplaceFrom.text?.toString().orEmpty(),
            replaceTo = binding.etReplaceTo.text?.toString().orEmpty(),
            replacementScope = if (binding.spinnerReplaceScope.selectedItemPosition == 1) {
                PunctuationReplacementScope.END
            } else {
                PunctuationReplacementScope.INNER
            },
            addEndPunctuation = addEndPunctuationValues[
                binding.spinnerAddEndPunctuation.selectedItemPosition.coerceIn(addEndPunctuationValues.indices)
            ]
        )
    }

    private fun showTextEditDialog(item: SubtitleFormatPreviewItem, position: Int) {
        val edit = EditText(this).apply {
            setText(item.text)
            setSelection(text.length)
            minLines = 3
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle("编辑第 ${position + 1} 条字幕")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val newText = edit.text?.toString().orEmpty()
                if (newText != item.text) {
                    item.text = newText
                    hasChanges = true
                    adapter.notifyItemChanged(position)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRangeDialog() {
        val start = EditText(this).apply { hint = "开始行"; inputType = EditorInfo.TYPE_CLASS_NUMBER }
        val end = EditText(this).apply { hint = "结束行"; inputType = EditorInfo.TYPE_CLASS_NUMBER }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margin = (20 * resources.displayMetrics.density).toInt()
            setPadding(margin, 0, margin, 0)
            addView(start, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(end, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        AlertDialog.Builder(this)
            .setTitle("区间选择（1-${adapter.itemCount}）")
            .setView(container)
            .setPositiveButton("选择") { _, _ ->
                val from = start.text.toString().toIntOrNull()
                val to = end.text.toString().toIntOrNull()
                if (from == null || to == null || from !in 1..adapter.itemCount ||
                    to !in 1..adapter.itemCount || from > to
                ) {
                    OverwritingToast.makeText(this, "请输入有效的起止行号", Toast.LENGTH_SHORT).show()
                } else adapter.selectRange(from - 1, to - 1)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmSave() {
        if (!::adapter.isInitialized || entries.isEmpty()) {
            OverwritingToast.makeText(this, "字幕尚未加载完成", Toast.LENGTH_SHORT).show()
            return
        }
        if (formattingJob?.isActive == true) {
            OverwritingToast.makeText(this, "格式化尚未完成，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("保存格式化结果")
            .setMessage("将覆盖原文件 $fileName，确定继续？")
            .setPositiveButton("保存") { _, _ -> saveToSource() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveToSource() {
        if (!::adapter.isInitialized || entries.isEmpty()) return
        try {
            val outputEntries = entries.mapIndexed { index, entry ->
                entry.copy(text = adapter.items[index].text)
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
            hasChanges = false
            OverwritingToast.makeText(this, "已保存到 $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            OverwritingToast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
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
        AlertDialog.Builder(this)
            .setTitle("放弃更改？")
            .setMessage("尚未保存的格式化结果将丢失。")
            .setPositiveButton("放弃") { _, _ -> finish() }
            .setNegativeButton("取消", null)
            .show()
    }
}
