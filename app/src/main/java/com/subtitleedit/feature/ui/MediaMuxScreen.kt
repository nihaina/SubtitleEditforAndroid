package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaMuxAudioAction
import com.subtitleedit.MediaMuxColumn
import com.subtitleedit.MediaMuxMode
import com.subtitleedit.MediaMuxRow
import com.subtitleedit.MediaMuxUiState
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.AppLogBox
import com.subtitleedit.ui.components.AppPrimaryButton
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.theme.AppSpacing

/** The processing host owns the title/tabs; this page keeps its queue and start action together. */
@Composable
internal fun MediaMuxScreen(
    state: MediaMuxUiState,
    onPickFiles: () -> Unit,
    onPickOutputDirectory: () -> Unit,
    onModeChanged: (MediaMuxMode) -> Unit,
    onAddEmptyRow: () -> Unit,
    onRemove: (String, MediaMuxColumn) -> Unit,
    onRemoveRow: (String) -> Unit,
    onMove: (MediaMuxColumn, Int, Int) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onResolveConflict: (MediaMuxAudioAction?, Boolean) -> Unit,
    onRemoveOutput: (String) -> Unit = {}
) {
    var ordering by rememberSaveable { mutableStateOf<String?>(null) }
    val editable = !state.isRunning && !state.isImporting
    Column(Modifier.fillMaxSize().padding(horizontal = AppSpacing.Page),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
            MediaMuxMode.entries.forEach { mode ->
                FilterChip(selected = state.mode == mode, onClick = { onModeChanged(mode) }, enabled = editable,
                    label = { Text(stringResource(if (mode == MediaMuxMode.MERGE) R.string.media_mux_merge else R.string.media_mux_split)) },
                    modifier = Modifier.weight(1f).testTag("mux-mode-${mode.name.lowercase()}"))
            }
        }
        Text(stringResource(if (state.mode == MediaMuxMode.MERGE) R.string.media_mux_merge_hint else R.string.media_mux_split_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
            OutlinedButton(onClick = onPickFiles, enabled = editable, modifier = Modifier.weight(1f).testTag("mux-import")) {
                Text(stringResource(R.string.media_mux_import))
            }
            OutlinedButton(onClick = onAddEmptyRow, enabled = editable, modifier = Modifier.weight(1f).testTag("mux-add-row")) {
                Text(stringResource(R.string.media_mux_blank_row))
            }
        }
        Column(Modifier.fillMaxWidth().clickable(enabled = editable, role = Role.Button, onClick = onPickOutputDirectory)
            .testTag("mux-output-directory")) {
            Text(stringResource(R.string.media_mux_output_directory), style = MaterialTheme.typography.labelLarge)
            Text(state.outputDirectory.ifBlank { stringResource(R.string.media_mux_output_choose) }, maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MediaMuxColumn.entries.forEach { column ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(column.titleResource), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.media_mux_order), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (editable && state.rows.isNotEmpty()) 1f else .38f),
                        modifier = Modifier.padding(vertical = 6.dp).testTag("mux-order-${column.name.lowercase()}")
                            .clickable(enabled = editable && state.rows.isNotEmpty(), role = Role.Button) { ordering = column.name })
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                Text(stringResource(R.string.media_mux_output), style = MaterialTheme.typography.titleSmall)
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("mux-queue"),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.rows.isEmpty()) item {
                Text(stringResource(R.string.media_mux_list_empty), modifier = Modifier.padding(vertical = AppSpacing.Page),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                MediaMuxQueueRow(row, index, state.mode, editable, onRemove, onRemoveRow, onRemoveOutput)
            }
            if (state.log.isNotBlank()) item {
                AppLogBox(state.log, Modifier.padding(vertical = AppSpacing.Inner), height = 140.dp)
            }
        }
        if (state.isRunning || state.isImporting) AppTaskProgress(true,
            if (state.isImporting) null else state.progress / 100f,
            status = stringResource(if (state.isImporting) R.string.media_mux_importing else R.string.media_mux_running),
            modifier = Modifier.testTag("mux-progress"))
        AppPrimaryButton(text = stringResource(if (state.isRunning) R.string.cancel else R.string.media_mux_start),
            onClick = if (state.isRunning) onCancel else onStart,
            enabled = state.isRunning || state.canProcess,
            modifier = Modifier.padding(bottom = AppSpacing.Inner).testTag("mux-start"))
    }
    val column = MediaMuxColumn.entries.firstOrNull { it.name == ordering }
    if (column != null && editable) MediaMuxOrderDialog(state.rows, column,
        onDismiss = { ordering = null }, onMove = { from, to -> onMove(column, from, to) })
    state.conflict?.let { conflict ->
        var applyToAll by rememberSaveable(conflict.videoName) { mutableStateOf(false) }
        AppDialog(
            onDismissRequest = { onResolveConflict(null, false) },
            title = { Text(stringResource(R.string.media_mux_conflict_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                    Text(stringResource(R.string.media_mux_conflict_message, conflict.videoName))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { applyToAll = !applyToAll }) {
                        Checkbox(applyToAll, onCheckedChange = { applyToAll = it }, modifier = Modifier.testTag("mux-conflict-all"))
                        Text(stringResource(R.string.media_mux_conflict_all), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { onResolveConflict(MediaMuxAudioAction.REPLACE, applyToAll) },
                        modifier = Modifier.testTag("mux-conflict-replace")) { Text(stringResource(R.string.media_mux_conflict_replace)) }
                    TextButton(onClick = { onResolveConflict(MediaMuxAudioAction.ADD, applyToAll) },
                        modifier = Modifier.testTag("mux-conflict-add")) { Text(stringResource(R.string.media_mux_conflict_add)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { onResolveConflict(null, false) }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private val MediaMuxColumn.titleResource: Int get() = if (this == MediaMuxColumn.VIDEO) R.string.media_mux_video else R.string.media_mux_audio

@Composable
private fun MediaMuxQueueRow(row: MediaMuxRow, index: Int, mode: MediaMuxMode, editable: Boolean,
    onRemove: (String, MediaMuxColumn) -> Unit, onRemoveRow: (String) -> Unit, onRemoveOutput: (String) -> Unit) {
    val active = row.eligible(mode)
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else .38f)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
        .testTag("mux-row-${row.id}").padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.media_mux_row, index + 1), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (!active) Text(stringResource(R.string.media_mux_row_skipped), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("mux-skipped-${row.id}"))
            IconButton(onClick = { onRemoveRow(row.id) }, enabled = editable, modifier = Modifier.size(32.dp)
                .testTag("mux-remove-row-${row.id}")) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.media_mux_remove_row), modifier = Modifier.size(16.dp))
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MediaMuxColumn.entries.forEach { column ->
                val file = if (column == MediaMuxColumn.VIDEO) row.video else row.audio
                Column(Modifier.weight(1f).testTag("mux-cell-${row.id}-${column.name.lowercase()}")) {
                    Text(file?.fileName ?: stringResource(R.string.media_mux_empty), color = textColor,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    if (file != null) TextButton(onClick = { onRemove(row.id, column) }, enabled = editable,
                        modifier = Modifier.testTag("mux-remove-${row.id}-${column.name.lowercase()}")) {
                        Text(stringResource(R.string.media_mux_remove, stringResource(column.titleResource)),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Column(Modifier.weight(1f).testTag("mux-output-${row.id}")) {
                Text(row.output?.fileName ?: stringResource(R.string.media_mux_waiting), color = textColor,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                row.output?.let { result ->
                    Text(result.uri.removePrefix("file://"), maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onRemoveOutput(row.id) }, enabled = editable,
                        modifier = Modifier.testTag("mux-remove-${row.id}-output")) {
                        Text(stringResource(R.string.media_mux_remove, stringResource(R.string.media_mux_output)),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
