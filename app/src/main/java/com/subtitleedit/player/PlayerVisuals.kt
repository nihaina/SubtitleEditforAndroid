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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import com.subtitleedit.library.MediaArtworkLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Embedded artwork, or the default vinyl, with the audio's recent measured loudness. */
@Composable
internal fun PlayerAudioVisual(
    playing: Boolean,
    loudness: Float,
    loudnessSampleId: Long,
    showLoudnessWaveform: Boolean,
    modifier: Modifier = Modifier,
    trackPath: String? = null,
    onArtworkClick: (() -> Unit)? = null
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val discSize = if (landscape) (configuration.screenHeightDp.dp * 0.42f).coerceIn(96.dp, 180.dp) else 300.dp
    val coverSize = discSize * 0.9f
    val targetSizePx = with(LocalDensity.current) { coverSize.roundToPx().coerceIn(32, 1024) }
    val view = LocalView.current
    var artworkRevision by remember(trackPath) { mutableIntStateOf(0) }
    // Returning from the tag editor may replace or remove this file's embedded picture.
    DisposableEffect(view, trackPath) {
        val owner = view.findViewTreeLifecycleOwner()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) artworkRevision++
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    var artwork by remember(trackPath, targetSizePx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(trackPath, targetSizePx, artworkRevision) {
        artwork = if (trackPath == null) null else withContext(Dispatchers.IO) {
            try {
                MediaArtworkLoader.loadCover(trackPath, targetSizePx, allowVideoFrame = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    // Stack the two actual drawing areas: top padding on a centered overlay allows
    // the waveform to grow back into the artwork's lower edge.
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val cover = artwork
        val artworkClick = if (onArtworkClick != null) Modifier.clickable(
            onClickLabel = stringResource(R.string.player_lyrics_show), role = Role.Button,
            onClick = onArtworkClick
        ) else Modifier
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(coverSize).clip(RoundedCornerShape(20.dp))
                    .testTag("player-audio-cover").then(artworkClick),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.testTag("player-audio-vinyl").then(artworkClick)) {
                if (playing) AnimatedVinyl(discSize) else StaticVinyl(discSize)
            }
        }
        if (showLoudnessWaveform) {
            PlayerLoudnessWaveform(playing, loudness, loudnessSampleId)
        }
    }
}

private const val LOUDNESS_BARS = 21
private const val LOUDNESS_THRESHOLD_SCALE = 1.3f

@Composable
private fun AnimatedVinyl(discSize: Dp) {
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
    VinylDisc(angle = angle, scale = pulseScale, size = discSize)
}

@Composable
private fun StaticVinyl(discSize: Dp) {
    VinylDisc(angle = 0f, scale = 1f, size = discSize)
}

@Composable
private fun VinylDisc(angle: Float, scale: Float, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
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
                    .size(size * fraction)
                    .clip(CircleShape)
                    .background(PlayerColors.OnDark.copy(alpha = 0.04f))
            )
        }
        Box(
            modifier = Modifier
                .size(size * 0.4f)
                .rotate(angle)
                .clip(CircleShape)
                .background(PlayerColors.accentBrush),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(size * (26f / 300f))
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
        // A paused stream has no new PCM. Keep the last measured shape until playback resumes.
        if (playing && sampleId > 0L) {
            val sample = (loudness.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f) / LOUDNESS_THRESHOLD_SCALE
            history = history.drop(1) + sample
        }
    }
    Row(
        modifier = modifier.testTag("player-loudness-waveform"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        history.forEach { sample -> LoudnessBar(sample) }
    }
}

@Composable
private fun LoudnessBar(height: Float) {
    Box(
        modifier = Modifier
            .width(4.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(3.dp)),
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
internal fun PlayerSubtitleOverlay(
    text: String,
    fontSize: Int,
    modifier: Modifier = Modifier,
    lineSpacing: Int = PlayerPreferences.DEFAULT_AUDIO_SUBTITLE_LINE_SPACING,
    alignment: PlayerSubtitleAlignment = PlayerSubtitleAlignment.CENTER
) {
    AnimatedVisibility(
        visible = text.isNotBlank(),
        modifier = modifier,
        enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.94f),
        exit = fadeOut(tween(180))
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = when (alignment) {
            PlayerSubtitleAlignment.LEFT -> Alignment.CenterStart
            PlayerSubtitleAlignment.CENTER -> Alignment.Center
            PlayerSubtitleAlignment.RIGHT -> Alignment.CenterEnd
        }) {
            Text(
                text = text,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(PlayerColors.Surface.copy(alpha = 0.94f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                color = PlayerColors.OnDark,
                style = playerAudioSubtitleTextStyle(fontSize, lineSpacing, alignment),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Transient on-screen message for gestures, bookmarks and timer changes. */
@Composable
internal fun PlayerHintOverlay(
    hint: PlayerGestureHint?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismiss: Boolean = true
) {
    LaunchedEffect(hint?.token, autoDismiss) {
        if (hint == null || !autoDismiss) return@LaunchedEffect
        kotlinx.coroutines.delay(1_100)
        onDismiss()
    }
    AnimatedVisibility(
        visible = hint != null,
        modifier = modifier,
        enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.9f),
        exit = fadeOut(tween(220))
    ) {
        // AnimatedVisibility keeps this content alive during exit; keep its text too.
        var displayedText by remember { mutableStateOf(hint?.text.orEmpty()) }
        var displayedIcon by remember { mutableIntStateOf(hint?.iconRes ?: R.drawable.ic_player_mode) }
        if (hint != null) {
            displayedText = hint.text
            displayedIcon = hint.iconRes
        }
        Column(
            modifier = Modifier
                .testTag("player-gesture-hint")
                .clip(RoundedCornerShape(18.dp))
                .background(PlayerColors.Surface.copy(alpha = 0.96f))
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(displayedIcon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = PlayerColors.Accent
            )
            Text(
                text = displayedText,
                color = PlayerColors.OnDark,
                fontSize = 14.sp,
                maxLines = 2,
                textAlign = TextAlign.Center
            )
        }
    }
}
