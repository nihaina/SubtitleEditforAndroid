package com.subtitleedit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.subtitleedit.ui.FavoritesScreen
import com.subtitleedit.ui.ToolDestination
import com.subtitleedit.ui.ToolsScreen
import com.subtitleedit.ui.settings.SettingsCacheItem
import com.subtitleedit.ui.settings.SettingsPageState
import com.subtitleedit.ui.settings.SettingsScreen
import com.subtitleedit.util.AppThemeMode
import com.subtitleedit.util.DirectoryDisplayPath
import com.subtitleedit.util.DraftManager
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.ModelDirectoryManager
import com.subtitleedit.util.ModelDirectoryMigration
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** State retained by the main Compose navigation while a top-level page is hidden. */
internal class MainTopLevelPagesState {
    var favoriteDirectories by mutableStateOf(emptyList<File>())
    var pendingFavoriteRemoval by mutableStateOf<File?>(null)
    var currentDraftFolder by mutableStateOf("")
    var drafts by mutableStateOf(emptyList<DraftsActivity.DraftItem>())
    var draftDialog by mutableStateOf<DraftsDialogState?>(null)
    var draftToExport by mutableStateOf<DraftsActivity.DraftItem?>(null)
    var settingsPage by mutableStateOf(SettingsPageState())
}

