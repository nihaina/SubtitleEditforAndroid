package com.subtitleedit.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.subtitleedit.R
import kotlinx.coroutines.delay

/** Audio controls are collapsed and restored explicitly from the content area's edge. */
@Composable
internal fun PlayerAudioControlsToggle(
    controlsVisible: Boolean,
    revealActivity: Long,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect?) -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var restoreVisible by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible, revealActivity, pressed) {
        if (controlsVisible || revealActivity == 0L) {
            restoreVisible = false
            return@LaunchedEffect
        }
        restoreVisible = true
        // A held pointer owns the button until release/cancellation; only idle time may hide it.
        if (!pressed) {
            delay(2_000L)
            restoreVisible = false
        }
    }
    AnimatedVisibility(
        visible = controlsVisible || restoreVisible || pressed,
        modifier = modifier,
        enter = fadeIn(tween(180)), exit = fadeOut(tween(220))
    ) {
        PlayerEdgeButton(
            icon = if (controlsVisible) R.drawable.ic_player_hide_controls else R.drawable.ic_player_restore_controls,
            contentDescription = stringResource(
                if (controlsVisible) R.string.player_audio_hide_controls else R.string.player_lyrics_restore_controls
            ),
            onClick = onToggleControls,
            modifier = Modifier.testTag(if (controlsVisible) "player-audio-hide-controls" else "player-lyrics-restore-controls"),
            interactionSource = interactionSource,
            onBoundsChanged = onBoundsChanged
        )
    }
}
