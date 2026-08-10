package com.subtitleedit

import android.Manifest
import android.content.ClipData
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.subtitleedit.adapter.FileListAdapter
import com.subtitleedit.databinding.ActivityMainBinding
import com.subtitleedit.databinding.DialogArchiveProgressBinding
import com.subtitleedit.databinding.DialogArchivePasswordBinding
import com.subtitleedit.databinding.DialogArchiveConflictBinding
import com.subtitleedit.databinding.DialogCreateArchiveBinding
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.util.ArchiveManager
import com.subtitleedit.util.ArchivePreviewCache
import com.subtitleedit.util.ArchivePasswordVault
import com.subtitleedit.model.ArchiveConflictDialogFormatter
import com.subtitleedit.model.ArchiveConflictDialogModel
import com.subtitleedit.model.ArchiveConflictFileMetadata
import com.subtitleedit.model.FileBrowserOrder
import com.subtitleedit.model.FileBrowserSearch
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.UpdateChecker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.subtitleedit.util.ArchivePasswordRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * 主界面 - 文件浏览器
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val MENU_SELECT_ALL = 0x10001
        const val MENU_SELECT_RANGE = 0x10002
        const val MENU_SEARCH = 0x10003
        const val MENU_CREATE = 0x10004
        const val MENU_MORE = 0x10005
        const val CONFLICT_WAIT_INTERVAL_MS = 250L
        const val DIRECTORY_REFRESH_DELAY_MS = 250L
        const val SEARCH_PROGRESS_INTERVAL_MS = 150L
        val DIRECTORY_CHANGE_EVENTS = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or
            FileObserver.ATTRIB or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v",
            "ts", "3gp", "mpg", "mpeg", "mts", "m2ts"
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileAdapter: FileListAdapter
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
    private var updateCheckStarted = false
    private var pendingUpdate: UpdateChecker.UpdateInfo? = null
    private var updateDialogShown = false
    private var showAllFileTypes = false
    private var showHiddenFiles = false
    private var sortField: FileSortField
        get() = stateModel.sortField ?: FileSortField.NAME
        set(value) { stateModel.sortField = value }
    private var sortDirection: FileSortDirection
        get() = stateModel.sortDirection ?: FileSortDirection.ASCENDING
        set(value) { stateModel.sortDirection = value }
    private val directoryRefreshHandler = Handler(Looper.getMainLooper())
    private var directoryObserver: FileObserver? = null
    private var observedDirectoryPath: String? = null
    private var directoryWatchingEnabled = false
    private var directorySearchJob: Job? = null
    private var fileCopyJob: Job? = null
    private var directorySearchGeneration = 0L
    private var activeFileSearchView: SearchView? = null
    private val directoryRefreshRunnable = Runnable {
        val directory = currentDirectory ?: return@Runnable
        if (directory.exists() && directory.canRead()) loadDirectory(directory)
    }

    private enum class ArchiveAction { PREVIEW, EXTRACT_CURRENT, TEST }

    private data class SplitOption(val label: String, val bytes: Long?)

    private data class ArchiveProgressUi(
        val dialog: AlertDialog,
        val binding: DialogArchiveProgressBinding
    )

    private data class FilePropertiesInfo(
        val name: String,
        val path: String,
        val type: String,
        val size: String,
        val modifiedTime: String,
        val mediaInfoTitle: String?,
        val mediaDetails: List<PropertyDetail>
    )

    private data class PropertyDetail(val label: String, val value: String)

    private data class SortOptionRow(
        val container: LinearLayout,
        val radioButton: AppCompatRadioButton
    )

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
        showAllFileTypes = settingsManager.isShowAllFileTypesEnabled()
        showHiddenFiles = settingsManager.isShowHiddenFilesEnabled()
        if (stateModel.sortField == null) sortField = settingsManager.getFileSortField()
        if (stateModel.sortDirection == null) sortDirection = settingsManager.getFileSortDirection()
        
        setupToolbar()
        setupRecyclerView()
        setupButtons()
        setupBottomNavigation()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        directoryWatchingEnabled = true
        val newShowAllFileTypes = SettingsManager.getInstance(this).isShowAllFileTypesEnabled()
        val newShowHiddenFiles = SettingsManager.getInstance(this).isShowHiddenFilesEnabled()
        showAllFileTypes = newShowAllFileTypes
        showHiddenFiles = newShowHiddenFiles
        if (stateModel.selectedTopLevelItem == R.id.nav_directory) currentDirectory?.let(::loadDirectory)
        pendingUpdate?.let(::showPendingUpdate)
        if (!updateCheckStarted && SettingsManager.getInstance(this).shouldCheckUpdatesOnStartup()) {
            updateCheckStarted = true
            lifecycleScope.launch {
                val update = UpdateChecker.check(this@MainActivity) ?: return@launch
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    showPendingUpdate(update)
                } else {
                    pendingUpdate = update
                }
            }
        }
    }

    override fun onPause() {
        directoryWatchingEnabled = false
        stopDirectoryObserver()
        super.onPause()
    }

    private fun showPendingUpdate(update: UpdateChecker.UpdateInfo) {
        pendingUpdate = null
        if (updateDialogShown || isFinishing || isDestroyed) return
        updateDialogShown = true
        UpdateChecker.showUpdateDialog(this, update)
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.nav_directory)
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean = true

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        if (stateModel.selectedTopLevelItem != R.id.nav_directory) return true
        if (selectedPaths.isNotEmpty() || pendingFileOperation != null) {
            if (pendingFileOperation == null) {
                menu.add(Menu.NONE, MENU_SELECT_ALL, 0, "全选")
                    .setIcon(R.drawable.ic_select_all)
                    .setContentDescription("全选")
                    .setTooltipText("全选")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(Menu.NONE, MENU_SELECT_RANGE, 1, "局部全选")
                    .setIcon(R.drawable.ic_select_range)
                    .setContentDescription("局部全选")
                    .setTooltipText("局部全选")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        } else {
            val searchItem = menu.add(Menu.NONE, MENU_SEARCH, 0, R.string.menu_search)
                .setIcon(R.drawable.ic_search)
            searchItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
            configureSearchItem(searchItem)
            menu.add(Menu.NONE, MENU_CREATE, 1, R.string.menu_new)
                .setIcon(R.drawable.ic_add)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, MENU_MORE, 2, R.string.activity_main_text_01)
                .setIcon(R.drawable.ic_more_vertical)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SELECT_ALL -> {
            selectAllVisibleFiles()
            true
        }
        MENU_SELECT_RANGE -> {
            selectRangeBetweenSelectedFiles()
            true
        }
        MENU_CREATE -> {
            showCreateMenu()
            true
        }
        MENU_MORE -> {
            showDirectoryMoreMenu()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
    
    private fun setupRecyclerView() {
        fileAdapter = FileListAdapter(
            onItemClick = ::onFileClicked,
            onItemLongClick = ::enterSelectionMode,
            isItemRestricted = ::isRestrictedAndroidDirectory
        )
        
        binding.rvFileList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = fileAdapter
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
        binding.bottomNavigation.selectedItemId = stateModel.selectedTopLevelItem
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            showTopLevelPage(item.itemId)
            true
        }
        showTopLevelPage(stateModel.selectedTopLevelItem)
    }

    private fun showTopLevelPage(itemId: Int) {
        stateModel.selectedTopLevelItem = itemId
        val directorySelected = itemId == R.id.nav_directory
        binding.directoryContent.visibility = if (directorySelected) View.VISIBLE else View.GONE
        binding.fragmentContainer.visibility = if (directorySelected) View.GONE else View.VISIBLE
        binding.toolbar.navigationIcon = null
        binding.toolbar.setNavigationOnClickListener(null)

        if (directorySelected) {
            supportActionBar?.title = getString(R.string.nav_directory)
            currentDirectory?.let(::loadDirectory)
        } else {
            directorySearchJob?.cancel()
            stopDirectoryObserver()
            val fragment = when (itemId) {
                R.id.nav_favorites -> FavoritesFragment()
                R.id.nav_drafts -> DraftsFragment()
                R.id.nav_tools -> ToolsFragment()
                else -> SettingsFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, fragment, itemId.toString())
                .commit()
            supportActionBar?.title = topLevelTitle(itemId)
        }
        binding.bottomNavigation.visibility = View.VISIBLE
        binding.bottomDivider.visibility = View.VISIBLE
        invalidateOptionsMenu()
    }

    private fun topLevelTitle(itemId: Int): String =
        when (itemId) {
                R.id.nav_favorites -> getString(R.string.nav_favorites)
                R.id.nav_drafts -> getString(R.string.drafts)
                R.id.nav_tools -> getString(R.string.menu_main_title_01)
                else -> getString(R.string.menu_main_title_02)
        }

    fun updateTopLevelToolbar(title: String, showBack: Boolean = false, onBack: (() -> Unit)? = null) {
        binding.toolbar.title = title
        binding.toolbar.navigationIcon = if (showBack) ContextCompat.getDrawable(this, R.drawable.ic_back) else null
        binding.toolbar.setNavigationOnClickListener(if (showBack) View.OnClickListener { onBack?.invoke() } else null)
    }

    fun openDirectoryFromFavorites(directory: File) {
        if (!loadDirectory(directory)) return
        directoryHistory.clear()
        selectedPaths.clear()
        pendingFileOperation = null
        pendingArchiveFile = null
        stateModel.searchQuery = ""
        stateModel.isFileSearchActive = false
        binding.bottomNavigation.selectedItemId = R.id.nav_directory
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
        PopupMenu(this, anchor).apply {
            menu.add("新建文件夹")
            menu.add("新建文件")
            setOnMenuItemClickListener { item ->
                if (item.title == "新建文件") showCreateFileDialog() else showCreateFolderDialog()
                true
            }
            show()
        }
    }

    private fun showCreateFolderDialog() {
        val input = EditText(this).apply {
            hint = "文件夹名称"
            setSingleLine(true)
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("新建文件夹")
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = input.text.toString()
                val validation = FileBrowserOrder.validateName(entered)
                if (validation != null) {
                    input.error = validation
                    return@setOnClickListener
                }
                val name = entered.trim()
                val directory = currentDirectory ?: return@setOnClickListener
                val target = File(directory, name)
                if (target.exists()) {
                    input.error = "同名项目已存在"
                    return@setOnClickListener
                }
                val created = runCatching {
                    target.mkdir()
                }.getOrDefault(false)
                if (!created) {
                    input.error = "创建失败，请检查目录写入权限"
                    return@setOnClickListener
                }
                dialog.dismiss()
                loadDirectory(directory)
            }
        }
        dialog.show()
    }

    private fun showCreateFileDialog() {
        val nameInput = EditText(this).apply {
            hint = "文件名"
            setSingleLine(true)
        }
        val extensionInput = EditText(this).apply {
            hint = "扩展名"
            setText("txt")
            setSingleLine(true)
        }
        val inputs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(nameInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
            addView(extensionInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("新建文件")
            .setView(inputs)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                nameInput.error = null
                extensionInput.error = null
                FileBrowserOrder.validateName(nameInput.text.toString())?.let { error ->
                    nameInput.error = error
                    return@setOnClickListener
                }
                FileBrowserOrder.validateExtension(extensionInput.text.toString())?.let { error ->
                    extensionInput.error = error
                    return@setOnClickListener
                }
                val name = FileBrowserOrder.composeFileName(
                    nameInput.text.toString(),
                    extensionInput.text.toString()
                )
                val directory = currentDirectory ?: return@setOnClickListener
                val target = File(directory, name)
                if (target.exists()) {
                    nameInput.error = "同名项目已存在"
                    return@setOnClickListener
                }
                val created = runCatching { target.createNewFile() }.getOrDefault(false)
                if (!created) {
                    nameInput.error = "创建失败，请检查目录写入权限"
                    return@setOnClickListener
                }
                dialog.dismiss()
                loadDirectory(directory)
            }
        }
        dialog.show()
        nameInput.requestFocus()
    }

    private fun showDirectoryMoreMenu() {
        val anchor = binding.toolbar.findViewById<View>(MENU_MORE) ?: binding.toolbar
        PopupMenu(this, anchor).apply {
            menu.add("排序")
            menu.add("设置")
            setOnMenuItemClickListener { item ->
                if (item.title == "排序") showSortDialog()
                else startActivity(Intent(this@MainActivity, FileManagementSettingsActivity::class.java))
                true
            }
            show()
        }
    }

    private fun showSortDialog() {
        val fields = listOf(
            "名称" to FileSortField.NAME,
            "类型" to FileSortField.TYPE,
            "大小" to FileSortField.SIZE,
            "日期" to FileSortField.DATE
        )
        val directions = listOf("升序" to FileSortDirection.ASCENDING, "降序" to FileSortDirection.DESCENDING)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val fieldRows = mutableListOf<Pair<SortOptionRow, FileSortField>>()
        val directionRows = mutableListOf<Pair<SortOptionRow, FileSortDirection>>()
        lateinit var refreshSelection: () -> Unit
        fields.forEach { (label, value) ->
            val row = sortRow(label, false) {
                sortField = value
                SettingsManager.getInstance(this).setFileSortField(value)
                displayDirectoryFiles()
                refreshSelection()
            }
            fieldRows.add(row to value)
            container.addView(row.container)
        }
        directions.forEach { (label, value) ->
            val row = sortRow(label, false) {
                sortDirection = value
                SettingsManager.getInstance(this).setFileSortDirection(value)
                displayDirectoryFiles()
                refreshSelection()
            }
            directionRows.add(row to value)
            container.addView(row.container)
        }
        refreshSelection = {
            fieldRows.forEach { (row, value) -> setSortRowSelected(row, sortField == value) }
            directionRows.forEach { (row, value) -> setSortRowSelected(row, sortDirection == value) }
        }
        refreshSelection()
        AlertDialog.Builder(this).setTitle("排序").setView(container)
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun sortRow(label: String, selected: Boolean, onClick: () -> Unit): SortOptionRow {
        val radio = AppCompatRadioButton(this).apply {
            isClickable = false
            isFocusable = false
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    ContextCompat.getColor(this@MainActivity, R.color.primary),
                    ContextCompat.getColor(this@MainActivity, R.color.on_surface_variant)
                )
            )
            layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 16f
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            })
            addView(radio)
            setOnClickListener { onClick() }
        }
        return SortOptionRow(row, radio).also { setSortRowSelected(it, selected) }
    }

    private fun setSortRowSelected(row: SortOptionRow, selected: Boolean) {
        row.container.setBackgroundColor(
            if (selected) ContextCompat.getColor(this, R.color.primary_container)
            else android.graphics.Color.TRANSPARENT
        )
        row.radioButton.isChecked = selected
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
        if (loadDirectory(restored ?: getDefaultDirectory())) return
        if (restored == null) return

        directoryHistory.clear()
        selectedPaths.clear()
        pendingFileOperation = null
        pendingArchiveFile = null
        loadDirectory(getDefaultDirectory())
    }
    
    private fun loadDirectory(directory: File): Boolean {
        if (!directory.exists() || !directory.canRead()) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "无法访问目录：${directory.name}", Toast.LENGTH_SHORT).show()
            return false
        }
        
        currentDirectory = directory
        updatePathDisplay()
        
        val files = mutableListOf<File>()
        
        files.addAll(
            directory.listFiles { file ->
                (showHiddenFiles || !file.name.startsWith(".")) &&
                    (file.isDirectory || shouldDisplayFile(file, showAllFileTypes))
            }?.toList().orEmpty()
        )
        directoryFiles.clear()
        directoryFiles.addAll(files.distinctBy { it.absolutePath })
        displayDirectoryFiles()
        startDirectoryObserver(directory)
        return true
    }

    private fun displayDirectoryFiles() {
        directorySearchJob?.cancel()
        directorySearchJob = null
        val searchGeneration = ++directorySearchGeneration
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
                searching = false
            )
            return
        }

        binding.searchProgress.visibility = View.VISIBLE
        showDirectoryFiles(
            directMatches,
            showParent = false,
            relativePathRoot = directory,
            searching = true
        )

        val rootPath = directory.absolutePath
        val includeHidden = showHiddenFiles
        val searchSortField = sortField
        val searchSortDirection = sortDirection
        directorySearchJob = lifecycleScope.launch {
            try {
                val displayed = withContext(Dispatchers.IO) {
                    val searchContext = coroutineContext
                    var lastPublishedAt = 0L
                    var lastPublishedCount = -1
                    val matches = FileBrowserSearch.search(
                        root = directory,
                        query = query,
                        includeHidden = includeHidden,
                        includeFile = { true },
                        canEnterDirectory = { !isRestrictedAndroidDirectory(it) },
                        onEntryVisited = { searchContext.ensureActive() },
                        onDirectoryScanned = { partialMatches ->
                            searchContext.ensureActive()
                            val now = SystemClock.elapsedRealtime()
                            if (partialMatches.size != lastPublishedCount &&
                                now - lastPublishedAt >= SEARCH_PROGRESS_INTERVAL_MS
                            ) {
                                lastPublishedAt = now
                                lastPublishedCount = partialMatches.size
                                val partialDisplayed = FileBrowserOrder.sort(
                                    partialMatches.toList(),
                                    searchSortField,
                                    searchSortDirection
                                )
                                directoryRefreshHandler.post {
                                    if (directorySearchGeneration == searchGeneration &&
                                        directorySearchJob?.isActive == true &&
                                        currentDirectory?.absolutePath == rootPath &&
                                        stateModel.searchQuery.trim() == query
                                    ) {
                                        showDirectoryFiles(
                                            partialDisplayed,
                                            showParent = false,
                                            relativePathRoot = directory,
                                            searching = true
                                        )
                                    }
                                }
                            }
                        }
                    )
                    FileBrowserOrder.sort(matches, searchSortField, searchSortDirection)
                }
                if (directorySearchGeneration != searchGeneration ||
                    currentDirectory?.absolutePath != rootPath ||
                    stateModel.searchQuery.trim() != query
                ) {
                    return@launch
                }
                showDirectoryFiles(
                    displayed,
                    showParent = false,
                    relativePathRoot = directory,
                    searching = false
                )
            } finally {
                if (directorySearchGeneration == searchGeneration) {
                    binding.searchProgress.visibility = View.INVISIBLE
                }
            }
        }
    }

    private fun showDirectoryFiles(
        displayed: List<File>,
        showParent: Boolean,
        relativePathRoot: File?,
        searching: Boolean
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
        fileAdapter.submitList(adapterItems) { fileAdapter.notifyDataSetChanged() }
        updateSelectionUi(invalidateMenu = false)
        binding.tvEmptyStateMessage.setText(
            if (searching) R.string.file_searching else R.string.no_files
        )
        binding.emptyState.visibility = if (adapterItems.isEmpty()) View.VISIBLE else View.GONE
        binding.rvFileList.visibility = if (adapterItems.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun shouldDisplayFile(file: File, includeAllFileTypes: Boolean): Boolean {
        if (!file.isFile) return false
        return includeAllFileTypes ||
            FileUtils.isSubtitleFile(file) ||
            FileUtils.isAudioFile(file) ||
            file.extension.lowercase() in VIDEO_EXTENSIONS ||
            ArchiveManager.isRecognizedArchive(file)
    }

    private fun startDirectoryObserver(directory: File) {
        if (!directoryWatchingEnabled) return
        val path = runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath }
        if (path == observedDirectoryPath && directoryObserver != null) return
        stopDirectoryObserver()

        directoryObserver = createDirectoryObserver(directory).also { observer ->
            observedDirectoryPath = path
            observer.startWatching()
        }
    }

    @Suppress("DEPRECATION")
    private fun createDirectoryObserver(directory: File): FileObserver =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(directory, DIRECTORY_CHANGE_EVENTS) {
                override fun onEvent(event: Int, path: String?) = scheduleDirectoryRefresh(event)
            }
        } else {
            object : FileObserver(directory.absolutePath, DIRECTORY_CHANGE_EVENTS) {
                override fun onEvent(event: Int, path: String?) = scheduleDirectoryRefresh(event)
            }
        }

    private fun scheduleDirectoryRefresh(event: Int) {
        if (!directoryWatchingEnabled || event and DIRECTORY_CHANGE_EVENTS == 0) return
        directoryRefreshHandler.removeCallbacks(directoryRefreshRunnable)
        directoryRefreshHandler.postDelayed(directoryRefreshRunnable, DIRECTORY_REFRESH_DELAY_MS)
    }

    private fun stopDirectoryObserver() {
        directoryRefreshHandler.removeCallbacks(directoryRefreshRunnable)
        directoryObserver?.stopWatching()
        directoryObserver = null
        observedDirectoryPath = null
    }
    
    private fun updatePathDisplay() {
        currentDirectory?.let {
            binding.tvCurrentPath.text = it.absolutePath
        }
    }
    
    private fun onFileClicked(file: File) {
        // 处理父目录导航
        if (file.name == "..") {
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
            if (file.isDirectory) {
                navigateIntoDirectory(file)
            } else {
                toggleSelection(file)
            }
            return
        }
        
        if (file.isDirectory) {
            // 进入子目录
            navigateIntoDirectory(file)
        } else if (ArchiveManager.isRecognizedArchive(file)) {
            if (ArchiveManager.isSupportedArchive(file)) {
                showArchiveActions(file)
            } else {
                showShortToast("当前库暂不支持 ${file.extension.uppercase()} 格式")
            }
        } else if (FileUtils.isSubtitleFile(file)) {
            // 打开字幕文件进行编辑
            openFileForEdit(file)
        } else if (FileUtils.isAudioFile(file)) {
            openMediaFileForEdit(file, EditorMediaType.AUDIO)
        } else if (file.extension.lowercase() in VIDEO_EXTENSIONS) {
            showVideoOpenModePicker(file)
        } else {
            com.subtitleedit.util.OverwritingToast.makeText(this, "不支持的文件格式", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateIntoDirectory(directory: File) {
        val previousDirectory = currentDirectory ?: return
        val wasSearching = isFileSearchQueryActive()
        val historyEntries = if (wasSearching) {
            navigationHistoryForSearchResult(previousDirectory, directory)
        } else {
            listOf(previousDirectory)
        }
        if (wasSearching) {
            clearFileSearch(refreshDirectory = false)
        }

        if (loadDirectory(directory)) {
            directoryHistory.addAll(historyEntries)
            if (wasSearching) invalidateOptionsMenu()
        }
    }

    private fun navigationHistoryForSearchResult(root: File, target: File): List<File> {
        val rootPath = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        val reversedHistory = mutableListOf<File>()
        var directory = target.parentFile

        while (directory != null) {
            reversedHistory.add(directory)
            val directoryPath = runCatching { directory.canonicalPath }
                .getOrElse { directory.absolutePath }
            if (directoryPath == rootPath) return reversedHistory.asReversed()
            directory = directory.parentFile
        }

        return listOf(root)
    }

    private fun isFileSearchQueryActive(): Boolean =
        stateModel.isFileSearchActive && stateModel.searchQuery.isNotBlank()

    private fun clearFileSearch(refreshDirectory: Boolean) {
        stateModel.isFileSearchActive = false
        stateModel.searchQuery = ""
        directorySearchJob?.cancel()
        directorySearchJob = null
        directorySearchGeneration++
        binding.searchProgress.visibility = View.INVISIBLE
        if (refreshDirectory) displayDirectoryFiles()
    }

    private fun isRestrictedAndroidDirectory(file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            !file.isDirectory ||
            !file.name.equals("Android", ignoreCase = true)
        ) {
            return false
        }

        val storageRoot = Environment.getExternalStorageDirectory()
        val parentPath = runCatching { file.parentFile?.canonicalPath }.getOrNull()
        val storageRootPath = runCatching { storageRoot.canonicalPath }.getOrNull()
        return parentPath != null && parentPath == storageRootPath
    }

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

        val start = selectedIndices.minOrNull() ?: return
        val end = selectedIndices.maxOrNull() ?: return
        selectedPaths.addAll(visibleFiles.subList(start, end + 1).map { it.absolutePath })
        updateSelectionUi()
    }

    private fun selectedFiles(): List<File> = selectedPaths.map(::File).filter { it.exists() }

    private fun updateSelectionUi(invalidateMenu: Boolean = true) {
        val operation = pendingFileOperation
        val isSelectionUiActive = selectedPaths.isNotEmpty() || operation != null
        binding.selectionBottomActions.visibility = if (isSelectionUiActive) View.VISIBLE else View.GONE
        val showTopLevelNavigation = !isSelectionUiActive
        binding.bottomNavigation.visibility = if (showTopLevelNavigation) View.VISIBLE else View.GONE
        binding.bottomDivider.visibility = if (showTopLevelNavigation) View.VISIBLE else View.GONE

        val selectionTitle = when (operation) {
            FileOperation.COPY -> "选择复制目标（已选 ${selectedPaths.size} 项）"
            FileOperation.MOVE -> "选择移动目标（已选 ${selectedPaths.size} 项）"
            FileOperation.EXTRACT -> "选择解压目录"
            null -> "已选择 ${selectedPaths.size} 项"
        }
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
        binding.btnConfirmDestination.text = when (operation) {
            FileOperation.MOVE -> "移动到此处"
            FileOperation.EXTRACT -> "解压到此处"
            else -> "复制到此处"
        }
        supportActionBar?.title = if (isSelectionUiActive) {
            selectionTitle
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
        val sourcePath = source.canonicalFile
        val destinationPath = destination.canonicalFile
        if (source.isDirectory && destinationPath.path.startsWith(sourcePath.path + File.separator)) {
            throw IllegalArgumentException("不能将文件夹复制或移动到其自身内部")
        }
        val target = File(destination, uniqueFileName(destination, source.name))
        try {
            Files.move(source.toPath(), target.toPath())
        } catch (error: Exception) {
            if (!source.renameTo(target)) throw error
        }
    }

    private suspend fun copyFiles(
        sources: List<File>,
        destination: File,
        onProgress: (message: String, completed: Long, total: Long) -> Unit
    ) {
        var total = 0L
        sources.forEach { source ->
            currentCoroutineContext().ensureActive()
            total = addFileSize(total, fileTreeSize(source))
        }

        var completed = 0L
        var lastProgressUpdate = 0L
        fun reportProgress(message: String, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (force || now - lastProgressUpdate >= 100L || (total > 0L && completed >= total)) {
                lastProgressUpdate = now
                onProgress(message, completed, total)
            }
        }
        reportProgress("正在复制...", force = true)

        sources.forEach { source ->
            currentCoroutineContext().ensureActive()
            val target = File(destination, uniqueFileName(destination, source.name))
            val temporaryTarget = temporaryCopyTarget(destination, target.name)
            try {
                copyRecursivelyCancellable(source, temporaryTarget) { bytesCopied ->
                    completed = addFileSize(completed, bytesCopied)
                    reportProgress("正在复制 ${source.name}...")
                }
                currentCoroutineContext().ensureActive()
                Files.move(temporaryTarget.toPath(), target.toPath())
                reportProgress("正在复制 ${source.name}...", force = true)
            } catch (error: Throwable) {
                temporaryTarget.deleteRecursively()
                throw error
            }
        }
    }

    private suspend fun copyRecursivelyCancellable(
        source: File,
        target: File,
        onBytesCopied: (Long) -> Unit
    ) {
        currentCoroutineContext().ensureActive()
        if (source.isDirectory) {
            if (!target.mkdirs() && !target.isDirectory) {
                throw IllegalStateException("无法创建目录：${target.name}")
            }
            val children = source.listFiles()
                ?: throw IllegalStateException("无法读取目录：${source.name}")
            children.forEach { child ->
                copyRecursivelyCancellable(child, File(target, child.name), onBytesCopied)
            }
            return
        }

        target.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("无法创建目录：${parent.name}")
            }
        }
        val buffer = ByteArray(1024 * 1024)
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    onBytesCopied(count.toLong())
                }
            }
        }
    }

    private suspend fun fileTreeSize(file: File): Long {
        currentCoroutineContext().ensureActive()
        if (file.isFile) return file.length().coerceAtLeast(0L)
        if (!file.isDirectory) return 0L
        val children = file.listFiles() ?: return 0L
        var total = 0L
        children.forEach { child ->
            total = addFileSize(total, fileTreeSize(child))
        }
        return total
    }

    private fun addFileSize(current: Long, amount: Long): Long =
        if (amount > 0L && current > Long.MAX_VALUE - amount) Long.MAX_VALUE else current + amount

    private fun temporaryCopyTarget(destination: File, targetName: String): File {
        var index = 0
        var candidate: File
        do {
            val suffix = if (index == 0) "" else "-$index"
            candidate = File(destination, ".${targetName}.copying-${System.nanoTime()}$suffix")
            index++
        } while (candidate.exists())
        return candidate
    }

    private fun uniqueFileName(directory: File, originalName: String): String {
        if (!File(directory, originalName).exists()) return originalName
        val separator = originalName.lastIndexOf('.')
        val base = if (separator > 0) originalName.substring(0, separator) else originalName
        val extension = if (separator > 0) originalName.substring(separator) else ""
        var index = 1
        var candidate: String
        do {
            candidate = "$base ($index)$extension"
            index++
        } while (File(directory, candidate).exists())
        return candidate
    }

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
            val compressItem = if (!isFileSearchQueryActive()) menu.add("压缩") else null
            val propertiesItem = menu.add("详情")
            setOnMenuItemClickListener { item ->
                when {
                    item === compressItem -> showCreateArchiveDialog()
                    item === propertiesItem -> showSelectedProperties()
                }
                true
            }
            show()
        }
    }

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
        dialogBinding.etArchiveName.setText(defaultArchiveName(sources))
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

        var methods = ArchiveManager.compressionMethods(formats.first())
        var encryptionMethods = ArchiveManager.encryptionMethods(formats.first())
        fun refreshFormatControls(position: Int) {
            val format = formats[position.coerceIn(formats.indices)]
            methods = ArchiveManager.compressionMethods(format)
            dialogBinding.spinnerCompressionMethod.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                methods.map { it.displayName }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            dialogBinding.spinnerCompressionMethod.setSelection(0, false)
            encryptionMethods = ArchiveManager.encryptionMethods(format)
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
            showPasswordBook(dialogBinding.etArchivePassword)
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
                val extension = ArchiveManager.outputExtension(format, method)
                val rawName = dialogBinding.etArchiveName.text?.toString()?.trim().orEmpty()
                val baseName = stripArchiveExtension(rawName)
                when {
                    !isValidFileName(baseName) -> {
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
        sources: List<File>,
        output: File,
        format: ArchiveManager.CreateFormat,
        method: ArchiveManager.CompressionMethod,
        password: String,
        encryptionMethod: ArchiveManager.EncryptionMethod?,
        splitSizeBytes: Long?,
        deleteSources: Boolean
    ) {
        val progress = showArchiveProgress(
            title = "正在压缩：",
            message = sources.firstOrNull()?.name ?: output.name,
            showCancel = true
        )
        val committed = AtomicBoolean(false)
        val compressionJob = lifecycleScope.launch {
            val passwordChars = password.takeIf(String::isNotEmpty)?.toCharArray()
            try {
                val deleteFailures = withContext(Dispatchers.IO) {
                    val workerContext = coroutineContext
                    ArchiveManager.createArchive(
                        sources = sources,
                        destination = output,
                        format = format,
                        method = method,
                        password = passwordChars,
                        encryptionMethod = encryptionMethod,
                        splitSizeBytes = splitSizeBytes,
                        checkCancelled = workerContext::ensureActive,
                        onDetailedProgress = { compressionProgress ->
                            updateCompressionProgress(progress, compressionProgress)
                        },
                        onCommitted = {
                            committed.set(true)
                            runOnUiThread {
                                if (progress.dialog.isShowing) {
                                    progress.dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                                    if (deleteSources) {
                                        progress.binding.tvProgressMessage.text = "正在删除源文件..."
                                    }
                                }
                            }
                        }
                    )
                    if (deleteSources) sources.filterNot { it.deleteRecursively() } else emptyList()
                }
                exitSelectionMode()
                output.parentFile?.let(::loadDirectory)
                if (deleteFailures.isEmpty()) {
                    showShortToast("压缩完成：${output.name}")
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("压缩已完成")
                        .setMessage("${output.name} 已创建，但有 ${deleteFailures.size} 个源文件无法删除。")
                        .setPositiveButton("确定", null)
                        .show()
                }
            } catch (_: CancellationException) {
                exitSelectionMode()
                output.parentFile?.let(::loadDirectory)
                showShortToast(if (committed.get()) "压缩完成：${output.name}" else "已取消压缩")
            } catch (error: Throwable) {
                showOperationError("压缩失败", error)
                output.parentFile?.let(::loadDirectory)
            } finally {
                passwordChars?.fill('\u0000')
                progress.dialog.dismiss()
            }
        }
        progress.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { button ->
            if (committed.get()) {
                button.isEnabled = false
                return@setOnClickListener
            }
            button.isEnabled = false
            progress.binding.tvProgressMessage.text = "正在取消..."
            progress.binding.progressBar.isIndeterminate = true
            progress.binding.tvProgressPercent.visibility = View.GONE
            progress.binding.tvProgressLeading.visibility = View.GONE
            progress.binding.tvProgressProcessed.visibility = View.GONE
            compressionJob.cancel(CancellationException("用户取消压缩"))
        }
    }

    private fun showArchiveActions(archive: File) {
        val actions = arrayOf("解压预览", "解压到当前文件夹", "解压到指定目录", "解压测试")
        AlertDialog.Builder(this)
            .setTitle(archive.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> runArchiveAction(archive, ArchiveAction.PREVIEW)
                    1 -> runArchiveAction(archive, ArchiveAction.EXTRACT_CURRENT)
                    2 -> startExtractDestinationSelection(archive)
                    3 -> runArchiveAction(archive, ArchiveAction.TEST)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

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
        val title = when (action) {
            ArchiveAction.PREVIEW -> "正在读取压缩包"
            ArchiveAction.TEST -> "正在测试压缩包"
            ArchiveAction.EXTRACT_CURRENT -> error("不应直接执行解压操作")
        }
        val progress = showBlockingProgress(title, archive.name)
        lifecycleScope.launch {
            val passwordChars = password?.toCharArray()
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        when (action) {
                            ArchiveAction.PREVIEW -> {
                                val entries = ArchiveManager.listEntries(archive, passwordChars)
                                ArchivePreviewCache.write(this@MainActivity, entries)
                            }
                            ArchiveAction.TEST -> ArchiveManager.testArchive(archive, passwordChars)
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
                if (needsArchivePassword(archive, error, password != null)) {
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
        if (ArchiveManager.requiresStreamingConflictResolution(archive)) {
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
                        ArchiveManager.findDestinationConflicts(
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
                if (needsArchivePassword(archive, error, password != null)) {
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
        if (index >= conflicts.size) {
            executeArchiveExtraction(
                archive,
                destination,
                password,
                ArchiveManager.ConflictPolicy.FAIL,
                policies.toMap(),
                onCompleted,
                onCancelled
            )
            return
        }
        showExtractionConflictDialog(
            conflict = conflicts[index],
            onPolicySelected = { selectedPolicy, applyToAll ->
                if (applyToAll) {
                    executeArchiveExtraction(
                        archive,
                        destination,
                        password,
                        selectedPolicy,
                        policies.toMap(),
                        onCompleted,
                        onCancelled
                    )
                } else {
                    policies[conflicts[index].entryName] = selectedPolicy
                    resolveArchiveConflictChoices(
                        archive,
                        destination,
                        password,
                        conflicts,
                        onCompleted,
                        onCancelled,
                        index + 1,
                        policies
                    )
                }
            },
            onCancelled = onCancelled
        )
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
        val archiveProgress = showArchiveProgress(
            "正在解压",
            "目标：${destination.absolutePath}",
            showCancel = true
        )
        val cancelledByUser = AtomicBoolean(false)
        val extractionJob = lifecycleScope.launch {
            try {
                val passwordChars = password?.toCharArray()
                val result = try {
                    withContext(Dispatchers.IO) {
                        val workerContext = currentCoroutineContext()
                        runCatching {
                            ArchiveManager.extractArchive(
                                archive = archive,
                                destination = destination,
                                password = passwordChars,
                                conflictPolicy = conflictPolicy,
                                conflictPolicies = conflictPolicies,
                                conflictsPrechecked = true,
                                onProgress = { phase, completed, total ->
                                    updateArchiveProgress(archiveProgress, phase, completed, total)
                                },
                                onConflict = onConflict,
                                checkCancelled = workerContext::ensureActive
                            )
                        }
                    }
                } finally {
                    passwordChars?.fill('\u0000')
                    archiveProgress.dialog.dismiss()
                }
                result.onSuccess { extracted ->
                    showExtractionCompleted(extracted)
                    onCompleted()
                }.onFailure { error ->
                    if (isArchiveOperationCancelled(error)) {
                        onCancelled()
                    } else if (isDestinationConflict(error)) {
                        prepareArchiveExtraction(archive, destination, password, onCompleted, onCancelled)
                    } else if (needsArchivePassword(archive, error, password != null)) {
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
            } catch (error: CancellationException) {
                if (!cancelledByUser.get()) throw error
                onCancelled()
            }
        }
        archiveProgress.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { button ->
            button.isEnabled = false
            archiveProgress.binding.tvProgressMessage.text = "正在取消..."
            archiveProgress.binding.progressBar.isIndeterminate = true
            archiveProgress.binding.tvProgressPercent.visibility = View.GONE
            cancelledByUser.set(true)
            extractionJob.cancel(CancellationException("用户取消解压"))
        }
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
    ) {
        val model = ArchiveConflictDialogModel(
            entryName = conflict.entryName,
            source = ArchiveConflictFileMetadata(
                sizeBytes = conflict.sourceSize.takeIf { it >= 0L },
                modifiedAtMillis = conflict.sourceModifiedTimeMillis.takeIf { it > 0L }
            ),
            existing = ArchiveConflictFileMetadata(
                sizeBytes = conflict.existingSize.takeIf { it >= 0L },
                modifiedAtMillis = conflict.existingModifiedTimeMillis.takeIf { it > 0L }
            )
        )
        val dialogBinding = DialogArchiveConflictBinding.inflate(layoutInflater)
        dialogBinding.tvConflictTitle.text = "覆盖文件？"
        dialogBinding.tvConflictFileName.text = if (conflict.archiveInternal) {
            "压缩包内重复条目：${model.entryName}"
        } else {
            "（${model.entryName}）已存在"
        }
        dialogBinding.tvConflictSourceSize.text =
            "大小：${ArchiveConflictDialogFormatter.size(model.source.sizeBytes)}"
        dialogBinding.tvConflictSourceModified.text =
            "最后修改：${ArchiveConflictDialogFormatter.modifiedTime(model.source.modifiedAtMillis)}"
        dialogBinding.tvConflictReplacementSize.text =
            "大小：${ArchiveConflictDialogFormatter.size(model.existing.sizeBytes)}"
        dialogBinding.tvConflictReplacementModified.text =
            "最后修改：${ArchiveConflictDialogFormatter.modifiedTime(model.existing.modifiedAtMillis)}"
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()
        fun choose(policy: ArchiveManager.ConflictPolicy) {
            val applyToAll = dialogBinding.cbApplyToAll.isChecked
            dialog.dismiss()
            onPolicySelected(policy, applyToAll)
        }
        dialogBinding.btnConflictCancel.setOnClickListener {
            dialog.dismiss()
            onCancelled()
        }
        dialogBinding.btnConflictRename.setOnClickListener {
            choose(ArchiveManager.ConflictPolicy.RENAME)
        }
        dialogBinding.btnConflictSkip.setOnClickListener {
            choose(ArchiveManager.ConflictPolicy.SKIP)
        }
        dialogBinding.btnConflictReplace.setOnClickListener {
            choose(ArchiveManager.ConflictPolicy.OVERWRITE)
        }
        dialog.show()
    }

    private fun showExtractionCompleted(result: ArchiveManager.ExtractResult) {
        val message = if (result.skippedCount > 0) {
            "解压完成：${result.entryCount} 项，跳过 ${result.skippedCount} 项"
        } else {
            "解压完成：${result.entryCount} 项"
        }
        showShortToast(message)
    }

    private fun isDestinationConflict(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .any { it is ArchiveManager.DestinationConflictException }

    private fun isArchiveOperationCancelled(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .any { it is CancellationException }

    private fun showArchivePasswordDialog(
        archive: File,
        onPassword: (String) -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        val passwordBinding = DialogArchivePasswordBinding.inflate(layoutInflater)
        passwordBinding.btnPasswordBook.setOnClickListener {
            showPasswordBook(passwordBinding.etArchivePassword)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("输入压缩包密码")
            .setMessage(archive.name)
            .setView(passwordBinding.root)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnCancelListener { onCancelled() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                onCancelled()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = passwordBinding.etArchivePassword.text?.toString().orEmpty()
                if (password.isEmpty()) {
                    passwordBinding.etArchivePassword.error = "请输入密码"
                } else {
                    dialog.dismiss()
                    onPassword(password)
                }
            }
        }
        dialog.show()
    }

    private fun showPasswordBook(target: android.widget.EditText) {
        val vault = ArchivePasswordVault(this)
        val passwords = runCatching(vault::getPasswords).getOrElse {
            showShortToast("无法读取密码本")
            return
        }
        val labels = passwords.mapIndexed { index, password ->
            "密码 ${index + 1}（${"•".repeat(password.length.coerceIn(1, 8))}）"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("密码本")
            .apply {
                if (labels.isEmpty()) {
                    setMessage("密码本为空，可保存当前输入的密码。")
                } else {
                    setItems(labels) { _, which ->
                        target.setText(passwords[which])
                        target.setSelection(target.text?.length ?: 0)
                    }
                }
            }
            .setPositiveButton("保存当前密码") { _, _ ->
                val password = target.text?.toString().orEmpty()
                if (password.isEmpty()) {
                    showShortToast("请先输入密码")
                } else {
                    runCatching { vault.savePassword(password) }
                        .onSuccess { showShortToast("密码已保存") }
                        .onFailure { showShortToast("密码保存失败") }
                }
            }
            .apply {
                if (passwords.isNotEmpty()) {
                    setNeutralButton("清空密码本") { _, _ ->
                        runCatching(vault::clear)
                        showShortToast("密码本已清空")
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showBlockingProgress(title: String, message: String): AlertDialog =
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .create()
            .also(AlertDialog::show)

    private fun showArchiveProgress(
        title: String,
        message: String,
        showCancel: Boolean = false
    ): ArchiveProgressUi {
        val progressBinding = DialogArchiveProgressBinding.inflate(layoutInflater)
        progressBinding.tvProgressMessage.text = message
        progressBinding.tvProgressLeading.visibility = View.GONE
        progressBinding.tvProgressProcessed.visibility = View.GONE
        progressBinding.progressBar.isIndeterminate = true
        progressBinding.tvProgressPercent.visibility = View.GONE
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(progressBinding.root)
            .setCancelable(false)
        if (showCancel) builder.setNegativeButton("取消", null)
        val dialog = builder.create()
            .also(AlertDialog::show)
        return ArchiveProgressUi(dialog, progressBinding)
    }

    private fun updateArchiveProgress(
        progress: ArchiveProgressUi,
        phase: ArchiveManager.ProgressPhase,
        completed: Long,
        total: Long
    ) {
        runOnUiThread {
            if (!progress.dialog.isShowing) return@runOnUiThread
            progress.binding.tvProgressMessage.text = when (phase) {
                ArchiveManager.ProgressPhase.SCANNING -> "正在检查压缩包..."
                ArchiveManager.ProgressPhase.EXTRACTING -> "正在解压..."
            }
            if (total > 0L) {
                val ratio = completed.coerceIn(0L, total).toDouble() / total.toDouble()
                val percent = (ratio * 100.0).toInt().coerceIn(0, 100)
                progress.binding.progressBar.isIndeterminate = false
                progress.binding.progressBar.max = 1000
                progress.binding.progressBar.progress = (ratio * 1000.0).toInt().coerceIn(0, 1000)
                progress.binding.tvProgressPercent.text = if (phase == ArchiveManager.ProgressPhase.SCANNING) {
                    "$percent% · 已检查 $completed / $total 项"
                } else {
                    "$percent% · 已处理 ${FileUtils.formatFileSize(completed)} / ${FileUtils.formatFileSize(total)}"
                }
                progress.binding.tvProgressPercent.visibility = View.VISIBLE
            } else {
                progress.binding.progressBar.isIndeterminate = true
                if (phase == ArchiveManager.ProgressPhase.EXTRACTING) {
                    progress.binding.tvProgressPercent.text = if (completed > 0L) {
                        "已处理 ${FileUtils.formatFileSize(completed)}"
                    } else {
                        "正在读取..."
                    }
                    progress.binding.tvProgressPercent.visibility = View.VISIBLE
                } else {
                    progress.binding.tvProgressPercent.visibility = View.GONE
                }
            }
        }
    }

    private fun needsArchivePassword(
        archive: File,
        error: Throwable,
        passwordAttempted: Boolean
    ): Boolean {
        if (error.message?.contains("无法清理") == true ||
            error.message?.contains("未能恢复") == true) return false
        val causes = generateSequence(error as Throwable?) { it.cause }
        return causes.any { cause ->
                cause is ArchivePasswordRequiredException ||
                cause.message?.contains("password", ignoreCase = true) == true ||
                cause.message?.contains("passphrase", ignoreCase = true) == true ||
                cause.message?.contains("decrypt", ignoreCase = true) == true ||
                (passwordAttempted && archive.extension.equals("7z", ignoreCase = true) &&
                    cause.message?.contains("checksum verification failed", ignoreCase = true) == true)
        }
    }

    private fun showOperationError(title: String, error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(error.message ?: "未知错误")
            .setPositiveButton("确定", null)
            .show()
    }

    private fun defaultArchiveName(sources: List<File>): String =
        if (sources.size == 1) {
            sources.first().let { source ->
                if (source.isDirectory) source.name else source.nameWithoutExtension.ifBlank { source.name }
            }
        } else {
            "archive-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}"
        }

    private fun stripArchiveExtension(name: String): String =
        listOf(".tar.bz2", ".tar.gz", ".tar.xz", ".zip", ".7z", ".tar")
            .firstOrNull { name.endsWith(it, ignoreCase = true) }
            ?.let { name.dropLast(it.length) }
            ?: name

    private fun isValidFileName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')

    private fun showSelectedProperties() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        if (files.size == 1) {
            showFilePropertiesDialog(files.first())
        } else {
            showMultipleFilePropertiesDialog(files)
        }
    }

    private fun directorySize(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun fileProperties(file: File): FilePropertiesInfo {
        val isAudioFile = FileUtils.isAudioFile(file)
        val isVideoFile = file.extension.lowercase() in VIDEO_EXTENSIONS
        val type = if (file.isDirectory) "文件夹" else file.extension.uppercase().ifBlank { "未知" } + " 文件"
        val size = if (file.isDirectory) directorySize(file) else file.length()
        val modified = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
        val mediaInfoTitle = when {
            isAudioFile -> "音频信息"
            isVideoFile -> "视频信息"
            else -> null
        }
        val mediaDetails = when {
            isAudioFile -> readAudioProperties(file)
            isVideoFile -> readVideoProperties(file)
            else -> emptyList()
        }
        return FilePropertiesInfo(
            name = file.name,
            path = file.absolutePath,
            type = type,
            size = FileUtils.formatFileSize(size),
            modifiedTime = modified,
            mediaInfoTitle = mediaInfoTitle,
            mediaDetails = mediaDetails
        )
    }

    private fun readAudioProperties(file: File): List<PropertyDetail> {
        val technicalDetails = mutableListOf<PropertyDetail>()
        val embeddedDetails = mutableListOf<PropertyDetail>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }
                ?.let { technicalDetails += PropertyDetail("时长", formatMediaDuration(it)) }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let { technicalDetails += PropertyDetail("比特率", "${it / 1000L} kbps") }

            fun addEmbeddedDetail(label: String, metadataKey: Int) {
                retriever.extractMetadata(metadataKey)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
                    ?.let { embeddedDetails += PropertyDetail(label, it) }
            }

            addEmbeddedDetail("歌曲名", MediaMetadataRetriever.METADATA_KEY_TITLE)
            addEmbeddedDetail("艺术家", MediaMetadataRetriever.METADATA_KEY_ARTIST)
            addEmbeddedDetail("专辑", MediaMetadataRetriever.METADATA_KEY_ALBUM)
            addEmbeddedDetail("专辑艺术家", MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            addEmbeddedDetail("作曲", MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            addEmbeddedDetail("流派", MediaMetadataRetriever.METADATA_KEY_GENRE)
            addEmbeddedDetail("年份", MediaMetadataRetriever.METADATA_KEY_YEAR)
        } catch (_: Exception) {
            // Unsupported or damaged media can still show its regular file properties.
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val audioFormat = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { format ->
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
            audioFormat?.let { format ->
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    technicalDetails += PropertyDetail("采样率", formatSampleRate(sampleRate))
                }
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val displayChannels = when (channels) {
                        1 -> "单声道"
                        2 -> "立体声"
                        else -> "${channels} 声道"
                    }
                    technicalDetails += PropertyDetail("声道", displayChannels)
                }
            }
        } catch (_: Exception) {
            // Stream details are optional metadata.
        } finally {
            runCatching { extractor.release() }
        }
        return technicalDetails + embeddedDetails
    }

    private fun readVideoProperties(file: File): List<PropertyDetail> {
        val technicalDetails = linkedMapOf<String, String>()
        val embeddedDetails = linkedMapOf<String, String>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }
                ?.let { technicalDetails["时长"] = formatMediaDuration(it) }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let { technicalDetails["比特率"] = "${it / 1000L} kbps" }

            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
            if (width != null && height != null && width > 0 && height > 0) {
                val displayWidth = if (rotation == 90 || rotation == 270) height else width
                val displayHeight = if (rotation == 90 || rotation == 270) width else height
                technicalDetails["分辨率"] = "$displayWidth × $displayHeight"
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { technicalDetails["帧率"] = formatFrameRate(it) }

            fun addEmbeddedDetail(label: String, metadataKey: Int) {
                retriever.extractMetadata(metadataKey)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
                    ?.let { embeddedDetails[label] = it }
            }

            addEmbeddedDetail("标题", MediaMetadataRetriever.METADATA_KEY_TITLE)
            addEmbeddedDetail("艺术家", MediaMetadataRetriever.METADATA_KEY_ARTIST)
            addEmbeddedDetail("作者", MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            addEmbeddedDetail("日期", MediaMetadataRetriever.METADATA_KEY_DATE)
        } catch (_: Exception) {
            // Unsupported or damaged media can still show its regular file properties.
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            formats.firstOrNull {
                it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }?.let { format ->
                format.getString(MediaFormat.KEY_MIME)?.let {
                    technicalDetails["视频编码"] = displayCodec(it)
                }
                if (!technicalDetails.containsKey("分辨率") &&
                    format.containsKey(MediaFormat.KEY_WIDTH) &&
                    format.containsKey(MediaFormat.KEY_HEIGHT)) {
                    technicalDetails["分辨率"] =
                        "${format.getInteger(MediaFormat.KEY_WIDTH)} × ${format.getInteger(MediaFormat.KEY_HEIGHT)}"
                }
                if (!technicalDetails.containsKey("帧率") && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    technicalDetails["帧率"] = formatFrameRate(format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble())
                }
                if (!technicalDetails.containsKey("比特率") && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    technicalDetails["比特率"] = "${format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000} kbps"
                }
            }
            formats.firstOrNull {
                it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }?.let { format ->
                format.getString(MediaFormat.KEY_MIME)?.let {
                    technicalDetails["音频编码"] = displayCodec(it)
                }
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    technicalDetails["音频采样率"] = formatSampleRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                }
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    technicalDetails["音频声道"] = when (channels) {
                        1 -> "单声道"
                        2 -> "立体声"
                        else -> "${channels} 声道"
                    }
                }
            }
        } catch (_: Exception) {
            // Stream details are optional metadata.
        } finally {
            runCatching { extractor.release() }
        }
        return technicalDetails.map { PropertyDetail(it.key, it.value) } +
            embeddedDetails.map { PropertyDetail(it.key, it.value) }
    }

    private fun formatMediaDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatSampleRate(sampleRate: Int): String =
        if (sampleRate % 1000 == 0) {
            "${sampleRate / 1000} kHz"
        } else {
            String.format(Locale.US, "%.1f kHz", sampleRate / 1000.0)
        }

    private fun formatFrameRate(frameRate: Double): String =
        if (frameRate % 1.0 == 0.0) {
            "${frameRate.toInt()} fps"
        } else {
            String.format(Locale.US, "%.2f fps", frameRate)
        }

    private fun displayCodec(mimeType: String): String = when (mimeType.lowercase()) {
        "video/avc" -> "H.264 / AVC"
        "video/hevc" -> "H.265 / HEVC"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mp4v-es" -> "MPEG-4 Video"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        else -> mimeType.substringAfter('/', mimeType)
    }

    private fun showFilePropertiesDialog(file: File) {
        val content = layoutInflater.inflate(R.layout.dialog_file_properties, null)
        content.findViewById<TextView>(R.id.tvPropertyName).text = file.name
        content.findViewById<TextView>(R.id.tvPropertyType).text =
            if (file.isDirectory) "文件夹" else file.extension.uppercase().ifBlank { "未知" } + " 文件"
        content.findViewById<TextView>(R.id.tvPropertySize).text = getString(R.string.loading)
        content.findViewById<TextView>(R.id.tvPropertyModifiedTime).text =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
        content.findViewById<TextView>(R.id.tvPropertyPath).apply {
            text = file.absolutePath
            contentDescription = "点击复制路径：${file.absolutePath}"
            setOnClickListener {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("文件路径", file.absolutePath))
                showShortToast("路径已复制")
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton("确定", null)
            .show()

        val loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fileProperties(file) } }
            if (!dialog.isShowing) return@launch
            content.findViewById<View>(R.id.propertyLoadingIndicator).visibility = View.GONE
            result.onSuccess { properties ->
                content.findViewById<TextView>(R.id.tvPropertyType).text = properties.type
                content.findViewById<TextView>(R.id.tvPropertySize).text = properties.size
                content.findViewById<TextView>(R.id.tvPropertyModifiedTime).text = properties.modifiedTime
                content.findViewById<TextView>(R.id.tvMediaInfoTitle).apply {
                    text = properties.mediaInfoTitle
                    visibility = if (properties.mediaInfoTitle == null) View.GONE else View.VISIBLE
                }
                content.findViewById<LinearLayout>(R.id.mediaPropertiesContainer).apply {
                    removeAllViews()
                    properties.mediaDetails.forEach { detail ->
                        val row = layoutInflater.inflate(R.layout.item_file_property, this, false)
                        row.findViewById<TextView>(R.id.tvPropertyLabel).text = detail.label
                        row.findViewById<TextView>(R.id.tvPropertyValue).text = detail.value
                        addView(row)
                    }
                    visibility = if (properties.mediaDetails.isEmpty()) View.GONE else View.VISIBLE
                }
            }.onFailure {
                content.findViewById<TextView>(R.id.tvPropertySize).text = getString(R.string.read_failed)
            }
        }
        dialog.setOnDismissListener { loadJob.cancel() }
    }

    private fun showMultipleFilePropertiesDialog(files: List<File>) {
        val content = layoutInflater.inflate(R.layout.dialog_multiple_file_properties, null)
        content.findViewById<TextView>(R.id.tvSelectedItemCount).text = "${files.size} 项"
        val totalSizeView = content.findViewById<TextView>(R.id.tvSelectedTotalSize).apply {
            text = getString(R.string.loading)
        }
        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton("确定", null)
            .show()

        val loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { files.sumOf { if (it.isDirectory) directorySize(it) else it.length() } }
            }
            if (!dialog.isShowing) return@launch
            totalSizeView.text = result.fold(
                onSuccess = FileUtils::formatFileSize,
                onFailure = { getString(R.string.read_failed) }
            )
        }
        dialog.setOnDismissListener { loadJob.cancel() }
    }

    private fun showShortToast(message: String) {
        com.subtitleedit.util.OverwritingToast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun openMediaFileForEdit(
        mediaFile: File,
        mediaType: EditorMediaType,
        audioOnlyFromVideo: Boolean = false
    ) {
        val possibleSubtitleFiles = FileUtils.getPossibleSubtitleFiles(mediaFile)

        when {
            possibleSubtitleFiles.size > 1 -> {
                showSubtitleFilePicker(
                    mediaFile,
                    mediaType,
                    possibleSubtitleFiles,
                    audioOnlyFromVideo
                )
            }
            else -> {
                openMediaWithSubtitle(
                    mediaFile,
                    mediaType,
                    possibleSubtitleFiles.firstOrNull(),
                    audioOnlyFromVideo
                )
            }
        }
    }

    private fun updateCompressionProgress(
        progress: ArchiveProgressUi,
        compressionProgress: ArchiveManager.CompressionProgress
    ) {
        runOnUiThread {
            if (!progress.dialog.isShowing) return@runOnUiThread
            progress.binding.tvProgressLeading.text =
                "已生成 ${FileUtils.formatFileSize(compressionProgress.generatedBytes)}"
            progress.binding.tvProgressLeading.visibility = View.VISIBLE
            if (compressionProgress.sourceBytes > 0L) {
                progress.binding.tvProgressProcessed.text =
                    "已处理 ${FileUtils.formatFileSize(compressionProgress.processedBytes)} / " +
                        FileUtils.formatFileSize(compressionProgress.sourceBytes)
                progress.binding.tvProgressProcessed.visibility = View.VISIBLE
            } else {
                progress.binding.tvProgressProcessed.visibility = View.GONE
            }
            progress.binding.tvProgressMessage.text = compressionProgress.currentFileName?.let {
                it
            } ?: progress.binding.tvProgressMessage.text
            val percent = compressionProgress.percent
            if (percent != null) {
                progress.binding.progressBar.isIndeterminate = false
                progress.binding.progressBar.max = 100
                progress.binding.progressBar.progress = percent
                progress.binding.tvProgressPercent.text = "$percent%"
                progress.binding.tvProgressPercent.visibility = View.VISIBLE
            } else {
                progress.binding.progressBar.isIndeterminate = true
                progress.binding.tvProgressPercent.visibility = View.GONE
            }
        }
    }

    private fun updateFileCopyProgress(
        progress: ArchiveProgressUi,
        message: String,
        completed: Long,
        total: Long
    ) {
        runOnUiThread {
            if (!progress.dialog.isShowing) return@runOnUiThread
            progress.binding.tvProgressMessage.text = message
            if (total > 0L) {
                val ratio = completed.coerceIn(0L, total).toDouble() / total.toDouble()
                progress.binding.progressBar.isIndeterminate = false
                progress.binding.progressBar.max = 1000
                progress.binding.progressBar.progress = (ratio * 1000.0).toInt().coerceIn(0, 1000)
                progress.binding.tvProgressPercent.text =
                    "${FileUtils.formatFileSize(completed)} / ${FileUtils.formatFileSize(total)}"
                progress.binding.tvProgressPercent.visibility = View.VISIBLE
            } else {
                progress.binding.progressBar.isIndeterminate = true
                progress.binding.tvProgressPercent.visibility = View.GONE
            }
        }
    }

    private fun showVideoOpenModePicker(videoFile: File) {
        val options = arrayOf("加载视频", "仅加载音频")
        AlertDialog.Builder(this)
            .setTitle("打开视频文件")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openMediaFileForEdit(videoFile, EditorMediaType.VIDEO)
                    else -> openMediaFileForEdit(
                        videoFile,
                        EditorMediaType.AUDIO,
                        audioOnlyFromVideo = true
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 当存在多个同名字幕文件时，弹出选择对话框
     */
    private fun showSubtitleFilePicker(
        mediaFile: File,
        mediaType: EditorMediaType,
        subtitleFiles: List<File>,
        audioOnlyFromVideo: Boolean
    ) {
        val fileNames = subtitleFiles.map { file ->
            file.name + "  (" + FileUtils.formatFileSize(file.length()) + ")"
        }.toTypedArray()

        // 用自定义标题同时显示标题和提示信息（setItems 与 setView/setMessage 互斥）
        val customTitle = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(8, 8, 8, 0)
            addView(android.widget.TextView(context).apply {
                text = "选择字幕文件"
                textSize = 19f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(4, 0, 4, 6)
            })
            addView(android.widget.TextView(context).apply {
                val typeLabel = if (mediaType == EditorMediaType.VIDEO) "视频" else "音频"
                text = "$typeLabel「${mediaFile.name}」同目录下存在多个字幕文件，请选择要打开的文件："
                textSize = 14f
                setPadding(4, 0, 4, 0)
            })
        }

        AlertDialog.Builder(this)
            .setCustomTitle(customTitle)
            .setItems(fileNames) { _, which ->
                openMediaWithSubtitle(
                    mediaFile,
                    mediaType,
                    subtitleFiles[which],
                    audioOnlyFromVideo
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

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
        if (loadDirectory(directory)) {
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
        if (loadDirectory(target)) {
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
            val parent = directoryHistory.removeAt(directoryHistory.size - 1)
            loadDirectory(parent)
        } else {
            currentDirectory?.parentFile?.let { parent ->
                if (parent.exists() && parent.canRead()) {
                    loadDirectory(parent)
                }
            }
        }
    }
    
    private fun openFileForEdit(file: File) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_FILE_PATH, file.absolutePath)
        startActivity(intent)
    }
    
    override fun onBackPressed() {
        if (stateModel.selectedTopLevelItem != R.id.nav_directory) {
            val handled = (supportFragmentManager.findFragmentById(R.id.fragmentContainer) as? TopLevelBackHandler)
                ?.handleTopLevelBack() == true
            if (!handled) super.onBackPressed()
        } else if (pendingFileOperation != null) {
            if (!navigateDestinationUp()) {
                cancelDestinationSelection()
            }
        } else if (selectedPaths.isNotEmpty()) {
            exitSelectionMode()
        } else if (directoryHistory.isNotEmpty()) {
            goUpLevel()
        } else {
            super.onBackPressed()
        }
    }

}
