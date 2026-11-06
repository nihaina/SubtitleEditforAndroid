package com.subtitleedit.feature.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
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
import com.subtitleedit.util.FilePathPolicy
import java.io.File

@Composable
internal fun DirectoryBrowserContent(
    state: MainActivityScreenState,
    onScrollPosition: (() -> DirectoryScrollPosition) -> Unit,
    onDirectoryBreadcrumbClick: (File) -> Boolean,
    onFileClick: (File) -> Unit,
    onFileLongClick: (File) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    gridState: LazyGridState = rememberLazyGridState()
) {
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
        if (state.currentDirectory?.let(FilePathPolicy::canonicalOrAbsolute) != request.directoryPath) {
            return@LaunchedEffect
        }
        val savedPosition = request.savedPosition
        val position = savedPosition?.firstVisiblePath?.let { path ->
            state.files.indexOfFirst { it.absolutePath == path }
        }?.takeIf { it >= 0 } ?: savedPosition?.firstVisibleIndex ?: 0
        val offset = savedPosition?.takeIf { it.viewMode == null || it.viewMode == state.viewMode }?.offset ?: 0
        if (isGrid) gridState.scrollToItem(position.coerceIn(0, state.files.lastIndex), offset)
        else listState.scrollToItem(position.coerceIn(0, state.files.lastIndex), offset)
        appliedScrollGeneration = request.generation
    }
    Column(Modifier.fillMaxSize()) {
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
            if (isGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(136.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("directory-files"),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.files, key = { it.absolutePath }) { file ->
                        BrowserFileRow(file = file, relativePathRoot = state.relativePathRoot,
                            isSelected = file.absolutePath in state.selectedPaths,
                            isSelectionMode = state.selectedPaths.isNotEmpty() && state.pendingOperation == null,
                            onClick = { onFileClick(file) }, onLongClick = { onFileLongClick(file) }, viewMode = state.viewMode)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("directory-files"),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(state.files, key = { it.absolutePath }) { file ->
                        BrowserFileRow(
                            file = file,
                            relativePathRoot = state.relativePathRoot,
                            isSelected = file.absolutePath in state.selectedPaths,
                            isSelectionMode = state.selectedPaths.isNotEmpty() && state.pendingOperation == null,
                            onClick = { onFileClick(file) },
                            onLongClick = { onFileLongClick(file) },
                            viewMode = state.viewMode
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

