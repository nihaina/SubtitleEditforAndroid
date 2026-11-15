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

/** Only indexed catalog members participate; the performer field stays independent of album artist. */
internal fun buildMediaSingers(tracks: List<MediaAlbumTrack>): List<MediaSinger> =
    tracks.distinctBy { it.track.path }.groupBy { normalizedSingerName(it.track.artist) }
        .values.map { members ->
            MediaSinger(
                name = members.first().track.artist.trim()
                    .takeUnless { it.equals("<unknown>", ignoreCase = true) }.orEmpty(),
                tracks = members.sortedWith(
                    compareBy<MediaAlbumTrack> { it.track.displayTitle.lowercase(Locale.ROOT) }
                        .thenBy { it.track.path }
                )
            )
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
