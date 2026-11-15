package com.subtitleedit.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.library.MediaAlbum
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.MediaPlaylist
import com.subtitleedit.library.PlaybackHistoryEntry

internal data class MediaAlbumCatalogKey(
    val revision: Long,
    val directories: List<String>,
)

internal data class MediaAlbumCatalogSnapshot(
    val key: MediaAlbumCatalogKey,
    val tracks: List<MediaAlbumTrack>,
    val indexedTracks: List<MediaLibraryTrack> = emptyList(),
)

internal data class MediaAlbumLibrarySnapshot(
    val revision: Long,
    val refreshVersion: Int,
    val history: Map<String, PlaybackHistoryEntry>,
    val playlists: List<MediaPlaylist>,
)

internal data class MediaAlbumPreparedSnapshot(
    val catalog: MediaAlbumCatalogSnapshot,
    val history: Map<String, PlaybackHistoryEntry>,
    val albums: List<MediaAlbum>,
)

/** Navigation retains the prepared rows as well as their source, so returning has no empty frame. */
internal class MediaAlbumPageState {
    var catalog by mutableStateOf<MediaAlbumCatalogSnapshot?>(null)
    var library by mutableStateOf<MediaAlbumLibrarySnapshot?>(null)
    var prepared by mutableStateOf<MediaAlbumPreparedSnapshot?>(null)
}

internal typealias MediaAlbumCatalogLoader = suspend (directories: List<String>) -> List<MediaAlbumTrack>
