package com.subtitleedit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.feature.ui.BrowserFileRow
import com.subtitleedit.ui.components.AppAlertDialog
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
    Box(Modifier.fillMaxSize()) {
        if (directories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_favorite_directories),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 96.dp)
            ) {
                items(directories, key = { it.absolutePath }) { directory ->
                    BrowserFileRow(
                        file = directory,
                        relativePathRoot = null,
                        isSelected = false,
                        isSelectionMode = false,
                        onClick = { onOpenDirectory(directory) },
                        onLongClick = { onRequestRemoval(directory) },
                        restrictedOverride = false
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = onAddDirectory,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.add_favorite_directory),
                modifier = Modifier.size(24.dp)
            )
        }
    }

    pendingRemoval?.let { directory ->
        AppAlertDialog(
            title = stringResource(R.string.remove_favorite_directory),
            message = stringResource(R.string.remove_favorite_directory_confirm, directory.name),
            confirmText = stringResource(R.string.confirm),
            dismissText = stringResource(R.string.cancel),
            onConfirm = onConfirmRemoval,
            onDismiss = onDismissRemoval
        )
    }
}