@Composable
internal fun MainTopLevelPages(
    selectedPage: Int,
    activePage: Int,
    refreshVersion: Int,
    selectionVersion: Int,
    state: MainTopLevelPagesState,
    onOpenDirectory: (File) -> Unit,
    onToolbarChanged: (String, Boolean, (() -> Unit)?) -> Unit
) {
    val isActive = selectedPage == activePage
    val context = LocalContext.current
    val resources = LocalResources.current
    val preferences = context.getSharedPreferences(FAVORITES_PREFERENCES_NAME, Context.MODE_PRIVATE)
    val settings = SettingsManager.getInstance(context)
    val settingsScope = rememberCoroutineScope()
    var previousSelectedPage by remember { mutableIntStateOf(R.id.nav_directory) }
    var previousSelectionVersion by remember { mutableIntStateOf(selectionVersion) }

    fun switchSoftwareDirectory(targetRoot: File, migrateModels: Boolean) {
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
                }
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    "软件目录切换失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
            state.settingsPage = loadSettingsPage(context, settings)
        }
    }

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFavoriteDirectory(context, preferences, state, uri)
    }
    val softwareDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
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
    val draftExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        exportDraft(context, state, uri)
    }

    fun loadFavoriteDirectories() {
        state.favoriteDirectories = preferences.getStringSet(FAVORITES_PATHS_KEY, emptySet())
            ?.toSet()
            .orEmpty()
            .map(::File)
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
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

    LaunchedEffect(selectedPage, activePage, refreshVersion, selectionVersion, resources) {
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
            R.id.nav_favorites -> loadFavoriteDirectories()
            R.id.nav_drafts -> {
                loadDrafts()
                updateDraftToolbar()
            }
            R.id.nav_settings -> {
                val pending = state.settingsPage
                val loaded = loadSettingsPage(context, settings)
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

    BackHandler(
        enabled = isActive && selectedPage == R.id.nav_drafts && state.currentDraftFolder.isNotEmpty()
    ) {
        goToDraftFolder("")
    }

    val pageKey = if (selectedPage == R.id.nav_tools) 0 else selectionVersion
    key(selectedPage, pageKey) {
    when (selectedPage) {
        R.id.nav_favorites -> FavoritesScreen(
            directories = state.favoriteDirectories,
            pendingRemoval = state.pendingFavoriteRemoval,
            onAddDirectory = { directoryPicker.launch(null) },
            onOpenDirectory = onOpenDirectory,
            onRequestRemoval = { state.pendingFavoriteRemoval = it },
            onConfirmRemoval = {
                val directory = state.pendingFavoriteRemoval ?: return@FavoritesScreen
                val paths = preferences.getStringSet(FAVORITES_PATHS_KEY, emptySet())
                    ?.toMutableSet() ?: mutableSetOf()
                paths.remove(directory.absolutePath)
                preferences.edit().putStringSet(FAVORITES_PATHS_KEY, paths).apply()
                state.pendingFavoriteRemoval = null
                loadFavoriteDirectories()
            },
            onDismissRemoval = { state.pendingFavoriteRemoval = null }
        )

        R.id.nav_drafts -> DraftsPage(
            currentFolder = state.currentDraftFolder,
            drafts = state.drafts,
            fromEditor = false,
            dialogState = state.draftDialog,
            showTopBar = false,
            onBack = { goToDraftFolder("") },
            onBackToRoot = { goToDraftFolder("") },
            onItemClick = { item ->
                if (item.isFolder) {
                    goToDraftFolder(item.folderName)
                } else {
                    state.draftDialog = DraftsDialogState.Preview(
                        item,
                        DraftManager.readDraft(context, item.folderName, item.fileName)
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
            onLoadDraft = {},
            onDeleteDraft = { item ->
                state.draftDialog = null
                // DraftsFragment always rebuilt its adapter after the delete attempt and did
                // not show the standalone activity's success toast.
                DraftManager.deleteDraft(context, item.folderName, item.fileName)
                loadDrafts()
            },
            onDeleteFolder = { item ->
                state.draftDialog = null
                DraftManager.deleteDraftFolder(context, item.folderName)
                if (state.currentDraftFolder == item.folderName) goToDraftFolder("")
                else loadDrafts()
            }
        )

        R.id.nav_tools -> ToolsScreen(onOpen = { destination -> openTool(context, destination) })

        R.id.nav_settings -> SettingsScreen(
            state = state.settingsPage,
            encodings = FileUtils.SUPPORTED_ENCODINGS,
            showTopBar = false,
            // The legacy SettingsFragment removed the selected cache group
            // immediately. The standalone SettingsActivity kept its
            // confirmation dialog, so this host must opt out explicitly.
            confirmCacheClear = false,
            onBack = {},
            onEncodingSelected = { encoding ->
                if (encoding.isAuto) settings.setDefaultEncodingAutomatic()
                else settings.setDefaultEncoding(encoding.charset)
                state.settingsPage = loadSettingsPage(context, settings)
            },
            onThemeSelected = { mode ->
                settings.setThemeMode(mode)
                AppThemeMode.apply(context, mode)
                state.settingsPage = loadSettingsPage(context, settings)
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
            onOpenAiSettings = { context.openActivity(AiSettingsActivity::class.java) },
            onOpenModelManagement = { context.openActivity(ModelManagementActivity::class.java) },
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
            onCacheClear = { item -> clearSettingsCache(context, settings, state, item, false) },
            onEmptyCacheClear = { item -> clearSettingsCache(context, settings, state, item, false) }
        )
    }
    }
}

private fun addFavoriteDirectory(
    context: Context,
    preferences: android.content.SharedPreferences,
    state: MainTopLevelPagesState,
    uri: Uri
) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    val directory = File(DirectoryDisplayPath.fromUri(context, uri))
    if (!directory.isDirectory || !directory.canRead()) {
        showTopLevelToast(context, context.getString(R.string.favorite_directory_access_failed))
        return
    }

    val paths = preferences.getStringSet(FAVORITES_PATHS_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
    if (!paths.add(directory.absolutePath)) {
        showTopLevelToast(context, context.getString(R.string.favorite_directory_already_added))
        return
    }
    preferences.edit().putStringSet(FAVORITES_PATHS_KEY, paths).apply()
    state.favoriteDirectories = paths.map(::File).sortedBy { it.name.lowercase(Locale.getDefault()) }
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
        ToolDestination.AUTO_TIMESTAMP -> AutoTimestampActivity::class.java
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

private fun loadSettingsPage(context: Context, settings: SettingsManager): SettingsPageState {
    val encodings = FileUtils.SUPPORTED_ENCODINGS
    val encoding = if (settings.isDefaultEncodingAutomatic()) {
        SettingsManager.AUTO_ENCODING
    } else {
        val currentEncoding = settings.getDefaultEncoding()
        encodings.firstOrNull { it.charset == currentEncoding && !it.isAuto }
            ?.id ?: currentEncoding.name()
    }
    val themeMode = settings.getThemeMode()
    val cacheItems = settingsCacheItems(context)
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
        cacheItems = cacheItems
    )
}

private fun settingsCacheItems(context: Context): List<SettingsCacheItem> {
    val waveform = cacheFilesInWaveformDirectory(context) { it.extension == "wave" }
    val spectrogram = cacheFilesInWaveformDirectory(context) {
        it.extension == "png" && it.name.contains(".spec_")
    }
    val audio = context.cacheDir.walkTopDown().filter {
        it.isFile && it.name.startsWith("quick_transcribe_") && it.name.endsWith("_16k.wav")
    }.toList()
    return listOf(
        SettingsCacheItem(
            "waveform",
            context.getString(R.string.settings_cache_waveform_label),
            waveform.sumOf(File::length),
            context.getString(R.string.settings_cache_waveform_empty),
            context.getString(
                R.string.settings_cache_waveform_confirm,
                formatTopLevelSize(waveform.sumOf(File::length))
            )
        ),
        SettingsCacheItem(
            "spectrogram",
            context.getString(R.string.settings_cache_spectrogram_label),
            spectrogram.sumOf(File::length),
            context.getString(R.string.settings_cache_spectrogram_empty),
            context.getString(
                R.string.settings_cache_spectrogram_confirm,
                formatTopLevelSize(spectrogram.sumOf(File::length))
            )
        ),
        SettingsCacheItem(
            "quick_transcribe",
            context.getString(R.string.settings_cache_quick_transcribe_label),
            audio.sumOf(File::length),
            context.getString(R.string.settings_cache_quick_transcribe_empty),
            context.getString(
                R.string.settings_cache_quick_transcribe_confirm,
                formatTopLevelSize(audio.sumOf(File::length))
            )
        )
    )
}

private fun clearSettingsCache(
    context: Context,
    settings: SettingsManager,
    state: MainTopLevelPagesState,
    item: SettingsCacheItem,
    includeCacheLabel: Boolean = true
) {
    val matches: (File) -> Boolean = when (item.key) {
        "waveform" -> { file -> file.extension == "wave" }
        "spectrogram" -> { file -> file.extension == "png" && file.name.contains(".spec_") }
        "quick_transcribe" -> { file ->
            file.name.startsWith("quick_transcribe_") && file.name.endsWith("_16k.wav")
        }
        else -> return
    }
    val files = if (item.key == "quick_transcribe") {
        context.cacheDir.walkTopDown().filter { it.isFile && matches(it) }.toList()
    } else {
        cacheFilesInWaveformDirectory(context, matches)
    }
    val count = files.count(File::delete)
    val message = if (includeCacheLabel) {
        val label = item.label.removeSuffix("缓存")
        context.getString(R.string.settings_cache_cleared, count, label)
    } else {
        context.getString(R.string.settings_cache_cleared_generic, count)
    }
    showTopLevelToast(context, message)
    state.settingsPage = loadSettingsPage(context, settings)
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

private const val FAVORITES_PREFERENCES_NAME = "favorite_directories"
private const val FAVORITES_PATHS_KEY = "paths"
