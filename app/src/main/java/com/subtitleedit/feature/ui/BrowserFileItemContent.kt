package com.subtitleedit.feature.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.ui.components.StatusBadge
import com.subtitleedit.ui.theme.AppMotion
import com.subtitleedit.util.FileUtils
import java.text.SimpleDateFormat
import java.util.Date

/** Metadata from a filesystem or archive index; rendering never reads a File. */
internal data class BrowserFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long = -1L,
    val modifiedTimeMillis: Long? = null,
    val itemCount: Int? = null,
    val displayName: String = name,
    val isParentDirectory: Boolean = false
)

private data class BrowserFileDetails(
    val name: String,
    val summary: String,
    val duration: String,
    val modifiedTime: String,
    val extension: String
)

@Composable
internal fun BrowserFileItem(
    entry: BrowserFileEntry,
    viewMode: FileBrowserViewMode,
    modifier: Modifier = Modifier,
    interactionModifier: Modifier = Modifier,
    preview: MainFilePreview? = null,
    loadingDuration: Boolean = false,
    restricted: Boolean = false,
    isSelected: Boolean = false,
    alpha: Float = 1f
) {
    val locale = LocalLocale.current.platformLocale
    val modifiedTime = remember(entry.modifiedTimeMillis, locale) {
        entry.modifiedTimeMillis?.let { SimpleDateFormat("yyyy-MM-dd", locale).format(Date(it)) }.orEmpty()
    }
    val count = entry.itemCount
    val summary = when {
        entry.isParentDirectory -> ""
        !entry.isDirectory && entry.size >= 0L -> FileUtils.formatFileSize(entry.size)
        !entry.isDirectory -> stringResource(R.string.file_size_unknown)
        count == null || count < 0 -> ""
        count == 0 -> stringResource(R.string.directory_empty)
        else -> stringResource(R.string.directory_item_count, count)
    }
    val details = BrowserFileDetails(
        name = entry.displayName,
        summary = summary,
        duration = preview?.mediaDuration.orEmpty(),
        modifiedTime = if (entry.isParentDirectory) "" else modifiedTime,
        extension = if (entry.isDirectory) "" else entry.name.substringAfterLast('.', "").uppercase(locale)
    )
    Box(modifier.browserFileItemContainer(viewMode, isSelected, alpha).then(interactionModifier)) {
        when (viewMode) {
            FileBrowserViewMode.DETAILED -> DetailedFileRowContent(entry, preview, details, restricted, loadingDuration)
            FileBrowserViewMode.COMPACT -> CompactFileRowContent(entry, preview, details, restricted)
            FileBrowserViewMode.GRID -> GridFileItemContent(entry, preview, details, restricted)
        }
    }
}

@Composable
private fun Modifier.browserFileItemContainer(
    viewMode: FileBrowserViewMode = FileBrowserViewMode.DETAILED,
    isSelected: Boolean = false,
    alpha: Float = 1f
): Modifier {
    val containerColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            viewMode == FileBrowserViewMode.GRID -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> Color.Transparent
        },
        animationSpec = AppMotion.state(),
        label = "file-row-container"
    )
    val itemShape = RoundedCornerShape(when (viewMode) {
        FileBrowserViewMode.DETAILED -> 12.dp
        FileBrowserViewMode.COMPACT -> 8.dp
        FileBrowserViewMode.GRID -> 16.dp
    })
    return fillMaxWidth()
        .padding(horizontal = if (viewMode == FileBrowserViewMode.GRID) 0.dp else 8.dp)
        .alpha(alpha)
        .clip(itemShape)
        .background(containerColor)
}

