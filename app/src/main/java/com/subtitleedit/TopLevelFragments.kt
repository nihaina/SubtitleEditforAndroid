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
import androidx.compose.runtime.mutableStateOf
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
import java.io.File
import java.util.Locale

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
    refreshVersion: Int,
    state: MainTopLevelPagesState,
    onOpenDirectory: (File) -> Unit,
    onToolbarChanged: (String, Boolean, (() -> Unit)?) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val preferences = context.getSharedPreferences(FAVORITES_PREFERENCES_NAME, Context.MODE_PRIVATE)
    val settings = SettingsManager.getInstance(context)

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addFavoriteDirectory(context, preferences, state, uri)
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

    LaunchedEffect(selectedPage, refreshVersion, resources) {
        when (selectedPage) {
            R.id.nav_favorites -> loadFavoriteDirectories()
            R.id.nav_drafts -> {
                loadDrafts()
                updateDraftToolbar()
            }
            R.id.nav_settings -> state.settingsPage = loadSettingsPage(context, settings)
        }
    }

    BackHandler(
        enabled = selectedPage == R.id.nav_drafts && state.currentDraftFolder.isNotEmpty()
    ) {
        goToDraftFolder("")
    }

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
            onLongPress = { item -> state.draftDialog = DraftsDialogState.Actions(item) },
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
                if (DraftManager.deleteDraft(context, item.folderName, item.fileName)) {
                    showTopLevelToast(context, resources.getString(R.string.draft_deleted))
                    loadDrafts()
                }
            },
            onDeleteFolder = { item ->
                state.draftDialog = null
                if (DraftManager.deleteDraftFolder(context, item.folderName)) {
                    showTopLevelToast(context, resources.getString(R.string.draft_deleted))
                    if (state.currentDraftFolder == item.folderName) goToDraftFolder("")
                    else loadDrafts()
                }
            }
        )

        R.id.nav_tools -> ToolsScreen(onOpen = { destination -> openTool(context, destination) })

        R.id.nav_settings -> SettingsScreen(
            state = state.settingsPage,
            encodings = FileUtils.SUPPORTED_ENCODINGS,
            showTopBar = false,
            onBack = {},
            onEncodingSelected = { encoding ->
                settings.setDefaultEncoding(encoding.charset)
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
            onOpenAiSettings = { context.openActivity(AiSettingsActivity::class.java) },
            onOpenModelManagement = { context.openActivity(ModelManagementActivity::class.java) },
            onOpenTtsSettings = { context.openActivity(TtsSettingsActivity::class.java) },
            onOpenLogs = { context.openActivity(LogActivity::class.java) },
            onOpenAbout = { context.openActivity(AboutActivity::class.java) },
            onCacheClear = { item -> clearSettingsCache(context, settings, state, item) },
            onEmptyCacheClear = { showTopLevelToast(context, it.emptyMessage) }
        )
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
        showTopLevelToast(context, "导出成功")
    }.onFailure {
        showTopLevelToast(context, "导出失败：${it.message}")
    }
    state.draftToExport = null
}

private fun copyDraft(context: Context, item: DraftsActivity.DraftItem) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText("draft", DraftManager.readDraft(context, item.folderName, item.fileName))
    )
    showTopLevelToast(context, "已复制到剪贴板")
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
    startActivity(Intent(this, activity))
}

private fun loadSettingsPage(context: Context, settings: SettingsManager): SettingsPageState {
    val encodings = FileUtils.SUPPORTED_ENCODINGS
    val currentEncoding = settings.getDefaultEncoding()
    val encoding = encodings.firstOrNull { it.charset == currentEncoding }
        ?.displayName ?: currentEncoding.displayName()
    val themeMode = settings.getThemeMode()
    val cacheItems = settingsCacheItems(context)
    val cacheSize = cacheItems.sumOf(SettingsCacheItem::sizeBytes)
    return SettingsPageState(
        encoding = encoding,
        themeMode = themeMode,
        themeLabel = when (themeMode) {
            SettingsManager.THEME_LIGHT -> "亮色"
            SettingsManager.THEME_DARK -> "深色"
            else -> "跟随系统"
        },
        cacheSize = if (cacheSize > 0) formatTopLevelSize(cacheSize) else "",
        checkUpdatesOnStartup = settings.shouldCheckUpdatesOnStartup(),
        preserveOutputDirectories = settings.isOutputDirectoryPersistenceEnabled(),
        loopSelectedSubtitle = settings.isLoopSelectedSubtitleEnabled(),
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
            "waveform", "波形图缓存", waveform.sumOf(File::length), "暂无波形图缓存可清除",
            "将删除 ${formatTopLevelSize(waveform.sumOf(File::length))} 的波形图缓存，下次打开音频时会重新生成。\n确定继续？"
        ),
        SettingsCacheItem(
            "spectrogram", "频谱图缓存", spectrogram.sumOf(File::length), "暂无频谱图缓存可清除",
            "将删除 ${formatTopLevelSize(spectrogram.sumOf(File::length))} 的频谱图缓存，下次查看频谱图时会重新生成。\n确定继续？"
        ),
        SettingsCacheItem(
            "quick_transcribe", "快速转录音频缓存", audio.sumOf(File::length), "暂无快速转录音频缓存可清除",
            "将删除 ${formatTopLevelSize(audio.sumOf(File::length))} 的快速转录音频缓存，下次快速转录时会重新生成。\n确定继续？"
        )
    )
}

private fun clearSettingsCache(
    context: Context,
    settings: SettingsManager,
    state: MainTopLevelPagesState,
    item: SettingsCacheItem
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
    showTopLevelToast(context, "已清除 $count 个${item.label.removeSuffix("缓存")}缓存文件")
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
