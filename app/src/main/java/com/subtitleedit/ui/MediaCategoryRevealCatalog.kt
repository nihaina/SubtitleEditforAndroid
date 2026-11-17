package com.subtitleedit.ui

import androidx.annotation.WorkerThread
import com.subtitleedit.library.MediaAlbum
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.library.buildMediaAlbums
import com.subtitleedit.util.SharedFileCache
import java.io.File

/** A direct information link reads only its requested file and never changes scan roots. */
@WorkerThread
internal fun loadMediaCategoryRevealTrack(path: String, cacheDirectory: File): MediaAlbumTrack? {
    val file = File(path)
    if (!file.isFile || !file.canRead()) return null
    val metadata = SharedFileCache.attributes.get(file)?.takeIf { it.isRegularFile } ?: return null
    val track = PlayerTrackMetadataReader.readLibraryTrack(metadata, cacheDirectory = cacheDirectory)
    val tags = PlayerTrackMetadataReader.readAlbumMetadata(metadata, cacheDirectory)
    return MediaAlbumTrack(track, albumArtist = tags.albumArtist, composer = tags.composer,
        year = tags.year.take(4).toIntOrNull(), trackNumber = tags.trackNumber.substringBefore('/').toIntOrNull(),
        discNumber = tags.discNumber.substringBefore('/').toIntOrNull(),
        modifiedAtMs = metadata.modifiedTimeMillis, genre = tags.genre)
}

/** Refresh an existing catalog row for navigation without adding a file outside the catalog. */
internal fun mediaAlbumsWithRevealTrack(albums: List<MediaAlbum>, track: MediaAlbumTrack?): List<MediaAlbum> {
    if (track == null) return albums
    val tracks = albums.flatMap { it.tracks }
    val previous = tracks.firstOrNull { it.track.path == track.track.path } ?: return albums
    val current = track.copy(
        track = track.track.copy(addedAtMs = previous.track.addedAtMs),
        lastPlayedAtMs = previous.lastPlayedAtMs, playCount = previous.playCount)
    if (previous == current) return albums
    return buildMediaAlbums(tracks.filterNot { it.track.path == track.track.path } + current)
}
