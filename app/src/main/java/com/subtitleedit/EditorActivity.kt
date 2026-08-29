package com.subtitleedit

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.adapter.SubtitleAdapter
import com.subtitleedit.adapter.TranslationPreviewItem
import com.subtitleedit.databinding.ActivityEditorBinding
import com.subtitleedit.editor.EditorAudioFilePreparer
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.editor.EditorPlaybackController
import com.subtitleedit.editor.EditorSearchController
import com.subtitleedit.editor.EditorSubtitlePreviewController
import com.subtitleedit.editor.EditorTextPreviewDialog
import com.subtitleedit.editor.EditorTranscribeController
import com.subtitleedit.editor.EditorTranslationController
import com.subtitleedit.editor.EditorTtsController
import com.subtitleedit.editor.EditorWaveformController
import com.subtitleedit.util.DraftManager
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.CutPasteController
import com.subtitleedit.util.SubtitlePasteOps
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SearchReplaceOps
import com.subtitleedit.util.SubtitleEntryOps
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TimeUtils
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val stateModel: EditorViewModel by viewModels()

    private var filePath: String
        get() = stateModel.filePath
        set(value) { stateModel.filePath = value }
    private var currentFile: File?
        get() = stateModel.currentFile
        set(value) { stateModel.currentFile = value }
    // 字幕文件路径（当打开音频文件时，用于保存字幕）
    private var subtitleFilePath: String
        get() = stateModel.subtitleFilePath
        set(value) { stateModel.subtitleFilePath = value }
    private var subtitleFile: File?
        get() = stateModel.subtitleFile
        set(value) { stateModel.subtitleFile = value }
    private var subtitleEntries: MutableList<SubtitleEntry>
        get() = stateModel.subtitleEntries
        set(value) { stateModel.subtitleEntries = value }
    private var lastIndexedEntryCount: Int
        get() = stateModel.lastIndexedEntryCount
        set(value) { stateModel.lastIndexedEntryCount = value }
    private var currentCharset: Charset
        get() = stateModel.currentCharset
        set(value) { stateModel.currentCharset = value }
    private var currentFormat: SubtitleParser.SubtitleFormat
        get() = stateModel.currentFormat
        set(value) { stateModel.currentFormat = value }
    
    // 源视图模式标志
    private var isSourceViewMode: Boolean
        get() = stateModel.isSourceViewMode
        set(value) { stateModel.isSourceViewMode = value }
    // 源视图原始内容（用于 TXT 文件）- 保存原始文件内容，不做任何修改
    private var originalFileContent: String
        get() = stateModel.originalFileContent
        set(value) { stateModel.originalFileContent = value }
    // 当前显示的内容（可能是原始内容或从字幕列表生成的内容）
    private var sourceViewContent: String
        get() = stateModel.sourceViewContent
        set(value) { stateModel.sourceViewContent = value }

    // 大文件切换时，避免 TextWatcher 在 setText/逐字编辑期间反复复制整份文本。
    private var suppressSourceViewChanges = false
    private var sourceViewHasPendingEdits = false
    private var sourceViewTransitionJob: Job? = null
    private var sourceViewPreviewJob: Job? = null
    private var sourceViewWaveformSyncJob: Job? = null
    private var sourceViewEntryCount = 0
    private var sourceViewEditGeneration = 0L
    
    // 切换视图前保存的滚动位置
    private var savedScrollPosition: Int
        get() = stateModel.savedScrollPosition
        set(value) { stateModel.savedScrollPosition = value }
    private var savedFirstVisibleItemPosition: Int
        get() = stateModel.savedFirstVisibleItemPosition
        set(value) { stateModel.savedFirstVisibleItemPosition = value }
    
    // 长按时的位置（用于时间偏移等操作）
    private var longClickPosition: Int = -1
    
    // 是否有未保存的更改
    private var hasUnsavedChanges: Boolean
        get() = stateModel.hasUnsavedChanges
        set(value) { stateModel.hasUnsavedChanges = value }

    // 是否为新建且从未保存过的文件
    private var isNewFile: Boolean
        get() = stateModel.isNewFile
        set(value) { stateModel.isNewFile = value }

    // 当前格式信息（用于 toolbar subtitle 恢复）
    private var currentFormatInfo: String
        get() = stateModel.currentFormatInfo
        set(value) { stateModel.currentFormatInfo = value }
    
    // 复制/剪贴板数据（支持多行）
    private var clipboardTexts: List<String>
        get() = stateModel.clipboardTexts
        set(value) { stateModel.clipboardTexts = value }
    private val cutPasteController = CutPasteController()
    
    // AI 翻译 / 快速转录 / 快速 TTS
    private lateinit var translationController: EditorTranslationController
    private lateinit var transcribeController: EditorTranscribeController
    private lateinit var ttsController: EditorTtsController

    private lateinit var searchController: EditorSearchController
    
    private var mediaType: EditorMediaType
        get() = stateModel.mediaType
        set(value) { stateModel.mediaType = value }
    private var isAudioOnlyFromVideo: Boolean
        get() = stateModel.isAudioOnlyFromVideo
        set(value) { stateModel.isAudioOnlyFromVideo = value }
    private lateinit var audioFilePreparer: EditorAudioFilePreparer
    private lateinit var playbackController: EditorPlaybackController
    private lateinit var waveformController: EditorWaveformController
    private lateinit var subtitlePreviewController: EditorSubtitlePreviewController
    private var waveformMediaFile: File? = null
    private var waveformAudioStreamIndex: Int? = null
    private var isVideoFullscreen = false
    private var previousRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var videoViewportInlineIndex = 0

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
        if (uri == null) {
            stateModel.saveCoordinator.cancel()
        } else {
            val continuation = stateModel.saveCoordinator.complete(saveFileToUri(uri))
            executeSaveContinuation(continuation)
        }
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
        const val EXTRA_MEDIA_TYPE = "extra_media_type"
        const val EXTRA_AUDIO_ONLY_FROM_VIDEO = "extra_audio_only_from_video"
        const val EXTRA_SUBTITLE_FILE_PATH = "extra_subtitle_file_path"
        private const val MENU_SELECT_ALL = 0x20001
        private const val MENU_SELECT_RANGE = 0x20002
        private const val BULK_NOTIFY_THRESHOLD = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        audioFilePreparer = EditorAudioFilePreparer(cacheDir)
        
        if (!stateModel.initialized) {
            filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
            mediaType = if (intent.hasExtra(EXTRA_MEDIA_TYPE)) {
                EditorMediaType.fromIntentValue(intent.getStringExtra(EXTRA_MEDIA_TYPE))
            } else if (intent.getBooleanExtra(EXTRA_IS_AUDIO_FILE, false)) {
                EditorMediaType.AUDIO
            } else {
                EditorMediaType.SUBTITLE_ONLY
            }
            isAudioOnlyFromVideo = intent.getBooleanExtra(EXTRA_AUDIO_ONLY_FROM_VIDEO, false)
            subtitleFilePath = intent.getStringExtra(EXTRA_SUBTITLE_FILE_PATH) ?: ""

            if (filePath.isNotEmpty()) {
                if (mediaType.hasPlayableMedia) {
                    currentFile = File(filePath)
                    if (subtitleFilePath.isNotEmpty()) {
                        subtitleFile = File(subtitleFilePath)
                    }
                } else {
                    currentFile = File(filePath)
                }
            }
            stateModel.initialized = true
        }
        
        setupToolbar()
        setupRecyclerView()
        setupSourceView()
        setupSearchController()
        setupPlaybackController()
        setupWaveformController()
        setupSubtitlePreviewController()
        setupAiControllers()
        setupMediaActions()
        setupVideoPanel()
        setupBackPressedHandler()
        
        if (stateModel.documentLoaded) {
            restoreDocumentState()
            if (mediaType.hasPlayableMedia && filePath.isNotEmpty()) {
                loadMediaFile(subtitleFilePath, restoreDocument = true)
            }
        } else if (filePath.isNotEmpty()) {
            if (mediaType.hasPlayableMedia) {
                loadMediaFile(subtitleFilePath)
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
            if (subtitleAdapter.getSelectedCount() > 0) {
                cancelSelection()
            } else {
                handleBackPressed()
            }
        }
        
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            handleMenuClick(menuItem)
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean = true

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        if (::subtitleAdapter.isInitialized && subtitleAdapter.getSelectedCount() > 0) {
            menu.add(Menu.NONE, MENU_SELECT_ALL, 0, "全选")
                .setIcon(R.drawable.ic_select_all)
                .setContentDescription("全选")
                .setTooltipText("全选")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, MENU_SELECT_RANGE, 1, "区间选择")
                .setIcon(R.drawable.ic_select_range)
                .setContentDescription("区间选择")
                .setTooltipText("区间选择")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        } else {
            menuInflater.inflate(R.menu.menu_editor, menu)
        }
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
            R.id.menu_merge_subtitles -> {
                showMergeSubtitlesDialog()
                true
            }
            R.id.menu_search -> {
                searchController.show()
                true
            }
            MENU_SELECT_ALL -> {
                selectAllSubtitles()
                true
            }
            MENU_SELECT_RANGE -> {
                selectRangeBetweenSelectedSubtitles()
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
            onItemClick = { _, _ -> },
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
            hasPlayableMedia = mediaType.hasPlayableMedia,
            onSelectionChanged = {
                updateSelectedCountDisplay()
            }
        )
        
        binding.rvSubtitles.apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            adapter = subtitleAdapter
            // 滑块大跨度定位时复用更多已绑定行，减少文本测量和 ViewHolder 重绑。
            setItemViewCacheSize(12)
        }
    }
    
    private fun setupSourceView() {
        binding.etSourceView.addOnDocumentChangedListener {
            if (isSourceViewMode && !suppressSourceViewChanges) {
                hasUnsavedChanges = true
                sourceViewHasPendingEdits = true
                sourceViewEditGeneration++
                // SourceEditorView keeps one editable block per physical line. The debounced
                // preview performs the full-text snapshot only when parsing is needed.
                updateFormatInfo()
                scheduleSourceViewPreview()
            }
        }
    }
    
    private fun setupSearchController() {
        searchController = EditorSearchController(
            context = this,
            binding = binding,
            subtitleAdapter = subtitleAdapter,
            isSourceViewMode = { isSourceViewMode },
            ignoreSourceChanges = { suppressSourceViewChanges },
            entries = { subtitleEntries },
            replaceSourceContent = { content ->
                replaceSourceViewContent(content)
            },
            applyEntryUpdates = { updates ->
                val result = SearchReplaceOps.applyEntryUpdates(subtitleEntries, updates)
                if (result.removedCount > 0) {
                    submitSubtitleList(refreshAll = true, markChanged = true)
                } else {
                    notifyEntriesChanged(updates.map { it.index }, includeNeighbors = false)
                }
                result.removedCount
            },
            confirmReplaceAll = ::showReplaceAllConfirm,
            showMessage = ::showShortToast
        )
    }

    private fun setupPlaybackController() {
        playbackController = EditorPlaybackController(
            context = this,
            binding = binding,
            mediaType = mediaType,
            subtitles = { subtitleEntries },
            isSourceViewMode = { isSourceViewMode },
            onPlayingSubtitleChanged = { index ->
                if (index == null) {
                    subtitleAdapter.clearPlayingHighlight()
                } else {
                    subtitleAdapter.highlightCurrentPlaying(index)
                }
            },
            onMediaReady = ::onMediaReady,
            showMessage = ::showShortToast
        )
        playbackController.bind()
    }

    private fun setupWaveformController() {
        waveformController = EditorWaveformController(
            context = this,
            binding = binding,
            scope = lifecycleScope,
            hasPlayableMedia = mediaType.hasPlayableMedia,
            appCacheDir = cacheDir,
            currentPlaybackPositionMs = { playbackController.currentPositionMs },
            onSubtitlesChanged = { updatedSubtitles ->
                replaceSubtitleEntries(updatedSubtitles)
                if (isSourceViewMode) {
                    // 波形拖动期间 WaveformTimelineView 已经持有最新对象；这里只需把
                    // 时间字段异步序列化回源文档，避免每个 MOVE 事件都重建大文本。
                    scheduleSourceViewWaveformSync(updatedSubtitles)
                    markAsChanged()
                } else {
                    submitSubtitleList(refreshAll = true, syncWaveform = false, markChanged = true)
                }
            },
            onSelectedIndexChanged = { index ->
                if (index in subtitleEntries.indices) {
                    (binding.rvSubtitles.layoutManager as? LinearLayoutManager)?.let { manager ->
                        manager.scrollToPositionWithOffset(index, 0)
                    }
                }
            },
            onTimestampInserted = ::insertSubtitleFromTimestamp,
            showMessage = ::showShortToast
        )
        waveformController.bind()
    }

    private fun setupSubtitlePreviewController() {
        subtitlePreviewController = EditorSubtitlePreviewController(
            cacheDir = cacheDir,
            scope = lifecycleScope,
            replaceTrack = playbackController::replaceVideoSubtitleTrack
        )
    }

    private fun setupVideoPanel() {
        val isVideo = mediaType == EditorMediaType.VIDEO
        binding.videoSection.visibility = if (isVideo) View.VISIBLE else View.GONE
        binding.audioPlaybackControls.visibility = if (isVideo) View.GONE else View.VISIBLE
        if (mediaType != EditorMediaType.VIDEO) return

        videoViewportInlineIndex = binding.videoSection.indexOfChild(binding.videoViewportContainer)
            .coerceAtLeast(0)
        binding.btnVideoFullscreen.setOnClickListener {
            playbackController.showVideoControlsForInteraction()
            toggleVideoFullscreen()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.videoControlsOverlay) { view, insets ->
            val safeArea = if (isVideoFullscreen) {
                insets.getInsets(
                    WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.systemGestures()
                )
            } else {
                androidx.core.graphics.Insets.NONE
            }
            view.setPadding(safeArea.left, 0, safeArea.right, 0)
            insets
        }
        renderVideoFullscreenButton()
    }

    private fun toggleVideoFullscreen() {
        if (isVideoFullscreen) exitVideoFullscreen() else enterVideoFullscreen()
    }

    private fun enterVideoFullscreen() {
        if (mediaType != EditorMediaType.VIDEO || isVideoFullscreen) return
        val viewport = binding.videoViewportContainer
        (viewport.parent as? ViewGroup)?.removeView(viewport)
        binding.videoFullscreenHost.addView(
            viewport,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        binding.videoFullscreenHost.visibility = View.VISIBLE
        isVideoFullscreen = true
        binding.editorAppBar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        binding.editorContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        previousRequestedOrientation = requestedOrientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBarsForVideo()
        ViewCompat.requestApplyInsets(binding.videoControlsOverlay)
        renderVideoFullscreenButton()
    }

    private fun exitVideoFullscreen() {
        if (!isVideoFullscreen) return
        val viewport = binding.videoViewportContainer
        binding.videoFullscreenHost.removeView(viewport)
        binding.videoSection.addView(
            viewport,
            videoViewportInlineIndex.coerceAtMost(binding.videoSection.childCount),
            createInlineVideoLayoutParams()
        )
        binding.videoFullscreenHost.visibility = View.GONE
        isVideoFullscreen = false
        binding.editorAppBar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        binding.editorContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        showSystemBarsAfterVideo()
        requestedOrientation = previousRequestedOrientation
        ViewCompat.requestApplyInsets(binding.videoControlsOverlay)
        renderVideoFullscreenButton()
    }

    private fun renderVideoFullscreenButton() {
        if (mediaType != EditorMediaType.VIDEO) return
        binding.btnVideoFullscreen.setImageResource(
            if (isVideoFullscreen) R.drawable.ic_video_fullscreen_exit
            else R.drawable.ic_video_fullscreen
        )
        binding.btnVideoFullscreen.contentDescription = getString(
            if (isVideoFullscreen) R.string.editor_video_exit_fullscreen
            else R.string.editor_video_fullscreen
        )
    }

    private fun hideSystemBarsForVideo() {
        binding.editorRoot.fitsSystemWindows = false
        binding.editorRoot.setPadding(0, 0, 0, 0)
        binding.editorAppBar.fitsSystemWindows = false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, binding.editorRoot).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBarsAfterVideo() {
        WindowCompat.getInsetsController(window, binding.editorRoot)
            .show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding.editorRoot.fitsSystemWindows = true
        binding.editorAppBar.fitsSystemWindows = true
        ViewCompat.requestApplyInsets(binding.editorRoot)
    }

    private fun createInlineVideoLayoutParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        resources.getDimensionPixelSize(R.dimen.editor_video_height)
    )

    private fun onMediaReady(durationMs: Long, audioStreamIndex: Int?) {
        val ffmpegAudioStreamIndex = audioStreamIndex?.takeIf { it >= 0 }
            ?: waveformAudioStreamIndex
        stateModel.selectedAudioStreamIndex = ffmpegAudioStreamIndex
        if (stateModel.playbackSpeed != 1.0f) {
            playbackController.applyPlaybackSpeed(stateModel.playbackSpeed, showConfirmation = false)
        }
        if (stateModel.playbackPositionMs > 0L) {
            playbackController.seekTo(stateModel.playbackPositionMs)
        }

        val mediaFile = waveformMediaFile ?: return
        if (mediaType == EditorMediaType.VIDEO && audioStreamIndex == null) {
            waveformController.showNoAudioTrack(durationMs, subtitleEntries.toList())
        } else {
            waveformController.load(
                mediaFile,
                durationMs,
                subtitleEntries.toList(),
                ffmpegAudioStreamIndex
            )
        }
        scheduleSubtitlePreview()
    }

    private fun scheduleSubtitlePreview() {
        if (mediaType != EditorMediaType.VIDEO || !::subtitlePreviewController.isInitialized) return
        subtitlePreviewController.schedule(
            format = currentFormat,
            entries = subtitleEntries,
            sourceViewMode = isSourceViewMode,
            sourceContent = if (isSourceViewMode) sourceViewContent else originalFileContent
        )
    }

    /** 源码视图也保持 mpv 预览；防抖后只有最新文本会进入后台解析流程。 */
    private fun scheduleSourceViewPreview() {
        sourceViewPreviewJob?.cancel()
        val editGeneration = sourceViewEditGeneration
        sourceViewPreviewJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(350L)
            if (
                !isSourceViewMode ||
                suppressSourceViewChanges ||
                editGeneration != sourceViewEditGeneration
            ) {
                return@launch
            }
            val sourceSnapshot = snapshotSourceViewContentIfNeeded()
            val parsedDocument = withContext(Dispatchers.Default) {
                SubtitleParser.parseDocument(sourceSnapshot, format = currentFormat)
            }
            if (!isSourceViewMode || editGeneration != sourceViewEditGeneration) return@launch

            // 源视图编辑也必须更新波形字幕块。解析失败时保留上一次有效条目，
            // 避免用户输入时间轴中间态时字幕块瞬间全部消失。
            val canApplyEntries = parsedDocument.entries.isNotEmpty() ||
                sourceSnapshot.isBlank() ||
                !sourceContainsSubtitleMarker(sourceSnapshot)
            if (canApplyEntries) applySourceViewEntries(parsedDocument.entries)

            if (mediaType == EditorMediaType.VIDEO && ::subtitlePreviewController.isInitialized) {
                subtitlePreviewController.schedule(
                    format = currentFormat,
                    entries = parsedDocument.entries,
                    sourceViewMode = true,
                    sourceContent = sourceSnapshot
                )
            }
        }
    }

    /** 将波形拖动后的时间字段回写源视图，避免每次 MOVE 都触发逐行重建。 */
    private fun scheduleSourceViewWaveformSync(updatedSubtitles: List<SubtitleEntry>) {
        sourceViewWaveformSyncJob?.cancel()
        val sourceSyncInFlight = sourceViewHasPendingEdits || sourceViewPreviewJob?.isActive == true
        val sourceContentSnapshot = if (sourceViewHasPendingEdits) {
            // 先提交当前行块，确保波形时间回写时不会覆盖尚未进入 sourceViewContent 的文本修改。
            snapshotSourceViewContentIfNeeded()
        } else {
            sourceViewContent
        }
        if (sourceSyncInFlight) sourceViewPreviewJob?.cancel()
        val editGeneration = sourceViewEditGeneration
        val entriesSnapshot = updatedSubtitles.map { it.copy() }
        sourceViewWaveformSyncJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(40L)
            if (!isSourceViewMode || editGeneration != sourceViewEditGeneration) return@launch
            val format = currentFormat
            val entriesToSerialize = if (sourceSyncInFlight) {
                val sourceEntries = withContext(Dispatchers.Default) {
                    SubtitleParser.parseDocument(sourceContentSnapshot, format = format).entries
                }
                // 源文本在编辑器中可能刚增删了字幕。此时不把旧波形列表强行套回源文档，
                // 让新的源文本解析完成后再更新波形，避免丢失用户输入。
                if (sourceEntries.size != entriesSnapshot.size) {
                    scheduleSourceViewPreview()
                    return@launch
                }
                sourceEntries.mapIndexed { index, sourceEntry ->
                    val waveformEntry = entriesSnapshot[index]
                    sourceEntry.copy(
                        index = waveformEntry.index,
                        startTime = waveformEntry.startTime,
                        endTime = waveformEntry.endTime,
                        endTimeModified = waveformEntry.endTimeModified
                    )
                }
            } else {
                entriesSnapshot
            }
            val updatedSource = withContext(Dispatchers.Default) {
                serializeEntriesForFormat(format, entriesToSerialize)
            }
            if (!isSourceViewMode || editGeneration != sourceViewEditGeneration) return@launch
            sourceViewContent = updatedSource
            sourceViewHasPendingEdits = false
            sourceViewEditGeneration++
            setSourceViewEditorText(updatedSource, preserveScroll = true)
            updateFormatInfo()
            scheduleSubtitlePreview()
        }
    }

    private fun setupAiControllers() {
        val previewDialog = EditorTextPreviewDialog(this)
        translationController = EditorTranslationController(
            activity = this,
            scope = lifecycleScope,
            previewDialog = previewDialog,
            applyTexts = { appliedItems -> applyPreviewTexts(appliedItems, "翻译") },
            saveDraft = ::saveTranslationDraft,
            showMessage = ::showShortToast,
            subtitleFormatProvider = { currentFormat }
        )
        transcribeController = EditorTranscribeController(
            activity = this,
            scope = lifecycleScope,
            cacheDir = cacheDir,
            previewDialog = previewDialog,
            applyTexts = { appliedItems -> applyPreviewTexts(appliedItems, "转录") },
            showMessage = ::showShortToast
        )
        ttsController = EditorTtsController(
            activity = this,
            rootView = binding.root,
            showMessage = ::showShortToast
        )
    }

    private fun updateSelectedCountDisplay() {
        val count = subtitleAdapter.getSelectedCount()
        if (count > 0) {
            val formatName = getFormatDisplayName(currentFormat)
            supportActionBar?.subtitle = "$formatName | ${subtitleEntries.size} 条 | 选中：$count"
        } else {
            supportActionBar?.subtitle = currentFormatInfo
        }
        binding.toolbar.navigationIcon = ContextCompat.getDrawable(
            this,
            if (count > 0) R.drawable.ic_close else R.drawable.ic_back
        )
        binding.toolbar.navigationContentDescription =
            if (count > 0) "取消选择" else "返回"
        invalidateOptionsMenu()
    }

    private fun setDocumentTitle(title: String) {
        stateModel.documentTitle = title
        supportActionBar?.title = title
    }

    private fun restoreDocumentState() {
        setDocumentTitle(stateModel.documentTitle)
        if (isSourceViewMode) {
            binding.rvSubtitles.visibility = View.GONE
            binding.sourceViewContainer.visibility = View.VISIBLE
            val wasUnsaved = hasUnsavedChanges
            configureSourceViewEditor(sourceViewContent)
            hasUnsavedChanges = wasUnsaved
            binding.etSourceView.post { binding.etSourceView.scrollToDocumentY(savedScrollPosition) }
        } else {
            binding.sourceViewContainer.visibility = View.GONE
            binding.rvSubtitles.visibility = View.VISIBLE
            submitSubtitleList(
                refreshAll = true,
                selectedIndices = stateModel.selectedIndices,
                updateFormat = false,
                syncWaveform = false
            ) {
                val layoutManager = binding.rvSubtitles.layoutManager as LinearLayoutManager
                val position = savedFirstVisibleItemPosition
                if (position in subtitleEntries.indices) {
                    layoutManager.scrollToPositionWithOffset(position, savedScrollPosition)
                }
            }
        }
        updateFormatInfo()
        syncWaveformSubtitles()
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

        setDocumentTitle(file.name)
        // 使用用户设置的默认编码
        val settingsManager = SettingsManager.getInstance(this)
        currentCharset = settingsManager.getDefaultEncoding()

        val content = readFileOrNull(file, "读取文件失败") ?: return
        parseContent(content, file.name)
        hasUnsavedChanges = false
        isNewFile = false
    }

    private fun openFileFromUri(uri: Uri) {
        try {
            val content = FileUtils.readUri(this, uri)
            // 获取文件名并更新显示
            val fileName = getFileNameFromUri(uri)
            stateModel.openUriSubtitleDocument(uri.toString(), fileName)
            setDocumentTitle(stateModel.documentTitle)
            takePersistableWritePermission(uri)
            parseContent(content, fileName)
            hasUnsavedChanges = false
            com.subtitleedit.util.OverwritingToast.makeText(this, "文件已打开：$fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "打开文件失败：${e.message}", Toast.LENGTH_SHORT).show()
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
        val targetFile = if (mediaType.hasPlayableMedia) subtitleFile else currentFile
        if (targetFile == null || !targetFile.exists()) {
            showShortToast("当前文件无法重新加载编码，请通过「打开」功能重新选择文件")
            return
        }

        val content = readFileOrNull(targetFile, "切换编码失败") ?: return
        parseContent(content, targetFile.name)
        hasUnsavedChanges = false
        showShortToast("已切换编码为：${FileUtils.SUPPORTED_ENCODINGS.find { it.charset == currentCharset }?.displayName}")
    }
    
    private fun parseContent(content: String, fileName: String? = null) {
        val document = SubtitleParser.parseDocument(content, fileName)
        currentFormat = document.format
        
        // 始终保存原始文件内容
        originalFileContent = content
        
        if (currentFormat.isSourceOnly) {
            // Source-only documents still need a block model for waveform playback and for
            // keeping source rows aligned with the list view.  The source editor displays
            // the untouched full text, while these parsed entries provide the corresponding
            // subtitle blocks.
            replaceSubtitleEntries(document.entries)
            sourceViewContent = originalFileContent
            enterSourceViewMode()
        } else {
            replaceSubtitleEntries(document.entries)
            exitSourceViewMode()
        }
        
        updateFormatInfo()
        
        if (subtitleEntries.isEmpty() && !isSourceViewMode) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "未找到字幕内容", Toast.LENGTH_SHORT).show()
        }
        
        // 同步字幕到波形视图（仅音频模式有效）
        syncWaveformSubtitles()
        scheduleSubtitlePreview()
        stateModel.documentLoaded = true
    }
    
    /**
     * 进入源码视图。
     *
     * 大型字幕不使用淡入淡出：动画期间 RecyclerView、TextView 布局和 mpv 预览会短暂
     * 并存，正是日志中 native heap 峰值与 ANR 的高风险窗口。
     */
    private fun enterSourceViewMode(onFinished: (() -> Unit)? = null) {
        isSourceViewMode = true
        sourceViewWaveformSyncJob?.cancel()
        sourceViewHasPendingEdits = false
        sourceViewEditGeneration++
        if (::searchController.isInitialized) searchController.clearSourceWorkForTransition()
        
        // 保存 RecyclerView 的滚动位置
        val layoutManager = binding.rvSubtitles.layoutManager as LinearLayoutManager
        savedFirstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
        val firstView = layoutManager.findViewByPosition(savedFirstVisibleItemPosition)
        savedScrollPosition = firstView?.top ?: 0
        sourceViewEntryCount = subtitleEntries.size

        binding.rvSubtitles.visibility = View.GONE
        binding.sourceViewContainer.visibility = View.GONE

        configureSourceViewEditor(sourceViewContent)

        binding.rvSubtitles.animate().cancel()
        binding.sourceViewContainer.animate().cancel()
        binding.rvSubtitles.alpha = 1f
        binding.sourceViewContainer.alpha = 1f
        binding.sourceViewContainer.visibility = View.VISIBLE
        binding.etSourceView.post {
            if (savedFirstVisibleItemPosition >= 0 &&
                savedFirstVisibleItemPosition < sourceViewEntryCount
            ) {
                val estimatedScroll = savedFirstVisibleItemPosition * 80 - savedScrollPosition
                binding.etSourceView.scrollToDocumentY(estimatedScroll.coerceAtLeast(0))
            }
            onFinished?.invoke()
        }
        
        updateSourceViewMenuTitle()
    }
    
    /** 退出源码视图。源行 RecyclerView 已由调用方先禁用，避免它与列表同时编辑。 */
    private fun exitSourceViewMode(
        sourceScrollPosition: Int? = null,
        onFinished: (() -> Unit)? = null
    ) {
        isSourceViewMode = false
        if (::searchController.isInitialized) searchController.clearSourceWorkForTransition()
        
        // 保存 ScrollView 的滚动位置
        savedScrollPosition = sourceScrollPosition ?: binding.etSourceView.getDocumentScrollOffset()
        
        // 刷新字幕列表
        submitSubtitleList(
            refreshAll = true,
            updateFormat = false,
            // 源视图解析会创建新的 SubtitleEntry 对象；波形必须换绑到这批新对象，
            // 否则拖拽会继续修改切换前的旧条目，甚至把旧数据写回列表。
            syncWaveform = true,
            schedulePreview = false
        )

        binding.rvSubtitles.animate().cancel()
        binding.sourceViewContainer.animate().cancel()
        binding.sourceViewContainer.alpha = 1f
        binding.sourceViewContainer.visibility = View.GONE
        binding.rvSubtitles.alpha = 1f
        binding.rvSubtitles.visibility = View.VISIBLE
        binding.rvSubtitles.post {
            val layoutManager = binding.rvSubtitles.layoutManager as LinearLayoutManager
            if (subtitleEntries.isNotEmpty()) {
                val estimatedPosition = savedScrollPosition / 80
                layoutManager.scrollToPositionWithOffset(
                    estimatedPosition.coerceIn(0, subtitleEntries.lastIndex),
                    0
                )
            }
            onFinished?.invoke()
        }
        
        updateSourceViewMenuTitle()
    }
    
    /**
     * 切换源视图模式
     */
    private fun toggleSourceView() {
        if (sourceViewTransitionJob?.isActive == true) {
            showShortToast("正在切换视图，请稍候")
            return
        }
        if (currentFormat.isSourceOnly) {
            showShortToast("${getFormatDisplayName(currentFormat)} 文件使用源码视图编辑")
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
                    saveFile(SaveContinuation.ENTER_SOURCE_VIEW)
                }
                .setNeutralButton("直接切换（丢弃更改）") { _, _ ->
                    reloadAndEnterSourceView(forceDiskReload = true)
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
    private fun reloadAndEnterSourceView(forceDiskReload: Boolean = false) {
        sourceViewTransitionJob?.cancel()
        sourceViewTransitionJob = lifecycleScope.launch {
            try {
                sourceViewPreviewJob?.cancelAndJoin()
                val file = getCurrentSubtitleFile()
                val freshContent = when {
                    !forceDiskReload && originalFileContent.isNotEmpty() -> originalFileContent
                    file != null && file.exists() -> withContext(Dispatchers.IO) {
                        FileUtils.readFile(file, currentCharset)
                    }
                    else -> withContext(Dispatchers.Default) {
                        serializeEntriesForFormat(currentFormat)
                    }
                }

                originalFileContent = freshContent
                sourceViewContent = freshContent
                if (file == null || !file.exists()) {
                    showShortToast("文件尚未保存，已从当前列表生成源视图内容")
                }
                enterSourceViewMode {
                    sourceViewTransitionJob = null
                    // 用完整源码内容重新建立 mpv 字幕轨。
                    scheduleSubtitlePreview()
                    showShortToast("已切换到源视图")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sourceViewTransitionJob = null
                showShortToast("读取文件失败：${e.message}")
            }
        }
    }

    /**
     * 源视图 → 列表视图：解析源视图中当前编辑的内容
     */
    private fun doExitSourceView() {
        if (sourceViewTransitionJob?.isActive == true) return
        sourceViewWaveformSyncJob?.cancel()
        val sourceScrollPosition = binding.etSourceView.getDocumentScrollOffset()
        val editedContent = snapshotSourceViewContentIfNeeded()
        val changedFromSavedContent = editedContent != originalFileContent
        // 先禁用源行编辑，再在后台构建字幕条目列表，避免切换期间两套编辑状态并存。
        binding.etSourceView.setDocumentEnabled(false)
        setSourceViewEditorText("")
        showShortToast("正在解析字幕…")
        sourceViewTransitionJob = lifecycleScope.launch {
            try {
                sourceViewPreviewJob?.cancelAndJoin()
                val document = withContext(Dispatchers.Default) {
                    SubtitleParser.parseDocument(editedContent, format = currentFormat)
                }
                replaceSubtitleEntries(document.entries)
                originalFileContent = editedContent
                sourceViewContent = editedContent
                if (changedFromSavedContent) {
                    hasUnsavedChanges = true
                }
                exitSourceViewMode(sourceScrollPosition) {
                    binding.etSourceView.setDocumentEnabled(true)
                    sourceViewTransitionJob = null
                    updateFormatInfo()
                    // 等源码 Editable、解析临时对象和列表提交完成一个帧周期后，
                    // 再重建 mpv 字幕轨，避免切换瞬间额外复制所有条目。
                    sourceViewPreviewJob = lifecycleScope.launch {
                        kotlinx.coroutines.delay(350L)
                        if (!isSourceViewMode) scheduleSubtitlePreview()
                    }
                    showShortToast("已切换到列表视图")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                configureSourceViewEditor(editedContent)
                binding.etSourceView.setDocumentEnabled(true)
                sourceViewTransitionJob = null
                showShortToast("解析失败：${e.message}")
            }
        }
    }

    private fun setSourceViewEditorText(content: String, preserveScroll: Boolean = false) {
        suppressSourceViewChanges = true
        try {
            binding.etSourceView.setDocumentText(content, preserveScroll)
        } finally {
            suppressSourceViewChanges = false
        }
    }

    private fun configureSourceViewEditor(content: String) {
        setSourceViewEditorText(content)
        binding.etSourceView.setDocumentEnabled(true)
    }

    /** 搜索替换直接重写完整源码内容。 */
    private fun replaceSourceViewContent(content: String) {
        setSourceViewEditorText(content)
        sourceViewContent = content
        hasUnsavedChanges = true
        sourceViewHasPendingEdits = true
        sourceViewEditGeneration++
        updateFormatInfo()
        scheduleSourceViewPreview()
    }

    private fun snapshotSourceViewContentIfNeeded(): String {
        if (!isSourceViewMode || !sourceViewHasPendingEdits) return sourceViewContent
        val visibleContent = binding.etSourceView.getDocumentText()
        val snapshot = visibleContent
        sourceViewContent = snapshot
        // originalFileContent 始终保留保存时的基线，用于准确判断“编辑后又恢复原文”的情况。
        sourceViewHasPendingEdits = false
        return snapshot
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
                com.subtitleedit.util.OverwritingToast.makeText(this, "已加载草稿：$draftFileName", Toast.LENGTH_SHORT).show()
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
        val hasClipboard = clipboardTexts.isNotEmpty()
        
        val regularActions = mutableListOf<Pair<String, () -> Unit>>()
        if (currentFormat == SubtitleParser.SubtitleFormat.VTT) {
            regularActions.add("WebVTT Cue 属性" to { showWebVttCueDialog(position) })
        }
        regularActions.add("时间偏移" to { showOffsetDialog(position) })
        if (hasClipboard) {
            regularActions.add("向前粘贴 (${clipboardTexts.size}项)" to {
                insertSubtitle(after = false, refPosition = position, pasteAfterInsert = true)
            })
        }
        regularActions.add("向前插入" to { insertSubtitle(false, position) })
        if (hasClipboard) {
            regularActions.add("向后粘贴 (${clipboardTexts.size}项)" to {
                insertSubtitle(after = true, refPosition = position, pasteAfterInsert = true)
            })
        }
        regularActions.add("向后插入" to { insertSubtitle(true, position) })
        regularActions.add("复制" to { copySingle(position) })
        regularActions.add("剪切 (粘贴后删除)" to { cutSingle(position) })
        regularActions.add(
            (if (hasClipboard) "粘贴 (${clipboardTexts.size}项)[当前行]" else "粘贴") to {
                if (hasClipboard) pasteToPosition(position) else ensureClipboardNotEmpty()
            }
        )
        regularActions.add("删除" to { deleteSingleSubtitle(position) })

        val itemsList = mutableListOf<String>()
        if (hasSelection) {
            itemsList.add("对勾选字幕操作 (${selectedCount}项)")
        }
        itemsList.addAll(regularActions.map { it.first })
        
        val items = itemsList.toTypedArray()
        
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                if (hasSelection && which == 0) {
                    // 用户选择了"只对勾选字幕生效"，显示针对选中项的操作菜单
                    showSelectionContextMenu(hasClipboard)
                } else {
                    val actualWhich = if (hasSelection) which - 1 else which
                    regularActions.getOrNull(actualWhich)?.second?.invoke()
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
            itemsList.add("粘贴 (${clipboardTexts.size}项)")
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
            clipboardTexts = listOf(subtitleEntries[position].text)
            cutPasteController.clear()
            com.subtitleedit.util.OverwritingToast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 剪切单个字幕（长按的字幕）
     */
    private fun cutSingle(position: Int) {
        if (isSourceViewMode) return
        
        if (position >= 0 && position < subtitleEntries.size) {
            // 先保存到剪贴板
            clipboardTexts = listOf(subtitleEntries[position].text)
            cutPasteController.markSingleCut(position)
            com.subtitleedit.util.OverwritingToast.makeText(this, "已剪切", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 剪切选中的字幕
     */
    private fun cutSelected() {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要剪切的字幕") ?: return
        
        clipboardTexts = selectedEntries.map { it.first.text }
        cutPasteController.markMultiCut(selectedEntries.map { it.second })
        com.subtitleedit.util.OverwritingToast.makeText(this, "已剪切 ${clipboardTexts.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 执行剪切删除操作（在粘贴后调用）
     */
    private fun performCutDelete() {
        if (!cutPasteController.hasPendingCut()) return

        val deletedIndices = cutPasteController.snapshotDeletedIndices()
        val sortedPositions = cutPasteController.consumeDeletedIndicesDesc()
        sortedPositions.forEach { position ->
            if (position < subtitleEntries.size) {
                subtitleEntries.removeAt(position)
            }
        }
        syncAfterDelete(deletedIndices)
    }
    
    /**
     * 粘贴到指定位置（单行替换）
     */
    private fun pasteToPosition(position: Int) {
        if (!ensureListMode()) return
        
        if (!ensureClipboardNotEmpty()) return

        if (position >= 0 && position < subtitleEntries.size) {
            val targetSnapshot = SubtitleEntryOps.deepCopy(subtitleEntries[position])
            var targetPosition = position
            // 如果是剪切模式，先删除原字幕
            if (cutPasteController.hasPendingCut()) {
                targetPosition = cutPasteController.adjustPastePositionAfterCut(position)
                performCutDelete()
            }

            if (subtitleEntries.isEmpty()) {
                subtitleEntries.add(targetSnapshot)
                targetPosition = 0
            }
            targetPosition = targetPosition.coerceIn(0, subtitleEntries.lastIndex)

            val pasteResult = SubtitlePasteOps.pasteAtPosition(
                entries = subtitleEntries,
                position = targetPosition,
                clipboardTexts = clipboardTexts
            )
            if (pasteResult.structureChanged) {
                submitSubtitleList(refreshAll = true, markChanged = true)
                com.subtitleedit.util.OverwritingToast.makeText(this, "已粘贴 ${clipboardTexts.size} 项", Toast.LENGTH_SHORT).show()
            } else {
                notifyEntriesChanged(pasteResult.affectedPositions)
                com.subtitleedit.util.OverwritingToast.makeText(this, "已粘贴", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 删除单个字幕（长按的字幕）
     */
    private fun deleteSingleSubtitle(position: Int) {
        if (!ensureListMode()) return
        
        if (position >= 0 && position < subtitleEntries.size) {
            showDeleteConfirm("确定要删除此字幕吗？") {
                    subtitleEntries.removeAt(position)
                    syncAfterDelete(setOf(position))
                    com.subtitleedit.util.OverwritingToast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 插入字幕到指定位置
     */
    private fun insertSubtitle(
        after: Boolean,
        refPosition: Int,
        pasteAfterInsert: Boolean = false
    ) {
        if (!ensureListMode()) return
        if (refPosition !in subtitleEntries.indices) return
        if (pasteAfterInsert && !ensureClipboardNotEmpty()) return

        // 剪切粘贴会删除来源行，先保留参考行时间并修正插入位置。
        val refEntry = SubtitleEntryOps.deepCopy(subtitleEntries[refPosition])
        var insertPosition = if (after) refPosition + 1 else refPosition
        if (pasteAfterInsert && cutPasteController.hasPendingCut()) {
            insertPosition = cutPasteController.adjustPastePositionAfterCut(insertPosition)
            performCutDelete()
        }
        insertPosition = insertPosition.coerceIn(0, subtitleEntries.size)

        val insertedEntries = SubtitleEntryOps.createInsertedEntries(
            after = after,
            reference = refEntry,
            previous = subtitleEntries.getOrNull(insertPosition - 1),
            next = subtitleEntries.getOrNull(insertPosition),
            texts = if (pasteAfterInsert) clipboardTexts else listOf("新字幕")
        )
        insertedEntries.forEachIndexed { index, entry ->
            entry.index = insertPosition + index + 1
        }
        subtitleEntries.addAll(insertPosition, insertedEntries)
        submitSubtitleList(
            refreshAll = true,
            syncWaveform = false,
            markChanged = true
        ) {
            subtitleAdapter.syncSelectionWithCurrentList()
            updateSelectedCountDisplay()
        }
        setWaveformSubtitlesKeepSelection(insertPosition)
        val message = if (pasteAfterInsert) {
            "已${if (after) "向后" else "向前"}粘贴 ${clipboardTexts.size} 项"
        } else {
            "已插入新字幕"
        }
        com.subtitleedit.util.OverwritingToast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        submitSubtitleList(
            refreshAll = true,
            syncWaveform = false,
            markChanged = true
        )
        setWaveformSubtitlesKeepSelection(insertPos)
        com.subtitleedit.util.OverwritingToast.makeText(this, "已插入新字幕", Toast.LENGTH_SHORT).show()
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
        SubtitleEntryOps.applyOffsetAll(selectedEntryObjects, offsetMs)
        
        notifyEntriesChanged(selectedEntries.map { it.second })
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

    /** Subtitle Edit 将 cue identifier/settings 作为 WebVTT 的格式字段单独编辑。 */
    private fun showWebVttCueDialog(position: Int) {
        if (!ensureListMode() || currentFormat != SubtitleParser.SubtitleFormat.VTT) return
        val entry = subtitleEntries.getOrNull(position) ?: return
        val layout = createDialogInputContainer()
        val identifierInput = EditText(this).apply {
            hint = "Cue identifier（可选）"
            setText(entry.cueIdentifier)
            isSingleLine = true
        }
        val settingsInput = EditText(this).apply {
            hint = "例如：line:90% position:50% align:start"
            setText(entry.cueSettings)
            isSingleLine = true
        }
        layout.addView(TextView(this).apply { text = "Cue identifier" })
        layout.addView(identifierInput)
        layout.addView(TextView(this).apply {
            text = "Cue settings（line / position / size / align / vertical / region）"
        })
        layout.addView(settingsInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("WebVTT Cue 属性")
            .setView(layout)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val identifier = identifierInput.text.toString().trim()
                val settings = settingsInput.text.toString().trim()
                val error = validateWebVttCueProperties(identifier, settings)
                if (error != null) {
                    showShortToast(error)
                    return@setOnClickListener
                }
                entry.cueIdentifier = identifier
                entry.cueSettings = settings
                onEntryUpdated(position, "WebVTT Cue 属性已更新")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun validateWebVttCueProperties(identifier: String, settings: String): String? {
        if (identifier.contains('\n') || identifier.contains("-->")) {
            return "Cue identifier 不能包含换行或 -->"
        }
        if (settings.contains('\n') || settings.contains("-->")) {
            return "Cue settings 不能包含换行或 -->"
        }
        val allowedKeys = setOf("vertical", "line", "position", "size", "align", "region")
        val invalid = settings.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .firstOrNull { token ->
                val separator = token.indexOf(':')
                separator <= 0 || token.substring(0, separator).lowercase() !in allowedKeys
            }
        return invalid?.let { "无法识别的 Cue setting：$it" }
    }
    
    /**
     * 复制选中的字幕（支持多行）
     */
    private fun copySelected() {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要复制的字幕") ?: return
        
        clipboardTexts = selectedEntries.map { it.first.text }
        cutPasteController.clear()
        com.subtitleedit.util.OverwritingToast.makeText(this, "已复制 ${clipboardTexts.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 粘贴到选中的位置
     */
    private fun pasteToSelected() {
        if (!ensureListMode()) return
        
        if (!ensureClipboardNotEmpty()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要粘贴到的字幕") ?: return

        val selectedPositionsBeforeCut = selectedEntries.map { it.second }.sorted()
        if (clipboardTexts.size < selectedPositionsBeforeCut.size) {
            showShortToast("剪贴板行数不足：剪贴板 ${clipboardTexts.size} 行，当前选中 ${selectedPositionsBeforeCut.size} 行")
            return
        }
        var selectedPositions = selectedPositionsBeforeCut

        // 如果是剪切模式，先删除原字幕，并同步调整目标选中位置
        if (cutPasteController.hasPendingCut()) {
            val deletedIndices = cutPasteController.snapshotDeletedIndices()
            if (selectedPositionsBeforeCut.any { it in deletedIndices }) {
                showShortToast("剪切来源不能同时作为粘贴目标")
                return
            }
            selectedPositions = selectedPositionsBeforeCut
                .map { pos -> pos - deletedIndices.count { it < pos } }
                .filter { it >= 0 }
            performCutDelete()
        }

        if (selectedPositions.isEmpty()) {
            showShortToast("没有可粘贴到的目标位置")
            return
        }

        val pasteResult = SubtitlePasteOps.pasteToSelection(
            entries = subtitleEntries,
            selectedPositions = selectedPositions,
            clipboardTexts = clipboardTexts
        )

        submitSubtitleList(
            refreshAll = true,
            selectedIndices = pasteResult.affectedPositions,
            markChanged = true
        )
        com.subtitleedit.util.OverwritingToast.makeText(this, "已粘贴 ${clipboardTexts.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    private fun markAsChanged() {
        hasUnsavedChanges = true
    }

    private fun onEntryUpdated(position: Int, message: String = "已更新") {
        notifyEntriesChanged(listOf(position))
        showShortToast(message)
    }

    private fun notifyEntriesChanged(
        positions: Iterable<Int>,
        includeNeighbors: Boolean = true,
        syncWaveform: Boolean = true,
        markChanged: Boolean = true
    ) {
        val positionList = positions.toList()
        if (positionList.size > BULK_NOTIFY_THRESHOLD) {
            // Bulk translation/replace operations must not enqueue one RecyclerView update per row.
            subtitleAdapter.refreshAllItems()
        } else if (includeNeighbors) {
            notifyPositionsWithNeighbors(positionList)
        } else {
            positionList
                .filter { it in subtitleEntries.indices }
                .distinct()
                .sorted()
                .forEach { subtitleAdapter.notifyItemChanged(it) }
        }
        if (syncWaveform) syncWaveformSubtitles()
        if (markChanged) markAsChanged()
        if (::playbackController.isInitialized) playbackController.invalidateHighlightCache()
        if (::searchController.isInitialized) searchController.onDocumentChanged()
        scheduleSubtitlePreview()
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
        if (allAffected.size > BULK_NOTIFY_THRESHOLD) {
            subtitleAdapter.refreshAllItems()
        } else {
            allAffected.sorted().forEach { subtitleAdapter.notifyItemChanged(it) }
        }
    }

    private fun syncWaveformSubtitles(preserveSelection: Boolean = false) {
        if (preserveSelection) {
            waveformController.setSubtitlesPreserveSelection(subtitleEntries.toList())
        } else {
            waveformController.setSubtitles(subtitleEntries.toList())
        }
    }

    private fun setWaveformSubtitlesKeepSelection(selectedIndex: Int) {
        waveformController.setSubtitlesKeepSelection(subtitleEntries.toList(), selectedIndex)
    }

    private fun submitSubtitleList(
        refreshAll: Boolean = false,
        selectedIndices: Set<Int>? = null,
        clearSelection: Boolean = false,
        updateFormat: Boolean = true,
        syncWaveform: Boolean = true,
        markChanged: Boolean = false,
        schedulePreview: Boolean = true,
        afterSubmit: (() -> Unit)? = null
    ) {
        renumberEntries(force = refreshAll)
        subtitleAdapter.submitList(subtitleEntries.toList()) {
            if (clearSelection) {
                subtitleAdapter.clearSelection()
            }
            selectedIndices?.let { subtitleAdapter.setSelectionByIndices(it) }
            if (refreshAll) {
                subtitleAdapter.refreshAllItems()
            }
            updateSelectedCountDisplay()
            afterSubmit?.invoke()
        }
        if (updateFormat) updateFormatInfo()
        if (syncWaveform) syncWaveformSubtitles()
        if (markChanged) markAsChanged()
        if (::playbackController.isInitialized) playbackController.invalidateHighlightCache()
        if (::searchController.isInitialized) searchController.onDocumentChanged()
        if (schedulePreview) scheduleSubtitlePreview()
    }
    
    private fun newFile() {
        runAfterUnsavedChangesConfirmed(
            message = "当前文件有未保存的更改，确定要新建吗？",
            action = ::doNewFile
        )
    }
    
    private fun doNewFile() {
        stateModel.startNewSubtitleDocument()
        clearSubtitleEntries()
        // 添加默认字幕行：3秒时长，文本"请输入文本"
        subtitleEntries.add(SubtitleEntry(
            index = 1,
            startTime = 0L,
            endTime = 3000L,
            text = "请输入文本"
        ))
        sourceViewContent = ""
        originalFileContent = ""
        currentCharset = StandardCharsets.UTF_8
        currentFormat = SubtitleParser.SubtitleFormat.SRT
        isSourceViewMode = false
        binding.rvSubtitles.visibility = android.view.View.VISIBLE
        binding.sourceViewContainer.visibility = android.view.View.GONE
        submitSubtitleList(refreshAll = true, clearSelection = true, syncWaveform = true)
        setDocumentTitle(stateModel.documentTitle)
        currentFormatInfo = "格式：SRT | 条目数：${subtitleEntries.size}"
        supportActionBar?.subtitle = currentFormatInfo
        hasUnsavedChanges = false
        stateModel.documentLoaded = true
        com.subtitleedit.util.OverwritingToast.makeText(this, "已新建文件", Toast.LENGTH_SHORT).show()
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
    
    private fun saveFile(continuation: SaveContinuation = SaveContinuation.NONE) {
        stateModel.saveCoordinator.begin(continuation)
        stateModel.documentUri?.let { uriString ->
            val completed = stateModel.saveCoordinator.complete(saveFileToUri(Uri.parse(uriString)))
            executeSaveContinuation(completed)
            return
        }

        val targetFile = if (mediaType.hasPlayableMedia) {
            subtitleFile
        } else {
            currentFile
        }
        
        if (isNewFile || targetFile == null) {
            launchSaveFilePicker()
            return
        }

        val completed = stateModel.saveCoordinator.complete(saveWithContent { content ->
            FileUtils.writeFile(targetFile, content, currentCharset)
        })
        executeSaveContinuation(completed)
    }
    
    private fun saveFileAs() {
        stateModel.saveCoordinator.begin(SaveContinuation.NONE)
        launchSaveFilePicker()
    }

    private fun launchSaveFilePicker() {
        val formatExtension = getFormatExtension(currentFormat)
        saveFileLauncher.launch("subtitle.$formatExtension")
    }
    
    private fun saveFileToUri(uri: Uri): Boolean {
        val saved = saveWithContent { content ->
            EditorDocumentWriter.write(content, currentCharset) {
                contentResolver.openOutputStream(uri)
            }
        }
        if (saved) {
            val fileName = getFileNameFromUri(uri)
            stateModel.saveUriSubtitleDocument(uri.toString(), fileName)
            takePersistableWritePermission(uri)
            setDocumentTitle(stateModel.documentTitle)
        }
        return saved
    }

    private fun takePersistableWritePermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun executeSaveContinuation(continuation: SaveContinuation) {
        when (continuation) {
            SaveContinuation.NONE -> Unit
            SaveContinuation.FINISH -> finish()
            SaveContinuation.ENTER_SOURCE_VIEW -> reloadAndEnterSourceView()
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
    
    /**
     * 保存草稿
     */
    private fun saveDraft() {
        val content = getCurrentEditableContent(requireNonEmptyList = true) ?: return
        
        val fileName = currentFile?.name ?: "未命名"
        val savedFileName = DraftManager.saveDraft(this, fileName, content)
        com.subtitleedit.util.OverwritingToast.makeText(this, "草稿已保存：$savedFileName", Toast.LENGTH_LONG).show()
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

    private fun showMergeSubtitlesDialog() {
        if (!ensureListMode()) return
        if (subtitleEntries.size < 2) {
            showShortToast(getString(R.string.merge_subtitles_requires_entries))
            return
        }

        val layout = createDialogInputContainer()
        val (gapRow, gapInput) = createLabeledNumberInputRow(
            hint = getString(R.string.merge_subtitles_gap_hint),
            label = getString(R.string.merge_subtitles_gap_unit),
            defaultValue = "",
            allowSigned = false
        )
        layout.addView(gapRow)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.menu_merge_subtitles)
            .setView(layout)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val maxGapMs = gapInput.text.toString().trim().toLongOrNull()
                if (maxGapMs == null || maxGapMs < 0L) {
                    gapInput.error = getString(R.string.merge_subtitles_invalid_gap)
                    return@setOnClickListener
                }
                mergeSubtitles(maxGapMs)
                dialog.dismiss()
            }
        }
        dialog.show()
        gapInput.requestFocus()
    }

    private fun mergeSubtitles(maxGapMs: Long) {
        val mergedEntries = SubtitleEntryOps.mergeAdjacent(subtitleEntries, maxGapMs)
        val removedCount = subtitleEntries.size - mergedEntries.size
        if (removedCount == 0) {
            showShortToast(getString(R.string.merge_subtitles_no_match))
            return
        }

        cutPasteController.clear()
        replaceSubtitleEntries(mergedEntries)
        submitSubtitleList(
            refreshAll = true,
            clearSelection = true,
            markChanged = true
        )
        showShortToast(getString(R.string.merge_subtitles_result, removedCount))
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
                SubtitleEntryOps.applyOffset(entry, offsetMs)
                
                notifyEntriesChanged(listOf(longClickPos))
            }
            // 没有长按位置但有选中的字幕，对选中的字幕应用偏移
            subtitleAdapter.getSelectedCount() > 0 -> {
                val selectedEntries = subtitleAdapter.getSelectedEntries()
                SubtitleEntryOps.applyOffsetAll(selectedEntries.map { it.first }, offsetMs)
                
                notifyEntriesChanged(selectedEntries.map { it.second })
            }
            // 都没有，对所有字幕应用偏移
            else -> {
                SubtitleEntryOps.applyOffsetAll(subtitleEntries, offsetMs)
                
                submitSubtitleList(refreshAll = true, markChanged = true)
            }
        }
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
                syncAfterDelete(deletedIndices)
                com.subtitleedit.util.OverwritingToast.makeText(this, "已删除 ${selectedEntries.size} 条字幕", Toast.LENGTH_SHORT).show()
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

        submitSubtitleList(
            refreshAll = true,
            selectedIndices = remainingSelectedIndices,
            syncWaveform = false,
            markChanged = true
        ) {
            // 刷新被删除行的前一行（消除时间冲突标红）
            deletedIndices.forEach { deletedIdx ->
                val offset = deletedIndices.count { it < deletedIdx }
                val prevIdx = (deletedIdx - offset) - 1
                if (prevIdx >= 0 && prevIdx < subtitleEntries.size) {
                    subtitleAdapter.notifyItemChanged(prevIdx)
                }
            }
        }
        // 同步字幕到波形视图，保持选中状态
        waveformController.setSubtitlesAfterDelete(subtitleEntries.toList(), deletedIndices)
    }

    /**
     * 取消所有选择的字幕
     */
    private fun cancelSelection() {
        if (!ensureListMode()) return
        
        subtitleAdapter.clearSelection()
        updateSelectedCountDisplay()
    }

    private fun selectAllSubtitles() {
        if (!ensureListMode() || subtitleEntries.isEmpty()) return

        if (subtitleAdapter.getSelectedCount() == subtitleEntries.size) {
            subtitleAdapter.setAllSelection(false)
        } else {
            subtitleAdapter.setAllSelection(true)
        }
        updateSelectedCountDisplay()
    }

    private fun selectRangeBetweenSelectedSubtitles() {
        if (!ensureListMode()) return

        val selectedPositions = subtitleAdapter.getSelectedPositions().sorted()
        if (selectedPositions.size < 2) {
            showShortToast("请先选择至少两行字幕")
            return
        }

        val start = selectedPositions.first()
        val end = selectedPositions.last()
        val range = (start..end).toSet()
        if (range.all { it in selectedPositions }) return

        subtitleAdapter.setSelectionByIndices(range)
        updateSelectedCountDisplay()
    }
    
    /**
     * 显示 AI 翻译对话框
     */
    private fun showAiTranslate() {
        if (!ensureListMode()) return
        val selectedEntries = requireSelectedEntries("请先选择要翻译的字幕") ?: return
        translationController.start(selectedEntries)
    }

    /** 对当前选中的字幕行按各自时间范围执行离线语音转录。 */
    private fun showQuickTranscribe() {
        if (!ensureListMode()) return
        val audioFile = currentFile?.takeIf { mediaType.hasPlayableMedia } ?: run {
            showShortToast("仅在打开音频或视频文件时可快速转录")
            return
        }
        val selectedEntries = requireSelectedEntries("请先选择要转录的字幕") ?: return
        val audioCacheKey = waveformController.getAudioCacheKey(audioFile) ?: run {
            showShortToast("媒体缓存索引尚未准备完成")
            return
        }
        transcribeController.start(
            selectedEntries = selectedEntries,
            timelineEntries = subtitleEntries.toList(),
            audioFile = audioFile,
            audioCacheKey = audioCacheKey,
            audioStreamIndex = stateModel.selectedAudioStreamIndex
        )
    }

    /** 把预览对话框中勾选应用的文本写回字幕列表。 */
    private fun applyPreviewTexts(appliedItems: List<TranslationPreviewItem>, actionName: String) {
        appliedItems.forEach { item ->
            subtitleEntries.getOrNull(item.entryPosition)?.text = item.translatedText
        }
        if (appliedItems.isNotEmpty()) {
            notifyEntriesChanged(appliedItems.map { it.entryPosition }, includeNeighbors = false)
        }
        showShortToast("已应用 ${appliedItems.size} 条$actionName")
    }

    private fun saveTranslationDraft(previewItems: List<TranslationPreviewItem>) {
        val draftEntries = subtitleEntries.map { it.copy() }.toMutableList()
        previewItems.filter { it.apply }.forEach { item ->
            draftEntries.getOrNull(item.entryPosition)?.text = item.translatedText
        }
        val fileName = currentFile?.name ?: "未命名"
        val draftContent = serializeEntriesForFormat(currentFormat, draftEntries)
        val savedFileName = DraftManager.saveDraft(this, fileName, draftContent)
        showShortToast("翻译草稿已保存：$savedFileName")
    }
    
    private fun renumberEntries(force: Boolean = false) {
        val currentCount = subtitleEntries.size
        if (!force && currentCount == lastIndexedEntryCount) return
        subtitleEntries.forEachIndexed { index, entry ->
            entry.index = index + 1
        }
        lastIndexedEntryCount = currentCount
    }

    private fun replaceSubtitleEntries(entries: List<SubtitleEntry>) {
        subtitleEntries = entries.toMutableList()
        renumberEntries(force = true)
    }

    /**
     * Apply a parsed source document while preserving the list view's row objects whenever
     * the number of subtitle blocks is unchanged.  Waveform and list-view consumers can then
     * keep their existing block references, and only affected rows (plus their neighbours)
     * are rebound.  A size change still replaces the list in one operation because every row
     * after the edit has a new index.
     */
    private fun applySourceViewEntries(updatedEntries: List<SubtitleEntry>) {
        if (subtitleEntries.size != updatedEntries.size) {
            replaceSubtitleEntries(updatedEntries)
            syncWaveformSubtitles()
            return
        }

        val changedPositions = updatedEntries.indices.filter { position ->
            subtitleEntries[position] != updatedEntries[position]
        }
        changedPositions.forEach { position ->
            val target = subtitleEntries[position]
            val source = updatedEntries[position]
            target.index = source.index
            target.startTime = source.startTime
            target.endTime = source.endTime
            target.text = source.text
            target.endTimeModified = source.endTimeModified
            target.cueIdentifier = source.cueIdentifier
            target.cueSettings = source.cueSettings
        }
        renumberEntries(force = true)

        if (changedPositions.isEmpty()) {
            syncWaveformSubtitles(preserveSelection = true)
        } else if (::subtitleAdapter.isInitialized && subtitleAdapter.itemCount == subtitleEntries.size) {
            // Reuse the same payload/neighbour refresh path as list-view edits.  The
            // RecyclerView is hidden in source mode, but retaining its row references keeps
            // the two editing modes consistent when the user switches back.
            notifyEntriesChanged(
                changedPositions,
                includeNeighbors = true,
                syncWaveform = false,
                markChanged = false
            )
            syncWaveformSubtitles(preserveSelection = true)
        } else {
            syncWaveformSubtitles(preserveSelection = true)
        }
    }

    /**
     * Distinguish an empty-but-valid source document from a temporarily malformed cue.  Once
     * the last timing marker has actually been removed, the old waveform blocks must be
     * cleared; while a marker is still present we keep them until parsing succeeds.
     */
    private fun sourceContainsSubtitleMarker(content: String): Boolean {
        return when (currentFormat) {
            SubtitleParser.SubtitleFormat.SRT,
            SubtitleParser.SubtitleFormat.VTT -> content.contains("-->")
            SubtitleParser.SubtitleFormat.LRC -> Regex("\\[-?\\d{1,4}[:.]\\d{1,2}").containsMatchIn(content)
            SubtitleParser.SubtitleFormat.TXT -> content.isNotBlank()
            else -> true
        }
    }

    private fun clearSubtitleEntries() {
        subtitleEntries.clear()
        renumberEntries(force = true)
    }
    
    private fun updateFormatInfo() {
        val formatName = getFormatDisplayName(currentFormat)
        val countInfo = if (isSourceViewMode) {
            val lines = if (::binding.isInitialized) {
                binding.etSourceView.getDocumentLineCount()
            } else if (sourceViewContent.isEmpty()) {
                0
            } else {
                sourceViewContent.count { it == '\n' } + 1
            }
            "行数：$lines"
        } else {
            "条目数：${subtitleEntries.size}"
        }
        currentFormatInfo = "格式：$formatName | $countInfo"
        supportActionBar?.subtitle = currentFormatInfo
    }

    private fun getFormatDisplayName(format: SubtitleParser.SubtitleFormat): String {
        return when (format) {
            SubtitleParser.SubtitleFormat.SRT -> "SRT"
            SubtitleParser.SubtitleFormat.LRC -> "LRC"
            SubtitleParser.SubtitleFormat.TXT -> "TXT"
            SubtitleParser.SubtitleFormat.ASS -> "ASS"
            SubtitleParser.SubtitleFormat.SSA -> "SSA"
            SubtitleParser.SubtitleFormat.VTT -> "WebVTT"
            else -> "未知"
        }
    }

    private fun getFormatExtension(format: SubtitleParser.SubtitleFormat): String {
        return when (format) {
            SubtitleParser.SubtitleFormat.SRT -> "srt"
            SubtitleParser.SubtitleFormat.LRC -> "lrc"
            SubtitleParser.SubtitleFormat.TXT -> "txt"
            SubtitleParser.SubtitleFormat.ASS -> "ass"
            SubtitleParser.SubtitleFormat.SSA -> "ssa"
            SubtitleParser.SubtitleFormat.VTT -> "vtt"
            else -> "srt"
        }
    }

    private fun serializeEntriesForFormat(format: SubtitleParser.SubtitleFormat): String {
        return serializeEntriesForFormat(format, subtitleEntries)
    }

    private fun serializeEntriesForFormat(
        format: SubtitleParser.SubtitleFormat,
        entries: List<SubtitleEntry>
    ): String {
        return when (format) {
            SubtitleParser.SubtitleFormat.SRT -> SubtitleParser.toSRT(entries)
            SubtitleParser.SubtitleFormat.LRC -> SubtitleParser.toLRC(
                entries,
                SubtitleParser.parseDocument(
                    originalFileContent,
                    format = SubtitleParser.SubtitleFormat.LRC
                ).header
            )
            SubtitleParser.SubtitleFormat.TXT -> SubtitleParser.toTXT(entries)
            SubtitleParser.SubtitleFormat.ASS,
            SubtitleParser.SubtitleFormat.SSA -> sourceViewContent
            SubtitleParser.SubtitleFormat.VTT -> {
                val originalDocument = SubtitleParser.parseDocument(
                    originalFileContent,
                    format = SubtitleParser.SubtitleFormat.VTT
                )
                SubtitleParser.toVTT(
                    entries,
                    originalDocument.header.ifBlank { "WEBVTT" },
                    originalDocument.footer
                )
            }
            else -> SubtitleParser.toSRT(entries)
        }
    }

    private fun getCurrentEditableContent(requireNonEmptyList: Boolean = false): String? {
        if (isSourceViewMode) return snapshotSourceViewContentIfNeeded()
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

    private fun ensureMediaMode(): Boolean {
        if (mediaType.hasPlayableMedia) return true
        showShortToast("此功能仅在打开音频或视频文件时可用")
        return false
    }

    private fun ensureClipboardNotEmpty(): Boolean {
        if (clipboardTexts.isNotEmpty()) return true
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

    private fun getCurrentSubtitleFile(): File? {
        return if (mediaType.hasPlayableMedia) subtitleFile else currentFile
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

    private inline fun saveWithContent(writeAction: (String) -> Unit): Boolean {
        return try {
            val content = getCurrentEditableContent() ?: return false
            writeAction(content)
            originalFileContent = content
            if (isSourceViewMode) sourceViewContent = content
            hasUnsavedChanges = false
            showShortToast("保存成功")
            true
        } catch (e: Exception) {
            showShortToast("保存失败：${e.message}")
            false
        }
    }

    private fun showShortToast(message: String) {
        com.subtitleedit.util.OverwritingToast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })
    }

    private fun handleBackPressed() {
        if (isVideoFullscreen) {
            exitVideoFullscreen()
            return
        }
        if (::subtitleAdapter.isInitialized && subtitleAdapter.getSelectedCount() > 0) {
            cancelSelection()
            return
        }
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("是否保存更改？")
                .setPositiveButton("保存") { _, _ ->
                    saveFile(SaveContinuation.FINISH)
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
    
    override fun onStop() {
        // 防抖预览尚未来得及执行时，旋转/切后台可能回收源行 ViewHolder；在这里一次性
        // 提交当前源文档，避免丢失尚未刷新的内容。
        if (isSourceViewMode && sourceViewHasPendingEdits) {
            snapshotSourceViewContentIfNeeded()
        }
        if (::playbackController.isInitialized) {
            stateModel.playbackPositionMs = playbackController.currentPositionMs
            stateModel.playbackSpeed = playbackController.playbackSpeed
            playbackController.pauseForLifecycle()
        }
        if (::subtitleAdapter.isInitialized) {
            stateModel.selectedIndices = subtitleAdapter.getSelectedPositions()
            if (isSourceViewMode) {
                savedScrollPosition = binding.etSourceView.getDocumentScrollOffset()
            } else {
                val layoutManager = binding.rvSubtitles.layoutManager as? LinearLayoutManager
                val position = layoutManager?.findFirstVisibleItemPosition() ?: -1
                if (position >= 0) {
                    savedFirstVisibleItemPosition = position
                    savedScrollPosition = layoutManager?.findViewByPosition(position)?.top ?: 0
                }
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        sourceViewTransitionJob?.cancel()
        sourceViewPreviewJob?.cancel()
        sourceViewWaveformSyncJob?.cancel()
        ttsController.release()
        if (::subtitlePreviewController.isInitialized) subtitlePreviewController.release()
        audioFilePreparer.release()
        playbackController.release()
        waveformController.release()
        translationController.release()
        transcribeController.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isVideoFullscreen) hideSystemBarsForVideo()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (mediaType == EditorMediaType.VIDEO && !isVideoFullscreen) {
            binding.videoViewportContainer.layoutParams = createInlineVideoLayoutParams()
        }
    }
    
    // ==================== 媒体播放器相关方法 ====================
    
    private fun setupMediaActions() {
        if (!mediaType.hasPlayableMedia) return

        binding.btnQuickTranscribe.setOnClickListener {
            showQuickTranscribe()
        }
        binding.btnQuickTranscribe.setOnLongClickListener {
            startActivity(Intent(this, ModelSettingsActivity::class.java))
            true
        }

        binding.btnQuickTts.setOnClickListener {
            showQuickTts()
        }
        binding.btnQuickTts.setOnLongClickListener {
            startActivity(Intent(this, TtsSettingsActivity::class.java))
            true
        }
    }

    /** 使用设置中选定的系统 TTS 引擎，按字幕顺序朗读当前勾选项。 */
    private fun showQuickTts() {
        if (!ensureListMode()) return
        val selectedEntries = requireSelectedEntries("请先选择要朗读的字幕") ?: return
        val texts = selectedEntries.map { it.first.text.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) {
            showShortToast("选中的字幕没有可朗读文本")
            return
        }
        ttsController.speak(texts)
    }

    private fun loadMediaFile(subtitleFilePath: String?, restoreDocument: Boolean = false) {
        if (filePath.isEmpty() || currentFile == null) {
            showShortToast("媒体文件路径无效")
            finish()
            return
        }

        val originalFile = currentFile ?: return
        if (!originalFile.exists()) {
            showShortToast("媒体文件不存在")
            finish()
            return
        }

        setDocumentTitle(originalFile.name)

        if (mediaType == EditorMediaType.VIDEO) {
            doLoadMediaFile(
                playbackFile = originalFile,
                analysisFile = originalFile,
                subtitleFilePath = subtitleFilePath,
                restoreDocument = restoreDocument
            )
            return
        }

        val checkingDialog = android.app.AlertDialog.Builder(this)
            .setMessage(
                if (isAudioOnlyFromVideo) "正在检测视频音轨..." else "正在检测音频文件..."
            )
            .setCancelable(false)
            .create()
        checkingDialog.show()

        lifecycleScope.launch {
            val preparedAudio = try {
                audioFilePreparer.prepare(
                    originalFile,
                    inspectVideoAudioTrack = isAudioOnlyFromVideo
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                checkingDialog.dismiss()
                prepareMediaDocument(subtitleFilePath, restoreDocument)
                stateModel.documentLoaded = true
                showShortToast(error.message ?: "加载音频失败")
                return@launch
            }
            checkingDialog.dismiss()

            if (preparedAudio.wasFixed) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this@EditorActivity,
                    "检测到音频 start time 不为 0,请注意处理,已临时修复，正在加载...",
                    Toast.LENGTH_LONG
                ).show()
            }

            doLoadMediaFile(
                playbackFile = preparedAudio.playbackFile,
                analysisFile = originalFile,
                subtitleFilePath = subtitleFilePath,
                restoreDocument = restoreDocument,
                audioStreamIndex = preparedAudio.audioStreamIndex
            )
        }
    }

    private fun doLoadMediaFile(
        playbackFile: File,
        analysisFile: File,
        subtitleFilePath: String?,
        restoreDocument: Boolean,
        audioStreamIndex: Int? = null
    ) {
        waveformMediaFile = analysisFile
        waveformAudioStreamIndex = audioStreamIndex
        prepareMediaDocument(subtitleFilePath, restoreDocument)
        stateModel.documentLoaded = true
        playbackController.prepare(playbackFile)
    }

    private fun prepareMediaDocument(
        subtitleFilePath: String?,
        restoreDocument: Boolean
    ) {
        if (restoreDocument) {
            syncWaveformSubtitles()
        } else if (subtitleFilePath != null) {
            val subtitleFile = File(subtitleFilePath)
            if (subtitleFile.exists()) {
                loadSubtitleFile(subtitleFile)
            } else {
                prepareEmptyMediaDocument()
                showShortToast("未找到同名字幕文件")
            }
        } else {
            prepareEmptyMediaDocument()
        }
    }

    private fun prepareEmptyMediaDocument() {
        clearSubtitleEntries()
        currentFormat = SubtitleParser.SubtitleFormat.SRT
        isSourceViewMode = false
        sourceViewContent = ""
        originalFileContent = ""
        binding.sourceViewContainer.visibility = View.GONE
        binding.rvSubtitles.visibility = View.VISIBLE
        submitSubtitleList(refreshAll = true, syncWaveform = false)
    }

    /**
     * 加载字幕文件
     */
    private fun loadSubtitleFile(subtitleFile: File) {
        val settingsManager = SettingsManager.getInstance(this)
        currentCharset = settingsManager.getDefaultEncoding()
        
        try {
            val content = FileUtils.readFile(subtitleFile, currentCharset)
            parseContent(content, subtitleFile.name)
            hasUnsavedChanges = false
            isNewFile = false
        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "读取字幕文件失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ==================== 字幕时间控制按钮方法 ====================
    
    /**
     * 跳转到字幕的开始时间
     */
    private fun jumpToSubtitleTime(entry: SubtitleEntry) {
        if (!ensureMediaMode()) return
        
        playbackController.seekTo(entry.startTime)
        showShortToast("已跳转到 ${TimeUtils.formatForDisplay(entry.startTime)}")
    }
    
    
    
    /**
     * 将字幕的开始时间设置为当前音频进度
     */
    private fun setSubtitleTimeToCurrentPosition(entry: SubtitleEntry, position: Int) {
        if (!ensureMediaMode()) return
        
        val newStartTime = playbackController.currentPositionMs
        entry.startTime = newStartTime
        
        notifyEntriesChanged(listOf(position))
        
        if (newStartTime >= entry.endTime) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "开始时间已设置，但大于结束时间，请调整结束时间", Toast.LENGTH_LONG).show()
        } else {
            showShortToast("已将开始时间设置为 ${TimeUtils.formatForDisplay(newStartTime)}")
        }
    }
    
}

