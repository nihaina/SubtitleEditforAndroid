package com.subtitleedit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.AlbumTrackSortOptions
import com.subtitleedit.library.MediaAlbum
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.mediaAlbumArtworkPaths
import com.subtitleedit.library.sortMediaAlbumTracks
import com.subtitleedit.player.formatPlaybackTime

/** Album membership, visible order and selection all feed the same playback/action callbacks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaAlbumDetailContent(
    album: MediaAlbum,
    groupTracks: Boolean,
    sortField: AlbumSortField,
    ascending: Boolean,
    trackSort: AlbumTrackSortOptions,
    onTrackSortChanged: (AlbumTrackSortOptions) -> Unit,
    isActive: Boolean,
    onBack: () -> Unit,
    onPlay: (List<MediaAlbumTrack>, String?) -> Unit,
    onShuffle: (List<MediaAlbumTrack>) -> Unit,
    onFavoriteSelection: (List<MediaLibraryTrack>, () -> Unit) -> Unit,
    onAddSelectionToPlaylist: (List<MediaLibraryTrack>, () -> Unit) -> Unit,
    onShowInfo: (String) -> Unit,
    metadataRefreshKey: Any? = null,
    revealRequest: MediaCategoryRevealRequest? = null,
    onRevealFinished: (Long) -> Unit = {}
) {
    var grouping by rememberSaveable(album.key) { mutableStateOf(MediaCategory.SINGERS) }
    var groupMenuOpen by remember { mutableStateOf(false) }
    var chosenGroups by rememberSaveable(album.key, grouping) { mutableStateOf<List<String>?>(null) }
    val albumName = album.name.ifBlank { stringResource(R.string.media_album_unknown) }
    val groupingName = stringResource(grouping.detailGroupLabel)
    val unknownGroup = stringResource(when (grouping) {
        MediaCategory.ARTISTS -> R.string.media_album_unknown_album_artist
        MediaCategory.COMPOSERS -> R.string.media_album_unknown_composer
        else -> R.string.media_album_unknown_artist
    })
    val groups = remember(album, grouping, unknownGroup) {
        album.tracks.groupBy { item ->
            when (grouping) {
                MediaCategory.ARTISTS -> item.albumArtist
                MediaCategory.COMPOSERS -> item.composer
                else -> item.track.artist
            }.trim().ifBlank { unknownGroup }
        }
    }
    val selectedGroups = remember(groups, chosenGroups) {
        chosenGroups?.filterTo(linkedSetOf()) { it in groups }
            ?: groups.keys.toSet()
    }
    LaunchedEffect(revealRequest?.token, groups, groupTracks, isActive) {
        val path = revealRequest?.takeIf { isActive }?.trackPath ?: return@LaunchedEffect
        if (groupTracks) {
            groups.entries.firstOrNull { (_, members) -> members.any { it.track.path == path } }
                ?.key?.let { chosenGroups = listOf(it) }
        }
    }
    val visibleTracks = remember(album, groupTracks, groups, selectedGroups, trackSort) {
        val members = if (groupTracks) selectedGroups.flatMap { groups[it].orEmpty() } else album.tracks
        sortMediaAlbumTracks(members, trackSort)
    }
    val artworkPaths = remember(album, sortField, ascending) { mediaAlbumArtworkPaths(album, sortField, ascending) }

    MediaTrackDetailContent(
        collectionKey = album.key,
        title = albumName,
        backDescription = stringResource(R.string.media_album_detail_back),
        visibleTracks = visibleTracks,
        metadataRefreshKey = metadataRefreshKey,
        selectionResetKey = listOf(groupTracks, grouping, selectedGroups),
        trackSort = trackSort,
        onTrackSortChanged = onTrackSortChanged,
        isActive = isActive,
        onBack = onBack,
        onPlay = onPlay,
        onShuffle = onShuffle,
        onFavoriteSelection = onFavoriteSelection,
        onAddSelectionToPlaylist = onAddSelectionToPlaylist,
        onShowInfo = onShowInfo,
        tagPrefix = "album",
        revealRequest = revealRequest,
        onRevealFinished = onRevealFinished
    ) {
        if (groupTracks) {
            item(key = "grouping") {
                Box(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                    TextButton(
                        onClick = { groupMenuOpen = true },
                        modifier = Modifier.testTag("album-group-selector")
                    ) {
                        Text(groupingName, style = MaterialTheme.typography.titleSmall)
                        Icon(
                            painterResource(R.drawable.ic_arrow_right), contentDescription = null,
                            modifier = Modifier.padding(start = 4.dp).size(18.dp).rotate(90f)
                        )
                    }
                    DropdownMenu(expanded = groupMenuOpen, onDismissRequest = { groupMenuOpen = false }) {
                        for (category in listOf(MediaCategory.SINGERS, MediaCategory.ARTISTS, MediaCategory.COMPOSERS)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(category.detailGroupLabel)) },
                                modifier = Modifier.testTag("album-grouping:${category.name}"),
                                onClick = {
                                    groupMenuOpen = false
                                    grouping = category
                                }
                            )
                        }
                    }
                }
            }
            item(key = "groups") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().testTag("album-groups"),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "select-all") {
                        MediaDetailGroupCard(
                            selected = selectedGroups.size == groups.size,
                            onClick = { chosenGroups = if (selectedGroups.size == groups.size) emptyList() else null },
                            modifier = Modifier.width(112.dp).heightIn(min = 56.dp).testTag("album-group-all")
                        ) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.media_lib_selection_all), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                    items(groups.keys.toList(), key = { "group:$it" }) { group ->
                        val selected = group in selectedGroups
                        MediaDetailGroupCard(
                            selected = selected,
                            onClick = {
                                val changed = if (selected) selectedGroups - group else selectedGroups + group
                                chosenGroups = changed.toList().takeUnless { changed.size == groups.size }
                            },
                            onLongClick = { chosenGroups = listOf(group) },
                            modifier = Modifier.width(112.dp).heightIn(min = 56.dp)
                                .testTag("album-group:$group")
                        ) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                                Text(group, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        item(key = "summary") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (groupTracks) Text(
                        stringResource(R.string.media_album_detail_group_count, selectedGroups.size, groups.size, groupingName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.media_album_detail_song_count, visibleTracks.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            painterResource(R.drawable.ic_player_alarm),
                            contentDescription = stringResource(R.string.player_info_duration),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            formatPlaybackTime(visibleTracks.sumOf { it.track.durationMs.coerceAtLeast(0) }),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                MediaAlbumArtwork(artworkPaths, Modifier.size(40.dp), album.tracks.map { it.modifiedAtMs },
                    with(LocalDensity.current) { 40.dp.roundToPx() })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

private val MediaCategory.detailGroupLabel: Int
    get() = when (this) {
        MediaCategory.ARTISTS -> R.string.media_album_detail_album_artist
        MediaCategory.COMPOSERS -> R.string.media_category_composers
        else -> R.string.media_category_singers
    }
