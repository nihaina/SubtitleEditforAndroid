package com.subtitleedit.feature.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.util.BatchRename
import com.subtitleedit.util.BatchRenameHistory

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BatchRenameHistoryDialog(
    entries: List<BatchRenameHistory.Entry>,
    onDismiss: () -> Unit,
    onSelect: (BatchRename.Options) -> Unit,
    onDelete: (String) -> Unit
) {
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val tokenColor = MaterialTheme.colorScheme.primary
    val fileNameLabel = stringResource(R.string.batch_rename_file_name)
    val numberLabel = stringResource(R.string.batch_rename_number)
    val transformation = remember(tokenColor, fileNameLabel, numberLabel) {
        RenamePatternTransformation(tokenColor, fileNameLabel, numberLabel)
    }
    val deleteLabel = stringResource(R.string.batch_rename_history_delete_title)
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_rename_history_title)) },
        text = {
            if (entries.isEmpty()) {
                Text(stringResource(R.string.batch_rename_history_empty))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        val options = entry.options
                        Column(
                            Modifier.fillMaxWidth()
                                .combinedClickable(
                                    role = Role.Button,
                                    onLongClickLabel = deleteLabel,
                                    onLongClick = { deletingId = entry.id },
                                    onClick = { onSelect(options) }
                                )
                                .padding(horizontal = 4.dp, vertical = 14.dp)
                        ) {
                            Text(
                                transformation.filter(AnnotatedString(options.pattern)).text,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            val extension = when (options.extension) {
                                null -> stringResource(R.string.batch_rename_history_keep_extension)
                                "" -> stringResource(R.string.batch_rename_history_no_extension)
                                else -> ".${options.extension}"
                            }
                            Text(
                                "$extension · " + stringResource(
                                    R.string.batch_rename_history_numbering,
                                    options.numberWidth,
                                    options.startNumber
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (options.find.isNotEmpty()) {
                                val regex = if (options.regexEnabled) " · " + stringResource(R.string.batch_rename_regex) else ""
                                Text(
                                    "${options.find} → ${options.replacement}$regex",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
    entries.firstOrNull { it.id == deletingId }?.let { entry ->
        AppDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(deleteLabel) },
            text = { Text(stringResource(R.string.batch_rename_history_delete_message)) },
            confirmButton = {
                TextButton(onClick = { onDelete(entry.id); deletingId = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
