package com.subtitleedit.feature.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaMuxAudioAction
import com.subtitleedit.MediaMuxColumn
import com.subtitleedit.MediaMuxMode
import com.subtitleedit.MediaMuxRow
import com.subtitleedit.MediaMuxUiState
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppChoiceTile
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.AppLogBox
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.settings.SettingsTileCard
import com.subtitleedit.ui.settings.SettingsTileGroup
import com.subtitleedit.ui.settings.SettingsTileRow
import com.subtitleedit.ui.settings.SettingsTileSection
import com.subtitleedit.ui.settings.settingsChipColor
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.settings.settingsTileColor
import com.subtitleedit.ui.settings.settingsPrimaryTextColor
import com.subtitleedit.ui.settings.settingsSecondaryTextColor
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
    val pageBackground = settingsPageBackground()
    val queuedFiles = state.rows.sumOf { (if (it.video != null) 1 else 0) + (if (it.audio != null) 1 else 0) }

    AppToolScaffold(
        title = stringResource(R.string.media_processing_mux_tab),
        onBack = {},
        showTopBar = false,
        contentBackground = pageBackground,
        topBarContainerColor = pageBackground,
        topBarScrolledContainerColor = pageBackground,
        bottomBar = {
            Box(Modifier.background(pageBackground)) {
                Button(
                    onClick = if (state.isRunning) onCancel else onStart,
                    enabled = state.isRunning || state.canProcess,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
                        .height(56.dp)
                        .testTag("mux-start")
                ) {
                    Text(
                        stringResource(if (state.isRunning) R.string.cancel else R.string.media_mux_start),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    ) {
        // ── Processing mode ──
        SettingsTileSection(title = stringResource(R.string.media_mux_mode)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
            ) {
                MediaMuxMode.entries.forEach { mode ->
                    AppChoiceTile(
                        text = stringResource(
                            if (mode == MediaMuxMode.MERGE) R.string.media_mux_merge else R.string.media_mux_split
                        ),
                        selected = state.mode == mode,
                        enabled = editable,
                        singleLine = true,
                        onClick = { onModeChanged(mode) },
                        modifier = Modifier.weight(1f).testTag("mux-mode-${mode.name.lowercase()}")
                    )
                }
            }
            Text(
                stringResource(
                    if (state.mode == MediaMuxMode.MERGE) R.string.media_mux_merge_hint else R.string.media_mux_split_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = settingsSecondaryTextColor()
            )
        }

        // ── Queue input and output directory ──
        SettingsTileGroup {
            SettingsTileRow(
                title = stringResource(R.string.media_mux_import),
                iconRes = R.drawable.ic_file_video,
                value = if (queuedFiles == 0) {
                    stringResource(R.string.media_mux_import_none)
                } else {
                    stringResource(R.string.media_mux_import_count, queuedFiles)
                },
                valueBelow = true,
                enabled = editable,
                modifier = Modifier.testTag("mux-import"),
                onClick = onPickFiles
            )
            SettingsTileRow(
                title = stringResource(R.string.media_mux_output_directory),
                iconRes = R.drawable.ic_folder,
                value = state.outputDirectory.ifBlank { stringResource(R.string.media_mux_output_choose) },
                valueBelow = true,
                enabled = editable,
                modifier = Modifier.testTag("mux-output-directory"),
                onClick = onPickOutputDirectory
            )
        }

        // ── Queue ──
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
        ) {
            MediaMuxQueueHeader(
                rowCount = state.rows.size,
                outputCount = state.rows.count { it.output != null },
                orderEnabled = editable && state.rows.isNotEmpty(),
                addEnabled = editable,
                onAddEmptyRow = onAddEmptyRow,
                onOrder = { ordering = it.name }
            )
            // The page owns vertical scrolling, so the queue keeps a bounded height instead of
            // competing for the leftover space and collapsing on short screens.
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 420.dp).testTag("mux-queue"),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
            ) {
                if (state.rows.isEmpty()) item { MediaMuxEmptyState() }
                itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                    MediaMuxQueueRow(row, index, state.mode, editable, onRemove, onRemoveRow, onRemoveOutput)
                }
            }
        }

        // ── Progress & Log ──
        if (state.isRunning || state.isImporting) {
            SettingsTileCard(modifier = Modifier.testTag("mux-progress")) {
                AppTaskProgress(
                    true,
                    if (state.isImporting) null else state.progress / 100f,
                    status = stringResource(
                        if (state.isImporting) R.string.media_mux_importing else R.string.media_mux_running
                    )
                )
            }
        }
        if (state.log.isNotBlank()) {
            SettingsTileSection(title = stringResource(R.string.activity_settings_text_06)) {
                AppLogBox(state.log, height = 140.dp)
            }
        }
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
                        modifier = Modifier.fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(role = Role.Checkbox) { applyToAll = !applyToAll }
                            .padding(horizontal = 4.dp, vertical = 2.dp)) {
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

/** Column captions of the queue stay above it, so the three columns read as one table. */
@Composable
private fun MediaMuxQueueHeader(
    rowCount: Int,
    outputCount: Int,
    orderEnabled: Boolean,
    addEnabled: Boolean,
    onAddEmptyRow: () -> Unit,
    onOrder: (MediaMuxColumn) -> Unit
) {
    SettingsTileCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.media_mux_queue), style = MaterialTheme.typography.titleMedium,
                color = settingsPrimaryTextColor(), modifier = Modifier.weight(1f))
            if (rowCount > 0) Text(stringResource(R.string.media_mux_queue_count, rowCount),
                style = MaterialTheme.typography.labelMedium, color = settingsSecondaryTextColor(),
                modifier = Modifier.clip(CircleShape).background(settingsChipColor()).padding(horizontal = 10.dp, vertical = 4.dp))
            IconButton(onClick = onAddEmptyRow, enabled = addEnabled,
                modifier = Modifier.size(32.dp).testTag("mux-add-row")) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.media_mux_blank_row),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = if (addEnabled) 1f else .4f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MediaMuxColumn.entries.forEach { column ->
                Column(Modifier.weight(1f)) {
                    MediaMuxColumnLabel(
                        iconRes = if (column == MediaMuxColumn.VIDEO) R.drawable.ic_file_video else R.drawable.ic_file_audio,
                        text = stringResource(column.titleResource)
                    )
                    MediaMuxOrderLink(enabled = orderEnabled,
                        modifier = Modifier.testTag("mux-order-${column.name.lowercase()}"),
                        onClick = { onOrder(column) })
                }
            }
            Column(Modifier.weight(1f)) {
                MediaMuxColumnLabel(iconRes = R.drawable.ic_media_convert, text = stringResource(R.string.media_mux_output))
                Text(
                    if (outputCount > 0) {
                        stringResource(R.string.media_mux_output_count, outputCount)
                    } else {
                        stringResource(R.string.media_mux_output_none)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = settingsSecondaryTextColor().copy(alpha = if (outputCount > 0) 1f else .55f),
                    modifier = Modifier.padding(vertical = 5.dp, horizontal = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun MediaMuxColumnLabel(iconRes: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(14.dp),
            tint = settingsSecondaryTextColor())
        Text(text, style = MaterialTheme.typography.labelLarge, color = settingsPrimaryTextColor(),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MediaMuxOrderLink(enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .alpha(if (enabled) 1f else .4f)
            .clickable(enabled = enabled, interactionSource = source, indication = LocalIndication.current,
                role = Role.Button, onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(painterResource(R.drawable.ic_sort_direction), contentDescription = null, modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.media_mux_order), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

@Composable
private fun MediaMuxEmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = AppSpacing.Inner),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
    ) {
        Icon(painterResource(R.drawable.ic_media_convert), contentDescription = null, modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f))
        Text(stringResource(R.string.media_mux_list_empty), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text(stringResource(R.string.media_mux_output_hint), style = MaterialTheme.typography.bodySmall,
            color = settingsSecondaryTextColor(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun MediaMuxQueueRow(row: MediaMuxRow, index: Int, mode: MediaMuxMode, editable: Boolean,
    onRemove: (String, MediaMuxColumn) -> Unit, onRemoveRow: (String) -> Unit, onRemoveOutput: (String) -> Unit) {
    val active = row.eligible(mode)
    val dim = if (active) 1f else .45f
    Column(Modifier.fillMaxWidth()
        .clip(MaterialTheme.shapes.large)
        .background(settingsTileColor())
        .testTag("mux-row-${row.id}")
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val rowLabel = stringResource(R.string.media_mux_row, index + 1)
            Box(Modifier.size(22.dp).clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.primaryContainer else settingsChipColor())
                .semantics { contentDescription = rowLabel },
                contentAlignment = Alignment.Center) {
                Text("${index + 1}", style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else settingsSecondaryTextColor())
            }
            if (!active) Text(stringResource(R.string.media_mux_row_skipped), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(CircleShape).background(settingsChipColor())
                    .padding(horizontal = 8.dp, vertical = 3.dp).testTag("mux-skipped-${row.id}"))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onRemoveRow(row.id) }, enabled = editable, modifier = Modifier.size(32.dp)
                .testTag("mux-remove-row-${row.id}")) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.media_mux_remove_row),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = if (editable) 1f else .4f))
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MediaMuxColumn.entries.forEach { column ->
                val file = if (column == MediaMuxColumn.VIDEO) row.video else row.audio
                MediaMuxCell(
                    name = file?.fileName,
                    placeholder = stringResource(R.string.media_mux_empty),
                    dim = dim,
                    removeLabel = file?.let { stringResource(R.string.media_mux_remove, stringResource(column.titleResource)) },
                    removeTag = "mux-remove-${row.id}-${column.name.lowercase()}",
                    removeEnabled = editable,
                    onRemove = file?.let { { onRemove(row.id, column) } },
                    modifier = Modifier.weight(1f).testTag("mux-cell-${row.id}-${column.name.lowercase()}")
                )
            }
            MediaMuxCell(
                name = row.output?.fileName,
                placeholder = stringResource(R.string.media_mux_waiting),
                detail = row.output?.uri,
                dim = dim,
                removeLabel = row.output?.let { stringResource(R.string.media_mux_remove, stringResource(R.string.media_mux_output)) },
                removeTag = "mux-remove-${row.id}-output",
                removeEnabled = editable,
                onRemove = row.output?.let { { onRemoveOutput(row.id) } },
                modifier = Modifier.weight(1f).testTag("mux-output-${row.id}")
            )
        }
    }
}

/** One queue column: an inset capsule holding the file name, optional path and its remove action. */
@Composable
private fun MediaMuxCell(
    name: String?,
    placeholder: String,
    dim: Float,
    removeLabel: String?,
    removeTag: String,
    removeEnabled: Boolean,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    Column(modifier = modifier
        .clip(MaterialTheme.shapes.medium)
        .background(settingsChipColor())
        .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name ?: placeholder, style = MaterialTheme.typography.bodySmall, maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = if (name == null) settingsSecondaryTextColor().copy(alpha = dim * .8f)
            else settingsPrimaryTextColor().copy(alpha = dim))
        if (detail != null) Text(detail.removePrefix("file://"), style = MaterialTheme.typography.labelSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = settingsSecondaryTextColor().copy(alpha = dim))
        if (removeLabel != null && onRemove != null) {
            MediaMuxCellAction(removeLabel, removeEnabled, Modifier.testTag(removeTag), onRemove)
        }
    }
}

@Composable
private fun MediaMuxCellAction(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(modifier = modifier
        .padding(top = 2.dp)
        .clip(CircleShape)
        .alpha(if (enabled) 1f else .4f)
        .clickable(enabled = enabled, interactionSource = source, indication = LocalIndication.current,
            role = Role.Button, onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(11.dp),
            tint = MaterialTheme.colorScheme.error)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
    }
}
