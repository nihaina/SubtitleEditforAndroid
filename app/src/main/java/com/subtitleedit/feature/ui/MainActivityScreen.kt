package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.FileOperation
import com.subtitleedit.DirectoryScrollPosition
import com.subtitleedit.MainTopLevelPages
import com.subtitleedit.MainTopLevelPagesState
import com.subtitleedit.ui.MediaCategoryRevealRequest
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.util.BatchRename
import com.subtitleedit.util.FileOperationUiPolicy
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.model.BrowserFileMetadata
import java.io.File

private val EditorBottomNavigationItems = listOf(
    Triple(R.id.nav_directory, R.drawable.ic_nav_folder, R.string.nav_directory),
    Triple(R.id.nav_favorites, R.drawable.ic_favorite, R.string.nav_favorites),
    Triple(R.id.nav_drafts, R.drawable.ic_nav_draft, R.string.drafts),
    Triple(R.id.nav_tools, R.drawable.ic_tools, R.string.menu_main_title_01),
    Triple(R.id.nav_settings, R.drawable.ic_settings, R.string.menu_main_title_02)
)

private val PlayerBottomNavigationItems = listOf(
    Triple(R.id.nav_directory, R.drawable.ic_nav_folder, R.string.nav_directory),
    Triple(R.id.nav_library, R.drawable.ic_nav_library, R.string.nav_library),
    Triple(R.id.nav_tools, R.drawable.ic_nav_album, R.string.media_categories_navigation),
    Triple(R.id.nav_stats, R.drawable.ic_nav_stats, R.string.nav_stats),
    Triple(R.id.nav_settings, R.drawable.ic_settings, R.string.menu_main_title_02)
)

internal fun bottomNavigationItems(playerModeEnabled: Boolean) =
    if (playerModeEnabled) PlayerBottomNavigationItems else EditorBottomNavigationItems

internal data class MainDirectoryScrollRequest(
    val generation: Long,
    val directoryPath: String,
    val savedPosition: DirectoryScrollPosition?
)

internal data class MainDirectoryRevealRequest(val path: String, val token: Long)

internal data class MainActivityScreenState(
    val selectedTopLevelItem: Int = R.id.nav_directory,
    val toolbarTitle: String = "",
    val toolbarHasBack: Boolean = false,
    val isDirectoryLoading: Boolean = false,
    val isDirectoryNavigationPending: Boolean = false,
    val isSearchInProgress: Boolean = false,
    val showDirectoryEmptyState: Boolean = false,
    val directoryEmptyStateMessage: Int = R.string.no_files,
    val currentDirectory: File? = null,
    val canonicalDirectoryPath: String? = null,
    val files: List<File> = emptyList(),
    val fileMetadata: Map<String, BrowserFileMetadata> = emptyMap(),
    val scrollRequest: MainDirectoryScrollRequest? = null,
    val directoryRevealRequest: MainDirectoryRevealRequest? = null,
    val relativePathRoot: File? = null,
    val selectedPaths: Set<String> = emptySet(),
    val pendingOperation: FileOperation? = null,
    val isSearchExpanded: Boolean = false,
    val searchQuery: String = "",
    val sortField: FileSortField = FileSortField.NAME,
    val sortDirection: FileSortDirection = FileSortDirection.ASCENDING,
    val viewMode: FileBrowserViewMode = FileBrowserViewMode.DETAILED,
    val canConvertSelected: Boolean = false,
    val canCompressSelected: Boolean = true,
    val canEditTagsSelected: Boolean = false,
    val canClearCacheSelected: Boolean = false,
    val canProcessMediaSelected: Boolean = false,
    val playerModeEnabled: Boolean = false
)

