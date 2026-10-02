package com.subtitleedit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.feature.ui.ArchiveCreateSubmission
import com.subtitleedit.feature.ui.ArchiveCreationDialogUi
import com.subtitleedit.feature.ui.ArchiveFormatOptionUi
import com.subtitleedit.feature.ui.ArchiveSplitOptionUi
import com.subtitleedit.feature.ui.MainCreateItemError
import com.subtitleedit.feature.ui.MainCreateItemField
import com.subtitleedit.feature.ui.MainCreateItemKind
import com.subtitleedit.feature.ui.MainActivityDialogUi
import com.subtitleedit.feature.ui.MainActivityScreen
import com.subtitleedit.feature.ui.MainActivityScreenState
import com.subtitleedit.feature.ui.MainDirectoryScrollRequest
import com.subtitleedit.feature.ui.MainFileOperationDialogs
import com.subtitleedit.feature.ui.SubtitleConversionDialogUi
import com.subtitleedit.feature.ui.SubtitleConversionResultUi
import com.subtitleedit.repository.ArchiveRepository
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.ArchiveManager
import com.subtitleedit.util.ArchivePreviewCache
import com.subtitleedit.model.FileBrowserOrder
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.FileTransferManager
import com.subtitleedit.util.FileBrowserNavigation
import com.subtitleedit.util.ArchiveNamePolicy
import com.subtitleedit.util.ArchiveErrorPolicy
import com.subtitleedit.util.FileBrowserPolicy
import com.subtitleedit.util.AndroidDirectoryPolicy
import com.subtitleedit.util.FilePathPolicy
import com.subtitleedit.util.SelectionRangePolicy
import com.subtitleedit.util.MainBackNavigationPolicy
import com.subtitleedit.util.MainLifecycleCoordinator
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileSelectionPolicy
import com.subtitleedit.util.FileOperationUiPolicy
import com.subtitleedit.util.ArchiveActionUiPolicy
import com.subtitleedit.util.ArchiveActionUiPolicy.ArchiveAction
import com.subtitleedit.util.ArchivePasswordVault
import com.subtitleedit.util.DirectoryWatcher
import com.subtitleedit.util.DirectorySearchController
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleFormatConverter
import com.subtitleedit.util.UpdateChecker
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 主界面 - 文件浏览器
 */
class MainActivity : AppCompatActivity() {

    private val archiveRepository: ArchiveRepository
        get() = (application as SubtitleEditApplication).dependencies.archiveRepository

    private companion object {
        const val CONFLICT_WAIT_INTERVAL_MS = 250L
    }

    private lateinit var filePropertiesDialogController: FilePropertiesDialogController
    private lateinit var topLevelNavigationCoordinator: MainTopLevelNavigationCoordinator
    private lateinit var mediaOpenController: MediaOpenController
    private lateinit var archivePasswordDialogController: ArchivePasswordDialogController
    private lateinit var archiveConflictDialogController: ArchiveConflictDialogController
    private lateinit var archiveProgressDialogController: ArchiveProgressDialogController
    private lateinit var archiveCompressionController: ArchiveCompressionController
    private lateinit var archiveExtractionRunner: ArchiveExtractionRunner
    private lateinit var archiveActionDialogController: ArchiveActionDialogController
    private val stateModel: MainViewModel by viewModels()

