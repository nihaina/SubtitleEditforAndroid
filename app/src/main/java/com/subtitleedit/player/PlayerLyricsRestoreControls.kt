package com.subtitleedit.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape)
                .background(PlayerColors.SurfaceHigh.copy(alpha = 0.94f))
                .clickable(role = Role.Button, onClick = onRestoreControls)
                .testTag("player-lyrics-restore-controls"),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(R.drawable.ic_player_restore_controls),
                stringResource(R.string.player_lyrics_restore_controls),
                modifier = Modifier.size(16.dp), tint = PlayerColors.OnDark)
        }
    }
}
