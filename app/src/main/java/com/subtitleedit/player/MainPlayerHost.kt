package com.subtitleedit.player

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.subtitleedit.ui.MediaCategoryRevealRequest
import kotlin.math.abs
import kotlin.math.roundToInt

/** The media output is mounted once; only its drawing transform changes when docking. */
@Composable
internal fun MainPlayerHost(
    viewModel: PlayerViewModel,
    onEditTags: () -> Unit,
    onPickSubtitle: () -> Unit,
    onImmersiveChanged: (Boolean, Boolean) -> Unit,
    enabled: Boolean = true,
    onNavigateToCategory: ((MediaCategoryRevealRequest) -> Unit)? = null,
    onLocate: ((String) -> Unit)? = null,
    content: @Composable (@Composable () -> Unit) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val expanded by viewModel.expanded.collectAsState()
    val outputSuspended by viewModel.outputSuspended.collectAsState()
    val outputGeneration by viewModel.outputGeneration.collectAsState()
    val inPictureInPicture = rememberPlayerPictureInPicture()
    val enterPictureInPicture = rememberPlayerPictureInPictureAction(viewModel)
    val density = LocalDensity.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val animation = remember { Animatable(if (expanded) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var settleStart by remember { mutableStateOf<Float?>(null) }
    var settleVelocity by remember { mutableFloatStateOf(0f) }
    var settleRevision by remember { mutableIntStateOf(0) }
    var miniBounds by remember { mutableStateOf<Rect?>(null) }
    var audioArtworkBounds by remember { mutableStateOf<Rect?>(null) }
    val hasTrack = enabled && state.currentTrack != null
    BackHandler(enabled = !inPictureInPicture && hasTrack && (expanded || dragging || animation.value > 0f)) {
        viewModel.collapse()
    }

    LaunchedEffect(expanded, dragging, settleRevision) {
        if (dragging) {
            animation.stop()
        } else {
            settleStart?.let { animation.snapTo(it) }
            settleStart = null
            animation.animateTo(if (expanded) 1f else 0f,
                spring(dampingRatio = 0.82f, stiffness = 320f), initialVelocity = settleVelocity)
            settleVelocity = 0f
        }
    }
    val progress = if (inPictureInPicture) 1f else
        (if (dragging) dragProgress else settleStart ?: animation.value).coerceIn(0f, 1f)
    val immersive = hasTrack && (expanded || dragging || progress > 0f)
    SideEffect { onImmersiveChanged(immersive, hasTrack && (immersive || state.showsVideo && state.playing)) }
    DisposableEffect(Unit) { onDispose { onImmersiveChanged(false, false) } }

    BoxWithConstraints(Modifier.fillMaxSize().then(if (enabled) Modifier.testTag("main-player-host")
        .semantics { testTagsAsResourceId = true } else Modifier)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val fullBounds = Rect(0f, 0f, widthPx, heightPx)
        val mini = miniBounds
        val currentState by rememberUpdatedState(state)
        val currentProgress by rememberUpdatedState(progress)
        val currentMini by rememberUpdatedState(mini)
        val currentExpanded by rememberUpdatedState(expanded)
        val topDragHeight = with(density) { 112.dp.toPx() }
        val flingThreshold = with(density) { 700.dp.toPx() }
        val travel = (mini?.top ?: heightPx).coerceAtLeast(heightPx * 0.5f)

        Box(Modifier.fillMaxSize().pointerInput(hasTrack, widthPx, heightPx, mini, inPictureInPicture) {
            if (!hasTrack || inPictureInPicture) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val snapshot = currentState
                val blocked = snapshot.controlsLocked || snapshot.panel != PlayerPanel.NONE ||
                    snapshot.showTrackInfoDialog || snapshot.showPlaylistPickerDialog || snapshot.showEndBehaviorDialog
                val startProgress = currentProgress
                val fromMini = startProgress < 0.01f && currentMini?.contains(down.position) == true
                val fromTop = startProgress >= 0.01f && down.position.y <= topDragHeight
                if (blocked || (!fromMini && !fromTop)) return@awaitEachGesture
                val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                var accepted = false
                var cancelled = false
                var completed = false
                var last = down.position
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        velocity.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) break
                        val total = change.position - down.position
                        if (!accepted) {
                            if (abs(total.x) > viewConfiguration.touchSlop && abs(total.x) > abs(total.y)) break
                            if (abs(total.y) <= viewConfiguration.touchSlop) continue
                            if ((fromMini && total.y > 0f) || (!fromMini && startProgress >= 0.99f && total.y < 0f)) break
                            dragProgress = startProgress
                            dragging = true
                            accepted = true
                        }
                        if (event.changes.count { it.pressed } > 1) { cancelled = true; break }
                        dragProgress = PlayerSheetMotion.progressAfterDrag(dragProgress,
                            change.position.y - last.y, travel)
                        last = change.position
                        change.consume()
                    }
                    completed = true
                } finally {
                    if (accepted) {
                        val velocityY = if (cancelled || !completed) 0f else velocity.calculateVelocity().y
                        settleStart = dragProgress
                        settleVelocity = -velocityY / travel
                        val open = if (cancelled || !completed) currentExpanded else
                            PlayerSheetMotion.settleExpanded(dragProgress, velocityY, flingThreshold)
                        if (open) viewModel.expand() else viewModel.collapse()
                        settleRevision++
                        dragging = false
                    }
                }
            }
        }) {
            content {
                if (hasTrack) Box(Modifier.fillMaxWidth().height(72.dp)
                    .onGloballyPositioned { miniBounds = it.boundsInRoot() })
            }
            if (inPictureInPicture) Box(Modifier.fillMaxSize().background(Color.Black))
            if (hasTrack && (mini != null || inPictureInPicture) && widthPx > 0f && heightPx > 0f) {
                val dockBounds = mini ?: fullBounds
                val sheet = PlayerSheetMotion.mediaBounds(dockBounds, fullBounds, progress)
                val thumbnail = with(density) {
                    Rect(12.dp.toPx(), dockBounds.top + 12.dp.toPx(), 84.dp.toPx(), dockBounds.top + 60.dp.toPx())
                }
                val media = PlayerSheetMotion.mediaBounds(thumbnail, fullBounds, progress)
                val videoScale = media.width / widthPx
                if (!inPictureInPicture) {
                    val background = PlayerColors.pageGradient
                    Box(Modifier.offset { IntOffset(0, sheet.top.roundToInt()) }
                        .fillMaxWidth().height(with(density) { sheet.height.toDp() }).background(background))
                }

                // Measure the TextureView at the viewport size throughout the animation.
                // Resizing it on each drag frame would rebuild mpv's EGL output each time.
                val discSize = if (landscape)
                    (LocalConfiguration.current.screenHeightDp.dp * 0.42f).coerceIn(96.dp, 180.dp) else 300.dp
                val discPx = with(density) { discSize.toPx() }
                val bottomPadding = when {
                    inPictureInPicture -> 0.dp
                    landscape -> 24.dp
                    else -> 120.dp
                }
                val artOffset = with(density) {
                    bottomPadding.toPx() / 2f + if (!state.isVideo && state.showLoudnessWaveform) 30.dp.toPx() else 0f
                }
                val artScale = thumbnail.height / discPx + (1f - thumbnail.height / discPx) * progress
                if (!outputSuspended) key(outputGeneration) { PlayerMediaStage(
                    viewModel = viewModel,
                    modifier = Modifier.playerMediaViewport(media),
                    onArtworkBoundsChanged = { audioArtworkBounds = it },
                    videoModifier = Modifier.graphicsLayer {
                        alpha = if (state.showsVideo) 1f else 0f
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = videoScale
                        scaleY = videoScale
                        translationX = media.left
                        translationY = media.center.y - heightPx * videoScale / 2f
                    },
                    audioArtworkModifier = Modifier.graphicsLayer {
                        scaleX = artScale
                        scaleY = artScale
                        translationX = (thumbnail.center.x - widthPx / 2f) * (1f - progress)
                        translationY = (thumbnail.center.y - heightPx / 2f) * (1f - progress) +
                            artOffset * artScale * (1f - progress)
                    }.padding(bottom = bottomPadding)
                ) }

                if (progress > 0f) Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = ((progress - 0.25f) / 0.75f).coerceIn(0f, 1f)
                    translationY = (1f - progress) * travel * 0.55f
                }) {
                    PlayerScreen(viewModel, onEditTags = onEditTags, onPickSubtitle = onPickSubtitle,
                        embedded = true,
                        interactionsEnabled = !inPictureInPicture && expanded && !dragging && progress >= 0.999f,
                        embeddedArtworkBounds = audioArtworkBounds,
                        onCollapse = viewModel::collapse, onNavigateToCategory = onNavigateToCategory,
                        onLocate = onLocate, onEnterPictureInPicture = enterPictureInPicture,
                        inPictureInPicture = inPictureInPicture)
                }
                if (progress < 0.34f) PlayerMiniBar(state, viewModel::expand, viewModel::togglePlay,
                    viewModel::previous, viewModel::next,
                    modifier = Modifier.offset { IntOffset(0, sheet.top.roundToInt()) },
                    contentAlpha = (1f - progress * 3f).coerceIn(0f, 1f))
                if (!inPictureInPicture) PlayerHardwareVolumeBar(viewModel)
            }
        }
    }
}
