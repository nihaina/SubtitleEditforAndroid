package com.subtitleedit

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import com.subtitleedit.player.MainPlayerController
import com.subtitleedit.player.PlayerMediaNotifications
import com.subtitleedit.player.MainPlayerHost
import com.subtitleedit.player.LocalPlayerLauncher
import com.subtitleedit.player.PlayerPlayMode
import com.subtitleedit.player.PlayerQueueSource
import com.subtitleedit.player.PlayerQueueSourceKind
import com.subtitleedit.player.PlayerTrack
import com.subtitleedit.player.PlayerViewModel
import com.subtitleedit.player.putPlayerQueueSource
import com.subtitleedit.player.hidePlayerSystemBars
import com.subtitleedit.player.updatePlayerCutoutMode
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.feature.ui.ArchiveCreateSubmission
import com.subtitleedit.feature.ui.ArchiveCreationDialogUi
import com.subtitleedit.feature.ui.ArchiveFormatOptionUi
import com.subtitleedit.feature.ui.ArchiveSplitOptionUi
import com.subtitleedit.feature.ui.BatchRenameDialogUi
import com.subtitleedit.feature.ui.MainCreateItemError
import com.subtitleedit.feature.ui.MainCreateItemField
import com.subtitleedit.feature.ui.MainCreateItemKind
import com.subtitleedit.feature.ui.MainActivityDialogUi
import com.subtitleedit.feature.ui.MainActivityScreen
import com.subtitleedit.feature.ui.MainActivityScreenState
import com.subtitleedit.feature.ui.MainDirectoryScrollRequest
import com.subtitleedit.feature.ui.MainDirectoryRevealRequest
import com.subtitleedit.feature.ui.MainFileOperationDialogs
import com.subtitleedit.feature.ui.MediaProcessingChooserDialog
import com.subtitleedit.feature.ui.SubtitleConversionDialogUi
import com.subtitleedit.feature.ui.SubtitleConversionResultUi
import com.subtitleedit.repository.ArchiveRepository
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.ArchiveManager
import com.subtitleedit.util.BatchRename
import com.subtitleedit.util.BatchRenameConflictResolver
import com.subtitleedit.util.ArchivePreviewCache
import com.subtitleedit.model.FileBrowserOrder
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.library.MediaLibraryFileObserver
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.FileTransferManager
import com.subtitleedit.util.FileDeletionManager
import com.subtitleedit.util.FileBrowserNavigation
import com.subtitleedit.util.ArchiveNamePolicy
import com.subtitleedit.util.ArchiveErrorPolicy
import com.subtitleedit.util.AndroidDirectoryPolicy
import com.subtitleedit.util.FilePathPolicy
import com.subtitleedit.util.SelectionRangePolicy
import com.subtitleedit.util.MainBackNavigationPolicy
import com.subtitleedit.util.MainNavigationPolicy
import com.subtitleedit.util.MainLifecycleCoordinator
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileSelectionPolicy
import com.subtitleedit.util.FileOperationUiPolicy
import com.subtitleedit.util.ArchiveActionUiPolicy
import com.subtitleedit.util.ArchiveActionUiPolicy.ArchiveAction
import com.subtitleedit.util.ArchivePasswordVault
import com.subtitleedit.util.DirectoryWatcher
import com.subtitleedit.util.DirectoryChanges
import com.subtitleedit.util.BrowserDirectorySnapshot
import com.subtitleedit.util.DirectorySnapshotLoader
import com.subtitleedit.util.DirectoryDisplayOptions
import com.subtitleedit.util.DirectoryDisplayPreparer
import com.subtitleedit.util.PreparedDirectoryDisplay
import com.subtitleedit.util.SharedFileCache
import com.subtitleedit.util.DirectorySearchController
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.MediaCacheCleaner
import com.subtitleedit.util.SubtitleFormatConverter
import com.subtitleedit.util.UpdateChecker
import com.subtitleedit.ui.settings.SettingsCacheItem
import com.subtitleedit.ui.MediaCategoryRevealRequest
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主界面 - 文件浏览器
 */
class MainActivity : AppComposeActivity() {

    private val archiveRepository: ArchiveRepository
        get() = (application as SubtitleEditApplication).dependencies.archiveRepository

    private companion object {
        // Long file tasks outlive configuration changes; UI callbacks resolve
        // the Activity that is currently attached instead of retaining the old one.
        var activeInstance: WeakReference<MainActivity>? = null
        const val BACK_EXIT_CONFIRM_WINDOW_MS = 2_000L
    }

    private fun isCurrentTaskHost(): Boolean = activeInstance?.get() === this

    private lateinit var filePropertiesDialogController: FilePropertiesDialogController
    private lateinit var topLevelNavigationCoordinator: MainTopLevelNavigationCoordinator
    private lateinit var mediaOpenController: MediaOpenController
    private lateinit var archivePasswordDialogController: ArchivePasswordDialogController
    private lateinit var archiveConflictDialogController: ArchiveConflictDialogController
    private lateinit var archiveProgressDialogController: ArchiveProgressDialogController
    private lateinit var archiveCompressionController: ArchiveCompressionController
    private lateinit var archiveExtractionRunner: ArchiveExtractionRunner
    private lateinit var archiveTestRunner: ArchiveTestRunner
    private lateinit var archiveActionDialogController: ArchiveActionDialogController
    private val stateModel: MainViewModel by viewModels()
    private val playerModel: PlayerViewModel by viewModels()
    private lateinit var playerController: MainPlayerController
    private var playerImmersive = false
    private var playerKeepScreenOn = false
    private var categoryRevealRequest by mutableStateOf<MediaCategoryRevealRequest?>(null)
    private var mainVolumeControlStream: Int? = null
    private var mainCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT

