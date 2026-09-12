package com.subtitleedit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.subtitleedit.adapter.FileListAdapter
import com.subtitleedit.databinding.ActivityMainBinding
import com.subtitleedit.databinding.DialogCreateArchiveBinding
import com.subtitleedit.databinding.DialogSubtitleConvertBinding
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.repository.ArchiveRepository
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
        const val MENU_CREATE = 0x10004
        const val MENU_MORE = 0x10005
        const val CONFLICT_WAIT_INTERVAL_MS = 250L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileAdapter: FileListAdapter
    private lateinit var filePropertiesDialogController: FilePropertiesDialogController
    private lateinit var mainMenuController: MainMenuController
    private lateinit var topLevelNavigationCoordinator: MainTopLevelNavigationCoordinator
    private lateinit var mediaOpenController: MediaOpenController
    private lateinit var fileBrowserDialogController: FileBrowserDialogController
    private lateinit var archivePasswordDialogController: ArchivePasswordDialogController
    private lateinit var archiveConflictDialogController: ArchiveConflictDialogController
    private lateinit var archiveProgressDialogController: ArchiveProgressDialogController
    private lateinit var archiveCompressionController: ArchiveCompressionController
    private lateinit var archiveExtractionRunner: ArchiveExtractionRunner
    private lateinit var archiveActionDialogController: ArchiveActionDialogController
    private val stateModel: MainViewModel by viewModels()

    private var currentDirectory: File?
        get() = stateModel.currentDirectory
        set(value) { stateModel.currentDirectory = value }
    private val directoryHistory get() = stateModel.directoryHistory
    private val visibleFiles = mutableListOf<File>()
    private val directoryFiles = mutableListOf<File>()
    private val selectedPaths get() = stateModel.selectedPaths
    private var pendingFileOperation: FileOperation?
        get() = stateModel.pendingFileOperation
        set(value) { stateModel.pendingFileOperation = value }
    private var pendingArchiveFile: File?
        get() = stateModel.pendingArchiveFile
        set(value) { stateModel.pendingArchiveFile = value }
    private var showAllFileTypes = false
    private var showHiddenFiles = false
    private var sortField: FileSortField
        get() = stateModel.sortField ?: FileSortField.NAME
        set(value) { stateModel.sortField = value }
    private var sortDirection: FileSortDirection
        get() = stateModel.sortDirection ?: FileSortDirection.ASCENDING
        set(value) { stateModel.sortDirection = value }
    private val directoryWatcher = DirectoryWatcher(::refreshWatchedDirectory)
    private lateinit var directorySearchController: DirectorySearchController
    private lateinit var backNavigationCallback: OnBackPressedCallback
    private lateinit var lifecycleCoordinator: MainLifecycleCoordinator
    private var directoryLoadJob: Job? = null
    private var fileCopyJob: Job? = null
    private var activeFileSearchView: SearchView? = null
    private data class SplitOption(val label: String, val bytes: Long?)

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
        AppCompatDelegate.setDefaultNightMode(
            when (settingsManager.getThemeMode()) {
                com.subtitleedit.util.SettingsManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                com.subtitleedit.util.SettingsManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        filePropertiesDialogController = FilePropertiesDialogController(this, ::showShortToast)
        mainMenuController = MainMenuController(
            configureSearch = ::configureSearchItem,
            selectAll = ::selectAllVisibleFiles,
            selectRange = ::selectRangeBetweenSelectedFiles,
            showCreate = ::showCreateMenu,
            showMore = ::showDirectoryMoreMenu
        )
        topLevelNavigationCoordinator = MainTopLevelNavigationCoordinator(
            activity = this,
            binding = binding,
            selectedItem = { stateModel.selectedTopLevelItem },
            setSelectedItem = { stateModel.selectedTopLevelItem = it },
            saveDirectoryScroll = ::saveCurrentDirectoryScrollPosition,
            currentDirectory = { currentDirectory },
            loadDirectory = { directory, restore -> loadDirectory(directory, restore) },
            cancelDirectorySearch = { directorySearchController.cancel() },
            stopDirectoryWatcher = { directoryWatcher.stop() },
            invalidateMenu = ::invalidateOptionsMenu,
            clearDirectorySelection = {
                directoryHistory.clear()
                selectedPaths.clear()
                pendingFileOperation = null
                pendingArchiveFile = null
                stateModel.searchQuery = ""
                stateModel.isFileSearchActive = false
            }
        )
        mediaOpenController = MediaOpenController(this, ::openMediaWithSubtitle)
        fileBrowserDialogController = FileBrowserDialogController(
            activity = this,
            dp = ::dp,
            currentDirectory = { currentDirectory },
            onCreated = { currentDirectory?.let(::loadDirectory) },
            onSortChanged = { field, direction ->
                field?.let {
                    sortField = it
                    SettingsManager.getInstance(this).setFileSortField(it)
                }
                direction?.let {
                    sortDirection = it
                    SettingsManager.getInstance(this).setFileSortDirection(it)
                }
                displayDirectoryFiles()
            },
            onOpenSettings = {
                startActivity(Intent(this, FileManagementSettingsActivity::class.java))
            }
        )
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
                AlertDialog.Builder(this)
                    .setTitle("压缩已完成")
                    .setMessage("${output.name} 已创建，但有 $count 个源文件无法删除。")
                    .setPositiveButton("确定", null)
                    .show()
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
                showDirectoryFiles(files, showParent = false, relativePathRoot = currentDirectory, searching = true)
            },
            onCompleted = { files ->
                showDirectoryFiles(files, showParent = false, relativePathRoot = currentDirectory, searching = false)
            },
            onFinished = { binding.searchProgress.visibility = View.INVISIBLE }
        )
        showAllFileTypes = settingsManager.isShowAllFileTypesEnabled()
        showHiddenFiles = settingsManager.isShowHiddenFilesEnabled()
        if (stateModel.sortField == null) sortField = settingsManager.getFileSortField()
        if (stateModel.sortDirection == null) sortDirection = settingsManager.getFileSortDirection()
        
        setupToolbar()
        setupRecyclerView()
        setupButtons()
        setupBottomNavigation()
        setupBackNavigation()
        lifecycleCoordinator = MainLifecycleCoordinator(
            activity = this,
            lifecycleOwner = this,
            scope = lifecycleScope,
            directoryWatcher = directoryWatcher,
            shouldShowDirectory = { stateModel.selectedTopLevelItem == R.id.nav_directory },
            refreshDirectory = { currentDirectory?.let(::loadDirectory) },
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
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.nav_directory)
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean = true

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        return mainMenuController.prepare(
            menu = menu,
            isDirectorySelected = stateModel.selectedTopLevelItem == R.id.nav_directory,
            hasSelection = selectedPaths.isNotEmpty(),
            hasPendingOperation = pendingFileOperation != null
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        mainMenuController.handle(item).takeIf { it } ?: super.onOptionsItemSelected(item)
    
    private fun setupRecyclerView() {
        fileAdapter = FileListAdapter(
            onItemClick = ::onFileClicked,
            onItemLongClick = ::enterSelectionMode,
            isItemRestricted = ::isRestrictedAndroidDirectory
        )
        
        binding.rvFileList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = fileAdapter
            // Selection updates are represented by item alpha/stroke. The default
            // change animation restores alpha to 1f at animation end, which makes
            // only currently visible rows become bright again after navigation or
            // a directory refresh. Disable change animations so bound selection
            // visuals remain authoritative.
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
    }
    
    private fun setupButtons() {
        binding.btnCopySelected.setOnClickListener { startDestinationSelection(FileOperation.COPY) }
        binding.btnMoveSelected.setOnClickListener { startDestinationSelection(FileOperation.MOVE) }
        binding.btnRenameSelected.setOnClickListener { renameSelectedFile() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelectedFiles() }
        binding.btnMoreSelected.setOnClickListener { showMoreActions() }
        binding.btnConfirmDestination.setOnClickListener { completeDestinationOperation() }
        binding.btnCancelDestination.setOnClickListener {
            cancelDestinationSelection()
        }
    }

    private fun setupBottomNavigation() {
        topLevelNavigationCoordinator.bind(topLevelNavigationCoordinator::showPage)
    }

    private fun showTopLevelPage(itemId: Int) {
        topLevelNavigationCoordinator.showPage(itemId)
    }

    fun updateTopLevelToolbar(title: String, showBack: Boolean = false, onBack: (() -> Unit)? = null) {
        topLevelNavigationCoordinator.updateToolbar(title, showBack, onBack)
    }

    fun openDirectoryFromFavorites(directory: File) {
        topLevelNavigationCoordinator.openDirectoryFromFavorites(directory)
    }

    private fun configureSearchItem(item: MenuItem) {
        var suppressSearchCallbacks = true
        val searchView = SearchView(this)
        activeFileSearchView = searchView
        searchView.apply {
            queryHint = getString(R.string.file_search_hint)
            maxWidth = Int.MAX_VALUE
            setQuery(stateModel.searchQuery, false)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = true
                override fun onQueryTextChange(newText: String?): Boolean {
                    if (activeFileSearchView !== searchView || suppressSearchCallbacks) return true
                    val updatedQuery = newText.orEmpty()
                    val selectionUiActive = selectedPaths.isNotEmpty() || pendingFileOperation != null
                    if (selectionUiActive) return true
                    if (updatedQuery == stateModel.searchQuery) return true
                    stateModel.isFileSearchActive = true
                    stateModel.searchQuery = updatedQuery
                    displayDirectoryFiles()
                    return true
                }
            })
        }
        item.actionView = searchView
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                if (activeFileSearchView !== searchView) return true
                stateModel.isFileSearchActive = true
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (activeFileSearchView !== searchView || suppressSearchCallbacks) return true
                val enteringSelectionMode = selectedPaths.isNotEmpty() || pendingFileOperation != null
                if (!enteringSelectionMode) {
                    clearFileSearch(refreshDirectory = true)
                }
                return true
            }
        })
        if (stateModel.isFileSearchActive && stateModel.searchQuery.isNotEmpty()) {
            item.expandActionView()
            searchView.setQuery(stateModel.searchQuery, false)
            searchView.post {
                if (activeFileSearchView !== searchView) return@post
                val retainedQuery = stateModel.searchQuery
                if (stateModel.isFileSearchActive && retainedQuery.isNotEmpty() &&
                    searchView.query.toString() != retainedQuery
                ) {
                    searchView.setQuery(retainedQuery, false)
                }
                suppressSearchCallbacks = false
            }
        } else {
            suppressSearchCallbacks = false
        }
    }

    private fun showCreateMenu() {
        val anchor = binding.toolbar.findViewById<View>(MENU_CREATE) ?: binding.toolbar
        fileBrowserDialogController.showCreateMenu(anchor)
    }

    private fun showDirectoryMoreMenu() {
        val anchor = binding.toolbar.findViewById<View>(MENU_MORE) ?: binding.toolbar
        fileBrowserDialogController.showMoreMenu(anchor, ::showSortDialog)
    }

    private fun showSortDialog() {
        fileBrowserDialogController.showSortDialog({ sortField }, { sortDirection })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    
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
        AlertDialog.Builder(this)
            .setTitle(R.string.error)
            .setMessage("需要存储权限才能访问字幕文件。请在设置中授予权限。")
            .setPositiveButton(R.string.confirm) { _, _ ->
                checkPermissions()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun getDefaultDirectory(): File {
        return Environment.getExternalStorageDirectory()
    }

    private fun loadInitialDirectory() {
        val restored = currentDirectory?.takeIf { it.exists() && it.canRead() }
        if (loadDirectory(restored ?: getDefaultDirectory(), restoreScrollPosition = restored != null)) return
        if (restored == null) return

        directoryHistory.clear()
        selectedPaths.clear()
        pendingFileOperation = null
        pendingArchiveFile = null
        loadDirectory(getDefaultDirectory())
    }
    
    private fun loadDirectory(directory: File, restoreScrollPosition: Boolean = false): Boolean {
        if (!directory.exists() || !directory.canRead()) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "无法访问目录：${directory.name}", Toast.LENGTH_SHORT).show()
            return false
        }
        
        currentDirectory = directory
        updatePathDisplay()
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
            if (currentDirectory?.absolutePath != requestedPath) return@launch
            directoryFiles.clear()
            directoryFiles.addAll(files)
            displayDirectoryFiles(restoreScrollPosition = restoreScrollPosition)
        }
        directoryWatcher.watch(directory)
        return true
    }

    private fun displayDirectoryFiles(restoreScrollPosition: Boolean = false) {
        directorySearchController.cancel()
        binding.searchProgress.visibility = View.INVISIBLE

        val directory = currentDirectory ?: return
        val query = if (stateModel.isFileSearchActive) {
            stateModel.searchQuery.trim()
        } else {
            ""
        }
        val directMatches = FileBrowserOrder.sort(
            FileBrowserOrder.filter(directoryFiles, query),
            sortField,
            sortDirection
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

        binding.searchProgress.visibility = View.VISIBLE
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
            sortField = sortField,
            sortDirection = sortDirection,
            isCurrent = {
                currentDirectory?.absolutePath == directory.absolutePath &&
                    stateModel.searchQuery.trim() == query
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
        val directory = currentDirectory
        if (showParent && directory?.parentFile?.canRead() == true) {
            adapterItems.add(File(directory.absolutePath + "/.."))
        }
        adapterItems.addAll(displayed)
        fileAdapter.setRelativePathRoot(relativePathRoot)
        val directoryPath = currentDirectory?.let(::directoryPath)
        val savedScrollPosition = if (restoreScrollPosition && directoryPath != null) {
            stateModel.directoryScrollPositions[directoryPath]
        } else {
            null
        }
        fileAdapter.submitList(adapterItems) {
            // AsyncListDiffer may finish after the selection update below. Reapply the
            // latest selection state once the new list is installed so visible holders
            // cannot retain the pre-refresh alpha/stroke values.
            fileAdapter.updateSelection(
                selectedPaths.isNotEmpty() && pendingFileOperation == null,
                selectedPaths.toSet()
            )
            fileAdapter.refreshSelectionVisuals()
            if (restoreScrollPosition && directoryPath != null) {
                binding.rvFileList.post {
                    if (currentDirectory?.let(::directoryPath) != directoryPath) return@post
                    restoreDirectoryScrollPosition(savedScrollPosition)
                }
            }
        }
        updateSelectionUi(invalidateMenu = false)
        binding.tvEmptyStateMessage.setText(
            if (searching) R.string.file_searching else R.string.no_files
        )
        binding.emptyState.visibility = if (adapterItems.isEmpty()) View.VISIBLE else View.GONE
        binding.rvFileList.visibility = if (adapterItems.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun directoryPath(directory: File): String =
        FilePathPolicy.canonicalOrAbsolute(directory)

    private fun saveCurrentDirectoryScrollPosition() {
        val directory = currentDirectory ?: return
        val layoutManager = binding.rvFileList.layoutManager as? LinearLayoutManager ?: return
        val firstVisibleIndex = layoutManager.findFirstVisibleItemPosition()
        if (firstVisibleIndex < 0) return

        val firstVisibleView = layoutManager.findViewByPosition(firstVisibleIndex)
        val offset = firstVisibleView?.let {
            layoutManager.getDecoratedTop(it) - binding.rvFileList.paddingTop
        } ?: 0
        val firstVisiblePath = fileAdapter.currentList.getOrNull(firstVisibleIndex)?.absolutePath
        stateModel.directoryScrollPositions[directoryPath(directory)] = DirectoryScrollPosition(
            firstVisiblePath = firstVisiblePath,
            firstVisibleIndex = firstVisibleIndex,
            offset = offset
        )
    }

    private fun restoreDirectoryScrollPosition(savedPosition: DirectoryScrollPosition?) {
        val layoutManager = binding.rvFileList.layoutManager as? LinearLayoutManager ?: return
        if (fileAdapter.itemCount == 0) return

        val position = savedPosition?.firstVisiblePath?.let { path ->
            fileAdapter.currentList.indexOfFirst { it.absolutePath == path }
        }?.takeIf { it >= 0 } ?: savedPosition?.firstVisibleIndex ?: 0
        layoutManager.scrollToPositionWithOffset(
            position.coerceIn(0, fileAdapter.itemCount - 1),
            savedPosition?.offset ?: 0
        )
    }

    private fun refreshWatchedDirectory() {
        val directory = currentDirectory ?: return
        if (directory.exists() && directory.canRead()) loadDirectory(directory)
    }
    
    private fun updatePathDisplay() {
        currentDirectory?.let {
            binding.tvCurrentPath.text = it.absolutePath
        }
    }
    
    private fun onFileClicked(file: File) {
        // 处理父目录导航
        if (file.name == "..") {
            // 普通文件选择期间暂时锁定顶部父目录项，避免点击文件夹选择后
            // 意外离开当前目录。复制/移动等目标目录选择仍允许返回上级。
            if (selectedPaths.isNotEmpty() && pendingFileOperation == null) return
            if (pendingFileOperation != null) navigateDestinationUp() else goUpLevel()
            return
        }

        if (isRestrictedAndroidDirectory(file)) {
            showShortToast(getString(R.string.android_directory_access_denied))
            return
        }

        if (pendingFileOperation != null) {
            if (file.isDirectory) {
                navigateDestinationInto(file)
            } else {
                showShortToast("请选择目标文件夹")
            }
            return
        }

        if (selectedPaths.isNotEmpty()) {
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
        } else if (FileUtils.isSubtitleFile(file)) {
            // 打开字幕文件进行编辑
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
        val previousDirectory = currentDirectory ?: return
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
            directoryHistory.addAll(historyEntries)
            if (wasSearching) invalidateOptionsMenu()
        }
    }

    private fun isFileSearchQueryActive(): Boolean =
        stateModel.isFileSearchActive && stateModel.searchQuery.isNotBlank()

    private fun clearFileSearch(refreshDirectory: Boolean) {
        stateModel.isFileSearchActive = false
        stateModel.searchQuery = ""
        directorySearchController.cancel()
        binding.searchProgress.visibility = View.INVISIBLE
        if (refreshDirectory) displayDirectoryFiles()
    }

    private fun isRestrictedAndroidDirectory(file: File): Boolean =
        AndroidDirectoryPolicy.isRestricted(file)

    private fun enterSelectionMode(file: File) {
        if (file.name == ".." || pendingFileOperation != null) return
        if (file.absolutePath in selectedPaths) {
            toggleSelection(file)
            return
        }
        pendingFileOperation = null
        selectedPaths.add(file.absolutePath)
        updateSelectionUi()
    }

    private fun toggleSelection(file: File) {
        if (!selectedPaths.add(file.absolutePath)) selectedPaths.remove(file.absolutePath)
        if (selectedPaths.isEmpty()) exitSelectionMode() else updateSelectionUi()
    }

    private fun selectAllVisibleFiles() {
        if (visibleFiles.isNotEmpty() && visibleFiles.all { it.absolutePath in selectedPaths }) {
            exitSelectionMode()
            return
        }
        selectedPaths.addAll(visibleFiles.map { it.absolutePath })
        updateSelectionUi()
    }

    private fun selectRangeBetweenSelectedFiles() {
        val selectedIndices = visibleFiles.mapIndexedNotNull { index, file ->
            index.takeIf { file.absolutePath in selectedPaths }
        }
        if (selectedIndices.size < 2) {
            showShortToast("请先在当前目录选择两个文件")
            return
        }

        val range = SelectionRangePolicy.contiguousRange(selectedIndices) ?: return
        selectedPaths.addAll(visibleFiles.subList(range.first, range.last + 1).map { it.absolutePath })
        updateSelectionUi()
    }

    private fun selectedFiles(): List<File> = FileSelectionPolicy.existingFiles(selectedPaths)

    private fun updateSelectionUi(invalidateMenu: Boolean = true) {
        val operation = pendingFileOperation
        val isSelectionUiActive = selectedPaths.isNotEmpty() || operation != null
        binding.selectionBottomActions.visibility = if (isSelectionUiActive) View.VISIBLE else View.GONE
        val showTopLevelNavigation = !isSelectionUiActive
        binding.bottomNavigation.visibility = if (showTopLevelNavigation) View.VISIBLE else View.GONE
        binding.bottomDivider.visibility = if (showTopLevelNavigation) View.VISIBLE else View.GONE

        val choosingDestination = operation != null
        binding.selectionActionItems.visibility = if (choosingDestination) View.GONE else View.VISIBLE
        binding.destinationActionItems.visibility = if (choosingDestination) View.VISIBLE else View.GONE
        listOf(
            binding.btnCopySelected,
            binding.btnMoveSelected,
            binding.btnRenameSelected,
            binding.btnDeleteSelected,
            binding.btnMoreSelected
        ).forEach { it.isEnabled = !choosingDestination }
        binding.btnConfirmDestination.text = FileOperationUiPolicy.destinationButtonLabel(operation)
        supportActionBar?.title = if (isSelectionUiActive) {
            FileOperationUiPolicy.selectionTitle(operation, selectedPaths.size)
        } else {
            getString(R.string.nav_directory)
        }
        binding.toolbar.navigationIcon = if (isSelectionUiActive) {
            ContextCompat.getDrawable(this, R.drawable.ic_close)
        } else {
            null
        }
        binding.toolbar.navigationContentDescription = if (isSelectionUiActive) "退出选择模式" else null
        binding.toolbar.setNavigationOnClickListener(if (isSelectionUiActive) {
            View.OnClickListener { exitSelectionMode() }
        } else {
            null
        })
        if (invalidateMenu) {
            if (isSelectionUiActive) activeFileSearchView = null
            invalidateOptionsMenu()
        }
        fileAdapter.updateSelection(selectedPaths.isNotEmpty() && operation == null, selectedPaths)
    }

    private fun exitSelectionMode() {
        selectedPaths.clear()
        pendingFileOperation = null
        pendingArchiveFile = null
        updateSelectionUi()
    }

    private fun startDestinationSelection(operation: FileOperation) {
        if (selectedFiles().isEmpty()) {
            exitSelectionMode()
            return
        }
        pendingFileOperation = operation
        updateSelectionUi()
    }

    private fun completeDestinationOperation() {
        val operation = pendingFileOperation ?: return
        val destination = currentDirectory ?: return
        if (operation == FileOperation.EXTRACT) {
            val archive = pendingArchiveFile ?: run {
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
        progress.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { button ->
            button.isEnabled = false
            progress.binding.tvProgressMessage.text = "正在取消..."
            progress.binding.progressBar.isIndeterminate = true
            progress.binding.tvProgressPercent.visibility = View.GONE
            cancelledByUser.set(true)
            fileCopyJob?.cancel(CancellationException("用户取消复制"))
        }
    }

    private fun cancelDestinationSelection() {
        pendingFileOperation = null
        pendingArchiveFile = null
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
        val input = android.widget.EditText(this).apply {
            setText(file.name)
            setSelection(text.length)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
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
                            currentDirectory?.let(::loadDirectory)
                            showShortToast("已重命名")
                        } else {
                            showShortToast("重命名失败")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteSelectedFiles() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确定要删除选中的 ${files.size} 项吗？此操作无法撤销。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) { files.all { it.deleteRecursively() } }
                    if (deleted) {
                        exitSelectionMode()
                        currentDirectory?.let(::loadDirectory)
                        showShortToast("已删除")
                    } else {
                        showShortToast("删除失败")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMoreActions() {
        PopupMenu(this, binding.btnMoreSelected).apply {
            val selected = selectedFiles()
            val canConvert = selected.isNotEmpty() && selected.all {
                it.isFile && FileUtils.isSubtitleFile(it)
            }
            val convertItem = if (canConvert) {
                menu.add(getString(R.string.convert_format))
            } else {
                null
            }
            val compressItem = if (!isFileSearchQueryActive()) menu.add("压缩") else null
            val propertiesItem = menu.add("详情")
            setOnMenuItemClickListener { item ->
                when {
                    item === convertItem -> showSubtitleFormatConvertDialog()
                    item === compressItem -> showCreateArchiveDialog()
                    item === propertiesItem -> showSelectedProperties()
                }
                true
            }
            show()
        }
    }

    private fun showSubtitleFormatConvertDialog() {
        val sources = selectedFiles().filter { it.isFile && FileUtils.isSubtitleFile(it) }
        if (sources.isEmpty() || sources.size != selectedPaths.size) return

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

                val dialogBinding = DialogSubtitleConvertBinding.inflate(layoutInflater)
                if (conversionSources.size == 1) {
                    dialogBinding.tvSourceFile.text = getString(
                        R.string.dialog_subtitle_convert_source_file,
                        conversionSources.first().file.name
                    )
                    dialogBinding.tvSourceFormat.text = getString(
                        R.string.dialog_subtitle_convert_source_format,
                        SubtitleFormatConverter.displayName(conversionSources.first().source.format)
                    )
                } else {
                    dialogBinding.tvSourceFile.text = getString(
                        R.string.dialog_subtitle_convert_source_files,
                        conversionSources.size
                    )
                    dialogBinding.tvSourceFormat.text = getString(
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
                dialogBinding.spinnerTargetFormat.adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    targetFormats.map(SubtitleFormatConverter::displayName)
                ).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                val sourceFormats = conversionSources.map { it.source.format }.toSet()
                val defaultTargetIndex = targetFormats.indexOfFirst { it !in sourceFormats }
                    .takeIf { it >= 0 } ?: 0
                dialogBinding.spinnerTargetFormat.setSelection(defaultTargetIndex)

                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.dialog_subtitle_convert_title)
                    .setView(dialogBinding.root)
                    .setPositiveButton(R.string.start_convert, null)
                    .setNegativeButton(R.string.cancel, null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val targetFormat = targetFormats[dialogBinding.spinnerTargetFormat.selectedItemPosition]
                        val filesToConvert = conversionSources.filter { it.source.format != targetFormat }
                        if (filesToConvert.isEmpty()) {
                            showShortToast(getString(R.string.dialog_subtitle_convert_same_format))
                            return@setOnClickListener
                        }

                        val keepOriginal = dialogBinding.cbKeepOriginal.isChecked
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
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
                            dialog.dismiss()
                            conversionResult.onSuccess { batchResult ->
                                exitSelectionMode()
                                currentDirectory?.let(::loadDirectory)
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
                                    AlertDialog.Builder(this@MainActivity)
                                        .setTitle(R.string.dialog_subtitle_convert_title)
                                        .setMessage(
                                            message + "\n\n" + batchResult.failedFiles.joinToString("\n")
                                        )
                                        .setPositiveButton(R.string.confirm, null)
                                        .show()
                                }
                            }.onFailure { error ->
                                showShortToast(
                                    getString(
                                        R.string.dialog_subtitle_convert_failed,
                                        error.message ?: "未知错误"
                                    )
                                )
                            }
                        }
                    }
                }
                dialog.show()
            }
        }
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
        val outputDirectory = currentDirectory ?: return
        if (sources.isEmpty()) return

        val dialogBinding = DialogCreateArchiveBinding.inflate(layoutInflater)
        val formats = listOf(
            ArchiveManager.CreateFormat.ZIP,
            ArchiveManager.CreateFormat.SEVEN_Z,
            ArchiveManager.CreateFormat.TAR
        )
        val splitOptions = listOf(
            SplitOption("不分卷", null),
            SplitOption("10 MB", 10L * 1024 * 1024),
            SplitOption("50 MB", 50L * 1024 * 1024),
            SplitOption("100 MB", 100L * 1024 * 1024),
            SplitOption("500 MB", 500L * 1024 * 1024)
        )
        dialogBinding.etArchiveName.setText(ArchiveNamePolicy.defaultName(sources))
        dialogBinding.etArchiveName.setSelection(dialogBinding.etArchiveName.text?.length ?: 0)
        dialogBinding.spinnerArchiveFormat.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            formats.map { it.displayName }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        dialogBinding.spinnerSplitSize.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            splitOptions.map { it.label }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        var methods = archiveRepository.compressionMethods(formats.first())
        var encryptionMethods = archiveRepository.encryptionMethods(formats.first())
        fun refreshFormatControls(position: Int) {
            val format = formats[position.coerceIn(formats.indices)]
            methods = archiveRepository.compressionMethods(format)
            dialogBinding.spinnerCompressionMethod.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                methods.map { it.displayName }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            dialogBinding.spinnerCompressionMethod.setSelection(0, false)
            encryptionMethods = archiveRepository.encryptionMethods(format)
            dialogBinding.spinnerEncryptionMethod.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                encryptionMethods.map { it.displayName }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            if (encryptionMethods.isNotEmpty()) {
                dialogBinding.spinnerEncryptionMethod.setSelection(0, false)
            }
            val passwordEnabled = encryptionMethods.isNotEmpty()
            dialogBinding.layoutArchivePassword.isEnabled = passwordEnabled
            dialogBinding.btnPasswordBook.isEnabled = passwordEnabled
            val zipEncryptionOptions = format == ArchiveManager.CreateFormat.ZIP
            dialogBinding.layoutArchiveEncryption.visibility =
                if (zipEncryptionOptions) View.VISIBLE else View.GONE
            dialogBinding.spinnerEncryptionMethod.isEnabled = zipEncryptionOptions
            val splitEnabled = format == ArchiveManager.CreateFormat.ZIP ||
                format == ArchiveManager.CreateFormat.SEVEN_Z
            dialogBinding.spinnerSplitSize.isEnabled = splitEnabled
            if (!splitEnabled) dialogBinding.spinnerSplitSize.setSelection(0)
            dialogBinding.tvPasswordHint.text = when (format) {
                ArchiveManager.CreateFormat.ZIP -> "留空则不加密；ZipCrypto 兼容性更好，AES-256 更安全"
                ArchiveManager.CreateFormat.SEVEN_Z -> "使用 7Z AES-256 加密；留空则不加密"
                ArchiveManager.CreateFormat.TAR -> "密码仅适用于 ZIP 和 7Z 格式"
            }
        }
        dialogBinding.spinnerArchiveFormat.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    refreshFormatControls(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        refreshFormatControls(0)
        dialogBinding.btnPasswordBook.setOnClickListener {
            archivePasswordDialogController.showPasswordBook(dialogBinding.etArchivePassword)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("创建压缩文件")
            .setView(dialogBinding.root)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val format = formats[dialogBinding.spinnerArchiveFormat.selectedItemPosition]
                val method = methods[dialogBinding.spinnerCompressionMethod.selectedItemPosition]
                val splitSizeBytes = splitOptions[dialogBinding.spinnerSplitSize.selectedItemPosition].bytes
                val extension = archiveRepository.outputExtension(format, method)
                val rawName = dialogBinding.etArchiveName.text?.toString()?.trim().orEmpty()
                val baseName = ArchiveNamePolicy.stripExtension(rawName)
                when {
                    !ArchiveNamePolicy.isValidName(baseName) -> {
                        dialogBinding.etArchiveName.error = "请输入有效名称"
                    }
                    File(outputDirectory, "$baseName.$extension").exists() -> {
                        dialogBinding.etArchiveName.error = "同名压缩包已存在"
                    }
                    else -> {
                        val password = if (encryptionMethods.isNotEmpty()) {
                            dialogBinding.etArchivePassword.text?.toString().orEmpty()
                        } else {
                            ""
                        }
                        val encryptionMethod = when (format) {
                            ArchiveManager.CreateFormat.ZIP -> encryptionMethods[
                                dialogBinding.spinnerEncryptionMethod.selectedItemPosition
                            ]
                            ArchiveManager.CreateFormat.SEVEN_Z -> encryptionMethods.first()
                            ArchiveManager.CreateFormat.TAR -> null
                        }
                        dialog.dismiss()
                        createArchive(
                            sources = sources,
                            output = File(outputDirectory, "$baseName.$extension"),
                            format = format,
                            method = method,
                            password = password,
                            encryptionMethod = encryptionMethod,
                            splitSizeBytes = splitSizeBytes,
                            deleteSources = dialogBinding.cbDeleteSources.isChecked
                        )
                    }
                }
            }
        }
        dialog.show()
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
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("解压测试通过")
                            .setMessage(
                                "压缩包完整可读。\n\n条目：${tested.entryCount}\n解压大小：${FileUtils.formatFileSize(tested.totalBytes)}"
                            )
                            .setPositiveButton("确定", null)
                            .show()
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
        selectedPaths.clear()
        pendingArchiveFile = archive
        pendingFileOperation = FileOperation.EXTRACT
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

    private fun showBlockingProgress(title: String, message: String): AlertDialog =
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
        val current = currentDirectory ?: return
        saveCurrentDirectoryScrollPosition()
        if (loadDirectory(directory, restoreScrollPosition = true)) {
            directoryHistory += current
        }
    }

    private fun navigateDestinationUp(): Boolean {
        val current = currentDirectory ?: return false
        val storageRoot = getDefaultDirectory()
        val currentPath = runCatching { current.canonicalPath }.getOrElse { current.absolutePath }
        val rootPath = runCatching { storageRoot.canonicalPath }.getOrElse { storageRoot.absolutePath }
        if (currentPath == rootPath) return false

        val target = current.parentFile ?: return false
        if (!target.exists() || !target.canRead()) return false
        saveCurrentDirectoryScrollPosition()
        if (loadDirectory(target, restoreScrollPosition = true)) {
            val historyTarget = directoryHistory.lastOrNull()
            val historyPath = historyTarget?.let { history ->
                runCatching { history.canonicalPath }.getOrElse { history.absolutePath }
            }
            if (historyPath == runCatching { target.canonicalPath }.getOrElse { target.absolutePath }) {
                directoryHistory.removeAt(directoryHistory.lastIndex)
            } else {
                directoryHistory.clear()
            }
            return true
        }
        return false
    }
    
    private fun goUpLevel() {
        if (directoryHistory.isNotEmpty()) {
            saveCurrentDirectoryScrollPosition()
            val parent = directoryHistory.removeAt(directoryHistory.size - 1)
            loadDirectory(parent, restoreScrollPosition = true)
        } else {
            currentDirectory?.parentFile?.let { parent ->
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
        when (MainBackNavigationPolicy.decide(
            isDirectorySelected = stateModel.selectedTopLevelItem == R.id.nav_directory,
            hasPendingFileOperation = pendingFileOperation != null,
            hasSelection = selectedPaths.isNotEmpty(),
            hasDirectoryHistory = directoryHistory.isNotEmpty()
        )) {
            MainBackNavigationPolicy.Decision.DELEGATE_TO_TOP_LEVEL -> {
                val handled = (supportFragmentManager.findFragmentById(R.id.fragmentContainer)
                    as? TopLevelBackHandler)?.handleTopLevelBack() == true
                if (!handled) finishFromBackNavigation()
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
