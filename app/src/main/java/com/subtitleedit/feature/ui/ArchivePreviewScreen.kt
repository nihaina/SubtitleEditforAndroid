package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.model.ArchivePreviewItem
import com.subtitleedit.util.ArchiveManager
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivePreviewScreen(
    archiveName: String,
    currentDirectory: String,
    entryCount: Int?,
    items: List<ArchivePreviewItem>,
    isLoading: Boolean,
    errorMessage: String?,
    onNavigateBack: () -> Unit,
    onOpenDirectory: (String) -> Unit
) {
    val pathScrollState = rememberScrollState()
    LaunchedEffect(currentDirectory) {
        snapshotFlow { pathScrollState.maxValue }.collectLatest { maxValue ->
            pathScrollState.scrollTo(maxValue)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Image(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "返回"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = archiveName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        entryCount?.let {
                            Text(
                                text = "${it} 项",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .horizontalScroll(pathScrollState)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = archiveName,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (currentDirectory.isNotEmpty()) {
                    Text(
                        text = " / $currentDirectory",
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    errorMessage != null -> PreviewEmptyState(
                        text = errorMessage,
                        isError = true,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    items.isEmpty() -> PreviewEmptyState(
                        text = "此文件夹为空",
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items, key = ArchivePreviewItem::path) { item ->
                            ArchivePreviewRow(item = item) {
                                onOpenDirectory(item.path)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewEmptyState(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 24.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ArchivePreviewRow(
    item: ArchivePreviewItem,
    onOpenDirectory: () -> Unit
) {
    val file = remember(item.name) { File(item.name) }
    val icon = remember(item.name, item.isDirectory) { iconFor(item, file) }
    val modifiedDate = remember(item.modifiedTimeMillis) {
        if (item.modifiedTimeMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(item.modifiedTimeMillis))
        } else {
            null
        }
    }
    val suffix = remember(item.name, item.isDirectory) {
        if (item.isDirectory) "" else item.name.substringAfterLast('.', "").uppercase()
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (item.isDirectory) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = "打开文件夹",
                        onClick = onOpenDirectory
                    )
                } else {
                    Modifier
                }
            ),
        shape = MaterialTheme.shapes.small,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = item.name,
                modifier = Modifier
                    .size(48.dp)
                    .padding(8.dp)
            )

            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        item.isDirectory && item.itemCount == 0 -> stringResource(R.string.directory_empty)
                        item.isDirectory -> stringResource(R.string.directory_item_count, item.itemCount)
                        item.size >= 0L -> FileUtils.formatFileSize(item.size)
                        else -> "大小未知"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                if (suffix.isNotEmpty()) {
                    Text(
                        text = suffix,
                        modifier = Modifier
                            .widthIn(max = 88.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.extraSmall
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                modifiedDate?.let { date ->
                    Text(
                        text = date,
                        modifier = Modifier.padding(top = if (suffix.isNotEmpty()) 5.dp else 0.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun iconFor(item: ArchivePreviewItem, file: File): Int {
    if (item.isDirectory) return R.drawable.ic_folder
    val suffix = file.extension.lowercase()
    return when {
        FileUtils.isAudioFile(file) -> R.drawable.ic_file_audio
        FileUtils.isSubtitleFile(file) || FileTypePolicy.isText(file) -> R.drawable.ic_file_text
        suffix in VIDEO_EXTENSIONS -> R.drawable.ic_file_video
        suffix in ArchiveManager.recognizedExtensions -> R.drawable.ic_file_archive
        else -> R.drawable.ic_file
    }
}

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v")
