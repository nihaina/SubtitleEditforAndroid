package com.subtitleedit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.library.*
import com.subtitleedit.player.formatPlaybackTime

/** Singer membership comes from the indexed artist tag; album filters never change the catalog. */
@Composable
internal fun MediaSingerDetailContent(
    singer: MediaSinger,
    options: MediaSingerViewOptions,
    onOptionsChanged: (MediaSingerViewOptions) -> Unit,
    isActive: Boolean,
    onBack: () -> Unit,
    onPlay: (List<MediaAlbumTrack>, String?) -> Unit,
    onShuffle: (List<MediaAlbumTrack>) -> Unit,
    onFavoriteSelection: (List<MediaLibraryTrack>, () -> Unit) -> Unit,
    onAddSelectionToPlaylist: (List<MediaLibraryTrack>, () -> Unit) -> Unit,
    onShowInfo: (String) -> Unit
) {
    val name = singer.name.ifBlank { stringResource(R.string.media_album_unknown_artist) }
    val albums = remember(singer, options.albumSortField, options.albumAscending) {
        filterAndSortMediaAlbums(singer.albums, "", options.albumSortField, options.albumAscending)
    }
    var chosenAlbums by rememberSaveable(singer.key) { mutableStateOf<List<String>?>(null) }
    val selectedAlbums = remember(albums, chosenAlbums) {
        val keys = albums.mapTo(hashSetOf()) { it.key }
        chosenAlbums?.filterTo(linkedSetOf()) { it in keys }?.takeIf { it.isNotEmpty() }
            ?: albums.take(1).mapTo(linkedSetOf()) { it.key }
    }
    val visibleTracks = remember(singer, albums, selectedAlbums, options.groupTracks, options.trackSort) {
        val members = if (options.groupTracks) albums.filter { it.key in selectedAlbums }.flatMap { it.tracks }
            else singer.tracks
        sortMediaAlbumTracks(members, options.trackSort)
    }
    MediaTrackDetailContent(
        collectionKey = singer.key,
        title = name,
        backDescription = stringResource(R.string.singer_detail_back),
        visibleTracks = visibleTracks,
        selectionResetKey = listOf(options.groupTracks, selectedAlbums),
        trackSort = options.trackSort,
        onTrackSortChanged = { onOptionsChanged(options.copy(trackSort = it)) },
        isActive = isActive,
        onBack = onBack,
        onPlay = onPlay,
        onShuffle = onShuffle,
        onFavoriteSelection = onFavoriteSelection,
        onAddSelectionToPlaylist = onAddSelectionToPlaylist,
        onShowInfo = onShowInfo,
        tagPrefix = "singer"
    ) {
        if (options.groupTracks) {
            item(key = "grouping") {
                Text(
                    stringResource(R.string.media_category_albums),
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item(key = "groups") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().testTag("singer-albums"),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(albums, key = { it.key }) { album ->
                        val selected = album.key in selectedAlbums
                        OutlinedCard(
                            onClick = {
                                val changed = if (selected) selectedAlbums - album.key else selectedAlbums + album.key
                                if (changed.isNotEmpty()) chosenAlbums = changed.toList()
                            },
                            modifier = Modifier.width(168.dp).heightIn(min = 64.dp)
                                .testTag("singer-album:${album.key}").semantics { this.selected = selected },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MediaAlbumArtwork(
                                    mediaAlbumArtworkPaths(album, options.albumSortField, options.albumAscending),
                                    Modifier.size(40.dp), album.tracks.map { it.modifiedAtMs }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        album.name.ifBlank { stringResource(R.string.media_album_unknown) },
                                        style = MaterialTheme.typography.titleSmall, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        stringResource(R.string.media_album_detail_song_count, album.tracks.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "summary") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (options.groupTracks) Text(
                    stringResource(R.string.media_album_detail_group_count, selectedAlbums.size, albums.size,
                        stringResource(R.string.media_category_albums)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.media_album_detail_song_count, visibleTracks.size),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(painterResource(R.drawable.ic_player_alarm), stringResource(R.string.player_info_duration),
                        modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatPlaybackTime(visibleTracks.sumOf { it.track.durationMs.coerceAtLeast(0L) }),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
