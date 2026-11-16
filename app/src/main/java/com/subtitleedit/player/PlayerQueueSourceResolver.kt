package com.subtitleedit.player

import android.content.Context
import androidx.annotation.WorkerThread
import com.subtitleedit.R
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaCreditRole
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.buildMediaAlbums
import com.subtitleedit.library.buildMediaCredits
import com.subtitleedit.library.filterAndSortMediaAlbums
import com.subtitleedit.library.filterAndSortMediaSingers
import com.subtitleedit.library.sortMediaAlbumTracks
import com.subtitleedit.model.FileBrowserOrder
import com.subtitleedit.ui.MediaAlbumPreferences
import com.subtitleedit.ui.MediaSingerPreferences
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SettingsManager
import java.io.File

/** Resolves adjacent collections using the same persisted ordering as their browser page. */
internal class PlayerQueueSourceResolver(
    context: Context,
    private val repository: MediaLibraryRepository
) {
    private val app = context.applicationContext

    /** Deleted and unrecognized sources have no successor, even when wrapping is enabled. */
    @WorkerThread
    fun next(source: PlayerQueueSource, wrap: Boolean = false): PlayerResolvedQueue? =
        adjacent(source, forward = true, wrap = wrap)

    @WorkerThread
    fun previous(source: PlayerQueueSource, wrap: Boolean = false): PlayerResolvedQueue? =
        adjacent(source, forward = false, wrap = wrap)

    private fun adjacent(source: PlayerQueueSource, forward: Boolean, wrap: Boolean): PlayerResolvedQueue? = when (source.kind) {
        PlayerQueueSourceKind.DIRECTORY -> adjacentDirectory(source, forward, wrap)
        PlayerQueueSourceKind.PLAYLIST -> adjacentPlaylist(source, forward, wrap)
        PlayerQueueSourceKind.ALBUM -> adjacentAlbum(source, forward, wrap)
        else -> adjacentCredit(source, forward, wrap)
    }

    private fun adjacentDirectory(source: PlayerQueueSource, forward: Boolean, wrap: Boolean): PlayerResolvedQueue? {
        val current = File(source.key).absoluteFile
        val parent = current.parentFile ?: return null
        val settings = SettingsManager.getInstance(app)
        val field = settings.getFileSortField()
        val direction = settings.getFileSortDirection()
        val showHidden = settings.isShowHiddenFilesEnabled()
        val siblings = FileBrowserOrder.sort(
            children(parent).filter { it.isDirectory && (showHidden || !it.name.startsWith('.')) },
            field, direction
        )
        val index = siblings.indexOfFirst { it.absolutePath == current.absolutePath }
        if (index < 0) return null
        for (directory in siblings.neighbors(index, forward, wrap)) {
            val files = children(directory).filter {
                (showHidden || !it.name.startsWith('.')) && playable(it)
            }
            val tracks = FileBrowserOrder.sort(files, field, direction).map { file ->
                PlayerTrack(file.absolutePath, file.name, FileTypePolicy.isVideo(file))
            }
            if (tracks.isNotEmpty()) return PlayerResolvedQueue(
                source.copy(key = directory.absolutePath), tracks, directory.name
            )
        }
        return null
    }

    private fun adjacentPlaylist(source: PlayerQueueSource, forward: Boolean, wrap: Boolean): PlayerResolvedQueue? {
        val playlists = repository.getPlaylists()
        val index = playlists.indexOfFirst { it.id.toString() == source.key }
        if (index < 0) return null
        for (playlist in playlists.neighbors(index, forward, wrap)) {
            val tracks = repository.getPlaylistTracks(playlist.id).playableTracks()
            if (tracks.isNotEmpty()) return PlayerResolvedQueue(
                source.copy(key = playlist.id.toString()), tracks, playlist.name
            )
        }
        return null
    }

    private fun adjacentAlbum(source: PlayerQueueSource, forward: Boolean, wrap: Boolean): PlayerResolvedQueue? {
        val options = MediaAlbumPreferences(app).load()
        val albums = filterAndSortMediaAlbums(
            buildMediaAlbums(catalog(source)), "", options.sortField, options.ascending
        )
        val index = albums.indexOfFirst { it.key == source.key }
        if (index < 0) return null
        for (album in albums.neighbors(index, forward, wrap)) {
            // Match the initial singer group shown when opening an album detail page.
            val members = if (options.groupTracks) {
                album.tracks.groupBy { it.track.artist.trim() }.values
                    .firstOrNull { group -> group.any { playable(it.track.file) } }.orEmpty()
            } else album.tracks
            val tracks = sortMediaAlbumTracks(members, options.trackSort).map { it.track }.playableTracks()
            if (tracks.isNotEmpty()) return PlayerResolvedQueue(
                source.copy(key = album.key), tracks,
                album.name.ifBlank { app.getString(R.string.media_album_unknown) }
            )
        }
        return null
    }

    private fun adjacentCredit(source: PlayerQueueSource, forward: Boolean, wrap: Boolean): PlayerResolvedQueue? {
        val role = when (source.kind) {
            PlayerQueueSourceKind.SINGER -> MediaCreditRole.SINGER
            PlayerQueueSourceKind.ARTIST -> MediaCreditRole.ARTIST
            PlayerQueueSourceKind.COMPOSER -> MediaCreditRole.COMPOSER
            PlayerQueueSourceKind.GENRE -> MediaCreditRole.GENRE
            else -> return null
        }
        val options = MediaSingerPreferences(app, role).load()
        val credits = filterAndSortMediaSingers(
            buildMediaCredits(catalog(source), role), "", options.sortField, options.ascending
        )
        val index = credits.indexOfFirst { it.key == source.key }
        if (index < 0) return null
        for (credit in credits.neighbors(index, forward, wrap)) {
            val members = if (options.groupTracks) {
                filterAndSortMediaAlbums(credit.albums, "", options.albumSortField, options.albumAscending)
                    .firstOrNull { album -> album.tracks.any { playable(it.track.file) } }?.tracks.orEmpty()
            } else credit.tracks
            val tracks = sortMediaAlbumTracks(members, options.trackSort).map { it.track }.playableTracks()
            if (tracks.isNotEmpty()) return PlayerResolvedQueue(
                source.copy(key = credit.key), tracks, credit.name.ifBlank { unknownCreditName(role) }
            )
        }
        return null
    }

    /** Visit each other source at most once, never reloading the source being left. */
    private fun <T> List<T>.neighbors(index: Int, forward: Boolean, wrap: Boolean): Sequence<T> = sequence {
        for (distance in 1 until size) {
            val candidate = index + if (forward) distance else -distance
            if (!wrap && candidate !in indices) break
            yield(this@neighbors[(candidate + size) % size])
        }
    }

    private fun catalog(source: PlayerQueueSource): List<MediaAlbumTrack> {
        val roots = source.roots.map { File(it).absolutePath.trimEnd(File.separatorChar) }
        val indexed = repository.getIndexedTracks().filter { track ->
            roots.isEmpty() || roots.any { root ->
                track.path == root || track.path.startsWith(root + File.separator)
            }
        }
        val history = repository.getPlaybackHistory(Int.MAX_VALUE).associateBy { it.path }
        return repository.getPersistedAlbumTracks(indexed).map { item ->
            val played = history[item.track.path]
            item.copy(lastPlayedAtMs = played?.playedAtMs ?: 0L, playCount = played?.playCount ?: 0)
        }
    }

    private fun unknownCreditName(role: MediaCreditRole): String = app.getString(when (role) {
        MediaCreditRole.SINGER -> R.string.media_album_unknown_artist
        MediaCreditRole.ARTIST -> R.string.media_album_unknown_album_artist
        MediaCreditRole.COMPOSER -> R.string.media_album_unknown_composer
        MediaCreditRole.GENRE -> R.string.media_genre_unknown
    })

    private fun List<MediaLibraryTrack>.playableTracks(): List<PlayerTrack> =
        distinctBy { it.path }.filter { playable(it.file) }.map {
            PlayerTrack(it.path, it.displayTitle, it.isVideo || FileTypePolicy.isVideo(it.file))
        }

    private fun playable(file: File): Boolean = file.isFile && file.canRead() &&
        (FileUtils.isAudioFile(file) || FileTypePolicy.isVideo(file))

    private fun children(directory: File): List<File> = try {
        directory.listFiles()?.toList().orEmpty()
    } catch (_: SecurityException) {
        emptyList()
    }
}
