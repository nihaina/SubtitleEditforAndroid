package com.subtitleedit.library

import java.util.Locale

internal data class MediaAlbumTrack(
    val track: MediaLibraryTrack,
    val albumArtist: String = "",
    val composer: String = "",
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val modifiedAtMs: Long = 0L,
    val genre: String = "",
    val lastPlayedAtMs: Long = 0L,
    val playCount: Int = 0
)

internal data class MediaAlbum(val name: String, val tracks: List<MediaAlbumTrack>) {
    val key: String = normalizedAlbumName(name)
    val artist: String = tracks.map { it.albumArtist.ifBlank { it.track.artist }.trim() }
        .filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase(Locale.ROOT) }.joinToString(" / ")
    val singer: String = tracks.map { it.track.artist.trim() }.filter { it.isNotEmpty() }
        .distinct().sortedBy { it.lowercase(Locale.ROOT) }.joinToString(" / ")
    val durationMs: Long = tracks.sumOf { it.track.durationMs.coerceAtLeast(0L) }
    val year: Int? = tracks.mapNotNull { it.year }.minOrNull()
    val updatedAtMs: Long = tracks.maxOfOrNull { it.modifiedAtMs } ?: 0L
    val artworkPaths: List<String> = tracks.map { it.track.path }
}

internal enum class AlbumSortField { NAME, TRACK_COUNT, DURATION, YEAR, UPDATED_AT, ARTIST }

/** Cover candidates follow the applied sort; this never changes the album's playback sequence. */
internal fun mediaAlbumArtworkPaths(album: MediaAlbum, sortField: AlbumSortField, ascending: Boolean): List<String> {
    val primary = Comparator<MediaAlbumTrack> { left, right ->
        if (sortField == AlbumSortField.YEAR && (left.year == null || right.year == null)) {
            when {
                left.year == right.year -> 0
                left.year == null -> 1
                else -> -1
            }
        } else {
            val comparison = when (sortField) {
                AlbumSortField.NAME, AlbumSortField.TRACK_COUNT ->
                    left.track.displayTitle.lowercase(Locale.ROOT).compareTo(right.track.displayTitle.lowercase(Locale.ROOT))
                AlbumSortField.DURATION -> left.track.durationMs.compareTo(right.track.durationMs)
                AlbumSortField.YEAR -> compareValues(left.year, right.year)
                AlbumSortField.UPDATED_AT -> left.modifiedAtMs.compareTo(right.modifiedAtMs)
                AlbumSortField.ARTIST -> left.track.artist.lowercase(Locale.ROOT).compareTo(right.track.artist.lowercase(Locale.ROOT))
            }
            if (ascending) comparison else -comparison
        }
    }
    return album.tracks.sortedWith(primary
        .thenBy { it.track.displayTitle.lowercase(Locale.ROOT) }.thenBy { it.track.path })
        .map { it.track.path }.distinct()
}

internal data class MediaAlbumMetadata(
    val albumArtist: String = "",
    val composer: String = "",
    val year: String = "",
    val trackNumber: String = "",
    val discNumber: String = "",
    val genre: String = ""
)

/** Album identity comes from the tag, never from a folder or a display-only unknown label. */
internal fun buildMediaAlbums(tracks: List<MediaAlbumTrack>): List<MediaAlbum> =
    tracks.distinctBy { it.track.path }.groupBy { normalizedAlbumName(it.track.album) }
        .values.map { albumTracks ->
            MediaAlbum(
                name = albumTracks.first().track.album.trim().takeUnless { it.equals("<unknown>", true) }.orEmpty(),
                tracks = albumTracks.sortedWith(
                    compareBy<MediaAlbumTrack> { it.discNumber ?: 1 }
                        .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                        .thenBy { it.track.displayTitle.lowercase(Locale.ROOT) }
                        .thenBy { it.track.path }
                )
            )
        }

internal fun filterAndSortMediaAlbums(
    albums: List<MediaAlbum>,
    query: String,
    sortField: AlbumSortField,
    ascending: Boolean
): List<MediaAlbum> {
    val search = query.trim().lowercase(Locale.ROOT)
    val primary = Comparator<MediaAlbum> { left, right ->
        // Missing years remain at the end in either direction.
        if (sortField == AlbumSortField.YEAR && (left.year == null || right.year == null)) {
            when {
                left.year == right.year -> 0
                left.year == null -> 1
                else -> -1
            }
        } else {
            val comparison = when (sortField) {
                AlbumSortField.NAME -> left.key.compareTo(right.key)
                AlbumSortField.TRACK_COUNT -> left.tracks.size.compareTo(right.tracks.size)
                AlbumSortField.DURATION -> left.durationMs.compareTo(right.durationMs)
                AlbumSortField.YEAR -> compareValues(left.year, right.year)
                AlbumSortField.UPDATED_AT -> left.updatedAtMs.compareTo(right.updatedAtMs)
                AlbumSortField.ARTIST -> left.singer.lowercase(Locale.ROOT).compareTo(right.singer.lowercase(Locale.ROOT))
            }
            if (ascending) comparison else -comparison
        }
    }
    return albums.filter { album ->
        search.isEmpty() || album.name.lowercase(Locale.ROOT).contains(search)
    }.sortedWith(primary.thenBy { it.key }.thenBy { it.name })
}

private fun normalizedAlbumName(name: String): String = name.trim()
    .takeUnless { it.equals("<unknown>", true) }.orEmpty().lowercase(Locale.ROOT)

/** Builds the catalog off the UI thread; the repository supplies persisted text tags. */
internal object AlbumCatalogLoader {
    /** Persisted classifications render without touching every source file on page entry. */
    fun persisted(tracks: List<MediaLibraryTrack>, metadata: Map<String, IndexedAlbumMetadata>): List<MediaAlbumTrack> =
        tracks.distinctBy { it.path }.map { track ->
            val saved = metadata[track.path]
            entry(track, saved?.metadata ?: MediaAlbumMetadata(), saved?.modifiedAtMs ?: 0L)
        }

    private fun entry(track: MediaLibraryTrack, metadata: MediaAlbumMetadata, modifiedAtMs: Long) = MediaAlbumTrack(
                track = track,
                albumArtist = metadata.albumArtist,
                composer = metadata.composer,
                year = YEAR_PATTERN.find(metadata.year)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 },
                trackNumber = metadata.trackNumber.substringBefore('/').trim().toIntOrNull()?.takeIf { it > 0 },
                discNumber = metadata.discNumber.substringBefore('/').trim().toIntOrNull()?.takeIf { it > 0 },
                modifiedAtMs = modifiedAtMs,
                genre = metadata.genre
            )

    private val YEAR_PATTERN = Regex("(?:^|\\D)(\\d{4})(?:\\D|$)")
}
