package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** Compact file details shared by the rename preview and its reorder dialog. */
@Composable
internal fun RenameFileRow(
    file: File,
    modifier: Modifier = Modifier,
    displayName: String = file.name,
    metadataRefreshKey: Any? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val metadata = rememberBrowserFileMetadata(file, metadataRefreshKey = metadataRefreshKey)
    val isDirectory = metadata?.isDirectory == true
    val modified = metadata?.modifiedTimeMillis
    val size = metadata?.size ?: -1L
    val previewKey = metadata?.cacheKey
    val restricted = metadata?.restricted == true
    val targetSize = remember(context) { (48 * context.resources.displayMetrics.density + 0.5f).toInt() }
    var preview by remember(previewKey) { mutableStateOf<MainFilePreview?>(null) }
    var directoryCount by remember(previewKey) { mutableStateOf<Int?>(null) }
    LaunchedEffect(previewKey) {
        val snapshot = metadata ?: return@LaunchedEffect
        preview = MainFilePreviewLoader.preview(context, snapshot, targetSize)
        if (isDirectory && !restricted) {
            directoryCount = MainFilePreviewLoader.directoryItemCount(snapshot)
        }
    }
    val modifiedTime = remember(modified, locale) {
        modified?.let { SimpleDateFormat("yy-MM-dd HH:mm", locale).format(Date(it)) }.orEmpty()
    }
    val sizeOrCount = if (isDirectory) {
        when (val count = directoryCount) {
            null, -1 -> ""
            0 -> stringResource(R.string.directory_empty)
            else -> stringResource(R.string.directory_item_count, count)
        }
    } else {
        if (size >= 0L) FileUtils.formatFileSize(size) else ""
    }
    val details = listOf(modifiedTime, sizeOrCount, preview?.mediaDuration.orEmpty())
        .filter(String::isNotEmpty).joinToString("  ")

    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 68.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileRowIcon(file, isDirectory, preview?.bitmap, restricted)
        Column(
            modifier = Modifier.weight(1f).padding(start = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailingContent?.let { trailing ->
            Box(Modifier.padding(start = 4.dp)) { trailing() }
        }
    }
}
