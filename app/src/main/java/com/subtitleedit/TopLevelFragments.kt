package com.subtitleedit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import com.subtitleedit.filepicker.AppFilePickerContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.subtitleedit.ui.FavoritesScreen
import com.subtitleedit.ui.ListeningStatsScreen
import com.subtitleedit.ui.ListeningStatsPageState
import com.subtitleedit.ui.MediaLibraryScreen
import com.subtitleedit.ui.MediaLibraryPageState
import com.subtitleedit.ui.MediaAlbumScreen
import com.subtitleedit.ui.MediaAlbumPageState
import com.subtitleedit.ui.MediaLibraryScanCoordinator
import com.subtitleedit.ui.collectRetainedPageState
import com.subtitleedit.library.MediaLibraryFileObserver
import com.subtitleedit.ui.showLibraryError
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.player.PlayerPreferences
import com.subtitleedit.player.PlayerSubtitleSettingsActivity
import com.subtitleedit.ui.ToolDestination
import com.subtitleedit.ui.ToolsScreen
import com.subtitleedit.ui.settings.SettingsCacheItem
import com.subtitleedit.ui.settings.SettingsPageState
import com.subtitleedit.ui.settings.SettingsScreen
import com.subtitleedit.ui.settings.PlayerUsbSettingsActivity
import com.subtitleedit.util.AppThemeMode
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.DraftManager
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.FavoriteDirectoryStore
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SettingsBackupManager
import com.subtitleedit.util.ModelDirectoryManager
import com.subtitleedit.util.ModelDirectoryMigration
import com.subtitleedit.util.SubtitleFontManager
import com.subtitleedit.util.SettingsCacheSizes
import com.subtitleedit.util.readSettingsCacheSizes
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State retained by the main Compose navigation while a top-level page is hidden. */
internal class MainTopLevelPagesState {
    var favoriteDirectories by mutableStateOf(emptyList<File>())
    var mediaLibraryDirectoryPaths by mutableStateOf(emptySet<String>())
    val mediaLibraryDirectories: List<File>
        get() = favoriteDirectories.filter { it.absolutePath in mediaLibraryDirectoryPaths }
    var pendingFavoriteRemoval by mutableStateOf<File?>(null)
    var currentDraftFolder by mutableStateOf("")
    var drafts by mutableStateOf(emptyList<DraftsActivity.DraftItem>())
    var draftDialog by mutableStateOf<DraftsDialogState?>(null)
    var draftToExport by mutableStateOf<DraftsActivity.DraftItem?>(null)
    var settingsPage by mutableStateOf(SettingsPageState())
    var settingsCacheRefreshVersion by mutableIntStateOf(0)
    val listeningStatsPage = ListeningStatsPageState()
    val mediaAlbumPage = MediaAlbumPageState()
    val mediaLibraryPage = MediaLibraryPageState()
    internal var retainedSaveableStateHolder: SaveableStateHolder? = null
}

