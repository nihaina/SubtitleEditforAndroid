package com.subtitleedit.feature.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.FileOperation
import com.subtitleedit.R
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.theme.AppMotion
import com.subtitleedit.util.FileOperationUiPolicy
import com.subtitleedit.util.MainNavigationPolicy

/** Only toolbar values cross this boundary; directory rows never invalidate its composition. */
internal data class MainToolbarState(
    val selectedTopLevelItem: Int,
    val toolbarTitle: String,
    val toolbarHasBack: Boolean,
    val selectionCount: Int,
    val pendingOperation: FileOperation?,
    val isSearchExpanded: Boolean,
    val searchQuery: String,
    val playerModeEnabled: Boolean,
    val viewMode: FileBrowserViewMode,
    val sortField: FileSortField,
    val sortDirection: FileSortDirection,
)

internal fun MainActivityScreenState.toolbarState() = MainToolbarState(
    selectedTopLevelItem, toolbarTitle, toolbarHasBack, selectedPaths.size, pendingOperation,
    isSearchExpanded, searchQuery, playerModeEnabled, viewMode, sortField, sortDirection,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainPageToolbar(
    state: MainToolbarState,
    scrollBehavior: TopAppBarScrollBehavior,
    searchFocusRequester: FocusRequester,
    onSearchChanged: (String) -> Unit,
    onToolbarBack: () -> Unit,
    onTopLevelPageSelected: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectRange: () -> Unit,
    onOnlineClicked: () -> Unit,
    onTogglePlayerMode: () -> Unit,
    onViewModeChanged: (FileBrowserViewMode) -> Unit,
    onSortFieldChanged: (FileSortField) -> Unit,
    onSortDirectionChanged: (FileSortDirection) -> Unit,
    onCreateRequested: (MainCreateItemKind) -> Unit,
    onSearchRequested: () -> Unit,
    onOpenFileManagementSettings: () -> Unit,
    directoryTitleAction: (@Composable () -> Unit)? = null,
) {
    var createMenuExpanded by remember { mutableStateOf(false) }
    var directoryMenuExpanded by remember { mutableStateOf(false) }
    val isDirectoryPage = state.selectedTopLevelItem == R.id.nav_directory
    val isSelectionActive = isDirectoryPage && (state.selectionCount > 0 || state.pendingOperation != null)
    val usesPageBackground = isDirectoryPage || state.selectedTopLevelItem in setOf(
        R.id.nav_library, R.id.nav_stats, R.id.nav_favorites, R.id.nav_drafts, R.id.nav_settings, R.id.nav_tools
    )
    TopAppBar(
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (usesPageBackground) settingsPageBackground() else MaterialTheme.colorScheme.surface,
            scrolledContainerColor = if (usesPageBackground) settingsPageBackground() else MaterialTheme.colorScheme.surfaceContainer
        ),
        title = {
            val titleAction = directoryTitleAction?.takeIf { !state.isSearchExpanded && !isSelectionActive }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DirectoryToolbarTitle(
                    searchOpen = state.isSearchExpanded && isDirectoryPage && !isSelectionActive,
                    query = state.searchQuery,
                    title = when {
                        state.toolbarTitle.isNotBlank() -> state.toolbarTitle
                        isSelectionActive -> FileOperationUiPolicy.selectionTitle(
                            state.pendingOperation,
                            state.selectionCount
                        )
                        isDirectoryPage -> stringResource(R.string.nav_directory)
                        else -> stringResource(MainNavigationPolicy.titleRes(state.selectedTopLevelItem))
                    },
                    onQueryChanged = onSearchChanged,
                    onCloseSearch = onToolbarBack,
                    focusRequester = searchFocusRequester,
                    modifier = if (titleAction == null) Modifier else Modifier.weight(1f, fill = false),
                )
                titleAction?.invoke()
            }
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
            } else if (MainNavigationPolicy.isAuxiliaryPage(state.selectedTopLevelItem, state.playerModeEnabled)) {
                IconButton(onClick = { onTopLevelPageSelected(R.id.nav_directory) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = stringResource(R.string.tools_navigate_back)
                    )
                }
            }
        },
        actions = {
            if (isDirectoryPage && isSelectionActive) {
                // The legacy menu keeps these actions available while a
                // selection is active, except during destination picking.
                if (state.pendingOperation == null && state.selectionCount > 0) {
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
                IconButton(
                    onClick = onOnlineClicked,
                    modifier = Modifier.testTag("directory-online")
                ) {
                    Icon(
                        painterResource(R.drawable.ic_online),
                        contentDescription = stringResource(R.string.player_online_title)
                    )
                }
                IconButton(
                    onClick = onTogglePlayerMode,
                    modifier = Modifier.testTag("directory-player-mode")
                ) {
                    Icon(
                        painterResource(R.drawable.ic_player_mode),
                        contentDescription = stringResource(R.string.player_mode_title),
                        tint = if (state.playerModeEnabled) MaterialTheme.colorScheme.primary
                        else LocalContentColor.current
                    )
                }
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
                                            onCreateRequested(MainCreateItemKind.FILE)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.directory_menu_folder)) },
                                        leadingIcon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                                        onClick = {
                                            directoryMenuExpanded = false
                                            onCreateRequested(MainCreateItemKind.FOLDER)
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
}
