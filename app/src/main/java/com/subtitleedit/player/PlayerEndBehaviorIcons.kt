package com.subtitleedit.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

/** The two symbols mirror the track and queue choices shown in the ending settings. */
@Composable
internal fun PlayerEndBehaviorIcon(mode: PlayerPlayMode, behavior: PlayerEndBehavior, tint: Color) {
    val applied = behavior.effectiveFor(mode)
    Row(Modifier.size(36.dp, 24.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(trackEndIcon(applied.trackAction)), contentDescription = null,
            modifier = Modifier.size(16.dp, 24.dp), tint = tint)
        Box(Modifier.size(1.dp, 18.dp).background(tint.copy(alpha = tint.alpha * 0.4f)))
        Icon(painterResource(queueEndIcon(applied.queueAction)), contentDescription = null,
            modifier = Modifier.size(16.dp, 24.dp), tint = tint)
    }
}

@Composable
internal fun playerEndBehaviorActionDescription(mode: PlayerPlayMode, behavior: PlayerEndBehavior): String {
    val applied = behavior.effectiveFor(mode)
    return stringResource(R.string.player_end_action_description,
        stringResource(trackEndLabel(applied.trackAction)), stringResource(queueEndLabel(applied.queueAction)))
}

internal fun trackEndIcon(action: PlayerTrackEndAction): Int = when (action) {
    PlayerTrackEndAction.STOP -> R.drawable.ic_player_track_end_stop
    PlayerTrackEndAction.LOAD_NEXT_PAUSED -> R.drawable.ic_player_track_end_next_paused
    PlayerTrackEndAction.FOLLOW_MODE -> R.drawable.ic_player_track_end_next
    PlayerTrackEndAction.REPEAT_TRACK -> R.drawable.ic_player_repeat_one
}

internal fun queueEndIcon(action: PlayerQueueEndAction): Int = when (action) {
    PlayerQueueEndAction.STOP -> R.drawable.ic_player_queue_end_stop
    PlayerQueueEndAction.NEXT_QUEUE -> R.drawable.ic_player_queue_end_next
    PlayerQueueEndAction.REPEAT_QUEUE -> R.drawable.ic_player_queue_end_repeat
}

internal fun trackEndLabel(action: PlayerTrackEndAction): Int = when (action) {
    PlayerTrackEndAction.STOP -> R.string.player_end_stop
    PlayerTrackEndAction.LOAD_NEXT_PAUSED -> R.string.player_end_load_next_paused
    PlayerTrackEndAction.FOLLOW_MODE -> R.string.player_end_continue
    PlayerTrackEndAction.REPEAT_TRACK -> R.string.player_mode_repeat_one
}

private fun queueEndLabel(action: PlayerQueueEndAction): Int = when (action) {
    PlayerQueueEndAction.STOP -> R.string.player_end_stop
    PlayerQueueEndAction.NEXT_QUEUE -> R.string.player_end_next_queue
    PlayerQueueEndAction.REPEAT_QUEUE -> R.string.player_end_repeat_queue
}
