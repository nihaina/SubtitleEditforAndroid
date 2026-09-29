package com.subtitleedit.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import java.io.File

@Composable
fun FavoritesScreen(
    directories: List<File>,
    pendingRemoval: File?,
    onAddDirectory: () -> Unit,
    onOpenDirectory: (File) -> Unit,
    onRequestRemoval: (File) -> Unit,
    onConfirmRemoval: () -> Unit,
    onDismissRemoval: () -> Unit
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddDirectory) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_favorite_directory),
                    contentDescription = stringResource(R.string.add_favorite_directory)
                )
            }
        }
    ) { padding ->
        if (directories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_favorite_directories),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 88.dp)
            ) {
                items(directories, key = { it.absolutePath }) { directory ->
                    ListItem(
                        headlineContent = { Text(directory.name.ifEmpty { directory.absolutePath }) },
                        supportingContent = {
                            Text(directory.absolutePath, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = { onOpenDirectory(directory) },
                            onLongClick = { onRequestRemoval(directory) }
                        )
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    pendingRemoval?.let { directory ->
        AlertDialog(
            onDismissRequest = onDismissRemoval,
            title = { Text(stringResource(R.string.remove_favorite_directory)) },
            text = { Text(stringResource(R.string.remove_favorite_directory_confirm, directory.name)) },
            confirmButton = {
                TextButton(onClick = onConfirmRemoval) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissRemoval) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