@Composable
private fun DetailedFileRowContent(
    entry: BrowserFileEntry,
    preview: MainFilePreview?,
    details: BrowserFileDetails,
    restricted: Boolean,
    loadingDuration: Boolean
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BrowserFileIcon(entry.name, entry.isDirectory, 42.dp, preview?.bitmap, restricted)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            FileItemTitle(details.name, restricted, MaterialTheme.typography.bodyLarge)
            if (!entry.isParentDirectory) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(details.summary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 1, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (loadingDuration || details.duration.isNotEmpty()) {
                        Text(if (loadingDuration) "00:00" else details.duration,
                            modifier = Modifier.padding(start = 12.dp).width(72.dp).alpha(if (loadingDuration) 0f else 1f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1)
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (details.extension.isNotEmpty()) StatusBadge(details.extension)
            else if (entry.isDirectory && !entry.isParentDirectory) Spacer(Modifier.height(24.dp))
            if (details.modifiedTime.isNotEmpty()) {
                Text(details.modifiedTime, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BrowserFileIcon(
    name: String,
    isDirectory: Boolean,
    size: Dp,
    bitmap: Bitmap? = null,
    restricted: Boolean = false
) {
    Box(
        modifier = Modifier.size(size).alpha(if (name.startsWith(".") && name != "..") 0.5f else 1f),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null && !isDirectory) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.file_name),
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.15f)),
                contentScale = if (name.endsWith(".apk", ignoreCase = true)) ContentScale.Fit else ContentScale.Crop
            )
        } else {
            Icon(
                painter = painterResource(MainFilePreviewLoader.iconResource(name, isDirectory)),
                contentDescription = stringResource(R.string.file_name),
                modifier = Modifier.fillMaxSize(),
                tint = if (restricted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
            )
        }
    }
}

@Composable
private fun FileItemTitle(name: String, restricted: Boolean, style: TextStyle, lines: Int = 1) {
    Text(name, style = style, color = if (restricted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        minLines = lines, maxLines = lines,
        overflow = if (lines > 1) TextOverflow.Ellipsis else TextOverflow.MiddleEllipsis)
}

@Composable
private fun CompactFileRowContent(entry: BrowserFileEntry, preview: MainFilePreview?, details: BrowserFileDetails, restricted: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BrowserFileIcon(entry.name, entry.isDirectory, 30.dp, preview?.bitmap, restricted)
        Box(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
            FileItemTitle(details.name, restricted, MaterialTheme.typography.bodyMedium)
        }
        if (details.summary.isNotEmpty()) {
            Text(details.summary, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1)
            Spacer(Modifier.width(6.dp))
        }
        if (details.extension.isNotEmpty()) StatusBadge(details.extension)
    }
}

@Composable
private fun GridFileItemContent(entry: BrowserFileEntry, preview: MainFilePreview?, details: BrowserFileDetails, restricted: Boolean) {
    Column(Modifier.fillMaxWidth().padding(4.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (preview?.bitmap != null && !entry.name.endsWith(".apk", true)) {
                Image(preview.bitmap.asImageBitmap(), null,
                    Modifier.fillMaxSize().alpha(if (entry.name.startsWith(".")) 0.5f else 1f), contentScale = ContentScale.Crop)
            } else {
                BrowserFilePreviewIcon(entry.name, entry.isDirectory, preview?.bitmap, restricted, size = 64.dp)
            }
            GridFileMetadata(
                left = details.modifiedTime,
                right = details.extension,
                modifier = Modifier.align(Alignment.TopStart)
            )
            GridFileMetadata(
                left = if (entry.isDirectory) "" else details.summary,
                right = if (entry.isDirectory) details.summary else details.duration,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }
        Spacer(Modifier.height(4.dp))
        FileItemTitle(details.name, restricted, MaterialTheme.typography.bodySmall, lines = 2)
    }
}

@Composable
private fun GridFileMetadata(left: String, right: String, modifier: Modifier = Modifier) {
    if (left.isEmpty() && right.isEmpty()) return
    val textStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp)
    Row(
        modifier = modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(left, modifier = Modifier.weight(1f), style = textStyle, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (right.isNotEmpty()) {
            Text(right, style = textStyle, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Preserves the icon scale and APK padding used by the grid and rename rows. */
@Composable
internal fun BrowserFilePreviewIcon(
    name: String,
    isDirectory: Boolean,
    bitmap: Bitmap?,
    restricted: Boolean,
    size: Dp = 48.dp
) {
    val isApk = name.endsWith(".apk", ignoreCase = true)
    Box(
        modifier = Modifier.size(size).alpha(if (name.startsWith(".") && name != "..") 0.5f else 1f),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.file_name),
                modifier = if (isApk) Modifier.fillMaxSize().padding(6.dp) else Modifier.fillMaxSize(),
                contentScale = if (isApk) ContentScale.Fit else ContentScale.Crop
            )
        } else {
            Icon(
                painter = painterResource(MainFilePreviewLoader.iconResource(name, isDirectory)),
                contentDescription = stringResource(R.string.file_name),
                modifier = Modifier.size(size * 0.6f),
                tint = if (restricted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
            )
        }
    }
}