@Composable
internal fun MainTopLevelPages(
    selectedPage: Int,
    activePage: Int,
    refreshVersion: Int,
    selectionVersion: Int,
    state: MainTopLevelPagesState,
    playerModeEnabled: Boolean,
    viewMode: FileBrowserViewMode,
    onOpenDirectory: (File) -> Unit,
    onToolbarChanged: (String, Boolean, (() -> Unit)?) -> Unit,
    saveableStateHolder: SaveableStateHolder? = null,
    onOpenSubtitle: (File) -> Unit = {}
) {
    val isActive = selectedPage == activePage
    val activeNow by rememberUpdatedState(isActive)
    val toolbarCallback by rememberUpdatedState(onToolbarChanged)
    val context = LocalContext.current
    val resources = LocalResources.current
    val directoryStore = remember(context) { FavoriteDirectoryStore(context) }
    val settings = SettingsManager.getInstance(context)
    val settingsScope = rememberCoroutineScope()
    var previousSelectedPage by remember { mutableIntStateOf(R.id.nav_directory) }
    var previousSelectionVersion by remember { mutableIntStateOf(selectionVersion) }
    val candidateStateHolder = rememberSaveableStateHolder()
    val pageStateHolder = saveableStateHolder ?: state.retainedSaveableStateHolder
        ?: candidateStateHolder.also { state.retainedSaveableStateHolder = it }

    fun loadFavoriteDirectories() = refreshFavoriteDirectoryState(directoryStore, state)

    fun refreshSettingsPage() {
        state.settingsPage = loadSettingsPage(context, settings, state.settingsPage.cacheItems)
    }

    fun clearCache(item: SettingsCacheItem) {
        settingsScope.launch {
            val message = withContext(Dispatchers.IO) { clearSettingsCache(context, item, false) }
            showTopLevelToast(context, message)
            state.settingsCacheRefreshVersion++
        }
    }

    fun removeFavoriteDirectory(directory: File) {
        val wasMediaLibrary = directory.absolutePath in directoryStore.load().mediaLibraryPaths
        directoryStore.remove(directory)
        loadFavoriteDirectories()
        if (wasMediaLibrary) MediaLibraryFileObserver.removeDirectory(context, directory, state.mediaLibraryDirectories)
    }

    fun switchSoftwareDirectory(targetRoot: File, migrateModels: Boolean) {
        val currentRoot = settings.getSoftwareDirectory().absoluteFile
        val currentModels = settings.getModelDirectory()
        state.settingsPage = state.settingsPage.copy(isMigratingSoftwareDirectory = migrateModels)
        settingsScope.launch {
            runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val targetModels = File(targetRoot, ModelDirectoryManager.MODELS_DIRECTORY_NAME)
                    if (migrateModels && currentModels.canonicalFile != targetModels.canonicalFile) {
                        ModelDirectoryMigration.migrate(currentModels, targetModels)
                        settings.rewriteModelDirectoryPaths(currentModels, targetModels)
                        currentModels.parentFile?.let {
                            settings.rewriteModelDirectoryPaths(it, targetRoot)
                        }
                    }
                    settings.setSoftwareDirectory(targetRoot.path)
                    settings.clearPersistedOutputDirectories()
                    targetRoot.mkdirs()
                    targetModels.mkdirs()
                    listOf("Convert", "Translate", "Output", "TranscriptMatch").forEach {
                        File(targetRoot, it).mkdirs()
                    }
                    SubtitleFontManager.synchronizeSoftwareDirectory(currentRoot, targetRoot)
                }
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    "软件目录切换失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
            refreshSettingsPage()
            state.settingsCacheRefreshVersion++
        }
    }

    val directoryPicker = rememberLauncherForActivityResult(AppFilePickerContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFavoriteDirectory(context, directoryStore, state, uri)
    }
    var libraryScanPickerPending by remember { mutableStateOf(false) }
    val libraryScanRunning by MediaLibraryScanCoordinator.scanning.collectRetainedPageState(isActive)
    val libraryScanPicker = rememberLauncherForActivityResult(AppFilePickerContracts.OpenDocumentTree()) { uri ->
        libraryScanPickerPending = false
        if (uri != null) {
            try {
                com.subtitleedit.util.PickedUriAccess.retainPermission(
                    context, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val dir = File(DirectoryDisplayPath.fromUri(context, uri))
                if (!dir.isDirectory || !dir.canRead()) {
                    showTopLevelToast(context, context.getString(R.string.favorite_directory_access_failed))
                    return@rememberLauncherForActivityResult
                }
                directoryStore.addMediaLibrary(dir)
                loadFavoriteDirectories()
                MediaLibraryScanCoordinator.scan(context, listOf(dir))
            } catch (error: Exception) {
                showLibraryError(context, error)
            }
        }
    }
    val softwareDirectoryPicker = rememberLauncherForActivityResult(AppFilePickerContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val target = File(DirectoryDisplayPath.fromUri(context, uri)).absoluteFile
            if (target.canonicalFile != settings.getSoftwareDirectory().absoluteFile.canonicalFile) {
                val modelCount = ModelDirectoryMigration.findRecognizedModels(settings.getModelDirectory()).size
                if (modelCount == 0) {
                    switchSoftwareDirectory(target, migrateModels = false)
                } else {
                    state.settingsPage = state.settingsPage.copy(
                        pendingSoftwareDirectory = target.path,
                        pendingSoftwareDirectoryModelCount = modelCount
                    )
                }
            }
        }
    }
    val draftExporter = rememberLauncherForActivityResult(AppFilePickerContracts.CreateDocument("*/*")) { uri ->
        exportDraft(context, state, uri)
    }

    val mediaLibraryDirectories = state.mediaLibraryDirectories
    LaunchedEffect(mediaLibraryDirectories, playerModeEnabled, isActive) {
        if (playerModeEnabled && isActive) MediaLibraryFileObserver.syncRoots(context, mediaLibraryDirectories)
    }

    val backupExporter = rememberLauncherForActivityResult(
        AppFilePickerContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(SettingsBackupManager.export(context).toByteArray(StandardCharsets.UTF_8))
                } ?: error("无法打开备份文件")
            }.onSuccess { showTopLevelToast(context, context.getString(R.string.settings_backup_saved)) }
                .onFailure { showTopLevelToast(context, "配置备份失败：${it.message ?: "未知错误"}") }
        }
    }
    val backupImporter = rememberLauncherForActivityResult(
        AppFilePickerContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(StandardCharsets.UTF_8)
                } ?: error("无法读取备份文件")
                SettingsBackupManager.restore(context, json)
            }.onSuccess { result ->
                refreshSettingsPage()
                state.settingsCacheRefreshVersion++
                loadFavoriteDirectories()
                AppThemeMode.apply(context, settings.getThemeMode())
                showTopLevelToast(
                    context,
                    if (result.skippedCount > 0) {
                        context.getString(R.string.settings_restore_success_skipped, result.skippedCount)
                    } else {
                        context.getString(R.string.settings_restore_success)
                    }
                )
            }.onFailure {
                showTopLevelToast(context, context.getString(R.string.settings_backup_invalid))
            }
        }
    }

    fun loadDrafts() {
        state.drafts = if (state.currentDraftFolder.isEmpty()) {
            DraftManager.getAllDraftFolders(context).map {
                DraftsActivity.DraftItem(it.name, "", it.name, "", true)
            }
        } else {
            DraftManager.getDraftsInFolder(context, state.currentDraftFolder).map {
                DraftsActivity.DraftItem(
                    state.currentDraftFolder,
                    it.name,
                    it.name,
                    DraftManager.getFormattedDate(it),
                    false
                )
            }
        }
    }

    fun updateDraftToolbar() {
        onToolbarChanged(
            if (state.currentDraftFolder.isEmpty()) resources.getString(R.string.drafts)
            else state.currentDraftFolder,
            state.currentDraftFolder.isNotEmpty(),
            if (state.currentDraftFolder.isNotEmpty()) {
                { state.currentDraftFolder = ""; state.draftDialog = null; loadDrafts(); updateDraftToolbar() }
            } else null
        )
    }

    fun goToDraftFolder(folder: String) {
        state.currentDraftFolder = folder
        loadDrafts()
        updateDraftToolbar()
    }

    LaunchedEffect(selectedPage, activePage, refreshVersion, selectionVersion, resources, playerModeEnabled) {
        if (!isActive) return@LaunchedEffect
        if (selectionVersion != previousSelectionVersion) {
            state.pendingFavoriteRemoval = null
            state.draftDialog = null
            state.draftToExport = null
        }
        if (selectedPage == R.id.nav_drafts &&
            (previousSelectedPage != R.id.nav_drafts || selectionVersion != previousSelectionVersion)
        ) {
            // The old navigation replaced DraftsFragment on every tab entry.
            state.currentDraftFolder = ""
            state.draftDialog = null
            state.draftToExport = null
        }
        previousSelectedPage = selectedPage
        previousSelectionVersion = selectionVersion
        when (selectedPage) {
            R.id.nav_library -> loadFavoriteDirectories()
            R.id.nav_tools -> if (playerModeEnabled) loadFavoriteDirectories()
            R.id.nav_favorites -> loadFavoriteDirectories()
            R.id.nav_drafts -> {
                loadDrafts()
                updateDraftToolbar()
            }
            R.id.nav_settings -> {
                val pending = state.settingsPage
                val loaded = loadSettingsPage(context, settings, pending.cacheItems)
                // MainActivity refreshes top-level pages when the folder picker
                // returns. Keep a directory choice that is waiting for the
                // migration confirmation across that reload.
                state.settingsPage = loaded.copy(
                    pendingSoftwareDirectory = pending.pendingSoftwareDirectory
                        ?.takeUnless { pending.isMigratingSoftwareDirectory },
                    pendingSoftwareDirectoryModelCount = pending.pendingSoftwareDirectoryModelCount
                        .takeUnless { pending.isMigratingSoftwareDirectory } ?: 0,
                    isMigratingSoftwareDirectory = pending.isMigratingSoftwareDirectory
                )
            }
        }
    }

    LaunchedEffect(selectedPage, activePage, refreshVersion, resources, state.settingsCacheRefreshVersion, playerModeEnabled) {
        if (!isActive || selectedPage != R.id.nav_settings || playerModeEnabled) return@LaunchedEffect
        val cacheItems = withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            settingsCacheItems(context, readSettingsCacheSizes(context.cacheDir) { job.ensureActive() })
        }
        // Only cache fields come from the worker; settings changed during the scan stay current.
        state.settingsPage = state.settingsPage.copy(
            cacheItems = cacheItems,
            cacheSize = cacheItems.sumOf(SettingsCacheItem::sizeBytes).takeIf { it > 0L }
                ?.let(::formatTopLevelSize).orEmpty()
        )
    }

    BackHandler(
        enabled = isActive && selectedPage == R.id.nav_drafts && state.currentDraftFolder.isNotEmpty()
    ) {
        goToDraftFolder("")
    }

    pageStateHolder.SaveableStateProvider("$playerModeEnabled:$selectedPage") {
    when (selectedPage) {
        R.id.nav_library -> MediaLibraryScreen(
            refreshVersion = refreshVersion,
            selectionVersion = selectionVersion,
            pageState = state.mediaLibraryPage,
            onToolbarChanged = { title, hasBack, back ->
                if (activeNow) toolbarCallback(title, hasBack, back)
            },
            onRequestScanDirectory = {
                if (!libraryScanPickerPending && !libraryScanRunning) {
                    libraryScanPickerPending = true
                    try {
                        libraryScanPicker.launch(null)
                    } catch (error: Exception) {
                        libraryScanPickerPending = false
                        showLibraryError(context, error)
                    }
                }
            },
            mediaLibraryDirectories = mediaLibraryDirectories,
            isActive = isActive,
            scanPickerPending = libraryScanPickerPending,
            onRemoveFavoriteDirectory = ::removeFavoriteDirectory
        )

        R.id.nav_stats -> ListeningStatsScreen(
            refreshVersion = refreshVersion,
            pageState = state.listeningStatsPage,
            isActive = isActive,
        )

        R.id.nav_favorites -> FavoritesScreen(
            directories = state.favoriteDirectories,
            mediaLibraryDirectoryPaths = state.mediaLibraryDirectoryPaths,
            metadataRefreshKey = refreshVersion,
            viewMode = viewMode,
            pendingRemoval = state.pendingFavoriteRemoval,
            onAddDirectory = { directoryPicker.launch(null) },
            onOpenDirectory = onOpenDirectory,
            onRequestRemoval = { state.pendingFavoriteRemoval = it },
            onConfirmRemoval = {
                val directory = state.pendingFavoriteRemoval ?: return@FavoritesScreen
                removeFavoriteDirectory(directory)
                state.pendingFavoriteRemoval = null
            },
            onDismissRemoval = { state.pendingFavoriteRemoval = null }
        )

        R.id.nav_drafts -> DraftsPage(
            currentFolder = state.currentDraftFolder,
            metadataRefreshKey = refreshVersion,
            drafts = state.drafts,
            dialogState = state.draftDialog,
            showTopBar = false,
            viewMode = viewMode,
            onBack = { goToDraftFolder("") },
            onBackToRoot = { goToDraftFolder("") },
            onItemClick = { item ->
                if (item.isFolder) {
                    goToDraftFolder(item.folderName)
                } else {
                    context.startActivity(
                        DraftPreviewActivity.createIntent(context, item.folderName, item.fileName)
                    )
                }
            },
            onLongPress = { item ->
                if (!item.isFolder) state.draftDialog = DraftsDialogState.Actions(item)
            },
            onDeleteClick = { item ->
                state.draftDialog = if (item.isFolder) DraftsDialogState.DeleteFolder(item)
                else DraftsDialogState.DeleteDraft(item)
            },
            onRequestDelete = { item ->
                state.draftDialog = if (item.isFolder) DraftsDialogState.DeleteFolder(item)
                else DraftsDialogState.DeleteDraft(item)
            },
            onDismissDialog = { state.draftDialog = null },
            onCopyDraft = { item -> copyDraft(context, item) },
            onExportDraft = { item ->
                state.draftToExport = item
                draftExporter.launch(item.fileName)
            },
            onDeleteDraft = { item ->
                state.draftDialog = null
                // DraftsFragment always rebuilt its adapter after the delete attempt and did
                // not show the standalone activity's success toast.
                DraftManager.deleteDraft(context, item.folderName, item.fileName)
                if (state.currentDraftFolder == item.folderName &&
                    DraftManager.getDraftsInFolder(context, state.currentDraftFolder).isEmpty()
                ) {
                    state.currentDraftFolder = ""
                    updateDraftToolbar()
                }
                loadDrafts()
            },
            onDeleteFolder = { item ->
                state.draftDialog = null
                DraftManager.deleteDraftFolder(context, item.folderName)
                if (state.currentDraftFolder == item.folderName) goToDraftFolder("")
                else loadDrafts()
            }
        )

        R.id.nav_tools -> if (playerModeEnabled) {
            MediaAlbumScreen(
                refreshVersion = refreshVersion,
                mediaLibraryDirectories = mediaLibraryDirectories,
                isActive = isActive,
                pageState = state.mediaAlbumPage,
                onOpenSubtitle = onOpenSubtitle
            )
        } else {
            ToolsScreen(
                onOpen = { destination -> openTool(context, destination) },
                useMaterial3Visuals = true
            )
        }

        R.id.nav_settings -> SettingsScreen(
            state = state.settingsPage,
            encodings = FileUtils.SUPPORTED_ENCODINGS,
            showTopBar = false,
            useMaterial3Visuals = true,
            // The legacy SettingsFragment removed the selected cache group
            // immediately. The standalone SettingsActivity kept its
            // confirmation dialog, so this host must opt out explicitly.
            confirmCacheClear = false,
            onBack = {},
            onEncodingSelected = { encoding ->
                if (encoding.isAuto) settings.setDefaultEncodingAutomatic()
                else settings.setDefaultEncoding(encoding.charset)
                refreshSettingsPage()
            },
            onThemeSelected = { mode ->
                settings.setThemeMode(mode)
                AppThemeMode.apply(context, mode)
                refreshSettingsPage()
            },
            onCheckUpdatesChanged = { enabled ->
                state.settingsPage = state.settingsPage.copy(checkUpdatesOnStartup = enabled)
                settings.setCheckUpdatesOnStartup(enabled)
            },
            onPreserveDirectoriesChanged = { enabled ->
                state.settingsPage = state.settingsPage.copy(preserveOutputDirectories = enabled)
                settings.setOutputDirectoryPersistenceEnabled(enabled)
            },
            onLoopSelectedChanged = { enabled ->
                state.settingsPage = state.settingsPage.copy(loopSelectedSubtitle = enabled)
                settings.setLoopSelectedSubtitleEnabled(enabled)
            },
            onSelectPlayingChanged = { enabled ->
                state.settingsPage = state.settingsPage.copy(selectPlayingSubtitle = enabled)
                settings.setSelectPlayingSubtitleEnabled(enabled)
            },
            onHardwareDecodingChanged = { enabled ->
                state.settingsPage = state.settingsPage.copy(hardwareDecodingEnabled = enabled)
                settings.setHardwareDecodingEnabled(enabled)
            },
            onAllowConcurrentPlaybackChanged = { enabled ->
                state.settingsPage = state.settingsPage.copy(allowConcurrentPlayback = enabled)
                PlayerPreferences.getInstance(context).allowConcurrentPlayback = enabled
            },
            onOpenPlayerUsbSettings = { context.openActivity(PlayerUsbSettingsActivity::class.java) },
            onOpenAiSettings = { context.openActivity(AiSettingsActivity::class.java) },
            onOpenModelManagement = { context.openActivity(ModelManagementActivity::class.java) },
            onOpenPageTranslation = { context.openActivity(PageTranslationSettingsActivity::class.java) },
            onOpenTtsSettings = { context.openActivity(TtsSettingsActivity::class.java) },
            onOpenLogs = { context.openActivity(LogActivity::class.java) },
            onOpenAbout = { context.openActivity(AboutActivity::class.java) },
            onSelectSoftwareDirectory = { softwareDirectoryPicker.launch(null) },
            onConfirmSoftwareDirectory = {
                val targetPath = state.settingsPage.pendingSoftwareDirectory
                if (targetPath != null) {
                    state.settingsPage = state.settingsPage.copy(
                        pendingSoftwareDirectory = null,
                        pendingSoftwareDirectoryModelCount = 0,
                        isMigratingSoftwareDirectory = true
                    )
                    switchSoftwareDirectory(File(targetPath).absoluteFile, migrateModels = true)
                }
            },
            onCancelSoftwareDirectory = {
                state.settingsPage = state.settingsPage.copy(
                    pendingSoftwareDirectory = null,
                    pendingSoftwareDirectoryModelCount = 0
                )
            },
            // SettingsFragment's legacy cache dialog deleted the selected group
            // immediately and always used the generic result message.
            onCacheClear = ::clearCache,
            onEmptyCacheClear = ::clearCache,
            onBackup = { backupExporter.launch(context.getString(R.string.app_name) + ".json") },
            onRestore = { backupImporter.launch(arrayOf("application/json", "text/plain", "*/*")) },
            onOpenPlayerSubtitleSettings = if (playerModeEnabled) {
                { context.openActivity(PlayerSubtitleSettingsActivity::class.java) }
            } else null
        )
    }
    }
}