    private val visibleFiles = mutableListOf<File>()
    private val directoryFiles = mutableListOf<File>()
    private var displayedFiles = emptyList<File>()
    private var directoryEmptyStateVisible = false
    private var directoryEmptyStateMessage = R.string.no_files
    private var showAllFileTypes = false
    private var showHiddenFiles = false
    private var directoryLoading = false
    private var searchInProgress = false
    private var relativePathRoot: File? = null
    private var customToolbarTitle: String? = null
    private var customToolbarBack: (() -> Unit)? = null
    private var customToolbarHasBack = false
    private var topLevelPageRefreshVersion by mutableIntStateOf(0)
    private var topLevelPageSelectionVersion by mutableIntStateOf(0)
    private var searchExpanded = false
    private var directoryListState: LazyListState? = null
    private var directoryScrollRequest: MainDirectoryScrollRequest? = null
    private var nextDirectoryScrollGeneration = 0L
    private var screenState by mutableStateOf(MainActivityScreenState())
    private var subtitleConversionDialog by mutableStateOf<SubtitleConversionDialogUi?>(null)
    private var subtitleConversionResult by mutableStateOf<SubtitleConversionResultUi?>(null)
    private var archiveCreationDialog by mutableStateOf<ArchiveCreationDialogUi?>(null)
    private var mainDialog by mutableStateOf<MainActivityDialogUi?>(null)
    private var pendingSubtitleConversionSources: List<SubtitleConversionSource>? = null
    private var pendingArchiveDraft: PendingArchiveDraft? = null
    private var pendingRenameFile: File? = null
    private var pendingDeleteFiles: List<File>? = null
    private var nextSubtitleConversionDialogId = 0
    private val directoryWatcher = DirectoryWatcher(::refreshWatchedDirectory)
    private lateinit var directorySearchController: DirectorySearchController
    private lateinit var backNavigationCallback: OnBackPressedCallback
    private lateinit var lifecycleCoordinator: MainLifecycleCoordinator
    private var directoryLoadJob: Job? = null
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
        screenState = MainActivityScreenState(
            selectedTopLevelItem = stateModel.documentState.selectedTopLevelItem
        )
        searchExpanded = stateModel.documentState.isFileSearchActive &&
            stateModel.documentState.searchQuery.isNotEmpty()
        filePropertiesDialogController = FilePropertiesDialogController(this, ::showShortToast)
        mediaOpenController = MediaOpenController(this, ::openMediaWithSubtitle)
        archivePasswordDialogController = ArchivePasswordDialogController(this, ::showShortToast)
        archiveConflictDialogController = ArchiveConflictDialogController(this)
        archiveProgressDialogController = ArchiveProgressDialogController(this)
        archiveActionDialogController = ArchiveActionDialogController(this)
        archiveCompressionController = ArchiveCompressionController(
            activity = this,
            scope = lifecycleScope,
            repository = archiveRepository,
            progressController = archiveProgressDialogController,
            onExitSelection = ::exitSelectionMode,
            onRefreshDirectory = ::loadDirectory,
            onToast = ::showShortToast,
            onError = ::showOperationError,
            onDeleteFailures = { output, count ->
                mainDialog = MainActivityDialogUi.Message(
                    title = "压缩已完成",
                    message = "${output.name} 已创建，但有 $count 个源文件无法删除。"
                )
            }
        )
        archiveExtractionRunner = ArchiveExtractionRunner(
            activity = this,
            scope = lifecycleScope,
            repository = archiveRepository,
            progressController = archiveProgressDialogController
        )
        directorySearchController = DirectorySearchController(
            scope = lifecycleScope,
            canEnterDirectory = { file -> !isRestrictedAndroidDirectory(file) },
            onPartialResult = { files ->
                showDirectoryFiles(files, showParent = false, relativePathRoot = stateModel.documentState.currentDirectory, searching = true)
            },
            onCompleted = { files ->
                showDirectoryFiles(files, showParent = false, relativePathRoot = stateModel.documentState.currentDirectory, searching = false)
            },
            onFinished = {
                searchInProgress = false
                publishScreenState()
            }
        )
        showAllFileTypes = settingsManager.isShowAllFileTypesEnabled()
        showHiddenFiles = settingsManager.isShowHiddenFilesEnabled()
        if (stateModel.documentState.sortField == null) {
            stateModel.documentState.sortField = settingsManager.getFileSortField()
        }
        if (stateModel.documentState.sortDirection == null) {
            stateModel.documentState.sortDirection = settingsManager.getFileSortDirection()
        }
        
