package com.subtitleedit.library

import java.util.Locale

internal enum class AlbumTrackSortField {
    TITLE,
    FILE_NAME,
    FOLDER_PATH,
    ALBUM,
    ARTIST,
    ALBUM_ARTIST,
    COMPOSER,
    GENRE,
    TRACK_NUMBER,
    DURATION,
    YEAR,
    MODIFIED_AT,
    ADDED_AT,
    LAST_PLAYED,
    PLAY_COUNT
}

internal data class AlbumTrackSortOptions(
    val field: AlbumTrackSortField = AlbumTrackSortField.TRACK_NUMBER,
    val ascending: Boolean = true
)

/** Applies the displayed song order without changing the indexed album or duplicating files. */
internal fun sortMediaAlbumTracks(
    tracks: List<MediaAlbumTrack>,
    options: AlbumTrackSortOptions
): List<MediaAlbumTrack> {
    fun text(value: String) = value.lowercase(Locale.ROOT)
    val primary = Comparator<MediaAlbumTrack> { left, right ->
        val leftNumber = left.trackNumber?.takeIf { it > 0 }
        val rightNumber = right.trackNumber?.takeIf { it > 0 }
        val leftYear = left.year?.takeIf { it > 0 }
        val rightYear = right.year?.takeIf { it > 0 }
        val missing = when (options.field) {
            AlbumTrackSortField.TRACK_NUMBER -> compareMissing(leftNumber, rightNumber)
            AlbumTrackSortField.YEAR -> compareMissing(leftYear, rightYear)
            else -> null
        }
        // Unknown numeric tags stay after known tags in either direction.
        if (missing != null) missing else {
            val compared = when (options.field) {
                AlbumTrackSortField.TITLE -> text(left.track.displayTitle).compareTo(text(right.track.displayTitle))
                AlbumTrackSortField.FILE_NAME -> text(left.track.file.name).compareTo(text(right.track.file.name))
                AlbumTrackSortField.FOLDER_PATH -> text(left.track.file.parent.orEmpty()).compareTo(text(right.track.file.parent.orEmpty()))
                AlbumTrackSortField.ALBUM -> text(left.track.album).compareTo(text(right.track.album))
                AlbumTrackSortField.ARTIST -> text(left.track.artist).compareTo(text(right.track.artist))
                AlbumTrackSortField.ALBUM_ARTIST -> text(left.albumArtist).compareTo(text(right.albumArtist))
                AlbumTrackSortField.COMPOSER -> text(left.composer).compareTo(text(right.composer))
                AlbumTrackSortField.GENRE -> text(left.genre).compareTo(text(right.genre))
                AlbumTrackSortField.TRACK_NUMBER -> {
                    val disc = (left.discNumber?.takeIf { it > 0 } ?: 1)
                        .compareTo(right.discNumber?.takeIf { it > 0 } ?: 1)
                    if (disc != 0) disc else compareValues(leftNumber, rightNumber)
                }
                AlbumTrackSortField.DURATION -> left.track.durationMs.coerceAtLeast(0L).compareTo(right.track.durationMs.coerceAtLeast(0L))
                AlbumTrackSortField.YEAR -> compareValues(leftYear, rightYear)
                AlbumTrackSortField.MODIFIED_AT -> left.modifiedAtMs.compareTo(right.modifiedAtMs)
                AlbumTrackSortField.ADDED_AT -> left.track.addedAtMs.compareTo(right.track.addedAtMs)
                AlbumTrackSortField.LAST_PLAYED -> left.lastPlayedAtMs.compareTo(right.lastPlayedAtMs)
                AlbumTrackSortField.PLAY_COUNT -> left.playCount.compareTo(right.playCount)
            }
            if (options.ascending) compared else -compared
        }
    }
    return tracks.distinctBy { it.track.path }.sortedWith(
        primary.thenBy { text(it.track.displayTitle) }.thenBy { it.track.path }
    )
}

/** Null means both values are known and the normal directional comparison should run. */
private fun compareMissing(left: Int?, right: Int?): Int? = when {
    left != null && right != null -> null
    left == right -> 0
    left == null -> 1
    else -> -1
}
