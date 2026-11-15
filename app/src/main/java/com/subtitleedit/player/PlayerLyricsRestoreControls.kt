package com.subtitleedit.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.subtitleedit.R
import kotlinx.coroutines.delay

/** A direct way back to playback controls while browsing a full-screen lyric document. */
@Composable
internal fun PlayerLyricsRestoreControls(
    scrollActivity: Long,
    onRestoreControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(scrollActivity) {
        if (scrollActivity == 0L) {
            visible = false
            return@LaunchedEffect
        }
        visible = true
        delay(2_000L)
        visible = false
    }
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(tween(180)), exit = fadeOut(tween(220))) {
        PlayerEdgeButton(
            icon = R.drawable.ic_player_restore_controls,
            contentDescription = stringResource(R.string.player_lyrics_restore_controls),
            onClick = onRestoreControls,
            modifier = Modifier.testTag("player-lyrics-restore-controls")
        )
    }
}
