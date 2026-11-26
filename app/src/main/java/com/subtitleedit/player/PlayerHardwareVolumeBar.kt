package com.subtitleedit.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtitleedit.R
import kotlin.math.roundToInt

internal fun playerVolumePercentAt(positionY: Float, trackTop: Float, trackBottom: Float): Int {
    val range = trackBottom - trackTop
    if (range <= 0f) return 0
    return (((trackBottom - positionY) / range).coerceIn(0f, 1f) * 100f).roundToInt()
}

@Composable
internal fun PlayerHardwareVolumeBar(viewModel: PlayerViewModel) {
    val visible by viewModel.hardwareVolumeBar.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val requestedHeight = if (landscape) 200.dp else 240.dp
    val description = stringResource(R.string.player_usb_hardware_volume_level, state.volumePercent)
    val video = state.showsVideo
    val containerColor = if (video) Color.Black.copy(alpha = 0.58f) else PlayerColors.Surface.copy(alpha = 0.96f)
    val foreground = if (video) Color.White else PlayerColors.Accent
    val inactiveTrack = if (video) Color.White.copy(alpha = 0.4f) else PlayerColors.Accent.copy(alpha = 0.3f)

    if (!state.usbHardwareVolume) return

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 16.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val barHeight = requestedHeight.coerceAtMost(maxHeight)
        val topInset = with(density) { (TRACK_TOP_INSET + THUMB_RADIUS).toPx() }
        val bottomInset = with(density) { (TRACK_BOTTOM_INSET + THUMB_RADIUS).toPx() }
        val heightPx = with(density) { barHeight.toPx() }
        val trackTop = topInset
        val trackBottom = heightPx - bottomInset

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(y = -maxHeight * VERTICAL_POSITION_BIAS)
                .width(BAR_WIDTH)
                .height(barHeight),
            enter = slideInHorizontally(
                animationSpec = tween(MOTION_DURATION_MS),
                initialOffsetX = { with(density) { REVEAL_DISTANCE.roundToPx() } },
            ) + fadeIn(animationSpec = tween(MOTION_DURATION_MS)),
            exit = slideOutHorizontally(
                animationSpec = tween(MOTION_DURATION_MS),
                targetOffsetX = { with(density) { REVEAL_DISTANCE.roundToPx() } },
            ) + fadeOut(animationSpec = tween(MOTION_DURATION_MS)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(containerColor)
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            state.volumePercent.toFloat(),
                            0f..100f,
                            steps = 99,
                        )
                        setProgress { progress ->
                            viewModel.setVolume(progress.roundToInt().coerceIn(0, 100))
                            true
                        }
                    }
                    .pointerInput(barHeight, density) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val adjustsVolume = down.position.y in trackTop..trackBottom
                            var lastRequestedPercent: Int? = null
                            fun updateVolume(positionY: Float) {
                                val percent = playerVolumePercentAt(positionY, trackTop, trackBottom)
                                if (percent == lastRequestedPercent) {
                                    viewModel.showHardwareVolumeBar()
                                } else {
                                    lastRequestedPercent = percent
                                    viewModel.setVolume(percent)
                                }
                            }
                            down.consume()
                            if (adjustsVolume) {
                                updateVolume(down.position.y)
                            } else {
                                viewModel.showHardwareVolumeBar()
                            }

                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val activeChanges = event.changes.filter { it.pressed }
                                if (adjustsVolume) {
                                    activeChanges.firstOrNull()?.let { change ->
                                        updateVolume(change.position.y)
                                    }
                                }
                                event.changes.forEach { it.consume() }
                                pressed = activeChanges.isNotEmpty()
                                if (pressed && !adjustsVolume) viewModel.showHardwareVolumeBar()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = BAR_VERTICAL_PADDING),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LABEL_HEIGHT),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "${state.volumePercent}%",
                            color = foreground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                        Text(
                            text = "DAC",
                            color = if (video) Color.White.copy(alpha = 0.68f) else PlayerColors.OnDarkVariant,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        VerticalVolumeTrack(
                            percent = state.volumePercent,
                            foreground = foreground,
                            inactiveTrack = inactiveTrack,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FOOTER_HEIGHT),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(
                                if (state.volumePercent == 0) R.drawable.ic_player_muted
                                else R.drawable.ic_player_volume
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(foreground),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalVolumeTrack(
    percent: Int,
    foreground: Color,
    inactiveTrack: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val trackWidth = 4.dp.toPx()
        val thumbRadius = THUMB_RADIUS.toPx()
        val trackHeight = (size.height - thumbRadius * 2).coerceAtLeast(0f)
        val centerX = size.width / 2f
        val trackLeft = centerX - trackWidth / 2f
        val trackTop = thumbRadius
        val trackBottom = trackTop + trackHeight
        val thumbCenterY = trackBottom - trackHeight * percent.coerceIn(0, 100) / 100f
        val radius = CornerRadius(trackWidth / 2f)

        drawRoundRect(
            color = inactiveTrack,
            topLeft = Offset(trackLeft, trackTop),
            size = Size(trackWidth, trackHeight),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = foreground,
            topLeft = Offset(trackLeft, thumbCenterY),
            size = Size(trackWidth, (trackBottom - thumbCenterY).coerceAtLeast(0f)),
            cornerRadius = radius,
        )
        drawCircle(
            color = foreground,
            radius = 7.dp.toPx(),
            center = Offset(centerX, thumbCenterY),
        )
    }
}

private val BAR_WIDTH = 64.dp
private val BAR_VERTICAL_PADDING = 6.dp
private val LABEL_HEIGHT = 34.dp
private val FOOTER_HEIGHT = 28.dp
private val TRACK_TOP_INSET = BAR_VERTICAL_PADDING + LABEL_HEIGHT
private val TRACK_BOTTOM_INSET = BAR_VERTICAL_PADDING + FOOTER_HEIGHT
private val THUMB_RADIUS = 7.dp
private val REVEAL_DISTANCE = 10.dp
private const val VERTICAL_POSITION_BIAS = 0.12f
private const val MOTION_DURATION_MS = 180