internal enum class MainCreateItemKind { FOLDER, FILE }
internal enum class MainCreateItemField { NAME, EXTENSION }
internal data class MainCreateItemError(val field: MainCreateItemField, val message: String)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MainActivityScreen(
    state: MainActivityScreenState,
    topLevelPageRefreshVersion: Int,
    topLevelPageSelectionVersion: Int,
    onDirectoryScrollPosition: (() -> DirectoryScrollPosition) -> Unit,
    onTopLevelPageSelected: (Int) -> Unit,
    onOpenDirectoryFromFavorites: (File) -> Unit,
    onUpdateTopLevelToolbar: (String, Boolean, (() -> Unit)?) -> Unit,
    onSearchRequested: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onCreateItem: (MainCreateItemKind, String, String) -> MainCreateItemError?,
    onSortFieldChanged: (FileSortField) -> Unit,
    onSortDirectionChanged: (FileSortDirection) -> Unit,
    onViewModeChanged: (FileBrowserViewMode) -> Unit,
    onTogglePlayerMode: () -> Unit,
    onOnlineClicked: () -> Unit,
    onOpenFileManagementSettings: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectRange: () -> Unit,
    onToolbarBack: () -> Unit,
    onDirectoryBreadcrumbClick: (File) -> Boolean,
    onFileClick: (File) -> Unit,
    onFileLongClick: (File) -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onConvertSelection: () -> Unit,
    onCompressSelection: () -> Unit,
    onEditTagsSelection: () -> Unit,
    onClearCacheSelection: () -> Unit,
    onShowSelectionProperties: () -> Unit,
    onConfirmDestination: () -> Unit,
    onCancelDestination: () -> Unit,
    subtitleConversion: SubtitleConversionDialogUi?,
    subtitleConversionResult: SubtitleConversionResultUi?,
    archiveCreation: ArchiveCreationDialogUi?,
    onDismissSubtitleConversion: (Int) -> Unit,
    onConvertSubtitle: (Int, Int, Boolean) -> Boolean,
    onDismissConversionResult: () -> Unit,
    onDismissArchiveCreation: () -> Unit,
    onCreateArchive: (ArchiveCreateSubmission) -> String?,
    onLoadArchivePasswords: () -> List<String>?,
    onSaveArchivePassword: (String) -> Unit,
    onClearArchivePasswordBook: () -> Unit,
    mainDialog: MainActivityDialogUi?,
    onDismissMainDialog: () -> Unit,
    onPermissionDialogConfirm: () -> Unit,
    onRenameConfirm: (String) -> Unit,
    batchRename: BatchRenameDialogUi?,
    onDismissBatchRename: () -> Unit,
    onBatchRenameConfirm: (BatchRename.Options) -> Unit,
    onBatchRenameOrderChanged: (List<File>) -> Unit,
    onDeleteBatchRenameHistory: (String) -> Unit,
    onDeleteConfirm: () -> Unit,
    onCacheItemSelected: (com.subtitleedit.ui.settings.SettingsCacheItem) -> Unit,
    onCacheClearConfirm: (com.subtitleedit.ui.settings.SettingsCacheItem) -> Unit,
    onProcessMediaSelection: () -> Unit = {},
    miniPlayerSlot: @Composable () -> Unit = {},
    categoryRevealRequest: MediaCategoryRevealRequest? = null,
    onDirectoryRevealFinished: (Long) -> Unit = {},
    imagePreviewFile: File? = null,
    onImagePreviewPageChanged: (File) -> Unit = {},
    onCloseImagePreview: () -> Unit = {},
    onShareImagePreview: (File) -> Unit = {},
    onDeleteImagePreview: (File) -> Unit = {},
    onShowImagePreviewInfo: (File) -> Unit = {}
) {
    val directoryListState = rememberDirectoryListState(state)
    val directoryGridState = rememberDirectoryGridState(state)
    val appliedDirectoryScrollGeneration = remember { mutableStateOf<Long?>(null) }
    val topLevelPagesState = remember { MainTopLevelPagesState() }
    val topLevelPageSaveableState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    LaunchedEffect(categoryRevealRequest?.token) {
        categoryRevealRequest?.let { topLevelPagesState.mediaAlbumPage.revealRequest = it }
    }
    val topLevelOrder = remember(state.playerModeEnabled) { bottomNavigationItems(state.playerModeEnabled).map { it.first } }
    val topLevelMotion = rememberMainTopLevelMotion(topLevelOrder, state.selectedTopLevelItem)
    var createDialogKind by remember { mutableStateOf<MainCreateItemKind?>(null) }
    var createName by remember { mutableStateOf("") }
    var createExtension by remember { mutableStateOf("txt") }
    var createNameError by remember { mutableStateOf<String?>(null) }
    var createExtensionError by remember { mutableStateOf<String?>(null) }
    val isDirectoryPage = state.selectedTopLevelItem == R.id.nav_directory
    val previewFile = imagePreviewFile.takeIf { isDirectoryPage && state.pendingOperation == null }
    val isPreviewingImage = previewFile != null
    val previewDirectory = previewFile?.parent
    val previewFiles = remember(previewDirectory, state.files, state.fileMetadata) {
        if (previewDirectory == null) emptyList() else state.files.filter { file ->
            FileTypePolicy.isImage(file) && file.parent == previewDirectory &&
                state.fileMetadata[file.absolutePath]?.isRegularFile == true
        }
    }
    var previewControlsVisible by rememberSaveable(isPreviewingImage) { mutableStateOf(true) }
    var previewQuarterTurns by rememberSaveable(previewFile?.absolutePath) { mutableIntStateOf(0) }
    // Selection actions belong to directoryContent in the legacy layout. The
    // selection state can survive a top-level tab switch, but its toolbar and
    // bottom action row stay hidden until the directory tab is visible again.
    val isSelectionActive = isDirectoryPage &&
        (state.selectedPaths.isNotEmpty() || state.pendingOperation != null)
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val createNameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(createDialogKind) {
        if (createDialogKind != null) {
            createName = ""
            createExtension = "txt"
            createNameError = null
            createExtensionError = null
        }
        if (createDialogKind == MainCreateItemKind.FILE) {
            createNameFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    LaunchedEffect(state.isSearchExpanded, isSelectionActive, isDirectoryPage, isPreviewingImage) {
        if (state.isSearchExpanded && !isSelectionActive && isDirectoryPage && !isPreviewingImage) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    val bottomBar: @Composable () -> Unit = {
        MainBottomBar(
            state = state.bottomBarState(),
            previewControlsVisible = previewControlsVisible,
            previewActions = if (previewFile == null) null else {
                {
                    DirectoryImagePreviewActions(
                        onShare = { onShareImagePreview(previewFile) },
                        onRotate = { previewQuarterTurns = (previewQuarterTurns + 1) % 4 },
                        onDelete = { onDeleteImagePreview(previewFile) },
                        onShowInfo = { onShowImagePreviewInfo(previewFile) }
                    )
                }
            },
            indicatorFrame = topLevelMotion.indicatorFrame,
            onTopLevelPageSelected = onTopLevelPageSelected,
            onCopy = onCopy,
            onMove = onMove,
            onRename = onRename,
            onDelete = onDelete,
            onConvertSelection = onConvertSelection,
            onCompressSelection = onCompressSelection,
            onEditTagsSelection = onEditTagsSelection,
            onClearCacheSelection = onClearCacheSelection,
            onShowSelectionProperties = onShowSelectionProperties,
            onConfirmDestination = onConfirmDestination,
            onCancelDestination = onCancelDestination,
            onProcessMediaSelection = onProcessMediaSelection,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.systemBarsIgnoringVisibility))
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (previewFile != null) {
            DirectoryImagePreview(
                file = previewFile,
                files = previewFiles.takeIf { previewFile in it } ?: listOf(previewFile),
                isSelected = previewFile.absolutePath in state.selectedPaths,
                selectionTitle = state.toolbarTitle.takeIf { isSelectionActive },
                controlsVisible = previewControlsVisible,
                quarterTurns = previewQuarterTurns,
                onPageChanged = onImagePreviewPageChanged,
                onNavigationClick = if (isSelectionActive) onToolbarBack else onCloseImagePreview,
                onToggleSelection = { onFileLongClick(previewFile) },
                onToggleControls = { previewControlsVisible = !previewControlsVisible },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                bottomBar = bottomBar
            )
        } else MainTopLevelPager(
            state = state,
            pageOrder = topLevelOrder,
            onSelected = onTopLevelPageSelected,
            motion = topLevelMotion,
            userScrollEnabled = createDialogKind == null && mainDialog == null && batchRename == null,
            modifier = Modifier.weight(1f).fillMaxWidth().background(settingsPageBackground())
        ) { pageState, pageActive ->
            val state = pageState
            val isDirectoryPage = state.selectedTopLevelItem == R.id.nav_directory
            val isMediaCategoriesPage = state.playerModeEnabled && state.selectedTopLevelItem == R.id.nav_tools
            val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
            Column(Modifier.fillMaxSize()) {
                if (!isMediaCategoriesPage) MainPageToolbar(
                    state = state.toolbarState(),
                    scrollBehavior = scrollBehavior,
                    searchFocusRequester = searchFocusRequester,
                    onSearchChanged = onSearchChanged,
                    onToolbarBack = onToolbarBack,
                    onTopLevelPageSelected = onTopLevelPageSelected,
                    onSelectAll = onSelectAll,
                    onSelectRange = onSelectRange,
                    onOnlineClicked = onOnlineClicked,
                    onTogglePlayerMode = onTogglePlayerMode,
                    onViewModeChanged = onViewModeChanged,
                    onSortFieldChanged = onSortFieldChanged,
                    onSortDirectionChanged = onSortDirectionChanged,
                    onCreateRequested = { kind ->
                        createDialogKind = kind
                        createName = ""
                        createExtension = "txt"
                        createNameError = null
                        createExtensionError = null
                    },
                    onSearchRequested = onSearchRequested,
                    onOpenFileManagementSettings = onOpenFileManagementSettings,
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(settingsPageBackground())
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                ) {
                    if (isDirectoryPage) {
                        DirectoryBrowserContent(
                            state = state,
                            onScrollPosition = onDirectoryScrollPosition,
                            onDirectoryBreadcrumbClick = onDirectoryBreadcrumbClick,
                            onFileClick = onFileClick,
                            onFileLongClick = onFileLongClick,
                            listState = directoryListState,
                            gridState = directoryGridState,
                            appliedScrollGeneration = appliedDirectoryScrollGeneration,
                            isActive = pageActive,
                            onRevealFinished = onDirectoryRevealFinished
                        )
                    } else {
                        MainTopLevelPages(
                            selectedPage = state.selectedTopLevelItem,
                            activePage = if (pageActive) state.selectedTopLevelItem else -1,
                            refreshVersion = topLevelPageRefreshVersion,
                            selectionVersion = topLevelPageSelectionVersion,
                            state = topLevelPagesState,
                            playerModeEnabled = state.playerModeEnabled,
                            viewMode = state.viewMode,
                            onOpenDirectory = onOpenDirectoryFromFavorites,
                            onToolbarChanged = onUpdateTopLevelToolbar,
                            saveableStateHolder = topLevelPageSaveableState
                        )
                    }
                }
            }
        }

        if (!isPreviewingImage) {
            com.subtitleedit.ui.LocalMediaSelection.current?.let { selection ->
                com.subtitleedit.ui.MediaSelectionPanel(selection)
            }
            miniPlayerSlot()
            bottomBar()
        }
    }

    createDialogKind?.let { kind ->
        AppDialog(
            onDismissRequest = { createDialogKind = null },
            title = { Text(stringResource(if (kind == MainCreateItemKind.FOLDER) R.string.directory_create_folder_title else R.string.directory_create_file_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = createName,
                        onValueChange = { createName = it; createNameError = null },
                        modifier = Modifier.fillMaxWidth().focusRequester(createNameFocusRequester),
                        placeholder = { Text(stringResource(if (kind == MainCreateItemKind.FOLDER) R.string.directory_create_folder_name_hint else R.string.file_name)) },
                        singleLine = true,
                        isError = createNameError != null,
                        supportingText = { createNameError?.let { Text(it) } }
                    )
                    if (kind == MainCreateItemKind.FILE) {
                        OutlinedTextField(
                            value = createExtension,
                            onValueChange = { createExtension = it; createExtensionError = null },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.batch_rename_extension)) },
                            singleLine = true,
                            isError = createExtensionError != null,
                            supportingText = { createExtensionError?.let { Text(it) } }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val error = onCreateItem(kind, createName, createExtension)
                    if (error == null) {
                        createDialogKind = null
                    } else if (error.field == MainCreateItemField.NAME) {
                        createNameError = error.message
                    } else {
                        createExtensionError = error.message
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { createDialogKind = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    MainFileOperationDialogs(
        subtitleConversion = subtitleConversion,
        subtitleConversionResult = subtitleConversionResult,
        archiveCreation = archiveCreation,
        onDismissSubtitleConversion = onDismissSubtitleConversion,
        onConvertSubtitle = onConvertSubtitle,
        onDismissConversionResult = onDismissConversionResult,
        onDismissArchiveCreation = onDismissArchiveCreation,
        onCreateArchive = onCreateArchive,
        onLoadArchivePasswords = onLoadArchivePasswords,
        onSaveArchivePassword = onSaveArchivePassword,
        onClearArchivePasswordBook = onClearArchivePasswordBook,
        mainDialog = mainDialog,
        onDismissMainDialog = onDismissMainDialog,
        onPermissionDialogConfirm = onPermissionDialogConfirm,
        onRenameConfirm = onRenameConfirm,
        batchRename = batchRename,
        onDismissBatchRename = onDismissBatchRename,
        onBatchRenameConfirm = onBatchRenameConfirm,
        onBatchRenameOrderChanged = onBatchRenameOrderChanged,
        onDeleteBatchRenameHistory = onDeleteBatchRenameHistory,
        onDeleteConfirm = onDeleteConfirm,
        onCacheItemSelected = onCacheItemSelected,
        onCacheClearConfirm = onCacheClearConfirm
    )
}

@Composable
internal fun SelectionActions(
    operation: FileOperation?,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    canConvert: Boolean,
    canCompress: Boolean,
    canEditTags: Boolean,
    canClearCache: Boolean,
    onConvert: () -> Unit,
    onCompress: () -> Unit,
    onEditTags: () -> Unit,
    onClearCache: () -> Unit,
    onProperties: () -> Unit,
    onConfirmDestination: () -> Unit,
    onCancelDestination: () -> Unit,
    canProcessMedia: Boolean = false,
    onProcessMedia: () -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Spacer(Modifier.fillMaxWidth().height(4.dp).background(containerColor))
    if (operation == null) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).background(containerColor),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionAction(Modifier.weight(1f), R.drawable.ic_file_copy, stringResource(R.string.activity_main_contentdescription_01), onCopy)
            SelectionAction(Modifier.weight(1f), R.drawable.ic_file_move, stringResource(R.string.activity_main_contentdescription_02), onMove)
            SelectionAction(Modifier.weight(1f), R.drawable.ic_rename, stringResource(R.string.activity_main_contentdescription_03), onRename)
            SelectionAction(Modifier.weight(1f), R.drawable.ic_delete_normal, stringResource(R.string.delete), onDelete)
            Box(Modifier.weight(1f)) {
                SelectionAction(
                    Modifier.fillMaxWidth(),
                    R.drawable.ic_more,
                    stringResource(R.string.activity_main_text_01)
                ) { menuExpanded = true }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (canConvert) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.convert_format)) },
                            onClick = { menuExpanded = false; onConvert() }
                        )
                    }
                    if (canCompress) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.file_op_compress)) },
                            onClick = { menuExpanded = false; onCompress() }
                        )
                    }
                    if (canEditTags) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.media_tag_editor_title)) },
                            onClick = { menuExpanded = false; onEditTags() }
                        )
                    }
                    if (canProcessMedia) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.media_convert_title)) },
                            onClick = { menuExpanded = false; onProcessMedia() }
                        )
                    }
                    if (canClearCache) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.clear_cache)) },
                            onClick = { menuExpanded = false; onClearCache() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.file_op_properties)) },
                        onClick = { menuExpanded = false; onProperties() }
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).background(containerColor).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.activity_main_text_02),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onCancelDestination) { Text(stringResource(R.string.cancel)) }
            Button(onClick = onConfirmDestination) {
                Text(FileOperationUiPolicy.destinationButtonLabel(operation))
            }
        }
    }
}

@Composable
private fun SelectionAction(modifier: Modifier, icon: Int, label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .height(64.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            modifier = Modifier.padding(top = 2.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}
