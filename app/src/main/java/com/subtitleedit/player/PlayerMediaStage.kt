package com.subtitleedit.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

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
    audioArtworkModifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // This node must never be keyed by the track, expanded state or animation progress.
        AndroidView(
            factory = { context -> PlayerMpvView(context).also(viewModel::attach) },
            modifier = Modifier.fillMaxSize().then(videoModifier),
            onRelease = viewModel::detach
        )
        if (!state.isVideo) {
            key(state.currentTrack?.path) {
                PlayerAudioVisual(
                    playing = state.playing,
                    loudness = state.loudness,
                    loudnessSampleId = state.loudnessSampleId,
                    showLoudnessWaveform = state.showLoudnessWaveform,
                    modifier = audioArtworkModifier,
                    trackPath = state.currentTrack?.path
                )
            }
        }
    }
}
