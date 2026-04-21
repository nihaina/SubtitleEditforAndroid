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
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.subtitleedit.view.DraggableScrollView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import android.media.MediaPlayer
import com.subtitleedit.adapter.SubtitleAdapter
import com.subtitleedit.databinding.ActivityEditorBinding
import com.subtitleedit.util.AiTranslator
import com.subtitleedit.view.WaveformTimelineView
import com.subtitleedit.util.DraftManager
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SearchReplaceEngine
import com.subtitleedit.util.SearchReplaceOps
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
import com.subtitleedit.audio.FfmpegWaveformChunkLoader
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope

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
    
    // 当前格式信息（用于 toolbar subtitle 恢复）
    private var currentFormatInfo = ""
    
    // 复制/剪贴板数据（支持多行）
    private var clipboardEntries: List<SubtitleEntry> = emptyList()
    private var isCutMode: Boolean = false  // 是否为剪切模式
    
    // 翻译相关
    private var translateJob: Job? = null
    private var isTranslating = false
    private var translateCancelled = false
    
    // 搜索相关
    private val searchEngine = SearchReplaceEngine()
    
    // 音频文件相关
    private var isAudioFile: Boolean = false
    private var audioFilePath: String = ""
    private var audioDuration: Long = 0L  // 音频总时长（毫秒）
    private var audioCurrentPosition: Long = 0L  // 当前播放位置（毫秒）
    private var isPlaying: Boolean = false
    private var isUserSeeking = false
    
    // 播放速率（默认 1.0）
    private var playbackSpeed: Float = 1.0f
    
    // MediaPlayer
    private var mediaPlayer: MediaPlayer? = null
    
    // FFmpeg 波形加载器
    private var ffmpegChunkLoader: FfmpegWaveformChunkLoader? = null
    
    // 临时修复的 WAV 文件（start time 不为 0 时生成）
    private var tempFixedWavFile: File? = null
    
    // 波形图展开状态（提升为 class 级别，避免 setupAudioPlayer 重复调用时状态丢失）
    private var isWaveformExpanded = true

    // 频谱图
    private var currentDisplayMode = WaveformTimelineView.DisplayMode.WAVEFORM
    private var spectrogramFile: File? = null

    // 频谱图生成进度追踪
    private var spectrogramTotalChunks = 0
    private var spectrogramDoneChunks = 0
    private var spectrogramIsGenerating = false

    // 手动生成状态：false = 未生成/未触发，true = 已触发或缓存已就绪
    private var isWaveformGenerated = false
    private var isSpectrogramGenerationStarted = false
    
    // 【新增】标记波形图是否正在后台生成中，防止状态丢失和重复生成
    private var isWaveformGenerating = false

    // 选中字幕循环播放
    private var loopSubtitleEntry: SubtitleEntry? = null  // 当前循环目标

    // ==================== 播放进度更新相关（类成员变量，避免循环叠加）====================
    // Handler 和 Runnable 提升为类成员，配合 16ms 更新频率实现 60fps
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    updatePlayerUI()
                    highlightSubtitleAtTime(audioCurrentPosition)

                    // 选中字幕循环播放：检测双边界
                    val loopTarget = loopSubtitleEntry
                    if (loopTarget != null &&
                        SettingsManager.getInstance(this@EditorActivity).isLoopSelectedSubtitleEnabled()
                    ) {
                        when {
                            // 超过右边界 → 跳回开始
                            audioCurrentPosition >= loopTarget.endTime -> {
                                player.seekTo(loopTarget.startTime.toInt())
                                audioCurrentPosition = loopTarget.startTime
                                updatePlayerUI()
                            }
                            // 在左边界之前（用户拖进度条拖到外面）→ 跳到开始
                            audioCurrentPosition < loopTarget.startTime -> {
                                player.seekTo(loopTarget.startTime.toInt())
                                audioCurrentPosition = loopTarget.startTime
                                updatePlayerUI()
                            }
                        }
                    }

                    // 4ms 递归，实现 240fps，适配高刷新率屏幕
                    progressHandler.postDelayed(this, 4)
                }
            }
        }
    }

    /**
     * 开始定时更新播放进度（16ms 间隔，约 60fps）
     * 关键：先移除已有的回调，确保永远只有一个循环在运行
     */
    private fun startProgressUpdate() {
        // 先移除已有的，确保永远只有一个循环在运行
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    /**
     * 停止定时更新播放进度
     * 必须在以下生命周期/状态切换处调用：
     * 1. onPause()
     * 2. 播放完成回调 OnCompletionListener
     * 3. 停止播放按钮点击时
     * 4. onDestroy() / release()
     */
    private fun stopProgressUpdate() {
        progressHandler.removeCallbacks(progressRunnable)
    }

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
        setupBackPressedHandler()
        
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
        supportActionBar?.title = "未命名"
        
        binding.toolbar.setNavigationOnClickListener {
            handleBackPressed()
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
                // 同步循环目标
                val selected = subtitleAdapter.getSelectedEntries()
                loopSubtitleEntry = if (selected.isNotEmpty()) selected.first().first else null
            },
            onItemLongClick = { _, position ->
                showContextMenu(position)
            },
            onTimeClick = { entry, position, isStartTime ->
                showTimeEditDialog(entry, position, isStartTime)
            },
            onTextClick = { entry, position ->
                showTextEditDialog(entry, position)
            },
            onJumpToTimeClick = { entry, _ ->
                jumpToSubtitleTime(entry)
            },
            onSetTimeClick = { entry, position ->
                setSubtitleTimeToCurrentPosition(entry, position)
            },
            isAudioFile = isAudioFile,
            onSelectionChanged = {
                updateSelectedCountDisplay()
            }
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
                if (query.isNotEmpty() && searchEngine.setQueryIfChanged(query)) {
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
        searchEngine.clearResults()
        // 显示软键盘
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    
    /**
     * 隐藏搜索条
     */
    private fun hideSearchBar() {
        binding.searchBar.visibility = android.view.View.GONE
        searchEngine.clearAll()
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
        if (!searchEngine.hasSearchContext()) {
            showShortToast("请先搜索内容")
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
        val position = searchEngine.currentResultPositionOrNull()
        if (position == null) {
            showShortToast("没有可替换的匹配项")
            return
        }

        val newContent = SearchReplaceOps.replaceInContentAt(
            content = content,
            start = position,
            queryLength = searchEngine.query.length,
            replacement = replaceText
        )
        if (newContent == null) {
            showShortToast("没有可替换的匹配项")
            return
        }
        binding.etSourceView.setText(newContent)
        sourceViewContent = newContent
        hasUnsavedChanges = true
        
        // 重新搜索以更新结果
        searchInSourceView()
        showShortToast("已替换 1 处")
    }
    
    /**
     * 在列表中替换一个匹配项
     */
    private fun replaceOneInRecyclerView(replaceText: String) {
        val position = searchEngine.currentResultPositionOrNull()
        if (position == null) {
            showShortToast("没有可替换的匹配项")
            return
        }

        val entry = subtitleEntries[position]
        val newText = SearchReplaceOps.replaceTextIfChanged(
            originalText = entry.text,
            query = searchEngine.query,
            replacement = replaceText
        )
        
        if (newText != null) {
            entry.text = newText
            subtitleAdapter.notifyItemChanged(position)
            hasUnsavedChanges = true
            
            // 重新搜索以更新结果
            searchInRecyclerView()
            showShortToast("已替换 1 处")
        } else {
            showShortToast("当前项无可替换内容")
            // 跳转到下一个
            goToNextResult()
        }
    }
    
    /**
     * 全部替换
     */
    private fun replaceAll() {
        val replaceText = binding.etReplace.text?.toString() ?: ""
        if (searchEngine.query.isEmpty()) {
            showShortToast("请先搜索内容")
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
        val result = SearchReplaceOps.replaceAllInContent(
            content = content,
            query = searchEngine.query,
            replacement = replaceText
        )
        
        if (result.matchCount > 0) {
            showReplaceAllConfirm(result.matchCount) {
                    binding.etSourceView.setText(result.newContent)
                    sourceViewContent = result.newContent
                    hasUnsavedChanges = true
                    clearSearchStateAfterReplace()
                    showShortToast("已替换 ${result.matchCount} 处")
            }
        } else {
            showShortToast("没有找到可替换的内容")
        }
    }
    
    /**
     * 在列表中全部替换
     */
    private fun replaceAllInRecyclerView(replaceText: String) {
        val query = searchEngine.query
        val updates = SearchReplaceOps.collectTextUpdates(
            texts = subtitleEntries.map { it.text },
            query = query,
            replacement = replaceText
        )
        
        if (updates.isNotEmpty()) {
            showReplaceAllConfirm(updates.size) {
                    updates.forEach { update ->
                        subtitleEntries[update.index].text = update.newText
                    }
                    updates.forEach { update ->
                        val position = update.index
                        subtitleAdapter.notifyItemChanged(position)
                    }
                    hasUnsavedChanges = true
                    clearSearchStateAfterReplace()
                    showShortToast("已替换 ${updates.size} 处")
            }
        } else {
            showShortToast("没有找到可替换的内容")
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
        if (searchEngine.query.isEmpty() || content.isEmpty()) {
            searchEngine.clearResults()
            // 清除高亮
            val spannable = SpannableString(content)
            binding.etSourceView.setText(spannable, TextView.BufferType.EDITABLE)
            return
        }

        searchEngine.setResults(searchEngine.findMatchesInText(content))
        updateSearchResultDisplay()
        highlightSearchInSourceView()
    }
    
    /**
     * 在源视图中高亮搜索结果
     */
    private fun highlightSearchInSourceView() {
        val content = binding.etSourceView.text?.toString() ?: ""
        val query = searchEngine.query
        val results = searchEngine.results
        if (query.isEmpty() || results.isEmpty()) return
        
        val spannable = SpannableString(content)
        val highlightColor = ContextCompat.getColor(this, R.color.inverse_primary)
        
        // 高亮所有搜索结果
        results.forEach { startIndex ->
            val endIndex = (startIndex + query.length).coerceAtMost(content.length)
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
        if (searchEngine.currentIndex in results.indices) {
            val currentIndex = results[searchEngine.currentIndex]
            val endIndex = (currentIndex + query.length).coerceAtMost(content.length)
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
        if (searchEngine.currentIndex in results.indices) {
            val position = results[searchEngine.currentIndex]
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
        val query = searchEngine.query
        if (query.isEmpty()) {
            searchEngine.clearResults()
            return
        }
        
        val results = subtitleEntries.mapIndexedNotNull { index, entry ->
            // 搜索文本内容
            if (entry.text.contains(query, ignoreCase = true)) {
                index
            } else {
                // 搜索时间（格式化为字符串后搜索）
                val timeStr = TimeUtils.formatForDisplay(entry.startTime)
                if (timeStr.contains(query, ignoreCase = true)) {
                    index
                } else {
                    null
                }
            }
        }

        searchEngine.setResults(results)
        updateSearchResultDisplay()
        
        // 跳转到第一个结果
        if (results.isNotEmpty()) {
            scrollToSearchResult(0)
        }
    }
    
    /**
     * 更新搜索结果显示
     */
    private fun updateSearchResultDisplay() {
        if (searchEngine.results.isEmpty()) {
            Toast.makeText(this, getString(R.string.search_no_results), Toast.LENGTH_SHORT).show()
        } else {
            val message = getString(R.string.search_result_count, searchEngine.results.size, searchEngine.currentIndex + 1)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 滚动到搜索结果位置
     */
    private fun scrollToSearchResult(index: Int) {
        if (index !in searchEngine.results.indices) return

        val position = searchEngine.results[index]
        if (isSourceViewMode) {
            // 源视图模式：重新高亮并滚动
            highlightSearchInSourceView()
        } else {
            // 列表模式：滚动 RecyclerView
            binding.rvSubtitles.scrollToPosition(position)
            // 高亮显示（通过 adapter）
            subtitleAdapter.highlightSearchResult(position, searchEngine.query)
        }
    }
    
    /**
     * 跳转到上一个搜索结果
     */
    private fun goToPreviousResult() {
        val index = searchEngine.moveToPrevious() ?: return
        updateSearchResultDisplay()
        scrollToSearchResult(index)
    }
    
    /**
     * 跳转到下一个搜索结果
     */
    private fun goToNextResult() {
        val index = searchEngine.moveToNext() ?: return
        updateSearchResultDisplay()
        scrollToSearchResult(index)
    }
    
    private fun updateSelectedCountDisplay() {
        val count = subtitleAdapter.getSelectedCount()
        if (count > 0) {
            supportActionBar?.subtitle = "$currentFormatInfo | 选中：$count"
        } else {
            supportActionBar?.subtitle = currentFormatInfo
        }
    }
    
    private fun loadFile() {
        if (filePath.isEmpty() || currentFile == null) {
            finishWithToast("文件路径无效")
            return
        }

        val file = currentFile ?: run {
            finishWithToast("文件路径无效")
            return
        }

        if (!file.exists()) {
            finishWithToast("文件不存在")
            return
        }

        supportActionBar?.title = file.name
        // 使用用户设置的默认编码
        val settingsManager = SettingsManager.getInstance(this)
        currentCharset = settingsManager.getDefaultEncoding()

        val content = readFileOrNull(file, "读取文件失败") ?: return
        parseContent(content)
        hasUnsavedChanges = false
    }
    
    private fun openFileFromUri(uri: Uri) {
        try {
            val content = FileUtils.readUri(this, uri)
            currentFile = null
            // 获取文件名并更新显示
            val fileName = getFileNameFromUri(uri)
            supportActionBar?.title = fileName
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
        val targetFile = if (isAudioFile) subtitleFile else currentFile
        if (targetFile == null || !targetFile.exists()) {
            showShortToast("当前文件无法重新加载编码，请通过「打开」功能重新选择文件")
            return
        }

        val content = readFileOrNull(targetFile, "切换编码失败") ?: return
        parseContent(content)
        hasUnsavedChanges = false
        showShortToast("已切换编码为：${FileUtils.SUPPORTED_ENCODINGS.find { it.charset == currentCharset }?.displayName}")
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
        
        // 同步字幕到波形视图（仅音频模式有效）
        syncWaveformSubtitles()
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
            showShortToast("TXT 文件只能使用源视图模式")
            return
        }

        if (isSourceViewMode) {
            // 源视图 → 列表视图：直接切换，解析源视图当前内容
            doExitSourceView()
        } else {
            // 列表视图 → 源视图：需要重新读取文件
            doEnterSourceView()
        }
    }

    /**
     * 列表视图 → 源视图
     * 重新从磁盘读取文件内容，有未保存更改时提醒先保存
     */
    private fun doEnterSourceView() {
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("有未保存的更改")
                .setMessage("切换到源视图将重新读取文件，当前列表中未保存的更改不会体现在源视图中。\n\n建议先保存后再切换。")
                .setPositiveButton("先保存再切换") { _, _ ->
                    saveFile()
                    reloadAndEnterSourceView()
                }
                .setNeutralButton("直接切换（丢弃更改）") { _, _ ->
                    reloadAndEnterSourceView()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            reloadAndEnterSourceView()
        }
    }

    /**
     * 重新从磁盘读取原始文件后进入源视图
     */
    private fun reloadAndEnterSourceView() {
        val file = getCurrentSubtitleFile()

        if (file != null && file.exists()) {
            // 有文件：重新读取磁盘内容，保证与已保存状态一致
            val freshContent = readFileOrNull(file, "读取文件失败") ?: return
            originalFileContent = freshContent
            sourceViewContent = freshContent
        } else {
            // 无文件（从剪贴板/URI 打开，或新建未保存）：退而序列化当前列表
            sourceViewContent = serializeEntriesForFormat(currentFormat)
            originalFileContent = sourceViewContent
            showShortToast("文件尚未保存，已从当前列表生成源视图内容")
        }

        enterSourceViewMode()
        showShortToast("已切换到源视图")
    }

    /**
     * 源视图 → 列表视图：解析源视图中当前编辑的内容
     */
    private fun doExitSourceView() {
        val editedContent = binding.etSourceView.text.toString()
        try {
            subtitleEntries = SubtitleParser.parse(editedContent, currentCharset).toMutableList()
            // 将源视图内容同步回 originalFileContent，使再次切换时内容一致
            originalFileContent = editedContent
            sourceViewContent   = editedContent
            // 标记有未保存更改（用户在源视图里编辑了内容）
            if (isSourceContentModifiedComparedToFile(editedContent)) {
                hasUnsavedChanges = true
            }
            exitSourceViewMode()
            updateFormatInfo()
            showShortToast("已切换到列表视图")
        } catch (e: Exception) {
            showShortToast("解析失败：${e.message}")
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
        if (!ensureListMode()) return
        
        // 保存长按位置
        longClickPosition = position
        
        val selectedCount = subtitleAdapter.getSelectedCount()
        val hasSelection = selectedCount > 0
        val hasClipboard = clipboardEntries.isNotEmpty()
        
        // 构建菜单项列表
        val itemsList = mutableListOf<String>()
        
        // 如果有选中项，添加"勾选字幕操作"选项
        if (hasSelection) {
            itemsList.add("对勾选字幕操作 (${selectedCount}项)")
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
                    showSelectionContextMenu(hasClipboard)
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
                            ensureClipboardNotEmpty()
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
    private fun showSelectionContextMenu(hasClipboard: Boolean) {
        if (!ensureListMode()) return
        
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
                        ensureClipboardNotEmpty()
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
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要剪切的字幕") ?: return
        
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
    
    /**
     * 执行剪切删除操作（在粘贴后调用）
     */
    private fun performCutDelete() {
        if (!isCutMode) return
        
        if (cutPositionValue >= 0) {
            // 单行剪切
            if (cutPositionValue < subtitleEntries.size) {
                subtitleEntries.removeAt(cutPositionValue)
                renumberEntries()
                syncAfterDelete(setOf(cutPositionValue))
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
            syncAfterDelete(cutPositionsValue.toSet())
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
        if (!ensureListMode()) return
        
        if (!ensureClipboardNotEmpty()) return
        
        if (position >= 0 && position < subtitleEntries.size) {
            // 如果是剪切模式，先删除原字幕
            if (isCutMode) {
                performCutDelete()
            }
            
            if (clipboardEntries.size == 1) {
                // 单行粘贴，直接替换
                val targetEntry = subtitleEntries[position]
                val sourceEntry = clipboardEntries[0]
                copyEntryContent(sourceEntry, targetEntry)
                notifyPositionsWithNeighbors(listOf(position))
                // 同步字幕到波形视图
                syncWaveformSubtitles()
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
                copyEntryContent(sourceEntry, targetEntry)
            }
            val affectedPositions = List(clipboardEntries.size) { index -> position + index }
            notifyPositionsWithNeighbors(affectedPositions)
            // 同步字幕到波形视图
            syncWaveformSubtitles()
        } else {
            // 剪贴板行数大于剩余行数，替换后插入多余行
            for (i in 0 until remainingRows) {
                val targetEntry = subtitleEntries[position + i]
                val sourceEntry = clipboardEntries[i]
                copyEntryContent(sourceEntry, targetEntry)
            }
            // 插入多余的行
            for (i in remainingRows until clipboardEntries.size) {
                val newEntry = clipboardEntries[i].copy()
                subtitleEntries.add(position + remainingRows, newEntry)
            }
            renumberEntries()
            subtitleAdapter.submitList(subtitleEntries.toList())
            // 同步字幕到波形视图
            syncWaveformSubtitles()
        }

        markAsChanged()
        Toast.makeText(this, "已粘贴 ${clipboardEntries.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 删除单个字幕（长按的字幕）
     */
    private fun deleteSingleSubtitle(position: Int) {
        if (!ensureListMode()) return
        
        if (position >= 0 && position < subtitleEntries.size) {
            showDeleteConfirm("确定要删除此字幕吗？") {
                    subtitleEntries.removeAt(position)
                    renumberEntries()
                    syncAfterDelete(setOf(position))
                    markAsChanged()
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 插入字幕到指定位置
     */
    private fun insertSubtitle(after: Boolean, refPosition: Int) {
        if (!ensureListMode()) return

        val insertPosition = if (after) refPosition + 1 else refPosition

        val newEntry = SubtitleEntry()
        newEntry.index = insertPosition + 1

        if (subtitleEntries.isNotEmpty() && refPosition < subtitleEntries.size) {
            val refEntry = subtitleEntries[refPosition]
            if (after) {
                // 向后插入：新字幕的开始时间 = 参考字幕的结束时间
                newEntry.startTime = refEntry.endTime
                newEntry.endTime = newEntry.startTime + 3000
            } else {
                // 向前插入：新字幕的结束时间 = 参考字幕的开始时间
                newEntry.endTime = refEntry.startTime
                newEntry.startTime = maxOf(0L, newEntry.endTime - 3000)
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

        // 同步字幕到波形视图，保持选中状态
        if (isAudioFile) {
            binding.waveformTimelineView.setSubtitlesKeepSelection(subtitleEntries.toList(), insertPosition)
        }

        markAsChanged()
        Toast.makeText(this, "已插入新字幕", Toast.LENGTH_SHORT).show()
    }

    private fun insertSubtitleFromTimestamp(startMs: Long, endMs: Long) {
        val realStart = minOf(startMs, endMs)
        val realEnd = maxOf(startMs, endMs)
        if (realEnd - realStart < 100) return

        val newEntry = SubtitleEntry().apply {
            this.startTime = realStart
            this.endTime = realEnd
            this.text = "新字幕"
        }
        val insertPos = subtitleEntries.indexOfFirst { it.startTime > realStart }
            .let { if (it == -1) subtitleEntries.size else it }

        subtitleEntries.add(insertPos, newEntry)
        renumberEntries()
        subtitleAdapter.submitList(subtitleEntries.toList())

        // 同步字幕到波形视图，保持选中状态
        if (isAudioFile) {
            binding.waveformTimelineView.setSubtitlesKeepSelection(subtitleEntries.toList(), insertPos)
        }

        markAsChanged()
        Toast.makeText(this, "已插入新字幕", Toast.LENGTH_SHORT).show()
    }

    /**
     * 显示针对选中字幕的时间偏移对话框
     */
    private fun showOffsetDialogForSelection() {
        if (!ensureListMode()) return
        showOffsetInputDialog("时间偏移 (只对勾选字幕)") { totalOffset ->
            applyOffsetToSelection(totalOffset)
        }
    }
    
    /**
     * 对选中的字幕应用时间偏移
     */
    private fun applyOffsetToSelection(offsetMs: Long) {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("没有选中的字幕") ?: return
        
        // 保存选中的条目对象（用于同步选中状态）
        val selectedEntryObjects = selectedEntries.map { it.first }.toSet()
        
        // 应用时间偏移
        selectedEntryObjects.forEach { entry ->
            applyOffsetToEntry(entry, offsetMs)
        }
        
        // 刷新列表并同步选中状态
        subtitleAdapter.submitList(subtitleEntries.toList())
        subtitleAdapter.syncSelectionWithCurrentList()
        
        markAsChanged()
        showShortToast("已对选中项应用 ${offsetMs}ms 偏移")
    }
    
    private fun showTimeEditDialog(entry: SubtitleEntry, position: Int, isStartTime: Boolean) {
        if (!ensureListMode()) return
        
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
                        // 用户修改了结束时间，设置标记
                        entry.endTimeModified = true
                    }
                    
                    onEntryUpdated(position)
                } else {
                    showShortToast("时间格式无效")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showTextEditDialog(entry: SubtitleEntry, position: Int) {
        if (!ensureListMode()) return
        
        val editText = EditText(this).apply {
            setText(entry.text)
            setLines(3)
        }
        
        AlertDialog.Builder(this)
            .setTitle("编辑字幕文本")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                entry.text = editText.text.toString()
                onEntryUpdated(position)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 复制选中的字幕（支持多行）
     */
    private fun copySelected() {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要复制的字幕") ?: return
        
        clipboardEntries = selectedEntries.map { it.first.copy() }
        isCutMode = false
        Toast.makeText(this, "已复制 ${clipboardEntries.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 粘贴到选中的位置
     */
    private fun pasteToSelected() {
        if (!ensureListMode()) return
        
        if (!ensureClipboardNotEmpty()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要粘贴到的字幕") ?: return
        
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
            copyEntryContent(sourceEntry, targetEntry)
        }

        notifyPositionsWithNeighbors(selectedEntries.map { it.second })

        // 同步字幕到波形视图
        syncWaveformSubtitles()

        markAsChanged()
        Toast.makeText(this, "已粘贴 ${clipboardEntries.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    private fun markAsChanged() {
        hasUnsavedChanges = true
    }

    private fun copyEntryContent(source: SubtitleEntry, target: SubtitleEntry) {
        target.startTime = source.startTime
        target.endTime = source.endTime
        target.text = source.text
    }

    private fun applyOffsetToEntry(entry: SubtitleEntry, offsetMs: Long) {
        entry.startTime = (entry.startTime + offsetMs).coerceAtLeast(0)
        entry.endTime = (entry.endTime + offsetMs).coerceAtLeast(entry.startTime + 1)
    }

    private fun onEntryUpdated(position: Int, message: String = "已更新") {
        subtitleAdapter.notifyItemChanged(position)
        markAsChanged()
        showShortToast(message)
    }

    private fun notifyPositionsWithNeighbors(positions: List<Int>) {
        if (positions.isEmpty()) return
        val allAffected = mutableSetOf<Int>()
        positions.forEach { pos ->
            if (pos in subtitleEntries.indices) {
                allAffected.add(pos)
            }
            val prev = pos - 1
            if (prev in subtitleEntries.indices) {
                allAffected.add(prev)
            }
            val next = pos + 1
            if (next in subtitleEntries.indices) {
                allAffected.add(next)
            }
        }
        allAffected.sorted().forEach { subtitleAdapter.notifyItemChanged(it) }
    }

    private fun syncWaveformSubtitles() {
        if (!isAudioFile) return
        binding.waveformTimelineView.setSubtitles(subtitleEntries.toList())
    }
    
    private fun newFile() {
        runAfterUnsavedChangesConfirmed(
            message = "当前文件有未保存的更改，确定要新建吗？",
            action = ::doNewFile
        )
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
        supportActionBar?.title = "未命名"
        currentFormatInfo = "格式：SRT | 条目数：0"
        supportActionBar?.subtitle = currentFormatInfo
        hasUnsavedChanges = false
        Toast.makeText(this, "已新建文件", Toast.LENGTH_SHORT).show()
    }
    
    private fun openFile() {
        runAfterUnsavedChangesConfirmed(
            message = "当前文件有未保存的更改，确定要打开新文件吗？",
            action = ::doOpenFile
        )
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
        
        if (targetFile == null) {
            saveFileAs()
            return
        }

        saveWithContent { content ->
            FileUtils.writeFile(targetFile, content, currentCharset)
        }
    }
    
    private fun saveFileAs() {
        val formatExtension = if (isSourceViewMode) "txt" else getFormatExtension(currentFormat)
        saveFileLauncher.launch("subtitle.$formatExtension")
    }
    
    private fun saveFileToUri(uri: Uri) {
        saveWithContent { content ->
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray(currentCharset))
            }
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
        if (!ensureListMode()) return
        
        val totalCount = subtitleEntries.size
        if (totalCount == 0) {
            showShortToast("没有字幕条目")
            return
        }
        
        val layout = createDialogInputContainer()
        val rangeLabel = " (1-$totalCount)"

        // 起始输入
        val (startLayout, etStart) = createLabeledNumberInputRow(
            hint = "从",
            label = rangeLabel,
            defaultValue = "1",
            allowSigned = false,
            labelPaddingStart = 10
        )
        
        // 结束输入
        val (endLayout, etEnd) = createLabeledNumberInputRow(
            hint = "到",
            label = rangeLabel,
            defaultValue = totalCount.toString(),
            allowSigned = false,
            labelPaddingStart = 10
        )
        
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
                showShortToast("已选择第 $start 到 $end 条字幕")
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 保存草稿
     */
    private fun saveDraft() {
        val content = getCurrentEditableContent(requireNonEmptyList = true) ?: return
        
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
        if (!ensureListMode()) return
        showOffsetInputDialog("时间偏移") { totalOffset ->
            applyOffset(totalOffset, longClickPos)
        }
    }

    private fun showOffsetInputDialog(
        title: String,
        onConfirm: (offsetMs: Long) -> Unit
    ) {
        val layout = createDialogInputContainer()
        val (msRow, etMs) = createLabeledNumberInputRow("毫秒", "毫秒", "0", allowSigned = true)
        val (secRow, etSec) = createLabeledNumberInputRow("秒", "秒", "0", allowSigned = true)
        val (minRow, etMin) = createLabeledNumberInputRow("分", "分", "0", allowSigned = true)
        val (hourRow, etHour) = createLabeledNumberInputRow("小时", "小时", "0", allowSigned = true)

        layout.addView(msRow)
        layout.addView(secRow)
        layout.addView(minRow)
        layout.addView(hourRow)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("输入偏移量，正数延迟，负数提前")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val ms = etMs.text.toString().toLongOrNull() ?: 0L
                val sec = etSec.text.toString().toLongOrNull() ?: 0L
                val min = etMin.text.toString().toLongOrNull() ?: 0L
                val hour = etHour.text.toString().toLongOrNull() ?: 0L
                onConfirm(ms + sec * 1000 + min * 60000 + hour * 3600000)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createDialogInputContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
    }

    private fun createLabeledNumberInputRow(
        hint: String,
        label: String,
        defaultValue: String,
        allowSigned: Boolean,
        labelPaddingStart: Int = 20
    ): Pair<LinearLayout, EditText> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val input = EditText(this).apply {
            this.hint = hint
            inputType = if (allowSigned) {
                EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            } else {
                EditorInfo.TYPE_CLASS_NUMBER
            }
            setText(defaultValue)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val text = TextView(this).apply {
            this.text = label
            setPadding(labelPaddingStart, 0, 0, 0)
        }
        row.addView(input)
        row.addView(text)
        return row to input
    }
    
    private fun applyOffset(offsetMs: Long, longClickPos: Int = -1) {
        if (!ensureListMode()) return

        when {
            // 有长按位置，对长按的那一行应用偏移（无论是否有选中状态）
            longClickPos >= 0 && longClickPos < subtitleEntries.size -> {
                val entry = subtitleEntries[longClickPos]
                applyOffsetToEntry(entry, offsetMs)
                
                // 刷新列表并同步选中状态（保持其他选中状态）
                subtitleAdapter.submitList(subtitleEntries.toList())
                subtitleAdapter.syncSelectionWithCurrentList()
            }
            // 没有长按位置但有选中的字幕，对选中的字幕应用偏移
            subtitleAdapter.getSelectedCount() > 0 -> {
                val selectedEntries = subtitleAdapter.getSelectedEntries()
                selectedEntries.forEach { (entry, _) ->
                    applyOffsetToEntry(entry, offsetMs)
                }
                
                // 刷新列表并同步选中状态
                subtitleAdapter.submitList(subtitleEntries.toList())
                subtitleAdapter.syncSelectionWithCurrentList()
            }
            // 都没有，对所有字幕应用偏移
            else -> {
                subtitleEntries.forEach { entry ->
                    applyOffsetToEntry(entry, offsetMs)
                }
                
                // 刷新列表
                subtitleAdapter.submitList(subtitleEntries.toList())
            }
        }
        
        markAsChanged()
        showShortToast("已应用 ${offsetMs}ms 偏移")
    }
    
    private fun deleteSelectedSubtitles() {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要删除的字幕") ?: return
        
        showDeleteConfirm("确定要删除选中的字幕吗？") {
                val deletedIndices = selectedEntries.map { it.second }.toSet()
                // 从后往前删除，避免索引变化
                selectedEntries.sortedByDescending { it.second }.forEach { (_, position) ->
                    subtitleEntries.removeAt(position)
                }
                renumberEntries()
                syncAfterDelete(deletedIndices)
                markAsChanged()
                Toast.makeText(this, "已删除 ${selectedEntries.size} 条字幕", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 删除字幕后同步状态（保持未删除项的选中状态）
     * @param deletedIndices 被删除的索引集合（删除前的索引）
     */
    private fun syncAfterDelete(deletedIndices: Set<Int>) {
        // 保存删除前的所有选中索引
        val allSelectedIndices = subtitleAdapter.getSelectedPositions()

        // 计算删除后应该保持选中的索引（未被删除的选中项）
        val remainingSelectedIndices = mutableSetOf<Int>()
        allSelectedIndices.forEach { idx ->
            if (idx !in deletedIndices) {
                // 计算有多少个被删除的索引在当前索引之前
                val offset = deletedIndices.count { it < idx }
                remainingSelectedIndices.add(idx - offset)
            }
        }

        // 先清空选中状态，避免旧对象引用问题
        subtitleAdapter.clearSelection()

        subtitleAdapter.submitList(subtitleEntries.toList()) {
            // submitList 完成后，恢复选中状态
            subtitleAdapter.setSelectionByIndices(remainingSelectedIndices)
            updateSelectedCountDisplay()

            // 刷新被删除行的前一行（消除时间冲突标红）
            deletedIndices.forEach { deletedIdx ->
                val offset = deletedIndices.count { it < deletedIdx }
                val prevIdx = (deletedIdx - offset) - 1
                if (prevIdx >= 0 && prevIdx < subtitleEntries.size) {
                    subtitleAdapter.notifyItemChanged(prevIdx)
                }
            }
        }
        updateFormatInfo()

        // 同步字幕到波形视图，保持选中状态
        if (isAudioFile) {
            binding.waveformTimelineView.setSubtitlesAfterDelete(subtitleEntries.toList(), deletedIndices)
        }
    }

    /**
     * 取消所有选择的字幕
     */
    private fun cancelSelection() {
        if (!ensureListMode()) return
        
        subtitleAdapter.clearSelection()
        updateSelectedCountDisplay()
        showShortToast("已取消选择")
    }
    
    /**
     * 显示 AI 翻译对话框
     */
    private fun showAiTranslate() {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要翻译的字幕") ?: return
        
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
                
                finishTranslation(progressDialog)
                
                if (result.isSuccess) {
                    val translatedTexts = result.getOrNull() ?: emptyList()
                    showTranslationResult(selectedEntries, translatedTexts)
                } else {
                    val errorMessage = result.exceptionOrNull()?.message ?: "未知错误"
                    showTranslationError(errorMessage)
                }
            } catch (e: Exception) {
                finishTranslation(progressDialog)
                showTranslationError(e.message ?: "未知错误")
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
            showShortToast("翻译结果数量不匹配")
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
                showShortToast("翻译已应用")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun finishTranslation(progressDialog: AlertDialog) {
        if (progressDialog.isShowing) {
            progressDialog.dismiss()
        }
        isTranslating = false
        translateJob = null
    }

    private fun showTranslationError(message: String) {
        Toast.makeText(this, "翻译失败：$message", Toast.LENGTH_LONG).show()
    }
    
    private fun renumberEntries() {
        subtitleEntries.forEachIndexed { index, entry ->
            entry.index = index + 1
        }
    }
    
    private fun updateFormatInfo() {
        val formatName = getFormatDisplayName(currentFormat)
        val countInfo = if (isSourceViewMode) {
            val lines = sourceViewContent.lines().size
            "行数：$lines"
        } else {
            "条目数：${subtitleEntries.size}"
        }
        currentFormatInfo = "格式：$formatName | $countInfo"
        supportActionBar?.subtitle = currentFormatInfo
        // 条目数变化时，强制刷新所有可见项的序号显示
        if (!isSourceViewMode) {
            subtitleAdapter.refreshAllItems()
        }
    }

    private fun getFormatDisplayName(format: SubtitleParser.SubtitleFormat): String {
        return when (format) {
            SubtitleParser.SubtitleFormat.SRT -> "SRT"
            SubtitleParser.SubtitleFormat.LRC -> "LRC"
            SubtitleParser.SubtitleFormat.TXT -> "TXT"
            else -> "未知"
        }
    }

    private fun getFormatExtension(format: SubtitleParser.SubtitleFormat): String {
        return when (format) {
            SubtitleParser.SubtitleFormat.SRT -> "srt"
            SubtitleParser.SubtitleFormat.LRC -> "lrc"
            SubtitleParser.SubtitleFormat.TXT -> "txt"
            else -> "srt"
        }
    }

    private fun serializeEntriesForFormat(format: SubtitleParser.SubtitleFormat): String {
        return when (format) {
            SubtitleParser.SubtitleFormat.SRT -> SubtitleParser.toSRT(subtitleEntries)
            SubtitleParser.SubtitleFormat.LRC -> SubtitleParser.toLRC(subtitleEntries)
            SubtitleParser.SubtitleFormat.TXT -> SubtitleParser.toTXT(subtitleEntries)
            else -> SubtitleParser.toSRT(subtitleEntries)
        }
    }

    private fun getCurrentEditableContent(requireNonEmptyList: Boolean = false): String? {
        if (isSourceViewMode) return sourceViewContent
        if (requireNonEmptyList && subtitleEntries.isEmpty()) {
            showShortToast("没有内容可保存")
            return null
        }
        return serializeEntriesForFormat(currentFormat)
    }

    private fun ensureListMode(): Boolean {
        if (!isSourceViewMode) return true
        showShortToast("源视图模式下不支持此操作")
        return false
    }

    private fun ensureAudioMode(): Boolean {
        if (isAudioFile) return true
        showShortToast("此功能仅在打开音频文件时可用")
        return false
    }

    private fun ensureClipboardNotEmpty(): Boolean {
        if (clipboardEntries.isNotEmpty()) return true
        showShortToast("剪贴板为空，请先复制")
        return false
    }

    private fun requireSelectedEntries(emptyMessage: String): List<Pair<SubtitleEntry, Int>>? {
        val selectedEntries = subtitleAdapter.getSelectedEntries()
        if (selectedEntries.isEmpty()) {
            showShortToast(emptyMessage)
            return null
        }
        return selectedEntries
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        positiveText: String = "确定",
        negativeText: String = "取消",
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onConfirm() }
            .setNegativeButton(negativeText, null)
            .show()
    }

    private fun showUnsavedChangesConfirm(message: String, onConfirm: () -> Unit) {
        showConfirmDialog(
            title = "提示",
            message = message,
            onConfirm = onConfirm
        )
    }

    private fun runAfterUnsavedChangesConfirmed(
        message: String,
        action: () -> Unit
    ) {
        if (!hasUnsavedChanges) {
            action()
            return
        }
        showUnsavedChangesConfirm(message, action)
    }

    private fun showReplaceAllConfirm(count: Int, onConfirm: () -> Unit) {
        showConfirmDialog(
            title = "确认替换",
            message = "确定要全部替换吗？共找到 $count 处匹配项。",
            onConfirm = onConfirm
        )
    }

    private fun showDeleteConfirm(message: String, onConfirm: () -> Unit) {
        showConfirmDialog(
            title = "删除",
            message = message,
            onConfirm = onConfirm
        )
    }

    private fun clearSearchStateAfterReplace() {
        searchEngine.clearAll()
        binding.etSearch.text?.clear()
    }

    private fun getCurrentSubtitleFile(): File? {
        return if (isAudioFile) subtitleFile else currentFile
    }

    private fun isSourceContentModifiedComparedToFile(editedContent: String): Boolean {
        val file = getCurrentSubtitleFile() ?: return true
        return editedContent != FileUtils.readFile(file, currentCharset)
    }

    private fun readFileOrNull(file: File, failurePrefix: String): String? {
        return try {
            FileUtils.readFile(file, currentCharset)
        } catch (e: Exception) {
            showShortToast("$failurePrefix：${e.message}")
            null
        }
    }

    private fun finishWithToast(message: String) {
        showShortToast(message)
        finish()
    }

    private inline fun saveWithContent(writeAction: (String) -> Unit) {
        try {
            val content = getCurrentEditableContent() ?: return
            writeAction(content)
            hasUnsavedChanges = false
            showShortToast("保存成功")
        } catch (e: Exception) {
            showShortToast("保存失败：${e.message}")
        }
    }

    private fun showShortToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })
    }

    private fun handleBackPressed() {
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
            finish()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 释放 MediaPlayer
        releaseMediaPlayer()
        // 释放波形加载器
        ffmpegChunkLoader?.release()
        ffmpegChunkLoader = null
        // 清理临时修复的 WAV 文件
        tempFixedWavFile?.delete()
        tempFixedWavFile = null
        if (isTranslating) {
            translateCancelled = true
            translateJob?.cancel()
        }
    }
    
    // ==================== 音频播放器相关方法 ====================
    
    /**
     * 检查音频 start time，若不为 0 则用 FFmpeg 转换为 WAV 修复
     * @return 修复后可用的音频文件（可能是原文件，也可能是临时 WAV）
     */
    private suspend fun checkAndFixAudioStartTime(audioFile: File): File {
        return withContext(Dispatchers.IO) {
            // 用 FFprobeKit 获取 start time
            val session = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(audioFile.absolutePath)
            val startTime = session.mediaInformation?.startTime?.toDoubleOrNull() ?: 0.0

            if (startTime <= 0.001) {
                // start time 正常，直接返回原文件
                return@withContext audioFile
            }

            android.util.Log.w("EditorActivity", "音频 start time 不为 0：$startTime，开始转换为 WAV")

            // 生成临时 WAV 文件（放在 cacheDir，避免污染用户目录）
            val wavFile = File(cacheDir, "${audioFile.nameWithoutExtension}_fixed.wav")
            if (wavFile.exists()) wavFile.delete()

            val cmd = "-y -i \"${audioFile.absolutePath}\" -c:a pcm_s16le -ar 44100 -ac 2 \"${wavFile.absolutePath}\""
            val ffmpegSession = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)

            if (ffmpegSession.returnCode.isValueSuccess) {
                android.util.Log.d("EditorActivity", "WAV 转换成功：${wavFile.absolutePath}")
                wavFile
            } else {
                android.util.Log.e("EditorActivity", "WAV 转换失败，使用原文件")
                audioFile
            }
        }
    }
    
    /**
     * 初始化 MediaPlayer
     */
    private fun initializeMediaPlayer() {
        if (!isAudioFile) return
        
        mediaPlayer = MediaPlayer()
        mediaPlayer?.setOnCompletionListener {
            isPlaying = false
            stopProgressUpdate()
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
        // 停止进度更新
        stopProgressUpdate()
        
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
        
        // 设置时间轴点击监听器
        binding.waveformTimelineView.onTimelineClickListener = { position ->
            // 点击时间轴跳转到对应时间
            val targetTime = (audioDuration * position).toLong()
            seekTo(targetTime)
            // 暂停时也要更新 UI，确保播放头位置正确显示
            updatePlayerUI()
        }
        
        // 设置字幕变化监听器
        binding.waveformTimelineView.onSubtitleChangeListener = { updatedSubtitles ->
            // 更新字幕列表
            subtitleEntries.clear()
            subtitleEntries.addAll(updatedSubtitles)
            renumberEntries()
            // 使用 notifyDataSetChanged 强制立即刷新，而不是异步的 submitList
            subtitleAdapter.notifyDataSetChanged()
            markAsChanged()
        }
        
        // 设置选中状态变化监听器（波形时间轴选中状态变化时的处理）
        binding.waveformTimelineView.onSelectedIndicesChangeListener = { indices ->
            // 不同步选中状态到字幕列表，保持两者独立
            // 只处理循环播放和滚动逻辑
            if (indices.isNotEmpty()) {
                val firstSelectedIndex = indices.first()
                if (firstSelectedIndex >= 0 && firstSelectedIndex < subtitleEntries.size) {
                    binding.rvSubtitles.scrollToPosition(firstSelectedIndex)
                    loopSubtitleEntry = subtitleEntries[firstSelectedIndex]

                    // 若循环模式开启，且播放头不在字幕区间内，则跳到字幕起始时间
                    val target = subtitleEntries[firstSelectedIndex]
                    if (SettingsManager.getInstance(this).isLoopSelectedSubtitleEnabled()) {
                        if (audioCurrentPosition < target.startTime || audioCurrentPosition >= target.endTime) {
                            seekTo(target.startTime)
                        }
                    }
                }
            } else {
                loopSubtitleEntry = null
            }
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
                    val targetTime = (audioDuration * progress / 1000).toLong()
                    audioCurrentPosition = targetTime
                    binding.tvCurrentTime.text = TimeUtils.formatForDisplay(targetTime)
                    val wavePosition = if (audioDuration > 0) audioCurrentPosition.toFloat() / audioDuration else 0f
                    binding.waveformTimelineView.setCurrentPosition(wavePosition)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isUserSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isUserSeeking = false
                seekTo(audioCurrentPosition)
            }
        })
        
        // 初始化播放器状态
        updatePlayerUI()
        
        // ——— 展开/折叠 ———
        binding.btnToggleWaveform.setOnClickListener {
            isWaveformExpanded = !isWaveformExpanded
            binding.timelineContainer.visibility =
                if (isWaveformExpanded) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnToggleWaveform.text =
                if (isWaveformExpanded) "▼" else "▶"
            updateGenerateButton()
            refreshWaveformToolbarState()
        }

        // ——— 模式切换 ———
        binding.btnToggleDisplayMode.setOnClickListener {
            currentDisplayMode =
                if (currentDisplayMode == WaveformTimelineView.DisplayMode.WAVEFORM)
                    WaveformTimelineView.DisplayMode.SPECTROGRAM
                else WaveformTimelineView.DisplayMode.WAVEFORM

            binding.waveformTimelineView.setDisplayMode(currentDisplayMode)

            if (currentDisplayMode == WaveformTimelineView.DisplayMode.SPECTROGRAM) {
                // 切换到频谱图时，检查是否已经启动过生成
                if (isSpectrogramGenerationStarted) {
                    // 如果之前已经启动过生成，切回来时直接恢复状态并刷新可见区域
                    // 只要已完成的分块还没达到总数，就认为还在生成中
                    spectrogramIsGenerating = spectrogramDoneChunks < spectrogramTotalChunks
                    binding.waveformTimelineView.refreshVisibleChunks()
                } else {
                    // 只有在完全没有启动过生成的情况下，才初始化这些重置状态
                    binding.waveformTimelineView.resetSpectrogramCache()
                    spectrogramTotalChunks = calcTotalChunks()
                    spectrogramDoneChunks = 0
                    spectrogramIsGenerating = false
                }
            }

            updateGenerateButton()
            refreshWaveformToolbarState()
        }

        // ——— 频谱图分块回调 ———
        binding.waveformTimelineView.onSpectrogramChunkRequest =
            { chunkIndex, startMs, endMs, widthPx, heightPx ->
                // 只有用户手动点击生成后才真正执行
                if (isSpectrogramGenerationStarted) {
                    generateSpectrogramChunkAsync(chunkIndex, startMs, endMs, widthPx, heightPx)
                }
            }

        // ——— 振幅缩放 ———
        binding.btnAmplitudeZoomIn.setOnClickListener {
            binding.waveformTimelineView.zoomInAmplitude()
        }
        binding.btnAmplitudeZoomIn.setOnLongClickListener {
            binding.waveformTimelineView.resetAmplitudeScale()
            Toast.makeText(this, "振幅已重置", Toast.LENGTH_SHORT).show()
            true
        }
        binding.btnAmplitudeZoomOut.setOnClickListener {
            binding.waveformTimelineView.zoomOutAmplitude()
        }

        // ——— 播放速率按钮 ———
        binding.tvPlaybackSpeed.setOnClickListener {
            showSpeedInputDialog()
        }

        // ——— 手动生成按钮 ———
        binding.btnGenerateCache.setOnClickListener {
            if (currentDisplayMode == WaveformTimelineView.DisplayMode.WAVEFORM) {
                startWaveformGeneration()
            } else {
                startSpectrogramGeneration()
            }
        }

        // ——— 打轴按钮 ———
        var timestampStartMs = 0L
        binding.btnInsertSubtitle.setOnLongClickListener {
            timestampStartMs = audioCurrentPosition
            binding.waveformTimelineView.startTimestamping(timestampStartMs)
            true
        }
        binding.btnInsertSubtitle.setOnTouchListener { _, event ->
            if ((event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)
                && binding.waveformTimelineView.isInTimestampingMode()) {
                val endMs = binding.waveformTimelineView.stopTimestamping()
                insertSubtitleFromTimestamp(timestampStartMs, endMs)
            }
            false
        }
    }

    /** 计算频谱图总 chunk 数 */
    private fun calcTotalChunks(): Int {
        if (audioDuration <= 0) return 0
        val chunkMs = WaveformTimelineView.CHUNK_DURATION_MS
        return ((audioDuration + chunkMs - 1) / chunkMs).toInt()
    }

    /** 根据当前展开状态和显示模式，同步工具栏按钮文字和可用状态 */
    private fun refreshWaveformToolbarState() {
        val isSpectrogram = currentDisplayMode == WaveformTimelineView.DisplayMode.SPECTROGRAM

        // 标签文字：显示当前正在展示的内容名称
        (binding.tvWaveformLabel as? android.widget.TextView)?.text =
            if (isSpectrogram) "频谱图" else "波形图"

        // 模式切换按钮文字：显示点击后将切换到的目标模式
        (binding.btnToggleDisplayMode as? android.widget.TextView)?.text =
            if (isSpectrogram) "波形" else "频谱"

        // 振幅按钮：频谱模式下或折叠时禁用
        val amplEnabled = isWaveformExpanded && !isSpectrogram
        binding.btnAmplitudeZoomIn.isEnabled  = amplEnabled
        binding.btnAmplitudeZoomOut.isEnabled = amplEnabled
        val color = if (amplEnabled) "#CCCCCC" else "#555555"
        (binding.btnAmplitudeZoomIn  as? android.widget.TextView)
            ?.setTextColor(android.graphics.Color.parseColor(color))
        (binding.btnAmplitudeZoomOut as? android.widget.TextView)
            ?.setTextColor(android.graphics.Color.parseColor(color))
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
        
        // 先显示检测中提示，再异步检测 start time
        val checkingDialog = android.app.AlertDialog.Builder(this)
            .setMessage("正在检测音频文件...")
            .setCancelable(false)
            .create()
        checkingDialog.show()
        
        lifecycleScope.launch {
            val originalFile = currentFile!!
            val audioFile = checkAndFixAudioStartTime(originalFile)
            
            val wasFixed = audioFile != originalFile
            if (wasFixed) {
                tempFixedWavFile = audioFile
            }
            
            checkingDialog.dismiss()
            
            if (wasFixed) {
                Toast.makeText(
                    this@EditorActivity,
                    "检测到音频 start time 不为 0,请注意处理,已临时修复，正在加载...",
                    Toast.LENGTH_LONG
                ).show()
            }
            
            // 使用修复后的文件路径继续加载
            doLoadAudioFile(audioFile, subtitleFilePath)
        }
    }
    
    /**
     * 实际执行音频加载（原 loadAudioFile 的主体逻辑）
     */
    private fun doLoadAudioFile(audioFile: File, subtitleFilePath: String?) {
        audioFilePath = audioFile.absolutePath
        supportActionBar?.title = subtitleFilePath?.let { File(it).name } ?: "（无字幕文件）"
        
        try {
            mediaPlayer?.reset()
            mediaPlayer?.setDataSource(audioFile.absolutePath)
            mediaPlayer?.prepare()
            audioDuration = mediaPlayer?.duration?.toLong() ?: 0L
            // 恢复用户设置的播放速率
            if (playbackSpeed != 1.0f) {
                mediaPlayer?.playbackParams = android.media.PlaybackParams().setSpeed(playbackSpeed)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "加载音频失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
        
        if (subtitleFilePath != null) {
            val subtitleFile = File(subtitleFilePath)
            if (subtitleFile.exists()) {
                loadSubtitleFile(subtitleFile)
            } else {
                subtitleEntries.clear()
                subtitleAdapter.submitList(emptyList())
                updateFormatInfo()
                Toast.makeText(this, "未找到同名字幕文件", Toast.LENGTH_SHORT).show()
            }
        } else {
            subtitleEntries.clear()
            subtitleAdapter.submitList(emptyList())
            updateFormatInfo()
        }
        
        binding.waveformTimelineView.initialize(audioDuration, subtitleEntries.toList())
        
        // 波形缓存使用修复后的音频文件
        val settingsManager = SettingsManager.getInstance(this)
        val cacheDir: File? = when (settingsManager.getWaveformCacheLocation()) {
            SettingsManager.WAVEFORM_CACHE_APP -> File(cacheDir, "waveform")
            else -> null   // null = 与音频同目录
        }
        
        ffmpegChunkLoader = FfmpegWaveformChunkLoader(lifecycleScope)
        ffmpegChunkLoader?.prepare(audioFile.absolutePath, audioDuration, cacheDir)
        
        if (ffmpegChunkLoader?.isCacheReady() == true) {
            // 缓存已存在，直接连接，不显示按钮
            isWaveformGenerated = true
            connectWaveformLoader()
        } else {
            // 需要手动生成
            isWaveformGenerated = false
            updateGenerateButton()
        }
        
        updatePlayerUI()
        // 初始状态下同步生成按钮
        if (isAudioFile) updateGenerateButton()
    }

    /**
     * 异步生成某个 chunk 的频谱图（分块版本）
     * 每个 chunk 按当前 View 宽度生成对应 30s 的频谱图，保证 1:1 像素精度
     */
    private fun generateSpectrogramChunkAsync(
        chunkIndex: Int,
        startMs: Long, endMs: Long,
        widthPx: Int, heightPx: Int
    ) {
        val audioFile = currentFile ?: return
        val settingsManager = SettingsManager.getInstance(this)
        val cacheBaseDir = when (settingsManager.getWaveformCacheLocation()) {
            SettingsManager.WAVEFORM_CACHE_APP -> File(cacheDir, "waveform")
            else -> audioFile.parentFile ?: File(cacheDir, "waveform")
        }
        cacheBaseDir.mkdirs()

        val specFile = File(cacheBaseDir,
            "${audioFile.nameWithoutExtension}.spec_${chunkIndex}_${widthPx}x${heightPx}.png")

        lifecycleScope.launch(Dispatchers.IO) {
            val bmp: android.graphics.Bitmap? = if (specFile.exists() && specFile.length() > 0) {
                // 缓存命中，不算作"新生成"，直接回调
                android.graphics.BitmapFactory.decodeFile(specFile.absolutePath)
            } else {
                val startSec = startMs / 1000.0
                val durSec   = (endMs - startMs) / 1000.0
                val cmd = "-y -ss $startSec -t $durSec " +
                          "-i \"${audioFile.absolutePath}\" " +
                          "-lavfi showspectrumpic=s=${widthPx}x${heightPx}:" +
                          "mode=combined:color=intensity:scale=log:legend=0 " +
                          "-frames:v 1 \"${specFile.absolutePath}\""

                val session = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
                if (session.returnCode.isValueSuccess && specFile.exists())
                    android.graphics.BitmapFactory.decodeFile(specFile.absolutePath)
                else null
            }

            withContext(Dispatchers.Main) {
                if (bmp != null) {
                    binding.waveformTimelineView.updateSpectrogramChunk(chunkIndex, bmp)

                    // 更新进度：只在本次切换触发的生成流程中计数
                    if (spectrogramIsGenerating) {
                        spectrogramDoneChunks++
                        if (spectrogramDoneChunks >= spectrogramTotalChunks) {
                            spectrogramIsGenerating = false
                            Toast.makeText(
                                this@EditorActivity,
                                "频谱图缓存生成完成",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * 根据当前模式和生成状态，更新生成按钮的可见性和文字
     */
    private fun updateGenerateButton() {
        val needsGenerate = when (currentDisplayMode) {
            WaveformTimelineView.DisplayMode.WAVEFORM    -> !isWaveformGenerated
            WaveformTimelineView.DisplayMode.SPECTROGRAM -> !isSpectrogramGenerationStarted
        }
        binding.btnGenerateCache.visibility =
            if (needsGenerate && isWaveformExpanded) android.view.View.VISIBLE
            else android.view.View.GONE

        // 根据模式和正在生成的状态更新按钮文字和可点击性
        if (currentDisplayMode == WaveformTimelineView.DisplayMode.WAVEFORM) {
            if (isWaveformGenerating) {
                binding.btnGenerateCache.text = "生成中..."
                binding.btnGenerateCache.isEnabled = false
            } else {
                binding.btnGenerateCache.text = "生成波形图"
                binding.btnGenerateCache.isEnabled = true
            }
        } else {
            binding.btnGenerateCache.text = "生成频谱图"
            // 频谱图一旦开始就会隐藏按钮，所以只要显示就一定是可用的
            binding.btnGenerateCache.isEnabled = true 
        }
    }

    /**
     * 用户点击生成 → 开始波形缓存生成
     */
    private fun startWaveformGeneration() {
        // 标记为正在生成
        isWaveformGenerating = true
        updateGenerateButton()
        
        Toast.makeText(this, "正在生成波形缓存，请稍候...", Toast.LENGTH_SHORT).show()
        ffmpegChunkLoader?.generateCache { success ->
            // 回调结束，重置生成中状态
            isWaveformGenerating = false
            
            if (success) {
                isWaveformGenerated = true
                Toast.makeText(this, "波形缓存生成完成", Toast.LENGTH_SHORT).show()
                connectWaveformLoader()
            } else {
                Toast.makeText(this, "波形缓存生成失败", Toast.LENGTH_SHORT).show()
            }
            updateGenerateButton()
        }
    }

    /**
     * 用户点击生成 → 开始频谱图生成（解锁 chunk 回调）
     */
    private fun startSpectrogramGeneration() {
        isSpectrogramGenerationStarted = true
        spectrogramTotalChunks = calcTotalChunks()
        spectrogramDoneChunks = 0
        spectrogramIsGenerating = spectrogramTotalChunks > 0
        updateGenerateButton()
        Toast.makeText(this, "正在生成频谱图缓存，请稍候...", Toast.LENGTH_SHORT).show()
        
        // 【关键修复】重置 View 内部的 Spectrogram 缓存状态
        // 这会清除 View 内部记录的"已请求 Chunk"列表，
        // 防止之前被忽略的初始可见区块请求（如开头 3 分钟）被判定为重复请求而跳过。
        binding.waveformTimelineView.resetSpectrogramCache()
        
        // 触发可见区域的 chunk 请求
        binding.waveformTimelineView.refreshVisibleChunks()
    }

    /**
     * 连接波形加载器到 View
     */
    private fun connectWaveformLoader() {
        binding.waveformTimelineView.onChunkLoadRequest = { chunkIndex, startMs, endMs, targetSamples ->
            ffmpegChunkLoader?.requestChunk(chunkIndex, startMs, endMs, targetSamples) { idx, data ->
                binding.waveformTimelineView.post { 
                    binding.waveformTimelineView.updateChunk(idx, data) 
                }
            }
        }
        
        // 缓存已就绪，重新触发可见区域的 chunk 加载请求
        // （因为在 initialize() 时可能回调还未设置，导致请求被忽略）
        binding.waveformTimelineView.refreshVisibleChunks()
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
                // 暂停时停止进度更新
                stopProgressUpdate()
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
        // 若循环模式开启且有选中字幕，将目标时间钳制在字幕区间内
        val loopTarget = loopSubtitleEntry
        val clampedTime = if (
            loopTarget != null &&
            SettingsManager.getInstance(this).isLoopSelectedSubtitleEnabled()
        ) {
            timeMs.coerceIn(loopTarget.startTime, loopTarget.endTime - 1)
        } else {
            timeMs.coerceIn(0L, audioDuration)
        }

        mediaPlayer?.seekTo(clampedTime.toInt())
        audioCurrentPosition = clampedTime

        // 高亮显示对应时间的字幕
        highlightSubtitleAtTime(audioCurrentPosition)
        
        // 立即更新 UI（进度条、波形图时间轴线）
        updatePlayerUI()
        
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
        // endTime 用严格小于，确保边界时间点归属于后一行而非前一行
        for ((index, entry) in subtitleEntries.withIndex()) {
            if (timeMs >= entry.startTime && timeMs < entry.endTime) {
                subtitleAdapter.highlightCurrentPlaying(index)
                return
            }
        }
        // 当前时间不在任何字幕区间内，清除高亮
        subtitleAdapter.clearPlayingHighlight()
    }
    
    private fun updatePlayerUI() {
        mediaPlayer?.let { player ->
            if (!isUserSeeking) {
                val pos = player.currentPosition.toLong()
                // 单调递增过滤：只接受向前推进的值（200ms 容差允许 seek 后的回退）
                if (pos >= audioCurrentPosition || audioCurrentPosition - pos > 200) {
                    audioCurrentPosition = pos
                }
            }
            audioDuration = player.duration.toLong().takeIf { it > 0 } ?: audioDuration
            isPlaying = player.isPlaying
        }

        binding.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )

        binding.tvCurrentTime.text = TimeUtils.formatForDisplay(audioCurrentPosition)
        binding.tvTotalTime.text   = TimeUtils.formatForDisplay(audioDuration)

        if (!isUserSeeking) {
            val progress = if (audioDuration > 0)
                (audioCurrentPosition * 1000 / audioDuration).toInt().coerceIn(0, 1000)
            else 0
            binding.seekBar.progress = progress
        }

        val wavePosition = if (audioDuration > 0) audioCurrentPosition.toFloat() / audioDuration else 0f
        binding.waveformTimelineView.setCurrentPosition(wavePosition)
    }
    
    // ==================== 字幕时间控制按钮方法 ====================
    
    /**
     * 跳转到字幕的开始时间
     */
    private fun jumpToSubtitleTime(entry: SubtitleEntry) {
        if (!ensureAudioMode()) return
        
        seekTo(entry.startTime)
        showShortToast("已跳转到 ${TimeUtils.formatForDisplay(entry.startTime)}")
    }
    
    
    
    /**
     * 将字幕的开始时间设置为当前音频进度
     */
    private fun setSubtitleTimeToCurrentPosition(entry: SubtitleEntry, position: Int) {
        if (!ensureAudioMode()) return
        
        val newStartTime = audioCurrentPosition
        entry.startTime = newStartTime
        
        // 刷新该条目显示
        subtitleAdapter.notifyItemChanged(position)
        markAsChanged()
        
        if (newStartTime >= entry.endTime) {
            Toast.makeText(this, "开始时间已设置，但大于结束时间，请调整结束时间", Toast.LENGTH_LONG).show()
        } else {
            showShortToast("已将开始时间设置为 ${TimeUtils.formatForDisplay(newStartTime)}")
        }
    }
    
    /**
     * 弹出速率输入对话框
     */
    private fun showSpeedInputDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(playbackSpeed.toString())
            hint = "例如：0.5、1.0、1.5、2.0"
            selectAll()
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(this)
            .setTitle("设置播放速率")
            .setMessage("请输入倍数（0.25 ~ 4.0）")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val text = input.text?.toString()?.trim() ?: ""
                val speed = text.toFloatOrNull()
                when {
                    speed == null ->
                        Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show()
                    speed < 0.25f || speed > 4.0f ->
                        Toast.makeText(this, "速率范围：0.25 ~ 4.0", Toast.LENGTH_SHORT).show()
                    else ->
                        applyPlaybackSpeed(speed)
                }
            }
            .setNegativeButton("取消", null)
            .show()

        // 自动弹出软键盘
        input.postDelayed({
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    /**
     * 应用播放速率到 MediaPlayer，并刷新按钮文字
     */
    private fun applyPlaybackSpeed(speed: Float) {
        playbackSpeed = speed

        // 格式化按钮文字：整数倍省略小数（1× / 1.5×）
        val label = if (speed == speed.toLong().toFloat()) {
            "${speed.toLong()}×"
        } else {
            // 最多保留两位有效小数，去掉末尾 0
            "%.2f".format(speed).trimEnd('0').trimEnd('.') + "×"
        }
        binding.tvPlaybackSpeed.text = label

        mediaPlayer?.let { player ->
            try {
                val params = android.media.PlaybackParams().setSpeed(speed)
                player.playbackParams = params
            } catch (e: Exception) {
                Toast.makeText(this, "设置速率失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        Toast.makeText(this, "播放速率已设置为 ${label}", Toast.LENGTH_SHORT).show()
    }
}

