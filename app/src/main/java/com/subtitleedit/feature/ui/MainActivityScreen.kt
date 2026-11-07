package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.FileOperation
import com.subtitleedit.DirectoryScrollPosition
import com.subtitleedit.MainTopLevelPages
import com.subtitleedit.MainTopLevelPagesState
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.util.BatchRename
import com.subtitleedit.util.FileOperationUiPolicy
import com.subtitleedit.util.MainNavigationPolicy
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.model.FileBrowserViewMode
import java.io.File
import com.subtitleedit.ui.theme.AppMotion

private val MainBottomNavigationItems = listOf(
    Triple(R.id.nav_directory, R.drawable.ic_nav_folder, R.string.nav_directory),
    Triple(R.id.nav_favorites, R.drawable.ic_favorite, R.string.nav_favorites),
    Triple(R.id.nav_drafts, R.drawable.ic_nav_draft, R.string.drafts),
    Triple(R.id.nav_tools, R.drawable.ic_tools, R.string.menu_main_title_01),
    Triple(R.id.nav_settings, R.drawable.ic_settings, R.string.menu_main_title_02)
)

private val TopLevelOrder = MainBottomNavigationItems.map { it.first }

internal data class MainDirectoryScrollRequest(
    val generation: Long,
    val directoryPath: String,
    val savedPosition: DirectoryScrollPosition?
)

internal data class MainActivityScreenState(
    val selectedTopLevelItem: Int = R.id.nav_directory,
    val toolbarTitle: String = "",
    val toolbarHasBack: Boolean = false,
    val isDirectoryLoading: Boolean = false,
    val isSearchInProgress: Boolean = false,
    val showDirectoryEmptyState: Boolean = false,
    val directoryEmptyStateMessage: Int = R.string.no_files,
    val currentDirectory: File? = null,
    val files: List<File> = emptyList(),
    val scrollRequest: MainDirectoryScrollRequest? = null,
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
    val canClearCacheSelected: Boolean = false
)

internal enum class MainCreateItemKind { FOLDER, FILE }
internal enum class MainCreateItemField { NAME, EXTENSION }
internal data class MainCreateItemError(val field: MainCreateItemField, val message: String)

