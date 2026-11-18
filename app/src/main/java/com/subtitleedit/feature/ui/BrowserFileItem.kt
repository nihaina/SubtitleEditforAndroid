package com.subtitleedit.feature.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.library.MediaLibraryFileObserver
import com.subtitleedit.util.SharedFileCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowserFileRow(
    modifier: Modifier = Modifier,
    file: File,
    relativePathRoot: File?,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    restrictedOverride: Boolean? = null,
    enabled: Boolean = true,
    viewMode: FileBrowserViewMode = FileBrowserViewMode.DETAILED,
    directoryPageStyle: Boolean = false,
    metadata: BrowserFileMetadata? = null,
    metadataRefreshKey: Any? = null,
    isDirectoryHint: Boolean? = null,
    initialMetadata: BrowserFileMetadata? = null,
    directoryArtwork: Bitmap? = null
) {
    val context = LocalContext.current
    // FavoritesFragment historically used FileListAdapter without an
    // isItemRestricted callback. Preserve that behavior for its rows while
    // keeping the Android/data restriction for the main browser.
    val fileMetadata = rememberBrowserFileMetadata(file, metadata, metadataRefreshKey, initialMetadata)
    val restricted = restrictedOverride ?: fileMetadata?.restricted ?: false
    val previewKey = fileMetadata?.cacheKey
    val thumbnailDp = when (viewMode) {
        FileBrowserViewMode.DETAILED -> 40
        FileBrowserViewMode.COMPACT -> 32
        FileBrowserViewMode.GRID -> 160
    }
    val targetSize = remember(context, thumbnailDp) { (thumbnailDp * context.resources.displayMetrics.density + 0.5f).toInt() }
    var rowData by remember(file.absolutePath, previewKey, targetSize, restricted) {
        mutableStateOf(fileMetadata?.let { snapshot ->
            MainFilePreviewLoader.peek(snapshot, targetSize)?.let { preview ->
                BrowserFileRowData(preview, MainFilePreviewLoader.peekDirectoryItemCount(snapshot).takeUnless { restricted })
            }
        })
    }
    LaunchedEffect(file.absolutePath, previewKey, targetSize, restricted) {
        val snapshot = fileMetadata ?: return@LaunchedEffect
        val preview = MainFilePreviewLoader.preview(context, snapshot, targetSize)
        val count = if (snapshot.isDirectory && file.name != ".." && !restricted) {
            MainFilePreviewLoader.directoryItemCount(snapshot)
        } else null
        rowData = BrowserFileRowData(preview, count)
    }
    val displayName = remember(file.absolutePath, relativePathRoot?.absolutePath) {
        relativePathRoot?.let { root -> runCatching { file.relativeTo(root).path }.getOrDefault(file.name) }
            ?: file.name
    }
    val alpha = when {
        !enabled -> 0.38f
        isSelectionMode && !isSelected -> 0.72f
        restricted -> 0.55f
        else -> 1f
    }
    BrowserFileItem(
        entry = BrowserFileEntry(
            name = file.name,
            isDirectory = fileMetadata?.isDirectory ?: isDirectoryHint ?: false,
            size = fileMetadata?.size ?: -1L,
            modifiedTimeMillis = fileMetadata?.modifiedTimeMillis,
            itemCount = rowData?.directoryCount.takeUnless { restricted },
            displayName = displayName,
            isParentDirectory = file.name == ".."
        ),
        viewMode = viewMode,
        modifier = modifier,
        interactionModifier = Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = { if (restricted) onClick() else onLongClick() }
        ),
        preview = directoryArtwork?.let { MainFilePreview(bitmap = it) } ?: rowData?.preview,
        loadingDuration = MainFilePreviewLoader.isMediaFile(file) && rowData == null,
        restricted = restricted || !enabled,
        isSelected = isSelected,
        alpha = alpha,
        directoryPageStyle = directoryPageStyle
    )
}

private data class BrowserFileRowData(val preview: MainFilePreview, val directoryCount: Int?)

/** A replacement list is a new data load even when its path-only File values compare equal. */
internal class BrowserFileRefreshKey(private val snapshot: Any) {
    override fun equals(other: Any?): Boolean = other is BrowserFileRefreshKey && snapshot === other.snapshot
    override fun hashCode(): Int = System.identityHashCode(snapshot)
}

/** Other browser surfaces can load a snapshot once, without filesystem reads in composition. */
@Composable
internal fun rememberBrowserFileMetadata(
    file: File,
    metadata: BrowserFileMetadata? = null,
    metadataRefreshKey: Any? = null,
    initialMetadata: BrowserFileMetadata? = null
): BrowserFileMetadata? {
    // The directory loader already owns this snapshot. A second state/effect per row
    // would only publish the same value and add work to the first directory frame.
    if (metadata != null) return metadata
    val fallback by key(file.absolutePath) {
        produceState<BrowserFileMetadata?>(
            initialMetadata ?: SharedFileCache.attributes.peek(file.absolutePath), file.absolutePath,
            metadataRefreshKey
        ) {
            // A conflated signal cannot miss this path during a burst. Other paths
            // remain cache hits, so they never trigger a metadata stat here.
            var first = true
            SharedFileCache.attributes.invalidationRevision.collect {
                val verify = first && metadataRefreshKey != null &&
                    file.parentFile?.absolutePath !in MediaLibraryFileObserver.observedDirectories.value
                value = withContext(Dispatchers.IO) {
                    if (verify) SharedFileCache.attributes.refresh(file) else SharedFileCache.attributes.get(file)
                }
                first = false
            }
        }
    }
    return fallback?.takeIf { it.path == file.absolutePath }
}

@Composable
internal fun FileRowIcon(file: File, isDirectory: Boolean, bitmap: android.graphics.Bitmap?, restricted: Boolean, size: Dp = 48.dp) {
    BrowserFilePreviewIcon(file.name, isDirectory, bitmap, restricted, size)
}

