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
import com.subtitleedit.util.AndroidDirectoryPolicy
import com.subtitleedit.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** Compact file details shared by the rename preview and its reorder dialog. */
@Composable
internal fun RenameFileRow(
    file: File,
    modifier: Modifier = Modifier,
    displayName: String = file.name,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val isDirectory = file.isDirectory
    val modified = file.lastModified()
    val size = file.length()
    val previewKey = "${file.absolutePath}:$size:$modified"
    val restricted = remember(file.absolutePath) { AndroidDirectoryPolicy.isRestricted(file) }
    val targetSize = remember(context) { (48 * context.resources.displayMetrics.density + 0.5f).toInt() }
    var preview by remember(previewKey) { mutableStateOf<MainFilePreview?>(null) }
    var directoryCount by remember(previewKey) { mutableStateOf<Int?>(null) }
    LaunchedEffect(previewKey) {
        preview = withContext(Dispatchers.IO) { MainFilePreviewLoader.preview(context, file, targetSize) }
        if (isDirectory && !restricted) {
            directoryCount = withContext(Dispatchers.IO) { MainFilePreviewLoader.directoryItemCount(file) }
        }
    }
    val modifiedTime = remember(modified, locale) {
        SimpleDateFormat("yy-MM-dd HH:mm", locale).format(Date(modified))
    }
    val sizeOrCount = if (isDirectory) {
        when (val count = directoryCount) {
            null, -1 -> ""
            0 -> stringResource(R.string.directory_empty)
            else -> stringResource(R.string.directory_item_count, count)
        }
    } else {
        FileUtils.formatFileSize(size)
    }
    val details = listOf(modifiedTime, sizeOrCount, preview?.mediaDuration.orEmpty())
        .filter(String::isNotEmpty).joinToString("  ")

    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 68.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileRowIcon(file, preview?.bitmap, restricted)
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
