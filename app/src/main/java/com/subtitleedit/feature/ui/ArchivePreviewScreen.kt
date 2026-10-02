package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                modifier = Modifier.height(56.dp),
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
            Text(
                text = buildString {
                    append(archiveName)
                    if (currentDirectory.isNotEmpty()) append(" / ").append(currentDirectory)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .horizontalScroll(pathScrollState)
                    .padding(12.dp),
                maxLines = 1,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    errorMessage != null -> PreviewEmptyState(
                        text = errorMessage,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    items.isEmpty() -> PreviewEmptyState(
                        text = "此文件夹为空",
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
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
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 24.dp),
        fontSize = 16.sp,
        color = MaterialTheme.colorScheme.onBackground
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
            .padding(4.dp)
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
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = stringResource(R.string.file_name),
                modifier = Modifier
                    .size(48.dp)
                    .padding(8.dp)
            )

            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
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
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                if (suffix.isNotEmpty()) {
                    Text(
                        text = suffix,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.extraSmall
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                modifiedDate?.let { date ->
                    Text(
                        text = date,
                        modifier = Modifier.padding(top = if (suffix.isNotEmpty()) 5.dp else 0.dp),
                        fontSize = 11.sp,
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
