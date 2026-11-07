package com.subtitleedit.feature.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.util.AndroidDirectoryPolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
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
    directoryPageStyle: Boolean = false
) {
    val context = LocalContext.current
    // FavoritesFragment historically used FileListAdapter without an
    // isItemRestricted callback. Preserve that behavior for its rows while
    // keeping the Android/data restriction for the main browser.
    val restricted = remember(file.absolutePath, restrictedOverride) {
        restrictedOverride ?: AndroidDirectoryPolicy.isRestricted(file)
    }
    val previewKey = remember(file.absolutePath, file.length(), file.lastModified()) {
        "${file.absolutePath}:${file.length()}:${file.lastModified()}"
    }
    val thumbnailDp = when (viewMode) {
        FileBrowserViewMode.DETAILED -> 40
        FileBrowserViewMode.COMPACT -> 32
        FileBrowserViewMode.GRID -> 160
    }
    val targetSize = remember(context, thumbnailDp) { (thumbnailDp * context.resources.displayMetrics.density + 0.5f).toInt() }
    var preview by remember(previewKey) { mutableStateOf<MainFilePreview?>(null) }
    var directoryCount by remember(previewKey) { mutableStateOf<Int?>(null) }
    LaunchedEffect(previewKey, targetSize) {
        preview = withContext(Dispatchers.IO) {
            MainFilePreviewLoader.preview(context, file, targetSize)
        }
        if (file.isDirectory && file.name != ".." && !restricted) {
            directoryCount = withContext(Dispatchers.IO) {
                MainFilePreviewLoader.directoryItemCount(file)
            }
        }
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
            isDirectory = file.isDirectory,
            size = file.length(),
            modifiedTimeMillis = file.lastModified(),
            itemCount = directoryCount.takeUnless { restricted },
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
        preview = preview,
        loadingDuration = MainFilePreviewLoader.isMediaFile(file) && preview == null,
        restricted = restricted || !enabled,
        isSelected = isSelected,
        alpha = alpha,
        directoryPageStyle = directoryPageStyle
    )
}

@Composable
internal fun FileRowIcon(file: File, bitmap: android.graphics.Bitmap?, restricted: Boolean, size: Dp = 48.dp) {
    BrowserFilePreviewIcon(file.name, file.isDirectory, bitmap, restricted, size)
}

