package com.subtitleedit.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R

/** Vinyl artwork with the recent measured loudness of the audio being played. */
@Composable
internal fun PlayerAudioVisual(
    playing: Boolean,
    loudness: Float,
    loudnessSampleId: Long,
    showLoudnessWaveform: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (playing) AnimatedVinyl() else StaticVinyl()
        if (showLoudnessWaveform) {
            PlayerLoudnessWaveform(playing, loudness, loudnessSampleId, Modifier.padding(top = 312.dp))
        }
    }
}

private const val LOUDNESS_BARS = 21

@Composable
private fun AnimatedVinyl() {
    val spin = rememberInfiniteTransition(label = "vinyl-spin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing)),
        label = "vinyl-angle"
    )
    val pulse = rememberInfiniteTransition(label = "vinyl-pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "vinyl-pulse-scale"
    )
    VinylDisc(angle = angle, scale = pulseScale)
}

@Composable
private fun StaticVinyl() {
    VinylDisc(angle = 0f, scale = 1f)
}

@Composable
private fun VinylDisc(angle: Float, scale: Float) {
    Box(
        modifier = Modifier
            .size(300.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(PlayerColors.SurfaceHigh, PlayerColors.Surface, PlayerColors.Background),
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        listOf(0.86f, 0.72f, 0.58f).forEach { fraction ->
            Box(
                modifier = Modifier
                    .size(300.dp * fraction)
                    .clip(CircleShape)
                    .background(PlayerColors.OnDark.copy(alpha = 0.04f))
            )
        }
        Box(
            modifier = Modifier
                .size(120.dp)
                .rotate(angle)
                .clip(CircleShape)
                .background(PlayerColors.accentBrush),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(PlayerColors.Background)
            )
        }
    }
}

@Composable
private fun PlayerLoudnessWaveform(playing: Boolean, loudness: Float, sampleId: Long, modifier: Modifier = Modifier) {
    var history by remember { mutableStateOf(List(LOUDNESS_BARS) { 0f }) }
    LaunchedEffect(playing, sampleId) {
        if (!playing) {
            history = List(LOUDNESS_BARS) { 0f }
            return@LaunchedEffect
        }
        if (sampleId > 0L) {
            val sample = loudness.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            history = history.drop(1) + sample
        }
    }
    Row(
        modifier = modifier.testTag("player-loudness-waveform"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        history.forEach { sample -> LoudnessBar(if (playing) sample else 0f) }
    }
}

@Composable
private fun LoudnessBar(height: Float) {
    Box(
        modifier = Modifier
            .width(4.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(PlayerColors.OnDarkVariant.copy(alpha = 0.12f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(44.dp * height)
                .clip(RoundedCornerShape(3.dp))
                .background(PlayerColors.accentBrush)
        )
    }
}

/** Floating subtitle cue for audio playback; mpv renders subtitles itself for video. */
@Composable
internal fun PlayerSubtitleOverlay(text: String, fontSize: Int, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = text.isNotBlank(),
        modifier = modifier,
        enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.94f),
        exit = fadeOut(tween(180))
    ) {
        Text(
            text = text,
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PlayerColors.Surface.copy(alpha = 0.94f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            color = PlayerColors.OnDark,
            fontSize = fontSize.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Transient on-screen message for gestures, bookmarks and timer changes. */
@Composable
internal fun PlayerHintOverlay(hint: PlayerGestureHint?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(hint?.token) {
        if (hint == null) return@LaunchedEffect
        kotlinx.coroutines.delay(1_100)
        onDismiss()
    }
    AnimatedVisibility(
        visible = hint != null,
        modifier = modifier,
        enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.9f),
        exit = fadeOut(tween(220))
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(PlayerColors.Surface.copy(alpha = 0.96f))
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_player_mode),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = PlayerColors.Accent
            )
            Text(
                text = hint?.text.orEmpty(),
                color = PlayerColors.OnDark,
                fontSize = 14.sp,
                maxLines = 2,
                textAlign = TextAlign.Center
            )
        }
    }
}
