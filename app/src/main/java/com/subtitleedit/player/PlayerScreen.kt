package com.subtitleedit.player

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
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
internal fun PlayerScreen(
    viewModel: PlayerViewModel,
    onEditTags: () -> Unit = {},
    onPickSubtitle: () -> Unit = {},
    embedded: Boolean = false,
    interactionsEnabled: Boolean = true,
    onCollapse: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsState()
    val pageGradient = PlayerColors.pageGradient
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current
    val hapticView by rememberUpdatedState(LocalView.current)
    var playerHeightPx by remember { mutableStateOf(0) }
    var topOverlayBounds by remember { mutableStateOf<Rect?>(null) }
    var bottomOverlayBounds by remember { mutableStateOf<Rect?>(null) }
    var expandedAudioTopInset by remember(configuration.screenWidthDp, configuration.screenHeightDp,
        landscape, configuration.fontScale, density.density) {
        mutableStateOf<Float?>(null)
    }
    var expandedAudioBottomInset by remember(configuration.screenWidthDp, configuration.screenHeightDp,
        landscape, configuration.fontScale, density.density) {
        mutableStateOf<Float?>(null)
    }
    var gestureOrigin by remember { mutableStateOf(Offset.Zero) }
    val currentTopOverlay by rememberUpdatedState(topOverlayBounds)
    val currentBottomOverlay by rememberUpdatedState(bottomOverlayBounds)
    val currentGestureOrigin by rememberUpdatedState(gestureOrigin)
    val collapseGestureHeightPx = with(density) { if (embedded) 112.dp.toPx() else 0f }
    var videoLockActivity by remember(state.currentTrack?.path) { mutableStateOf(0L) }
    var audioRestoreActivity by remember(state.currentTrack?.path) { mutableStateOf(0L) }
    LaunchedEffect(state.controlsVisible) {
        if (state.controlsVisible) audioRestoreActivity = 0L
    }
    val audioButtonTopPadding = expandedAudioTopInset?.let {
        with(density) { it.coerceAtLeast(0f).toDp() }
    } ?: 64.dp
    val audioButtonBottomPadding = expandedAudioBottomInset?.let {
        with(density) { it.coerceAtLeast(0f).toDp() }
    } ?: if (landscape) 132.dp else 204.dp
    val audioContentTopPadding = if (state.controlsVisible) audioButtonTopPadding else 0.dp
    val audioContentBottomPadding = if (state.controlsVisible) audioButtonBottomPadding else 0.dp
    BackHandler(enabled = embedded && interactionsEnabled && onCollapse != null && !state.controlsLocked &&
        state.panel == PlayerPanel.NONE && !state.showTrackInfoDialog && !state.showPlaylistPickerDialog) {
        onCollapse?.invoke()
    }
    BackHandler(enabled = !embedded && interactionsEnabled && state.lyricsVisible && !state.isVideo && state.panel == PlayerPanel.NONE &&
        !state.showTrackInfoDialog && !state.showPlaylistPickerDialog) {
        viewModel.setLyricsVisible(false)
    }
    BackHandler(enabled = interactionsEnabled && state.isVideo && state.controlsLocked) {
        viewModel.setVideoControlsLocked(false)
    }
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("player-screen")
                .semantics { testTagsAsResourceId = true }
                .onGloballyPositioned { gestureOrigin = it.positionInRoot(); playerHeightPx = it.size.height }
                .then(if (embedded) Modifier else Modifier.background(pageGradient))
                .then(if (interactionsEnabled) Modifier else Modifier
                    .clearAndSetSemantics {}
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    })
                .pointerInput(state.currentTrack?.path, state.isVideo, state.durationMs, state.controlsLocked,
                    interactionsEnabled, collapseGestureHeightPx) {
                    if (!interactionsEnabled) return@pointerInput
                    var axis: Int? = null
                    var dragVolume = 0f
                    var appliedVolume = 0
                    var lastVolumeHapticAt = 0L
                    var canSeek = false
                    var canAdjustVolume = false
                    fun outsideOverlays(position: Offset): Boolean {
                        val point = position + currentGestureOrigin
                        return currentTopOverlay?.contains(point) != true &&
                            currentBottomOverlay?.contains(point) != true
                    }
                    try {
                        detectDragGestures(
                            orientationLock = null,
                            onDragStart = { down, _, _ ->
                                axis = null
                                val current = viewModel.state.value
                                dragVolume = viewModel.refreshSystemVolume().toFloat()
                                appliedVolume = dragVolume.roundToInt()
                                lastVolumeHapticAt = 0L
                                // Only the initial press is excluded by the controls; an accepted
                                // video drag keeps ownership as it crosses into either overlay.
                                val outsideCollapseHandle = down.position.y >= collapseGestureHeightPx
                                canSeek = outsideCollapseHandle && current.isVideo && !current.controlsLocked && current.ready && current.panel == PlayerPanel.NONE &&
                                    outsideOverlays(down.position)
                                // Vertical swipes in the audio lyric list belong to the list.
                                canAdjustVolume = outsideCollapseHandle && !current.controlsLocked && current.panel == PlayerPanel.NONE &&
                                    (current.isVideo || !current.lyricsVisible)
                            },
                            onDragEnd = {
                                viewModel.endVolumeGesture()
                                if (axis == AXIS_SEEK) {
                                    if (canSeek) {
                                        viewModel.state.value.seekPreviewMs?.let { viewModel.seekTo(it) }
                                    }
                                    viewModel.clearSeekPreview()
                                }
                                axis = null
                            },
                            onDragCancel = {
                                viewModel.endVolumeGesture()
                                viewModel.clearSeekPreview()
                                axis = null
                            }
                        ) { change, amount ->
                            val current = viewModel.state.value
                            if (current.controlsLocked) {
                                change.consume()
                                return@detectDragGestures
                            }
                            if (axis == null) axis = if (abs(amount.x) >= abs(amount.y)) AXIS_SEEK else AXIS_VOLUME
                            when (axis) {
                                AXIS_SEEK -> if (canSeek && current.durationMs > 0) {
                                    change.consume()
                                    val delta = (amount.x / size.width * current.durationMs * 1.6f).toLong()
                                    val base = current.seekPreviewMs ?: current.positionMs
                                    viewModel.seekTo((base + delta).coerceIn(0, current.durationMs), fromGesture = true)
                                }
                                AXIS_VOLUME -> if (canAdjustVolume) {
                                    change.consume()
                                    // Keep fractional movement across system steps, but follow external key changes.
                                    if (current.volumePercent != appliedVolume) dragVolume = current.volumePercent.toFloat()
                                    dragVolume = (dragVolume - amount.y / 14f).coerceIn(0f, 200f)
                                    viewModel.setVolumeFromGesture(dragVolume.roundToInt())
                                    appliedVolume = viewModel.state.value.volumePercent
                                    val now = SystemClock.uptimeMillis()
                                    if (appliedVolume != current.volumePercent &&
                                        (lastVolumeHapticAt == 0L || now - lastVolumeHapticAt >= 60L)) {
                                        hapticView.performHapticFeedback(
                                            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                                            else HapticFeedbackConstants.CLOCK_TICK
                                        )
                                        lastVolumeHapticAt = now
                                    }
                                }
                            }
                        }
                    } finally {
                        viewModel.endVolumeGesture()
                    }
                }
                .pointerInput(state.currentTrack?.path, interactionsEnabled) {
                    if (!interactionsEnabled) return@pointerInput
                    detectTapGestures(
                        onTap = {
                            val current = viewModel.state.value
                            if (current.controlsLocked) videoLockActivity++
                            else if (current.isVideo) viewModel.setControlsVisible(!current.controlsVisible)
                            else if (!current.controlsVisible) audioRestoreActivity++
                        },
                        onDoubleTap = { offset ->
                            if (viewModel.state.value.controlsLocked) videoLockActivity++
                            else {
                                val left = offset.x < size.width / 2f
                                viewModel.skipBy(if (left) -15_000L else 15_000L)
                            }
                        }
                    )
                }
        ) {
            if (!embedded) {
                AndroidView(
                    factory = { context -> PlayerMpvView(context).also { viewModel.attach(it) } },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { viewModel.detach(it) }
                )
            }
            if (!state.isVideo) {
                Box(Modifier.fillMaxSize().then(
                    if (!embedded || state.lyricsVisible) Modifier.background(pageGradient) else Modifier
                ), contentAlignment = Alignment.Center) {
                    key(state.currentTrack?.path) {
                        if (state.lyricsVisible) {
                            key(state.selectedSubtitleId) {
                                PlayerLyricsView(
                                    lines = state.lyricLines,
                                    positionMs = state.seekPreviewMs ?: state.positionMs,
                                    subtitleText = state.subtitleText,
                                    loading = state.lyricsLoading,
                                    fontSize = state.subtitleFontSize,
                                    lineSpacing = state.audioSubtitleLineSpacing,
                                    alignment = state.audioSubtitleAlignment,
                                    onSeek = { viewModel.seekTo(it) },
                                    onOpenSubtitles = { viewModel.openPanel(PlayerPanel.SUBTITLE) },
                                    onUserScroll = {
                                        if (!viewModel.state.value.controlsVisible) audioRestoreActivity++
                                    },
                                    modifier = Modifier.fillMaxSize().padding(
                                        top = audioContentTopPadding, bottom = audioContentBottomPadding
                                    )
                                )
                            }
                        } else {
                            if (embedded) {
                                val artworkHitSize = if (landscape)
                                    (configuration.screenHeightDp.dp * 0.42f).coerceIn(96.dp, 180.dp) else 300.dp
                                Box(Modifier.padding(bottom = if (landscape) 24.dp else 120.dp)) {
                                    Box(Modifier.size(artworkHitSize)
                                        .clickable(enabled = interactionsEnabled,
                                            onClickLabel = stringResource(R.string.player_lyrics_show),
                                            role = Role.Button, onClick = viewModel::toggleLyrics))
                                }
                            } else {
                                PlayerAudioVisual(state.playing, state.loudness, state.loudnessSampleId, state.showLoudnessWaveform,
                                    Modifier.padding(bottom = if (landscape) 24.dp else 120.dp),
                                    trackPath = state.currentTrack?.path,
                                    onArtworkClick = viewModel::toggleLyrics)
                            }
                        }
                    }
                }
            }

            PlayerSubtitleOverlay(
                text = if (state.isVideo || state.lyricsVisible) "" else state.subtitleText,
                fontSize = state.subtitleFontSize,
                lineSpacing = state.audioSubtitleLineSpacing,
                alignment = state.audioSubtitleAlignment,
                modifier = Modifier.align(Alignment.Center).padding(bottom = if (landscape) 64.dp else 260.dp)
            )

            PlayerHintOverlay(
                hint = state.gestureHint,
                onDismiss = viewModel::clearHint,
                autoDismiss = !state.volumeGestureActive,
                modifier = Modifier.align(Alignment.Center)
            )

            if (!state.isVideo && state.panel == PlayerPanel.NONE &&
                !state.showTrackInfoDialog && !state.showPlaylistPickerDialog) {
                if (!state.controlsVisible && playerHeightPx > 0 &&
                    (expandedAudioTopInset == null || expandedAudioBottomInset == null)) {
                    MeasurePlayerAudioControls(state, viewModel, landscape) { topHeight, bottomHeight ->
                        expandedAudioTopInset = topHeight.toFloat()
                        expandedAudioBottomInset = bottomHeight.toFloat()
                    }
                }
                Box(Modifier.fillMaxSize().padding(top = audioButtonTopPadding, bottom = audioButtonBottomPadding)) {
                    PlayerAudioControlsToggle(
                        controlsVisible = state.controlsVisible,
                        revealActivity = audioRestoreActivity,
                        onToggleControls = {
                            val visible = viewModel.state.value.controlsVisible
                            viewModel.setControlsVisible(!visible)
                            if (visible) audioRestoreActivity++
                        },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }

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
                PlayerControlOverlay(state.isVideo, top = true, onBoundsChanged = {
                    topOverlayBounds = it
                    if (it != null && !state.isVideo) expandedAudioTopInset = it.bottom - gestureOrigin.y
                }) {
                    PlayerTopBar(state, viewModel, landscape, onCollapse)
                }
            }

            AnimatedVisibility(
                visible = state.controlsVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(220))
            ) {
                PlayerControlOverlay(state.isVideo, top = false, onBoundsChanged = {
                    bottomOverlayBounds = it
                    if (it != null && !state.isVideo) expandedAudioBottomInset = playerHeightPx - it.top + gestureOrigin.y
                }) {
                    PlayerControls(state, viewModel, landscape)
                }
            }

            if (interactionsEnabled) {
                PlayerPanelSheet(state, viewModel, onPickSubtitle = onPickSubtitle)

                PlayerTrackInfoDialog(
                    state = state,
                    onDismiss = viewModel::closeTrackInfoDialog,
                    onEditTags = onEditTags
                )

                PlayerPlaylistPickerDialog(
                    state = state,
                    onDismiss = viewModel::closePlaylistPickerDialog,
                    onCreatePlaylist = viewModel::createUserPlaylist,
                    onConfirmAdd = viewModel::addCurrentTrackToPlaylists
                )
            }

            if (state.isVideo && state.controlsLocked) {
                // Shield exiting controls too; only the edge unlock button remains interactive.
                Box(Modifier.fillMaxSize().testTag("player-video-lock-shield").pointerInput(state.currentTrack?.path) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        videoLockActivity++
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            videoLockActivity++
                        } while (event.changes.any { it.pressed })
                    }
                })
            }
            if (state.isVideo && state.panel == PlayerPanel.NONE &&
                !state.showTrackInfoDialog && !state.showPlaylistPickerDialog) {
                PlayerVideoLockControl(
                    locked = state.controlsLocked,
                    controlsVisible = state.controlsVisible,
                    revealActivity = videoLockActivity,
                    onToggleLock = { viewModel.setVideoControlsLocked(!viewModel.state.value.controlsLocked) },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }
}

