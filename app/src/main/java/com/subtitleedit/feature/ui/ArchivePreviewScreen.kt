package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.model.ArchivePreviewItem
import com.subtitleedit.model.FileBrowserViewMode
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ArchivePreviewScreen(
    archiveName: String,
    currentDirectory: String,
    entryCount: Int?,
    items: List<ArchivePreviewItem>,
    isLoading: Boolean,
    errorMessage: String?,
    onNavigateBack: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    viewMode: FileBrowserViewMode = FileBrowserViewMode.DETAILED
) {
    val pathScrollState = rememberScrollState()
    LaunchedEffect(currentDirectory) {
        snapshotFlow { pathScrollState.maxValue }.collectLatest { maxValue ->
            pathScrollState.scrollTo(maxValue)
        }
    }

    AppToolScaffold(
        title = archiveName,
        onBack = onNavigateBack,
        scrollable = false,
        contentPadding = PaddingValues(0.dp),
        titleContent = {
            Column {
                Text(text = archiveName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                entryCount?.let {
                    Text(
                        text = stringResource(R.string.archive_entry_count, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                style = MaterialTheme.typography.bodyMedium,
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
                        text = stringResource(R.string.archive_preview_folder_empty),
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else -> BrowserFileLayout(
                        items = items,
                        key = ArchivePreviewItem::path,
                        viewMode = viewMode,
                        modifier = Modifier.fillMaxSize()
                    ) { item ->
                        BrowserFileItem(
                            entry = BrowserFileEntry(
                                name = item.name,
                                isDirectory = item.isDirectory,
                                size = item.size,
                                modifiedTimeMillis = item.modifiedTimeMillis.takeIf { it > 0L },
                                itemCount = item.itemCount
                            ),
                            viewMode = viewMode,
                            interactionModifier = if (item.isDirectory) {
                                Modifier.clickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(R.string.open_folder),
                                    onClick = { onOpenDirectory(item.path) }
                                )
                            } else {
                                Modifier
                            }
                        )
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
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
}
