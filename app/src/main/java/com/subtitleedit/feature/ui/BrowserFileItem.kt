package com.subtitleedit.feature.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.ui.components.StatusBadge
import com.subtitleedit.ui.theme.AppMotion
import com.subtitleedit.util.AndroidDirectoryPolicy
import com.subtitleedit.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
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
    viewMode: FileBrowserViewMode = FileBrowserViewMode.DETAILED
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    // FavoritesFragment historically used FileListAdapter without an
    // isItemRestricted callback. Preserve that behavior for its rows while
    // keeping the Android/data restriction for the main browser.
    val restricted = remember(file.absolutePath, restrictedOverride) {
        restrictedOverride ?: AndroidDirectoryPolicy.isRestricted(file)
    }
    val previewKey = remember(file.absolutePath, file.length(), file.lastModified()) {
        "${file.absolutePath}:${file.length()}:${file.lastModified()}"
    }
    val thumbnailDp = if (viewMode == FileBrowserViewMode.GRID) 64 else 48
    val targetSize = remember(context, thumbnailDp) { (thumbnailDp * context.resources.displayMetrics.density + 0.5f).toInt() }
    var preview by remember(previewKey) { mutableStateOf<MainFilePreview?>(null) }
    var directoryCount by remember(previewKey) { mutableStateOf<Int?>(null) }
    LaunchedEffect(previewKey, targetSize) {
        preview = withContext(Dispatchers.IO) {
            MainFilePreviewLoader.preview(context, file, targetSize)
        }
        if (viewMode != FileBrowserViewMode.GRID && file.isDirectory && file.name != ".." && !restricted) {
            directoryCount = withContext(Dispatchers.IO) {
                MainFilePreviewLoader.directoryItemCount(file)
            }
        }
    }
    val displayName = remember(file.absolutePath, relativePathRoot?.absolutePath) {
        relativePathRoot?.let { root -> runCatching { file.relativeTo(root).path }.getOrDefault(file.name) }
            ?: file.name
    }
    val modifiedTime = remember(file.lastModified(), locale) {
        SimpleDateFormat("yyyy-MM-dd", locale).format(Date(file.lastModified()))
    }
    val alpha = when {
        !enabled -> 0.38f
        isSelectionMode && !isSelected -> 0.72f
        restricted -> 0.55f
        else -> 1f
    }
    val isGrid = viewMode == FileBrowserViewMode.GRID
    val containerColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            isGrid -> Color.Transparent
            else -> MaterialTheme.colorScheme.surfaceContainerLowest
        },
        animationSpec = AppMotion.state(),
        label = "file-row-container"
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            isSelected -> 2.dp
            else -> 0.dp
        },
        animationSpec = AppMotion.state(),
        label = "file-row-border"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = AppMotion.state(),
        label = "file-row-border-color"
    )
    val rowShape = if (isGrid) MaterialTheme.shapes.large else MaterialTheme.shapes.medium
    val itemModifier = modifier
        .fillMaxWidth()
        .padding(
            horizontal = if (isGrid) 0.dp else 4.dp,
            vertical = when (viewMode) {
                FileBrowserViewMode.DETAILED -> 4.dp
                FileBrowserViewMode.COMPACT -> 2.dp
                FileBrowserViewMode.GRID -> 0.dp
            }
        )
        .alpha(alpha)
        .clip(rowShape)
        .combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = { if (restricted) onClick() else onLongClick() }
        )
    val summary = when {
        file.name == ".." -> ""
        !file.isDirectory -> FileUtils.formatFileSize(file.length())
        restricted || directoryCount == null || directoryCount!! < 0 -> ""
        directoryCount == 0 -> stringResource(R.string.directory_empty)
        else -> stringResource(R.string.directory_item_count, directoryCount!!)
    }
    val details = BrowserFileDetails(
        name = displayName,
        summary = summary,
        duration = preview?.mediaDuration.orEmpty(),
        modifiedTime = if (file.name == "..") "" else modifiedTime,
        extension = if (file.isDirectory) "" else file.extension.uppercase(locale)
    )
    val border = BorderStroke(borderWidth, borderColor)
    Surface(
        modifier = itemModifier,
        shape = rowShape,
        color = containerColor,
        border = border,
        tonalElevation = if (isSelected && !isGrid) 2.dp else 0.dp
    ) {
        when (viewMode) {
            FileBrowserViewMode.DETAILED -> DetailedFileRowContent(file, preview, details, restricted || !enabled)
            FileBrowserViewMode.COMPACT -> CompactFileRowContent(file, preview, details, restricted || !enabled)
            FileBrowserViewMode.GRID -> GridFileItemContent(file, preview, displayName, restricted || !enabled)
        }
    }
}

