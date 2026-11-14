package com.subtitleedit.library

import java.io.File

internal data class MediaLibraryTrack(
    val path: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val isVideo: Boolean = false,
    val durationMs: Long = 0L,
    val addedAtMs: Long = 0L,
) {
    val file: File
        get() = File(path)

    val displayTitle: String
        get() = title.ifBlank { file.nameWithoutExtension.ifBlank { file.name } }
}

internal data class MediaPlaylist(
    val id: Long,
    val name: String,
    val isSystem: Boolean = false,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val itemCount: Int = 0,
    val firstTrackPath: String? = null,
)

internal data class PlaybackHistoryEntry(
    val id: Long,
    val path: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val isVideo: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val playedAtMs: Long = 0L,
    val playCount: Int = 1,
)

internal data class ListeningDayStat(
    val date: String,
    val durationMs: Long = 0L,
    val trackCount: Int = 0,
    val playCount: Int = 0,
)

internal data class ListeningSessionRecord(
    val id: Long,
    val mediaPath: String,
    val title: String,
    val artist: String,
    val album: String,
    val isVideo: Boolean,
    val listeningDate: String,
    val startAtMs: Long,
    val lastActiveAtMs: Long,
    val durationMs: Long,
    val trackCount: Int,
)

internal data class TopListeningItem(
    val mediaPath: String,
    val title: String,
    val artist: String,
    val album: String,
    val totalDurationMs: Long,
    val playCount: Int,
)
