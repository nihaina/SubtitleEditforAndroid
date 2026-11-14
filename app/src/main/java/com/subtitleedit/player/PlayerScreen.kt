package com.subtitleedit.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
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
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var topOverlayBounds by remember { mutableStateOf<Rect?>(null) }
    var bottomOverlayBounds by remember { mutableStateOf<Rect?>(null) }
    var gestureOrigin by remember { mutableStateOf(Offset.Zero) }
    val currentTopOverlay by rememberUpdatedState(topOverlayBounds)
    val currentBottomOverlay by rememberUpdatedState(bottomOverlayBounds)
    val currentGestureOrigin by rememberUpdatedState(gestureOrigin)
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("player-screen")
                .semantics { testTagsAsResourceId = true }
                .onGloballyPositioned { gestureOrigin = it.positionInRoot() }
                .background(pageGradient)
                .pointerInput(state.currentTrack?.path, state.isVideo, state.durationMs) {
                    var axis: Int? = null
                    var dragVolume = 0f
                    var canSeek = false
                    fun outsideOverlays(position: Offset): Boolean {
                        val point = position + currentGestureOrigin
                        return currentTopOverlay?.contains(point) != true &&
                            currentBottomOverlay?.contains(point) != true
                    }
                    detectDragGestures(
                        orientationLock = null,
                        onDragStart = { down, _, _ ->
                            axis = null
                            val current = viewModel.state.value
                            dragVolume = current.volumePercent.toFloat()
                            // Only the initial press is excluded by the controls; an accepted
                            // video drag keeps ownership as it crosses into either overlay.
                            canSeek = current.isVideo && current.ready && current.panel == PlayerPanel.NONE &&
                                outsideOverlays(down.position)
                        },
                        onDragEnd = {
                            if (axis == AXIS_SEEK) {
                                if (canSeek) {
                                    viewModel.state.value.seekPreviewMs?.let { viewModel.seekTo(it) }
                                }
                                viewModel.clearSeekPreview()
                            }
                            axis = null
                        },
                        onDragCancel = {
                            viewModel.clearSeekPreview()
                            axis = null
                        }
                    ) { change, amount ->
                        val current = viewModel.state.value
                        if (axis == null) axis = if (abs(amount.x) >= abs(amount.y)) AXIS_SEEK else AXIS_VOLUME
                        when (axis) {
                            AXIS_SEEK -> if (canSeek && current.durationMs > 0) {
                                change.consume()
                                val delta = (amount.x / size.width * current.durationMs * 1.6f).toLong()
                                val base = current.seekPreviewMs ?: current.positionMs
                                viewModel.seekTo((base + delta).coerceIn(0, current.durationMs), fromGesture = true)
                            }
                            AXIS_VOLUME -> {
                                change.consume()
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
                            Modifier.padding(bottom = if (landscape) 24.dp else 120.dp))
                    }
                }
            }

            PlayerSubtitleOverlay(
                text = if (state.isVideo) "" else state.subtitleText,
                fontSize = state.subtitleFontSize,
                modifier = Modifier.align(Alignment.Center).padding(bottom = if (landscape) 64.dp else 260.dp)
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
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(220))
            ) {
                PlayerControlOverlay(state.isVideo, top = true, onBoundsChanged = { topOverlayBounds = it }) {
                    PlayerTopBar(state, viewModel, landscape)
                }
            }

            AnimatedVisibility(
                visible = state.controlsVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(220))
            ) {
                PlayerControlOverlay(state.isVideo, top = false, onBoundsChanged = { bottomOverlayBounds = it }) {
                    PlayerControls(state, viewModel, landscape)
                }
            }

            PlayerPanelSheet(state, viewModel, onEditTags)
        }
    }
}

private val LocalVideoControls = staticCompositionLocalOf { false }

/** The scrim lives inside AnimatedVisibility so it fades with the controls. */
@Composable
private fun PlayerControlOverlay(
    video: Boolean,
    top: Boolean,
    onBoundsChanged: (Rect?) -> Unit,
    content: @Composable () -> Unit
) {
    val boundsChanged by rememberUpdatedState(onBoundsChanged)
    DisposableEffect(Unit) {
        onDispose { boundsChanged(null) }
    }
    val scrim = Color.Black.copy(alpha = 0.26f)
    val modifier = Modifier.fillMaxWidth()
        .testTag(if (top) "player-top-overlay" else "player-bottom-overlay")
        .onGloballyPositioned { boundsChanged(it.boundsInRoot()) }
        .then(if (video) Modifier.background(Brush.verticalGradient(
            if (top) listOf(scrim, Color.Transparent) else listOf(Color.Transparent, scrim)
        )) else Modifier)
    CompositionLocalProvider(LocalVideoControls provides video) {
        Box(modifier) { content() }
    }
}

private object PlayerControlColors {
    val Foreground: Color @Composable get() = if (LocalVideoControls.current) Color.White else PlayerColors.OnDark
    val Secondary: Color @Composable get() = if (LocalVideoControls.current) Color.White.copy(alpha = 0.85f) else PlayerColors.OnDarkVariant
    val Accent: Color @Composable get() = if (LocalVideoControls.current) Color.White else PlayerColors.Accent
    val Surface: Color @Composable get() = if (LocalVideoControls.current) Color.Black.copy(alpha = 0.18f) else PlayerColors.SurfaceHigh
    val PlayBackground: Brush @Composable get() = if (LocalVideoControls.current)
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.9f)))
        else PlayerColors.accentBrush
    val PlayForeground: Color @Composable get() = if (LocalVideoControls.current) Color.Black else PlayerColors.Background
}

