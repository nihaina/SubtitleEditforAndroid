package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.subtitleedit.model.FileBrowserViewMode

internal fun browserFileContentPadding(
    viewMode: FileBrowserViewMode,
    bottom: Dp = if (viewMode == FileBrowserViewMode.GRID) 8.dp else 4.dp
): PaddingValues = if (viewMode == FileBrowserViewMode.GRID) {
    PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = bottom)
} else {
    PaddingValues(top = 4.dp, bottom = bottom)
}

/** Keeps columns, spacing and padding identical for filesystem and virtual directories. */
@Composable
internal fun <T> BrowserFileLayout(
    items: List<T>,
    key: (T) -> Any,
    viewMode: FileBrowserViewMode,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    gridState: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = browserFileContentPadding(viewMode),
    itemContent: @Composable (T) -> Unit
) {
    if (viewMode == FileBrowserViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = modifier,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(items, key = key) { item -> itemContent(item) }
        }
    } else {
        LazyColumn(modifier = modifier, state = listState, contentPadding = contentPadding) {
            items(items, key = key) { item -> itemContent(item) }
        }
    }
}
