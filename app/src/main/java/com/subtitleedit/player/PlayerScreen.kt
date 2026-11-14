package com.subtitleedit.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.subtitleedit.R
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun PlayerScreen(viewModel: PlayerViewModel, onEditTags: () -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    val pageGradient = PlayerColors.pageGradient
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .background(pageGradient)
                .pointerInput(state.durationMs) {
                    var axis: Int? = null
                    var dragVolume = 0f
                    detectDragGestures(
                        onDragStart = {
                            axis = null
                            dragVolume = viewModel.state.value.volumePercent.toFloat()
                        },
                        onDragEnd = {
                            if (axis == AXIS_SEEK) {
                                viewModel.state.value.seekPreviewMs?.let { viewModel.seekTo(it) }
                                viewModel.clearSeekPreview()
                            }
                            axis = null
                        },
                        onDragCancel = {
                            viewModel.clearSeekPreview()
                            axis = null
                        }
                    ) { change, amount ->
                        change.consume()
                        val current = viewModel.state.value
                        if (axis == null) axis = if (abs(amount.x) >= abs(amount.y)) AXIS_SEEK else AXIS_VOLUME
                        when (axis) {
                            AXIS_SEEK -> if (current.durationMs > 0) {
                                val delta = (amount.x / size.width * current.durationMs * 1.6f).toLong()
                                val base = current.seekPreviewMs ?: current.positionMs
                                viewModel.seekTo((base + delta).coerceIn(0, current.durationMs), fromGesture = true)
                            }
                            AXIS_VOLUME -> {
                                dragVolume = (dragVolume - amount.y / 14f).coerceIn(0f, 200f)
                                viewModel.setVolume(dragVolume.roundToInt())
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { viewModel.setControlsVisible(!viewModel.state.value.controlsVisible) },
                        onDoubleTap = { offset ->
                            val left = offset.x < size.width / 2f
                            viewModel.skipBy(if (left) -15_000L else 15_000L)
                        }
                    )
                }
        ) {
            AndroidView(
                factory = { context -> PlayerMpvView(context).also { viewModel.attach(it) } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { viewModel.detach(it) }
            )
            if (!state.isVideo) {
                Box(Modifier.fillMaxSize().background(pageGradient), contentAlignment = Alignment.Center) {
                    key(state.currentTrack?.path) {
                        PlayerAudioVisual(state.playing, state.loudness, state.loudnessSampleId, state.showLoudnessWaveform,
                            Modifier.padding(bottom = 120.dp))
                    }
                }
            }

            PlayerSubtitleOverlay(
                text = if (state.isVideo) "" else state.subtitleText,
                fontSize = state.subtitleFontSize,
                modifier = Modifier.align(Alignment.Center).padding(bottom = 260.dp)
            )

            PlayerHintOverlay(
                hint = state.gestureHint,
                onDismiss = viewModel::clearHint,
                modifier = Modifier.align(Alignment.Center)
            )

            state.error?.let { error ->
                Text(
                    text = error,
                    color = PlayerColors.Danger,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center).padding(28.dp)
                        .clip(CircleShape).background(PlayerColors.Surface)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            AnimatedVisibility(
                visible = state.controlsVisible,
                modifier = Modifier.align(Alignment.TopCenter).then(if (state.isVideo) Modifier.background(
                    Brush.verticalGradient(listOf(PlayerColors.Background.copy(alpha = 0.96f), Color.Transparent))
                ) else Modifier),
                enter = fadeIn() + slideInVertically { -it / 3 },
                exit = fadeOut() + slideOutVertically { -it / 3 }
            ) {
                PlayerTopBar(state, viewModel)
            }

            AnimatedVisibility(
                visible = state.controlsVisible,
                modifier = Modifier.align(Alignment.BottomCenter).then(if (state.isVideo) Modifier.background(
                    Brush.verticalGradient(listOf(Color.Transparent, PlayerColors.Background.copy(alpha = 0.96f)))
                ) else Modifier),
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 }
            ) {
                PlayerControls(state, viewModel)
            }

            PlayerPanelSheet(state, viewModel, onEditTags)
        }
    }
}

private const val AXIS_SEEK = 0
private const val AXIS_VOLUME = 1

@Composable
private fun PlayerTopBar(state: PlayerUiState, viewModel: PlayerViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player-top-bar")
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { viewModel.requestFinish() }, modifier = Modifier.testTag("player-back")) {
            Icon(
                painterResource(R.drawable.ic_back),
                contentDescription = stringResource(R.string.tools_navigate_back),
                tint = PlayerColors.OnDark
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                text = state.currentTrack?.name.orEmpty(),
                color = PlayerColors.OnDark,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = when {
                state.sleepTimer.active ->
                    stringResource(R.string.player_timer_remaining, PlayerSleepTimerPolicy.formatRemaining(state.sleepRemainingMs))
                state.scheduledStartRemainingMs != null ->
                    stringResource(R.string.player_timer_remaining, PlayerSleepTimerPolicy.formatRemaining(state.scheduledStartRemainingMs))
                else -> stringResource(R.string.player_queue_position, state.currentIndex + 1, state.playlist.size)
            }
            Text(
                text = subtitle,
                color = PlayerColors.OnDarkVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = { viewModel.openPanel(PlayerPanel.TIMER) }, modifier = Modifier.testTag("player-timer")) {
            Icon(
                painterResource(R.drawable.ic_player_alarm),
                contentDescription = stringResource(R.string.player_panel_timer),
                tint = if (state.sleepTimer.active || state.scheduledStartRemainingMs != null)
                    PlayerColors.Accent else PlayerColors.OnDarkVariant
            )
        }
        IconButton(onClick = { viewModel.openPanel(PlayerPanel.SETTINGS) }) {
            Icon(
                painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.player_panel_settings),
                tint = PlayerColors.OnDarkVariant
            )
        }
    }
}

