package com.subtitleedit.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.R

/** Playback options are a draft until the user confirms the dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerEndBehaviorDialog(
    mode: PlayerPlayMode,
    behavior: PlayerEndBehavior,
    onDismiss: () -> Unit,
    onConfirm: (PlayerPlayMode, PlayerEndBehavior) -> Unit,
    includeNextQueue: Boolean = true
) {
    val applied = behavior.effectiveFor(mode)
    var advanced by rememberSaveable(mode, behavior) { mutableStateOf(true) }
    var draftMode by rememberSaveable(mode, behavior) { mutableStateOf(mode) }
    var trackAction by rememberSaveable(mode, behavior) { mutableStateOf(applied.trackAction) }
    var queueAction by rememberSaveable(mode, behavior, includeNextQueue) {
        mutableStateOf(applied.queueAction.takeUnless { !includeNextQueue && it == PlayerQueueEndAction.NEXT_QUEUE }
            ?: PlayerQueueEndAction.STOP)
    }
    var wrapQueues by rememberSaveable(mode, behavior) { mutableStateOf(applied.wrapQueues) }
    var reshuffle by rememberSaveable(mode, behavior) { mutableStateOf(applied.reshuffle) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.92f

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        PlayerPanelImmersiveWindow()
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 480.dp)
                .testTag("player-end-behavior-dialog").semantics { testTagsAsResourceId = true },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.heightIn(max = maxHeight)) {
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).testTag("player-end-behavior-options")
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (advanced) {
                        EndBehaviorHeading(R.string.player_end_track_heading)
                        Column(Modifier.selectableGroup()) {
                            listOf(PlayerTrackEndAction.STOP, PlayerTrackEndAction.FOLLOW_MODE,
                                PlayerTrackEndAction.LOAD_NEXT_PAUSED).forEach { action ->
                                EndBehaviorRadioRow(
                                    selected = trackAction == action,
                                    labelRes = trackEndLabel(action),
                                    iconRes = trackEndIcon(action),
                                    tag = "player-end-track:${action.name}",
                                    onClick = { trackAction = action }
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        EndBehaviorHeading(R.string.player_end_queue_heading)
                        EndBehaviorCheckRow(
                            checked = reshuffle,
                            labelRes = R.string.player_end_reshuffle,
                            tag = "player-end-reshuffle",
                            onChecked = { reshuffle = it }
                        )
                        Text(stringResource(R.string.player_end_then),
                            Modifier.padding(top = 8.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.bodyMedium)
                        Column(Modifier.selectableGroup()) {
                            PlayerQueueEndAction.entries.filter { includeNextQueue || it != PlayerQueueEndAction.NEXT_QUEUE }
                                .forEach { action ->
                                    EndBehaviorRadioRow(
                                        selected = queueAction == action,
                                        labelRes = action.labelRes,
                                        iconRes = queueEndIcon(action),
                                        tag = "player-end-queue:${action.name}",
                                        onClick = { queueAction = action }
                                    )
                                    if (action == PlayerQueueEndAction.NEXT_QUEUE) {
                                        AnimatedVisibility(visible = queueAction == PlayerQueueEndAction.NEXT_QUEUE) {
                                            EndBehaviorCheckRow(
                                                checked = wrapQueues,
                                                labelRes = R.string.player_end_wrap_queues,
                                                tag = "player-end-wrap-queues",
                                                onChecked = { wrapQueues = it }
                                            )
                                        }
                                    }
                                }
                        }
                    } else {
                        EndBehaviorHeading(R.string.player_end_simple_heading)
                        Column(Modifier.selectableGroup()) {
                            PlayerPlayMode.entries.forEach { item ->
                                EndBehaviorRadioRow(
                                    selected = draftMode == item,
                                    labelRes = modeLabel(item),
                                    iconRes = modeIcon(item),
                                    tag = "player-end-mode:${item.name}",
                                    onClick = { draftMode = item }
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EndBehaviorTab(false, advanced, Modifier.weight(1f)) { advanced = false }
                    EndBehaviorTab(true, advanced, Modifier.weight(1f)) { advanced = true }
                    IconButton(onClick = {
                        draftMode = PlayerPlayMode.REPEAT_ALL
                        val defaults = PlayerEndBehavior()
                        trackAction = defaults.trackAction
                        queueAction = defaults.queueAction
                        wrapQueues = defaults.wrapQueues
                        reshuffle = defaults.reshuffle
                    }, modifier = Modifier.testTag("player-end-reset")) {
                        Icon(painterResource(R.drawable.ic_restore),
                            contentDescription = stringResource(R.string.player_end_reset))
                    }
                    IconButton(onClick = {
                        onConfirm(draftMode, PlayerEndBehavior(
                            enabled = advanced, trackAction = trackAction, queueAction = queueAction,
                            wrapQueues = wrapQueues, reshuffle = reshuffle
                        ))
                        onDismiss()
                    }, modifier = Modifier.testTag("player-end-confirm")) {
                        Icon(painterResource(R.drawable.ic_check), stringResource(R.string.confirm),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun EndBehaviorHeading(labelRes: Int) {
    Text(stringResource(labelRes), Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun EndBehaviorRadioRow(selected: Boolean, labelRes: Int, iconRes: Int, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected = selected, onClick = null)
        Icon(painterResource(iconRes), contentDescription = null, Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(labelRes), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EndBehaviorCheckRow(checked: Boolean, labelRes: Int, tag: String, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag)
        .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChecked)
        .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(labelRes), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EndBehaviorTab(value: Boolean, selectedValue: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.testTag(if (value) "player-end-advanced" else "player-end-simple")
        .semantics { selected = selectedValue == value },
        border = BorderStroke(1.dp, if (selectedValue == value) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant)) {
        Text(stringResource(if (value) R.string.player_end_advanced else R.string.player_end_simple), maxLines = 1)
    }
}

private val PlayerQueueEndAction.labelRes: Int get() = when (this) {
    PlayerQueueEndAction.STOP -> R.string.player_end_stop
    PlayerQueueEndAction.NEXT_QUEUE -> R.string.player_end_next_queue
    PlayerQueueEndAction.REPEAT_QUEUE -> R.string.player_end_repeat_queue
}

