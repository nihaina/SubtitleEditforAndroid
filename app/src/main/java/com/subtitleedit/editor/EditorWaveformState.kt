package com.subtitleedit.editor

import android.graphics.Bitmap
import com.subtitleedit.model.SubtitleEntry

/** The two visualizations rendered by the editor timeline. */
enum class EditorWaveformDisplayMode {
    WAVEFORM,
    SPECTROGRAM
}

/** The editing operation currently applied to a subtitle block. */
enum class EditorWaveformDragMode {
    NONE,
    MOVE,
    RESIZE_START,
    RESIZE_END
}

/**
 * Snapshot consumed by the Compose waveform surface.
 *
 * The controller owns this state and publishes a new snapshot after every data or gesture
 * change. Bitmap values are deliberately kept in the snapshot: they are decoded off the UI
 * thread and are immutable while they are displayed by Compose.
 */
data class EditorWaveformState(
    val durationMs: Long = 0L,
    val subtitles: List<SubtitleEntry> = emptyList(),
    val visibleStartMs: Long = 0L,
    val visibleDurationMs: Long = DEFAULT_VISIBLE_DURATION_MS,
    val currentPosition: Float = 0f,
    val chunkData: List<FloatArray?> = emptyList(),
    val spectrogramChunks: Map<Int, Bitmap> = emptyMap(),
    val selectedIndices: Set<Int> = emptySet(),
    val limitedPlaybackIndex: Int? = null,
    val amplitudeScale: Float = 1f,
    val displayMode: EditorWaveformDisplayMode = EditorWaveformDisplayMode.WAVEFORM,
    val initialized: Boolean = false,
    val isExpanded: Boolean = true,
    val isTimestamping: Boolean = false,
    val timestampStartMs: Long = 0L,
    val timestampAnchorMs: Long = 0L,
    val hasPlayableMedia: Boolean = true,
    val hasAudioTrack: Boolean = true,
    val isPreparingCacheIndex: Boolean = false,
    val cacheIndexFailure: String? = null,
    val isWaveformGenerated: Boolean = false,
    val isWaveformGenerating: Boolean = false,
    val isSpectrogramGenerationStarted: Boolean = false,
    val spectrogramTotalChunks: Int = 0,
    val spectrogramDoneChunks: Int = 0,
    val spectrogramIsGenerating: Boolean = false,
    val viewportWidthPx: Int = 0,
    val viewportHeightPx: Int = 0
) {
    val currentPositionMs: Long
        get() = (durationMs * currentPosition.coerceIn(0f, 1f)).toLong()

    val canGenerateWaveform: Boolean
        get() = hasAudioTrack && !isPreparingCacheIndex && cacheIndexFailure == null &&
            !isWaveformGenerated && !isWaveformGenerating

    val canGenerateSpectrogram: Boolean
        get() = hasAudioTrack && !isPreparingCacheIndex && cacheIndexFailure == null &&
            !isSpectrogramGenerationStarted

    val totalChunks: Int
        get() = chunkData.size

    companion object {
        const val CHUNK_DURATION_MS = 30_000L
        const val DEFAULT_VISIBLE_DURATION_MS = 180_000L
        const val MIN_VISIBLE_DURATION_MS = 2_000L
        const val MAX_VISIBLE_DURATION_MS = 600_000L
    }
}
