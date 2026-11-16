package com.subtitleedit.feature.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.subtitleedit.DirectoryScrollPosition
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.ui.theme.AppMotion
import com.subtitleedit.ui.settings.settingsPageBackground
import java.io.File

private fun initialDirectoryPosition(state: MainActivityScreenState): Pair<Int, Int> {
    val request = state.scrollRequest?.takeIf {
        it.directoryPath == (state.canonicalDirectoryPath ?: state.currentDirectory?.absolutePath)
    } ?: return 0 to 0
    if (state.files.isEmpty()) return 0 to 0
    val saved = request.savedPosition
    val index = saved?.firstVisiblePath?.let { path -> state.files.indexOfFirst { it.absolutePath == path } }
        ?.takeIf { it >= 0 } ?: saved?.firstVisibleIndex ?: 0
    val offset = saved?.takeIf { it.viewMode == null || it.viewMode == state.viewMode }?.offset ?: 0
    return index.coerceIn(0, state.files.lastIndex) to offset
}

/** A destination starts at its own saved anchor before its rows are first measured. */
@Composable
internal fun rememberDirectoryListState(state: MainActivityScreenState): LazyListState =
    key(state.currentDirectory?.absolutePath, state.viewMode) {
        val (index, offset) = initialDirectoryPosition(state)
        rememberLazyListState(index, offset)
    }

@Composable
internal fun rememberDirectoryGridState(state: MainActivityScreenState): LazyGridState =
    key(state.currentDirectory?.absolutePath, state.viewMode) {
        val (index, _) = initialDirectoryPosition(state)
        rememberLazyGridState(index, 0)
    }

@Composable
internal fun DirectoryBrowserContent(
    state: MainActivityScreenState,
    onScrollPosition: (() -> DirectoryScrollPosition) -> Unit,
    onDirectoryBreadcrumbClick: (File) -> Boolean,
    onFileClick: (File) -> Unit,
    onFileLongClick: (File) -> Unit,
    listState: LazyListState = rememberDirectoryListState(state),
    gridState: LazyGridState = rememberDirectoryGridState(state),
    isActive: Boolean = true
) {
    // Directory long-press keeps file operations; only an already active media selection takes over.
    val mediaSelection = com.subtitleedit.ui.LocalMediaSelection.current?.takeIf { it.active }
    LaunchedEffect(mediaSelection, isActive, state.files, state.fileMetadata) {
        if (isActive) mediaSelection?.refreshVisiblePaths(
            state.files.map { it.absolutePath }, state.fileMetadata
        )
    }
    val isGrid = state.viewMode == FileBrowserViewMode.GRID
    SideEffect {
        onScrollPosition {
            val index = if (isGrid) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex
            val offset = if (isGrid) gridState.firstVisibleItemScrollOffset else listState.firstVisibleItemScrollOffset
            DirectoryScrollPosition(state.files.getOrNull(index)?.absolutePath, index, offset, state.viewMode)
        }
    }
    val scrollRequest = state.scrollRequest
    var appliedScrollGeneration by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(scrollRequest, state.files, state.currentDirectory, isGrid) {
        val request = scrollRequest ?: return@LaunchedEffect
        if (appliedScrollGeneration == request.generation || state.files.isEmpty()) return@LaunchedEffect
        if ((state.canonicalDirectoryPath ?: state.currentDirectory?.absolutePath) != request.directoryPath) {
            return@LaunchedEffect
        }
        val savedPosition = request.savedPosition
        val position = savedPosition?.firstVisiblePath?.let { path ->
            state.files.indexOfFirst { it.absolutePath == path }
        }?.takeIf { it >= 0 } ?: savedPosition?.firstVisibleIndex ?: 0
        // Grid cell heights can change with the adaptive column count; restore the
        // saved item without a stale pixel offset that could scroll past it.
        val offset = if (isGrid) 0 else savedPosition?.takeIf {
            it.viewMode == null || it.viewMode == state.viewMode
        }?.offset ?: 0
        if (isGrid) gridState.scrollToItem(position.coerceIn(0, state.files.lastIndex), offset)
        else listState.scrollToItem(position.coerceIn(0, state.files.lastIndex), offset)
        appliedScrollGeneration = request.generation
    }
    Column(Modifier.fillMaxSize().background(settingsPageBackground())) {
        state.currentDirectory?.let { directory ->
            DirectoryBreadcrumbBar(
                directory = directory,
                enabled = state.selectedPaths.isEmpty() || state.pendingOperation != null,
                onNavigate = onDirectoryBreadcrumbClick
            )
        }
        // The legacy directory screen only exposed progress while a recursive
        // search was running. Loading a directory kept the existing list and
        // did not replace the empty-state text with the search message.
        val searchProgressAlpha by animateFloatAsState(
            targetValue = if (state.isSearchInProgress) 1f else 0f,
            animationSpec = AppMotion.fast(),
            label = "directory-search-progress"
        )
        androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = searchProgressAlpha),
            trackColor = Color.Transparent,
            progress = { 1f }
        )

        if (state.showDirectoryEmptyState) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(state.directoryEmptyStateMessage),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else if (state.files.isNotEmpty()) {
            BrowserFileLayout(
                items = state.files,
                key = { it.absolutePath },
                viewMode = state.viewMode,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("directory-files"),
                listState = listState,
                gridState = gridState,
                adaptiveGrid = true
            ) { file ->
                BrowserFileRow(
                    file = file,
                    metadata = state.fileMetadata[file.absolutePath],
                    relativePathRoot = state.relativePathRoot,
                    isSelected = mediaSelection?.contains(file.absolutePath) ?: (file.absolutePath in state.selectedPaths),
                    isSelectionMode = mediaSelection?.let { selection ->
                        selection.active && (state.fileMetadata[file.absolutePath]?.isRegularFile
                            ?: selection.isVisibleMedia(file.absolutePath)) && (com.subtitleedit.util.FileUtils.isAudioFile(file) ||
                            com.subtitleedit.util.FileTypePolicy.isVideo(file))
                    } ?: (state.selectedPaths.isNotEmpty() && state.pendingOperation == null),
                    onClick = { if (!state.isDirectoryNavigationPending) onFileClick(file) },
                    onLongClick = { if (!state.isDirectoryNavigationPending) onFileLongClick(file) },
                    viewMode = state.viewMode,
                    directoryPageStyle = true
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

