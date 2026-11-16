package com.subtitleedit.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.player.formatPlaybackTime
import com.subtitleedit.ui.theme.AppMotion
import java.io.File

/** A non-modal selection panel so the file list remains available for selection. */
@Composable
internal fun MediaLibrarySelectionPanel(
    selectedFiles: List<File>,
    totalDurationMs: Long,
    onRemove: (String) -> Unit,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShowInfo: () -> Unit,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectRange: () -> Unit,
    onInvertSelection: () -> Unit,
    modifier: Modifier = Modifier,
    favoriteActionLabelRes: Int = R.string.player_action_favorite,
    onRemoveSelection: (() -> Unit)? = null,
    removeSelectionLabelRes: Int = R.string.player_queue_remove_selected,
    showDuration: Boolean = true,
    onOptionsRequested: (() -> Unit)? = null
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var optionsExpanded by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("library-selection-panel"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp
    ) {
        Column(Modifier.animateContentSize(AppMotion.state()).padding(vertical = 4.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Row(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.media_lib_files_selected, selectedFiles.size),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (showDuration) {
                        Icon(
                            painterResource(R.drawable.ic_schedule),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatPlaybackTime(totalDurationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .testTag("library-selection-expand")
                ) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_right),
                        contentDescription = stringResource(
                            if (expanded) R.string.media_lib_selection_collapse
                            else R.string.media_lib_selection_expand
                        ),
                        modifier = Modifier.rotate(if (expanded) 90f else -90f)
                    )
                }
            }
            if (expanded) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .heightIn(max = 200.dp).testTag("library-selection-files")
                ) {
                    items(selectedFiles, key = { it.absolutePath }) { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                                modifier = Modifier.weight(1f)
                            )
                            val removeLabel = stringResource(R.string.media_lib_selection_remove, file.name)
                            IconButton(
                                onClick = { onRemove(file.absolutePath) },
                                modifier = Modifier.testTag("library-selection-remove:${file.absolutePath}")
                                    .semantics { contentDescription = removeLabel }
                            ) {
                                Box(
                                    modifier = Modifier.size(18.dp).border(
                                        1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape
                                    ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier.width(8.dp).height(1.5.dp)
                                            .background(MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Box(Modifier.weight(1f)) {
                    SelectionPanelAction(
                        label = stringResource(R.string.media_lib_files_options),
                        icon = R.drawable.ic_more,
                        onClick = {
                            advancedExpanded = false
                            if (onOptionsRequested != null) onOptionsRequested() else optionsExpanded = true
                        },
                        modifier = Modifier.testTag("library-selection-options")
                    )
                    DropdownMenu(
                        expanded = optionsExpanded,
                        onDismissRequest = { optionsExpanded = false }
                    ) {
                        if (onRemoveSelection != null) DropdownMenuItem(
                            text = { Text(stringResource(removeSelectionLabelRes)) },
                            enabled = selectedFiles.isNotEmpty(),
                            onClick = { optionsExpanded = false; onRemoveSelection() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(favoriteActionLabelRes)) },
                            enabled = onRemoveSelection == null || selectedFiles.isNotEmpty(),
                            onClick = { optionsExpanded = false; onFavorite() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.player_action_add_to_playlist)) },
                            enabled = onRemoveSelection == null || selectedFiles.isNotEmpty(),
                            onClick = { optionsExpanded = false; onAddToPlaylist() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.media_lib_files_info)) },
                            enabled = onRemoveSelection == null || selectedFiles.size == 1,
                            onClick = { optionsExpanded = false; onShowInfo() }
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    SelectionPanelAction(
                        label = stringResource(R.string.media_lib_selection_advanced),
                        icon = R.drawable.ic_select_range,
                        onClick = { optionsExpanded = false; advancedExpanded = true },
                        modifier = Modifier.testTag("library-selection-advanced")
                    )
                    DropdownMenu(
                        expanded = advancedExpanded,
                        onDismissRequest = { advancedExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.media_lib_selection_all)) },
                            onClick = { advancedExpanded = false; onSelectAll() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.media_lib_selection_range)) },
                            onClick = { advancedExpanded = false; onSelectRange() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.media_lib_selection_invert)) },
                            onClick = { advancedExpanded = false; onInvertSelection() }
                        )
                    }
                }
                SelectionPanelAction(
                    label = stringResource(R.string.cancel),
                    icon = R.drawable.ic_close,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).testTag("library-selection-cancel")
                )
            }
        }
    }
}

@Composable
private fun SelectionPanelAction(
    label: String,
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
