package com.subtitleedit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.R
import com.subtitleedit.player.PlayerPanelImmersiveWindow
import com.subtitleedit.ui.components.AppLazyListScrollbar

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaSelectionOptionsDialog(
    controller: MediaSelectionController,
    onAction: (MediaSelectionAction, List<String>) -> Unit,
    onDismiss: () -> Unit,
    immersive: Boolean = false
) {
    val count = controller.selectedPaths.size
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
    val listState = rememberLazyListState()
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !immersive)
    ) {
        if (immersive) PlayerPanelImmersiveWindow()
        Surface(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.9f).heightIn(max = maxHeight)
                .testTag("media-selection-options-dialog").semantics { testTagsAsResourceId = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column {
                Text(stringResource(R.string.media_selection_options_title, count),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)
                        .testTag("media-selection-options-title"))
                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("media-selection-action-list")) {
                        MediaSelectionAction.entries.forEach { action ->
                            if (action == MediaSelectionAction.ADD_TO_FAVORITES ||
                                action == MediaSelectionAction.FILE_INFO || action == MediaSelectionAction.SHARE) {
                                item(key = "divider:${action.name}") {
                                    HorizontalDivider(Modifier.padding(
                                        horizontal = if (action == MediaSelectionAction.ADD_TO_FAVORITES) 0.dp else 24.dp,
                                        vertical = 8.dp))
                                }
                            }
                            item(key = action.name) {
                                val enabled = action.enabledFor(count)
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                        .clickable(enabled = enabled) { onAction(action, controller.selectedPaths.toList()) }
                                        .testTag("media-selection-action:${action.name}")
                                        .padding(horizontal = 24.dp, vertical = 16.dp),
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
                    AppLazyListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(value = controller.closeAfterAction, role = Role.Checkbox,
                            onValueChange = { controller.closeAfterAction = it })
                        .testTag("media-selection-close-after-action")
                        .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = controller.closeAfterAction, onCheckedChange = null)
                    Text(stringResource(R.string.media_selection_close_after_action),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp))
                }
            }
        }
    }
}
