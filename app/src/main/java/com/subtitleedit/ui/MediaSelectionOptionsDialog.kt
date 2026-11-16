package com.subtitleedit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.player.PlayerPanelImmersiveWindow
import com.subtitleedit.ui.components.AppDialog

internal enum class MediaSelectionAction(val labelRes: Int, val iconRes: Int, val singleFileOnly: Boolean = false) {
    PLAY_NEXT(R.string.media_selection_play_next, R.drawable.ic_skip_next),
    ADD_TO_QUEUE(R.string.media_selection_add_to_queue, R.drawable.ic_queue_play),
    ADD_TO_PLAYLIST(R.string.player_action_add_to_playlist, R.drawable.ic_playlist_add),
    ADD_TO_FAVORITES(R.string.media_selection_add_to_favorites, R.drawable.ic_favorite),
    REMOVE_FROM_FAVORITES(R.string.media_selection_remove_from_favorites, R.drawable.ic_favorite_border),
    FILE_INFO(R.string.media_lib_files_info, R.drawable.ic_info, singleFileOnly = true),
    EDIT_TAGS(R.string.player_info_edit_tags, R.drawable.ic_rename),
    LOCATE_DIRECTORY(R.string.media_selection_locate_directory, R.drawable.ic_folder, singleFileOnly = true),
    SHARE(R.string.media_selection_share, R.drawable.ic_share),
    CLEAR_HISTORY(R.string.media_selection_clear_history, R.drawable.ic_history),
    DELETE_FILES(R.string.media_selection_delete_files, R.drawable.ic_delete_normal);

    fun enabledFor(count: Int): Boolean = count > 0 && (!singleFileOnly || count == 1)
}

/** Batch choices are a dialog so the complete action list is accessible on small screens. */
@Composable
internal fun MediaSelectionOptionsDialog(
    controller: MediaSelectionController,
    onAction: (MediaSelectionAction, List<String>) -> Unit,
    onDismiss: () -> Unit,
    immersive: Boolean = false
) {
    val count = controller.selectedPaths.size
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.68f
    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("media-selection-options-dialog").semantics { testTagsAsResourceId = true },
        title = {
            if (immersive) PlayerPanelImmersiveWindow()
            Text(stringResource(R.string.media_lib_files_options))
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.media_selection_close_after_action),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 12.dp))
                    Switch(
                        checked = controller.closeAfterAction,
                        onCheckedChange = { controller.closeAfterAction = it },
                        modifier = Modifier.testTag("media-selection-close-after-action")
                    )
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("media-selection-action-list")) {
                    items(MediaSelectionAction.entries, key = { it.name }) { action ->
                        val enabled = action.enabledFor(count)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable(enabled = enabled) { onAction(action, controller.selectedPaths.toList()) }
                                .testTag("media-selection-action:${action.name}")
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Icon(painterResource(action.iconRes), contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f))
                            Text(stringResource(action.labelRes),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
