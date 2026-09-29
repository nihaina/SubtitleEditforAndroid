package com.subtitleedit

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.subtitleedit.adapter.SubtitleAdapter
import com.subtitleedit.adapter.TranslationPreviewItem
import com.subtitleedit.ComposeDialogHost
import com.subtitleedit.repository.MediaRepository
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.editor.EditorMediaDocumentController
import com.subtitleedit.util.SubtitleFormatPolicy
import com.subtitleedit.util.SubtitleStableRange
import com.subtitleedit.util.SubtitleSerialization
import com.subtitleedit.util.SelectionRangePolicy
import com.subtitleedit.editor.EditorPlaybackController
import com.subtitleedit.editor.EditorPlaybackUiState
import com.subtitleedit.editor.EditorSearchController
import com.subtitleedit.editor.EditorSourcePreviewController
import com.subtitleedit.editor.EditorSourceLineEditController
import com.subtitleedit.editor.EditorSourceWaveformSyncController
import com.subtitleedit.editor.EditorSubtitleDialogController
import com.subtitleedit.editor.EditorConfirmationDialogController
import com.subtitleedit.editor.EditorHistoryCoordinator
import com.subtitleedit.editor.EditorFileSessionController
import com.subtitleedit.editor.EditorListOperationsController
import com.subtitleedit.editor.EditorSourceViewState
import com.subtitleedit.editor.EditorSourceViewCoordinator
import com.subtitleedit.editor.EditorSaveSessionController
import com.subtitleedit.editor.EditorListPresentationController
import com.subtitleedit.editor.EditorSubtitlePreviewController
import com.subtitleedit.editor.EditorTextPreviewDialog
import com.subtitleedit.editor.EditorTranscribeController
import com.subtitleedit.editor.EditorTranslationController
import com.subtitleedit.editor.EditorTtsController
import com.subtitleedit.editor.EditorWaveformController
import com.subtitleedit.editor.EditorMenuController
import com.subtitleedit.editor.EditorLifecycleCoordinator
import com.subtitleedit.editor.EditorCoordinator
import com.subtitleedit.editor.EditorNavigationCoordinator
import com.subtitleedit.editor.EditorStateCoordinator
import com.subtitleedit.ui.EditorScreen
import com.subtitleedit.ui.EditorPlaybackPanel
import com.subtitleedit.ui.EditorSourceEditor
import com.subtitleedit.ui.EditorSourceEditorState
import com.subtitleedit.ui.EditorSourceDocumentChange
import com.subtitleedit.ui.EditorToolbar
import com.subtitleedit.ui.EditorToolbarMenuAction
import com.subtitleedit.ui.EditorToolbarMenuGroup
import com.subtitleedit.ui.EditorWaveformActions
import com.subtitleedit.ui.EditorWaveformPanel
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.mpv.EditorMpvView
import com.subtitleedit.util.DraftManager
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.CutPasteController
import com.subtitleedit.util.SubtitlePasteOps
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleEntryOps
import com.subtitleedit.util.SubtitleTextSplitOps
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleSourceSynchronizer
import com.subtitleedit.util.TimeUtils
import com.subtitleedit.util.subtitle.SubtitleDocument
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/**
 * 字幕编辑界面
 * 支持点击编辑、长按菜单、多选、复制粘贴功能
 * 支持草稿箱功能
 * 支持源视图模式（用于 TXT 文件）
 */
class EditorActivity : AppCompatActivity() {

    /** The only native view retained by the editor: libmpv needs a SurfaceView for video output. */
    private lateinit var videoPlayerView: EditorMpvView
    private val subtitleListState = LazyListState()
    private var composeSubtitleEntries by mutableStateOf<List<SubtitleEntry>>(emptyList())
    private var composeSubtitleRevision by mutableIntStateOf(0)
    private var composeSourceViewMode by mutableStateOf(false)
    private var composeListLoading by mutableStateOf(false)
    private val sourceEditorState = EditorSourceEditorState()
    private var toolbarTitle by mutableStateOf("未命名")
    private var toolbarSubtitle by mutableStateOf("")
    private var toolbarSelectionActive by mutableStateOf(false)
    private lateinit var subtitleAdapter: SubtitleAdapter
    private val stateModel: EditorViewModel by viewModels {
        EditorViewModelFactory(
            (application as SubtitleEditApplication).dependencies.subtitleRepository
        )
    }

    private var suppressSourceViewChanges = false
    private val sourceViewState = EditorSourceViewState()
    private var sourceViewHasPendingEdits: Boolean
        get() = sourceViewState.pendingEdits
        set(value) { sourceViewState.pendingEdits = value }
    private var sourceViewTransitionJob: Job? = null
    private var sourceListParseJob: Job? = null
    private var suppressHistoryRecording = false
    private val cutPasteController = CutPasteController()

    private lateinit var sourceWaveformSyncController: EditorSourceWaveformSyncController
    private lateinit var subtitleDialogController: EditorSubtitleDialogController
    private lateinit var confirmationDialogController: EditorConfirmationDialogController
    private lateinit var historyCoordinator: EditorHistoryCoordinator
    private lateinit var sourceViewCoordinator: EditorSourceViewCoordinator
    private lateinit var fileSessionController: EditorFileSessionController
    private lateinit var saveSessionController: EditorSaveSessionController
    private val mediaDocumentController = EditorMediaDocumentController()
    private val listOperationsController = EditorListOperationsController()
    private lateinit var listPresentationController: EditorListPresentationController
    private var pendingListIndexRefreshStart: Int? = null
    private var listRenderVersion = 0L
    private var listLoadingActive = false
    private var runningDeferredListEdit = false
    private lateinit var sourceLineEditController: EditorSourceLineEditController

    private lateinit var translationController: EditorTranslationController
    private lateinit var transcribeController: EditorTranscribeController
    private lateinit var textPreviewDialog: EditorTextPreviewDialog
    private lateinit var ttsController: EditorTtsController
    private lateinit var searchController: EditorSearchController
    private lateinit var editorCoordinator: EditorCoordinator
    private lateinit var sourcePreviewController: EditorSourcePreviewController
    private lateinit var mediaRepository: MediaRepository
    private lateinit var playbackController: EditorPlaybackController
    private lateinit var waveformController: EditorWaveformController
    private lateinit var subtitlePreviewController: EditorSubtitlePreviewController
    private var longClickPosition: Int = -1
    private var waveformMediaFile: File? = null
    private var waveformAudioStreamIndex: Int? = null

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
            saveFileToUriAsync(uri)
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
        private const val BULK_NOTIFY_THRESHOLD = 200
        private const val LARGE_LIST_LOADING_THRESHOLD = 1_000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoPlayerView = EditorMpvView(this)
        mediaRepository = (application as SubtitleEditApplication).dependencies.mediaRepository(cacheDir)
        
        if (!stateModel.initialized) {
            stateModel.filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
            stateModel.mediaType = if (intent.hasExtra(EXTRA_MEDIA_TYPE)) {
                EditorMediaType.fromIntentValue(intent.getStringExtra(EXTRA_MEDIA_TYPE))
            } else if (intent.getBooleanExtra(EXTRA_IS_AUDIO_FILE, false)) {
                EditorMediaType.AUDIO
            } else {
                EditorMediaType.SUBTITLE_ONLY
            }
            stateModel.isAudioOnlyFromVideo = intent.getBooleanExtra(EXTRA_AUDIO_ONLY_FROM_VIDEO, false)
            stateModel.subtitleFilePath = intent.getStringExtra(EXTRA_SUBTITLE_FILE_PATH) ?: ""

            if (stateModel.filePath.isNotEmpty()) {
                if (stateModel.mediaType.hasPlayableMedia) {
                    stateModel.currentFile = File(stateModel.filePath)
                    if (stateModel.subtitleFilePath.isNotEmpty()) {
                        stateModel.subtitleFile = File(stateModel.subtitleFilePath)
                    }
                } else {
                    stateModel.currentFile = File(stateModel.filePath)
                }
            }
            stateModel.initialized = true
        }
        composeSourceViewMode = stateModel.isSourceViewMode
        
        setupSubtitleAdapter()
        listPresentationController = EditorListPresentationController(
            adapter = { subtitleAdapter },
            entries = { stateModel.subtitleEntries },
            selectedIds = {
                if (::subtitleAdapter.isInitialized) {
                    subtitleAdapter.getSelectedEntries().mapTo(mutableSetOf()) { it.first.stableId }
                } else emptySet()
            },
            updateSelectedCount = ::updateSelectedCountDisplay
        )
        setupSourceView()
        fileSessionController = EditorFileSessionController(this, stateModel.subtitleRepository)
        saveSessionController = EditorSaveSessionController(this, stateModel.subtitleRepository)
        setupHistoryCoordinator()
        sourceViewCoordinator = EditorSourceViewCoordinator(
            format = { stateModel.currentFormat },
            peekUndo = { stateModel.peekUndo() },
            updateLatestHistory = { content, entries -> stateModel.updateLatestSourceHistory(content, entries) },
            applyEntries = { entries -> applySourceViewEntries(entries) },
            setEntriesGeneration = { stateModel.documentState.sourceViewEntriesGeneration = stateModel.documentState.sourceViewEditGeneration },
            clearPendingEdits = { sourceViewHasPendingEdits = false }
        )
        setupSubtitleDialogController()
        confirmationDialogController = EditorConfirmationDialogController(this) { stateModel.hasUnsavedChanges }
        setupSearchController()
        setupPlaybackController()
        setupWaveformController()
        setupSubtitlePreviewController()
        setupAiControllers()
        val lifecycleCoordinator = EditorLifecycleCoordinator(
            subtitleAdapter = subtitleAdapter,
            sourcePreview = sourcePreviewController,
            sourceWaveformSync = sourceWaveformSyncController,
            subtitlePreview = if (::subtitlePreviewController.isInitialized) subtitlePreviewController else null,
            playback = playbackController,
            waveform = waveformController,
            tts = ttsController,
            translation = translationController,
            transcribe = transcribeController,
            mediaRelease = { mediaRepository.release() },
            isDocumentLoaded = { stateModel.documentLoaded },
            isSourceMode = { stateModel.isSourceViewMode },
            hasPendingSourceEdits = { sourceViewHasPendingEdits },
            snapshotSource = { snapshotSourceViewContentIfNeeded() },
            scheduleSourcePreview = { scheduleSourceViewPreview() },
            scheduleSubtitlePreview = { scheduleSubtitlePreview() },
            cancelSourceParse = {
                sourceListParseJob?.cancel()
                sourceListParseJob = null
            },
            savePlaybackState = { position, speed ->
                stateModel.playbackPositionMs = position
                stateModel.playbackSpeed = speed
            },
            saveSelectedIndices = { indices -> stateModel.selectedIndices = indices },
            saveSourceScroll = { offset -> stateModel.savedScrollPosition = offset },
            readSourceScroll = { sourceEditorState.getDocumentScrollOffset() },
            readListScroll = {
                subtitleListState.firstVisibleItemIndex to subtitleListState.firstVisibleItemScrollOffset
            },
            saveListScroll = { position, offset ->
                stateModel.savedFirstVisibleItemPosition = position
                stateModel.savedScrollPosition = offset
            }
        )
        val navigationCoordinator = EditorNavigationCoordinator(
            activity = this,
            subtitleAdapter = subtitleAdapter,
            isVideoFullscreen = { stateModel.isVideoFullscreen },
            exitVideoFullscreen = ::exitVideoFullscreen,
            cancelSelection = ::cancelSelection,
            documentState = stateModel.documentState,
            saveAndFinish = { saveFile(SaveContinuation.FINISH) },
            finishWithoutSaving = ::finish
        )
        editorCoordinator = EditorCoordinator(
            menu = EditorMenuController(this, ::handleEditorMenuAction),
            lifecycle = lifecycleCoordinator,
            navigation = navigationCoordinator,
            state = EditorStateCoordinator(
                lifecycleOwner = this,
                state = stateModel.uiState,
                effects = stateModel.effects,
                onStateChanged = { state, previous ->
                    if (previous == null ||
                        previous.isSourceViewMode != state.isSourceViewMode ||
                        previous.selectedIndices != state.selectedIndices ||
                        previous.documentLoaded != state.documentLoaded
                    ) stateModel.onEvent(EditorEvent.RequestOptionsMenuRefresh)
                },
                invalidateMenu = {},
                showMessage = ::showShortToast
            )
        )
        editorCoordinator.bindNavigation()
        editorCoordinator.bindState()
        setupToolbar()
        