private data class BrowserFileDetails(
    val name: String,
    val summary: String,
    val duration: String,
    val modifiedTime: String,
    val extension: String
) {
    fun metadata(includeDate: Boolean = false): String =
        listOf(summary, duration, if (includeDate) modifiedTime else "").filter(String::isNotEmpty).joinToString(" • ")
}

@Composable
private fun DetailedFileRowContent(file: File, preview: MainFilePreview?, details: BrowserFileDetails, restricted: Boolean) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        FileRowIcon(file, preview?.bitmap, restricted)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
            FileItemTitle(details.name, restricted, MaterialTheme.typography.titleMedium)
            if (file.name != "..") {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(details.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Keep the duration slot while the media preview is loading.
                    val loadingDuration = MainFilePreviewLoader.isMediaFile(file) && preview == null
                    if (loadingDuration || details.duration.isNotEmpty()) {
                        Text(if (loadingDuration) "00:00" else "• ${details.duration}",
                            modifier = Modifier.padding(start = 8.dp).width(72.dp).alpha(if (loadingDuration) 0f else 1f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (details.extension.isNotEmpty()) StatusBadge(details.extension)
            if (details.modifiedTime.isNotEmpty()) Text(details.modifiedTime, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1)
        }
    }
}

@Composable
private fun CompactFileRowContent(file: File, preview: MainFilePreview?, details: BrowserFileDetails, restricted: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FileRowIcon(file, preview?.bitmap, restricted, size = 36.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            FileItemTitle(details.name, restricted, MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            if (file.name != "..") {
                Text(details.metadata(includeDate = true), modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (details.extension.isNotEmpty()) StatusBadge(details.extension)
    }
}

@Composable
private fun GridFileItemContent(file: File, preview: MainFilePreview?, displayName: String, restricted: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(56.dp)
                .alpha(if (file.name.startsWith(".") && file.name != "..") 0.5f else 1f),
            contentAlignment = Alignment.Center
        ) {
            if (preview?.bitmap != null) {
                val isApk = file.extension.equals("apk", true)
                Image(
                    preview.bitmap.asImageBitmap(), null,
                    Modifier.size(56.dp).clip(MaterialTheme.shapes.small).padding(if (isApk) 4.dp else 0.dp),
                    contentScale = if (isApk) ContentScale.Fit else ContentScale.Crop
                )
            } else {
                Icon(
                    painterResource(MainFilePreviewLoader.iconResource(file)),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = if (restricted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
                )
            }
        }
        Text(displayName, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
            color = if (restricted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FileItemTitle(name: String, restricted: Boolean, style: TextStyle, lines: Int = 1) {
    Text(name, style = style, color = if (restricted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        minLines = lines, maxLines = lines, overflow = TextOverflow.MiddleEllipsis)
}

@Composable
internal fun FileRowIcon(file: File, bitmap: android.graphics.Bitmap?, restricted: Boolean, size: Dp = 48.dp) {
    val iconDescription = stringResource(R.string.file_name)
    Box(
        modifier = Modifier.size(size).alpha(
            if (file.name.startsWith(".") && file.name != "..") 0.5f else 1f
        ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            val isApk = file.extension.equals("apk", ignoreCase = true)
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = iconDescription,
                modifier = if (isApk) Modifier.fillMaxSize().padding(6.dp) else Modifier.fillMaxSize(),
                contentScale = if (isApk) ContentScale.Fit else ContentScale.Crop
            )
        } else {
            Icon(
                painter = painterResource(MainFilePreviewLoader.iconResource(file)),
                contentDescription = iconDescription,
                modifier = Modifier.size(size * 0.6f),
                tint = if (restricted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
            )
        }
    }
}