private fun refreshFavoriteDirectoryState(store: FavoriteDirectoryStore, state: MainTopLevelPagesState) {
    val snapshot = store.load()
    state.favoriteDirectories = snapshot.directories
    state.mediaLibraryDirectoryPaths = snapshot.mediaLibraryPaths
}

private fun addFavoriteDirectory(
    context: Context,
    store: FavoriteDirectoryStore,
    state: MainTopLevelPagesState,
    uri: Uri
) {
    runCatching {
        com.subtitleedit.util.PickedUriAccess.retainPermission(context,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    val directory = File(DirectoryDisplayPath.fromUri(context, uri))
    if (!directory.isDirectory || !directory.canRead()) {
        showTopLevelToast(context, context.getString(R.string.favorite_directory_access_failed))
        return
    }

    if (!store.addCommon(directory)) {
        showTopLevelToast(context, context.getString(R.string.favorite_directory_already_added))
        return
    }
    refreshFavoriteDirectoryState(store, state)
}

private fun exportDraft(context: Context, state: MainTopLevelPagesState, uri: Uri?) {
    val draft = state.draftToExport
    if (uri != null && draft != null) runCatching {
        context.contentResolver.openOutputStream(uri)?.use {
            it.write(DraftManager.readDraft(context, draft.folderName, draft.fileName).toByteArray())
        }
    }.onSuccess {
        showTopLevelToast(context, context.getString(R.string.drafts_export_success))
    }.onFailure {
        showTopLevelToast(context, context.getString(R.string.drafts_export_failed, it.message.orEmpty()))
    }
    state.draftToExport = null
}

private fun copyDraft(context: Context, item: DraftsActivity.DraftItem) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText("draft", DraftManager.readDraft(context, item.folderName, item.fileName))
    )
    showTopLevelToast(context, context.getString(R.string.drafts_copied_to_clipboard))
}