@Composable
private fun PlayerControls(state: PlayerUiState, viewModel: PlayerViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player-controls")
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatPlaybackTime(state.seekPreviewMs ?: state.positionMs),
                color = PlayerColors.OnDarkVariant,
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            Text(formatPlaybackTime(state.durationMs), color = PlayerColors.OnDarkVariant, fontSize = 12.sp)
        }
        Slider(
            value = (state.seekPreviewMs ?: state.positionMs).toFloat(),
            onValueChange = { viewModel.seekTo(it.toLong(), fromGesture = true) },
            onValueChangeFinished = {
                viewModel.state.value.seekPreviewMs?.let { viewModel.seekTo(it) }
                viewModel.clearSeekPreview()
            },
            valueRange = 0f..state.durationMs.coerceAtLeast(1L).toFloat(),
            enabled = state.ready && state.durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = PlayerColors.Accent,
                activeTrackColor = PlayerColors.Accent,
                inactiveTrackColor = PlayerColors.SurfaceHigh
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp).testTag("player-seek")
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            PlayerTransportIcon(R.drawable.ic_skip_previous, R.string.player_previous, state.hasPrevious,
                tag = "player-previous", onClick = viewModel::previous)
            PlayerTransportIcon(R.drawable.ic_history, R.string.player_seek_back, state.ready, onClick = { viewModel.skipBy(-15_000L) })
            PlayerPlayButton(state.playing, tag = "player-play", onClick = viewModel::togglePlay)
            PlayerTransportIcon(R.drawable.ic_seek_back, R.string.player_seek_forward, state.ready, onClick = { viewModel.skipBy(15_000L) })
            PlayerTransportIcon(R.drawable.ic_skip_next, R.string.player_next, state.hasNext,
                tag = "player-next", onClick = viewModel::next)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val modeDescription = stringResource(modeLabel(state.mode))
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(PlayerColors.SurfaceHigh)
                    .testTag("player-mode-chip")
                    .semantics { contentDescription = modeDescription }
                    .clickable(role = Role.Button) { viewModel.cycleMode() },
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(modeIcon(state.mode)), contentDescription = null,
                    modifier = Modifier.size(24.dp), tint = PlayerColors.Accent)
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(PlayerColors.SurfaceHigh)
                    .clickable(role = Role.Button) { viewModel.openPanel(PlayerPanel.SPEED) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("${state.speed}x", color = PlayerColors.OnDark, fontSize = 11.sp)
            }
            PlayerControlIcon(R.drawable.ic_player_equalizer, R.string.player_panel_equalizer,
                state.equalizer.enabled, { viewModel.openPanel(PlayerPanel.EQUALIZER) })
            PlayerControlIcon(R.drawable.ic_subtitle_format, R.string.player_panel_subtitle,
                state.selectedSubtitleId.isNotBlank() && state.selectedSubtitleId != "none", { viewModel.openPanel(PlayerPanel.SUBTITLE) })
            PlayerControlIcon(R.drawable.ic_player_bookmarks, R.string.player_panel_bookmark,
                state.bookmarks.isNotEmpty(), { viewModel.openPanel(PlayerPanel.BOOKMARK) })
            PlayerControlIcon(R.drawable.ic_player_playlist, R.string.player_panel_playlist,
                false, { viewModel.openPanel(PlayerPanel.PLAYLIST) })
        }
    }
}

@Composable
private fun PlayerTransportIcon(icon: Int, description: Int, enabled: Boolean, rotate: Float = 0f,
    tag: String? = null, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(46.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Icon(
            painterResource(icon),
            contentDescription = stringResource(description),
            modifier = Modifier.size(24.dp).scale(scaleX = if (rotate == 180f) -1f else 1f, scaleY = 1f),
            tint = if (enabled) PlayerColors.OnDark else PlayerColors.OnDarkVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun PlayerPlayButton(playing: Boolean, tag: String? = null, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "play-button-scale"
    )
    val description = stringResource(if (playing) R.string.player_pause else R.string.player_play)
    Box(
        modifier = Modifier
            .size(76.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .scale(scale)
            .clip(CircleShape)
            .background(PlayerColors.accentBrush, CircleShape)
            .semantics { contentDescription = description }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(if (playing) R.drawable.ic_video_pause else R.drawable.ic_video_play),
            contentDescription = null,
            modifier = Modifier.size(34.dp),
            tint = PlayerColors.Background
        )
    }
}

@Composable
private fun PlayerControlIcon(icon: Int, description: Int, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(
            painterResource(icon),
            contentDescription = stringResource(description),
            modifier = Modifier.size(20.dp),
            tint = if (active) PlayerColors.Accent else PlayerColors.OnDarkVariant
        )
    }
}