        topLevelNavigationCoordinator = MainTopLevelNavigationCoordinator(
            activity = this,
            state = stateModel.documentState,
            saveDirectoryScroll = ::saveCurrentDirectoryScrollPosition,
            loadDirectory = { directory, restore -> loadDirectory(directory, restore) },
            cancelDirectorySearch = { directorySearchController.cancel() },
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
                MainActivityScreen(
                        state = screenState,
                        topLevelPageRefreshVersion = topLevelPageRefreshVersion,
                        topLevelPageSelectionVersion = topLevelPageSelectionVersion,
                        onDirectoryListState = { directoryListState = it },
                        onTopLevelPageSelected = ::showTopLevelPage,
                        onOpenDirectoryFromFavorites = ::openDirectoryFromFavorites,
                        onUpdateTopLevelToolbar = ::updateTopLevelToolbar,
                        onSearchRequested = ::openFileSearch,
                        onSearchChanged = ::changeFileSearch,
                        onCreateItem = ::createBrowserItem,
                        onSortFieldChanged = ::changeFileSortField,
                        onSortDirectionChanged = ::changeFileSortDirection,
                        onOpenFileManagementSettings = {
                            startActivity(Intent(this@MainActivity, FileManagementSettingsActivity::class.java))
                        },
                        onSelectAll = ::selectAllVisibleFiles,
                        onSelectRange = ::selectRangeBetweenSelectedFiles,
                        onToolbarBack = ::onToolbarBack,
                        onFileClick = ::onFileClicked,
                        onFileLongClick = ::enterSelectionMode,
                        onCopy = { startDestinationSelection(FileOperation.COPY) },
                        onMove = { startDestinationSelection(FileOperation.MOVE) },
                        onRename = ::renameSelectedFile,
                        onDelete = ::confirmDeleteSelectedFiles,
                        onConvertSelection = ::showSubtitleFormatConvertDialog,
                        onCompressSelection = ::showCreateArchiveDialog,
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
                        onDeleteConfirm = ::deleteConfirmedSelection
                )
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
        lifecycleCoordinator.onResume()
        topLevelPageRefreshVersion++
    }

    override fun onPause() {
        lifecycleCoordinator.onPause()
        super.onPause()
    }

    private fun showPendingUpdate(update: UpdateChecker.UpdateInfo) {
        if (isFinishing || isDestroyed) return
        UpdateChecker.showUpdateDialog(this, update)
    }

    override fun onDestroy() {
        if (::lifecycleCoordinator.isInitialized) lifecycleCoordinator.onDestroy()
        super.onDestroy()
    }
    