        if (stateModel.documentLoaded) {
            restoreDocumentState()
            if (stateModel.mediaType.hasPlayableMedia && stateModel.filePath.isNotEmpty()) {
                loadMediaFile(stateModel.subtitleFilePath, restoreDocument = true)
            }
        } else if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            openFileFromUri(requireNotNull(intent.data))
        } else if (stateModel.filePath.isNotEmpty()) {
            if (stateModel.mediaType.hasPlayableMedia) {
                loadMediaFile(stateModel.subtitleFilePath)
            } else {
                loadFile()
            }
        }
    }

    private fun setupToolbar() {
        setContent {
            SubtitleEditComposeTheme {
                val editorUiState by stateModel.uiState.collectAsState()
                DisposableEffect(editorUiState.isVideoFullscreen) {
                    applyVideoFullscreenWindow(editorUiState.isVideoFullscreen)
                    onDispose { }
                }
                if (editorUiState.isVideoFullscreen && stateModel.mediaType == EditorMediaType.VIDEO) {
                    EditorFullscreenVideo(
                        playbackState = playbackController.uiState,
                        videoPlayerView = videoPlayerView,
                        onTogglePlayPause = playbackController::togglePlayPause,
                        onSeekStarted = playbackController::onSeekStarted,
                        onSeekProgress = playbackController::onSeekProgress,
                        onSeekFinished = playbackController::onSeekFinished,
                        onSpeedClick = { playbackController.showSpeedInputDialog() },
                        onToggleFullscreen = ::exitVideoFullscreen
                    )
                } else EditorScreen(
                    title = toolbarTitle,
                    subtitle = toolbarSubtitle,
                    isSelectionActive = toolbarSelectionActive,
                    onNavigateUp = { editorCoordinator.onNavigateUp() },
                    prepareMenu = ::buildComposeMenu,
                    searchState = searchController.uiState,
                    onQueryChange = searchController::onQueryChanged,
                    onReplacementChange = searchController::onReplacementChanged,
                    onPrevious = searchController::searchPrevious,
                    onNext = searchController::searchNext,
                    onReplace = searchController::replaceOneFromUi,
                    onReplaceAll = searchController::replaceAllFromUi,
                    onToggleMatchCase = searchController::toggleMatchCase,
                    onToggleWholeWord = searchController::toggleWholeWord,
                    onCloseSearch = searchController::hide,
                    isSourceViewMode = composeSourceViewMode,
                    listLoading = composeListLoading,
                    entries = composeSubtitleEntries,
                    contentRevision = composeSubtitleRevision,
                    listState = subtitleListState,
                    adapter = subtitleAdapter,
                    hasPlayableMedia = subtitleAdapter.hasPlayableMedia(),
                    onLongClick = { _, position -> showContextMenu(position) },
                    onTimeClick = { entry, position, isStart -> showTimeEditDialog(entry, position, isStart) },
                    onTextClick = { entry, position -> showTextEditDialog(entry, position) },
                    onJumpToTime = { entry, _ -> jumpToSubtitleTime(entry) },
                    onSetTime = { entry, position -> setSubtitleTimeToCurrentPosition(entry, position) },
                    mediaContent = {
                        val playbackState = playbackController.uiState
                        EditorPlaybackPanel(
                            state = playbackState,
                            onTogglePlayPause = playbackController::togglePlayPause,
                            onSeekStarted = playbackController::onSeekStarted,
                            onSeekProgress = playbackController::onSeekProgress,
                            onSeekFinished = playbackController::onSeekFinished,
                            onSpeedClick = { playbackController.showSpeedInputDialog() },
                            onToggleFullscreen = ::toggleVideoFullscreen,
                            isFullscreen = editorUiState.isVideoFullscreen
                        )
                        if (playbackState.mediaType == EditorMediaType.VIDEO) {
                            AndroidView(
                                factory = { videoPlayerView },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                            )
                        }
                        if (playbackState.mediaType.hasPlayableMedia) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TextButton(onClick = ::showQuickTranscribe) { Text("转录") }
                                TextButton(onClick = ::showQuickTts) { Text("朗读") }
                                TextButton(onClick = ::splitSubtitleAtPlaybackHead) { Text("切分") }
                            }
                        }
                        val waveformState by waveformController.composeState.collectAsState()
                        EditorWaveformPanel(
                            state = waveformState,
                            actions = EditorWaveformActions(
                                onViewportSizeChanged = waveformController::updateComposeViewport,
                                onViewportPan = waveformController::panComposeViewport,
                                onZoom = waveformController::zoomComposeViewport,
                                onSeek = { positionMs ->
                                    waveformController.seekComposeToTime(positionMs)
                                    playbackController.seekTo(positionMs)
                                },
                                onSelectSubtitle = waveformController::selectComposeSubtitle,
                                onSubtitleDrag = waveformController::updateComposeSubtitleDrag,
                                onTimestampDrag = waveformController::updateComposeTimestampAnchor,
                                onTimestampFinished = waveformController::finishComposeTimestamping,
                                onToggleExpanded = waveformController::toggleComposeExpanded,
                                onToggleDisplayMode = waveformController::toggleComposeDisplayMode,
                                onAmplitudeZoomIn = waveformController::zoomComposeAmplitudeIn,
                                onAmplitudeZoomOut = waveformController::zoomComposeAmplitudeOut,
                                onAmplitudeReset = waveformController::resetComposeAmplitude,
                                onGenerate = waveformController::startComposeGeneration,
                                onStartTimestamping = waveformController::startComposeTimestamping,
                                onQuickTranscribe = ::showQuickTranscribe,
                                onQuickTts = ::showQuickTts,
                                onQuickSplit = ::splitSubtitleAtPlaybackHead,
                                onChunkRequested = waveformController::requestComposeChunk,
                                onSpectrogramChunkRequested = waveformController::requestComposeSpectrogramChunk
                            )
                        )
                    },
                    sourceContent = {
                        EditorSourceEditor(state = sourceEditorState)
                    }
                )
            }
        }
    }

    private fun buildComposeMenu(): List<EditorToolbarMenuGroup> {
        return editorCoordinator.prepareMenu(
            sourceMode = stateModel.isSourceViewMode,
            selectedCount = if (::subtitleAdapter.isInitialized) subtitleAdapter.getSelectedCount() else 0,
            undo = if (stateModel.isSourceViewMode) stateModel.peekUndoWithoutSelection() else stateModel.peekUndo(),
            redo = if (stateModel.isSourceViewMode) stateModel.peekRedoWithoutSelection() else stateModel.peekRedo(),
            sourceTransitioning = stateModel.isSourceViewTransitioning || sourceViewTransitionJob?.isActive == true
        ).map { group ->
            EditorToolbarMenuGroup(
                title = group.title,
                actions = group.actions.map { action ->
                    EditorToolbarMenuAction(
                        title = action.title,
                        enabled = action.enabled,
                        onClick = { editorCoordinator.handleMenu(action.action) }
                    )
                }
            )
        }
    }

    private fun handleEditorMenuAction(action: EditorMenuController.Action) {
        when (action) {
            EditorMenuController.Action.UNDO -> undoEdit()
            EditorMenuController.Action.REDO -> redoEdit()
            EditorMenuController.Action.NEW -> newFile()
            EditorMenuController.Action.OPEN -> openFile()
            EditorMenuController.Action.SAVE -> saveFile()
            EditorMenuController.Action.SAVE_AS -> saveFileAs()
            EditorMenuController.Action.ENCODING -> showEncodingDialog()
            EditorMenuController.Action.SOURCE_VIEW -> toggleSourceView()
            EditorMenuController.Action.MERGE -> showMergeSubtitlesDialog()
            EditorMenuController.Action.SEARCH -> searchController.show()
            EditorMenuController.Action.SELECT_ALL -> selectAllSubtitles()
            EditorMenuController.Action.SELECT_RANGE -> selectRangeBetweenSelectedSubtitles()
            EditorMenuController.Action.SAVE_DRAFT -> saveDraft()
            EditorMenuController.Action.DRAFTS -> openDrafts()
        }
    }

    private fun setupSubtitleAdapter() {
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
            hasPlayableMedia = stateModel.mediaType.hasPlayableMedia,
            onSelectionChanged = {
                recordListSelectionChange()
                updateSelectedCountDisplay()
            }
        )
        
    }

    private fun publishComposeSubtitleEntries() {
        composeSubtitleEntries = stateModel.subtitleEntries.toList()
        composeSubtitleRevision++
    }

    private fun scrollToSubtitle(position: Int, offset: Int = 0) {
        if (stateModel.subtitleEntries.isEmpty()) return
        val target = position.coerceIn(stateModel.subtitleEntries.indices)
        lifecycleScope.launch {
            subtitleListState.scrollToItem(target, offset.coerceAtLeast(0))
        }
    }
    
    private fun setupSourceView() {
        sourceLineEditController = EditorSourceLineEditController(
            lineCount = sourceEditorState::getDocumentLineCount,
            lineText = sourceEditorState::getDocumentLineText,
            currentFormat = { stateModel.currentFormat },
            entries = { stateModel.subtitleEntries }
        )
        sourcePreviewController = EditorSourcePreviewController(
            scope = lifecycleScope,
            isSourceViewMode = { stateModel.isSourceViewMode },
            suppressSourceViewChanges = { suppressSourceViewChanges },
            editGeneration = { stateModel.documentState.sourceViewEditGeneration },
            currentFormat = { stateModel.currentFormat },
            snapshotContent = ::snapshotSourceViewContentIfNeeded,
            onParsed = ::applySourcePreview
        )
        sourceEditorState.addOnDocumentChangedListener {
            onComposeSourceDocumentChanged()
        }
        sourceEditorState.addOnDocumentChangeListener { change ->
            onComposeSourceDocumentChange(change)
        }
    }

    private fun onComposeSourceDocumentChanged() {
        if (stateModel.isSourceViewMode && !suppressSourceViewChanges) {
            val updatedText = sourceEditorState.getDocumentText()
            recordSourceTextChange(stateModel.sourceHistoryTextSnapshot, updatedText)
            stateModel.sourceHistoryTextSnapshot = updatedText
            stateModel.setSourceDocumentContent(updatedText)
            sourceViewHasPendingEdits = true
            stateModel.documentState.sourceViewEditGeneration++
            updateFormatInfo()
            scheduleSourceViewPreview()
        }
    }

    private fun onComposeSourceDocumentChange(change: EditorSourceDocumentChange) {
        if (stateModel.isSourceViewMode && !suppressSourceViewChanges) {
            if (change.oldLineCount != change.newLineCount) sourceLineEditController.invalidateLineIndex()
            applySimpleSourceLineChange(change.startLine, change.oldLineCount, change.newLineCount)
        }
    }

    /* Kept as a small forwarding hook for callers that publish a full state replacement. */
    private fun setComposeSourceDocumentText(value: String, preserveScroll: Boolean = false) {
        suppressSourceViewChanges = true
        try {
            sourceEditorState.setDocumentText(value, preserveScroll)
        } finally {
            suppressSourceViewChanges = false
        }
    }
    
    private fun setupSubtitleDialogController() {
        subtitleDialogController = EditorSubtitleDialogController(
            context = this,
            ensureListMode = ::ensureListMode,
            currentFormat = { stateModel.currentFormat },
            entryAt = { position -> stateModel.subtitleEntries.getOrNull(position) },
            updateTime = { position, start, value ->
                val result = if (start) {
                    stateModel.execute(EditorCommand.UpdateTime(position, startTime = value))
                } else {
                    stateModel.execute(EditorCommand.UpdateTime(position, endTime = value))
                }
                result.changedPositions.isNotEmpty()
            },
            updateText = { position, value ->
                stateModel.execute(EditorCommand.UpdateText(position, value))
                    .changedPositions.isNotEmpty()
            },
            updateCue = { position, identifier, settings ->
                stateModel.subtitleEntries.getOrNull(position)?.apply {
                    cueIdentifier = identifier
                    cueSettings = settings
                }
            },
            onUpdated = ::onEntryUpdated,
            showMessage = ::showShortToast
        )
    }

    private fun setupHistoryCoordinator() {
        historyCoordinator = EditorHistoryCoordinator(
            stateModel = stateModel,
            documentState = stateModel.documentState,
            selectedIds = {
                if (::subtitleAdapter.isInitialized) {
                    subtitleAdapter.getSelectedEntries().mapTo(mutableSetOf()) { it.first.stableId }
                } else emptySet()
            },
            sourceMode = { stateModel.isSourceViewMode },
            sourceEntriesReady = { stateModel.documentState.sourceViewEntriesGeneration == stateModel.documentState.sourceViewEditGeneration },
            applyOperation = ::applyHistoryOperation,
            invalidateMenu = {}
        )
    }

    private fun setupSearchController() {
        searchController = EditorSearchController(
            context = this,
            sourceEditorState = sourceEditorState,
            subtitleAdapter = subtitleAdapter,
            scrollListToPosition = ::scrollToSubtitle,
            isSourceViewMode = { stateModel.isSourceViewMode },
            ignoreSourceChanges = { suppressSourceViewChanges },
            entries = { stateModel.subtitleEntries },
            replaceSourceContent = { content ->
                replaceSourceViewContent(content)
            },
            applyEntryUpdates = { updates ->
                val result = stateModel.execute(
                    EditorCommand.UpdateTexts(updates.map { it.index to it.newText })
                )
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
            mediaType = stateModel.mediaType,
            subtitles = { stateModel.subtitleEntries },
            isSourceViewMode = { stateModel.isSourceViewMode },
            onPlayingSubtitleChanged = { index ->
                if (index == null) {
                    subtitleAdapter.clearPlayingHighlight()
                } else {
                    subtitleAdapter.highlightCurrentPlaying(index)
                }
            },
            onMediaReady = ::onMediaReady,
            showMessage = ::showShortToast,
            videoPlayerHost = videoPlayerView
        )
        playbackController.bind()
    }

    private fun setupWaveformController() {
        sourceWaveformSyncController = EditorSourceWaveformSyncController(
            scope = lifecycleScope,
            isSourceViewMode = { stateModel.isSourceViewMode },
            hasPendingSourceEdits = { sourceViewHasPendingEdits },
            isPreviewActive = { sourcePreviewController.isActive },
            editGeneration = { stateModel.documentState.sourceViewEditGeneration },
            currentFormat = { stateModel.currentFormat },
            sourceContent = { stateModel.sourceViewContent },
            snapshotSourceContent = ::snapshotSourceViewContentIfNeeded,
            currentEntries = { stateModel.subtitleEntries },
            cancelPreview = { sourcePreviewController.cancel() },
            schedulePreview = ::scheduleSourceViewPreview,
            recordHistory = ::recordSourceTextChange,
            onSourceUpdated = ::applySourceWaveformUpdatedSource
        )
        waveformController = EditorWaveformController(
            context = this,
            scope = lifecycleScope,
            hasPlayableMedia = stateModel.mediaType.hasPlayableMedia,
            appCacheDir = cacheDir,
            mediaRepository = mediaRepository,
            currentPlaybackPositionMs = { playbackController.currentPositionMs },
            onSubtitleChanged = { changedIndex, updatedEntry, dragSessionKey, isFinal ->
                val currentEntry = stateModel.subtitleEntries.getOrNull(changedIndex)
                    ?: return@EditorWaveformController
                if (stateModel.isSourceViewMode) {
                    val updatedEntries = stateModel.subtitleEntries.toMutableList().apply {
                        set(changedIndex, updatedEntry.copy())
                    }
                    waveformController.updateSubtitleEntries(
                        mapOf(changedIndex to updatedEntry.copy()),
                        stateModel.subtitleEntries.size
                    )
                    sourceWaveformSyncController.schedule(
                        updatedEntries,
                        dragSessionKey,
                        isFinal,
                        null
                    )
                    stateModel.subtitleEntries[changedIndex] = updatedEntry.copy()
                    markAsChanged()
                } else {
                    currentEntry.startTime = updatedEntry.startTime
                    currentEntry.endTime = updatedEntry.endTime
                    currentEntry.endTimeModified = updatedEntry.endTimeModified
                    notifyEntriesChanged(
                        positions = listOf(changedIndex),
                        includeNeighbors = true,
                        syncWaveform = false,
                        markChanged = isFinal,
                    )
                }
            },
            onSelectedIndexChanged = { index ->
                if (index in stateModel.subtitleEntries.indices) {
                    scrollToSubtitle(index)
                }
            },
            onTimestampInserted = ::insertSubtitleFromTimestamp,
            showMessage = ::showShortToast
        )
        playbackController.onPositionChanged = { positionMs, duration ->
            waveformController.setComposeCurrentPosition(
                if (duration <= 0L) 0f else positionMs.toFloat() / duration.toFloat()
            )
        }
        waveformController.bind()
    }

    private fun setupSubtitlePreviewController() {
        subtitlePreviewController = EditorSubtitlePreviewController(
            cacheDir = cacheDir,
            scope = lifecycleScope,
            replaceTrack = playbackController::replaceVideoSubtitleTrack
        )
    }

    private fun switchMediaType(type: EditorMediaType) {
        if (stateModel.mediaType == type) {
            return
        }
        stateModel.mediaType = type
        playbackController.setMediaType(type)
        waveformController.setMediaAvailable(type.hasPlayableMedia)
        subtitleAdapter.setHasPlayableMedia(type.hasPlayableMedia)
    }

    private fun exitVideoFullscreen() {
        if (stateModel.isVideoFullscreen) {
            val previousOrientation = stateModel.previousRequestedOrientation
            if (previousOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                requestedOrientation = previousOrientation
            }
        }
        stateModel.isVideoFullscreen = false
    }

    private fun toggleVideoFullscreen() {
        if (stateModel.isVideoFullscreen) {
            exitVideoFullscreen()
        } else {
            stateModel.previousRequestedOrientation = requestedOrientation
            stateModel.isVideoFullscreen = true
        }
    }

    private fun applyVideoFullscreenWindow(fullscreen: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }

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
        if (stateModel.mediaType == EditorMediaType.VIDEO && audioStreamIndex == null) {
            waveformController.showNoAudioTrack(durationMs, stateModel.subtitleEntries.toList())
        } else {
            waveformController.load(
                mediaFile,
                durationMs,
                stateModel.subtitleEntries.toList(),
                ffmpegAudioStreamIndex
            )
        }
        scheduleSubtitlePreview()
    }

    private fun scheduleSubtitlePreview() {
        if (stateModel.mediaType != EditorMediaType.VIDEO || !::subtitlePreviewController.isInitialized) return
        subtitlePreviewController.schedule(
            format = stateModel.currentFormat,
            entries = stateModel.subtitleEntries,
            sourceViewMode = stateModel.isSourceViewMode,
            sourceContent = if (stateModel.isSourceViewMode) stateModel.sourceViewContent else stateModel.originalFileContent
        )
    }

    /** 源码视图也保持 mpv 预览；防抖后只有最新文本会进入后台解析流程。 */
    private fun scheduleSourceViewPreview() {
        sourcePreviewController.schedule()
    }

    /**
     * Keep the parsed row model in sync for a text-only edit inside one cue. The full parser is
     * still used for structural edits, but ordinary character edits can be applied from the
     * changed source block immediately and therefore do not make view switches or history wait.
     */
    private fun applySimpleSourceLineChange(startLine: Int, oldLineCount: Int, newLineCount: Int) {
        if (stateModel.subtitleEntries.isEmpty()) return
        val update = sourceLineEditController.resolve(startLine, oldLineCount, newLineCount) ?: return
        val entryIndex = update.entryIndex
        val current = stateModel.subtitleEntries.getOrNull(entryIndex) ?: return
        val updated = update.entry
        current.startTime = updated.startTime
        current.endTime = updated.endTime
        current.text = updated.text
        current.endTimeModified = updated.endTimeModified
        current.cueIdentifier = updated.cueIdentifier
        current.cueSettings = updated.cueSettings
        stateModel.documentState.sourceViewEntriesGeneration = stateModel.documentState.sourceViewEditGeneration
        sourceViewHasPendingEdits = false
        stateModel.updateSourceHistory(stateModel.sourceViewContent, stateModel.subtitleEntries)
        if (::subtitleAdapter.isInitialized && entryIndex in 0 until subtitleAdapter.itemCount) {
            subtitleAdapter.notifyItemChanged(entryIndex)
        }
        // Keep only the dependent waveform row in sync. Publishing the complete document here
        // would copy/broadcast every subtitle for each source-editor keystroke on large files.
        syncWaveformSubtitles(changedPositions = listOf(entryIndex))
        if (::playbackController.isInitialized) playbackController.invalidateHighlightCache()
        if (::searchController.isInitialized) searchController.onDocumentChanged()
    }

    private fun applySourcePreview(
        editGeneration: Long,
        sourceSnapshot: String,
        parsedDocument: SubtitleDocument
    ) {
        // 源视图编辑也必须更新波形字幕块。解析失败时保留上一次有效条目，
        // 避免用户输入时间轴中间态时字幕块瞬间全部消失。
        val canApplyEntries = parsedDocument.entries.isNotEmpty() ||
            sourceSnapshot.isBlank() ||
            !sourceContainsSubtitleMarker(sourceSnapshot)
        if (canApplyEntries) {
            applySourceViewEntries(normalizeSourceViewEntries(parsedDocument.entries))
            stateModel.updateLatestSourceHistory(sourceSnapshot, stateModel.subtitleEntries)
            stateModel.documentState.sourceViewEntriesGeneration = editGeneration
            sourceViewHasPendingEdits = false
        }

        if (stateModel.mediaType == EditorMediaType.VIDEO && ::subtitlePreviewController.isInitialized) {
            subtitlePreviewController.schedule(
                format = stateModel.currentFormat,
                entries = parsedDocument.entries,
                sourceViewMode = true,
                sourceContent = sourceSnapshot
            )
        }
    }

    /**
     * LRC parsing intentionally gives cues without an explicit terminator a 24 ms
     * implicit gap before the next cue. When a user deletes an existing terminator
     * in source view, retain the former explicit boundary for the in-memory row so
     * switching to list view does not show a spurious 24 ms drift.
     */
    private fun normalizeSourceViewEntries(parsed: List<SubtitleEntry>): List<SubtitleEntry> {
        if (stateModel.currentFormat != SubtitleParser.SubtitleFormat.LRC) return parsed
        val previous = stateModel.subtitleEntries
        return parsed.mapIndexed { index, entry ->
            val old = previous.getOrNull(index)
            val next = parsed.getOrNull(index + 1)
            if (old?.endTimeModified == true &&
                !entry.endTimeModified &&
                next != null &&
                entry.endTime == next.startTime - 24L
            ) {
                entry.copy(endTime = next.startTime, endTimeModified = false)
            } else {
                entry
            }
        }
    }

    /** 将波形拖动后的时间字段回写源视图，避免每次 MOVE 都触发逐行重建。 */
    private fun applySourceWaveformUpdatedSource(updatedSource: String) {
        stateModel.originalFileContent = updatedSource
        stateModel.sourceViewContent = updatedSource
        stateModel.sourceHistoryTextSnapshot = updatedSource
        sourceViewHasPendingEdits = false
        stateModel.updateLatestSourceHistory(updatedSource, stateModel.subtitleEntries)
        stateModel.documentState.sourceViewEditGeneration++
        stateModel.documentState.sourceViewEntriesGeneration = stateModel.documentState.sourceViewEditGeneration
        setSourceViewEditorText(updatedSource, preserveScroll = true)
        updateFormatInfo()
        scheduleSubtitlePreview()
    }
    private fun setupAiControllers() {
        textPreviewDialog = EditorTextPreviewDialog(this)
        translationController = EditorTranslationController(
            activity = this,
            scope = lifecycleScope,
            previewDialog = textPreviewDialog,
            applyTexts = { appliedItems -> applyPreviewTexts(appliedItems, "翻译") },
            saveDraft = ::saveTranslationDraft,
            showMessage = ::showShortToast,
            aiTranslationService = (application as SubtitleEditApplication).dependencies.aiTranslationService,
            subtitleFormatProvider = { stateModel.currentFormat }
        )
        transcribeController = EditorTranscribeController(
            activity = this,
            scope = lifecycleScope,
            cacheDir = cacheDir,
            previewDialog = textPreviewDialog,
            applyTexts = { appliedItems -> applyPreviewTexts(appliedItems, "转录") },
            showMessage = ::showShortToast,
            speechRecognitionService = (application as SubtitleEditApplication).dependencies.speechRecognitionService
        )
        ttsController = EditorTtsController(
            activity = this,
            showMessage = ::showShortToast
        )
    }

    private fun updateSelectedCountDisplay() {
        val count = subtitleAdapter.getSelectedCount()
        toolbarSelectionActive = count > 0
        if (count > 0) {
            val formatName = SubtitleFormatPolicy.displayName(stateModel.currentFormat)
            toolbarSubtitle = "$formatName | ${stateModel.subtitleEntries.size} 条 | 选中：$count"
        } else {
            toolbarSubtitle = stateModel.currentFormatInfo
        }
    }

    private fun setDocumentTitle(title: String) {
        stateModel.documentTitle = title
        toolbarTitle = title
    }

    private fun restoreDocumentState() {
        setDocumentTitle(stateModel.documentTitle)
        if (stateModel.isSourceViewMode) {
            composeSourceViewMode = true
            val wasUnsaved = stateModel.hasUnsavedChanges
            configureSourceViewEditor(stateModel.sourceViewContent)
            stateModel.hasUnsavedChanges = wasUnsaved
            sourceEditorState.scrollToDocumentY(stateModel.savedScrollPosition)
        } else {
            composeSourceViewMode = false
            submitSubtitleList(
                refreshAll = true,
                selectedIndices = stateModel.selectedIndices,
                updateFormat = false,
                syncWaveform = false
            ) {
                val position = stateModel.savedFirstVisibleItemPosition
                if (position in stateModel.subtitleEntries.indices) {
                    scrollToSubtitle(position, stateModel.savedScrollPosition)
                }
            }
        }
        updateFormatInfo()
        syncWaveformSubtitles()
    }
    
    private fun loadFile() {
        if (stateModel.filePath.isEmpty() || stateModel.currentFile == null) {
            finishWithToast("文件路径无效")
            return
        }

        val file = stateModel.currentFile ?: run {
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
        stateModel.currentCharset = settingsManager.getDefaultEncoding()
        val charset = stateModel.currentCharset

        lifecycleScope.launch {
            val content = runCatching {
                withContext(Dispatchers.IO) {
                    fileSessionController.readFile(file, charset)
                }
            }.getOrElse {
                showShortToast("读取文件失败：${it.message}")
                return@launch
            }
            parseContent(content, file.name)
            stateModel.hasUnsavedChanges = false
            stateModel.isNewFile = false
        }
    }

    private enum class OpenedUriKind { SUBTITLE, AUDIO, VIDEO, UNSUPPORTED }

    private val subtitleOpenExtensions = FileUtils.SUBTITLE_EXTENSIONS - "txt"
    private val subtitleOpenMimeTypes = setOf(
        "text/vtt",
        "text/srt",
        "text/ass",
        "text/ssa",
        "application/x-subrip",
        "application/srt",
        "application/ttml+xml"
    )

    private fun classifyOpenedUri(uri: Uri, fileName: String): OpenedUriKind {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mime = contentResolver.getType(uri)?.lowercase().orEmpty()
        return when {
            mime.startsWith("audio/") || extension in FileUtils.AUDIO_EXTENSIONS ->
                OpenedUriKind.AUDIO
            mime.startsWith("video/") || extension in FileTypePolicy.videoExtensions ->
                OpenedUriKind.VIDEO
            extension in subtitleOpenExtensions || mime in subtitleOpenMimeTypes ->
                OpenedUriKind.SUBTITLE
            else -> OpenedUriKind.UNSUPPORTED
        }
    }

    private suspend fun copyOpenedUriToCache(uri: Uri, fileName: String): File =
        withContext(Dispatchers.IO) {
            val mediaDir = File(cacheDir, "editor_open_media").apply { mkdirs() }
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "media" }
            val target = File(mediaDir, "${System.currentTimeMillis()}_$safeName")
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取文件")
            input.use { source ->
                FileOutputStream(target).use { output -> source.copyTo(output) }
            }
            target
        }

    private fun openFileFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) { fileSessionController.fileName(uri) }
                val kind = classifyOpenedUri(uri, fileName)
                when (kind) {
                    OpenedUriKind.SUBTITLE -> {
                        val content = withContext(Dispatchers.IO) { fileSessionController.readUri(uri) }
                        val mediaTitle = stateModel.documentTitle.takeIf {
                            stateModel.mediaType.hasPlayableMedia
                        }
                        stateModel.openUriSubtitleDocument(uri.toString(), fileName)
                        setDocumentTitle(mediaTitle ?: stateModel.documentTitle)
                        takePersistableWritePermission(uri)
                        parseContent(content, fileName)
                        stateModel.hasUnsavedChanges = false
                        stateModel.documentLoaded = true
                        showShortToast("文件已打开：$fileName")
                    }

                    OpenedUriKind.AUDIO, OpenedUriKind.VIDEO -> {
                        val openedType = if (kind == OpenedUriKind.AUDIO) {
                            EditorMediaType.AUDIO
                        } else {
                            EditorMediaType.VIDEO
                        }
                        val mediaFile = copyOpenedUriToCache(uri, fileName)
                        // A subtitle-only document keeps its local file as the subtitle sidecar
                        // when media is added, so saving still updates the original subtitle.
                        if (!stateModel.mediaType.hasPlayableMedia && stateModel.documentUri == null) {
                            stateModel.currentFile?.let { subtitle ->
                                stateModel.subtitleFilePath = subtitle.absolutePath
                                stateModel.subtitleFile = subtitle
                            }
                        }
                        stateModel.filePath = mediaFile.absolutePath
                        stateModel.currentFile = mediaFile
                        stateModel.isAudioOnlyFromVideo = false
                        switchMediaType(openedType)
                        // Keep the current subtitle document and entries while replacing/adding media.
                        loadMediaFile(stateModel.subtitleFilePath, restoreDocument = true)
                        setDocumentTitle(fileName)
                        stateModel.documentLoaded = true
                        showShortToast("文件已打开：$fileName")
                    }

                    OpenedUriKind.UNSUPPORTED -> {
                        showShortToast("请选择字幕或音视频文件")
                        if (intent.action == Intent.ACTION_VIEW) finish()
                    }
                }
            } catch (e: Exception) {
                showShortToast("打开文件失败：${e.message ?: "未知错误"}")
            }
        }
    }
    
    /**
     * 从 URI 获取文件名
     */
    private fun getFileNameFromUri(uri: Uri): String = fileSessionController.fileName(uri)
    
    private fun reloadFile() {
        val targetFile = if (stateModel.mediaType.hasPlayableMedia) stateModel.subtitleFile else stateModel.currentFile
        if (targetFile == null || !targetFile.exists()) {
            showShortToast("当前文件无法重新加载编码，请通过「打开」功能重新选择文件")
            return
        }

        val charset = stateModel.currentCharset
        lifecycleScope.launch {
            val content = runCatching {
                withContext(Dispatchers.IO) {
                    fileSessionController.readFile(targetFile, charset)
                }
            }.getOrElse {
                showShortToast("切换编码失败：${it.message}")
                return@launch
            }
            parseContent(content, targetFile.name)
            stateModel.hasUnsavedChanges = false
            showShortToast("已切换编码为：${FileUtils.SUPPORTED_ENCODINGS.find { it.charset == stateModel.currentCharset }?.displayName}")
        }
    }
    
    private fun parseContent(content: String, fileName: String? = null) {
        val document = stateModel.loadSubtitleContent(content, fileName)
        stateModel.currentFormat = document.format
        
        if (stateModel.currentFormat.isSourceOnly) {
            // Source-only documents still need a block model for waveform playback and for
            // keeping source rows aligned with the list view.  The source editor displays
            // the untouched full text, while these parsed entries provide the corresponding
            // subtitle blocks.
            replaceSubtitleEntries(document.entries, preserveStableIds = false)
            enterSourceViewMode()
        } else {
            replaceSubtitleEntries(document.entries, preserveStableIds = false)
            exitSourceViewMode()
        }
        
        updateFormatInfo()
        
        if (stateModel.subtitleEntries.isEmpty() && !stateModel.isSourceViewMode) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "未找到字幕内容", Toast.LENGTH_SHORT).show()
        }
        
        // 同步字幕到波形视图（仅音频模式有效）
        syncWaveformSubtitles()
        scheduleSubtitlePreview()
        initializeEditHistoryBaseline(clearHistory = true)
    }
    
    /**
     * 进入源码视图。
     *
     * 大型字幕不使用淡入淡出：动画期间 Compose 列表和 mpv 预览会短暂
     * 并存，正是日志中 native heap 峰值与 ANR 的高风险窗口。
     */
    private fun enterSourceViewMode(onFinished: (() -> Unit)? = null) {
        stateModel.isSourceViewMode = true
        composeSourceViewMode = true
        sourceListParseJob?.cancel()
        sourceListParseJob = null
        sourceWaveformSyncController.cancel()
        sourceViewHasPendingEdits = false
        stateModel.documentState.sourceViewEditGeneration++
        stateModel.documentState.sourceViewEntriesGeneration = stateModel.documentState.sourceViewEditGeneration
        if (::searchController.isInitialized) searchController.clearSourceWorkForTransition()
        
        stateModel.savedFirstVisibleItemPosition = subtitleListState.firstVisibleItemIndex
        stateModel.savedScrollPosition = subtitleListState.firstVisibleItemScrollOffset
        stateModel.sourceViewEntryCount = stateModel.subtitleEntries.size

        configureSourceViewEditor(stateModel.sourceViewContent)
        if (stateModel.savedFirstVisibleItemPosition >= 0 &&
            stateModel.savedFirstVisibleItemPosition < stateModel.sourceViewEntryCount
        ) {
            val estimatedScroll = stateModel.savedFirstVisibleItemPosition * 80 + stateModel.savedScrollPosition
            sourceEditorState.scrollToDocumentY(estimatedScroll.coerceAtLeast(0))
        }
        onFinished?.invoke()
        
    }
    
    /** 退出源码视图。源码编辑器已由调用方先禁用，避免它与列表同时编辑。 */
    private fun exitSourceViewMode(
        sourceScrollPosition: Int? = null,
        onFinished: (() -> Unit)? = null
    ) {
        stateModel.isSourceViewMode = false
        composeSourceViewMode = false
        if (::searchController.isInitialized) searchController.clearSourceWorkForTransition()
        
        // 保存 Compose 源码编辑器的滚动位置
        stateModel.savedScrollPosition = sourceScrollPosition ?: sourceEditorState.getDocumentScrollOffset()
        
        submitSubtitleList(
            // Source-view waveform drags update the existing SubtitleEntry objects in place.
            // ListAdapter may therefore see the same object on both sides of DiffUtil and skip
            // rebinding the time fields. Force a row refresh when the list becomes visible so
            // the end-time change (including a cleared/adjacent end time) is shown immediately.
            refreshAll = true,
            updateFormat = false,
            syncWaveform = false,
            schedulePreview = false,
            afterSubmit = {
                if (stateModel.subtitleEntries.isNotEmpty()) {
                    val estimatedPosition = stateModel.savedScrollPosition / 80
                    scrollToSubtitle(estimatedPosition)
                }
                onFinished?.invoke()
            }
        )
        
    }
    
    /**
     * 切换源视图模式
     */
    private fun toggleSourceView() {
        if (stateModel.isSourceViewTransitioning || sourceViewTransitionJob?.isActive == true) {
            showShortToast("正在切换视图，请稍候")
            return
        }
        if (stateModel.currentFormat.isSourceOnly) {
            showShortToast("${SubtitleFormatPolicy.displayName(stateModel.currentFormat)} 文件使用源码视图编辑")
            return
        }

        stateModel.isSourceViewTransitioning = true
        if (stateModel.isSourceViewMode) {
            // 源视图 → 列表视图：直接切换，解析源视图当前内容
            doExitSourceView()
        } else {
            // 列表视图 → 源视图：把列表修改定点同步到内存中的原始文本
            doEnterSourceView()
        }
    }

    /** 列表视图 → 源视图：只同步内存中的原始文本，不读写实际文件。 */
    private fun doEnterSourceView() {
        enterSourceViewFromMemory()
    }

    /** 将列表条目定点写回内存中的原始文本后进入源视图。 */
    private fun enterSourceViewFromMemory() {
        sourceViewTransitionJob?.cancel()
        showShortToast("正在切换到源视图…")
        sourceViewTransitionJob = lifecycleScope.launch {
            try {
                sourcePreviewController.cancel()
                val sourceBase = stateModel.originalFileContent
                val freshContent = if (!stateModel.sourceViewNeedsListSync) {
                    sourceBase
                } else {
                    val listSnapshot = stateModel.subtitleEntries.toList()
                    withContext(Dispatchers.Default) {
                        SubtitleSourceSynchronizer.apply(
                            content = sourceBase,
                            format = stateModel.currentFormat,
                            oldEntries = SubtitleParser.parseDocument(
                                sourceBase,
                                format = stateModel.currentFormat
                            ).entries,
                            newEntries = listSnapshot
                        )
                    }
                }

                stateModel.originalFileContent = freshContent
                stateModel.sourceViewContent = freshContent
                stateModel.sourceHistoryTextSnapshot = freshContent
                stateModel.sourceViewNeedsListSync = false
                sourceViewHasPendingEdits = false
                enterSourceViewMode {
                    sourceViewTransitionJob = null
                    stateModel.isSourceViewTransitioning = false
                    // 用完整源码内容重新建立 mpv 字幕轨。
                    scheduleSubtitlePreview()
                    showShortToast("已切换到源视图")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sourceViewTransitionJob = null
                stateModel.isSourceViewTransitioning = false
                showShortToast("切换到源视图失败：${e.message}")
            }
        }
    }

    /**
     * 源视图 → 列表视图：解析源视图中当前编辑的内容
     */
    private fun doExitSourceView() {
        if (sourceViewTransitionJob?.isActive == true) return
        val sourceScrollPosition = sourceEditorState.getDocumentScrollOffset()
        var editedContent = stateModel.sourceViewContent
        // 先禁用源行编辑，再在后台构建字幕条目列表，避免切换期间两套编辑状态并存。
        sourceEditorState.setDocumentEnabled(false)
        showShortToast("正在切换到列表视图…")
        sourceViewTransitionJob = lifecycleScope.launch {
            var needsBackgroundParse = false
            try {
                sourceWaveformSyncController.cancelAndJoin()
                editedContent = stateModel.sourceViewContent
                sourcePreviewController.cancel()
                if (stateModel.documentState.sourceViewEntriesGeneration != stateModel.documentState.sourceViewEditGeneration) {
                    val appliedLocally = sourceViewCoordinator.applyDeletionLocally(editedContent)
                    if (!appliedLocally) {
                        // Keep the current rows for the transition and parse the large source
                        // document after the list is visible. This removes the full-file parse
                        // from the user-facing switch path.
                        needsBackgroundParse = true
                    }
                }
                stateModel.originalFileContent = editedContent
                stateModel.sourceViewContent = editedContent
                stateModel.sourceHistoryTextSnapshot = editedContent
                stateModel.sourceViewNeedsListSync = false
                syncEditHistoryBaseline()
                exitSourceViewMode(sourceScrollPosition) {
                    sourceEditorState.setDocumentEnabled(true)
                    sourceViewTransitionJob = null
                    stateModel.isSourceViewTransitioning = false
                    updateFormatInfo()
                    // 等源码 Editable、解析临时对象和列表提交完成一个帧周期后，
                    // 再重建 mpv 字幕轨，避免切换瞬间额外复制所有条目。
                    if (needsBackgroundParse) {
                        scheduleListSourceParse(editedContent, stateModel.documentState.sourceViewEditGeneration)
                    } else {
                        sourcePreviewController.scheduleListPreview(::scheduleSubtitlePreview)
                    }
                    showShortToast("已切换到列表视图")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                configureSourceViewEditor(editedContent)
                sourceEditorState.setDocumentEnabled(true)
                sourceViewTransitionJob = null
                stateModel.isSourceViewTransitioning = false
                showShortToast("解析失败：${e.message}")
            }
        }
    }

    /** Parse a source snapshot off the UI thread and publish it once the list view is active. */
    private fun scheduleListSourceParse(content: String, generation: Long) {
        sourceListParseJob?.cancel()
        sourceListParseJob = lifecycleScope.launch {
            try {
                val document = withContext(Dispatchers.Default) {
                    SubtitleParser.parseDocument(content, format = stateModel.currentFormat)
                }
                if (isActive) applyParsedListSourceDocument(document, content, generation)
            } finally {
                if (sourceListParseJob === coroutineContext[Job]) sourceListParseJob = null
            }
        }
    }

    private fun applyParsedListSourceDocument(
        document: SubtitleDocument,
        content: String,
        generation: Long
    ) {
        if (stateModel.isSourceViewMode || stateModel.sourceViewContent != content ||
            generation != stateModel.documentState.sourceViewEditGeneration
        ) return
        if (deferLargeListEdit(document.entries.size) {
                applyParsedListSourceDocument(document, content, generation)
            }) return
        applySourceViewEntries(normalizeSourceViewEntries(document.entries))
        stateModel.documentState.sourceViewEntriesGeneration = generation
        stateModel.sourceViewNeedsListSync = false
        stateModel.updateSourceHistory(content, stateModel.subtitleEntries)
        syncEditHistoryBaseline()
        submitSubtitleList(
            refreshAll = false,
            syncWaveform = false,
            markChanged = false
        )
    }

    private fun setSourceViewEditorText(content: String, preserveScroll: Boolean = false) {
        if (::sourceLineEditController.isInitialized) sourceLineEditController.invalidateLineIndex()
        suppressSourceViewChanges = true
        try {
            sourceEditorState.setDocumentText(content, preserveScroll)
        } finally {
            suppressSourceViewChanges = false
        }
    }

    private fun configureSourceViewEditor(content: String) {
        setSourceViewEditorText(content)
        stateModel.sourceHistoryTextSnapshot = content
        sourceEditorState.setDocumentEnabled(true)
    }

    /** 搜索替换直接重写完整源码内容。 */
    private fun replaceSourceViewContent(content: String) {
        recordSourceTextChange(stateModel.sourceHistoryTextSnapshot, content)
        setSourceViewEditorText(content)
        stateModel.originalFileContent = content
        stateModel.sourceViewContent = content
        stateModel.sourceHistoryTextSnapshot = content
        stateModel.sourceViewNeedsListSync = false
        stateModel.hasUnsavedChanges = true
        sourceViewHasPendingEdits = true
        stateModel.documentState.sourceViewEditGeneration++
        updateFormatInfo()
        scheduleSourceViewPreview()
    }

    private fun snapshotSourceViewContentIfNeeded(): String {
        if (!stateModel.isSourceViewMode || !sourceViewHasPendingEdits) return stateModel.sourceViewContent
        val visibleContent = sourceEditorState.getDocumentText()
        val snapshot = visibleContent
        stateModel.originalFileContent = snapshot
        stateModel.sourceViewContent = snapshot
        stateModel.sourceHistoryTextSnapshot = snapshot
        sourceViewHasPendingEdits = false
        return snapshot
    }

    /**
     * 加载草稿内容（覆盖当前内容）
     */
    private fun loadDraftContent(content: String, draftFileName: String) {
        ComposeDialogHost.show(this) { dialog ->
            AlertDialog(
                onDismissRequest = dialog::dismiss,
                title = { Text("加载草稿") },
                text = { Text("确定要用草稿内容覆盖当前编辑内容吗？（只覆盖内容，不更改文件名）") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog.dismiss()
                            parseContent(content)
                            stateModel.hasUnsavedChanges = true
                            com.subtitleedit.util.OverwritingToast.makeText(
                                this,
                                "已加载草稿：$draftFileName",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = dialog::dismiss) { Text("取消") }
                }
            )
        }
    }
    
    private fun showContextMenu(position: Int) {
        if (!ensureListMode()) return
        
        // 保存长按位置
        longClickPosition = position
        
        val selectedCount = subtitleAdapter.getSelectedCount()
        val hasSelection = selectedCount > 0
        val hasClipboard = stateModel.clipboardTexts.isNotEmpty()
        
        val regularActions = mutableListOf<Pair<String, () -> Unit>>()
        if (stateModel.currentFormat == SubtitleParser.SubtitleFormat.VTT) {
            regularActions.add("WebVTT Cue 属性" to { showWebVttCueDialog(position) })
        }
        regularActions.add("时间偏移" to { showOffsetDialog(position) })
        if (hasClipboard) {
            regularActions.add("向前粘贴 (${stateModel.clipboardTexts.size}项)" to {
                insertSubtitle(after = false, refPosition = position, pasteAfterInsert = true)
            })
        }
        regularActions.add("向前插入" to { insertSubtitle(false, position) })
        if (hasClipboard) {
            regularActions.add("向后粘贴 (${stateModel.clipboardTexts.size}项)" to {
                insertSubtitle(after = true, refPosition = position, pasteAfterInsert = true)
            })
        }
        regularActions.add("向后插入" to { insertSubtitle(true, position) })
        regularActions.add("复制" to { copySingle(position) })
        regularActions.add("剪切 (粘贴后删除)" to { cutSingle(position) })
        regularActions.add(
            (if (hasClipboard) "粘贴 (${stateModel.clipboardTexts.size}项)[当前行]" else "粘贴") to {
                if (hasClipboard) pasteToPosition(position) else ensureClipboardNotEmpty()
            }
        )
        if (position > 0) {
            regularActions.add("与前行合并" to { mergeSubtitlePositions(listOf(position - 1, position)) })
        }
        if (position + 1 < stateModel.subtitleEntries.size) {
            regularActions.add("与后行合并" to { mergeSubtitlePositions(listOf(position, position + 1)) })
        }
        if (position > 0) {
            regularActions.add("向前延伸至上一行" to {
                extendSubtitleToAdjacent(setOf(position), towardPrevious = true)
            })
        }
        if (position + 1 < stateModel.subtitleEntries.size) {
            regularActions.add("向后延伸至下一行" to {
                extendSubtitleToAdjacent(setOf(position), towardPrevious = false)
            })
        }
        regularActions.add("删除" to { deleteSingleSubtitle(position) })

        val menuActions = buildList {
            if (hasSelection) {
                add("对勾选字幕操作 (${selectedCount}项)" to {
                    showSelectionContextMenu(hasClipboard)
                })
            }
            addAll(regularActions)
        }
        showEditorActionDialog(actions = menuActions)
    }

    private fun showEditorActionDialog(
        actions: List<Pair<String, () -> Unit>>,
        title: String? = null
    ) {
        ComposeDialogHost.show(this) { dialog ->
            EditorActionListDialog(
                title = title,
                actions = actions,
                onDismiss = dialog::dismiss,
                onAction = { action ->
                    dialog.dismiss()
                    action()
                }
            )
        }
    }
    
    /**
     * 显示针对选中项的操作菜单
     */
    private fun showSelectionContextMenu(hasClipboard: Boolean) {
        if (!ensureListMode()) return
        
        val selectedPositions = subtitleAdapter.getSelectedPositions().sorted()
        val canMergeSelected = selectedPositions.size > 1 &&
            selectedPositions.zipWithNext().all { (left, right) -> right == left + 1 }
        val itemsList = mutableListOf<String>()
        itemsList.add("时间偏移")
        itemsList.add("AI 翻译")
        itemsList.add("复制")
        itemsList.add("剪切 (粘贴后删除)")
        if (hasClipboard) {
            itemsList.add("粘贴 (${stateModel.clipboardTexts.size}项)")
        } else {
            itemsList.add("粘贴")
        }
        var mergeIndex = -1
        if (canMergeSelected) {
            mergeIndex = itemsList.size
            itemsList.add("合并所选行")
        }
        val canExtendSelectedToPrevious = selectedPositions.any { it > 0 }
        val canExtendSelectedToNext = selectedPositions.any {
            it + 1 < stateModel.subtitleEntries.size
        }
        var extendPreviousIndex = -1
        var extendNextIndex = -1
        if (canExtendSelectedToPrevious) {
            extendPreviousIndex = itemsList.size
            itemsList.add("向前延伸至上一行")
        }
        if (canExtendSelectedToNext) {
            extendNextIndex = itemsList.size
            itemsList.add("向后延伸至下一行")
        }
        val deleteIndex = itemsList.size
        itemsList.add("删除选中")
        
        val actions: List<Pair<String, () -> Unit>> = itemsList.mapIndexed { index, title ->
            title to {
                when (index) {
                    0 -> showOffsetDialogForSelection()
                    1 -> showAiTranslate()
                    2 -> copySelected()
                    3 -> cutSelected()
                    4 -> if (hasClipboard) pasteToSelected() else ensureClipboardNotEmpty()
                    else -> when {
                        index == mergeIndex -> mergeSubtitlePositions(selectedPositions)
                        index == extendPreviousIndex -> extendSubtitleToAdjacent(
                            selectedPositions.toSet(), towardPrevious = true
                        )
                        index == extendNextIndex -> extendSubtitleToAdjacent(
                            selectedPositions.toSet(), towardPrevious = false
                        )
                        index == deleteIndex -> deleteSelectedSubtitles()
                    }
                }
                Unit
            }
        }
        showEditorActionDialog(title = "对勾选字幕操作", actions = actions)
    }

    private fun extendSubtitleToAdjacent(positions: Set<Int>, towardPrevious: Boolean) {
        if (deferLargeListEdit(stateModel.subtitleEntries.size) {
                extendSubtitleToAdjacent(positions, towardPrevious)
            }) return
        if (!ensureListMode()) return
        val result = stateModel.execute(
            EditorCommand.ExtendToAdjacent(positions, towardPrevious)
        )
        if (result.changedPositions.isEmpty()) {
            showShortToast("没有可延伸的字幕")
            return
        }
        notifyEntriesChanged(result.changedPositions)
        showShortToast(
            if (positions.size > 1) "已延伸 ${result.changedPositions.size} 行字幕"
            else if (towardPrevious) "已向前延伸至上一行" else "已向后延伸至下一行"
        )
    }

    private fun mergeSubtitlePositions(positions: List<Int>) {
        if (deferLargeListEdit(stateModel.subtitleEntries.size) { mergeSubtitlePositions(positions) }) return
        if (!ensureListMode()) return
        val sortedPositions = positions.distinct().sorted()
        if (sortedPositions.size < 2 ||
            sortedPositions.zipWithNext().any { (left, right) -> right != left + 1 }
        ) {
            showShortToast("只能合并连续字幕")
            return
        }

        val historyBefore = currentHistoryListState()
        val result = stateModel.execute(EditorCommand.Merge(sortedPositions))
        if (!result.structureChanged) {
            showShortToast("没有可合并的字幕")
            return
        }
        stateModel.historyEntriesSnapshot = historyBefore.entries
        stateModel.historySelectionSnapshot = historyBefore.selectedIds
        cutPasteController.clear()
        submitSubtitleList(
            refreshAll = true,
            clearSelection = true,
            markChanged = true
        )
        showShortToast("已合并 ${sortedPositions.size} 行字幕")
    }
    
    /**
     * 复制单个字幕（长按的字幕）
     */
    private fun copySingle(position: Int) {
        if (stateModel.isSourceViewMode) return
        
        if (position >= 0 && position < stateModel.subtitleEntries.size) {
            stateModel.clipboardTexts = listOf(stateModel.subtitleEntries[position].text)
            cutPasteController.clear()
            com.subtitleedit.util.OverwritingToast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 剪切单个字幕（长按的字幕）
     */
    private fun cutSingle(position: Int) {
        if (stateModel.isSourceViewMode) return
        
        if (position >= 0 && position < stateModel.subtitleEntries.size) {
            // 先保存到剪贴板
            stateModel.clipboardTexts = listOf(stateModel.subtitleEntries[position].text)
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
        
        stateModel.clipboardTexts = listOperationsController.copy(stateModel.subtitleEntries, selectedEntries.map { it.second })
        cutPasteController.markMultiCut(selectedEntries.map { it.second })
        com.subtitleedit.util.OverwritingToast.makeText(this, "已剪切 ${stateModel.clipboardTexts.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 执行剪切删除操作（在粘贴后调用）
     */
    private fun performCutDelete() {
        if (!cutPasteController.hasPendingCut()) return

        val deletedIndices = cutPasteController.snapshotDeletedIndices()
        val historyBefore = currentHistoryListState()
        listOperationsController.removePendingCut(stateModel.subtitleEntries, cutPasteController)
        syncAfterDelete(deletedIndices, historyBefore)
    }
    
    /**
     * 粘贴到指定位置（单行替换）
     */
    private fun pasteToPosition(position: Int) {
        if (deferLargeListEdit(stateModel.subtitleEntries.size) { pasteToPosition(position) }) return
        if (!ensureListMode()) return
        
        if (!ensureClipboardNotEmpty()) return

        if (position >= 0 && position < stateModel.subtitleEntries.size) {
            val targetSnapshot = SubtitleEntryOps.deepCopy(stateModel.subtitleEntries[position])
            var targetPosition = position
            // 如果是剪切模式，先删除原字幕
            if (cutPasteController.hasPendingCut()) {
                targetPosition = cutPasteController.adjustPastePositionAfterCut(position)
                performCutDelete()
            }

            if (stateModel.subtitleEntries.isEmpty()) {
                EditorDocumentOperations.addAt(stateModel.subtitleEntries, 0, targetSnapshot)
                targetPosition = 0
            }
            targetPosition = targetPosition.coerceIn(0, stateModel.subtitleEntries.lastIndex)

            val pasteResult = listOperationsController.pasteAt(stateModel.subtitleEntries, targetPosition, stateModel.clipboardTexts)
            if (pasteResult.structureChanged) {
                submitSubtitleList(refreshAll = true, markChanged = true)
                com.subtitleedit.util.OverwritingToast.makeText(this, "已粘贴 ${stateModel.clipboardTexts.size} 项", Toast.LENGTH_SHORT).show()
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
        
        if (position >= 0 && position < stateModel.subtitleEntries.size) {
                showDeleteConfirm("确定要删除此字幕吗？") {
                    if (deferLargeListEdit(stateModel.subtitleEntries.size) {
                            deleteSingleSubtitleConfirmed(position)
                        }) return@showDeleteConfirm
                    deleteSingleSubtitleConfirmed(position)
            }
        }
    }

    private fun deleteSingleSubtitleConfirmed(position: Int) {
        val historyBefore = currentHistoryListState()
        stateModel.execute(EditorCommand.Delete(setOf(position)))
        syncAfterDelete(setOf(position), historyBefore)
        com.subtitleedit.util.OverwritingToast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 插入字幕到指定位置
     */
    private fun insertSubtitle(
        after: Boolean,
        refPosition: Int,
        pasteAfterInsert: Boolean = false
    ) {
        if (deferLargeListEdit(stateModel.subtitleEntries.size) {
                insertSubtitle(after, refPosition, pasteAfterInsert)
            }) return
        if (!ensureListMode()) return
        if (refPosition !in stateModel.subtitleEntries.indices) return
        if (pasteAfterInsert && !ensureClipboardNotEmpty()) return

        // 剪切粘贴会删除来源行，先保留参考行时间并修正插入位置。
        val refEntry = SubtitleEntryOps.deepCopy(stateModel.subtitleEntries[refPosition])
        var insertPosition = if (after) refPosition + 1 else refPosition
        if (pasteAfterInsert && cutPasteController.hasPendingCut()) {
            insertPosition = cutPasteController.adjustPastePositionAfterCut(insertPosition)
            performCutDelete()
        }
        insertPosition = insertPosition.coerceIn(0, stateModel.subtitleEntries.size)

        val durationPerEntryMs = if (pasteAfterInsert) SubtitleEntryOps.DEFAULT_INSERT_DURATION_MS
            else SubtitleEntryOps.DEFAULT_NEW_SUBTITLE_DURATION_MS
        val insertedEntries = listOperationsController.createInserted(
            after, refEntry, stateModel.subtitleEntries.getOrNull(insertPosition - 1),
            stateModel.subtitleEntries.getOrNull(insertPosition),
            if (pasteAfterInsert) stateModel.clipboardTexts else listOf("新字幕"), insertPosition,
            durationPerEntryMs
        )
        stateModel.execute(EditorCommand.Insert(insertPosition, insertedEntries))
        renumberEntries(force = true)
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
            "已${if (after) "向后" else "向前"}粘贴 ${stateModel.clipboardTexts.size} 项"
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
        val insertPos = stateModel.subtitleEntries.indexOfFirst { it.startTime > realStart }
            .let { if (it == -1) stateModel.subtitleEntries.size else it }

        stateModel.execute(EditorCommand.Insert(insertPos, listOf(newEntry)))
        renumberEntries(force = true)
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
        if (deferLargeListEdit(stateModel.subtitleEntries.size) { applyOffsetToSelection(offsetMs) }) return
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("没有选中的字幕") ?: return
        
        stateModel.execute(EditorCommand.ApplyOffset(selectedEntries.map { it.second }.toSet(), offsetMs))
        notifyEntriesChanged(selectedEntries.map { it.second })
        showShortToast("已对选中项应用 ${offsetMs}ms 偏移")
    }
    
    private fun showTimeEditDialog(entry: SubtitleEntry, position: Int, isStartTime: Boolean) {
        subtitleDialogController.showTime(position, isStartTime)
    }

    private fun showTextEditDialog(entry: SubtitleEntry, position: Int) {
        subtitleDialogController.showText(position)
    }

    private fun showWebVttCueDialog(position: Int) {
        subtitleDialogController.showWebVttCue(position)
    }
    /**
     * 复制选中的字幕（支持多行）
     */
    private fun copySelected() {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要复制的字幕") ?: return
        
        stateModel.clipboardTexts = listOperationsController.copy(stateModel.subtitleEntries, selectedEntries.map { it.second })
        cutPasteController.clear()
        com.subtitleedit.util.OverwritingToast.makeText(this, "已复制 ${stateModel.clipboardTexts.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 粘贴到选中的位置
     */
    private fun pasteToSelected() {
        if (deferLargeListEdit(stateModel.subtitleEntries.size, ::pasteToSelected)) return
        if (!ensureListMode()) return
        
        if (!ensureClipboardNotEmpty()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要粘贴到的字幕") ?: return

        val selectedPositionsBeforeCut = selectedEntries.map { it.second }.sorted()
        if (stateModel.clipboardTexts.size < selectedPositionsBeforeCut.size) {
            showShortToast("剪贴板行数不足：剪贴板 ${stateModel.clipboardTexts.size} 行，当前选中 ${selectedPositionsBeforeCut.size} 行")
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
            val sortedDeleted = deletedIndices.sorted()
            var deletedBefore = 0
            selectedPositions = selectedPositionsBeforeCut.map { pos ->
                while (deletedBefore < sortedDeleted.size && sortedDeleted[deletedBefore] < pos) {
                    deletedBefore++
                }
                pos - deletedBefore
            }.filter { it >= 0 }
            performCutDelete()
        }

        if (selectedPositions.isEmpty()) {
            showShortToast("没有可粘贴到的目标位置")
            return
        }

        val pasteResult = SubtitlePasteOps.pasteToSelection(
            entries = stateModel.subtitleEntries,
            selectedPositions = selectedPositions,
            clipboardTexts = stateModel.clipboardTexts
        )

        submitSubtitleList(
            refreshAll = true,
            selectedIndices = pasteResult.affectedPositions,
            markChanged = true
        )
        com.subtitleedit.util.OverwritingToast.makeText(this, "已粘贴 ${stateModel.clipboardTexts.size} 项", Toast.LENGTH_SHORT).show()
    }
    
    private fun markAsChanged() {
        stateModel.hasUnsavedChanges = true
    }

    private fun currentHistoryListState(): EditorEditHistory.ListState =
        EditorEditHistory.ListState(
            entries = stateModel.subtitleEntries.map { it.copy() },
            selectedIds = if (::subtitleAdapter.isInitialized) {
                val currentIds = stateModel.subtitleEntries.mapTo(mutableSetOf()) { it.stableId }
                subtitleAdapter.getSelectedEntries()
                    .mapTo(mutableSetOf()) { it.first.stableId }
                    .filterTo(mutableSetOf()) { it in currentIds }
            } else {
                emptySet()
            }
        )

    private fun initializeEditHistoryBaseline(clearHistory: Boolean) {
        historyCoordinator.initialize(clearHistory)
    }

    private fun syncEditHistoryBaseline() {
        historyCoordinator.sync()
    }

    private fun recordListStateChange(selectedIdsOverride: Set<Long>? = null) {
        if (suppressHistoryRecording) return
        historyCoordinator.recordList(selectedIdsOverride)
    }

    private fun recordSourceTextChange(
        beforeText: String,
        afterText: String,
        beforeEntries: List<SubtitleEntry>? = null
    ) {
        if (suppressHistoryRecording) return
        historyCoordinator.recordSource(beforeText, afterText, beforeEntries)
    }

    private fun recordListSelectionChange() {
        if (suppressHistoryRecording || stateModel.isSourceViewMode) return
        recordListStateChange()
    }

    private fun undoEdit() {
        if (deferLargeListEdit(stateModel.subtitleEntries.size, ::undoEdit)) return
        suppressHistoryRecording = true
        val applied = try { historyCoordinator.undo() } finally { suppressHistoryRecording = false }
        if (!applied) {
            return
        }
        syncEditHistoryBaseline()
        stateModel.hasUnsavedChanges = true
    }

    private fun redoEdit() {
        if (deferLargeListEdit(stateModel.subtitleEntries.size, ::redoEdit)) return
        suppressHistoryRecording = true
        val applied = try { historyCoordinator.redo() } finally { suppressHistoryRecording = false }
        if (!applied) {
            return
        }
        syncEditHistoryBaseline()
        stateModel.hasUnsavedChanges = true
    }

    private fun applyHistoryOperation(
        command: EditorHistoryCommand,
        undo: Boolean
    ) {
        val operation = command as? EditorEditHistory.Operation ?: return
        when (operation) {
            is EditorEditHistory.Operation.ListChange -> {
                val target = if (undo) operation.before else operation.after
                val targetSourceText = if (undo) {
                    operation.beforeSourceText
                } else {
                    operation.afterSourceText
                }
                if (stateModel.isSourceViewMode) {
                    applyListHistoryInSourceView(target.entries, targetSourceText)
                } else {
                    applyListHistoryInListView(target, targetSourceText)
                }
            }
            is EditorEditHistory.Operation.SourceChange -> {
                val targetText = if (undo) operation.beforeText else operation.afterText
                val targetEntries = if (undo) {
                    operation.beforeEntries.takeIf { operation.beforeEntriesText == operation.beforeText }
                } else {
                    operation.afterEntries?.takeIf { operation.afterEntriesText == operation.afterText }
                }
                applySourceHistoryText(targetText, targetEntries)
            }
        }
    }

    private fun applyListHistoryInSourceView(
        targetEntries: List<SubtitleEntry>,
        targetSourceText: String?
    ) {
        val effectiveTargetEntries: List<SubtitleEntry>
        val updated: String
        if (targetSourceText != null) {
            effectiveTargetEntries = targetEntries
            updated = targetSourceText
        } else {
            val source = stateModel.sourceViewContent
            val currentEntries = SubtitleParser.parseDocument(source, format = stateModel.currentFormat).entries
            effectiveTargetEntries = SubtitleEntryOps.applyEditableHistoryTarget(
                current = stateModel.subtitleEntries,
                target = targetEntries
            )
            updated = SubtitleSourceSynchronizer.apply(
                content = source,
                format = stateModel.currentFormat,
                oldEntries = currentEntries,
                newEntries = effectiveTargetEntries
            )
        }
        stateModel.originalFileContent = updated
        stateModel.sourceViewContent = updated
        stateModel.sourceHistoryTextSnapshot = updated
        setSourceViewEditorText(updated, preserveScroll = true)
        applySourceViewEntries(effectiveTargetEntries.map { it.copy() })
        updateFormatInfo()
    }

    private fun applyListHistoryInListView(
        target: EditorEditHistory.ListState,
        targetSourceText: String?
    ) {
        if (targetSourceText == null && canRestoreListEntriesInPlace(target.entries)) {
            restoreListEntriesInPlace(target)
            return
        }

        val previousCount = stateModel.subtitleEntries.size
        val effectiveTargetEntries: List<SubtitleEntry>
        val updatedSource: String
        if (targetSourceText != null) {
            effectiveTargetEntries = target.entries
            updatedSource = targetSourceText
        } else {
            val source = stateModel.originalFileContent
            val currentEntries = SubtitleParser.parseDocument(source, format = stateModel.currentFormat).entries
            effectiveTargetEntries = SubtitleEntryOps.applyEditableHistoryTarget(
                current = stateModel.subtitleEntries,
                target = target.entries
            )
            updatedSource = SubtitleSourceSynchronizer.apply(
                content = source,
                format = stateModel.currentFormat,
                oldEntries = currentEntries,
                newEntries = effectiveTargetEntries
            )
        }
        stateModel.originalFileContent = updatedSource
        stateModel.sourceViewContent = updatedSource
        stateModel.sourceHistoryTextSnapshot = updatedSource
        applySourceViewEntries(effectiveTargetEntries.map { it.copy() })
        if (previousCount != stateModel.subtitleEntries.size || subtitleAdapter.itemCount != stateModel.subtitleEntries.size) {
            submitSubtitleList(
                // A structural history restore can update retained entries in place before
                // ListAdapter receives the new list.  Force a full bind so those rows do not
                // keep displaying the pre-undo contents.
                refreshAll = true,
                syncWaveform = false,
                markChanged = false
            ) {
                subtitleAdapter.setSelectionByStableIds(target.selectedIds)
                updateSelectedCountDisplay()
            }
        } else {
            subtitleAdapter.setSelectionByStableIds(target.selectedIds)
            updateSelectedCountDisplay()
        }
    }

    private fun canRestoreListEntriesInPlace(targetEntries: List<SubtitleEntry>): Boolean {
        if (stateModel.subtitleEntries.size != targetEntries.size) return false
        return stateModel.subtitleEntries.zip(targetEntries).all { (current, target) ->
            current.stableId == target.stableId &&
                current.cueIdentifier == target.cueIdentifier &&
                current.cueSettings == target.cueSettings
        }
    }

    private fun restoreListEntriesInPlace(target: EditorEditHistory.ListState) {
        val source = stateModel.originalFileContent
        val currentEntries = stateModel.subtitleEntries.toList()
        val changedPositions = currentEntries.indices.filter { position ->
            val current = currentEntries[position]
            val historical = target.entries[position]
            current.startTime != historical.startTime ||
                current.endTime != historical.endTime ||
                current.text != historical.text ||
                current.endTimeModified != historical.endTimeModified
        }
        val updatedSource = SubtitleSourceSynchronizer.apply(
            content = source,
            format = stateModel.currentFormat,
            oldEntries = currentEntries,
            newEntries = target.entries
        )

        currentEntries.forEachIndexed { position, current ->
            val historical = target.entries[position]
            current.index = historical.index
            current.startTime = historical.startTime
            current.endTime = historical.endTime
            current.text = historical.text
            current.endTimeModified = historical.endTimeModified
        }
        stateModel.originalFileContent = updatedSource
        stateModel.sourceViewContent = updatedSource
        stateModel.sourceHistoryTextSnapshot = updatedSource
        if (changedPositions.isNotEmpty()) {
            notifyEntriesChanged(
                positions = changedPositions,
                syncWaveform = true,
                markChanged = false
            )
        }
        subtitleAdapter.setSelectionByStableIds(target.selectedIds)
        updateSelectedCountDisplay()
    }

    private fun applySourceHistoryText(
        targetText: String,
        cachedEntries: List<SubtitleEntry>? = null
    ) {
        val selectedIds = currentHistoryListState().selectedIds
        val previousCount = stateModel.subtitleEntries.size
        stateModel.originalFileContent = targetText
        stateModel.sourceViewContent = targetText
        stateModel.sourceHistoryTextSnapshot = targetText
        if (stateModel.isSourceViewMode) {
            sourcePreviewController.cancel()
            stateModel.documentState.sourceViewEditGeneration++
            sourceViewHasPendingEdits = false
            setSourceViewEditorText(targetText, preserveScroll = true)
            if (cachedEntries != null) {
                applySourceViewEntries(cachedEntries)
                stateModel.documentState.sourceViewEntriesGeneration = stateModel.documentState.sourceViewEditGeneration
            } else {
                scheduleSourceViewPreview()
            }
            updateFormatInfo()
            return
        }

        if (cachedEntries == null) {
            stateModel.sourceViewNeedsListSync = false
            scheduleListSourceParse(targetText, stateModel.documentState.sourceViewEditGeneration)
            return
        }
        applySourceViewEntries(cachedEntries)
        if (previousCount != stateModel.subtitleEntries.size || subtitleAdapter.itemCount != stateModel.subtitleEntries.size) {
            submitSubtitleList(
                refreshAll = false,
                selectedStableIds = selectedIds,
                syncWaveform = false,
                markChanged = false
            )
        } else {
            subtitleAdapter.setSelectionByStableIds(selectedIds)
            updateSelectedCountDisplay()
        }
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
        val renderVersion = if (positionList.size > BULK_NOTIFY_THRESHOLD &&
            stateModel.subtitleEntries.size >= LARGE_LIST_LOADING_THRESHOLD
        ) {
            showListLoading()
            ++listRenderVersion
        } else null
        stateModel.refreshDocument()
        listPresentationController.notifyPositions(positionList, includeNeighbors)
        if (syncWaveform) syncWaveformSubtitles(changedPositions = positionList)
        if (markChanged) {
            if (!stateModel.isSourceViewMode) stateModel.sourceViewNeedsListSync = true
            recordListStateChange()
            markAsChanged()
        }
        publishComposeSubtitleEntries()
        if (::playbackController.isInitialized) playbackController.invalidateHighlightCache()
        if (::searchController.isInitialized) searchController.onDocumentChanged()
        scheduleSubtitlePreview()
        renderVersion?.let(::hideListLoadingAfterRender)
    }

    private fun syncWaveformSubtitles(
        preserveSelection: Boolean = false,
        changedPositions: Iterable<Int>? = null
    ) {
        if (changedPositions != null) {
            val changes = changedPositions.distinct().mapNotNull { index ->
                stateModel.subtitleEntries.getOrNull(index)?.let { index to it }
            }.toMap()
            if (waveformController.updateSubtitleEntries(changes, stateModel.subtitleEntries.size)) return
        }
        if (preserveSelection) {
            waveformController.setSubtitlesPreserveSelection(stateModel.subtitleEntries.toList())
        } else {
            waveformController.setSubtitles(stateModel.subtitleEntries.toList())
        }
    }

    private fun syncWaveformSubtitleRange(
        startIndex: Int,
        removedCount: Int,
        inserted: List<SubtitleEntry>
    ) {
        if (!waveformController.replaceSubtitleRange(startIndex, removedCount, inserted)) {
            syncWaveformSubtitles()
        }
    }

    private fun setWaveformSubtitlesKeepSelection(selectedIndex: Int) {
        waveformController.setSubtitlesKeepSelection(stateModel.subtitleEntries.toList(), selectedIndex)
    }

    private fun showListLoading() {
        listLoadingActive = true
        composeListLoading = true
    }

    private fun hideListLoadingAfterRender(version: Long) {
        if (!listLoadingActive || version != listRenderVersion) return
        window.decorView.doOnPreDraw {
            if (version == listRenderVersion) {
                listLoadingActive = false
                composeListLoading = false
            }
        }
        window.decorView.invalidate()
    }

    /** Let the loading overlay draw before a large synchronous model update starts. */
    private fun deferLargeListEdit(entryCount: Int, action: () -> Unit): Boolean {
        if (entryCount < LARGE_LIST_LOADING_THRESHOLD || runningDeferredListEdit || listLoadingActive) {
            return false
        }
        showListLoading()
        window.decorView.doOnPreDraw {
            window.decorView.post {
                if (isFinishing || isDestroyed) return@post
                val initialVersion = listRenderVersion
                runningDeferredListEdit = true
                try {
                    action()
                } catch (error: Exception) {
                    showShortToast("字幕更新失败：${error.message ?: "请稍后重试"}")
                } finally {
                    runningDeferredListEdit = false
                    if (initialVersion == listRenderVersion) {
                        hideListLoadingAfterRender(initialVersion)
                    }
                }
            }
        }
        return true
    }

    private fun submitSubtitleList(
        refreshAll: Boolean = false,
        selectedIndices: Set<Int>? = null,
        selectedStableIds: Set<Long>? = null,
        clearSelection: Boolean = false,
        updateFormat: Boolean = true,
        syncWaveform: Boolean = true,
        markChanged: Boolean = false,
        schedulePreview: Boolean = true,
        afterSubmit: (() -> Unit)? = null
    ) {
        val renderVersion = ++listRenderVersion
        if (maxOf(stateModel.subtitleEntries.size, subtitleAdapter.itemCount) >= LARGE_LIST_LOADING_THRESHOLD) {
            showListLoading()
        }
        renumberEntries(force = refreshAll)
        stateModel.refreshDocument()
        val targetSelectedIds = listPresentationController.targetSelection(
            stateModel.selectedIndices, selectedStableIds, clearSelection
        )
        if (markChanged) recordListStateChange(targetSelectedIds)
        subtitleAdapter.submitList(stateModel.subtitleEntries.toList()) {
            // ListAdapter replaces row objects asynchronously. Rebind selection by stable ID
            // after the new list is installed so selected rows survive source parsing and undo.
            listPresentationController.bindSelection(targetSelectedIds, clearSelection)
            publishComposeSubtitleEntries()
            if (refreshAll) {
                subtitleAdapter.refreshAllItems()
                pendingListIndexRefreshStart = null
            } else {
                pendingListIndexRefreshStart?.let { refreshStart ->
                    pendingListIndexRefreshStart = null
                    val start = refreshStart.coerceIn(0, subtitleAdapter.itemCount)
                    val count = subtitleAdapter.itemCount - start
                    if (count > 0) {
                        subtitleAdapter.notifyItemRangeChanged(start, count)
                    }
                }
            }
            afterSubmit?.invoke()
            hideListLoadingAfterRender(renderVersion)
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
        EditorDocumentOperations.addAt(stateModel.subtitleEntries, stateModel.subtitleEntries.size, SubtitleEntry(
            index = 1,
            startTime = 0L,
            endTime = 3000L,
            text = "请输入文本"
        ))
        stateModel.sourceViewContent = ""
        stateModel.originalFileContent = ""
        stateModel.sourceViewNeedsListSync = false
        stateModel.currentCharset = StandardCharsets.UTF_8
        stateModel.currentFormat = SubtitleParser.SubtitleFormat.SRT
        stateModel.isSourceViewMode = false
        composeSourceViewMode = false
        submitSubtitleList(refreshAll = true, clearSelection = true, syncWaveform = true)
        setDocumentTitle(stateModel.documentTitle)
        stateModel.currentFormatInfo = "格式：SRT | 条目数：${stateModel.subtitleEntries.size}"
        toolbarSubtitle = stateModel.currentFormatInfo
        stateModel.hasUnsavedChanges = false
        initializeEditHistoryBaseline(clearHistory = true)
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
            saveFileToUriAsync(Uri.parse(uriString))
            return
        }

        val targetFile = if (stateModel.mediaType.hasPlayableMedia) {
            stateModel.subtitleFile
        } else {
            stateModel.currentFile
        }
        
        if (stateModel.isNewFile || targetFile == null) {
            launchSaveFilePicker()
            return
        }

        val content = getCurrentEditableContent()
        if (content == null) {
            executeSaveContinuation(stateModel.saveCoordinator.complete(false))
            return
        }
        val charset = stateModel.currentCharset
        lifecycleScope.launch {
            val result = saveSessionController.saveFile(targetFile, content, charset)
            val saved = result.success
            if (!saved) showShortToast("保存失败：${result.error?.message}")
            if (saved) {
                stateModel.originalFileContent = content
                stateModel.sourceViewContent = content
                stateModel.sourceHistoryTextSnapshot = content
                stateModel.sourceViewNeedsListSync = false
                sourceViewHasPendingEdits = false
                stateModel.hasUnsavedChanges = false
                showShortToast("保存成功")
            }
            executeSaveContinuation(stateModel.saveCoordinator.complete(saved))
        }
    }
    
    private fun saveFileAs() {
        stateModel.saveCoordinator.begin(SaveContinuation.NONE)
        launchSaveFilePicker()
    }

    private fun launchSaveFilePicker() {
        val formatExtension = getFormatExtension(stateModel.currentFormat)
        saveFileLauncher.launch("subtitle.$formatExtension")
    }
    
    private fun saveFileToUriAsync(uri: Uri) {
        val content = getCurrentEditableContent()
        if (content == null) {
            executeSaveContinuation(stateModel.saveCoordinator.complete(false))
            return
        }
        val charset = stateModel.currentCharset
        lifecycleScope.launch {
            val result = saveSessionController.saveUri(uri, content, charset)
            val saved = result.success
            if (!saved) showShortToast("保存失败：${result.error?.message}")
            if (saved) {
                stateModel.originalFileContent = content
                stateModel.sourceViewContent = content
                stateModel.sourceHistoryTextSnapshot = content
                stateModel.sourceViewNeedsListSync = false
                sourceViewHasPendingEdits = false
                stateModel.hasUnsavedChanges = false
                val fileName = withContext(Dispatchers.IO) {
                    getFileNameFromUri(uri)
                }
                stateModel.saveUriSubtitleDocument(uri.toString(), fileName)
                takePersistableWritePermission(uri)
                setDocumentTitle(stateModel.documentTitle)
                showShortToast("保存成功")
            }
            executeSaveContinuation(stateModel.saveCoordinator.complete(saved))
        }
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
        }
    }
    
    private fun showEncodingDialog() {
        val encodings = FileUtils.SUPPORTED_ENCODINGS.map { it.displayName }
        val currentIndex = FileUtils.SUPPORTED_ENCODINGS.indexOfFirst { it.charset == stateModel.currentCharset }
        ComposeDialogHost.show(this) { dialog ->
            AlertDialog(
                onDismissRequest = dialog::dismiss,
                title = { Text("选择编码") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                        itemsIndexed(encodings) { index, encoding ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        dialog.dismiss()
                                        val newCharset = FileUtils.SUPPORTED_ENCODINGS[index].charset
                                        if (newCharset != stateModel.currentCharset) {
                                            showEncodingChangeDialog(newCharset)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = index == currentIndex,
                                    onClick = null
                                )
                                Text(encoding)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = dialog::dismiss) { Text("取消") }
                }
            )
        }
    }

    private fun showEncodingChangeDialog(newCharset: Charset) {
        ComposeDialogHost.show(this) { dialog ->
            AlertDialog(
                onDismissRequest = dialog::dismiss,
                title = { Text("切换编码") },
                text = { Text("请选择使用指定编码重新加载文件，或以该编码保存当前内容。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog.dismiss()
                            stateModel.currentCharset = newCharset
                            reloadFile()
                        }
                    ) { Text("重载") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = dialog::dismiss) { Text("取消") }
                        TextButton(
                            onClick = {
                                dialog.dismiss()
                                stateModel.currentCharset = newCharset
                                saveFile()
                            }
                        ) { Text("保存") }
                    }
                }
            )
        }
    }
    
    /**
     * 保存草稿
     */
    private fun saveDraft() {
        val content = getCurrentEditableContent(requireNonEmptyList = true) ?: return
        
        val fileName = stateModel.currentFile?.name ?: "未命名"
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
        if (stateModel.subtitleEntries.size < 2) {
            showShortToast(getString(R.string.merge_subtitles_requires_entries))
            return
        }

        val gapValue = mutableStateOf("")
        val gapError = mutableStateOf<String?>(null)
        ComposeDialogHost.show(this) { dialog ->
            AlertDialog(
                onDismissRequest = dialog::dismiss,
                title = { Text(getString(R.string.menu_merge_subtitles)) },
                text = {
                    OutlinedTextField(
                        value = gapValue.value,
                        onValueChange = {
                            gapValue.value = it
                            gapError.value = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(getString(R.string.merge_subtitles_gap_hint)) },
                        suffix = { Text(getString(R.string.merge_subtitles_gap_unit)) },
                        supportingText = gapError.value?.let { message ->
                            { Text(message, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val maxGapMs = gapValue.value.trim().toLongOrNull()
                            if (maxGapMs == null || maxGapMs < 0L) {
                                gapError.value = getString(R.string.merge_subtitles_invalid_gap)
                                return@TextButton
                            }
                            dialog.dismiss()
                            mergeSubtitles(maxGapMs)
                        }
                    ) { Text(getString(R.string.confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = dialog::dismiss) { Text(getString(R.string.cancel)) }
                }
            )
        }
    }

    private fun mergeSubtitles(maxGapMs: Long) {
        val groups = buildMergeGroups(stateModel.subtitleEntries, maxGapMs)
        val mergeGroups = groups.filter { it.entries.size > 1 }
        if (mergeGroups.isEmpty()) {
            showShortToast(getString(R.string.merge_subtitles_no_match))
            return
        }

        val previewItems = mergeGroups.map { group ->
            TranslationPreviewItem(
                entryPosition = group.startPosition,
                originalText = group.entries.joinToString("\n") { it.text },
                translatedText = group.mergedEntry.text
            )
        }
        textPreviewDialog.show(
            title = "字幕合并预览",
            editTitle = "编辑合并文本",
            previewItems = previewItems,
            onApply = { appliedItems ->
                applyMergedPreview(groups, appliedItems)
            }
        )
    }

    private data class MergeGroup(
        val startPosition: Int,
        val entries: List<SubtitleEntry>,
        val mergedEntry: SubtitleEntry
    )

    /** 按实际合并规则保留每个连续分组，供预览和应用阶段共享。 */
    private fun buildMergeGroups(
        entries: List<SubtitleEntry>,
        maxGapMs: Long
    ): List<MergeGroup> {
        if (entries.isEmpty()) return emptyList()

        val groups = mutableListOf<MergeGroup>()
        var startPosition = 0
        var currentEntries = mutableListOf(entries.first())
        var previousEntry = entries.first()

        entries.drop(1).forEachIndexed { offset, entry ->
            val position = offset + 1
            val shouldMerge = entry.startTime <= previousEntry.endTime ||
                entry.startTime - previousEntry.endTime <= maxGapMs
            if (shouldMerge) {
                currentEntries += entry
            } else {
                groups += createMergeGroup(startPosition, currentEntries)
                startPosition = position
                currentEntries = mutableListOf(entry)
            }
            previousEntry = entry
        }
        groups += createMergeGroup(startPosition, currentEntries)
        return groups
    }

    private fun createMergeGroup(
        startPosition: Int,
        entries: List<SubtitleEntry>
    ): MergeGroup {
        val mergedEntry = entries.first().copy()
        entries.drop(1).forEach { entry ->
            mergedEntry.startTime = minOf(mergedEntry.startTime, entry.startTime)
            mergedEntry.endTime = maxOf(mergedEntry.endTime, entry.endTime)
            mergedEntry.text = "${mergedEntry.text}；${entry.text}"
            mergedEntry.endTimeModified = mergedEntry.endTimeModified || entry.endTimeModified
        }
        return MergeGroup(startPosition, entries.toList(), mergedEntry)
    }

    private fun applyMergedPreview(
        groups: List<MergeGroup>,
        appliedItems: List<TranslationPreviewItem>
    ) {
        if (appliedItems.isNotEmpty() && deferLargeListEdit(stateModel.subtitleEntries.size) {
                applyMergedPreview(groups, appliedItems)
            }) return
        if (appliedItems.isEmpty()) {
            showShortToast("未应用任何字幕合并")
            return
        }

        val appliedByStart = appliedItems.associateBy { it.entryPosition }
        val mergedEntries = groups.flatMap { group ->
            val appliedItem = appliedByStart[group.startPosition]
            if (group.entries.size > 1 && appliedItem != null) {
                listOf(group.mergedEntry.copy(text = appliedItem.translatedText))
            } else {
                group.entries.map { it.copy() }
            }
        }
        val removedCount = stateModel.subtitleEntries.size - mergedEntries.size

        cutPasteController.clear()
        val historyBefore = currentHistoryListState()
        replaceSubtitleEntries(mergedEntries)
        stateModel.historyEntriesSnapshot = historyBefore.entries
        stateModel.historySelectionSnapshot = historyBefore.selectedIds
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
        val labels = listOf("毫秒", "秒", "分", "小时")
        val values = labels.map { mutableStateOf("0") }
        ComposeDialogHost.show(this) { dialog ->
            AlertDialog(
                onDismissRequest = dialog::dismiss,
                title = { Text(title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("输入偏移量，正数延迟，负数提前")
                        values.forEachIndexed { index, value ->
                            OutlinedTextField(
                                value = value.value,
                                onValueChange = { value.value = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(labels[index]) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.NumberSigned
                                )
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val ms = values[0].value.toLongOrNull() ?: 0L
                            val sec = values[1].value.toLongOrNull() ?: 0L
                            val min = values[2].value.toLongOrNull() ?: 0L
                            val hour = values[3].value.toLongOrNull() ?: 0L
                            dialog.dismiss()
                            onConfirm(ms + sec * 1000 + min * 60000 + hour * 3600000)
                        }
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = dialog::dismiss) { Text("取消") }
                }
            )
        }
    }
    
    private fun applyOffset(offsetMs: Long, longClickPos: Int = -1) {
        if (deferLargeListEdit(stateModel.subtitleEntries.size) { applyOffset(offsetMs, longClickPos) }) return
        if (!ensureListMode()) return

        when {
            // 有长按位置，对长按的那一行应用偏移（无论是否有选中状态）
            longClickPos >= 0 && longClickPos < stateModel.subtitleEntries.size -> {
                val entry = stateModel.subtitleEntries[longClickPos]
                stateModel.execute(EditorCommand.ApplyOffset(setOf(longClickPos), offsetMs))
                
                notifyEntriesChanged(listOf(longClickPos))
            }
            // 没有长按位置但有选中的字幕，对选中的字幕应用偏移
            subtitleAdapter.getSelectedCount() > 0 -> {
                val selectedEntries = subtitleAdapter.getSelectedEntries()
                stateModel.execute(EditorCommand.ApplyOffset(selectedEntries.map { it.second }.toSet(), offsetMs))
                
                notifyEntriesChanged(selectedEntries.map { it.second })
            }
            // 都没有，对所有字幕应用偏移
            else -> {
                stateModel.execute(EditorCommand.ApplyOffset(stateModel.subtitleEntries.indices.toSet(), offsetMs))
                
                submitSubtitleList(refreshAll = true, markChanged = true)
            }
        }
        showShortToast("已应用 ${offsetMs}ms 偏移")
    }
    
    private fun deleteSelectedSubtitles() {
        if (!ensureListMode()) return
        
        val selectedEntries = requireSelectedEntries("请先选择要删除的字幕") ?: return
        
        showDeleteConfirm("确定要删除选中的字幕吗？") {
                if (deferLargeListEdit(stateModel.subtitleEntries.size) {
                        deleteSelectedSubtitlesConfirmed(selectedEntries)
                    }) return@showDeleteConfirm
                deleteSelectedSubtitlesConfirmed(selectedEntries)
        }
    }

    private fun deleteSelectedSubtitlesConfirmed(selectedEntries: List<Pair<SubtitleEntry, Int>>) {
        val historyBefore = currentHistoryListState()
        val deletedIndices = selectedEntries.map { it.second }.toSet()
        stateModel.execute(EditorCommand.Delete(deletedIndices))
        syncAfterDelete(deletedIndices, historyBefore)
        com.subtitleedit.util.OverwritingToast.makeText(this, "已删除 ${selectedEntries.size} 条字幕", Toast.LENGTH_SHORT).show()
    }

    /**
     * 删除字幕后同步状态（保持未删除项的选中状态）
     * @param deletedIndices 被删除的索引集合（删除前的索引）
     */
    private fun syncAfterDelete(
        deletedIndices: Set<Int>,
        historyBefore: EditorEditHistory.ListState
    ) {
        stateModel.historyEntriesSnapshot = historyBefore.entries
        stateModel.historySelectionSnapshot = historyBefore.selectedIds
        val remainingIds = stateModel.subtitleEntries.mapTo(mutableSetOf()) { it.stableId }
        submitSubtitleList(
            refreshAll = false,
            selectedStableIds = stateModel.historySelectionSnapshot.filterTo(mutableSetOf()) { selectedId ->
                selectedId in remainingIds
            },
            syncWaveform = false,
            markChanged = true,
            afterSubmit = {
                val firstDeleted = deletedIndices.minOrNull()
                    ?.coerceAtMost(stateModel.subtitleEntries.size)
                    ?: stateModel.subtitleEntries.size
                val remainingCount = stateModel.subtitleEntries.size - firstDeleted
                if (remainingCount > 0) {
                    subtitleAdapter.notifyItemRangeChanged(firstDeleted, remainingCount)
                }
                if (deletedIndices.size <= BULK_NOTIFY_THRESHOLD) {
                    deletedIndices.sorted().forEachIndexed { offset, deletedIdx ->
                        val prevIdx = (deletedIdx - offset) - 1
                        if (prevIdx >= 0 && prevIdx < stateModel.subtitleEntries.size) {
                            subtitleAdapter.notifyItemChanged(prevIdx)
                        }
                    }
                }
            }
        )
        if (!waveformController.removeSubtitleIndices(deletedIndices)) {
            syncWaveformSubtitles(preserveSelection = true)
        }
    }

    /**
     * 取消所有选择的字幕
     */
    private fun cancelSelection() {
        if (deferLargeListEdit(stateModel.subtitleEntries.size, ::cancelSelection)) return
        if (!ensureListMode()) return
        
        subtitleAdapter.clearSelection()
        recordListStateChange()
        updateSelectedCountDisplay()
    }

    private fun selectAllSubtitles() {
        if (deferLargeListEdit(stateModel.subtitleEntries.size, ::selectAllSubtitles)) return
        if (!ensureListMode() || stateModel.subtitleEntries.isEmpty()) return

        if (subtitleAdapter.getSelectedCount() == stateModel.subtitleEntries.size) {
            subtitleAdapter.setAllSelection(false)
        } else {
            subtitleAdapter.setAllSelection(true)
        }
        recordListStateChange()
        updateSelectedCountDisplay()
    }

    private fun selectRangeBetweenSelectedSubtitles() {
        if (deferLargeListEdit(stateModel.subtitleEntries.size, ::selectRangeBetweenSelectedSubtitles)) return
        if (!ensureListMode()) return

        val selectedPositions = subtitleAdapter.getSelectedPositions().sorted()
        if (selectedPositions.size < 2) {
            showShortToast("请先选择至少两行字幕")
            return
        }

        val range = SelectionRangePolicy.contiguousRange(selectedPositions) ?: return
        val rangeSet = range.toSet()
        if (rangeSet.all { it in selectedPositions }) return

        subtitleAdapter.setSelectionByIndices(rangeSet)
        recordListStateChange()
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
        val audioFile = stateModel.currentFile?.takeIf { stateModel.mediaType.hasPlayableMedia } ?: run {
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
            timelineEntries = stateModel.subtitleEntries.toList(),
            audioFile = audioFile,
            audioCacheKey = audioCacheKey,
            audioStreamIndex = stateModel.selectedAudioStreamIndex
        )
    }

    /** 把预览对话框中勾选应用的文本写回字幕列表。 */
    private fun applyPreviewTexts(appliedItems: List<TranslationPreviewItem>, actionName: String) {
        if (deferLargeListEdit(stateModel.subtitleEntries.size) {
                applyPreviewTexts(appliedItems, actionName)
            }) return
        appliedItems.forEach { item ->
            stateModel.subtitleEntries.getOrNull(item.entryPosition)?.text = item.translatedText
        }
        if (appliedItems.isNotEmpty()) {
            notifyEntriesChanged(appliedItems.map { it.entryPosition }, includeNeighbors = false)
        }
        showShortToast("已应用 ${appliedItems.size} 条$actionName")
    }

    private fun saveTranslationDraft(previewItems: List<TranslationPreviewItem>) {
        val draftEntries = stateModel.subtitleEntries.map { it.copy() }.toMutableList()
        previewItems.filter { it.apply }.forEach { item ->
            draftEntries.getOrNull(item.entryPosition)?.text = item.translatedText
        }
        val fileName = stateModel.currentFile?.name ?: "未命名"
        val draftContent = serializeEntriesForFormat(stateModel.currentFormat, draftEntries)
        val savedFileName = DraftManager.saveDraft(this, fileName, draftContent)
        showShortToast("翻译草稿已保存：$savedFileName")
    }
    
    private fun renumberEntries(force: Boolean = false) {
        val currentCount = stateModel.subtitleEntries.size
        if (!force && currentCount == stateModel.lastIndexedEntryCount) return
        EditorDocumentOperations.renumber(stateModel.subtitleEntries)
        stateModel.lastIndexedEntryCount = currentCount
    }

    private fun replaceSubtitleEntries(
        entries: List<SubtitleEntry>,
        preserveStableIds: Boolean = false
    ) {
        val previous = stateModel.subtitleEntries
        stateModel.subtitleEntries = if (preserveStableIds) {
            SubtitleEntryOps.retainStableIds(previous, entries).toMutableList()
        } else {
            entries.toMutableList()
        }
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
        if (stateModel.subtitleEntries.size != updatedEntries.size) {
            val previousEntries = stateModel.subtitleEntries.toList()
            val previousIds = previousEntries.mapTo(mutableSetOf()) { it.stableId }
            val associatedEntries = if (updatedEntries.any { it.stableId in previousIds }) {
                updatedEntries.map { it.copy() }
            } else {
                SubtitleEntryOps.retainStableIds(previousEntries, updatedEntries)
            }
            val prefix = SubtitleStableRange.commonPrefix(previousEntries, associatedEntries)
            val suffix = SubtitleStableRange.commonSuffix(previousEntries, associatedEntries, prefix)
            val removedCount = previousEntries.size - prefix - suffix
            val previousById = previousEntries.associateBy { it.stableId }
            val retainedEntries = associatedEntries.map { parsedEntry ->
                previousById[parsedEntry.stableId]?.also {
                    EditorDocumentOperations.updateFields(it, parsedEntry)
                }
                    ?: parsedEntry
            }
            val inserted = retainedEntries.subList(prefix, retainedEntries.size - suffix)
            stateModel.subtitleEntries = EditorDocumentOperations.replaceEntries(previousEntries)
            stateModel.subtitleEntries.subList(prefix, prefix + removedCount).clear()
            EditorDocumentOperations.addAllAt(stateModel.subtitleEntries, prefix, inserted)
            renumberEntries(force = true)
            pendingListIndexRefreshStart = minOf(
                pendingListIndexRefreshStart ?: prefix,
                prefix
            )
            syncWaveformSubtitleRange(prefix, removedCount, inserted)
            return
        }

        val changedPositions = updatedEntries.indices.filter { position ->
            val current = stateModel.subtitleEntries[position]
            val updated = updatedEntries[position]
            current.index != updated.index ||
                current.startTime != updated.startTime ||
                current.endTime != updated.endTime ||
                current.text != updated.text ||
                current.endTimeModified != updated.endTimeModified ||
                current.cueIdentifier != updated.cueIdentifier ||
                current.cueSettings != updated.cueSettings
        }
        changedPositions.forEach { position ->
            val target = stateModel.subtitleEntries[position]
            val source = updatedEntries[position]
            EditorDocumentOperations.updateFields(target, source)
        }
        renumberEntries(force = true)

        if (changedPositions.isEmpty()) {
            syncWaveformSubtitles(preserveSelection = true, changedPositions = changedPositions)
        } else if (::subtitleAdapter.isInitialized && subtitleAdapter.itemCount == stateModel.subtitleEntries.size) {
            // Reuse the same payload/neighbour refresh path as list-view edits.  The
            // The Compose list is hidden in source mode, but retaining its row references keeps
            // the two editing modes consistent when the user switches back.
            notifyEntriesChanged(
                changedPositions,
                includeNeighbors = true,
                syncWaveform = false,
                markChanged = false
            )
            syncWaveformSubtitles(preserveSelection = true, changedPositions = changedPositions)
        } else {
            syncWaveformSubtitles(preserveSelection = true, changedPositions = changedPositions)
        }
    }

    /**
     * Distinguish an empty-but-valid source document from a temporarily malformed cue.  Once
     * the last timing marker has actually been removed, the old waveform blocks must be
     * cleared; while a marker is still present we keep them until parsing succeeds.
     */
    private fun sourceContainsSubtitleMarker(content: String): Boolean {
        return SubtitleFormatPolicy.containsSubtitleMarker(content, stateModel.currentFormat)
    }

    private fun clearSubtitleEntries() {
        EditorDocumentOperations.clear(stateModel.subtitleEntries)
        renumberEntries(force = true)
    }
    
    private fun updateFormatInfo() {
        val formatName = SubtitleFormatPolicy.displayName(stateModel.currentFormat)
        val countInfo = if (stateModel.isSourceViewMode) {
            val lines = if (stateModel.sourceViewContent.isEmpty()) {
                0
            } else {
                sourceEditorState.getDocumentLineCount()
            }
            "行数：$lines"
        } else {
            "条目数：${stateModel.subtitleEntries.size}"
        }
        stateModel.currentFormatInfo = "格式：$formatName | $countInfo"
        toolbarSubtitle = stateModel.currentFormatInfo
    }

    private fun getFormatExtension(format: SubtitleParser.SubtitleFormat): String =
        SubtitleFormatPolicy.extension(format)

    private fun serializeEntriesForFormat(format: SubtitleParser.SubtitleFormat): String {
        return serializeEntriesForFormat(format, stateModel.subtitleEntries)
    }

    private fun serializeEntriesForFormat(
        format: SubtitleParser.SubtitleFormat,
        entries: List<SubtitleEntry>
    ): String {
        if (format == SubtitleParser.SubtitleFormat.ASS ||
            format == SubtitleParser.SubtitleFormat.SSA
        ) return stateModel.sourceViewContent

        return SubtitleSerialization.serialize(
            stateModel.subtitleDocument.copy(
                header = stateModel.documentHeader,
                footer = stateModel.documentFooter
            ),
            format,
            entries,
            stateModel.sourceViewContent
        )
    }

    private fun getCurrentEditableContent(requireNonEmptyList: Boolean = false): String? {
        val sourceContent = if (stateModel.isSourceViewMode) snapshotSourceViewContentIfNeeded() else null
        val content = stateModel.buildSaveContent(sourceContent, requireNonEmptyList)
        if (content == null) {
            showShortToast("没有内容可保存")
            return null
        }
        return content
    }

    private fun ensureListMode(): Boolean {
        if (!stateModel.isSourceViewMode) return true
        showShortToast("源视图模式下不支持此操作")
        return false
    }

    private fun ensureMediaMode(): Boolean {
        if (stateModel.mediaType.hasPlayableMedia) return true
        showShortToast("此功能仅在打开音频或视频文件时可用")
        return false
    }

    private fun ensureClipboardNotEmpty(): Boolean {
        if (stateModel.clipboardTexts.isNotEmpty()) return true
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
        confirmationDialogController.show(title, message, positiveText, negativeText, onConfirm)
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
        confirmationDialogController.runAfterUnsavedChangesConfirmed(message, action)
    }

    private fun showReplaceAllConfirm(count: Int, onConfirm: () -> Unit) {
        confirmationDialogController.showReplaceAll(count, onConfirm)
    }

    private fun showDeleteConfirm(message: String, onConfirm: () -> Unit) {
        confirmationDialogController.showDelete(message, onConfirm)
    }

    private fun getCurrentSubtitleFile(): File? {
        return if (stateModel.mediaType.hasPlayableMedia) stateModel.subtitleFile else stateModel.currentFile
    }

    private fun finishWithToast(message: String) {
        showShortToast(message)
        finish()
    }

    private fun showShortToast(message: String) {
        com.subtitleedit.util.OverwritingToast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onStop() {
        editorCoordinator.onStop()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        editorCoordinator.onStart()
    }

    override fun onDestroy() {
        sourceViewTransitionJob?.cancel()
        editorCoordinator.onDestroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        editorCoordinator.onWindowFocusChanged(hasFocus)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        editorCoordinator.onConfigurationChanged()
    }

    // ==================== 媒体播放器相关方法 ====================
    
    private fun splitSubtitleAtPlaybackHead() {
        if (deferLargeListEdit(stateModel.subtitleEntries.size) { splitSubtitleAtPlaybackHead() }) return
        if (!ensureListMode()) return
        val playhead = playbackController.currentPositionMs
        val position = stateModel.subtitleEntries.indexOfFirst { entry ->
            playhead > entry.startTime && playhead < entry.endTime
        }
        if (position < 0) {
            showShortToast("播放头不在字幕范围内")
            return
        }
        val entry = stateModel.subtitleEntries[position]
        val duration = entry.endTime - entry.startTime
        if (duration <= 1L) return
        val split = SubtitleTextSplitOps.split(
            this,
            entry.text,
            (playhead - entry.startTime).toFloat() / duration.toFloat()
        )
        val historyBefore = currentHistoryListState()
        val selectedIds = historyBefore.selectedIds - entry.stableId
        val result = stateModel.execute(
            EditorCommand.Split(position, playhead, split.left, split.right)
        )
        if (!result.structureChanged) return
        stateModel.historyEntriesSnapshot = historyBefore.entries
        stateModel.historySelectionSnapshot = historyBefore.selectedIds
        submitSubtitleList(
            refreshAll = true,
            selectedStableIds = selectedIds,
            syncWaveform = false,
            markChanged = true
        )
        syncWaveformSubtitleRange(
            position,
            removedCount = 1,
            inserted = stateModel.subtitleEntries.subList(position, position + 2).toList()
        )
        showShortToast("已拆分字幕")
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
        if (stateModel.filePath.isEmpty() || stateModel.currentFile == null) {
            showShortToast("媒体文件路径无效")
            finish()
            return
        }

        val originalFile = stateModel.currentFile ?: return
        if (!originalFile.exists()) {
            showShortToast("媒体文件不存在")
            finish()
            return
        }

        setDocumentTitle(originalFile.name)

        if (stateModel.mediaType == EditorMediaType.VIDEO) {
            doLoadMediaFile(
                playbackFile = originalFile,
                analysisFile = originalFile,
                subtitleFilePath = subtitleFilePath,
                restoreDocument = restoreDocument
            )
            return
        }

        val checkingDialog = ComposeDialogHost.show(this) { _ ->
            AlertDialog(
                onDismissRequest = {},
                text = {
                    Text(
                        if (stateModel.isAudioOnlyFromVideo) {
                            "正在检测视频音轨..."
                        } else {
                            "正在准备音频文件..."
                        }
                    )
                },
                confirmButton = {}
            )
        }

        lifecycleScope.launch {
            val preparedAudio = try {
                mediaRepository.prepareAudio(
                    originalFile,
                    inspectVideoAudioTrack = stateModel.isAudioOnlyFromVideo
                )
            } catch (error: CancellationException) {
                checkingDialog.dismiss()
                throw error
            } catch (error: Exception) {
                checkingDialog.dismiss()
                prepareMediaDocument(stateModel.subtitleFilePath, restoreDocument)
                stateModel.documentLoaded = true
                showShortToast(error.message ?: "加载音频失败")
                return@launch
            }
            checkingDialog.dismiss()

            doLoadMediaFile(
                playbackFile = preparedAudio.playbackFile,
                analysisFile = originalFile,
                subtitleFilePath = subtitleFilePath,
                restoreDocument = restoreDocument,
                audioStreamIndex = preparedAudio.audioStreamIndex
            )
            val issues = preparedAudio.mp3Issues
            if (issues.hasIssues && !isFinishing && !isDestroyed) {
                val details = buildList {
                    issues.nonZeroStartTimeSeconds?.let { startTime ->
                        add(getString(R.string.mp3_file_issue_start_time, startTime))
                    }
                    if (issues.dataRateBelowNominalBitrate) {
                        add(getString(R.string.mp3_file_issue_low_data_rate))
                    }
                    if (issues.dataRateAboveNominalBitrate) {
                        add(getString(R.string.mp3_file_issue_high_data_rate))
                    }
                }.joinToString("\n") { "• $it" }
                ComposeDialogHost.show(this@EditorActivity) { dialog ->
                    AlertDialog(
                        onDismissRequest = dialog::dismiss,
                        title = { Text(getString(R.string.mp3_file_warning_title)) },
                        text = { Text(getString(R.string.mp3_file_warning_message, details)) },
                        confirmButton = {
                            TextButton(onClick = dialog::dismiss) {
                                Text(getString(android.R.string.ok))
                            }
                        }
                    )
                }
            }
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
            return
        }
        when (val preparation = mediaDocumentController.subtitlePreparation(stateModel.subtitleFilePath, false)) {
            is EditorMediaDocumentController.Preparation.Companion -> loadSubtitleFile(preparation.file)
            EditorMediaDocumentController.Preparation.Empty -> {
                prepareEmptyMediaDocument()
                if (!stateModel.subtitleFilePath.isNullOrBlank()) showShortToast("未找到同名字幕文件")
            }
        }
    }

    private fun prepareEmptyMediaDocument() {
        clearSubtitleEntries()
        stateModel.currentFormat = SubtitleParser.SubtitleFormat.SRT
        stateModel.isSourceViewMode = false
        composeSourceViewMode = false
        stateModel.sourceViewContent = ""
        stateModel.originalFileContent = ""
        stateModel.sourceViewNeedsListSync = false
        submitSubtitleList(refreshAll = true, syncWaveform = false)
        initializeEditHistoryBaseline(clearHistory = true)
    }

    /**
     * 加载字幕文件
     */
    private fun loadSubtitleFile(subtitleFile: File) {
        val settingsManager = SettingsManager.getInstance(this)
        stateModel.currentCharset = settingsManager.getDefaultEncoding()
        val charset = stateModel.currentCharset

        lifecycleScope.launch {
            val content = runCatching {
                withContext(Dispatchers.IO) {
                    stateModel.subtitleRepository.readFile(subtitleFile, charset)
                }
            }.getOrElse {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this@EditorActivity,
                    "读取字幕文件失败：${it.message}",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            parseContent(content, subtitleFile.name)
            stateModel.hasUnsavedChanges = false
            stateModel.isNewFile = false
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
        stateModel.execute(EditorCommand.UpdateTime(position, startTime = newStartTime))
        
        notifyEntriesChanged(listOf(position))
        
        if (newStartTime >= entry.endTime) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "开始时间已设置，但大于结束时间，请调整结束时间", Toast.LENGTH_LONG).show()
        } else {
            showShortToast("已将开始时间设置为 ${TimeUtils.formatForDisplay(newStartTime)}")
        }
    }
    
}

@Composable
private fun EditorFullscreenVideo(
    playbackState: EditorPlaybackUiState,
    videoPlayerView: EditorMpvView,
    onTogglePlayPause: () -> Unit,
    onSeekStarted: () -> Unit,
    onSeekProgress: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSpeedClick: () -> Unit,
    onToggleFullscreen: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { videoPlayerView },
            modifier = Modifier.fillMaxSize()
        )
        EditorPlaybackPanel(
            state = playbackState,
            onTogglePlayPause = onTogglePlayPause,
            onSeekStarted = onSeekStarted,
            onSeekProgress = onSeekProgress,
            onSeekFinished = onSeekFinished,
            onSpeedClick = onSpeedClick,
            onToggleFullscreen = onToggleFullscreen,
            isFullscreen = true,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun EditorActionListDialog(
    title: String?,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
    onAction: (() -> Unit) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { currentTitle -> ({ Text(currentTitle) }) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(actions) { _, (label, action) ->
                    TextButton(
                        onClick = { onAction(action) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 10.dp
                        )
                    ) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {}
    )
}
