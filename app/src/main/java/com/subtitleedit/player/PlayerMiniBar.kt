package com.subtitleedit.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R

@Composable
internal fun PlayerMiniBar(
    state: PlayerUiState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    contentAlpha: Float = 1f
) {
    Box(modifier.fillMaxWidth().height(72.dp).testTag("mini-player")
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.player_expand), onClick = onExpand)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The same media layer occupies this space in both player presentations.
            Spacer(Modifier.width(80.dp))
            Column(Modifier.weight(1f).graphicsLayer { alpha = contentAlpha }) {
                Text(state.currentTrack?.name.orEmpty(), color = PlayerColors.OnDark,
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.currentTrack?.file?.parentFile?.name.orEmpty(), color = PlayerColors.OnDarkVariant,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.graphicsLayer { alpha = contentAlpha }, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = state.hasPrevious,
                    modifier = Modifier.size(36.dp).testTag("mini-player-previous")) {
                    Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.player_previous),
                        Modifier.size(20.dp), tint = PlayerColors.OnDark)
                }
                IconButton(onClick = onTogglePlay,
                    modifier = Modifier.size(44.dp).testTag("mini-player-play-pause")) {
                    Icon(painterResource(if (state.playing) R.drawable.ic_video_pause else R.drawable.ic_video_play),
                        stringResource(if (state.playing) R.string.player_pause else R.string.player_play),
                        Modifier.size(26.dp), tint = PlayerColors.OnDark)
                }
                IconButton(onClick = onNext, enabled = state.hasNext,
                    modifier = Modifier.size(36.dp).testTag("mini-player-next")) {
                    Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.player_next),
                        Modifier.size(20.dp), tint = PlayerColors.OnDark)
                }
            }
        }
        val fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)
            .graphicsLayer { alpha = contentAlpha }.background(PlayerColors.SurfaceHigh)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(PlayerColors.Accent))
        }
    }
}