private val LocalVideoControls = staticCompositionLocalOf { false }

/** A hidden rotation/recreation needs current bar sizes without drawing or placing their controls. */
@Composable
private fun MeasurePlayerAudioControls(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    landscape: Boolean,
    onMeasured: (Int, Int) -> Unit
) {
    SubcomposeLayout { constraints ->
        val barConstraints = constraints.copy(minHeight = 0)
        val top = subcompose("top") { PlayerTopBar(state, viewModel, landscape) }
            .single().measure(barConstraints)
        val bottom = subcompose("bottom") { PlayerControls(state, viewModel, landscape) }
            .single().measure(barConstraints)
        onMeasured(top.height, bottom.height)
        layout(0, 0) {}
    }
}

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
private fun PlayerTopBar(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    landscape: Boolean,
    onCollapse: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player-top-bar")
            .windowInsetsPadding(if (landscape) WindowInsets(0, 0, 0, 0)
                else WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 8.dp, vertical = if (landscape) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCollapse ?: viewModel::requestFinish,
            modifier = Modifier.size(if (landscape) 42.dp else 48.dp).testTag("player-back")) {
            Icon(
                painterResource(if (onCollapse == null) R.drawable.ic_back else R.drawable.ic_arrow_right),
                contentDescription = stringResource(if (onCollapse == null) R.string.tools_navigate_back else R.string.player_collapse),
                modifier = if (onCollapse == null) Modifier else Modifier.rotate(90f),
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
            PlayerQuickActionsRow(state, viewModel, compact = true)
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
            .padding(horizontal = 8.dp)
            .padding(bottom = if (landscape) 6.dp else 8.dp)
    ) {
        if (!landscape) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).offset(y = 8.dp).zIndex(1f),
                verticalAlignment = Alignment.CenterVertically) {
                PlayerQuickActionsRow(state, viewModel)
                if (!state.isVideo && state.lyricsVisible) {
                    PlayerLyricsSource(state, viewModel, Modifier.weight(1f))
                }
            }
        } else if (!state.isVideo && state.lyricsVisible) {
            Row(Modifier.fillMaxWidth().offset(y = 8.dp).zIndex(1f), horizontalArrangement = Arrangement.End) {
                PlayerLyricsSource(state, viewModel, Modifier.fillMaxWidth(0.55f))
            }
        }
        PlayerSeekBar(state, viewModel, Modifier.padding(horizontal = 12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("player-transport-row"),
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
            Box(Modifier.padding(horizontal = 12.dp)) {
                PlayerToolsRow(state, viewModel, compact = false)
            }
        }
    }
}