private const val AXIS_SEEK = 0
private const val AXIS_VOLUME = 1

@Composable
private fun PlayerTopBar(state: PlayerUiState, viewModel: PlayerViewModel, landscape: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player-top-bar")
            .windowInsetsPadding(if (landscape) WindowInsets(0, 0, 0, 0)
                else WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 8.dp, vertical = if (landscape) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { viewModel.requestFinish() }, modifier = Modifier.size(if (landscape) 42.dp else 48.dp).testTag("player-back")) {
            Icon(
                painterResource(R.drawable.ic_back),
                contentDescription = stringResource(R.string.tools_navigate_back),
                tint = PlayerControlColors.Foreground
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                text = state.currentTrack?.name.orEmpty(),
                color = PlayerControlColors.Foreground,
                fontSize = if (landscape) 14.sp else 16.sp,
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
                color = PlayerControlColors.Secondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (landscape) {
            PlayerToolsRow(state, viewModel, compact = true)
        }
        IconButton(onClick = { viewModel.openPanel(PlayerPanel.TIMER) },
            modifier = Modifier.size(if (landscape) 42.dp else 48.dp).testTag("player-timer")) {
            Icon(
                painterResource(R.drawable.ic_player_alarm),
                contentDescription = stringResource(R.string.player_panel_timer),
                tint = if (state.sleepTimer.active || state.scheduledStartRemainingMs != null)
                    PlayerControlColors.Accent else PlayerControlColors.Secondary
            )
        }
        IconButton(onClick = { viewModel.openPanel(PlayerPanel.SETTINGS) },
            modifier = Modifier.size(if (landscape) 42.dp else 48.dp)) {
            Icon(
                painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.player_panel_settings),
                tint = PlayerControlColors.Secondary
            )
        }
    }
}

@Composable
private fun PlayerControls(state: PlayerUiState, viewModel: PlayerViewModel, landscape: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player-controls")
            .windowInsetsPadding(if (landscape) WindowInsets(0, 0, 0, 0)
                else WindowInsets.displayCutout.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 20.dp)
            .padding(bottom = if (landscape) 6.dp else 16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatPlaybackTime(state.seekPreviewMs ?: state.positionMs),
                color = PlayerControlColors.Secondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            Text(formatPlaybackTime(state.durationMs), color = PlayerControlColors.Secondary, fontSize = 12.sp)
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
                thumbColor = PlayerControlColors.Accent,
                activeTrackColor = PlayerControlColors.Accent,
                inactiveTrackColor = PlayerControlColors.Surface
            ),
            modifier = Modifier.fillMaxWidth().height(if (landscape) 24.dp else 28.dp).testTag("player-seek")
        )
        if (!state.isVideo) Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().testTag("player-transport-row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            PlayerTransportIcon(R.drawable.ic_skip_previous, R.string.player_previous, state.hasPrevious,
                tag = "player-previous", onClick = viewModel::previous)
            PlayerTransportIcon(R.drawable.ic_history, R.string.player_seek_back, state.ready, onClick = { viewModel.skipBy(-15_000L) })
            PlayerPlayButton(state.playing, video = state.isVideo, tag = "player-play", onClick = viewModel::togglePlay)
            PlayerTransportIcon(R.drawable.ic_seek_back, R.string.player_seek_forward, state.ready, onClick = { viewModel.skipBy(15_000L) })
            PlayerTransportIcon(R.drawable.ic_skip_next, R.string.player_next, state.hasNext,
                tag = "player-next", onClick = viewModel::next)
        }
        if (!landscape) {
            Spacer(Modifier.height(6.dp))
            PlayerToolsRow(state, viewModel, compact = false)
        }
    }
}

@Composable
private fun PlayerToolsRow(state: PlayerUiState, viewModel: PlayerViewModel, compact: Boolean) {
    Row(
        modifier = (if (compact) Modifier else Modifier.fillMaxWidth()).testTag("player-tools-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (compact) Arrangement.spacedBy(2.dp) else Arrangement.SpaceBetween
    ) {
        val modeDescription = stringResource(modeLabel(state.mode))
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(PlayerControlColors.Surface)
                .testTag("player-mode-chip")
                .semantics { contentDescription = modeDescription }
                .clickable(role = Role.Button) { viewModel.cycleMode() },
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(modeIcon(state.mode)), contentDescription = null,
                modifier = Modifier.size(24.dp), tint = PlayerControlColors.Accent)
        }
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 42.dp)
                .clip(CircleShape)
                .background(PlayerControlColors.Surface)
                .clickable(role = Role.Button) { viewModel.openPanel(PlayerPanel.SPEED) }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("${state.speed}x", color = PlayerControlColors.Foreground, fontSize = 11.sp)
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

@Composable
private fun PlayerTransportIcon(icon: Int, description: Int, enabled: Boolean, rotate: Float = 0f,
    tag: String? = null, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(46.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Icon(
            painterResource(icon),
            contentDescription = stringResource(description),
            modifier = Modifier.size(24.dp).scale(scaleX = if (rotate == 180f) -1f else 1f, scaleY = 1f),
            tint = if (enabled) PlayerControlColors.Foreground else PlayerControlColors.Secondary.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun PlayerPlayButton(playing: Boolean, video: Boolean, tag: String? = null, onClick: () -> Unit) {
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
            .size(if (video) 36.dp else 48.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .scale(scale)
            .clip(CircleShape)
            .background(PlayerControlColors.PlayBackground, CircleShape)
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
            modifier = Modifier.size(24.dp),
            tint = PlayerControlColors.PlayForeground
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
            tint = if (active) PlayerControlColors.Accent else PlayerControlColors.Secondary
        )
    }
}