private fun openTool(context: Context, destination: ToolDestination) {
    val activity = when (destination) {
        ToolDestination.BATCH_CONVERT -> BatchConvertActivity::class.java
        ToolDestination.SUBTITLE_FORMAT -> SubtitleFormatSelectActivity::class.java
        ToolDestination.AUTO_TRANSLATE -> AutoTranslateActivity::class.java
        ToolDestination.VOCAL_SEPARATION -> VocalSeparationActivity::class.java
        ToolDestination.SPEECH_TO_SUBTITLE -> SpeechToSubtitleActivity::class.java
        ToolDestination.TRANSCRIPT_MATCH -> TranscriptMatchActivity::class.java
        ToolDestination.MEDIA_CONVERT -> MediaConvertActivity::class.java
    }
    context.openActivity(activity)
}

private fun Context.openActivity(activity: Class<*>) {
    val intent = Intent(this, activity)
    if (this !is android.app.Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

private fun loadSettingsPage(
    context: Context,
    settings: SettingsManager,
    cacheItems: List<SettingsCacheItem>
): SettingsPageState {
    val encodings = FileUtils.SUPPORTED_ENCODINGS
    val encoding = if (settings.isDefaultEncodingAutomatic()) {
        SettingsManager.AUTO_ENCODING
    } else {
        val currentEncoding = settings.getDefaultEncoding()
        encodings.firstOrNull { it.charset == currentEncoding && !it.isAuto }
            ?.id ?: currentEncoding.name()
    }
    val themeMode = settings.getThemeMode()
    val cacheSize = cacheItems.sumOf(SettingsCacheItem::sizeBytes)
    return SettingsPageState(
        encoding = encoding,
        themeMode = themeMode,
        themeLabel = when (themeMode) {
            SettingsManager.THEME_LIGHT -> context.getString(R.string.settings_theme_light)
            SettingsManager.THEME_DARK -> context.getString(R.string.settings_theme_dark)
            else -> context.getString(R.string.settings_theme_system)
        },
        softwareDirectory = settings.getSoftwareDirectoryPath(),
        cacheSize = if (cacheSize > 0) formatTopLevelSize(cacheSize) else "",
        checkUpdatesOnStartup = settings.shouldCheckUpdatesOnStartup(),
        preserveOutputDirectories = settings.isOutputDirectoryPersistenceEnabled(),
        loopSelectedSubtitle = settings.isLoopSelectedSubtitleEnabled(),
        selectPlayingSubtitle = settings.isSelectPlayingSubtitleEnabled(),
        hardwareDecodingEnabled = settings.isHardwareDecodingEnabled(),
        allowConcurrentPlayback = PlayerPreferences.getInstance(context).allowConcurrentPlayback,
        usbExclusiveEnabled = PlayerPreferences.getInstance(context).usbExclusiveEnabled,
        cacheItems = cacheItems
    )
}

private fun settingsCacheItems(context: Context, sizes: SettingsCacheSizes): List<SettingsCacheItem> {
    return listOf(
        SettingsCacheItem(
            "waveform",
            context.getString(R.string.settings_cache_waveform_label),
            sizes.waveform,
            context.getString(R.string.settings_cache_waveform_empty),
            context.getString(
                R.string.settings_cache_waveform_confirm,
                formatTopLevelSize(sizes.waveform)
            )
        ),
        SettingsCacheItem(
            "spectrogram",
            context.getString(R.string.settings_cache_spectrogram_label),
            sizes.spectrogram,
            context.getString(R.string.settings_cache_spectrogram_empty),
            context.getString(
                R.string.settings_cache_spectrogram_confirm,
                formatTopLevelSize(sizes.spectrogram)
            )
        ),
        SettingsCacheItem(
            "quick_transcribe",
            context.getString(R.string.settings_cache_quick_transcribe_label),
            sizes.quickTranscribe,
            context.getString(R.string.settings_cache_quick_transcribe_empty),
            context.getString(
                R.string.settings_cache_quick_transcribe_confirm,
                formatTopLevelSize(sizes.quickTranscribe)
            )
        )
    )
}

private fun clearSettingsCache(
    context: Context,
    item: SettingsCacheItem,
    includeCacheLabel: Boolean = true
): String {
    val matches: (File) -> Boolean = when (item.key) {
        "waveform" -> { file -> file.extension == "wave" }
        "spectrogram" -> { file -> file.extension == "png" && file.name.contains(".spec_") }
        "quick_transcribe" -> { file ->
            file.name.startsWith("quick_transcribe_") && file.name.endsWith("_16k.wav")
        }
        else -> return ""
    }
    val files = if (item.key == "quick_transcribe") {
        context.cacheDir.walkTopDown().filter { it.isFile && matches(it) }.toList()
    } else {
        cacheFilesInWaveformDirectory(context, matches)
    }
    val count = files.count(File::delete)
    return if (includeCacheLabel) {
        val label = item.label.removeSuffix("缓存")
        context.getString(R.string.settings_cache_cleared, count, label)
    } else {
        context.getString(R.string.settings_cache_cleared_generic, count)
    }
}

private fun cacheFilesInWaveformDirectory(context: Context, matches: (File) -> Boolean): List<File> {
    val directory = File(context.cacheDir, "waveform")
    if (!directory.exists()) return emptyList()
    return directory.walkTopDown().filter { it.isFile && matches(it) }.toList()
}

private fun formatTopLevelSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(Locale.getDefault(), bytes / 1024.0)} KB"
    else -> "${"%.2f".format(Locale.getDefault(), bytes / 1024.0 / 1024.0)} MB"
}

private fun showTopLevelToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
