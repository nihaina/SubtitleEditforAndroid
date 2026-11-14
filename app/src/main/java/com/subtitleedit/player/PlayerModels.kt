package com.subtitleedit.player

import java.io.File

/** One playable media entry of the player queue. */
internal data class PlayerTrack(
    val path: String,
    val name: String,
    val isVideo: Boolean
) {
    val file: File get() = File(path)
    val stem: String get() = file.nameWithoutExtension
}

internal enum class PlayerPlayMode { SEQUENCE, REPEAT_ONE, REPEAT_ALL, SHUFFLE }

internal data class PlayerBookmark(
    val positionMs: Long,
    val createdAtMs: Long,
    val label: String = ""
)

internal enum class PlayerSubtitleKind { NONE, INTERNAL, EXTERNAL }

internal data class PlayerSubtitleOption(
    val id: String,
    val label: String,
    val kind: PlayerSubtitleKind,
    val mpvTrackId: Int = -1,
    val path: String = ""
)

internal data class PlayerEqualizerState(
    val enabled: Boolean = false,
    val gains: List<Float> = PlayerEqualizer.FLAT
)

/** A deadline on the monotonic clock, independent of playback speed and pauses. */
internal data class PlayerSleepTimer(
    val mode: Mode = Mode.OFF,
    val endsAtElapsedMs: Long = 0L,
    val durationMinutes: Int = 0
) {
    enum class Mode { OFF, COUNTDOWN }

    val active: Boolean get() = mode != Mode.OFF
}

/** Live mirror of the libmpv playback state published by [PlayerEngine]. */
internal data class PlayerEngineState(
    val loadId: Long = 0L,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val ready: Boolean = false,
    val ended: Boolean = false,
    val loudness: Float = 0f,
    val loudnessSampleId: Long = 0L,
    val error: String? = null,
    val subtitleText: String = "",
    val subtitleTracks: List<PlayerSubtitleOption> = emptyList(),
    val selectedSubtitleId: String = ""
)

/** mm:ss or h:mm:ss rendering shared by the seek bar, playlist and bookmarks. */
internal fun formatPlaybackTime(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0L) + 500L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
}
