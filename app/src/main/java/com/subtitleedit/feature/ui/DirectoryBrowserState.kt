package com.subtitleedit.feature.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.subtitleedit.FileOperation
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.model.FileBrowserViewMode
import java.io.File

/** A directory viewport has no dependency on toolbar labels, sort menus or other tabs. */
internal class DirectoryBrowserState(
    val currentDirectory: File?,
    val canonicalDirectoryPath: String?,
    val files: List<File>,
    val fileMetadata: Map<String, BrowserFileMetadata>,
    val selectedPaths: Set<String>,
    val pendingOperation: FileOperation?,
    val viewMode: FileBrowserViewMode,
    val scrollRequest: MainDirectoryScrollRequest?,
    val directoryRevealRequest: MainDirectoryRevealRequest?,
    val relativePathRoot: File?,
    val isDirectoryLoading: Boolean,
    val isDirectoryNavigationPending: Boolean,
    val isSearchInProgress: Boolean,
    val showDirectoryEmptyState: Boolean,
    val directoryEmptyStateMessage: Int,
)

private class DirectoryRowsKey(
    private val files: List<File>,
    private val metadata: Map<String, BrowserFileMetadata>,
) {
    override fun equals(other: Any?): Boolean = other is DirectoryRowsKey &&
        files === other.files && metadata === other.metadata
    override fun hashCode(): Int = 31 * System.identityHashCode(files) + System.identityHashCode(metadata)
}

@Composable
internal fun rememberDirectoryBrowserState(state: MainActivityScreenState): DirectoryBrowserState = remember(
    DirectoryRowsKey(state.files, state.fileMetadata), state.currentDirectory, state.canonicalDirectoryPath,
    state.selectedPaths, state.pendingOperation, state.viewMode, state.scrollRequest, state.directoryRevealRequest,
    state.relativePathRoot, state.isDirectoryLoading, state.isDirectoryNavigationPending,
    state.isSearchInProgress, state.showDirectoryEmptyState, state.directoryEmptyStateMessage,
) {
    DirectoryBrowserState(
        state.currentDirectory, state.canonicalDirectoryPath, state.files, state.fileMetadata,
        state.selectedPaths, state.pendingOperation, state.viewMode, state.scrollRequest, state.directoryRevealRequest,
        state.relativePathRoot, state.isDirectoryLoading, state.isDirectoryNavigationPending,
        state.isSearchInProgress, state.showDirectoryEmptyState, state.directoryEmptyStateMessage,
    )
}
