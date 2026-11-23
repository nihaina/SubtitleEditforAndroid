package com.subtitleedit.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.res.Configuration

/**
 * The output stays mounted while the host animates between the player and its mini bar.
 * Transform [videoModifier] rather than resizing the TextureView on every animation frame.
 * Artwork has its own drawing rectangle so the thumbnail contains the complete cover.
 */
@Composable
internal fun PlayerMediaStage(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    videoModifier: Modifier = Modifier,
    audioArtworkModifier: Modifier = Modifier,
    onArtworkBoundsChanged: ((Rect?) -> Unit)? = null
) {
    val state by viewModel.state.collectAsState()
    Box(modifier.fillMaxSize().then(if (state.showsVideo) Modifier.background(Color.Black) else Modifier),
        contentAlignment = Alignment.Center) {
        // This node must never be keyed by the track, expanded state or animation progress.
        AndroidView(
            factory = { context -> PlayerMpvView(context).also(viewModel::attach) },
            modifier = Modifier.fillMaxSize().then(videoModifier),
            onRelease = viewModel::detach
        )
        if (state.isVideo && state.audioMode) {
            val configuration = LocalConfiguration.current
            val noteSize = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                (configuration.screenHeightDp.dp * 0.42f).coerceIn(96.dp, 180.dp) else 300.dp
            PlayerVideoAudioNote(audioArtworkModifier.size(noteSize))
        } else if (!state.isVideo) {
            key(state.currentTrack?.path) {
                PlayerAudioVisual(
                    playing = state.playing,
                    loudness = state.loudness,
                    loudnessSampleId = state.loudnessSampleId,
                    showLoudnessWaveform = state.showLoudnessWaveform,
                    modifier = audioArtworkModifier,
                    trackPath = state.currentTrack?.path,
                    onArtworkBoundsChanged = onArtworkBoundsChanged
                )
            }
        }
    }
}