    private fun showTopLevelPage(itemId: Int) {
        // The legacy BottomNavigationView replaced the selected fragment even
        // when the user tapped the already-selected item. Keep a generation
        // change for that case so Compose pages reset/refresh with the same
        // lifecycle semantics.
        topLevelPageSelectionVersion++
        topLevelNavigationCoordinator.showPage(itemId)
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
            isSearchInProgress = searchInProgress,
            showDirectoryEmptyState = directoryEmptyStateVisible,
            directoryEmptyStateMessage = directoryEmptyStateMessage,
            currentDirectory = document.currentDirectory,
            files = displayedFiles,
            scrollRequest = directoryScrollRequest,
            relativePathRoot = relativePathRoot,
            selectedPaths = document.selectedPaths.toSet(),
            pendingOperation = document.pendingFileOperation,
            isSearchExpanded = searchExpanded && !isSelectionActive,
            searchQuery = document.searchQuery,
            sortField = document.sortField ?: FileSortField.NAME,
            sortDirection = document.sortDirection ?: FileSortDirection.ASCENDING,
            canConvertSelected = canConvertSelected,
            canCompressSelected = !isFileSearchQueryActive()
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
        if (!directory.exists() || !directory.canRead()) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "无法访问目录：${directory.name}", Toast.LENGTH_SHORT).show()
            return false
        }
        
        stateModel.documentState.currentDirectory = directory
        directoryScrollRequest = null
        updatePathDisplay()
        directoryLoading = true
        publishScreenState()
        directoryLoadJob?.cancel()
        val requestedPath = directory.absolutePath
        directoryLoadJob = lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                directory.listFiles { file ->
                    (showHiddenFiles || !file.name.startsWith(".")) &&
                        (file.isDirectory || FileBrowserPolicy.shouldDisplayFile(
                            file,
                            showAllFileTypes,
                            FileTypePolicy.videoExtensions,
                            archiveRepository::isRecognizedArchive
                        ))
                }?.toList().orEmpty().distinctBy { it.absolutePath }
            }
            if (stateModel.documentState.currentDirectory?.absolutePath != requestedPath) return@launch
            directoryFiles.clear()
            directoryFiles.addAll(files)
            directoryLoading = false
            displayDirectoryFiles(restoreScrollPosition = restoreScrollPosition)
        }
        directoryWatcher.watch(directory)
        return true
    }

    private fun displayDirectoryFiles(restoreScrollPosition: Boolean = false) {
        directorySearchController.cancel()
        searchInProgress = false

        val directory = stateModel.documentState.currentDirectory ?: run {
            publishScreenState()
            return
        }
        val query = if (stateModel.documentState.isFileSearchActive) {
            stateModel.documentState.searchQuery.trim()
        } else {
            ""
        }
        val directMatches = FileBrowserOrder.sort(
            FileBrowserOrder.filter(directoryFiles, query),
            stateModel.documentState.sortField ?: FileSortField.NAME,
            stateModel.documentState.sortDirection ?: FileSortDirection.ASCENDING
        )
        if (query.isEmpty()) {
            showDirectoryFiles(
                directMatches,
                showParent = true,
                relativePathRoot = null,
                searching = false,
                restoreScrollPosition = restoreScrollPosition
            )
            return
        }

        searchInProgress = true
        showDirectoryFiles(
            directMatches,
            showParent = false,
            relativePathRoot = directory,
            searching = true,
            restoreScrollPosition = restoreScrollPosition
        )

        directorySearchController.search(
            root = directory,
            query = query,
            includeHidden = showHiddenFiles,
            sortField = stateModel.documentState.sortField ?: FileSortField.NAME,
            sortDirection = stateModel.documentState.sortDirection ?: FileSortDirection.ASCENDING,
            isCurrent = {
                stateModel.documentState.currentDirectory?.absolutePath == directory.absolutePath &&
                    stateModel.documentState.searchQuery.trim() == query
            }
        )
    }

    private fun showDirectoryFiles(
        displayed: List<File>,
        showParent: Boolean,
        relativePathRoot: File?,
        searching: Boolean,
        restoreScrollPosition: Boolean = false
    ) {
        visibleFiles.clear()
        visibleFiles.addAll(displayed)

        val adapterItems = mutableListOf<File>()
        val directory = stateModel.documentState.currentDirectory
        if (showParent && directory?.parentFile?.canRead() == true) {
            adapterItems.add(File(directory.absolutePath + "/.."))
        }
        adapterItems.addAll(displayed)
        this.relativePathRoot = relativePathRoot
        displayedFiles = adapterItems
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
        publishScreenState()
    }

    private fun directoryPath(directory: File): String =
        FilePathPolicy.canonicalOrAbsolute(directory)

    private fun saveCurrentDirectoryScrollPosition() {
        val directory = stateModel.documentState.currentDirectory ?: return
        val listState = directoryListState ?: return
        val firstVisibleIndex = listState.firstVisibleItemIndex
        val firstVisiblePath = displayedFiles.getOrNull(firstVisibleIndex)?.absolutePath
        stateModel.documentState.directoryScrollPositions[directoryPath(directory)] = DirectoryScrollPosition(
            firstVisiblePath = firstVisiblePath,
            firstVisibleIndex = firstVisibleIndex,
            offset = listState.firstVisibleItemScrollOffset
        )
    }

    private fun refreshWatchedDirectory() {
        val directory = stateModel.documentState.currentDirectory ?: return
        if (directory.exists() && directory.canRead()) loadDirectory(directory)
    }
    
    private fun updatePathDisplay() {
        publishScreenState()
    }
    
    private fun onFileClicked(file: File) {
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
        } else if (FileUtils.isAudioFile(file)) {
            openMediaFileForEdit(file, EditorMediaType.AUDIO)
        } else if (FileTypePolicy.isVideo(file)) {
            showVideoOpenModePicker(file)
        } else {
            com.subtitleedit.util.OverwritingToast.makeText(this, "不支持的文件格式", Toast.LENGTH_SHORT).show()
        }
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
        if (visibleFiles.isNotEmpty() && visibleFiles.all { it.absolutePath in stateModel.documentState.selectedPaths }) {
            exitSelectionMode()
            return
        }
        stateModel.documentState.selectedPaths.addAll(visibleFiles.map { it.absolutePath })
        updateSelectionUi()
    }

    private fun selectRangeBetweenSelectedFiles() {
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
        stateModel.documentState.pendingFileOperation = operation
        updateSelectionUi()
    }

    private fun completeDestinationOperation() {
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
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
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
        fileCopyJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    copyFiles(
                        sources = sources,
                        destination = destination,
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
        onProgress: (message: String, completed: Long, total: Long) -> Unit
    ) = FileTransferManager.copy(sources, destination, onProgress)

    private fun renameSelectedFile() {
        val files = selectedFiles()
        if (files.size != 1) {
            showShortToast("请只选择一个文件或文件夹进行重命名")
            return
        }
        showRenameDialog(files.first())
    }

    private fun showRenameDialog(file: File) {
        pendingRenameFile = file
        mainDialog = MainActivityDialogUi.Rename(file.name)
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
                if (target.exists()) {
                    showShortToast("目标名称已存在")
                } else if (file.renameTo(target)) {
                    exitSelectionMode()
                    stateModel.documentState.currentDirectory?.let(::loadDirectory)
                    showShortToast("已重命名")
                } else {
                    showShortToast("重命名失败")
                }
            }
        }
    }

    private fun confirmDeleteSelectedFiles() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        pendingDeleteFiles = files
        mainDialog = MainActivityDialogUi.DeleteConfirmation(files.size)
    }

    private fun deleteConfirmedSelection() {
        val files = pendingDeleteFiles ?: return
        dismissMainDialog()
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) { files.all { it.deleteRecursively() } }
            if (deleted) {
                exitSelectionMode()
                stateModel.documentState.currentDirectory?.let(::loadDirectory)
                showShortToast("已删除")
            } else {
                showShortToast("删除失败")
            }
        }
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
                    targetFormats = targetFormats.map(SubtitleFormatConverter::displayName),
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
        if (action == ArchiveAction.EXTRACT_CURRENT) {
            prepareArchiveExtraction(
                archive = archive,
                destination = archive.parentFile ?: error("找不到目标目录"),
                password = password,
                onCompleted = { archive.parentFile?.let(::loadDirectory) }
            )
            return
        }
        val title = ArchiveActionUiPolicy.progressTitle(action)
        val progress = showBlockingProgress(title, archive.name)
        lifecycleScope.launch {
            val passwordChars = password?.toCharArray()
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        when (action) {
                            ArchiveAction.PREVIEW -> {
                                val entries = archiveRepository.listEntries(archive, passwordChars)
                                ArchivePreviewCache.write(this@MainActivity, entries)
                            }
                            ArchiveAction.TEST -> archiveRepository.testArchive(archive, passwordChars)
                            ArchiveAction.EXTRACT_CURRENT -> error("不应直接执行解压操作")
                        }
                    }
                }
            } finally {
                passwordChars?.fill('\u0000')
                progress.dismiss()
            }
            result.onSuccess { value ->
                when (action) {
                    ArchiveAction.PREVIEW -> startActivity(
                        ArchivePreviewActivity.createIntent(this@MainActivity, archive.name, value as File)
                    )
                    ArchiveAction.EXTRACT_CURRENT -> Unit
                    ArchiveAction.TEST -> {
                        val tested = value as ArchiveManager.TestResult
                        mainDialog = MainActivityDialogUi.Message(
                            title = "解压测试通过",
                            message = "压缩包完整可读。\n\n条目：${tested.entryCount}\n解压大小：${FileUtils.formatFileSize(tested.totalBytes)}"
                        )
                    }
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
        if (archiveRepository.requiresStreamingConflictResolution(archive)) {
            executeArchiveExtraction(
                archive = archive,
                destination = destination,
                password = password,
                conflictPolicy = ArchiveManager.ConflictPolicy.FAIL,
                conflictPolicies = emptyMap(),
                onCompleted = onCompleted,
                onCancelled = onCancelled,
                onConflict = ::awaitArchiveConflictResolution
            )
            return
        }
        val scanProgress = showArchiveProgress(
            "正在解压",
            "正在检查压缩包..."
        )
        lifecycleScope.launch {
            val passwordChars = password?.toCharArray()
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        archiveRepository.findDestinationConflicts(
                            archive = archive,
                            destination = destination,
                            password = passwordChars,
                            onProgress = { _, completed, total ->
                                updateArchiveProgress(
                                    scanProgress,
                                    ArchiveManager.ProgressPhase.SCANNING,
                                    completed,
                                    total
                                )
                            }
                        )
                    }
                }
            } finally {
                passwordChars?.fill('\u0000')
                scanProgress.dialog.dismiss()
            }
            result.onSuccess { conflicts ->
                if (conflicts.isEmpty()) {
                    executeArchiveExtraction(
                        archive,
                        destination,
                        password,
                        ArchiveManager.ConflictPolicy.FAIL,
                        emptyMap(),
                        onCompleted,
                        onCancelled
                    )
                } else {
                    resolveArchiveConflictChoices(
                        archive,
                        destination,
                        password,
                        conflicts,
                        onCompleted,
                        onCancelled = onCancelled
                    )
                }
            }.onFailure { error ->
                if (ArchiveErrorPolicy.needsPassword(archive, error, password != null)) {
                    showArchivePasswordDialog(
                        archive = archive,
                        onPassword = { enteredPassword ->
                            prepareArchiveExtraction(
                                archive,
                                destination,
                                enteredPassword,
                                onCompleted,
                                onCancelled
                            )
                        },
                        onCancelled = onCancelled
                    )
                } else {
                    onCancelled()
                    showOperationError("解压失败", error)
                }
            }
        }
    }

    private fun resolveArchiveConflictChoices(
        archive: File,
        destination: File,
        password: String?,
        conflicts: List<ArchiveManager.DestinationConflict>,
        onCompleted: () -> Unit,
        onCancelled: () -> Unit = {},
        index: Int = 0,
        policies: MutableMap<String, ArchiveManager.ConflictPolicy> = linkedMapOf()
    ) {
        ArchiveConflictCoordinator(
            showConflict = { conflict, onSelected, cancelled ->
                showExtractionConflictDialog(conflict, onSelected, cancelled)
            }
        ).collect(conflicts, onCancelled) { policy, selectedPolicies ->
            executeArchiveExtraction(
                archive, destination, password, policy, selectedPolicies, onCompleted, onCancelled
            )
        }
    }

    private fun executeArchiveExtraction(
        archive: File,
        destination: File,
        password: String?,
        conflictPolicy: ArchiveManager.ConflictPolicy,
        conflictPolicies: Map<String, ArchiveManager.ConflictPolicy>,
        onCompleted: () -> Unit,
        onCancelled: () -> Unit = {},
        onConflict: ((ArchiveManager.DestinationConflict) -> ArchiveManager.ConflictResolution)? = null
    ) {
        archiveExtractionRunner.run(
            archive = archive,
            destination = destination,
            password = password,
            conflictPolicy = conflictPolicy,
            conflictPolicies = conflictPolicies,
            onCompleted = {
                showExtractionCompleted(it)
                onCompleted()
            },
            onCancelled = onCancelled,
            onConflict = onConflict,
            onFailure = { error ->
                when {
                    ArchiveErrorPolicy.isCancelled(error) -> onCancelled()
                    ArchiveErrorPolicy.isDestinationConflict(error) ->
                        prepareArchiveExtraction(archive, destination, password, onCompleted, onCancelled)
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

    private fun awaitArchiveConflictResolution(
        conflict: ArchiveManager.DestinationConflict
    ): ArchiveManager.ConflictResolution {
        val decision = AtomicReference<ArchiveManager.ConflictResolution?>()
        val cancelled = AtomicBoolean(false)
        val completed = CountDownLatch(1)
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                cancelled.set(true)
                completed.countDown()
                return@runOnUiThread
            }
            showExtractionConflictDialog(
                conflict = conflict,
                onPolicySelected = { policy, applyToAll ->
                    decision.set(ArchiveManager.ConflictResolution(policy, applyToAll))
                    completed.countDown()
                },
                onCancelled = {
                    cancelled.set(true)
                    completed.countDown()
                }
            )
        }

        try {
            while (!completed.await(CONFLICT_WAIT_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                if (isFinishing || isDestroyed || Thread.currentThread().isInterrupted) {
                    throw CancellationException("解压已取消")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("解压已取消")
        }
        if (cancelled.get()) throw CancellationException("用户取消解压")
        return decision.get() ?: throw CancellationException("解压已取消")
    }

    private fun showExtractionConflictDialog(
        conflict: ArchiveManager.DestinationConflict,
        onPolicySelected: (ArchiveManager.ConflictPolicy, Boolean) -> Unit,
        onCancelled: () -> Unit = {}
    ) = archiveConflictDialogController.show(conflict, onPolicySelected, onCancelled)

    private fun showExtractionCompleted(result: ArchiveManager.ExtractResult) {
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
    ) = archivePasswordDialogController.showPasswordDialog(archive, onPassword, onCancelled)

    private fun showBlockingProgress(title: String, message: String): ComposeDialogHandle =
        archiveActionDialogController.showBlockingProgress(title, message)

    private fun showArchiveProgress(
        title: String,
        message: String,
        showCancel: Boolean = false
    ): ArchiveProgressDialogController.ProgressUi =
        archiveProgressDialogController.show(title, message, showCancel)

    private fun updateArchiveProgress(
        progress: ArchiveProgressDialogController.ProgressUi,
        phase: ArchiveManager.ProgressPhase,
        completed: Long,
        total: Long
    ) = archiveProgressDialogController.updateArchive(progress, phase, completed, total)

    private fun showOperationError(title: String, error: Throwable) =
        archiveProgressDialogController.showError(title, error)

    private fun showSelectedProperties() {
        filePropertiesDialogController.show(selectedFiles())
    }

    private fun showShortToast(message: String) {
        com.subtitleedit.util.OverwritingToast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun openMediaFileForEdit(
        mediaFile: File,
        mediaType: EditorMediaType,
        audioOnlyFromVideo: Boolean = false
    ) = mediaOpenController.open(mediaFile, mediaType, audioOnlyFromVideo)

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

    private fun showVideoOpenModePicker(videoFile: File) =
        mediaOpenController.showVideoModePicker(videoFile)

    /**
     * 当存在多个同名字幕文件时，弹出选择对话框
     */
    private fun openMediaWithSubtitle(
        mediaFile: File,
        mediaType: EditorMediaType,
        subtitleFile: File?,
        audioOnlyFromVideo: Boolean
    ) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_FILE_PATH, mediaFile.absolutePath)
        intent.putExtra(EditorActivity.EXTRA_MEDIA_TYPE, mediaType.name)
        intent.putExtra(EditorActivity.EXTRA_IS_AUDIO_FILE, mediaType == EditorMediaType.AUDIO)
        intent.putExtra(EditorActivity.EXTRA_AUDIO_ONLY_FROM_VIDEO, audioOnlyFromVideo)
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
        } else {
            stateModel.documentState.currentDirectory?.parentFile?.let { parent ->
                if (parent.exists() && parent.canRead()) {
                    saveCurrentDirectoryScrollPosition()
                    loadDirectory(parent, restoreScrollPosition = true)
                }
            }
        }
    }
    
    private fun openFileForEdit(file: File) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_FILE_PATH, file.absolutePath)
        startActivity(intent)
    }
    
    private fun handleBackNavigation() {
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
            hasDirectoryHistory = stateModel.documentState.directoryHistory.isNotEmpty()
        )) {
            MainBackNavigationPolicy.Decision.DELEGATE_TO_TOP_LEVEL -> {
                // Top-level pages are hosted by Compose. Nested pages publish
                // their back action through the shared toolbar state.
                if (customToolbarHasBack) {
                    customToolbarBack?.invoke()
                } else {
                    finishFromBackNavigation()
                }
            }
            MainBackNavigationPolicy.Decision.NAVIGATE_DESTINATION -> {
                if (!navigateDestinationUp()) cancelDestinationSelection()
            }
            MainBackNavigationPolicy.Decision.EXIT_SELECTION -> exitSelectionMode()
            MainBackNavigationPolicy.Decision.GO_UP_LEVEL -> goUpLevel()
            MainBackNavigationPolicy.Decision.FINISH -> finishFromBackNavigation()
        }
    }

    private fun finishFromBackNavigation() {
        backNavigationCallback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
    }

}
