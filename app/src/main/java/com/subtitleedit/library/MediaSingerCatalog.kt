package com.subtitleedit.library

import java.util.Locale

/** A performer tag is one identity; separators inside a name are not interpreted as people. */
internal data class MediaSinger(val name: String, val tracks: List<MediaAlbumTrack>) {
    val key: String = normalizedSingerName(name)
    val durationMs: Long = tracks.sumOf { it.track.durationMs.coerceAtLeast(0L) }
    val updatedAtMs: Long = tracks.maxOfOrNull { it.modifiedAtMs } ?: 0L
    val year: Int? = tracks.mapNotNull { it.year }.minOrNull()
    val albums: List<MediaAlbum> = buildMediaAlbums(tracks)
}

internal enum class SingerSortField { NAME, TRACK_COUNT, DURATION, UPDATED_AT }

internal enum class MediaCreditRole { SINGER, ARTIST, COMPOSER }

/** Kept as the singer entry point so existing browsing and its metadata semantics stay intact. */
internal fun buildMediaSingers(tracks: List<MediaAlbumTrack>): List<MediaSinger> =
    buildMediaCredits(tracks, MediaCreditRole.SINGER)

/** Each role groups one whole tag value, without borrowing another role's metadata. */
internal fun buildMediaCredits(tracks: List<MediaAlbumTrack>, role: MediaCreditRole): List<MediaSinger> =
    tracks.distinctBy { it.track.path }.groupBy { normalizedSingerName(it.creditName(role)) }
        .values.map { members ->
            MediaSinger(
                name = members.first().creditName(role).trim()
                    .takeUnless { it.equals("<unknown>", ignoreCase = true) }.orEmpty(),
                tracks = members.sortedWith(
                    compareBy<MediaAlbumTrack> { it.track.displayTitle.lowercase(Locale.ROOT) }
                        .thenBy { it.track.path }
                )
            )
        }

private fun MediaAlbumTrack.creditName(role: MediaCreditRole): String = when (role) {
    MediaCreditRole.SINGER -> track.artist
    MediaCreditRole.ARTIST -> albumArtist
    MediaCreditRole.COMPOSER -> composer
}

internal fun filterAndSortMediaSingers(
    singers: List<MediaSinger>,
    query: String,
    sortField: SingerSortField,
    ascending: Boolean
): List<MediaSinger> {
    val search = query.trim().lowercase(Locale.ROOT)
    val primary = Comparator<MediaSinger> { left, right ->
        val comparison = when (sortField) {
            SingerSortField.NAME -> left.key.compareTo(right.key)
            SingerSortField.TRACK_COUNT -> left.tracks.size.compareTo(right.tracks.size)
            SingerSortField.DURATION -> left.durationMs.compareTo(right.durationMs)
            SingerSortField.UPDATED_AT -> left.updatedAtMs.compareTo(right.updatedAtMs)
        }
        if (ascending) comparison else -comparison
    }
    return singers.filter { search.isEmpty() || it.key.contains(search) }
        .sortedWith(primary.thenBy { it.key }.thenBy { it.name })
}

private fun normalizedSingerName(name: String): String = name.trim()
    .takeUnless { it.equals("<unknown>", ignoreCase = true) }.orEmpty().lowercase(Locale.ROOT)
