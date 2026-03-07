package com.subtitleedit

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import android.media.MediaPlayer
import com.subtitleedit.adapter.SubtitleAdapter
import com.subtitleedit.databinding.ActivityEditorBinding
import com.subtitleedit.util.AiTranslator
import com.subtitleedit.util.DraftManager
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TimeUtils
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 字幕编辑界面
 * 支持点击编辑、长按菜单、多选、复制粘贴功能
 * 支持草稿箱功能
 * 支持源视图模式（用于 TXT 文件）
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var subtitleAdapter: SubtitleAdapter
    
    private var filePath: String = ""
    private var currentFile: File? = null
    // 字幕文件路径（当打开音频文件时，用于保存字幕）
    private var subtitleFilePath: String = ""
    private var subtitleFile: File? = null
    private var subtitleEntries = mutableListOf<SubtitleEntry>()
    private var currentCharset: Charset = StandardCharsets.UTF_8
    private var currentFormat: SubtitleParser.SubtitleFormat = SubtitleParser.SubtitleFormat.UNKNOWN
    
    // 源视图模式标志
    private var isSourceViewMode = false
    // 源视图原始内容（用于 TXT 文件）- 保存原始文件内容，不做任何修改
    private var originalFileContent = ""
    // 当前显示的内容（可能是原始内容或从字幕列表生成的内容）
    private var sourceViewContent = ""
    
    // 切换视图前保存的滚动位置
    private var savedScrollPosition = 0
    private var savedFirstVisibleItemPosition = 0
    
    // 长按时的位置（用于时间偏移等操作）
    private var longClickPosition: Int = -1
    
    // 是否有未保存的更改
    private var hasUnsavedChanges = false
    
    // 复制/剪贴板数据（支持多行）
    private var clipboardEntries: List<SubtitleEntry> = emptyList()
    private var isCutMode: Boolean = false  // 是否为剪切模式
    
    // 翻译相关
    private var translateJob: Job? = null
    private var isTranslating = false
    private var translateCancelled = false
    
    // 搜索相关
    private var searchResults: List<Int> = emptyList()
    private var currentSearchIndex: Int = -1
    private var searchQuery: String = ""
    
    // 音频文件相关
    private var isAudioFile: Boolean = false
    private var audioFilePath: String = ""
    private var audioDuration: Long = 0L  // 音频总时长（毫秒）
    private var audioCurrentPosition: Long = 0L  // 当前播放位置（毫秒）
    private var isPlaying: Boolean = false
    
    // MediaPlayer
    private var mediaPlayer: MediaPlayer? = null
    
    // 文件选择器
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { openFileFromUri(it) }
    }
    
    // 保存文件选择器
    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let { saveFileToUri(it) }
    }
    
    // 草稿箱选择器
    private val draftLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val content = result.data?.getStringExtra(DraftsActivity.EXTRA_DRAFT_CONTENT) ?: ""
            val draftFileName = result.data?.getStringExtra(DraftsActivity.EXTRA_DRAFT_FILE_NAME) ?: ""
            if (content.isNotEmpty()) {
                loadDraftContent(content, draftFileName)
            }
        }
    }
    
    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_IS_AUDIO_FILE = "extra_is_audio_file"
        const val EXTRA_SUBTITLE_FILE_PATH = "extra_subtitle_file_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
        isAudioFile = intent.getBooleanExtra(EXTRA_IS_AUDIO_FILE, false)
        subtitleFilePath = intent.getStringExtra(EXTRA_SUBTITLE_FILE_PATH) ?: ""
        
        if (filePath.isNotEmpty()) {
            if (isAudioFile) {
                // 音频文件模式：currentFile 指向音频文件，subtitleFile 指向字幕文件
                currentFile = File(filePath)
                if (subtitleFilePath.isNotEmpty()) {
                    subtitleFile = File(subtitleFilePath)
                }
            } else {
                // 普通模式：currentFile 指向字幕文件
                currentFile = File(filePath)
            }
        }
        
        setupToolbar()
        setupRecyclerView()
        setupSourceView()
        setupSearchBar()
        setupAudioPlayer()
        initializeMediaPlayer()
        
        if (filePath.isNotEmpty()) {
            if (isAudioFile) {
                loadAudioFile(subtitleFilePath)
            } else {
                loadFile()
            }
        }
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = getString(R.string.app_name)
        
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
        
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            handleMenuClick(menuItem)
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }
    
    private fun handleMenuClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_new -> {
                newFile()
                true
            }
            R.id.menu_open -> {
                openFile()
                true
            }
            R.id.menu_save -> {
                saveFile()
                true
            }
            R.id.menu_save_as -> {
                saveFileAs()
                true
            }
            R.id.menu_encoding -> {
                showEncodingDialog()
                true
            }
            R.id.menu_source_view -> {
                toggleSourceView()
                true
            }
            R.id.menu_search -> {
                showSearchBar()
                true
            }
            R.id.menu_cancel_selection -> {
                cancelSelection()
                true
            }
            R.id.menu_select_range -> {
                showSelectRangeDialog()
                true
            }
            R.id.menu_save_draft -> {
                saveDraft()
                true
            }
            R.id.menu_drafts -> {
                openDrafts()
                true
            }
            else -> false
        }
    }
    
    private fun setupRecyclerView() {
        subtitleAdapter = SubtitleAdapter(
            onItemClick = { _, _ ->
                updateSelectedCountDisplay()
            },
            onItemLongClick = { entry, position ->
                showContextMenu(position)
            },
            onTimeClick = { entry, position, isStartTime ->
                showTimeEditDialog(entry, position, isStartTime)
            },
            onTextClick = { entry, position ->
                showTextEditDialog(entry, position)
            },
            onJumpToTimeClick = { entry, position ->
                jumpToSubtitleTime(entry)
            },
            onSetTimeClick = { entry, position ->
                setSubtitleTimeToCurrentPosition(entry, position)
            },
            isAudioFile = isAudioFile
        )
        
        binding.rvSubtitles.apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            adapter = subtitleAdapter
        }
    }
    
    private fun setupSourceView() {
        binding.etSourceView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSourceViewMode) {
                    hasUnsavedChanges = true
                    sourceViewContent = s?.toString() ?: ""
                }
            }
        })
    }
    
    private fun setupSearchBar() {
        // 搜索输入框监听
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.isNotEmpty() && query != searchQuery) {
                    searchQuery = query
                    performSearch()
                }
            }
        })
        
        // 上一项按钮
        binding.btnSearchPrevious.setOnClickListener {
            goToPreviousResult()
        }
        
        // 下一项按钮
        binding.btnSearchNext.setOnClickListener {
            goToNextResult()
        }
        
        // 关闭按钮
        binding.btnSearchClose.setOnClickListener {
            hideSearchBar()
        }
        
        // 替换按钮
        binding.btnReplace.setOnClickListener {
            replaceOne()
        }
        
        // 全部替换按钮
        binding.btnReplaceAll.setOnClickListener {
            replaceAll()
        }
        
        // 回车键搜索
        binding.etSearch.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                performSearch()
                true
            } else {
                false
            }
        }
    }
    
    /**
     * 显示搜索条
     */
    private fun showSearchBar() {
        binding.searchBar.visibility = android.view.View.VISIBLE
        binding.etSearch.requestFocus()
        binding.etSearch.text?.clear()
        searchResults = emptyList()
        currentSearchIndex = -1
        // 显示软键盘
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    
    /**
     * 隐藏搜索条
     */
    private fun hideSearchBar() {
        binding.searchBar.visibility = android.view.View.GONE
        searchResults = emptyList()
        currentSearchIndex = -1
        searchQuery = ""
        binding.etSearch.text?.clear()
        binding.etReplace.text?.clear()
        // 清除列表中的搜索高亮
        subtitleAdapter.clearSearchHighlight()
        // 清除源视图中的搜索高亮
        if (isSourceViewMode) {
            clearSearchHighlightInSourceView()
        }
        // 隐藏软键盘
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }
    
    /**
     * 删除选中的字幕
     */
    private fun deleteSelectedSubtitle() {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val selectedEntries = subtitleAdapter.getSelectedEntries()
        if (selectedEntries.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的字幕", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确定要删除选中的字幕吗？")
            .setPositiveButton("确定") { _, _ ->
                // 保存要删除的条目对象
                val entriesToDelete = selectedEntries.map { it.first }
                // 从后往前删除，避免索引变化
                selectedEntries.sortedByDescending { it.second }.forEach { (_, position) ->
                    subtitleEntries.removeAt(position)
                }
                renumberEntries()
                subtitleAdapter.submitList(subtitleEntries.toList())
                // 从选中状态中移除被删除的条目
                entriesToDelete.forEach { entry ->
                    subtitleAdapter.removeSelectionByEntry(entry)
                }
                updateSelectedCountDisplay()
                updateFormatInfo()
                
                markAsChanged()
                Toast.makeText(this, "已删除 ${selectedEntries.size} 条字幕", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 清除源视图中的搜索高亮
     */
    private fun clearSearchHighlightInSourceView() {
        val content = binding.etSourceView.text?.toString() ?: ""
        // 恢复原始内容（不带高亮）
        binding.etSourceView.setText(content, TextView.BufferType.EDITABLE)
    }
    
    /**
     * 替换一个匹配项
     */
    private fun replaceOne() {
        val replaceText = binding.etReplace.text?.toString() ?: ""
        if (searchQuery.isEmpty() || searchResults.isEmpty()) {
            Toast.makeText(this, "请先搜索内容", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isSourceViewMode) {
            replaceOneInSourceView(replaceText)
        } else {
            replaceOneInRecyclerView(replaceText)
        }
    }
    
    /**
     * 在源视图中替换一个匹配项
     */
    private fun replaceOneInSourceView(replaceText: String) {
        val content = binding.etSourceView.text?.toString() ?: ""
        if (currentSearchIndex < 0 || currentSearchIndex >= searchResults.size) {
            Toast.makeText(this, "没有可替换的匹配项", Toast.LENGTH_SHORT).show()
            return
        }
        
        val position = searchResults[currentSearchIndex]
        val newContent = content.replaceRange(
            position,
            position + searchQuery.length,
            replaceText
        )
        binding.etSourceView.setText(newContent)
        sourceViewContent = newContent
        hasUnsavedChanges = true
        
        // 重新搜索以更新结果
        searchInSourceView()
        Toast.makeText(this, "已替换 1 处", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 在列表中替换一个匹配项
     */
    private fun replaceOneInRecyclerView(replaceText: String) {
        if (currentSearchIndex < 0 || currentSearchIndex >= searchResults.size) {
            Toast.makeText(this, "没有可替换的匹配项", Toast.LENGTH_SHORT).show()
            return
        }
        
        val position = searchResults[currentSearchIndex]
        val entry = subtitleEntries[position]
        val newText = entry.text.replace(searchQuery, replaceText, ignoreCase = true)
        
        if (entry.text != newText) {
            entry.text = newText
            subtitleAdapter.notifyItemChanged(position)
            hasUnsavedChanges = true
            
            // 重新搜索以更新结果
            searchInRecyclerView()
            Toast.makeText(this, "已替换 1 处", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "当前项无可替换内容", Toast.LENGTH_SHORT).show()
            // 跳转到下一个
            goToNextResult()
        }
    }
    
    /**
     * 全部替换
     */
    private fun replaceAll() {
        val replaceText = binding.etReplace.text?.toString() ?: ""
        if (searchQuery.isEmpty()) {
            Toast.makeText(this, "请先搜索内容", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isSourceViewMode) {
            replaceAllInSourceView(replaceText)
        } else {
            replaceAllInRecyclerView(replaceText)
        }
    }
    
    /**
     * 在源视图中全部替换
     */
    private fun replaceAllInSourceView(replaceText: String) {
        val content = binding.etSourceView.text?.toString() ?: ""
        var count = 0
        val newContent = content.replace(Regex(Regex.escape(searchQuery)), replaceText)
            .let {
                // 计算替换次数
                count = (content.length - it.length) / searchQuery.length + 
                    (if (searchQuery.length != replaceText.length) 0 else 
                        content.split(searchQuery, ignoreCase = true).size - 1)
                it
            }
        
        // 更准确地计算替换次数
        count = content.split(Regex(Regex.escape(searchQuery), RegexOption.IGNORE_CASE)).size - 1
        
        if (count > 0) {
            AlertDialog.Builder(this)
                .setTitle("确认替换")
                .setMessage("确定要全部替换吗？共找到 $count 处匹配项。")
                .setPositiveButton("确定") { _, _ ->
                    binding.etSourceView.setText(newContent)
                    sourceViewContent = newContent
                    hasUnsavedChanges = true
                    
                    // 清除搜索结果
                    searchResults = emptyList()
                    currentSearchIndex = -1
                    searchQuery = ""
                    binding.etSearch.text?.clear()
                    
                    Toast.makeText(this, "已替换 $count 处", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            Toast.makeText(this, "没有找到可替换的内容", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 在列表中全部替换
     */
    private fun replaceAllInRecyclerView(replaceText: String) {
        var count = 0
        val positionsToRefresh = mutableListOf<Int>()
        
        subtitleEntries.forEachIndexed { index, entry ->
            if (entry.text.contains(searchQuery, ignoreCase = true)) {
                val newText = entry.text.replace(Regex(Regex.escape(searchQuery), RegexOption.IGNORE_CASE), replaceText)
                if (entry.text != newText) {
                    entry.text = newText
                    positionsToRefresh.add(index)
                    count++
                }
            }
        }
        
        if (count > 0) {
            AlertDialog.Builder(this)
                .setTitle("确认替换")
                .setMessage("确定要全部替换吗？共找到 $count 处匹配项。")
                .setPositiveButton("确定") { _, _ ->
                    positionsToRefresh.forEach { position ->
                        subtitleAdapter.notifyItemChanged(position)
                    }
                    hasUnsavedChanges = true
                    
                    // 清除搜索结果
                    searchResults = emptyList()
                    currentSearchIndex = -1
                    searchQuery = ""
                    binding.etSearch.text?.clear()
                    
                    Toast.makeText(this, "已替换 $count 处", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            Toast.makeText(this, "没有找到可替换的内容", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 执行搜索
     */
    private fun performSearch() {
        if (isSourceViewMode) {
            // 源视图模式下搜索文本内容
            searchInSourceView()
        } else {
            // 列表视图模式下搜索
            searchInRecyclerView()
        }
    }
    
    /**
     * 在源视图中搜索
     */
    private fun searchInSourceView() {
        val content = binding.etSourceView.text?.toString() ?: ""
        if (searchQuery.isEmpty() || content.isEmpty()) {
            searchResults = emptyList()
            currentSearchIndex = -1
            // 清除高亮
            val spannable = SpannableString(content)
            binding.etSourceView.setText(spannable, TextView.BufferType.EDITABLE)
            return
        }
        
        searchResults = mutableListOf()
        var index = content.indexOf(searchQuery, ignoreCase = true)
        while (index >= 0) {
            (searchResults as MutableList).add(index)
            index = content.indexOf(searchQuery, index + 1, ignoreCase = true)
        }
        
        currentSearchIndex = if (searchResults.isNotEmpty()) 0 else -1
        updateSearchResultDisplay()
        highlightSearchInSourceView()
    }
    
    /**
     * 在源视图中高亮搜索结果
     */
    private fun highlightSearchInSourceView() {
        val content = binding.etSourceView.text?.toString() ?: ""
        if (searchQuery.isEmpty() || searchResults.isEmpty()) return
        
        val spannable = SpannableString(content)
        val highlightColor = ContextCompat.getColor(this, R.color.inverse_primary)
        
        // 高亮所有搜索结果
        searchResults.forEach { startIndex ->
            val endIndex = (startIndex + searchQuery.length).coerceAtMost(content.length)
            spannable.setSpan(
                BackgroundColorSpan(highlightColor),
                startIndex,
                endIndex,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                startIndex,
                endIndex,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        
        // 高亮当前选中的结果（使用不同颜色）
        if (currentSearchIndex >= 0 && currentSearchIndex < searchResults.size) {
            val currentIndex = searchResults[currentSearchIndex]
            val endIndex = (currentIndex + searchQuery.length).coerceAtMost(content.length)
            // 当前结果使用更亮的颜色
            spannable.setSpan(
                BackgroundColorSpan(ContextCompat.getColor(this, R.color.secondary)),
                currentIndex,
                endIndex,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        
        binding.etSourceView.setText(spannable, TextView.BufferType.EDITABLE)
        
        // 滚动到当前结果
        if (currentSearchIndex >= 0 && currentSearchIndex < searchResults.size) {
            val position = searchResults[currentSearchIndex]
            if (position < content.length) {
                val linesBefore = content.substring(0, position).count { it == '\n' }
                val estimatedScroll = linesBefore * 50
                binding.svSourceView.scrollTo(0, estimatedScroll)
            }
        }
    }
    
    /**
     * 在列表中搜索（搜索字幕文本和时间）
     */
    private fun searchInRecyclerView() {
        if (searchQuery.isEmpty()) {
            searchResults = emptyList()
            currentSearchIndex = -1
            return
        }
        
        searchResults = subtitleEntries.mapIndexedNotNull { index, entry ->
            // 搜索文本内容
            if (entry.text.contains(searchQuery, ignoreCase = true)) {
                index
            } else {
                // 搜索时间（格式化为字符串后搜索）
                val timeStr = TimeUtils.formatForDisplay(entry.startTime)
                if (timeStr.contains(searchQuery, ignoreCase = true)) {
                    index
                } else {
                    null
                }
            }
        }
        
        currentSearchIndex = if (searchResults.isNotEmpty()) 0 else -1
        updateSearchResultDisplay()
        
        // 跳转到第一个结果
        if (searchResults.isNotEmpty()) {
            scrollToSearchResult(0)
        }
    }
    
    /**
     * 更新搜索结果显示
     */
    private fun updateSearchResultDisplay() {
        if (searchResults.isEmpty()) {
            Toast.makeText(this, getString(R.string.search_no_results), Toast.LENGTH_SHORT).show()
        } else {
            val message = getString(R.string.search_result_count, searchResults.size, currentSearchIndex + 1)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 滚动到搜索结果位置
     */
    private fun scrollToSearchResult(index: Int) {
        if (index < 0 || index >= searchResults.size) return
        
        val position = searchResults[index]
        if (isSourceViewMode) {
            // 源视图模式：重新高亮并滚动
            highlightSearchInSourceView()
        } else {
            // 列表模式：滚动 RecyclerView
            binding.rvSubtitles.scrollToPosition(position)
            // 高亮显示（通过 adapter）
            subtitleAdapter.highlightSearchResult(position, searchQuery)
        }
    }
    
    /**
     * 跳转到上一个搜索结果
     */
    private fun goToPreviousResult() {
        if (searchResults.isEmpty()) return
        
        currentSearchIndex = if (currentSearchIndex <= 0) {
            searchResults.size - 1
        } else {
            currentSearchIndex - 1
        }
        updateSearchResultDisplay()
        scrollToSearchResult(currentSearchIndex)
    }
    
    /**
     * 跳转到下一个搜索结果
     */
    private fun goToNextResult() {
        if (searchResults.isEmpty()) return
        
        currentSearchIndex = if (currentSearchIndex >= searchResults.size - 1) {
            0
        } else {
            currentSearchIndex + 1
        }
        updateSearchResultDisplay()
        scrollToSearchResult(currentSearchIndex)
    }
    
    private fun updateSelectedCountDisplay() {
        val count = subtitleAdapter.getSelectedCount()
        if (count > 0) {
            binding.tvSelectedCount.text = "已选择 $count 项"
            binding.tvSelectedCount.visibility = android.view.View.VISIBLE
        } else {
            binding.tvSelectedCount.visibility = android.view.View.GONE
        }
    }
    
    private fun loadFile() {
        if (filePath.isEmpty() || currentFile == null) {
            Toast.makeText(this, "文件路径无效", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        if (!currentFile!!.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        binding.tvFileName.text = currentFile!!.name
        // 使用用户设置的默认编码
        val settingsManager = SettingsManager.getInstance(this)
        currentCharset = settingsManager.getDefaultEncoding()
        
        try {
            val content = FileUtils.readFile(currentFile!!, currentCharset)
            parseContent(content)
            hasUnsavedChanges = false
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openFileFromUri(uri: Uri) {
        try {
            val content = FileUtils.readUri(this, uri)
            currentFile = null
            // 获取文件名并更新显示
            val fileName = getFileNameFromUri(uri)
            binding.tvFileName.text = fileName
            parseContent(content)
            hasUnsavedChanges = false
            Toast.makeText(this, "文件已打开：$fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "打开文件失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 从 URI 获取文件名
     */
    private fun getFileNameFromUri(uri: Uri): String {
        var fileName = "未命名"
        // 尝试从 display name 获取
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && nameIndex >= 0) {
                fileName = it.getString(nameIndex)
            }
        }
        // 如果获取失败，尝试从 path 获取
        if (fileName == "未命名") {
            val path = uri.path
            if (!path.isNullOrEmpty()) {
                fileName = path.substringAfterLast('/')
            }
        }
        return fileName
    }
    
    private fun reloadFile() {
        if (currentFile != null && currentFile!!.exists()) {
            try {
                val content = FileUtils.readFile(currentFile!!, currentCharset)
                parseContent(content)
                hasUnsavedChanges = false
                Toast.makeText(this, "已切换编码为：${FileUtils.SUPPORTED_ENCODINGS.find { it.charset == currentCharset }?.displayName}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "切换编码失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "当前文件无法重新加载编码，请通过「打开」功能重新选择文件", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun parseContent(content: String) {
        currentFormat = SubtitleParser.detectFormat(content)
        
        // 始终保存原始文件内容
        originalFileContent = content
        
        // 如果是 TXT 格式，直接使用源视图模式
        if (currentFormat == SubtitleParser.SubtitleFormat.TXT) {
            sourceViewContent = originalFileContent
            enterSourceViewMode()
        } else {
            subtitleEntries = SubtitleParser.parse(content, currentCharset).toMutableList()
            exitSourceViewMode()
        }
        
        updateFormatInfo()
        
        if (subtitleEntries.isEmpty() && !isSourceViewMode) {
            Toast.makeText(this, "未找到字幕内容", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 进入源视图模式（带动画，保持滚动位置）
     */
    private fun enterSourceViewMode() {
        isSourceViewMode = true
        
        // 保存 RecyclerView 的滚动位置
        val layoutManager = binding.rvSubtitles.layoutManager as LinearLayoutManager
        savedFirstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
        val firstView = layoutManager.findViewByPosition(savedFirstVisibleItemPosition)
        savedScrollPosition = firstView?.top ?: 0
        
        // 设置源视图内容
        binding.etSourceView.setText(sourceViewContent)
        
        // 淡出字幕列表，淡入源视图
        binding.rvSubtitles.animate()
            .alpha(0f)
            .setDuration(150)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.rvSubtitles.visibility = android.view.View.GONE
                    binding.svSourceView.alpha = 0f
                    binding.svSourceView.visibility = android.view.View.VISIBLE
                    binding.svSourceView.animate()
                        .alpha(1f)
                        .setDuration(150)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                // 恢复滚动位置 - 根据可见项计算滚动位置
                                if (savedFirstVisibleItemPosition >= 0 && savedFirstVisibleItemPosition < subtitleEntries.size) {
                                    // 估算滚动位置（每行约 80dp）
                                    val estimatedScroll = savedFirstVisibleItemPosition * 80 - savedScrollPosition
                                    binding.svSourceView.scrollTo(0, estimatedScroll.coerceAtLeast(0))
                                }
                            }
                        })
                }
            })
        
        updateSourceViewMenuTitle()
    }
    
    /**
     * 退出源视图模式（带动画，保持滚动位置）
     */
    private fun exitSourceViewMode() {
        isSourceViewMode = false
        
        // 保存 ScrollView 的滚动位置
        savedScrollPosition = binding.svSourceView.scrollY
        
        // 刷新字幕列表
        subtitleAdapter.submitList(subtitleEntries.toList())
        
        // 淡出源视图，淡入字幕列表
        binding.svSourceView.animate()
            .alpha(0f)
            .setDuration(150)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.svSourceView.visibility = android.view.View.GONE
                    binding.rvSubtitles.alpha = 0f
                    binding.rvSubtitles.visibility = android.view.View.VISIBLE
                    binding.rvSubtitles.animate()
                        .alpha(1f)
                        .setDuration(150)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                // 恢复滚动位置 - 根据滚动位置计算可见项
                                val layoutManager = binding.rvSubtitles.layoutManager as LinearLayoutManager
                                val estimatedPosition = savedScrollPosition / 80
                                layoutManager.scrollToPositionWithOffset(estimatedPosition.coerceIn(0, subtitleEntries.size - 1), 0)
                            }
                        })
                }
            })
        
        updateSourceViewMenuTitle()
    }
    
    /**
     * 切换源视图模式
     */
    private fun toggleSourceView() {
        if (currentFormat == SubtitleParser.SubtitleFormat.TXT) {
            Toast.makeText(this, "TXT 文件只能使用源视图模式", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("当前有未保存的更改，切换视图可能会丢失更改，确定继续吗？")
                .setPositiveButton("确定") { _, _ ->
                    doToggleSourceView()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            doToggleSourceView()
        }
    }
    
    private fun doToggleSourceView() {
        if (isSourceViewMode) {
            // 从源视图切换到列表视图
            val editedContent = binding.etSourceView.text.toString()
            // 尝试解析源视图内容为字幕条目
            try {
                subtitleEntries = SubtitleParser.parse(editedContent, currentCharset).toMutableList()
                exitSourceViewMode()
                updateFormatInfo()
                Toast.makeText(this, "已切换到列表视图", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "解析失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            // 从列表视图切换到源视图 - 如果有原始内容，优先显示原始内容
            if (originalFileContent.isNotEmpty()) {
                sourceViewContent = originalFileContent
            } else {
                sourceViewContent = when (currentFormat) {
                    SubtitleParser.SubtitleFormat.SRT -> SubtitleParser.toSRT(subtitleEntries)
                    SubtitleParser.SubtitleFormat.LRC -> SubtitleParser.toLRC(subtitleEntries)
                    else -> SubtitleParser.toSRT(subtitleEntries)
                }
            }
            enterSourceViewMode()
            Toast.makeText(this, "已切换到源视图", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 更新源视图菜单项标题
     */
    private fun updateSourceViewMenuTitle() {
        // 菜单项标题在 strings.xml 中定义，这里不需要动态更新
    }
    
    /**
     * 加载草稿内容（覆盖当前内容）
     */
    private fun loadDraftContent(content: String, draftFileName: String) {
        AlertDialog.Builder(this)
            .setTitle("加载草稿")
            .setMessage("确定要用草稿内容覆盖当前编辑内容吗？（只覆盖内容，不更改文件名）")
            .setPositiveButton("确定") { _, _ ->
                parseContent(content)
                hasUnsavedChanges = true
                Toast.makeText(this, "已加载草稿：$draftFileName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showContextMenu(position: Int) {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 保存长按位置
        longClickPosition = position
        
        val selectedCount = subtitleAdapter.getSelectedCount()
        val hasSelection = selectedCount > 0
        val hasClipboard = clipboardEntries.isNotEmpty()
        
        // 构建菜单项列表
        val itemsList = mutableListOf<String>()
        
        // 如果有选中项，添加"只对勾选字幕生效"选项
        if (hasSelection) {
            itemsList.add("只对勾选字幕生效 (${selectedCount}项)")
        }
        
        // 添加常规操作（针对当前长按的字幕）
        itemsList.add("时间偏移")
        itemsList.add("向前插入")
        itemsList.add("向后插入")
        itemsList.add("复制")
        itemsList.add("剪切 (粘贴后删除)")
        if (hasClipboard) {
            itemsList.add("粘贴 (${clipboardEntries.size}项)[当前行]")
        } else {
            itemsList.add("粘贴")
        }
        itemsList.add("删除")
        
        val items = itemsList.toTypedArray()
        
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                if (hasSelection && which == 0) {
                    // 用户选择了"只对勾选字幕生效"，显示针对选中项的操作菜单
                    showSelectionContextMenu(selectedCount, hasClipboard)
                } else {
                    // 常规操作，索引需要调整
                    val actualWhich = if (hasSelection) which - 1 else which
                    when (actualWhich) {
                        0 -> showOffsetDialog(position)  // 时间偏移
                        1 -> insertSubtitle(false, position)  // 向前插入
                        2 -> insertSubtitle(true, position)  // 向后插入
                        3 -> copySingle(position)  // 复制
                        4 -> cutSingle(position)  // 剪切
                        5 -> if (hasClipboard) pasteToPosition(position) else {  // 粘贴
                            Toast.makeText(this, "剪贴板为空，请先复制", Toast.LENGTH_SHORT).show()
                        }
                        6 -> deleteSingleSubtitle(position)  // 删除
                    }
                }
            }
            .show()
    }
    
    /**
     * 显示针对选中项的操作菜单
     */
    private fun showSelectionContextMenu(selectedCount: Int, hasClipboard: Boolean) {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val itemsList = mutableListOf<String>()
        itemsList.add("时间偏移")
        itemsList.add("AI 翻译")
        itemsList.add("复制")
        itemsList.add("剪切 (粘贴后删除)")
        if (hasClipboard) {
            itemsList.add("粘贴 (${clipboardEntries.size}项)")
        } else {
            itemsList.add("粘贴")
        }
        itemsList.add("删除选中")
        
        val items = itemsList.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("对勾选字幕操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showOffsetDialogForSelection()
                    1 -> showAiTranslate()
                    2 -> copySelected()
                    3 -> cutSelected()
                    4 -> if (hasClipboard) pasteToSelected() else {
                        Toast.makeText(this, "剪贴板为空，请先复制", Toast.LENGTH_SHORT).show()
                    }
                    5 -> deleteSelectedSubtitles()
                }
            }
            .show()
    }
    
    /**
     * 复制单个字幕（长按的字幕）
     */
    private fun copySingle(position: Int) {
        if (isSourceViewMode) return
        
        if (position >= 0 && position < subtitleEntries.size) {
            clipboardEntries = listOf(subtitleEntries[position].copy())
            isCutMode = false
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 剪切单个字幕（长按的字幕）
     */
    private fun cutSingle(position: Int) {
        if (isSourceViewMode) return
        
        if (position >= 0 && position < subtitleEntries.size) {
            // 先保存到剪贴板
            clipboardEntries = listOf(subtitleEntries[position].copy())
            isCutMode = true
            cutPositionValue = position
            cutPositionsValue = emptyList()
            Toast.makeText(this, "已剪切", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 剪切选中的字幕
     */
    private fun cutSelected() {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val selectedEntries = subtitleAdapter.getSelectedEntries()
        if (selectedEntries.isEmpty()) {
            Toast.makeText(this, "请先选择要剪切的字幕", Toast.LENGTH_SHORT).show()
            return
        }
        
        clipboardEntries = selectedEntries.map { it.first.copy() }
        isCutMode = true
        // 保存要删除的位置（从后往前排序，方便删除）
        cutPositionsValue = selectedEntries.sortedByDescending { it.second }.map { it.second }
        cutPositionValue = -1
        Toast.makeText(this, "已剪切 ${clipboardEntries.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    // 剪切时保存要删除的位置（单行）
    private var cutPositionValue: Int = -1
    // 剪切时保存要删除的位置（多行）
    private var cutPositionsValue: List<Int> = emptyList()
    
    private fun hasSelection(): Boolean {
        return subtitleAdapter.getSelectedCount() > 0
    }
    
    /**
     * 执行剪切删除操作（在粘贴后调用）
     */
    private fun performCutDelete() {
        if (!isCutMode) return
        
        // 保存要删除的条目对象（用于从选中状态中移除）
        val entriesToDelete = if (cutPositionValue >= 0 && cutPositionValue < subtitleEntries.size) {
            listOf(subtitleEntries[cutPositionValue])
        } else if (cutPositionsValue.isNotEmpty()) {
            cutPositionsValue.filter { it < subtitleEntries.size }.map { subtitleEntries[it] }
        } else {
            emptyList()
        }
        
        if (cutPositionValue >= 0) {
            // 单行剪切
            if (cutPositionValue < subtitleEntries.size) {
                subtitleEntries.removeAt(cutPositionValue)
                renumberEntries()
                subtitleAdapter.submitList(subtitleEntries.toList())
                // 同步选中状态
                subtitleAdapter.syncSelectionWithCurrentList()
                // 从选中状态中移除被删除的条目
                entriesToDelete.forEach { entry ->
                    subtitleAdapter.removeSelectionByEntry(entry)
                }
                updateSelectedCountDisplay()
                updateFormatInfo()
                markAsChanged()
            }
            cutPositionValue = -1
        } else if (cutPositionsValue.isNotEmpty()) {
            // 多行剪切 - 从后往前删除
            val sortedPositions = cutPositionsValue.sortedDescending()
            sortedPositions.forEach { position ->
                if (position < subtitleEntries.size) {
                    subtitleEntries.removeAt(position)
                }
            }
            renumberEntries()
            subtitleAdapter.submitList(subtitleEntries.toList())
            // 同步选中状态
            subtitleAdapter.syncSelectionWithCurrentList()
            // 从选中状态中移除被删除的条目
            entriesToDelete.forEach { entry ->
                subtitleAdapter.removeSelectionByEntry(entry)
            }
            updateSelectedCountDisplay()
            updateFormatInfo()
            markAsChanged()
            cutPositionsValue = emptyList()
        }
        
        isCutMode = false
    }
    
    /**
     * 刷新所有条目（确保序号实时更新）
     */
    private fun refreshAllItems() {
        renumberEntries()
        subtitleAdapter.submitList(subtitleEntries.toList())
    }
    
    /**
     * 粘贴到指定位置（单行替换）
     */
    private fun pasteToPosition(position: Int) {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (clipboardEntries.isEmpty()) {
            Toast.makeText(this, "剪贴板为空，请先复制", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (position >= 0 && position < subtitleEntries.size) {
            // 如果是剪切模式，先删除原字幕
            if (isCutMode) {
                performCutDelete()
            }
            
            if (clipboardEntries.size == 1) {
                // 单行粘贴，直接替换
                val targetEntry = subtitleEntries[position]
                val sourceEntry = clipboardEntries[0]
                targetEntry.startTime = sourceEntry.startTime
                targetEntry.endTime = sourceEntry.endTime
                targetEntry.text = sourceEntry.text
                
                subtitleAdapter.notifyItemChanged(position)
                markAsChanged()
                Toast.makeText(this, "已粘贴", Toast.LENGTH_SHORT).show()
            } else {
                // 多行粘贴，从当前位置开始替换/插入
                pasteMultipleAtPosition(position)
            }
        }
    }
    
    /**
     * 多行粘贴到指定位置
     */
    private fun pasteMultipleAtPosition(position: Int) {
        // 计算需要替换的行数
        val remainingRows = subtitleEntries.size - position
        
        if (clipboardEntries.size <= remainingRows) {
            // 剪贴板行数小于等于剩余行数，直接替换
            for (i in clipboardEntries.indices) {
                val targetEntry = subtitleEntries[position + i]
                val sourceEntry = clipboardEntries[i]
                targetEntry.startTime = sourceEntry.startTime
                targetEntry.endTime = sourceEntry.endTime
                targetEntry.text = sourceEntry.text
            }
            // 刷新受影响的位置
            for (i in 0 until clipboardEntries.size) {
                subtitleAdapter.notifyItemChanged(position + i)
            }
        } else {
            // 剪贴板行数大于剩余行数，替换后插入多余行
            for (i in 0 until remainingRows) {
                val targetEntry = subtitleEntries[position + i]
                val sourceEntry = clipboardEntries[i]
                targetEntry.startTime = sourceEntry.startTime
                targetEntry.endTime = sourceEntry.endTime
                targetEntry.text = sourceEntry.text
            }
            // 插入多余的行
            for (i in remainingRows until clipboardEntries.size) {
                val newEntry = clipboardEntries[i].copy()
                subtitleEntries.add(position + remainingRows, newEntry)
            }
            renumberEntries()
            subtitleAdapter.submitList(subtitleEntries.toList())
        }
        
        markAsChanged()
        Toast.makeText(this, "已粘贴 ${clipboardEntries.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 删除单个字幕（长按的字幕）
     */
    private fun deleteSingleSubtitle(position: Int) {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (position >= 0 && position < subtitleEntries.size) {
            AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("确定要删除此字幕吗？")
                .setPositiveButton("确定") { _, _ ->
                    // 保存要删除的条目对象
                    val entryToDelete = subtitleEntries[position]
                    subtitleEntries.removeAt(position)
                    renumberEntries()
                    subtitleAdapter.submitList(subtitleEntries.toList())
                    // 同步选中状态
                    subtitleAdapter.syncSelectionWithCurrentList()
                    // 从选中状态中移除被删除的条目
                    subtitleAdapter.removeSelectionByEntry(entryToDelete)
                    updateSelectedCountDisplay()
                    updateFormatInfo()
                    
                    markAsChanged()
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
    
    /**
     * 插入字幕到指定位置
     */
    private fun insertSubtitle(after: Boolean, refPosition: Int) {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val insertPosition = if (after) refPosition + 1 else refPosition
        
        val newEntry = SubtitleEntry()
        newEntry.index = insertPosition + 1
        
        if (subtitleEntries.isNotEmpty()) {
            val refIndex = if (after && insertPosition > 0) insertPosition - 1 else insertPosition
            if (refIndex < subtitleEntries.size) {
                val refEntry = subtitleEntries[refIndex]
                newEntry.startTime = refEntry.endTime
                newEntry.endTime = newEntry.startTime + 3000
            }
        }
        
        newEntry.text = "新字幕"
        
        subtitleEntries.add(insertPosition, newEntry)
        renumberEntries()
        subtitleAdapter.submitList(subtitleEntries.toList())
        // 同步选中状态
        subtitleAdapter.syncSelectionWithCurrentList()
        // 不清空选择，也不自动选中新插入的行
        updateSelectedCountDisplay()
        updateFormatInfo()
        
        markAsChanged()
        Toast.makeText(this, "已插入新字幕", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 显示针对选中字幕的时间偏移对话框
     */
    private fun showOffsetDialogForSelection() {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        
        // 毫秒输入
        val msLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val etMs = EditText(this).apply {
            hint = "毫秒"
            inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            setText("0")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvMs = TextView(this).apply {
            text = "毫秒"
            setPadding(20, 0, 0, 0)
        }
        msLayout.addView(etMs)
        msLayout.addView(tvMs)
        
        // 秒输入
        val secLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val etSec = EditText(this).apply {
            hint = "秒"
            inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            setText("0")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvSec = TextView(this).apply {
            text = "秒"
            setPadding(20, 0, 0, 0)
        }
        secLayout.addView(etSec)
        secLayout.addView(tvSec)
        
        // 分输入
        val minLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val etMin = EditText(this).apply {
            hint = "分"
            inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            setText("0")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvMin = TextView(this).apply {
            text = "分"
            setPadding(20, 0, 0, 0)
        }
        minLayout.addView(etMin)
        minLayout.addView(tvMin)
        
        // 小时输入
        val hourLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val etHour = EditText(this).apply {
            hint = "小时"
            inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            setText("0")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvHour = TextView(this).apply {
            text = "小时"
            setPadding(20, 0, 0, 0)
        }
        hourLayout.addView(etHour)
        hourLayout.addView(tvHour)
        
        layout.addView(msLayout)
        layout.addView(secLayout)
        layout.addView(minLayout)
        layout.addView(hourLayout)
        
        AlertDialog.Builder(this)
            .setTitle("时间偏移 (只对勾选字幕)")
            .setMessage("输入偏移量，正数延迟，负数提前")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val ms = etMs.text.toString().toLongOrNull() ?: 0L
                val sec = etSec.text.toString().toLongOrNull() ?: 0L
                val min = etMin.text.toString().toLongOrNull() ?: 0L
                val hour = etHour.text.toString().toLongOrNull() ?: 0L
                
                val totalOffset = ms + sec * 1000 + min * 60000 + hour * 3600000
                applyOffsetToSelection(totalOffset)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 对选中的字幕应用时间偏移
     */
    private fun applyOffsetToSelection(offsetMs: Long) {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val selectedEntries = subtitleAdapter.getSelectedEntries()
        
        if (selectedEntries.isEmpty()) {
            Toast.makeText(this, "没有选中的字幕", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 保存选中的条目对象（用于同步选中状态）
        val selectedEntryObjects = selectedEntries.map { it.first }.toSet()
        
        // 应用时间偏移
        selectedEntryObjects.forEach { entry ->
            entry.startTime = (entry.startTime + offsetMs).coerceAtLeast(0)
            entry.endTime = (entry.endTime + offsetMs).coerceAtLeast(entry.startTime + 1)
        }
        
        // 刷新列表并同步选中状态
        subtitleAdapter.submitList(subtitleEntries.toList())
        subtitleAdapter.syncSelectionWithCurrentList()
        
        markAsChanged()
        Toast.makeText(this, "已对选中项应用 ${offsetMs}ms 偏移", Toast.LENGTH_SHORT).show()
    }
    
    private fun showTimeEditDialog(entry: SubtitleEntry, position: Int, isStartTime: Boolean) {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val currentTime = if (isStartTime) entry.startTime else entry.endTime
        val editText = EditText(this).apply {
            setText(TimeUtils.formatForInput(currentTime))
            inputType = EditorInfo.TYPE_CLASS_TEXT
            hint = "格式：00:00:01.500"
        }
        
        AlertDialog.Builder(this)
            .setTitle(if (isStartTime) "编辑开始时间" else "编辑结束时间")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newTime = TimeUtils.parseFromInput(editText.text.toString())
                if (newTime != null) {
                    if (isStartTime) {
                        entry.startTime = newTime
                    } else {
                        entry.endTime = newTime
                    }
                    
                    // 直接刷新该位置
                    subtitleAdapter.notifyItemChanged(position)
                    markAsChanged()
                    Toast.makeText(this, "已更新", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "时间格式无效", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showTextEditDialog(entry: SubtitleEntry, position: Int) {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val editText = EditText(this).apply {
            setText(entry.text)
            setLines(3)
        }
        
        AlertDialog.Builder(this)
            .setTitle("编辑字幕文本")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                entry.text = editText.text.toString()
                
                // 直接刷新该位置
                subtitleAdapter.notifyItemChanged(position)
                markAsChanged()
                Toast.makeText(this, "已更新", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 复制选中的字幕（支持多行）
     */
    private fun copySelected() {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val selectedEntries = subtitleAdapter.getSelectedEntries()
        if (selectedEntries.isEmpty()) {
            Toast.makeText(this, "请先选择要复制的字幕", Toast.LENGTH_SHORT).show()
            return
        }
        
        clipboardEntries = selectedEntries.map { it.first.copy() }
        isCutMode = false
        Toast.makeText(this, "已复制 ${clipboardEntries.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 粘贴到选中的位置
     */
    private fun pasteToSelected() {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (clipboardEntries.isEmpty()) {
            Toast.makeText(this, "剪贴板为空，请先复制", Toast.LENGTH_SHORT).show()
            return
        }
        
        val selectedEntries = subtitleAdapter.getSelectedEntries()
        if (selectedEntries.isEmpty()) {
            Toast.makeText(this, "请先选择要粘贴到的字幕", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 检查行数是否匹配
        if (selectedEntries.size != clipboardEntries.size) {
            Toast.makeText(this, "行数不匹配：已复制 ${clipboardEntries.size} 行，当前选中 ${selectedEntries.size} 行", Toast.LENGTH_LONG).show()
            return
        }
        
        // 如果是剪切模式，先删除原字幕
        if (isCutMode) {
            performCutDelete()
        }
        
        // 按顺序一一对应粘贴
        selectedEntries.sortedBy { it.second }.forEachIndexed { index, (targetEntry, _) ->
            val sourceEntry = clipboardEntries[index]
            targetEntry.startTime = sourceEntry.startTime
            targetEntry.endTime = sourceEntry.endTime
            targetEntry.text = sourceEntry.text
        }
        
        // 刷新所有选中的位置
        selectedEntries.forEach { (_, position) ->
            subtitleAdapter.notifyItemChanged(position)
        }
        
        markAsChanged()
        Toast.makeText(this, "已粘贴 ${clipboardEntries.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    private fun markAsChanged() {
        hasUnsavedChanges = true
    }
    
    private fun newFile() {
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("当前文件有未保存的更改，确定要新建吗？")
                .setPositiveButton("确定") { _, _ ->
                    doNewFile()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            doNewFile()
        }
    }
    
    private fun doNewFile() {
        filePath = ""
        currentFile = null
        subtitleEntries.clear()
        sourceViewContent = ""
        originalFileContent = ""
        currentCharset = StandardCharsets.UTF_8
        currentFormat = SubtitleParser.SubtitleFormat.SRT
        isSourceViewMode = false
        binding.rvSubtitles.visibility = android.view.View.VISIBLE
        binding.svSourceView.visibility = android.view.View.GONE
        subtitleAdapter.submitList(emptyList())
        subtitleAdapter.clearSelection()
        updateSelectedCountDisplay()
        binding.tvFileName.text = "未命名"
        binding.tvFormatInfo.text = "格式：SRT | 条目数：0"
        hasUnsavedChanges = false
        Toast.makeText(this, "已新建文件", Toast.LENGTH_SHORT).show()
    }
    
    private fun openFile() {
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("当前文件有未保存的更改，确定要打开新文件吗？")
                .setPositiveButton("确定") { _, _ ->
                    doOpenFile()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            doOpenFile()
        }
    }
    
    private fun doOpenFile() {
        openFileLauncher.launch(arrayOf("text/*", "*/*"))
    }
    
    private fun saveFile() {
        // 确定要保存的目标文件
        val targetFile = if (isAudioFile) {
            // 音频文件模式：保存到字幕文件
            subtitleFile
        } else {
            // 普通模式：保存到当前文件
            currentFile
        }
        
        if (targetFile == null && !isSourceViewMode) {
            saveFileAs()
            return
        }
        
        try {
            val content = if (isSourceViewMode) {
                sourceViewContent
            } else {
                when (currentFormat) {
                    SubtitleParser.SubtitleFormat.SRT -> SubtitleParser.toSRT(subtitleEntries)
                    SubtitleParser.SubtitleFormat.LRC -> SubtitleParser.toLRC(subtitleEntries)
                    SubtitleParser.SubtitleFormat.TXT -> SubtitleParser.toTXT(subtitleEntries)
                    else -> SubtitleParser.toSRT(subtitleEntries)
                }
            }
            
            if (targetFile != null) {
                FileUtils.writeFile(targetFile, content, currentCharset)
            } else {
                // 对于从 URI 打开的文件，无法直接保存，需要另存为
                saveFileAs()
                return
            }
            hasUnsavedChanges = false
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun saveFileAs() {
        val formatExtension = if (isSourceViewMode) {
            "txt"
        } else {
            when (currentFormat) {
                SubtitleParser.SubtitleFormat.SRT -> "srt"
                SubtitleParser.SubtitleFormat.LRC -> "lrc"
                SubtitleParser.SubtitleFormat.TXT -> "txt"
                else -> "srt"
            }
        }
        saveFileLauncher.launch("subtitle.$formatExtension")
    }
    
    private fun saveFileToUri(uri: Uri) {
        try {
            val content = if (isSourceViewMode) {
                sourceViewContent
            } else {
                when (currentFormat) {
                    SubtitleParser.SubtitleFormat.SRT -> SubtitleParser.toSRT(subtitleEntries)
                    SubtitleParser.SubtitleFormat.LRC -> SubtitleParser.toLRC(subtitleEntries)
                    SubtitleParser.SubtitleFormat.TXT -> SubtitleParser.toTXT(subtitleEntries)
                    else -> SubtitleParser.toSRT(subtitleEntries)
                }
            }
            
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray(currentCharset))
            }
            hasUnsavedChanges = false
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showEncodingDialog() {
        val encodings = FileUtils.SUPPORTED_ENCODINGS.map { it.displayName }
        val currentIndex = FileUtils.SUPPORTED_ENCODINGS.indexOfFirst { it.charset == currentCharset }
        
        AlertDialog.Builder(this)
            .setTitle("选择编码")
            .setSingleChoiceItems(encodings.toTypedArray(), currentIndex) { dialog, which ->
                val newCharset = FileUtils.SUPPORTED_ENCODINGS[which].charset
                if (newCharset != currentCharset) {
                    currentCharset = newCharset
                    reloadFile()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showSelectRangeDialog() {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val totalCount = subtitleEntries.size
        if (totalCount == 0) {
            Toast.makeText(this, "没有字幕条目", Toast.LENGTH_SHORT).show()
            return
        }
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        
        // 起始输入
        val startLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val etStart = EditText(this).apply {
            hint = "从"
            inputType = EditorInfo.TYPE_CLASS_NUMBER
            setText("1")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvStart = TextView(this).apply {
            text = " (1-$totalCount)"
            setPadding(10, 0, 0, 0)
        }
        startLayout.addView(etStart)
        startLayout.addView(tvStart)
        
        // 结束输入
        val endLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val etEnd = EditText(this).apply {
            hint = "到"
            inputType = EditorInfo.TYPE_CLASS_NUMBER
            setText(totalCount.toString())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvEnd = TextView(this).apply {
            text = " (1-$totalCount)"
            setPadding(10, 0, 0, 0)
        }
        endLayout.addView(etEnd)
        endLayout.addView(tvEnd)
        
        layout.addView(startLayout)
        layout.addView(endLayout)
        
        AlertDialog.Builder(this)
            .setTitle("快速选择范围")
            .setMessage("输入要选择的字幕范围")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val start = etStart.text.toString().toIntOrNull() ?: 1
                val end = etEnd.text.toString().toIntOrNull() ?: totalCount
                
                // 转换为 0-based 索引
                val startIndex = (start - 1).coerceIn(0, totalCount - 1)
                val endIndex = (end - 1).coerceIn(0, totalCount - 1)
                
                // 确保 start <= end
                val actualStart = minOf(startIndex, endIndex)
                val actualEnd = maxOf(startIndex, endIndex)
                
                // 选中范围内的所有条目
                for (i in actualStart..actualEnd) {
                    subtitleAdapter.toggleSelection(i)
                }
                updateSelectedCountDisplay()
                Toast.makeText(this, "已选择第 $start 到 $end 条字幕", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 保存草稿
     */
    private fun saveDraft() {
        val content = if (isSourceViewMode) {
            sourceViewContent
        } else {
            if (subtitleEntries.isEmpty()) {
                Toast.makeText(this, "没有内容可保存", Toast.LENGTH_SHORT).show()
                return
            }
            when (currentFormat) {
                SubtitleParser.SubtitleFormat.SRT -> SubtitleParser.toSRT(subtitleEntries)
                SubtitleParser.SubtitleFormat.LRC -> SubtitleParser.toLRC(subtitleEntries)
                SubtitleParser.SubtitleFormat.TXT -> SubtitleParser.toTXT(subtitleEntries)
                else -> SubtitleParser.toSRT(subtitleEntries)
            }
        }
        
        val fileName = currentFile?.name ?: "未命名"
        val savedFileName = DraftManager.saveDraft(this, fileName, content)
        Toast.makeText(this, "草稿已保存：$savedFileName", Toast.LENGTH_LONG).show()
    }
    
    /**
     * 打开草稿箱
     */
    private fun openDrafts() {
        val intent = Intent(this, DraftsActivity::class.java)
        intent.putExtra(DraftsActivity.EXTRA_FROM_EDITOR, true)
        draftLauncher.launch(intent)
    }
    
    private fun showOffsetDialog(longClickPos: Int = -1) {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        
        // 毫秒输入
        val msLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val etMs = EditText(this).apply {
            hint = "毫秒"
            inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            setText("0")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvMs = TextView(this).apply {
            text = "毫秒"
            setPadding(20, 0, 0, 0)
        }
        msLayout.addView(etMs)
        msLayout.addView(tvMs)
        
        // 秒输入
        val secLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val etSec = EditText(this).apply {
            hint = "秒"
            inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            setText("0")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvSec = TextView(this).apply {
            text = "秒"
            setPadding(20, 0, 0, 0)
        }
        secLayout.addView(etSec)
        secLayout.addView(tvSec)
        
        // 分输入
        val minLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val etMin = EditText(this).apply {
            hint = "分"
            inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            setText("0")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvMin = TextView(this).apply {
            text = "分"
            setPadding(20, 0, 0, 0)
        }
        minLayout.addView(etMin)
        minLayout.addView(tvMin)
        
        // 小时输入
        val hourLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val etHour = EditText(this).apply {
            hint = "小时"
            inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            setText("0")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvHour = TextView(this).apply {
            text = "小时"
            setPadding(20, 0, 0, 0)
        }
        hourLayout.addView(etHour)
        hourLayout.addView(tvHour)
        
        layout.addView(msLayout)
        layout.addView(secLayout)
        layout.addView(minLayout)
        layout.addView(hourLayout)
        
        AlertDialog.Builder(this)
            .setTitle("时间偏移")
            .setMessage("输入偏移量，正数延迟，负数提前")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val ms = etMs.text.toString().toLongOrNull() ?: 0L
                val sec = etSec.text.toString().toLongOrNull() ?: 0L
                val min = etMin.text.toString().toLongOrNull() ?: 0L
                val hour = etHour.text.toString().toLongOrNull() ?: 0L
                
                val totalOffset = ms + sec * 1000 + min * 60000 + hour * 3600000
                applyOffset(totalOffset, longClickPos)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun applyOffset(offsetMs: Long, longClickPos: Int = -1) {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 需要刷新的位置列表
        val positionsToRefresh = mutableListOf<Int>()
        
        when {
            // 有长按位置，对长按的那一行应用偏移（无论是否有选中状态）
            longClickPos >= 0 && longClickPos < subtitleEntries.size -> {
                val entry = subtitleEntries[longClickPos]
                entry.startTime = (entry.startTime + offsetMs).coerceAtLeast(0)
                entry.endTime = (entry.endTime + offsetMs).coerceAtLeast(entry.startTime + 1)
                positionsToRefresh.add(longClickPos)
                
                // 刷新列表并同步选中状态（保持其他选中状态）
                subtitleAdapter.submitList(subtitleEntries.toList())
                subtitleAdapter.syncSelectionWithCurrentList()
            }
            // 没有长按位置但有选中的字幕，对选中的字幕应用偏移
            subtitleAdapter.getSelectedCount() > 0 -> {
                val selectedEntries = subtitleAdapter.getSelectedEntries()
                selectedEntries.forEach { (entry, position) ->
                    entry.startTime = (entry.startTime + offsetMs).coerceAtLeast(0)
                    entry.endTime = (entry.endTime + offsetMs).coerceAtLeast(entry.startTime + 1)
                    positionsToRefresh.add(position)
                }
                
                // 刷新列表并同步选中状态
                subtitleAdapter.submitList(subtitleEntries.toList())
                subtitleAdapter.syncSelectionWithCurrentList()
            }
            // 都没有，对所有字幕应用偏移
            else -> {
                subtitleEntries.forEachIndexed { index, entry ->
                    entry.startTime = (entry.startTime + offsetMs).coerceAtLeast(0)
                    entry.endTime = (entry.endTime + offsetMs).coerceAtLeast(entry.startTime + 1)
                    positionsToRefresh.add(index)
                }
                
                // 刷新列表
                subtitleAdapter.submitList(subtitleEntries.toList())
            }
        }
        
        markAsChanged()
        Toast.makeText(this, "已应用 ${offsetMs}ms 偏移", Toast.LENGTH_SHORT).show()
    }
    
    private fun deleteSelectedSubtitles() {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val selectedEntries = subtitleAdapter.getSelectedEntries()
        if (selectedEntries.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的字幕", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确定要删除选中的字幕吗？")
            .setPositiveButton("确定") { _, _ ->
                // 保存要删除的条目对象
                val entriesToDelete = selectedEntries.map { it.first }
                // 从后往前删除，避免索引变化
                selectedEntries.sortedByDescending { it.second }.forEach { (_, position) ->
                    subtitleEntries.removeAt(position)
                }
                renumberEntries()
                subtitleAdapter.submitList(subtitleEntries.toList())
                // 同步选中状态
                subtitleAdapter.syncSelectionWithCurrentList()
                // 从选中状态中移除被删除的条目
                entriesToDelete.forEach { entry ->
                    subtitleAdapter.removeSelectionByEntry(entry)
                }
                updateSelectedCountDisplay()
                updateFormatInfo()
                
                markAsChanged()
                Toast.makeText(this, "已删除 ${selectedEntries.size} 条字幕", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 取消所有选择的字幕
     */
    private fun cancelSelection() {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        subtitleAdapter.clearSelection()
        updateSelectedCountDisplay()
        Toast.makeText(this, "已取消选择", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 显示 AI 翻译对话框
     */
    private fun showAiTranslate() {
        if (isSourceViewMode) {
            Toast.makeText(this, "源视图模式下不支持此操作", Toast.LENGTH_SHORT).show()
            return
        }
        
        val selectedEntries = subtitleAdapter.getSelectedEntries()
        if (selectedEntries.isEmpty()) {
            Toast.makeText(this, "请先选择要翻译的字幕", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 检查 API 设置
        val settingsManager = SettingsManager.getInstance(this)
        val apiKey = settingsManager.getAiApiKey()
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "请先在设置中配置 API Key", Toast.LENGTH_LONG).show()
            return
        }
        
        val model = settingsManager.getAiModel()
        val sourceLanguage = settingsManager.getAiSourceLanguage()
        val targetLanguage = settingsManager.getAiTargetLanguage()
        
        // 显示翻译确认对话框
        val sourceLangText = if (sourceLanguage == "自动检测") "自动检测" else sourceLanguage
        AlertDialog.Builder(this)
            .setTitle("AI 翻译")
            .setMessage("将使用 $model 模型翻译选中的 ${selectedEntries.size} 条字幕\n源语言：$sourceLangText\n目标语言：$targetLanguage\n\n点击「开始翻译」继续")
            .setPositiveButton("开始翻译") { _, _ ->
                startTranslation(selectedEntries, apiKey, model, sourceLanguage, targetLanguage)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 开始翻译
     */
    private fun startTranslation(
        selectedEntries: List<Pair<SubtitleEntry, Int>>,
        apiKey: String,
        model: String,
        sourceLanguage: String,
        targetLanguage: String
    ) {
        // 显示翻译进度对话框
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("正在翻译")
            .setMessage("正在翻译第 0/${selectedEntries.size} 条...")
            .setNegativeButton("取消") { _, _ ->
                translateCancelled = true
            }
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        translateCancelled = false
        isTranslating = true
        
        val aiTranslator = AiTranslator(apiKey, model, sourceLanguage, targetLanguage)
        val textsToTranslate = selectedEntries.map { it.first.text }
        
        translateJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = aiTranslator.translateTexts(
                    texts = textsToTranslate,
                    progressCallback = { current, total ->
                        runOnUiThread {
                            progressDialog.setMessage("正在翻译第 $current/$total 条...")
                        }
                    },
                    isCancelled = { translateCancelled }
                )
                
                progressDialog.dismiss()
                isTranslating = false
                
                if (result.isSuccess) {
                    val translatedTexts = result.getOrNull() ?: emptyList()
                    showTranslationResult(selectedEntries, translatedTexts)
                } else {
                    val error = result.exceptionOrNull()
                    Toast.makeText(
                        this@EditorActivity,
                        "翻译失败：${error?.message ?: "未知错误"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                isTranslating = false
                Toast.makeText(
                    this@EditorActivity,
                    "翻译失败：${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * 显示翻译结果预览
     */
    private fun showTranslationResult(
        selectedEntries: List<Pair<SubtitleEntry, Int>>,
        translatedTexts: List<String>
    ) {
        if (translatedTexts.size != selectedEntries.size) {
            Toast.makeText(this, "翻译结果数量不匹配", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 构建预览内容
        val previewText = selectedEntries.mapIndexed { index, (entry, _) ->
            "原文本：${entry.text}\n翻译后：${translatedTexts[index]}\n"
        }.joinToString("\n")
        
        val scrollView = ScrollView(this)
        scrollView.setPadding(50, 40, 50, 10)
        scrollView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        
        val textView = TextView(this)
        textView.text = previewText
        textView.textSize = 14f
        textView.setLineSpacing(0f, 1.3f)
        
        scrollView.addView(textView)
        
        AlertDialog.Builder(this)
            .setTitle("翻译结果预览")
            .setView(scrollView as android.view.View)
            .setPositiveButton("应用") { _, _ ->
                // 应用翻译结果
                selectedEntries.forEachIndexed { index, (entry, _) ->
                    entry.text = translatedTexts[index]
                }
                // 刷新显示
                selectedEntries.forEach { (_, position) ->
                    subtitleAdapter.notifyItemChanged(position)
                }
                markAsChanged()
                Toast.makeText(this, "翻译已应用", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun renumberEntries() {
        subtitleEntries.forEachIndexed { index, entry ->
            entry.index = index + 1
        }
    }
    
    private fun updateFormatInfo() {
        val formatName = when (currentFormat) {
            SubtitleParser.SubtitleFormat.SRT -> "SRT"
            SubtitleParser.SubtitleFormat.LRC -> "LRC"
            SubtitleParser.SubtitleFormat.TXT -> "TXT"
            else -> "未知"
        }
        val countInfo = if (isSourceViewMode) {
            val lines = sourceViewContent.lines().size
            "行数：$lines"
        } else {
            "条目数：${subtitleEntries.size}"
        }
        binding.tvFormatInfo.text = "格式：$formatName | $countInfo"
        // 条目数变化时，强制刷新所有可见项的序号显示
        if (!isSourceViewMode) {
            subtitleAdapter.refreshAllItems()
        }
    }
    
    private fun getFormatName(): String {
        return when (currentFormat) {
            SubtitleParser.SubtitleFormat.SRT -> "SRT"
            SubtitleParser.SubtitleFormat.LRC -> "LRC"
            SubtitleParser.SubtitleFormat.TXT -> "TXT"
            else -> "未知"
        }
    }
    
    override fun onBackPressed() {
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("是否保存更改？")
                .setPositiveButton("保存") { _, _ ->
                    saveFile()
                    finish()
                }
                .setNegativeButton("不保存") { _, _ ->
                    finish()
                }
                .setNeutralButton("取消", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 释放 MediaPlayer
        releaseMediaPlayer()
        if (isTranslating) {
            translateCancelled = true
            translateJob?.cancel()
        }
    }
    
    // ==================== 音频播放器相关方法 ====================
    
    /**
     * 初始化 MediaPlayer
     */
    private fun initializeMediaPlayer() {
        if (!isAudioFile) return
        
        mediaPlayer = MediaPlayer()
        mediaPlayer?.setOnCompletionListener {
            isPlaying = false
            updatePlayerUI()
        }
        mediaPlayer?.setOnErrorListener { _, what, extra ->
            Toast.makeText(this, "播放错误：$what, $extra", Toast.LENGTH_SHORT).show()
            isPlaying = false
            updatePlayerUI()
            true
        }
    }
    
    /**
     * 释放 MediaPlayer
     */
    private fun releaseMediaPlayer() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.stop()
            }
            player.release()
            mediaPlayer = null
        }
    }
    
    /**
     * 设置音频播放器 UI
     */
    private fun setupAudioPlayer() {
        if (!isAudioFile) {
            binding.audioPlayerContainer.visibility = android.view.View.GONE
            return
        }
        
        binding.audioPlayerContainer.visibility = android.view.View.VISIBLE
        
        // 设置音频文件名
        currentFile?.let {
            binding.tvAudioFileName.text = it.name
        }
        
        // 生成模拟波形图
        binding.waveformView.generateMockWaveform(200)
        
        // 设置波形图点击监听器
        binding.waveformView.onWaveformClickListener = { position ->
            // 点击波形图跳转到对应时间
            val targetTime = (audioDuration * position).toLong()
            seekTo(targetTime)
        }
        
        // 播放/暂停按钮
        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }
        
        // 进度条拖动 - SeekBar max 为 1000，代表 0-100% 的进度
        binding.seekBar.max = 1000
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // progress 范围是 0-1000，直接按比例计算时间
                    val targetTime = (audioDuration * progress / 1000).toLong()
                    audioCurrentPosition = targetTime
                    binding.tvCurrentTime.text = TimeUtils.formatForDisplay(targetTime)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekTo(audioCurrentPosition)
            }
        })
        
        // 缩放控制
        binding.btnZoomIn.setOnClickListener {
            val currentZoom = binding.waveformView.getZoomLevel()
            binding.waveformView.setZoomLevel(currentZoom + 1f)
            binding.zoomSeekBar.progress = (binding.waveformView.getZoomLevel() * 10).toInt()
        }
        
        binding.btnZoomOut.setOnClickListener {
            val currentZoom = binding.waveformView.getZoomLevel()
            binding.waveformView.setZoomLevel(currentZoom - 1f)
            binding.zoomSeekBar.progress = (binding.waveformView.getZoomLevel() * 10).toInt()
        }
        
        binding.zoomSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val zoom = progress / 10f
                    binding.waveformView.setZoomLevel(zoom)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        // 初始化播放器状态
        updatePlayerUI()
    }
    
    /**
     * 加载音频文件
     */
    private fun loadAudioFile(subtitleFilePath: String?) {
        if (filePath.isEmpty() || currentFile == null) {
            Toast.makeText(this, "音频文件路径无效", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        if (!currentFile!!.exists()) {
            Toast.makeText(this, "音频文件不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        audioFilePath = filePath
        binding.tvFileName.text = subtitleFilePath?.let { File(it).name } ?: "（无字幕文件）"
        
        // 设置 MediaPlayer 数据源
        try {
            mediaPlayer?.reset()
            mediaPlayer?.setDataSource(currentFile?.absolutePath)
            mediaPlayer?.prepare()
            audioDuration = mediaPlayer?.duration?.toLong() ?: 0L
        } catch (e: Exception) {
            Toast.makeText(this, "加载音频失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
        
        // 更新波形图
        binding.waveformView.generateMockWaveform(200)
        
        // 加载字幕文件（如果有）
        if (subtitleFilePath != null) {
            val subtitleFile = File(subtitleFilePath)
            if (subtitleFile.exists()) {
                loadSubtitleFile(subtitleFile)
            } else {
                // 没有字幕文件，显示空列表
                subtitleEntries.clear()
                subtitleAdapter.submitList(emptyList())
                updateFormatInfo()
                Toast.makeText(this, "未找到同名字幕文件", Toast.LENGTH_SHORT).show()
            }
        } else {
            // 没有指定字幕文件，显示空列表
            subtitleEntries.clear()
            subtitleAdapter.submitList(emptyList())
            updateFormatInfo()
        }
        
        updatePlayerUI()
    }
    
    /**
     * 加载字幕文件
     */
    private fun loadSubtitleFile(subtitleFile: File) {
        val settingsManager = SettingsManager.getInstance(this)
        currentCharset = settingsManager.getDefaultEncoding()
        
        try {
            val content = FileUtils.readFile(subtitleFile, currentCharset)
            parseContent(content)
            hasUnsavedChanges = false
        } catch (e: Exception) {
            Toast.makeText(this, "读取字幕文件失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 切换播放/暂停状态
     */
    private fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
            } else {
                player.start()
                isPlaying = true
                // 开始定时更新 UI
                startProgressUpdate()
            }
            updatePlayerUI()
        }
    }
    
    /**
     * 跳转到指定时间
     */
    private fun seekTo(timeMs: Long) {
        mediaPlayer?.seekTo(timeMs.toInt())
        audioCurrentPosition = timeMs.coerceIn(0L, audioDuration)
        
        // 高亮显示对应时间的字幕
        highlightSubtitleAtTime(audioCurrentPosition)
        
        // 如果之前在播放，继续播放
        if (isPlaying) {
            startProgressUpdate()
        }
    }
    
    /**
     * 高亮显示指定时间的字幕
     * 注意：只高亮显示，不自动滚动，以免干扰用户编辑
     */
    private fun highlightSubtitleAtTime(timeMs: Long) {
        if (isSourceViewMode) return
        
        // 查找包含当前时间的字幕
        for ((index, entry) in subtitleEntries.withIndex()) {
            if (timeMs >= entry.startTime && timeMs <= entry.endTime) {
                // 只高亮显示，不自动滚动，允许用户自由浏览
                subtitleAdapter.highlightCurrentPlaying(index)
                break
            }
        }
    }
    
    /**
     * 更新播放器 UI
     */
    private fun updatePlayerUI() {
        // 从 MediaPlayer 获取实时状态
        mediaPlayer?.let { player ->
            audioCurrentPosition = player.currentPosition.toLong()
            audioDuration = player.duration.toLong().takeIf { it > 0 } ?: audioDuration
            isPlaying = player.isPlaying
        }
        
        // 更新播放/暂停按钮图标
        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        binding.btnPlayPause.setImageResource(playPauseIcon)
        
        // 更新时间显示
        binding.tvCurrentTime.text = TimeUtils.formatForDisplay(audioCurrentPosition)
        binding.tvTotalTime.text = TimeUtils.formatForDisplay(audioDuration)
        
        // 更新进度条 - SeekBar max 为 1000，代表 0-100% 的进度
        val progress = if (audioDuration > 0) {
            (audioCurrentPosition * 1000 / audioDuration).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        binding.seekBar.progress = progress
        
        // 更新波形图播放位置
        val wavePosition = if (audioDuration > 0) {
            audioCurrentPosition.toFloat() / audioDuration
        } else {
            0f
        }
        binding.waveformView.setCurrentPosition(wavePosition)
    }
    
    /**
     * 开始定时更新播放进度
     */
    private fun startProgressUpdate() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isPlaying && mediaPlayer?.isPlaying == true) {
                    updatePlayerUI()
                    // 检查是否需要高亮字幕
                    highlightSubtitleAtTime(audioCurrentPosition)
                    handler.postDelayed(this, 100)
                }
            }
        }
        handler.post(updateRunnable)
    }
    
    // ==================== 字幕时间控制按钮方法 ====================
    
    /**
     * 跳转到字幕的开始时间
     */
    private fun jumpToSubtitleTime(entry: SubtitleEntry) {
        if (!isAudioFile) {
            Toast.makeText(this, "此功能仅在打开音频文件时可用", Toast.LENGTH_SHORT).show()
            return
        }
        
        seekTo(entry.startTime)
        Toast.makeText(this, "已跳转到 ${TimeUtils.formatForDisplay(entry.startTime)}", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 将字幕的开始时间设置为当前音频进度
     */
    private fun setSubtitleTimeToCurrentPosition(entry: SubtitleEntry, position: Int) {
        if (!isAudioFile) {
            Toast.makeText(this, "此功能仅在打开音频文件时可用", Toast.LENGTH_SHORT).show()
            return
        }
        
        val newStartTime = audioCurrentPosition
        entry.startTime = newStartTime
        
        // 刷新该条目显示
        subtitleAdapter.notifyItemChanged(position)
        markAsChanged()
        
        if (newStartTime >= entry.endTime) {
            Toast.makeText(this, "开始时间已设置，但大于结束时间，请调整结束时间", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "已将开始时间设置为 ${TimeUtils.formatForDisplay(newStartTime)}", Toast.LENGTH_SHORT).show()
        }
    }
}