@OptIn(ExperimentalMaterial3Api::class)
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
    onCacheClearConfirm: (com.subtitleedit.ui.settings.SettingsCacheItem) -> Unit
) {
    val directoryListState = rememberLazyListState()
    val directoryGridState = rememberLazyGridState()
    val topLevelPagesState = remember { MainTopLevelPagesState() }
    var createMenuExpanded by remember { mutableStateOf(false) }
    var directoryMenuExpanded by remember { mutableStateOf(false) }
    var createDialogKind by remember { mutableStateOf<MainCreateItemKind?>(null) }
    var createName by remember { mutableStateOf("") }
    var createExtension by remember { mutableStateOf("txt") }
    var createNameError by remember { mutableStateOf<String?>(null) }
    var createExtensionError by remember { mutableStateOf<String?>(null) }
    val isDirectoryPage = state.selectedTopLevelItem == R.id.nav_directory
    val usesPageBackground = isDirectoryPage || state.selectedTopLevelItem in setOf(
        R.id.nav_favorites, R.id.nav_drafts, R.id.nav_settings, R.id.nav_tools
    )
    // Selection actions belong to directoryContent in the legacy layout. The
    // selection state can survive a top-level tab switch, but its toolbar and
    // bottom action row stay hidden until the directory tab is visible again.
    val isSelectionActive = isDirectoryPage &&
        (state.selectedPaths.isNotEmpty() || state.pendingOperation != null)
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val createNameFocusRequester = remember { FocusRequester() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

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
    LaunchedEffect(state.isSearchExpanded, isSelectionActive, isDirectoryPage) {
        if (state.isSearchExpanded && !isSelectionActive && isDirectoryPage) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }
    LaunchedEffect(state.selectedTopLevelItem) {
        scrollBehavior.state.contentOffset = 0f
        scrollBehavior.state.heightOffset = 0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (usesPageBackground) settingsPageBackground() else MaterialTheme.colorScheme.surface,
                scrolledContainerColor = if (usesPageBackground) settingsPageBackground() else MaterialTheme.colorScheme.surfaceContainer
            ),
            title = {
                DirectoryToolbarTitle(
                    searchOpen = state.isSearchExpanded && isDirectoryPage && !isSelectionActive,
                    query = state.searchQuery,
                    title = when {
                        state.toolbarTitle.isNotBlank() -> state.toolbarTitle
                        isSelectionActive -> FileOperationUiPolicy.selectionTitle(
                            state.pendingOperation,
                            state.selectedPaths.size
                        )
                        isDirectoryPage -> stringResource(R.string.nav_directory)
                        else -> stringResource(MainNavigationPolicy.titleRes(state.selectedTopLevelItem))
                    },
                    onQueryChanged = onSearchChanged,
                    onCloseSearch = onToolbarBack,
                    focusRequester = searchFocusRequester
                )
            },
            navigationIcon = {
                if (state.toolbarHasBack || isSelectionActive) {
                    IconButton(onClick = onToolbarBack) {
                        Icon(
                            painter = painterResource(
                                if (isSelectionActive || state.isSearchExpanded) R.drawable.ic_close
                                else R.drawable.ic_back
                            ),
                            contentDescription = stringResource(
                                R.string.cancel
                            )
                        )
                    }
                }
            },
            actions = {
                if (isDirectoryPage && isSelectionActive) {
                    // The legacy menu keeps these actions available while a
                    // selection is active, except during destination picking.
                    if (state.pendingOperation == null && state.selectedPaths.isNotEmpty()) {
                        IconButton(onClick = onSelectAll) {
                            Icon(
                                painterResource(R.drawable.ic_select_all),
                                contentDescription = stringResource(R.string.source_selection_select_all)
                            )
                        }
                        IconButton(onClick = onSelectRange) {
                            Icon(
                                painterResource(R.drawable.ic_select_range),
                                contentDescription = stringResource(R.string.directory_select_range)
                            )
                        }
                    }
                } else if (isDirectoryPage) {
                    DirectoryViewMenu(state.viewMode, state.sortField, state.sortDirection,
                        onViewModeChanged, onSortFieldChanged, onSortDirectionChanged)
                    Box {
                        IconButton(onClick = {
                            createMenuExpanded = false
                            directoryMenuExpanded = true
                        }) {
                            Icon(painterResource(R.drawable.ic_more_vertical), contentDescription = stringResource(R.string.activity_main_text_01))
                        }
                        DropdownMenu(
                            expanded = directoryMenuExpanded,
                            onDismissRequest = {
                                directoryMenuExpanded = false
                            },
                            modifier = Modifier.widthIn(min = 200.dp)
                        ) {
                            AnimatedContent(
                                targetState = createMenuExpanded,
                                transitionSpec = {
                                    val direction = if (targetState) 1 else -1
                                    (fadeIn(AppMotion.state()) + slideInHorizontally(AppMotion.state()) { direction * it / 6 }) togetherWith
                                        (fadeOut(AppMotion.fast()) + slideOutHorizontally(AppMotion.fast()) { -direction * it / 6 }) using
                                        SizeTransform(clip = true) { _, _ -> AppMotion.state() }
                                },
                                contentAlignment = Alignment.TopStart,
                                label = "directory-create-menu"
                            ) { showCreateMenu ->
                                Column {
                                    if (showCreateMenu) {
                                        Text(
                                            stringResource(R.string.menu_new),
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_file)) },
                                            leadingIcon = { Icon(painterResource(R.drawable.ic_file), contentDescription = null) },
                                            onClick = {
                                                directoryMenuExpanded = false
                                                createDialogKind = MainCreateItemKind.FILE
                                                createName = ""
                                                createExtension = "txt"
                                                createNameError = null
                                                createExtensionError = null
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.directory_menu_folder)) },
                                            leadingIcon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                                            onClick = {
                                                directoryMenuExpanded = false
                                                createDialogKind = MainCreateItemKind.FOLDER
                                                createName = ""
                                                createExtension = "txt"
                                                createNameError = null
                                                createExtensionError = null
                                            }
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_new)) },
                                            leadingIcon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                                            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null) },
                                            onClick = { createMenuExpanded = true }
                                        )
                                        if (!state.isSearchExpanded) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.menu_search)) },
                                                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                                                onClick = {
                                                    directoryMenuExpanded = false
                                                    onSearchRequested()
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_main_title_02)) },
                                            leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                                            onClick = {
                                                directoryMenuExpanded = false
                                                onOpenFileManagementSettings()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            val topLevelOffsetPx = with(LocalDensity.current) { 28.dp.roundToPx() }
            AnimatedContent(
                targetState = state.selectedTopLevelItem,
                transitionSpec = {
                    val direction = if (
                        TopLevelOrder.indexOf(targetState) > TopLevelOrder.indexOf(initialState)
                    ) 1 else -1
                    (
                        slideInHorizontally(AppMotion.enter()) { direction * topLevelOffsetPx } +
                            fadeIn(animationSpec = androidx.compose.animation.core.tween(210, delayMillis = 90))
                        ) togetherWith (
                        slideOutHorizontally(AppMotion.exit()) { -direction * topLevelOffsetPx } +
                            fadeOut(animationSpec = androidx.compose.animation.core.tween(90))
                        ) using SizeTransform(clip = false)
                },
                label = "main-top-level"
            ) { page ->
                if (page == R.id.nav_directory) {
                    DirectoryBrowserContent(
                        state = state,
                        onScrollPosition = onDirectoryScrollPosition,
                        onDirectoryBreadcrumbClick = onDirectoryBreadcrumbClick,
                        onFileClick = onFileClick,
                        onFileLongClick = onFileLongClick,
                        listState = directoryListState,
                        gridState = directoryGridState
                    )
                } else {
                    MainTopLevelPages(
                        selectedPage = page,
                        activePage = state.selectedTopLevelItem,
                        refreshVersion = topLevelPageRefreshVersion,
                        selectionVersion = topLevelPageSelectionVersion,
                        state = topLevelPagesState,
                        viewMode = state.viewMode,
                        onOpenDirectory = onOpenDirectoryFromFavorites,
                        onToolbarChanged = onUpdateTopLevelToolbar
                    )
                }
            }
        }

        val showBottomNavigation = state.selectedPaths.isEmpty() && state.pendingOperation == null
        androidx.compose.animation.AnimatedContent(
            targetState = showBottomNavigation,
            transitionSpec = {
                androidx.compose.animation.fadeIn(AppMotion.enter()) togetherWith
                    androidx.compose.animation.fadeOut(AppMotion.exit())
            },
            label = "selection-actions"
        ) { showBottom ->
            if (showBottom) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MainBottomNavigation(
                    selectedItemId = state.selectedTopLevelItem,
                    onSelected = onTopLevelPageSelected
                )
            } else if (isSelectionActive) {
                SelectionActions(
                    operation = state.pendingOperation,
                    onCopy = onCopy,
                    onMove = onMove,
                    onRename = onRename,
                    onDelete = onDelete,
                    canConvert = state.canConvertSelected,
                    canCompress = state.canCompressSelected,
                    canClearCache = state.canClearCacheSelected,
                    onConvert = onConvertSelection,
                    onCompress = onCompressSelection,
                    onClearCache = onClearCacheSelection,
                    onProperties = onShowSelectionProperties,
                    onConfirmDestination = onConfirmDestination,
                    onCancelDestination = onCancelDestination
                )
            }
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
private fun SelectionActions(
    operation: FileOperation?,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    canConvert: Boolean,
    canCompress: Boolean,
    canClearCache: Boolean,
    onConvert: () -> Unit,
    onCompress: () -> Unit,
    onClearCache: () -> Unit,
    onProperties: () -> Unit,
    onConfirmDestination: () -> Unit,
    onCancelDestination: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Spacer(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceContainer))
    if (operation == null) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surfaceContainer),
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
            modifier = Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 16.dp, end = 8.dp),
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

@Composable
private fun MainBottomNavigation(selectedItemId: Int, onSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp).background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MainBottomNavigationItems.forEach { (id, icon, title) ->
            val label = stringResource(title)
            val selected = selectedItemId == id
            val indicatorAlpha by animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                animationSpec = AppMotion.enter(),
                label = "bottom-nav-indicator"
            )
            val indicatorScale by animateFloatAsState(
                targetValue = if (selected) 1f else 0.6f,
                animationSpec = AppMotion.enter(),
                label = "bottom-nav-indicator-scale"
            )
            val tint by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = AppMotion.fast(),
                label = "bottom-nav-tint"
            )
            val interactionSource = remember { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    // BottomNavigationView dispatched the selection callback even
                    // when the tapped item was already selected. MainActivity uses
                    // that callback to recreate/reset the corresponding top-level
                    // page, so repeated taps must remain observable here.
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onSelected(id) }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 28.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .graphicsLayer {
                            scaleX = indicatorScale
                            scaleY = indicatorScale
                        }
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = indicatorAlpha)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = label,
                        modifier = Modifier.size(24.dp),
                        tint = tint
                    )
                }
                Text(label, color = tint, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}
