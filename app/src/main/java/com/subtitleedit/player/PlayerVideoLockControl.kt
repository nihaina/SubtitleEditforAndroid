package com.subtitleedit.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.subtitleedit.R
import kotlinx.coroutines.delay

@Composable
internal fun PlayerVideoLockControl(
    locked: Boolean,
    controlsVisible: Boolean,
    revealActivity: Long,
    onToggleLock: () -> Unit,
    modifier: Modifier = Modifier
) {
    var temporarilyVisible by remember { mutableStateOf(false) }
    LaunchedEffect(locked, revealActivity) {
        temporarilyVisible = locked
        if (locked) {
            delay(2_000L)
            temporarilyVisible = false
        }
    }
    AnimatedVisibility(
        visible = if (locked) temporarilyVisible else controlsVisible,
        modifier = modifier,
        enter = fadeIn(tween(180)), exit = fadeOut(tween(220))
    ) {
        PlayerEdgeButton(
            icon = if (locked) R.drawable.ic_player_locked else R.drawable.ic_player_unlocked,
            contentDescription = stringResource(if (locked) R.string.player_unlock_controls else R.string.player_lock_controls),
            onClick = onToggleLock,
            modifier = Modifier.testTag("player-video-lock"),
            background = if (locked) Color.Black.copy(alpha = 0.65f) else Color.LightGray.copy(alpha = 0.85f),
            tint = if (locked) Color.White else Color.Black
        )
    }
}
