package com.subtitleedit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.feature.ui.BrowserFileRow
import com.subtitleedit.feature.ui.BrowserFileLayout
import com.subtitleedit.feature.ui.BrowserFileRefreshKey
import com.subtitleedit.feature.ui.browserFileContentPadding
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.SectionHeader
import com.subtitleedit.ui.settings.settingsPageBackground
import java.io.File

@Composable
fun FavoritesScreen(
    directories: List<File>,
    pendingRemoval: File?,
    onAddDirectory: () -> Unit,
    onOpenDirectory: (File) -> Unit,
    onRequestRemoval: (File) -> Unit,
    onConfirmRemoval: () -> Unit,
    onDismissRemoval: () -> Unit,
    viewMode: FileBrowserViewMode = FileBrowserViewMode.DETAILED,
    metadataRefreshKey: Any? = null,
    mediaLibraryDirectoryPaths: Set<String> = emptySet()
) {
    val sections = remember(directories, mediaLibraryDirectoryPaths) {
        val (library, common) = directories.partition { it.absolutePath in mediaLibraryDirectoryPaths }
        buildList {
            if (common.isNotEmpty()) add(FavoriteDirectorySection(R.string.nav_favorites, common))
            if (library.isNotEmpty()) add(FavoriteDirectorySection(R.string.nav_library, library))
        }
    }
    Box(Modifier.fillMaxSize().background(settingsPageBackground())) {
        if (directories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_favorite_directories),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            val directoryContent: @Composable (File) -> Unit = { directory ->
                FavoriteDirectoryItem(directory, viewMode, onOpenDirectory, onRequestRemoval,
                    metadataRefreshKey ?: BrowserFileRefreshKey(directories))
            }
            if (sections.lastOrNull()?.titleRes != R.string.nav_library) {
                BrowserFileLayout(
                    items = directories,
                    key = { it.absolutePath },
                    viewMode = viewMode,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = browserFileContentPadding(viewMode, bottom = 96.dp),
                    adaptiveGrid = true,
                    itemContent = directoryContent
                )
            } else {
                FavoriteDirectorySections(sections, viewMode, directoryContent)
            }
        }
        FloatingActionButton(
            onClick = onAddDirectory,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.add_favorite_directory),
                modifier = Modifier.size(24.dp)
            )
        }
    }

    pendingRemoval?.let { directory ->
        AppAlertDialog(
            title = stringResource(R.string.remove_favorite_directory),
            message = stringResource(R.string.remove_favorite_directory_confirm, directory.name),
            confirmText = stringResource(R.string.confirm),
            dismissText = stringResource(R.string.cancel),
            onConfirm = onConfirmRemoval,
            onDismiss = onDismissRemoval
        )
    }
}

private data class FavoriteDirectorySection(val titleRes: Int, val directories: List<File>)

@Composable
private fun FavoriteDirectorySections(
    sections: List<FavoriteDirectorySection>,
    viewMode: FileBrowserViewMode,
    directoryContent: @Composable (File) -> Unit
) {
    val padding = browserFileContentPadding(viewMode, bottom = 96.dp)
    if (viewMode == FileBrowserViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            sections.forEach { section ->
                item(key = section.titleRes, span = { GridItemSpan(maxLineSpan) }) {
                    FavoriteDirectorySectionHeader(section.titleRes, viewMode)
                }
                items(section.directories, key = { it.absolutePath }) { directoryContent(it) }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            sections.forEach { section ->
                item(key = section.titleRes) { FavoriteDirectorySectionHeader(section.titleRes, viewMode) }
                items(section.directories, key = { it.absolutePath }) { directoryContent(it) }
            }
        }
    }
}

@Composable
private fun FavoriteDirectorySectionHeader(titleRes: Int, viewMode: FileBrowserViewMode) {
    SectionHeader(
        stringResource(titleRes),
        Modifier.fillMaxWidth().padding(start = if (viewMode == FileBrowserViewMode.GRID) 4.dp else 12.dp,
            top = 12.dp)
    )
}

@Composable
private fun FavoriteDirectoryItem(
    directory: File,
    viewMode: FileBrowserViewMode,
    onOpenDirectory: (File) -> Unit,
    onRequestRemoval: (File) -> Unit,
    metadataRefreshKey: Any?
) {
    BrowserFileRow(
        file = directory,
        metadataRefreshKey = metadataRefreshKey,
        relativePathRoot = null,
        isSelected = false,
        isSelectionMode = false,
        onClick = { onOpenDirectory(directory) },
        onLongClick = { onRequestRemoval(directory) },
        restrictedOverride = false,
        viewMode = viewMode,
        directoryPageStyle = true
    )
}