    private var visibleFiles = emptyList<File>()
    private var directorySnapshot: BrowserDirectorySnapshot? = null
    private var pendingDirectorySnapshot: BrowserDirectorySnapshot? = null
    private var displayedMetadata: Map<String, BrowserFileMetadata> = emptyMap()
    private var displayedFiles = emptyList<File>()
    private var directoryEmptyStateVisible = false
    private var directoryEmptyStateMessage = R.string.no_files
    private var showAllFileTypes = false
    private var showHiddenFiles = false
    private var showFullFileNames = false
    private var directoryLoading = false
    private val directoryNavigationPending: Boolean
        get() = directoryLoading && directorySnapshot?.directory?.absolutePath !=
            stateModel.documentState.currentDirectory?.absolutePath
    private var searchInProgress = false
    private var relativePathRoot: File? = null
    private var customToolbarTitle: String? = null
    private var customToolbarBack: (() -> Unit)? = null
    private var customToolbarHasBack = false
    private var topLevelPageRefreshVersion by mutableIntStateOf(0)
    private var topLevelPageSelectionVersion by mutableIntStateOf(0)
    private var searchExpanded = false
    private var directoryScrollPosition: (() -> DirectoryScrollPosition)? = null
    private var directoryScrollRequest: MainDirectoryScrollRequest? = null
    private var directoryRevealRequest: MainDirectoryRevealRequest? = null
    private var nextDirectoryScrollGeneration = 0L
    private var screenState by mutableStateOf(MainActivityScreenState())
    private var subtitleConversionDialog by mutableStateOf<SubtitleConversionDialogUi?>(null)
    private var subtitleConversionResult by mutableStateOf<SubtitleConversionResultUi?>(null)
    private var archiveCreationDialog by mutableStateOf<ArchiveCreationDialogUi?>(null)
    private var mainDialog by mutableStateOf<MainActivityDialogUi?>(null)
    private var mediaProcessingSelection by mutableStateOf<MediaProcessingSelection?>(null)
    private var pendingSubtitleConversionSources: List<SubtitleConversionSource>? = null
    private var pendingArchiveDraft: PendingArchiveDraft? = null
    private var pendingRenameFile: File? = null
    private var pendingDeleteFiles: List<File>? = null
    private var pendingMediaDeleteSuccess: (() -> Unit)? = null
    private var pendingMediaCacheFiles: List<File>? = null
    private var nextSubtitleConversionDialogId = 0
    private val directoryWatcher = DirectoryWatcher(
        onRefresh = ::refreshWatchedDirectory,
        onWatchGap = { path ->
            if (::directorySnapshotLoader.isInitialized &&
                path !in MediaLibraryFileObserver.observedDirectories.value) directorySnapshotLoader.invalidate(path)
        }
    )
    private lateinit var directorySnapshotLoader: DirectorySnapshotLoader
    private lateinit var directoryDisplayPreparer: DirectoryDisplayPreparer
    private lateinit var directorySearchController: DirectorySearchController
    private lateinit var backNavigationCallback: OnBackPressedCallback
    private var lastBackExitPromptAt = 0L
    private lateinit var lifecycleCoordinator: MainLifecycleCoordinator
    private var fileCopyJob: Job? = null
    private data class PendingArchiveDraft(val sources: List<File>, val outputDirectory: File)

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            loadInitialDirectory()
        } else {
            showPermissionDeniedDialog()
        }
    }
    
    // 管理外部存储权限请求
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            loadInitialDirectory()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val settingsManager = com.subtitleedit.util.SettingsManager.getInstance(this)
        super.onCreate(savedInstanceState)
        mainVolumeControlStream = volumeControlStream
        if (com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled) {
            volumeControlStream = AudioManager.STREAM_MUSIC
        }
        if (Build.VERSION.SDK_INT >= 28) mainCutoutMode = window.attributes.layoutInDisplayCutoutMode
        playerController = MainPlayerController(this, playerModel).also {
            it.restoreState(savedInstanceState)
            lifecycle.addObserver(it)
        }
        openPlayerFromNotification(intent)
        activeInstance = WeakReference(this)
        directorySnapshotLoader = DirectorySnapshotLoader(
            lifecycleScope, stateModel.directoryCache, ::applyDirectorySnapshot
        )
        directoryDisplayPreparer = DirectoryDisplayPreparer(lifecycleScope, ::applyDirectoryDisplay)
        showFullFileNames = settingsManager.isShowFullFileNamesEnabled()
        screenState = MainActivityScreenState(
            selectedTopLevelItem = MainNavigationPolicy.pageForMode(
                stateModel.documentState.selectedTopLevelItem,
                com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled
            ),
            playerModeEnabled = com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled,
            showFullFileNames = showFullFileNames
        )
        searchExpanded = stateModel.documentState.isFileSearchActive &&
            stateModel.documentState.searchQuery.isNotEmpty()
        filePropertiesDialogController = FilePropertiesDialogController(this, ::showShortToast)
        mediaOpenController = MediaOpenController(this, ::openMediaWithSubtitle)
        archivePasswordDialogController = ArchivePasswordDialogController(
            activityProvider = { activeInstance?.get() },
            showToast = { message -> activeInstance?.get()?.showShortToast(message) }
        )
        archiveConflictDialogController = ArchiveConflictDialogController { activeInstance?.get() }
        archiveProgressDialogController = ArchiveProgressDialogController { activeInstance?.get() }
        archiveActionDialogController = ArchiveActionDialogController { activeInstance?.get() }
        archiveCompressionController = ArchiveCompressionController(
            scope = stateModel.viewModelScope,
            repository = archiveRepository,
            progressController = archiveProgressDialogController,
            onExitSelection = { activeInstance?.get()?.exitSelectionMode() },
            onRefreshDirectory = { directory -> activeInstance?.get()?.loadDirectory(directory) },
            onToast = { message -> activeInstance?.get()?.showShortToast(message) },
            onError = { title, error -> activeInstance?.get()?.showOperationError(title, error) },
            onDeleteFailures = { output, count -> activeInstance?.get()?.let { current ->
                current.mainDialog = MainActivityDialogUi.Message(
                    title = "压缩已完成",
                    message = "${output.name} 已创建，但有 $count 个源文件无法删除。"
                )
            }
            }
        )
        archiveExtractionRunner = ArchiveExtractionRunner(
            scope = stateModel.viewModelScope,
            repository = archiveRepository,
            progressController = archiveProgressDialogController
        )
        archiveTestRunner = ArchiveTestRunner(
            scope = stateModel.viewModelScope,
            repository = archiveRepository,
            progressController = archiveProgressDialogController
        )
        directorySearchController = DirectorySearchController(
            scope = lifecycleScope,
            canEnterDirectory = { file -> !isRestrictedAndroidDirectory(file) },
            onPartialResult = { files ->
                showSearchDirectoryFiles(files, searching = true)
            },
            onCompleted = { files ->
                showSearchDirectoryFiles(files, searching = false)
            },
            onFinished = {
                searchInProgress = false
                publishScreenState()
            },
            createMetadataReader = ::createSearchMetadataReader
        )
        showAllFileTypes = settingsManager.isShowAllFileTypesEnabled()
        showHiddenFiles = settingsManager.isShowHiddenFilesEnabled()
        if (stateModel.documentState.sortField == null) {
            stateModel.documentState.sortField = settingsManager.getFileSortField()
        }
        if (stateModel.documentState.sortDirection == null) {
            stateModel.documentState.sortDirection = settingsManager.getFileSortDirection()
        }
        if (stateModel.documentState.viewMode == null) {
            stateModel.documentState.viewMode = settingsManager.getFileViewMode()
        }
        
        topLevelNavigationCoordinator = MainTopLevelNavigationCoordinator(
            activity = this,
            state = stateModel.documentState,
            saveDirectoryScroll = ::saveCurrentDirectoryScrollPosition,
            loadDirectory = { directory, restore -> loadDirectory(directory, restore) },
            cancelDirectorySearch = {
                directorySearchController.cancel()
                directoryDisplayPreparer.cancel()
            },
            stopDirectoryWatcher = { directoryWatcher.stop() },
            clearDirectorySelection = {
                stateModel.documentState.directoryHistory.clear()
                stateModel.documentState.selectedPaths.clear()
                stateModel.documentState.pendingFileOperation = null
                stateModel.documentState.pendingArchiveFile = null
                stateModel.documentState.searchQuery = ""
                stateModel.documentState.isFileSearchActive = false
                searchExpanded = false
            },
            onPageChanged = { selectedPage ->
                stateModel.documentState.selectedTopLevelItem = selectedPage
                // The XML toolbar removed the directory SearchView whenever a
                // top-level page was selected. Keep the query in state so it
                // can be restored on return, but hide the expanded field while
                // the directory page is not visible.
                if (selectedPage != R.id.nav_directory) {
                    searchExpanded = false
                } else if (selectedPage == R.id.nav_directory &&
                    stateModel.documentState.isFileSearchActive &&
                    stateModel.documentState.searchQuery.isNotEmpty()
                ) {
                    searchExpanded = true
                }
                publishScreenState()
            },
            onToolbarChanged = { title, showBack, onBack ->
                customToolbarTitle = title
                customToolbarHasBack = showBack
                customToolbarBack = onBack
                publishScreenState()
            }
        )
        setContent {
            SubtitleEditComposeTheme {
                CompositionLocalProvider(com.subtitleedit.ui.LocalMediaSelection provides
                    if (screenState.playerModeEnabled) stateModel.mediaSelection else null) {
                com.subtitleedit.ui.MediaSelectionActionHost(
                    controller = stateModel.mediaSelection,
                    onAddToQueue = { paths -> playerModel.appendQueueTracks(paths.map { path ->
                        val file = File(path)
                        PlayerTrack(path, file.name, FileTypePolicy.isVideo(file))
                    }) },
                    onRemoveFromQueue = { paths -> playerModel.removeFromCurrentQueue(paths.toSet()) },
                    onPlayNext = { paths -> playerModel.enqueueNextTracks(paths.map { path ->
                        val file = File(path)
                        PlayerTrack(path, file.name, FileTypePolicy.isVideo(file))
                    }) },
                    onEditTags = playerController::openTagEditor,
                    onLocate = ::locateMediaFile,
                    onDeleteFiles = ::confirmDeleteMediaFiles,
                    immersive = playerModel.expanded.collectAsState().value,
                    onNavigateToCategory = ::revealPlayerTrackInCategory
                )
                MainPlayerHost(playerModel, playerController::openTagEditor, playerController::openSubtitlePicker,
                    onImmersiveChanged = ::updatePlayerWindow,
                    onNavigateToCategory = ::revealPlayerTrackInCategory,
                    onLocate = ::locateMediaFile,
                    enabled = screenState.playerModeEnabled) { miniPlayerSlot ->
                CompositionLocalProvider(
                    LocalPlayerLauncher provides if (screenState.playerModeEnabled) ::openPlayerQueue else null,
                    com.subtitleedit.player.LocalPlayerQueueModel provides
                        if (screenState.playerModeEnabled) playerModel else null
                ) {
                MainActivityScreen(
                        state = screenState,
                        onOpenPageTranslation = { startActivity(Intent(this, PageTranslationSettingsActivity::class.java)) },
                        topLevelPageRefreshVersion = topLevelPageRefreshVersion,
                        topLevelPageSelectionVersion = topLevelPageSelectionVersion,
                        onDirectoryScrollPosition = { directoryScrollPosition = it },
                        onTopLevelPageSelected = ::showTopLevelPage,
                        onOpenDirectoryFromFavorites = ::openDirectoryFromFavorites,
                        onUpdateTopLevelToolbar = ::updateTopLevelToolbar,
                        onSearchRequested = ::openFileSearch,
                        onSearchChanged = ::changeFileSearch,
                        onCreateItem = ::createBrowserItem,
                        onSortFieldChanged = ::changeFileSortField,
                        onSortDirectionChanged = ::changeFileSortDirection,
                        onViewModeChanged = ::changeFileViewMode,
                        onTogglePlayerMode = ::togglePlayerMode,
                        onOnlineClicked = ::showOnlinePlaceholder,
                        onOpenFileManagementSettings = {
                            startActivity(Intent(this@MainActivity, FileManagementSettingsActivity::class.java))
                        },
                        onSelectAll = ::selectAllVisibleFiles,
                        onSelectRange = ::selectRangeBetweenSelectedFiles,
                        onToolbarBack = ::onToolbarBack,
                        onDirectoryBreadcrumbClick = ::navigateToDirectoryBreadcrumb,
                        onFileClick = ::onFileClicked,
                        onFileLongClick = ::enterSelectionMode,
                        imagePreviewFile = stateModel.imagePreviewFile,
                        onImagePreviewPageChanged = { file ->
                            if (stateModel.imagePreviewFile != null) stateModel.imagePreviewFile = file
                        },
                        onCloseImagePreview = ::closeImagePreview,
                        onShareImagePreview = ::shareImagePreview,
                        onDeleteImagePreview = { file -> confirmDeleteFiles(listOf(file).filter(File::isFile)) },
                        onShowImagePreviewInfo = { file -> filePropertiesDialogController.show(listOf(file)) },
                        onCopy = { startDestinationSelection(FileOperation.COPY) },
                        onMove = { startDestinationSelection(FileOperation.MOVE) },
                        onRename = ::renameSelectedFile,
                        onDelete = ::confirmDeleteSelectedFiles,
                        onConvertSelection = ::showSubtitleFormatConvertDialog,
                        onCompressSelection = ::showCreateArchiveDialog,
                        onEditTagsSelection = ::editSelectedMediaTags,
                        onProcessMediaSelection = ::processSelectedMedia,
                        onClearCacheSelection = ::showSelectedMediaCacheDialog,
                        onShowSelectionProperties = ::showSelectedProperties,
                        onConfirmDestination = ::completeDestinationOperation,
                        onCancelDestination = ::cancelDestinationSelection,
                        subtitleConversion = subtitleConversionDialog,
                        subtitleConversionResult = subtitleConversionResult,
                        archiveCreation = archiveCreationDialog,
                        onDismissSubtitleConversion = ::dismissSubtitleConversion,
                        onConvertSubtitle = ::convertSelectedSubtitles,
                        onDismissConversionResult = { subtitleConversionResult = null },
                        onDismissArchiveCreation = ::dismissCreateArchiveDialog,
                        onCreateArchive = ::submitArchiveCreation,
                        onLoadArchivePasswords = ::loadArchivePasswords,
                        onSaveArchivePassword = ::saveArchivePassword,
                        onClearArchivePasswordBook = ::clearArchivePasswordBook,
                        mainDialog = mainDialog,
                        onDismissMainDialog = ::dismissMainDialog,
                        onPermissionDialogConfirm = ::confirmPermissionDialog,
                        onRenameConfirm = ::confirmRename,
                        batchRename = stateModel.batchRenameDialog,
                        onDismissBatchRename = ::dismissBatchRename,
                        onBatchRenameConfirm = ::confirmBatchRename,
                        onBatchRenameOrderChanged = ::changeBatchRenameOrder,
                        onDeleteBatchRenameHistory = ::deleteBatchRenameHistory,
                        onDeleteConfirm = ::deleteConfirmedSelection,
                        onCacheItemSelected = ::confirmSelectedMediaCacheClear,
                        onCacheClearConfirm = ::clearSelectedMediaCache,
                        miniPlayerSlot = miniPlayerSlot,
                        categoryRevealRequest = categoryRevealRequest,
                        onDirectoryRevealFinished = { token ->
                            if (directoryRevealRequest?.token == token) {
                                directoryRevealRequest = null
                                publishScreenState()
                            }
                        }
                )
                mediaProcessingSelection?.let { selection ->
                    MediaProcessingChooserDialog(
                        canConvert = selection.supports(MediaProcessingPage.CONVERT),
                        canMux = selection.supports(MediaProcessingPage.MUX),
                        onDismiss = { mediaProcessingSelection = null },
                        onSelect = ::openSelectedMediaProcessingPage
                    )
                }
                }
                }
                }
            }
        }
        setupBackNavigation()
        topLevelNavigationCoordinator.bind()
        lifecycleCoordinator = MainLifecycleCoordinator(
            activity = this,
            lifecycleOwner = this,
            scope = lifecycleScope,
            directoryWatcher = directoryWatcher,
            shouldShowDirectory = { stateModel.documentState.selectedTopLevelItem == R.id.nav_directory },
            refreshDirectory = { stateModel.documentState.currentDirectory?.let(::loadDirectory) },
            saveDirectoryScroll = ::saveCurrentDirectoryScrollPosition,
            readFileFilters = {
                SettingsManager.getInstance(this).isShowAllFileTypesEnabled() to
                    SettingsManager.getInstance(this).isShowHiddenFilesEnabled()
            },
            applyFileFilters = { showAll, showHidden ->
                showAllFileTypes = showAll
                showHiddenFiles = showHidden
            },
            shouldCheckUpdates = {
                SettingsManager.getInstance(this).shouldCheckUpdatesOnStartup()
            },
            showUpdate = ::showPendingUpdate
        )
        checkPermissions()
        observeSharedLibraryCache()
        intent.getStringExtra(EXTRA_REVEAL_MEDIA_PATH)?.let {
            intent.removeExtra(EXTRA_REVEAL_MEDIA_PATH)
            locateMediaFile(it)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openPlayerFromNotification(intent)
        intent.getStringExtra(EXTRA_REVEAL_MEDIA_PATH)?.let {
            intent.removeExtra(EXTRA_REVEAL_MEDIA_PATH)
            locateMediaFile(it)
        }
    }

    private fun openPlayerFromNotification(intent: Intent?) {
        if (intent?.getBooleanExtra(PlayerMediaNotifications.EXTRA_OPEN_PLAYER, false) != true) return
        intent.removeExtra(PlayerMediaNotifications.EXTRA_OPEN_PLAYER)
        if (com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled &&
            playerModel.state.value.currentTrack != null) playerModel.expand()
    }

    private fun setupBackNavigation() {
        backNavigationCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        }
        onBackPressedDispatcher.addCallback(this, backNavigationCallback)
    }

    override fun onResume() {
        super.onResume()
        applyPlayerWindow()
        showFullFileNames = SettingsManager.getInstance(this).isShowFullFileNamesEnabled()
        lifecycleCoordinator.onResume()
        refreshPlayerModeNavigation()
        topLevelPageRefreshVersion++
    }

    override fun onPause() {
        lifecycleCoordinator.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        playerController.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        val target = intent.component?.className
        val ownsMpv = target == EditorActivity::class.java.name || target == com.subtitleedit.player.PlayerActivity::class.java.name
        if (ownsMpv) playerModel.suspendOutput()
        try {
            super.startActivityForResult(intent, requestCode, options)
        } catch (error: RuntimeException) {
            if (ownsMpv) playerController.cancelPlaybackActivityLaunch()
            throw error
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyPlayerWindow()
    }

    private fun updatePlayerWindow(immersive: Boolean, keepScreenOn: Boolean) {
        if (playerImmersive == immersive && playerKeepScreenOn == keepScreenOn) return
        playerImmersive = immersive
        playerKeepScreenOn = keepScreenOn
        applyPlayerWindow()
    }

    private fun applyPlayerWindow() {
        if (playerImmersive) {
            window.updatePlayerCutoutMode(resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE, mainCutoutMode)
            window.hidePlayerSystemBars()
        } else {
            window.updatePlayerCutoutMode(false, mainCutoutMode)
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        if (playerKeepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showPendingUpdate(update: UpdateChecker.UpdateInfo) {
        if (isFinishing || isDestroyed) return
        UpdateChecker.showUpdateDialog(this, update)
    }

    override fun onDestroy() {
        if (::lifecycleCoordinator.isInitialized) lifecycleCoordinator.onDestroy()
        if (activeInstance?.get() === this) activeInstance = null
        super.onDestroy()
    }
    
    private fun revealPlayerTrackInCategory(request: MediaCategoryRevealRequest) {
        if (!com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled) return
        categoryRevealRequest = request
        playerModel.collapse()
        showTopLevelPage(R.id.nav_tools)
    }

    private fun showTopLevelPage(itemId: Int) {
        val target = MainNavigationPolicy.pageForMode(
            itemId, com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled
        )
        if (target == stateModel.documentState.selectedTopLevelItem) return
        closeImagePreview()
        topLevelPageSelectionVersion++
        topLevelNavigationCoordinator.showPage(target)
    }

    private fun locateMediaFile(path: String) {
        val file = File(path).absoluteFile
        val parent = file.parentFile ?: return
        if (!file.isFile || !parent.isDirectory) {
            showShortToast(getString(R.string.media_lib_file_missing))
            return
        }
        closeImagePreview()
        playerModel.collapse()
        showTopLevelPage(R.id.nav_directory)
        clearFileSearch(refreshDirectory = false)
        directoryRevealRequest = MainDirectoryRevealRequest(file.path, ++nextDirectoryScrollGeneration)
        val position = DirectoryScrollPosition(file.path, 0, 0,
            stateModel.documentState.viewMode ?: FileBrowserViewMode.DETAILED)
        stateModel.documentState.directoryScrollPositions[directoryPath(parent)] = position
        stateModel.documentState.directoryHistory.clear()
        loadDirectory(parent, restoreScrollPosition = true)
    }

    private fun publishScreenState() {
        val document = stateModel.documentState
        val isSelectionActive = document.selectedPaths.isNotEmpty() || document.pendingFileOperation != null
        val selectionTitle = FileOperationUiPolicy.selectionTitle(
            document.pendingFileOperation,
            document.selectedPaths.size
        )
        val selectedFiles = selectedFiles()
        val canConvertSelected = selectedFiles.isNotEmpty() && selectedFiles.all {
            it.isFile && FileUtils.isSubtitleFile(it)
        }
        val canClearCacheSelected = selectedFiles.isNotEmpty() && selectedFiles.all {
            it.isFile && (FileUtils.isAudioFile(it) || FileTypePolicy.isVideo(it))
        }
        val canEditTagsSelected = selectedFiles.size == document.selectedPaths.size &&
            MediaTagFileSelectionPolicy.fromFiles(selectedFiles, ::isAudioOrVideoFile) != null
        val canProcessMediaSelected = selectedFiles.size == document.selectedPaths.size &&
            MediaProcessingSelectionPolicy.fromFiles(selectedFiles, ::isAudioOrVideoFile) != null
        screenState = MainActivityScreenState(
            selectedTopLevelItem = document.selectedTopLevelItem,
            // Selection actions and title belong to the directory content. The
            // legacy top-level pages could be shown while a directory selection
            // remained stored, but their toolbar continued to show the page title.
            toolbarTitle = if (document.selectedTopLevelItem == R.id.nav_directory && isSelectionActive) {
                selectionTitle
            } else {
                customToolbarTitle.orEmpty()
            },
            toolbarHasBack = customToolbarHasBack,
            isDirectoryLoading = directoryLoading,
            isDirectoryNavigationPending = directoryNavigationPending,
            isSearchInProgress = searchInProgress,
            showDirectoryEmptyState = directoryEmptyStateVisible,
            directoryEmptyStateMessage = directoryEmptyStateMessage,
            // Keep the displayed path and rows from the same snapshot until navigation is ready.
            currentDirectory = directorySnapshot?.directory ?: document.currentDirectory,
            canonicalDirectoryPath = directorySnapshot?.canonicalPath,
            files = displayedFiles,
            fileMetadata = displayedMetadata,
            playerModeEnabled = com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled,
            scrollRequest = directoryScrollRequest,
            directoryRevealRequest = directoryRevealRequest,
            relativePathRoot = relativePathRoot,
            selectedPaths = document.selectedPaths.toSet(),
            pendingOperation = document.pendingFileOperation,
            isSearchExpanded = searchExpanded && !isSelectionActive,
            searchQuery = document.searchQuery,
            sortField = document.sortField ?: FileSortField.NAME,
            sortDirection = document.sortDirection ?: FileSortDirection.ASCENDING,
            viewMode = document.viewMode ?: FileBrowserViewMode.DETAILED,
            showFullFileNames = showFullFileNames,
            canConvertSelected = canConvertSelected,
            canCompressSelected = !isFileSearchQueryActive(),
            canEditTagsSelected = canEditTagsSelected,
            canProcessMediaSelected = canProcessMediaSelected,
            canClearCacheSelected = canClearCacheSelected
        )
    }

    private fun openFileSearch() {
        if (stateModel.documentState.selectedPaths.isNotEmpty() ||
            stateModel.documentState.pendingFileOperation != null
        ) return
        stateModel.documentState.isFileSearchActive = true
        searchExpanded = true
        publishScreenState()
    }

    private fun changeFileSearch(query: String) {
        if (stateModel.documentState.selectedPaths.isNotEmpty() ||
            stateModel.documentState.pendingFileOperation != null
        ) return
        if (query == stateModel.documentState.searchQuery) return
        stateModel.documentState.isFileSearchActive = true
        stateModel.documentState.searchQuery = query
        displayDirectoryFiles()
    }

    private fun closeFileSearch() {
        if (stateModel.documentState.selectedPaths.isNotEmpty() ||
            stateModel.documentState.pendingFileOperation != null
        ) return
        searchExpanded = false
        clearFileSearch(refreshDirectory = true)
        publishScreenState()
    }

    private fun onToolbarBack() {
        when {
            stateModel.documentState.selectedPaths.isNotEmpty() ||
                stateModel.documentState.pendingFileOperation != null -> exitSelectionMode()
            searchExpanded -> closeFileSearch()
            customToolbarHasBack -> customToolbarBack?.invoke()
        }
    }

    fun updateTopLevelToolbar(title: String, showBack: Boolean = false, onBack: (() -> Unit)? = null) {
        topLevelNavigationCoordinator.updateToolbar(title, showBack, onBack)
    }

    fun openDirectoryFromFavorites(directory: File) {
        topLevelNavigationCoordinator.openDirectoryFromFavorites(directory)
    }

    private fun createBrowserItem(
        kind: MainCreateItemKind,
        nameInput: String,
        extensionInput: String
    ): MainCreateItemError? {
        if (directoryNavigationPending) return MainCreateItemError(MainCreateItemField.NAME, "目录正在加载，请稍候")
        FileBrowserOrder.validateName(nameInput)?.let {
            return MainCreateItemError(MainCreateItemField.NAME, it)
        }
        if (kind == MainCreateItemKind.FILE) {
            FileBrowserOrder.validateExtension(extensionInput)?.let {
                return MainCreateItemError(MainCreateItemField.EXTENSION, it)
            }
        }
        val directory = stateModel.documentState.currentDirectory
            ?: return MainCreateItemError(MainCreateItemField.NAME, "当前目录不可用")
        val name = if (kind == MainCreateItemKind.FOLDER) nameInput.trim()
        else FileBrowserOrder.composeFileName(nameInput, extensionInput)
        val target = File(directory, name)
        if (target.exists()) return MainCreateItemError(MainCreateItemField.NAME, "同名项目已存在")
        val created = runCatching {
            if (kind == MainCreateItemKind.FOLDER) target.mkdir() else target.createNewFile()
        }.getOrDefault(false)
        if (!created) {
            return MainCreateItemError(MainCreateItemField.NAME, "创建失败，请检查目录写入权限")
        }
        loadDirectory(directory)
        return null
    }

    private fun changeFileSortField(field: FileSortField) {
        stateModel.documentState.sortField = field
        SettingsManager.getInstance(this).setFileSortField(field)
        displayDirectoryFiles()
    }

    private fun changeFileSortDirection(direction: FileSortDirection) {
        stateModel.documentState.sortDirection = direction
        SettingsManager.getInstance(this).setFileSortDirection(direction)
        displayDirectoryFiles()
    }

    private fun changeFileViewMode(mode: FileBrowserViewMode) {
        if (stateModel.documentState.viewMode == mode) return
        val directory = directorySnapshot?.directory ?: stateModel.documentState.currentDirectory
        val position = directoryScrollPosition?.invoke()
        if (directory != null && displayedFiles.isNotEmpty() && position != null) {
            directoryScrollRequest = MainDirectoryScrollRequest(++nextDirectoryScrollGeneration, directoryPath(directory), position.copy(offset = 0))
        }
        stateModel.documentState.viewMode = mode
        SettingsManager.getInstance(this).setFileViewMode(mode)
        publishScreenState()
    }
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 需要 MANAGE_EXTERNAL_STORAGE
            if (Environment.isExternalStorageManager()) {
                loadInitialDirectory()
            } else {
                requestManageStoragePermission()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10 需要 READ_EXTERNAL_STORAGE
            val readPermission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, readPermission) 
                == PackageManager.PERMISSION_GRANTED) {
                loadInitialDirectory()
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        readPermission,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        } else {
            // Android 5.x 不需要运行时权限
            loadInitialDirectory()
        }
    }
    
    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageStorageLauncher.launch(intent)
            }
        }
    }
    
    private fun showPermissionDeniedDialog() {
        mainDialog = MainActivityDialogUi.PermissionDenied
    }

    private fun dismissMainDialog() {
        mainDialog = null
        pendingRenameFile = null
        pendingDeleteFiles = null
        pendingMediaDeleteSuccess = null
        pendingMediaCacheFiles = null
    }

    private fun confirmPermissionDialog() {
        dismissMainDialog()
        checkPermissions()
    }
    
    private fun getDefaultDirectory(): File {
        return Environment.getExternalStorageDirectory()
    }

    private fun loadInitialDirectory() {
        val restored = stateModel.documentState.currentDirectory?.takeIf { it.exists() && it.canRead() }
        if (loadDirectory(restored ?: getDefaultDirectory(), restoreScrollPosition = restored != null)) return
        if (restored == null) return

        stateModel.documentState.directoryHistory.clear()
        stateModel.documentState.selectedPaths.clear()
        stateModel.documentState.pendingFileOperation = null
        stateModel.documentState.pendingArchiveFile = null
        loadDirectory(getDefaultDirectory())
    }
    
    private fun loadDirectory(directory: File, restoreScrollPosition: Boolean = false): Boolean {
        if (activeInstance?.get() !== this) return false
        if (!directory.exists() || !directory.canRead()) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "无法访问目录：${directory.name}", Toast.LENGTH_SHORT).show()
            return false
        }
        
        stateModel.documentState.currentDirectory = directory
        if (directoryRevealRequest?.path?.let { File(it).parentFile?.absolutePath != directory.absolutePath } == true) {
            directoryRevealRequest = null
        }
        directoryDisplayPreparer.cancel()
        pendingDirectorySnapshot = null
        directorySearchController.cancel()
        searchInProgress = false
        directoryLoading = true
        publishScreenState()
        watchDirectory(directory)
        directorySnapshotLoader.load(directory, restoreScrollPosition)
        return true
    }

    private fun applyDirectorySnapshot(snapshot: BrowserDirectorySnapshot, restoreScrollPosition: Boolean) {
        if (stateModel.documentState.currentDirectory?.absolutePath != snapshot.directory.absolutePath) return
        pendingDirectorySnapshot = snapshot
        if (stateModel.documentState.selectedTopLevelItem != R.id.nav_directory) return
        displayDirectoryFiles(restoreScrollPosition)
    }

    private fun displayDirectoryFiles(restoreScrollPosition: Boolean = false) {
        directorySearchController.cancel()
        searchInProgress = false
        if (stateModel.documentState.selectedTopLevelItem != R.id.nav_directory) return

        val directory = stateModel.documentState.currentDirectory ?: run {
            publishScreenState()
            return
        }
        val query = if (stateModel.documentState.isFileSearchActive) {
            stateModel.documentState.searchQuery.trim()
        } else {
            ""
        }
        val snapshot = (pendingDirectorySnapshot ?: directorySnapshot)
            ?.takeIf { it.directory.absolutePath == directory.absolutePath }
        // Sorting or clearing search during a jump must not publish an empty target list.
        if (snapshot == null && directoryLoading) return
        directoryDisplayPreparer.prepare(
            snapshot ?: BrowserDirectorySnapshot(directory, emptyList()),
            DirectoryDisplayOptions(
                query, showHiddenFiles, showAllFileTypes,
                stateModel.documentState.sortField ?: FileSortField.NAME,
                stateModel.documentState.sortDirection ?: FileSortDirection.ASCENDING,
                revealPath = directoryRevealRequest?.path
            ),
            restoreScrollPosition
        )
        publishScreenState()
    }

    private fun applyDirectoryDisplay(prepared: PreparedDirectoryDisplay, restoreScrollPosition: Boolean) {
        val snapshot = prepared.snapshot
        val directory = snapshot.directory
        if (stateModel.documentState.currentDirectory?.absolutePath != directory.absolutePath) return
        if (stateModel.documentState.selectedTopLevelItem != R.id.nav_directory) return
        // Publish the path and prepared rows together, after the worker has finished.
        directorySnapshot = snapshot
        if (pendingDirectorySnapshot === snapshot) pendingDirectorySnapshot = null
        directoryLoading = false
        if (stateModel.documentState.selectedTopLevelItem == R.id.nav_directory) {
            watchDirectory(directory, prepared.childDirectories)
        }
        val query = prepared.options.query
        if (query.isEmpty()) {
            showDirectoryFiles(
                prepared.files,
                metadata = prepared.metadata,
                showParent = true,
                relativePathRoot = null,
                searching = false,
                restoreScrollPosition = restoreScrollPosition
            )
            return
        }

        searchInProgress = true
        showDirectoryFiles(
            prepared.files,
            metadata = prepared.metadata,
            showParent = false,
            relativePathRoot = directory,
            searching = true,
            restoreScrollPosition = restoreScrollPosition
        )

        directorySearchController.search(
            root = directory,
            query = query,
            includeHidden = prepared.options.showHiddenFiles,
            sortField = prepared.options.sortField,
            sortDirection = prepared.options.sortDirection,
            isCurrent = {
                stateModel.documentState.currentDirectory?.absolutePath == directory.absolutePath &&
                    stateModel.documentState.searchQuery.trim() == query
            }
        )
    }

    private fun showDirectoryFiles(
        displayed: List<File>,
        metadata: Map<String, BrowserFileMetadata>,
        showParent: Boolean,
        relativePathRoot: File?,
        searching: Boolean,
        restoreScrollPosition: Boolean = false
    ) {
        visibleFiles = displayed

        val adapterItems = mutableListOf<File>()
        val directory = stateModel.documentState.currentDirectory
        val parent = directorySnapshot?.takeIf { it.directory.absolutePath == directory?.absolutePath }?.parent.takeIf { showParent }
        parent?.let { adapterItems.add(it.file) }
        adapterItems.addAll(displayed)
        this.relativePathRoot = relativePathRoot
        displayedFiles = adapterItems
        displayedMetadata = if (parent == null) metadata else metadata + (parent.path to parent)
        directoryEmptyStateVisible = adapterItems.isEmpty()
        directoryEmptyStateMessage = if (searching) R.string.file_searching else R.string.no_files
        val directoryPath = stateModel.documentState.currentDirectory?.let(::directoryPath)
        val savedScrollPosition = if (restoreScrollPosition && directoryPath != null) {
            stateModel.documentState.directoryScrollPositions[directoryPath]
        } else {
            null
        }
        directoryScrollRequest = if (restoreScrollPosition && directoryPath != null) {
            MainDirectoryScrollRequest(++nextDirectoryScrollGeneration, directoryPath, savedScrollPosition)
        } else {
            null
        }
        searchInProgress = searching
        updateSelectionUi()
    }

    private fun directoryPath(directory: File): String =
        directorySnapshot?.takeIf { it.directory.absolutePath == directory.absolutePath }?.canonicalPath
            ?: FilePathPolicy.canonicalOrAbsolute(directory)

    private fun saveCurrentDirectoryScrollPosition() {
        val directory = directorySnapshot?.directory ?: stateModel.documentState.currentDirectory ?: return
        val position = directoryScrollPosition?.invoke() ?: return
        stateModel.documentState.directoryScrollPositions[directoryPath(directory)] = position
    }

    private fun createSearchMetadataReader(): (File) -> BrowserFileMetadata? {
        val directEntries = directorySnapshot?.takeIf {
            it.directory.absolutePath == stateModel.documentState.currentDirectory?.absolutePath
        }?.entries.orEmpty().associateBy { it.path }
        return { file ->
            directEntries[file.absolutePath] ?: if (file.parentFile?.absolutePath in
                MediaLibraryFileObserver.observedDirectories.value) SharedFileCache.attributes.get(file)
            else SharedFileCache.attributes.refresh(file)
        }
    }

    private fun showSearchDirectoryFiles(entries: List<BrowserFileMetadata>, searching: Boolean) {
        val root = stateModel.documentState.currentDirectory ?: return
        showDirectoryFiles(entries.map { it.file }, entries.associateBy { it.path },
            showParent = false, relativePathRoot = root, searching = searching)
    }

    private fun refreshWatchedDirectory(changes: DirectoryChanges) {
        if (changes.directoryPath in MediaLibraryFileObserver.observedDirectories.value) return
        directorySnapshotLoader.update(changes)
    }

    private fun watchDirectory(directory: File, children: List<File>? = null) {
        val observed = MediaLibraryFileObserver.observedDirectories.value
        if (directory.absolutePath in observed) directoryWatcher.stop()
        else directoryWatcher.watch(directory, children?.filter { it.absolutePath !in observed })
    }

    private fun observeSharedLibraryCache() {
        lifecycleScope.launch {
            var previousCoverage = emptySet<String>()
            MediaLibraryFileObserver.observedDirectories.collect { observed ->
                val oldCoverage = previousCoverage
                previousCoverage = observed
                val directory = stateModel.documentState.currentDirectory ?: return@collect
                val wasCovered = directory.absolutePath in oldCoverage
                val isCovered = directory.absolutePath in observed
                val entries = directorySnapshot?.takeIf { it.directory == directory }?.entries.orEmpty()
                val coverageChanged = wasCovered != isCovered || (!isCovered && entries.any {
                    it.isDirectory && !it.restricted && (it.path in oldCoverage) != (it.path in observed)
                })
                if (!coverageChanged) return@collect
                if (isCovered) directoryWatcher.stop()
                else if (stateModel.documentState.selectedTopLevelItem == R.id.nav_directory &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    watchDirectory(directory, entries
                        .filter { it.isDirectory && !it.restricted }.map { it.file })
                    if (wasCovered) directorySnapshotLoader.load(directory)
                }
            }
        }
        lifecycleScope.launch {
            var previousPath: String? = null
            var previousVersion = -1L
            MediaLibraryFileObserver.directoryUpdates.revision.collect {
                val directory = stateModel.documentState.currentDirectory ?: return@collect
                val path = directory.absolutePath
                val version = MediaLibraryFileObserver.directoryUpdates.versionOf(path)
                if ((previousPath != path || previousVersion != version) &&
                    version > 0L &&
                    stateModel.documentState.selectedTopLevelItem == R.id.nav_directory &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    directorySnapshotLoader.load(directory)
                    previousPath = path
                    previousVersion = version
                }
            }
        }
    }
    
    private fun onFileClicked(file: File) {
        if (directoryNavigationPending) return
        if (com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled &&
            stateModel.mediaSelection.active && file.isFile) {
            if (isAudioOrVideoFile(file)) stateModel.mediaSelection.toggle(file.absolutePath)
            return
        }
        // 处理父目录导航
        if (file.name == "..") {
            // 普通文件选择期间暂时锁定顶部父目录项，避免点击文件夹选择后
            // 意外离开当前目录。复制/移动等目标目录选择仍允许返回上级。
            if (stateModel.documentState.selectedPaths.isNotEmpty() && stateModel.documentState.pendingFileOperation == null) return
            if (stateModel.documentState.pendingFileOperation != null) navigateDestinationUp() else goUpLevel()
            return
        }

        if (isRestrictedAndroidDirectory(file)) {
            showShortToast(getString(R.string.android_directory_access_denied))
            return
        }

        if (stateModel.documentState.pendingFileOperation != null) {
            if (file.isDirectory) {
                navigateDestinationInto(file)
            } else {
                showShortToast("请选择目标文件夹")
            }
            return
        }

        if (stateModel.documentState.selectedPaths.isNotEmpty()) {
            // 选中状态下点击文件或文件夹都只切换选中状态；目录导航通过
            // 退出选择模式后进行，避免选择文件夹时触发列表刷新和跳转。
            toggleSelection(file)
            return
        }
        
        if (file.isDirectory) {
            // 进入子目录
            navigateIntoDirectory(file)
        } else if (archiveRepository.isRecognizedArchive(file)) {
            if (archiveRepository.isSupportedArchive(file)) {
                showArchiveActions(file)
            } else {
                showShortToast("当前库暂不支持 ${file.extension.uppercase()} 格式")
            }
        } else if (FileUtils.isSubtitleFile(file) || FileTypePolicy.isText(file)) {
            // 打开字幕或文本文件进行编辑
            openFileForEdit(file)
        } else if (com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled &&
            (FileUtils.isAudioFile(file) || FileTypePolicy.isVideo(file))
        ) {
            openPlayer(file)
        } else if (FileUtils.isAudioFile(file)) {
            openMediaFileForEdit(file, EditorMediaType.AUDIO)
        } else if (FileTypePolicy.isVideo(file)) {
            openMediaFileForEdit(file, EditorMediaType.VIDEO)
        } else if (FileTypePolicy.isImage(file)) {
            stateModel.imagePreviewFile = file
        } else {
            com.subtitleedit.util.OverwritingToast.makeText(this, "不支持的文件格式", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeImagePreview() {
        stateModel.imagePreviewFile = null
    }

    private fun shareImagePreview(file: File) {
        try {
            check(file.isFile)
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.media_selection_share)))
        } catch (_: RuntimeException) {
            showShortToast(getString(R.string.directory_image_preview_share_failed))
        }
    }

    private fun navigateToDirectoryBreadcrumb(directory: File): Boolean {
        val current = stateModel.documentState.currentDirectory ?: return false
        if (stateModel.documentState.selectedPaths.isNotEmpty() &&
            stateModel.documentState.pendingFileOperation == null
        ) return false
        if (!directory.isAbsolute || !directory.isDirectory) {
            showShortToast(getString(R.string.directory_not_found))
            return false
        }
        val target = File(directoryPath(directory))
        if (isRestrictedAndroidDirectory(target)) {
            showShortToast(getString(R.string.android_directory_access_denied))
            return false
        }
        if (directoryPath(current) == directoryPath(target)) {
            if (searchExpanded || isFileSearchQueryActive()) clearFileSearch(refreshDirectory = true)
            return true
        }

        saveCurrentDirectoryScrollPosition()
        if (loadDirectory(target, restoreScrollPosition = true)) {
            // Back should continue to the target's parent, not a skipped descendant.
            stateModel.documentState.directoryHistory.clear()
            clearFileSearch(refreshDirectory = false)
            return true
        }
        return false
    }

    private fun navigateIntoDirectory(directory: File) {
        val previousDirectory = stateModel.documentState.currentDirectory ?: return
        saveCurrentDirectoryScrollPosition()
        val wasSearching = isFileSearchQueryActive()
        val historyEntries = if (wasSearching) {
            FileBrowserNavigation.historyForSearchResult(previousDirectory, directory)
        } else {
            listOf(previousDirectory)
        }
        if (wasSearching) {
            clearFileSearch(refreshDirectory = false)
        }

        if (loadDirectory(directory, restoreScrollPosition = true)) {
            stateModel.documentState.directoryHistory.addAll(historyEntries)
        }
    }

    private fun isFileSearchQueryActive(): Boolean =
        stateModel.documentState.isFileSearchActive && stateModel.documentState.searchQuery.isNotBlank()

    private fun clearFileSearch(refreshDirectory: Boolean) {
        stateModel.documentState.isFileSearchActive = false
        stateModel.documentState.searchQuery = ""
        searchExpanded = false
        searchInProgress = false
        directorySearchController.cancel()
        if (refreshDirectory) displayDirectoryFiles()
        else publishScreenState()
    }

    private fun isRestrictedAndroidDirectory(file: File): Boolean =
        AndroidDirectoryPolicy.isRestricted(file)

    private fun enterSelectionMode(file: File) {
        if (directoryNavigationPending) return
        if (com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled &&
            stateModel.mediaSelection.active) {
            if (isAudioOrVideoFile(file)) stateModel.mediaSelection.select(file.absolutePath)
            return
        }
        if (file.name == ".." || stateModel.documentState.pendingFileOperation != null) return
        if (file.absolutePath in stateModel.documentState.selectedPaths) {
            toggleSelection(file)
            return
        }
        stateModel.documentState.pendingFileOperation = null
        stateModel.documentState.selectedPaths.add(file.absolutePath)
        updateSelectionUi()
    }

    private fun toggleSelection(file: File) {
        if (!stateModel.documentState.selectedPaths.add(file.absolutePath)) stateModel.documentState.selectedPaths.remove(file.absolutePath)
        if (stateModel.documentState.selectedPaths.isEmpty()) exitSelectionMode() else updateSelectionUi()
    }

    private fun selectAllVisibleFiles() {
        if (com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled &&
            stateModel.mediaSelection.active) {
            stateModel.mediaSelection.updateVisiblePaths(visibleFiles.filter(::isAudioOrVideoFile).map { it.absolutePath })
            stateModel.mediaSelection.selectVisible(com.subtitleedit.ui.MediaLibrarySelectionAction.SELECT_ALL)
            return
        }
        if (visibleFiles.isNotEmpty() && visibleFiles.all { it.absolutePath in stateModel.documentState.selectedPaths }) {
            exitSelectionMode()
            return
        }
        stateModel.documentState.selectedPaths.addAll(visibleFiles.map { it.absolutePath })
        updateSelectionUi()
    }

    private fun selectRangeBetweenSelectedFiles() {
        if (com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled &&
            stateModel.mediaSelection.active) {
            stateModel.mediaSelection.selectVisible(com.subtitleedit.ui.MediaLibrarySelectionAction.SELECT_RANGE)
            return
        }
        val selectedIndices = visibleFiles.mapIndexedNotNull { index, file ->
            index.takeIf { file.absolutePath in stateModel.documentState.selectedPaths }
        }
        if (selectedIndices.size < 2) {
            showShortToast("请先在当前目录选择两个文件")
            return
        }

        val range = SelectionRangePolicy.contiguousRange(selectedIndices) ?: return
        stateModel.documentState.selectedPaths.addAll(visibleFiles.subList(range.first, range.last + 1).map { it.absolutePath })
        updateSelectionUi()
    }

    private fun selectedFiles(): List<File> = FileSelectionPolicy.existingFiles(stateModel.documentState.selectedPaths)

    private fun updateSelectionUi() = publishScreenState()

    private fun exitSelectionMode() {
        if (activeInstance?.get() !== this) return
        stateModel.documentState.selectedPaths.clear()
        stateModel.documentState.pendingFileOperation = null
        stateModel.documentState.pendingArchiveFile = null
        updateSelectionUi()
    }

    private fun startDestinationSelection(operation: FileOperation) {
        if (selectedFiles().isEmpty()) {
            exitSelectionMode()
            return
        }
        closeImagePreview()
        stateModel.documentState.pendingFileOperation = operation
        updateSelectionUi()
    }

    private fun completeDestinationOperation() {
        if (directoryNavigationPending) return
        val operation = stateModel.documentState.pendingFileOperation ?: return
        val destination = stateModel.documentState.currentDirectory ?: return
        if (operation == FileOperation.EXTRACT) {
            val archive = stateModel.documentState.pendingArchiveFile ?: run {
                cancelDestinationSelection()
                return
            }
            extractArchive(archive, destination)
            return
        }
        val sources = selectedFiles()
        if (sources.isEmpty()) {
            exitSelectionMode()
            return
        }

        if (operation == FileOperation.MOVE) {
            // 移动使用文件系统的重命名操作，启动后立即退出选择模式。
            exitSelectionMode()
            stateModel.viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        FileTransferManager.validateMove(sources, destination)
                        sources.forEach { source -> moveFile(source, destination) }
                    }
                }
                result.onSuccess {
                    showShortToast("已移动")
                    loadDirectory(destination)
                }.onFailure { error ->
                    showShortToast("移动失败：${error.message ?: "未知错误"}")
                    loadDirectory(destination)
                }
            }
            return
        }

        if (fileCopyJob?.isActive == true) return
        val progress = showArchiveProgress(
            title = "正在复制",
            message = "正在准备复制...",
            showCancel = true
        )
        val cancelledByUser = AtomicBoolean(false)
        fileCopyJob = stateModel.viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    copyFiles(
                        sources = sources,
                        destination = destination,
                        onConflict = { conflict ->
                            try {
                                archiveConflictDialogController.awaitResolution(conflict)
                            } catch (error: CancellationException) {
                                cancelledByUser.set(true)
                                throw error
                            }
                        },
                        onProgress = { message, completed, total ->
                            updateFileCopyProgress(progress, message, completed, total)
                        }
                    )
                }
                exitSelectionMode()
                loadDirectory(destination)
                showShortToast("已复制")
            } catch (error: CancellationException) {
                if (!cancelledByUser.get()) throw error
                exitSelectionMode()
                loadDirectory(destination)
                showShortToast("已取消复制")
            } catch (error: Throwable) {
                showShortToast("复制失败：${error.message ?: "未知错误"}")
            } finally {
                progress.dialog.dismiss()
                fileCopyJob = null
            }
        }
        progress.setCancelAction {
            progress.setCancelEnabled(false)
            progress.showCancelling()
            cancelledByUser.set(true)
            fileCopyJob?.cancel(CancellationException("用户取消复制"))
        }
    }

    private fun cancelDestinationSelection() {
        stateModel.documentState.pendingFileOperation = null
        stateModel.documentState.pendingArchiveFile = null
        updateSelectionUi()
    }

    private fun moveFile(source: File, destination: File) {
        FileTransferManager.move(source, destination)
    }

    private suspend fun copyFiles(
        sources: List<File>,
        destination: File,
        onConflict: suspend (ArchiveManager.DestinationConflict) -> ArchiveManager.ConflictResolution,
        onProgress: (message: String, completed: Long, total: Long) -> Unit
    ) = FileTransferManager.copy(sources, destination, onConflict, onProgress)

    private fun renameSelectedFile() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        if (files.size == 1) {
            showRenameDialog(files.first())
        } else {
            // Number in the same order as the visible list, including search results.
            val selectedPaths = files.map { it.absolutePath }.toSet()
            val ordered = displayedFiles.filter { it.absolutePath in selectedPaths }
            val remaining = files.filterNot { file -> ordered.any { it.absolutePath == file.absolutePath } }
            stateModel.batchRenameDialog = BatchRenameDialogUi(
                sources = ordered + remaining,
                history = SettingsManager.getInstance(this).getBatchRenameHistory()
            )
        }
    }

    private fun dismissBatchRename() {
        if (stateModel.batchRenameDialog?.running != true) stateModel.batchRenameDialog = null
    }

    private fun changeBatchRenameOrder(sources: List<File>) {
        val dialog = stateModel.batchRenameDialog ?: return
        if (dialog.running || sources.size != dialog.sources.size ||
            sources.map { it.absolutePath }.toSet() != dialog.sources.map { it.absolutePath }.toSet()
        ) return
        stateModel.batchRenameDialog = dialog.copy(sources = sources, error = null)
    }

    private fun deleteBatchRenameHistory(id: String) {
        val dialog = stateModel.batchRenameDialog ?: return
        if (dialog.running) return
        val settings = SettingsManager.getInstance(this)
        settings.removeBatchRenameHistory(id)
        stateModel.batchRenameDialog = dialog.copy(history = settings.getBatchRenameHistory())
    }

    private fun confirmBatchRename(options: BatchRename.Options) {
        val dialog = stateModel.batchRenameDialog ?: return
        if (dialog.running) return
        stateModel.batchRenameDialog = dialog.copy(running = true, error = null)
        directoryWatcher.stop()
        stateModel.viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val plan = resolveRenameConflicts(BatchRename.preview(dialog.sources, options))
                    ensureActive()
                    // Conflict prompts remain cancellable; finish or roll back once file changes start.
                    withContext(NonCancellable) { BatchRename.execute(plan) }
                }
            }
            val current = activeInstance?.get()?.takeIf { it.stateModel === stateModel }
            result.fold(
                onSuccess = { count ->
                    val historySaved = runCatching {
                        SettingsManager.getInstance(application).recordBatchRenameHistory(options)
                    }.isSuccess
                    stateModel.batchRenameDialog = null
                    stateModel.documentState.selectedPaths.clear()
                    current?.publishScreenState()
                    current?.showShortToast(getString(
                        if (historySaved) R.string.batch_rename_success else R.string.batch_rename_history_save_failed,
                        count
                    ))
                },
                onFailure = { error ->
                    stateModel.batchRenameDialog = dialog.copy(
                        error = if (error is CancellationException) null else {
                            getString(R.string.batch_rename_failed, error.message ?: getString(R.string.error))
                        }
                    )
                }
            )
            if (current?.stateModel?.documentState?.selectedTopLevelItem == R.id.nav_directory) {
                current.stateModel.documentState.currentDirectory?.let { current.loadDirectory(it) }
            }
        }
    }

    private suspend fun resolveRenameConflicts(
        entries: List<BatchRename.Entry>,
        strictNames: Boolean = true
    ): BatchRenameConflictResolver.Plan =
        BatchRenameConflictResolver.resolve(entries, strictNames) { conflict ->
            archiveConflictDialogController.awaitResolution(conflict)
        }

    private fun showRenameDialog(file: File, initialName: String = file.name) {
        pendingRenameFile = file
        mainDialog = MainActivityDialogUi.Rename(initialName)
    }

    private fun confirmRename(rawName: String) {
        val file = pendingRenameFile ?: return
        dismissMainDialog()
        val newName = rawName.trim()
        when {
            newName.isEmpty() || newName == "." || newName == ".." || newName.contains('/') || newName.contains('\\') ->
                showShortToast("文件名无效")
            newName == file.name -> Unit
            else -> {
                val target = File(file.parentFile, newName)
                stateModel.viewModelScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            val plan = resolveRenameConflicts(listOf(BatchRename.Entry(file, target)), strictNames = false)
                            ensureActive()
                            withContext(NonCancellable) { BatchRename.execute(plan) }
                        }
                    }
                    val current = activeInstance?.get()?.takeIf { it.stateModel === stateModel } ?: return@launch
                    result.fold(
                        onSuccess = { count ->
                            if (count > 0) {
                                if (current.stateModel.imagePreviewFile == file) current.closeImagePreview()
                                current.exitSelectionMode()
                                current.stateModel.documentState.currentDirectory?.let { current.loadDirectory(it) }
                                current.showShortToast("已重命名")
                            }
                        },
                        onFailure = { error ->
                            if (error is CancellationException) {
                                if (file.exists()) current.showRenameDialog(file, newName)
                            } else {
                                current.showShortToast(getString(R.string.batch_rename_failed, error.message ?: getString(R.string.error)))
                            }
                        }
                    )
                }
            }
        }
    }

    private fun confirmDeleteSelectedFiles() {
        confirmDeleteFiles(selectedFiles())
    }

    private fun confirmDeleteMediaFiles(paths: List<String>, onSuccess: () -> Unit) {
        confirmDeleteFiles(paths.distinct().map(::File).filter(::isAudioOrVideoFile), onSuccess)
    }

    private fun confirmDeleteFiles(files: List<File>, onSuccess: (() -> Unit)? = null) {
        if (files.isEmpty()) return
        pendingDeleteFiles = files
        pendingMediaDeleteSuccess = onSuccess
        mainDialog = MainActivityDialogUi.DeleteConfirmation(files.size)
    }

    private fun deleteConfirmedSelection() {
        val files = pendingDeleteFiles ?: return
        val mediaSuccess = pendingMediaDeleteSuccess
        dismissMainDialog()
        val suspendedPlayer = !playerModel.outputSuspended.value &&
            files.any { it.path == playerModel.state.value.currentTrack?.path }
        if (suspendedPlayer) playerModel.suspendOutput()
        val progress = showArchiveProgress(
            title = "正在删除",
            message = "正在准备删除...",
            showCancel = true
        )
        val cancelledByUser = AtomicBoolean(false)
        val deletionJob = stateModel.viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val worker = kotlin.coroutines.coroutineContext
                    val cacheSnapshots = linkedMapOf<File, MediaCacheCleaner.CacheSnapshot>()
                    try {
                        files.forEach { file ->
                            worker.ensureActive()
                            val mediaFiles = when {
                                isAudioOrVideoFile(file) -> sequenceOf(file)
                                file.isDirectory -> file.walkTopDown().onEach { worker.ensureActive() }
                                    .filter(::isAudioOrVideoFile)
                                else -> emptySequence()
                            }
                            mediaFiles.forEach { mediaFile ->
                                worker.ensureActive()
                                cacheSnapshots[mediaFile] = MediaCacheCleaner.snapshotForMedia(
                                    mediaFiles = listOf(mediaFile),
                                    kinds = MediaCacheCleaner.Kind.values().asList(),
                                    appCacheDir = cacheDir
                                )
                            }
                        }
                        FileDeletionManager.delete(files) { message, completed, total ->
                            if (!cancelledByUser.get()) updateFileDeletionProgress(progress, message, completed, total)
                        }
                    } finally {
                        // Preserve caches for surviving media in a partially deleted directory.
                        cacheSnapshots.forEach { (source, snapshot) ->
                            if (!source.exists()) MediaCacheCleaner.clear(snapshot)
                        }
                    }
                }
                if (result.allDeleted) {
                    mediaSuccess?.invoke()
                    showShortToast("已删除")
                } else {
                    showShortToast("删除失败")
                }
            } catch (error: CancellationException) {
                if (!cancelledByUser.get()) throw error
                showShortToast("已取消删除")
            } catch (error: Throwable) {
                showShortToast("删除失败：${error.message ?: "未知错误"}")
            } finally {
                progress.dialog.dismiss()
                val current = activeInstance?.get()?.takeIf { it.stateModel === stateModel }
                current?.let { activity ->
                    val removed = files.filterNot { it.exists() }.mapTo(hashSetOf()) { it.absolutePath }
                    activity.stateModel.imagePreviewFile?.let { preview ->
                        if (preview.absolutePath in removed) activity.closeImagePreview()
                    }
                    activity.stateModel.mediaSelection.removePaths(removed)
                    activity.playerModel.removeQueueTracks(removed)
                    if (suspendedPlayer) activity.playerModel.resumeOutput()
                    if (removed.isNotEmpty()) com.subtitleedit.player.PlayerLibraryWriter.submit {
                        com.subtitleedit.library.MediaLibraryRepository.getInstance(activity).updateObservedPaths(removed)
                    }
                    activity.exitSelectionMode()
                    activity.stateModel.documentState.currentDirectory?.let(activity::loadDirectory)
                }
            }
        }
        progress.setCancelAction {
            progress.showCancelling()
            cancelledByUser.set(true)
            deletionJob.cancel(CancellationException("用户取消删除"))
        }
    }

    private fun showSelectedMediaCacheDialog() {
        val mediaFiles = selectedFiles().filter(::isAudioOrVideoFile)
        if (mediaFiles.isEmpty()) return
        pendingMediaCacheFiles = mediaFiles
        stateModel.viewModelScope.launch {
            val cacheStats = withContext(Dispatchers.IO) {
                MediaCacheCleaner.statsForMedia(mediaFiles, cacheDir)
            }
            val items = MediaCacheCleaner.Kind.values().map { kind ->
                val stats = cacheStats.getValue(kind)
                val labelRes = when (kind) {
                    MediaCacheCleaner.Kind.WAVEFORM -> R.string.settings_cache_waveform_label
                    MediaCacheCleaner.Kind.SPECTROGRAM -> R.string.settings_cache_spectrogram_label
                    MediaCacheCleaner.Kind.QUICK_TRANSCRIBE -> R.string.settings_cache_quick_transcribe_label
                }
                val emptyRes = when (kind) {
                    MediaCacheCleaner.Kind.WAVEFORM -> R.string.settings_cache_waveform_empty
                    MediaCacheCleaner.Kind.SPECTROGRAM -> R.string.settings_cache_spectrogram_empty
                    MediaCacheCleaner.Kind.QUICK_TRANSCRIBE -> R.string.settings_cache_quick_transcribe_empty
                }
                val label = getString(labelRes)
                SettingsCacheItem(
                    key = kind.key,
                    label = label,
                    sizeBytes = stats.sizeBytes,
                    emptyMessage = getString(emptyRes),
                    confirmationMessage = getString(
                        R.string.file_manager_cache_confirm,
                        mediaFiles.size,
                        label,
                        formatCacheSize(stats.sizeBytes)
                    ),
                    fileCount = stats.fileCount
                )
            }
            if (pendingMediaCacheFiles == mediaFiles && mainDialog == null) {
                mainDialog = MainActivityDialogUi.CacheSelection(items)
            }
        }
    }

    private fun confirmSelectedMediaCacheClear(item: SettingsCacheItem) {
        if (item.fileCount <= 0) {
            dismissMainDialog()
            showShortToast(item.emptyMessage)
        } else {
            mainDialog = MainActivityDialogUi.CacheConfirmation(item)
        }
    }

    private fun clearSelectedMediaCache(item: SettingsCacheItem) {
        val mediaFiles = pendingMediaCacheFiles ?: selectedFiles().filter(::isAudioOrVideoFile)
        dismissMainDialog()
        val kind = MediaCacheCleaner.Kind.fromKey(item.key) ?: return
        stateModel.viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                MediaCacheCleaner.clearForMedia(
                    mediaFiles = mediaFiles,
                    kinds = listOf(kind),
                    appCacheDir = cacheDir
                )
            }
            val kindLabel = item.label.removeSuffix("缓存")
            showShortToast(getString(R.string.settings_cache_cleared, result.fileCount, kindLabel))
        }
    }

    private fun editSelectedMediaTags() {
        val files = selectedFiles()
        if (files.size != stateModel.documentState.selectedPaths.size) return
        val selection = MediaTagFileSelectionPolicy.fromFiles(files, ::isAudioOrVideoFile) ?: return
        startActivity(Intent(this, MediaTagEditorActivity::class.java).apply {
            putStringArrayListExtra(MediaTagEditorActivity.EXTRA_FILE_PATHS, ArrayList(selection.mediaFiles.map { it.absolutePath }))
            selection.coverFile?.let { putExtra(MediaTagEditorActivity.EXTRA_COVER_PATH, it.absolutePath) }
        })
    }

    private fun isAudioOrVideoFile(file: File): Boolean = file.isFile &&
        (FileUtils.isAudioFile(file) || FileTypePolicy.isVideo(file))

    private fun processSelectedMedia() {
        val files = selectedFiles()
        if (files.size != stateModel.documentState.selectedPaths.size) return
        mediaProcessingSelection = MediaProcessingSelectionPolicy.fromFiles(files, ::isAudioOrVideoFile)
    }

    private fun openSelectedMediaProcessingPage(page: MediaProcessingPage) {
        val pending = mediaProcessingSelection ?: return
        // Recheck the snapshot in case a selected file disappeared while the chooser was open.
        val selection = MediaProcessingSelectionPolicy.fromFiles(pending.files, ::isAudioOrVideoFile)
        mediaProcessingSelection = null
        if (selection == null || !selection.supports(page)) return
        val media = if (page == MediaProcessingPage.CONVERT) {
            requireNotNull(selection.conversion).mediaFiles
        } else requireNotNull(selection.muxFiles)
        val subtitles = if (page == MediaProcessingPage.CONVERT) selection.conversion?.subtitleFiles.orEmpty() else emptyList()
        startActivity(Intent(this, MediaConvertActivity::class.java).apply {
            putExtra(MediaConvertActivity.EXTRA_PAGE, page.name)
            putStringArrayListExtra(MediaConvertActivity.EXTRA_FILE_PATHS,
                ArrayList(media.map { it.absolutePath }))
            putStringArrayListExtra(MediaConvertActivity.EXTRA_SUBTITLE_PATHS,
                ArrayList(subtitles.map { it.absolutePath }))
        })
    }

    private fun formatCacheSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "${"%.1f".format(java.util.Locale.getDefault(), bytes / 1024.0)} KB"
        else -> "${"%.2f".format(java.util.Locale.getDefault(), bytes / 1024.0 / 1024.0)} MB"
    }

    private fun showSubtitleFormatConvertDialog() {
        val sources = selectedFiles().filter { it.isFile && FileUtils.isSubtitleFile(it) }
        if (sources.isEmpty() || sources.size != stateModel.documentState.selectedPaths.size) return

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    sources.map { source ->
                        SubtitleConversionSource(
                            file = source,
                            source = SubtitleFormatConverter.readFile(this@MainActivity, source)
                        )
                    }
                }
            }
            result.onFailure { error ->
                showShortToast(getString(R.string.dialog_subtitle_convert_failed, error.message ?: "未知错误"))
            }.onSuccess { conversionSources ->
                val targetFormats = SubtitleFormatConverter.supportedTargetFormats
                val unsupportedSource = conversionSources.firstOrNull {
                    it.source.format !in SubtitleFormatConverter.supportedSourceFormats
                }
                if (unsupportedSource != null) {
                    showShortToast(
                        getString(
                            R.string.dialog_subtitle_convert_unsupported_file,
                            unsupportedSource.file.name
                        )
                    )
                    return@onSuccess
                }

                val sourceFileText = if (conversionSources.size == 1) {
                    getString(
                        R.string.dialog_subtitle_convert_source_file,
                        conversionSources.first().file.name
                    )
                } else {
                    getString(R.string.dialog_subtitle_convert_source_files, conversionSources.size)
                }
                val sourceFormatText = if (conversionSources.size == 1) {
                    getString(
                        R.string.dialog_subtitle_convert_source_format,
                        SubtitleFormatConverter.displayName(conversionSources.first().source.format)
                    )
                } else {
                    getString(
                        R.string.dialog_subtitle_convert_source_formats,
                        conversionSources
                            .groupingBy { it.source.format }
                            .eachCount()
                            .entries
                            .joinToString("、") { (format, count) ->
                                "${SubtitleFormatConverter.displayName(format)} × $count"
                            }
                    )
                }
                val sourceFormats = conversionSources.map { it.source.format }.toSet()
                val defaultTargetIndex = targetFormats.indexOfFirst { it !in sourceFormats }
                    .takeIf { it >= 0 } ?: 0
                nextSubtitleConversionDialogId += 1
                pendingSubtitleConversionSources = conversionSources
                subtitleConversionResult = null
                subtitleConversionDialog = SubtitleConversionDialogUi(
                    id = nextSubtitleConversionDialogId,
                    sourceFileText = sourceFileText,
                    sourceFormatText = sourceFormatText,
                    targetFormats = targetFormats.mapIndexed { index, format ->
                        AppOption(index, SubtitleFormatConverter.displayName(format))
                    },
                    initialTargetIndex = defaultTargetIndex
                )
            }
        }
    }

    private fun dismissSubtitleConversion(dialogId: Int) {
        if (subtitleConversionDialog?.id != dialogId) return
        subtitleConversionDialog = null
        pendingSubtitleConversionSources = null
    }

    private fun convertSelectedSubtitles(
        dialogId: Int,
        targetIndex: Int,
        keepOriginal: Boolean
    ): Boolean {
        val dialog = subtitleConversionDialog?.takeIf { it.id == dialogId } ?: return false
        val conversionSources = pendingSubtitleConversionSources ?: return false
        val targetFormats = SubtitleFormatConverter.supportedTargetFormats
        if (targetFormats.isEmpty()) return false
        val targetFormat = targetFormats[targetIndex.coerceIn(targetFormats.indices)]
        val filesToConvert = conversionSources.filter { it.source.format != targetFormat }
        if (filesToConvert.isEmpty()) {
            showShortToast(getString(R.string.dialog_subtitle_convert_same_format))
            return false
        }

        lifecycleScope.launch {
            val conversionResult = withContext(Dispatchers.IO) {
                runCatching {
                    val successFiles = mutableListOf<String>()
                    val skippedFiles = conversionSources
                        .filter { it.source.format == targetFormat }
                        .map { it.file.name }
                    val failedFiles = mutableListOf<String>()
                    filesToConvert.forEach { conversionSource ->
                        runCatching {
                            val targetFile = File(
                                conversionSource.file.parentFile,
                                "${conversionSource.file.nameWithoutExtension}.${SubtitleFormatConverter.extension(targetFormat)}"
                            )
                            if (targetFile.exists()) {
                                error(
                                    getString(
                                        R.string.dialog_subtitle_convert_target_exists,
                                        targetFile.name
                                    )
                                )
                            }
                            val convertedContent = SubtitleFormatConverter.convert(
                                conversionSource.source,
                                targetFormat
                            )
                            FileUtils.writeFile(targetFile, convertedContent)
                            if (!keepOriginal && !conversionSource.file.delete()) {
                                targetFile.delete()
                                error("无法删除原文件")
                            }
                            successFiles += targetFile.name
                        }.onFailure {
                            failedFiles += "${conversionSource.file.name}: ${it.message ?: "未知错误"}"
                        }
                    }
                    SubtitleConversionResult(successFiles, skippedFiles, failedFiles)
                }
            }
            conversionResult.onSuccess { batchResult ->
                dismissSubtitleConversion(dialog.id)
                exitSelectionMode()
                stateModel.documentState.currentDirectory?.let(::loadDirectory)
                val message = buildString {
                    append(
                        getString(
                            R.string.dialog_subtitle_convert_batch_success,
                            batchResult.successFiles.size
                        )
                    )
                    if (batchResult.skippedFiles.isNotEmpty()) {
                        append(
                            getString(
                                R.string.dialog_subtitle_convert_batch_skipped,
                                batchResult.skippedFiles.size
                            )
                        )
                    }
                    if (batchResult.failedFiles.isNotEmpty()) {
                        append(
                            getString(
                                R.string.dialog_subtitle_convert_batch_failed,
                                batchResult.failedFiles.size
                            )
                        )
                    }
                }
                if (batchResult.failedFiles.isEmpty()) {
                    showShortToast(message)
                } else {
                    subtitleConversionResult = SubtitleConversionResultUi(
                        title = getString(R.string.dialog_subtitle_convert_title),
                        message = message + "\n\n" + batchResult.failedFiles.joinToString("\n")
                    )
                }
            }.onFailure { error ->
                dismissSubtitleConversion(dialog.id)
                showShortToast(
                    getString(
                        R.string.dialog_subtitle_convert_failed,
                        error.message ?: "未知错误"
                    )
                )
            }
        }
        return true
    }

    private data class SubtitleConversionSource(
        val file: File,
        val source: SubtitleFormatConverter.Source
    )

    private data class SubtitleConversionResult(
        val successFiles: List<String>,
        val skippedFiles: List<String>,
        val failedFiles: List<String>
    )

    private fun showCreateArchiveDialog() {
        val sources = selectedFiles()
        val outputDirectory = stateModel.documentState.currentDirectory ?: return
        if (sources.isEmpty()) return

        val formats = listOf(
            ArchiveManager.CreateFormat.ZIP,
            ArchiveManager.CreateFormat.SEVEN_Z,
            ArchiveManager.CreateFormat.TAR
        )
        val splitOptions = listOf(
            ArchiveSplitOptionUi("不分卷", null),
            ArchiveSplitOptionUi("10 MB", 10L * 1024 * 1024),
            ArchiveSplitOptionUi("50 MB", 50L * 1024 * 1024),
            ArchiveSplitOptionUi("100 MB", 100L * 1024 * 1024),
            ArchiveSplitOptionUi("500 MB", 500L * 1024 * 1024)
        )
        pendingArchiveDraft = PendingArchiveDraft(sources, outputDirectory)
        archiveCreationDialog = ArchiveCreationDialogUi(
            initialName = ArchiveNamePolicy.defaultName(sources),
            formats = formats.map { format ->
                ArchiveFormatOptionUi(
                    format = format,
                    compressionMethods = archiveRepository.compressionMethods(format),
                    encryptionMethods = archiveRepository.encryptionMethods(format)
                )
            },
            splitOptions = splitOptions
        )
    }

    private fun dismissCreateArchiveDialog() {
        archiveCreationDialog = null
        pendingArchiveDraft = null
    }

    private fun submitArchiveCreation(submission: ArchiveCreateSubmission): String? {
        val draft = pendingArchiveDraft ?: return "压缩文件信息已失效"
        val baseName = ArchiveNamePolicy.stripExtension(submission.name.trim())
        if (!ArchiveNamePolicy.isValidName(baseName)) return "请输入有效名称"
        val extension = archiveRepository.outputExtension(submission.format, submission.method)
        val output = File(draft.outputDirectory, "$baseName.$extension")
        if (output.exists()) return "同名压缩包已存在"

        pendingArchiveDraft = null
        archiveCreationDialog = null
        createArchive(
            sources = draft.sources,
            output = output,
            format = submission.format,
            method = submission.method,
            password = submission.password,
            encryptionMethod = submission.encryptionMethod,
            splitSizeBytes = submission.splitSizeBytes,
            deleteSources = submission.deleteSources
        )
        return null
    }

    private fun loadArchivePasswords(): List<String>? = runCatching {
        ArchivePasswordVault(this).getPasswords()
    }.onFailure {
        showShortToast("无法读取密码本")
    }.getOrNull()

    private fun saveArchivePassword(password: String) {
        if (password.isEmpty()) {
            showShortToast("请先输入密码")
            return
        }
        runCatching { ArchivePasswordVault(this).savePassword(password) }
            .onSuccess { showShortToast("密码已保存") }
            .onFailure { showShortToast("密码保存失败") }
    }

    private fun clearArchivePasswordBook() {
        runCatching { ArchivePasswordVault(this).clear() }
            .onSuccess { showShortToast("密码本已清空") }
            .onFailure { showShortToast("密码本清空失败") }
    }

    private fun createArchive(
        sources: List<File>, output: File, format: ArchiveManager.CreateFormat,
        method: ArchiveManager.CompressionMethod, password: String,
        encryptionMethod: ArchiveManager.EncryptionMethod?, splitSizeBytes: Long?,
        deleteSources: Boolean
    ) = archiveCompressionController.create(sources, output, format, method, password, encryptionMethod, splitSizeBytes, deleteSources)

    private fun showArchiveActions(archive: File) = archiveActionDialogController.showActions(
        archive = archive,
        onAction = { action -> runArchiveAction(archive, action) },
        onExtractToDestination = { startExtractDestinationSelection(archive) }
    )

    private fun runArchiveAction(
        archive: File,
        action: ArchiveAction,
        password: String? = null
    ) {
        if (!isCurrentTaskHost()) return
        if (action == ArchiveAction.EXTRACT_CURRENT) {
            prepareArchiveExtraction(
                archive = archive,
                destination = archive.parentFile ?: error("找不到目标目录"),
                password = password,
                onCompleted = { archive.parentFile?.let(::loadDirectory) }
            )
            return
        }
        if (action == ArchiveAction.TEST) {
            archiveTestRunner.run(
                archive = archive,
                password = password,
                onCompleted = { tested ->
                    activeInstance?.get()?.mainDialog = MainActivityDialogUi.Message(
                        title = "解压测试通过",
                        message = "压缩包完整可读。\n\n条目：${tested.entryCount}\n解压大小：${FileUtils.formatFileSize(tested.totalBytes)}"
                    )
                },
                onCancelled = { activeInstance?.get()?.showShortToast("已取消压缩包测试") },
                onFailure = { error ->
                    activeInstance?.get()?.let { current ->
                        if (ArchiveErrorPolicy.needsPassword(archive, error, password != null)) {
                            current.showArchivePasswordDialog(archive, { entered ->
                                current.runArchiveAction(archive, action, entered)
                            })
                        } else {
                            current.showOperationError("操作失败", error)
                        }
                    }
                }
            )
            return
        }
        val title = ArchiveActionUiPolicy.progressTitle(action)
        val progress = showBlockingProgress(title, archive.name)
        stateModel.viewModelScope.launch {
            val passwordChars = password?.toCharArray()
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        when (action) {
                            ArchiveAction.PREVIEW -> {
                                val entries = archiveRepository.listEntries(archive, passwordChars)
                                ArchivePreviewCache.write(this@MainActivity, entries)
                            }
                            ArchiveAction.TEST -> error("压缩包测试应使用可取消任务")
                            ArchiveAction.EXTRACT_CURRENT -> error("不应直接执行解压操作")
                        }
                    }
                }
            } finally {
                passwordChars?.fill('\u0000')
                progress.dismiss()
            }
            result.onSuccess { value ->
                if (!isCurrentTaskHost()) return@onSuccess
                when (action) {
                    ArchiveAction.PREVIEW -> startActivity(
                        ArchivePreviewActivity.createIntent(this@MainActivity, archive.name, value as File)
                    )
                    ArchiveAction.EXTRACT_CURRENT -> Unit
                    ArchiveAction.TEST -> Unit
                }
            }.onFailure { error ->
                if (ArchiveErrorPolicy.needsPassword(archive, error, password != null)) {
                    showArchivePasswordDialog(
                        archive = archive,
                        onPassword = { enteredPassword ->
                            runArchiveAction(archive, action, enteredPassword)
                        }
                    )
                } else {
                    showOperationError("操作失败", error)
                }
            }
        }
    }

    private fun startExtractDestinationSelection(archive: File) {
        stateModel.documentState.selectedPaths.clear()
        stateModel.documentState.pendingArchiveFile = archive
        stateModel.documentState.pendingFileOperation = FileOperation.EXTRACT
        updateSelectionUi()
    }

    private fun extractArchive(archive: File, destination: File) {
        prepareArchiveExtraction(
            archive = archive,
            destination = destination,
            onCompleted = {
                exitSelectionMode()
                loadDirectory(destination)
            },
            onCancelled = ::exitSelectionMode
        )
    }

    private fun prepareArchiveExtraction(
        archive: File,
        destination: File,
        password: String? = null,
        onCompleted: () -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        executeArchiveExtraction(archive, destination, password, onCompleted, onCancelled)
    }

    private fun executeArchiveExtraction(
        archive: File,
        destination: File,
        password: String?,
        onCompleted: () -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        if (!isCurrentTaskHost()) {
            onCancelled()
            return
        }
        archiveExtractionRunner.run(
            archive = archive,
            destination = destination,
            password = password,
            conflictPolicy = ArchiveManager.ConflictPolicy.FAIL,
            conflictPolicies = emptyMap(),
            onCompleted = {
                showExtractionCompleted(it)
                onCompleted()
            },
            onCancelled = onCancelled,
            onConflict = ::awaitArchiveConflictResolution,
            onFailure = { error ->
                when {
                    ArchiveErrorPolicy.isCancelled(error) -> onCancelled()
                    ArchiveErrorPolicy.needsPassword(archive, error, password != null) ->
                        showArchivePasswordDialog(archive, { entered ->
                            prepareArchiveExtraction(archive, destination, entered, onCompleted, onCancelled)
                        }, onCancelled)
                    else -> {
                        onCancelled()
                        showOperationError("解压失败", error)
                    }
                }
            }
        )
    }

    private suspend fun awaitArchiveConflictResolution(
        conflict: ArchiveManager.DestinationConflict
    ): ArchiveManager.ConflictResolution = archiveConflictDialogController.awaitResolution(conflict)

    private fun showExtractionCompleted(result: ArchiveManager.ExtractResult) {
        if (!isCurrentTaskHost()) return
        val message = if (result.skippedCount > 0) {
            "解压完成：${result.entryCount} 项，跳过 ${result.skippedCount} 项"
        } else {
            "解压完成：${result.entryCount} 项"
        }
        showShortToast(message)
    }

    private fun showArchivePasswordDialog(
        archive: File,
        onPassword: (String) -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        if (!isCurrentTaskHost()) {
            onCancelled()
            return
        }
        archivePasswordDialogController.showPasswordDialog(archive, onPassword, onCancelled)
    }

    private fun showBlockingProgress(title: String, message: String): ComposeDialogHandle =
        archiveActionDialogController.showBlockingProgress(title, message)

    private fun showArchiveProgress(
        title: String,
        message: String,
        showCancel: Boolean = false
    ): ArchiveProgressDialogController.ProgressUi =
        archiveProgressDialogController.show(title, message, showCancel)

    private fun showOperationError(title: String, error: Throwable) {
        if (activeInstance?.get() === this) {
            archiveProgressDialogController.showError(title, error)
        }
    }

    private fun showSelectedProperties() {
        filePropertiesDialogController.show(selectedFiles())
    }

    private fun showShortToast(message: String) {
        if (activeInstance?.get() !== this) return
        com.subtitleedit.util.OverwritingToast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun togglePlayerMode() {
        val preferences = com.subtitleedit.player.PlayerPreferences.getInstance(this)
        preferences.playerModeEnabled = !preferences.playerModeEnabled
        if (!preferences.playerModeEnabled) stateModel.mediaSelection.cancel()
        refreshPlayerModeNavigation()
        showShortToast(getString(
            if (preferences.playerModeEnabled) R.string.player_mode_enabled else R.string.player_mode_disabled
        ))
    }

    private fun refreshPlayerModeNavigation() {
        val enabled = com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled
        volumeControlStream = if (enabled) AudioManager.STREAM_MUSIC else mainVolumeControlStream ?: volumeControlStream
        if (!enabled && playerModel.state.value.currentTrack != null) playerModel.closeSession()
        val current = stateModel.documentState.selectedTopLevelItem
        val target = MainNavigationPolicy.pageForMode(
            current, enabled
        )
        if (target != current) showTopLevelPage(target) else publishScreenState()
    }

    private fun showOnlinePlaceholder() {
        showShortToast(getString(R.string.player_online_soon))
    }

    /** 音声模式：以当前目录的音视频为播放队列，从被点击的文件开始播放。 */
    private fun openPlayer(file: File) {
        val queue = screenState.files.filter(::isAudioOrVideoFile)
        if (queue.isEmpty()) return
        val startIndex = queue.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
        openPlayerQueue(queue.map { it.absolutePath }, startIndex, null,
            file.absoluteFile.parent?.let { PlayerQueueSource(PlayerQueueSourceKind.DIRECTORY, it) })
    }

    private fun openPlayerQueue(paths: List<String>, startIndex: Int, mode: PlayerPlayMode?,
        queueSource: PlayerQueueSource?) {
        if (!com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled) {
            startActivity(Intent(this, com.subtitleedit.player.PlayerActivity::class.java).apply {
                putStringArrayListExtra(com.subtitleedit.player.PlayerActivity.EXTRA_PLAYLIST, ArrayList(paths))
                putExtra(com.subtitleedit.player.PlayerActivity.EXTRA_START_INDEX, startIndex)
                mode?.let { putExtra(com.subtitleedit.player.PlayerActivity.EXTRA_PLAY_MODE, it.name) }
                putPlayerQueueSource(queueSource)
            })
            return
        }
        val tracks = paths.map { path ->
            val file = File(path)
            PlayerTrack(path, file.name, FileTypePolicy.isVideo(file))
        }
        playerModel.openQueue(tracks, startIndex, mode, queueSource)
    }

    private fun openMediaFileForEdit(
        mediaFile: File,
        mediaType: EditorMediaType
    ) = mediaOpenController.open(mediaFile, mediaType)

    private fun updateCompressionProgress(
        progress: ArchiveProgressDialogController.ProgressUi,
        compressionProgress: ArchiveManager.CompressionProgress
    ) = archiveProgressDialogController.updateCompression(progress, compressionProgress)

    private fun updateFileCopyProgress(
        progress: ArchiveProgressDialogController.ProgressUi,
        message: String,
        completed: Long,
        total: Long
    ) = archiveProgressDialogController.updateFileCopy(progress, message, completed, total)

    private fun updateFileDeletionProgress(
        progress: ArchiveProgressDialogController.ProgressUi,
        message: String,
        completed: Long,
        total: Long
    ) = archiveProgressDialogController.updateFileDeletion(progress, message, completed, total)

    /**
     * 当存在多个同名字幕文件时，弹出选择对话框
     */
    private fun openMediaWithSubtitle(
        mediaFile: File,
        mediaType: EditorMediaType,
        subtitleFile: File?
    ) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_FILE_PATH, mediaFile.absolutePath)
        intent.putExtra(EditorActivity.EXTRA_MEDIA_TYPE, mediaType.name)
        intent.putExtra(EditorActivity.EXTRA_IS_AUDIO_FILE, mediaType == EditorMediaType.AUDIO)
        intent.putExtra(
            EditorActivity.EXTRA_INSPECT_MP3,
            mediaType == EditorMediaType.AUDIO && mediaFile.extension.equals("mp3", ignoreCase = true)
        )
        if (subtitleFile != null) {
            intent.putExtra(EditorActivity.EXTRA_SUBTITLE_FILE_PATH, subtitleFile.absolutePath)
        }
        startActivity(intent)
    }

    private fun navigateDestinationInto(directory: File) {
        val current = stateModel.documentState.currentDirectory ?: return
        saveCurrentDirectoryScrollPosition()
        if (loadDirectory(directory, restoreScrollPosition = true)) {
            stateModel.documentState.directoryHistory += current
        }
    }

    private fun navigateDestinationUp(): Boolean {
        val current = stateModel.documentState.currentDirectory ?: return false
        val storageRoot = getDefaultDirectory()
        val currentPath = runCatching { current.canonicalPath }.getOrElse { current.absolutePath }
        val rootPath = runCatching { storageRoot.canonicalPath }.getOrElse { storageRoot.absolutePath }
        if (currentPath == rootPath) return false

        val target = current.parentFile ?: return false
        if (!target.exists() || !target.canRead()) return false
        saveCurrentDirectoryScrollPosition()
        if (loadDirectory(target, restoreScrollPosition = true)) {
            val historyTarget = stateModel.documentState.directoryHistory.lastOrNull()
            val historyPath = historyTarget?.let { history ->
                runCatching { history.canonicalPath }.getOrElse { history.absolutePath }
            }
            if (historyPath == runCatching { target.canonicalPath }.getOrElse { target.absolutePath }) {
                stateModel.documentState.directoryHistory.removeAt(stateModel.documentState.directoryHistory.lastIndex)
            } else {
                stateModel.documentState.directoryHistory.clear()
            }
            return true
        }
        return false
    }
    
    private fun goUpLevel() {
        if (stateModel.documentState.directoryHistory.isNotEmpty()) {
            saveCurrentDirectoryScrollPosition()
            val parent = stateModel.documentState.directoryHistory.removeAt(stateModel.documentState.directoryHistory.size - 1)
            loadDirectory(parent, restoreScrollPosition = true)
        } else if (hasNavigableParentDirectory()) {
            stateModel.documentState.currentDirectory?.parentFile?.let { parent ->
                saveCurrentDirectoryScrollPosition()
                loadDirectory(parent, restoreScrollPosition = true)
            }
        }
    }

    private fun hasNavigableParentDirectory(): Boolean {
        val current = stateModel.documentState.currentDirectory ?: return false
        val storageRoot = getDefaultDirectory()
        val currentPath = runCatching { current.canonicalPath }.getOrElse { current.absolutePath }
        val rootPath = runCatching { storageRoot.canonicalPath }.getOrElse { storageRoot.absolutePath }
        if (currentPath == rootPath) return false
        val parent = current.parentFile ?: return false
        return parent.exists() && parent.canRead()
    }
    
    private fun openFileForEdit(file: File) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_FILE_PATH, file.absolutePath)
        startActivity(intent)
    }
    
    private fun handleBackNavigation() {
        if (stateModel.imagePreviewFile != null) {
            if (stateModel.documentState.selectedPaths.isNotEmpty()) {
                exitSelectionMode()
            } else {
                closeImagePreview()
            }
            return
        }
        if (stateModel.mediaSelection.active) {
            stateModel.mediaSelection.cancel()
            return
        }
        if (stateModel.documentState.selectedTopLevelItem == R.id.nav_directory &&
            stateModel.documentState.selectedPaths.isEmpty() &&
            stateModel.documentState.pendingFileOperation == null && searchExpanded
        ) {
            closeFileSearch()
            return
        }
        when (MainBackNavigationPolicy.decide(
            isDirectorySelected = stateModel.documentState.selectedTopLevelItem == R.id.nav_directory,
            hasPendingFileOperation = stateModel.documentState.pendingFileOperation != null,
            hasSelection = stateModel.documentState.selectedPaths.isNotEmpty(),
            hasDirectoryHistory = stateModel.documentState.directoryHistory.isNotEmpty(),
            hasParentDirectory = hasNavigableParentDirectory()
        )) {
            MainBackNavigationPolicy.Decision.DELEGATE_TO_TOP_LEVEL -> {
                // Top-level pages are hosted by Compose. Nested pages publish
                // their back action through the shared toolbar state.
                if (customToolbarHasBack) {
                    lastBackExitPromptAt = 0L
                    customToolbarBack?.invoke()
                } else if (MainNavigationPolicy.isAuxiliaryPage(
                    stateModel.documentState.selectedTopLevelItem,
                    com.subtitleedit.player.PlayerPreferences.getInstance(this).playerModeEnabled
                )) {
                    lastBackExitPromptAt = 0L
                    showTopLevelPage(R.id.nav_directory)
                } else {
                    finishFromBackNavigation()
                }
            }
            MainBackNavigationPolicy.Decision.NAVIGATE_DESTINATION -> {
                lastBackExitPromptAt = 0L
                if (!navigateDestinationUp()) cancelDestinationSelection()
            }
            MainBackNavigationPolicy.Decision.EXIT_SELECTION -> {
                lastBackExitPromptAt = 0L
                exitSelectionMode()
            }
            MainBackNavigationPolicy.Decision.GO_UP_LEVEL -> {
                lastBackExitPromptAt = 0L
                goUpLevel()
            }
            MainBackNavigationPolicy.Decision.FINISH -> finishFromBackNavigation()
        }
    }

    private fun finishFromBackNavigation() {
        val now = System.currentTimeMillis()
        if (now - lastBackExitPromptAt <= BACK_EXIT_CONFIRM_WINDOW_MS) {
            lastBackExitPromptAt = 0L
            backNavigationCallback.isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        } else {
            lastBackExitPromptAt = now
            showShortToast("再按一次返回键退出")
        }
    }

}