@Composable
private fun PlayerQuickActionsRow(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val enabled = state.currentTrack != null
    val iconButtonSize = if (compact) 38.dp else 40.dp
    Row(
        modifier = modifier.testTag("player-quick-actions-row"),
        horizontalArrangement = if (compact) Arrangement.spacedBy(0.dp) else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = viewModel::toggleFavorite,
            enabled = enabled,
            modifier = Modifier.size(iconButtonSize).testTag("player-quick-favorite")
        ) {
            Icon(
                painter = painterResource(
                    if (state.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                ),
                contentDescription = stringResource(
                    if (state.isFavorite) R.string.player_action_unfavorite else R.string.player_action_favorite
                ),
                modifier = Modifier.size(20.dp),
                tint = if (state.isFavorite) PlayerControlColors.Accent else PlayerControlColors.Secondary
            )
        }
        IconButton(
            onClick = viewModel::openTrackInfoDialog,
            enabled = enabled,
            modifier = Modifier.size(iconButtonSize).testTag("player-quick-info")
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_info),
                contentDescription = stringResource(R.string.player_action_info),
                modifier = Modifier.size(20.dp),
                tint = PlayerControlColors.Secondary
            )
        }
        IconButton(
            onClick = viewModel::openPlaylistPickerDialog,
            enabled = enabled,
            modifier = Modifier.size(iconButtonSize).testTag("player-quick-add-playlist")
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_playlist_add),
                contentDescription = stringResource(R.string.player_action_add_to_playlist),
                modifier = Modifier.size(21.dp),
                tint = PlayerControlColors.Secondary
            )
        }
    }
}

/** The lyric source shares the action area, leaving the whole center for the document. */
@Composable
private fun PlayerLyricsSource(state: PlayerUiState, viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val label = state.subtitleOptions.firstOrNull {
        it.id == state.selectedSubtitleId && it.kind != PlayerSubtitleKind.NONE
    }?.label ?: stringResource(R.string.player_lyrics_title)
    Row(modifier.heightIn(min = 48.dp).testTag("player-lyrics-source"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        Text(label, modifier = Modifier.weight(1f).testTag("player-lyrics-source-label"),
            color = PlayerControlColors.Secondary, fontSize = 12.sp, maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = { viewModel.setLyricsVisible(false) }, modifier = Modifier.size(40.dp).testTag("player-lyrics-close")) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.player_lyrics_close),
                modifier = Modifier.size(20.dp), tint = PlayerControlColors.Secondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSeekBar(state: PlayerUiState, viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val enabled = state.ready && state.durationMs > 0
    val color = PlayerControlColors.Accent
    val colors = SliderDefaults.colors(
        thumbColor = color,
        activeTrackColor = color,
        inactiveTrackColor = color.copy(alpha = 0.24f),
        disabledThumbColor = color.copy(alpha = 0.38f),
        disabledActiveTrackColor = color.copy(alpha = 0.38f),
        disabledInactiveTrackColor = color.copy(alpha = 0.12f)
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            formatPlaybackTime(state.seekPreviewMs ?: state.positionMs),
            color = PlayerControlColors.Secondary,
            fontSize = 12.sp,
            maxLines = 1
        )
        Slider(
            value = (state.seekPreviewMs ?: state.positionMs).toFloat(),
            onValueChange = { viewModel.seekTo(it.toLong(), fromGesture = true) },
            onValueChangeFinished = {
                viewModel.state.value.seekPreviewMs?.let { viewModel.seekTo(it) }
                viewModel.clearSeekPreview()
            },
            valueRange = 0f..state.durationMs.coerceAtLeast(1L).toFloat(),
            enabled = enabled,
            colors = colors,
            thumb = {
                Box(Modifier.size(20.dp)
                    .shadow(1.dp, CircleShape)
                    .background(if (enabled) color else color.copy(alpha = 0.38f), CircleShape))
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    enabled = enabled,
                    colors = colors,
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null
                )
            },
            modifier = Modifier.weight(1f).height(48.dp).testTag("player-seek")
        )
        Text(
            formatPlaybackTime(state.durationMs),
            color = PlayerControlColors.Secondary,
            fontSize = 12.sp,
            maxLines = 1
        )
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
