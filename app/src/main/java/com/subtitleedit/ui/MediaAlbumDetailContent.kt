package com.subtitleedit.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.feature.ui.BrowserFileRow
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.AlbumTrackSortOptions
import com.subtitleedit.library.MediaAlbum
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.mediaAlbumArtworkPaths
import com.subtitleedit.library.sortMediaAlbumTracks
import com.subtitleedit.player.formatPlaybackTime
import com.subtitleedit.ui.settings.settingsPageBackground

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
    onShowInfo: (String) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var grouping by rememberSaveable(album.key) { mutableStateOf(MediaCategory.SINGERS) }
    var groupMenuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var chosenGroups by rememberSaveable(album.key, grouping) { mutableStateOf<List<String>?>(null) }
    var selectedPaths by remember(album.key) { mutableStateOf<Set<String>>(emptySet()) }
    var panelHeight by remember(album.key) { mutableStateOf(112.dp) }
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
        chosenGroups?.filterTo(linkedSetOf()) { it in groups }?.takeIf { it.isNotEmpty() }
            ?: groups.keys.take(1).toSet()
    }
    val visibleTracks = remember(album, groupTracks, groups, selectedGroups, trackSort) {
        val members = if (groupTracks) selectedGroups.flatMap { groups[it].orEmpty() } else album.tracks
        sortMediaAlbumTracks(members, trackSort)
    }
    val visiblePaths = remember(visibleTracks) { visibleTracks.map { it.track.path } }
    val selectedTracks = remember(visibleTracks, selectedPaths) {
        visibleTracks.filter { it.track.path in selectedPaths }
    }
    val artworkPaths = remember(album, sortField, ascending) { mediaAlbumArtworkPaths(album, sortField, ascending) }
    val selectionMode = selectedPaths.isNotEmpty()

    LaunchedEffect(groupTracks, grouping, selectedGroups) { selectedPaths = emptySet() }
    LaunchedEffect(visiblePaths) { selectedPaths = selectedPaths.intersect(visiblePaths.toSet()) }
    BackHandler(enabled = isActive && selectionMode) { selectedPaths = emptySet() }

    fun toggleSelection(path: String) {
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }
    fun select(action: MediaLibrarySelectionAction) {
        selectedPaths = updateMediaLibrarySelection(visiblePaths, selectedPaths, action)
    }
    fun finishSelection(paths: Set<String>) { selectedPaths = selectedPaths - paths }

    Column(Modifier.fillMaxSize().testTag("album-detail")) {
        TopAppBar(
            title = { Text(albumName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(
                    onClick = { if (selectionMode) selectedPaths = emptySet() else onBack() },
                    modifier = Modifier.testTag("album-detail-back")
                ) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.media_album_detail_back))
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = settingsPageBackground())
        )
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(bottom = if (selectionMode) panelHeight else 0.dp)
                    .testTag("album-tracks"),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                if (groupTracks) {
                    item(key = "grouping") {
                        Box(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp)) {
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
                                            selectedPaths = emptySet()
                                        }
                                    )
                                }
                            }
                        }
                    }
                    item(key = "groups") {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().testTag("album-groups"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(groups.keys.toList(), key = { it }) { group ->
                                val selected = group in selectedGroups
                                OutlinedCard(
                                    onClick = {
                                        val changed = if (selected) selectedGroups - group else selectedGroups + group
                                        if (changed.isNotEmpty()) chosenGroups = changed.toList()
                                        selectedPaths = emptySet()
                                    },
                                    modifier = Modifier.width(112.dp).heightIn(min = 88.dp)
                                        .testTag("album-group:$group").semantics { this.selected = selected },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Box(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(10.dp), contentAlignment = Alignment.Center) {
                                        Text(group, style = MaterialTheme.typography.titleSmall, maxLines = 3,
                                            overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "summary") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (groupTracks) Text(
                                stringResource(R.string.media_album_detail_group_count, selectedGroups.size, groups.size, groupingName),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.media_album_track_summary, visibleTracks.size,
                                    formatPlaybackTime(visibleTracks.sumOf { it.track.durationMs.coerceAtLeast(0) })),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        MediaAlbumArtwork(artworkPaths, Modifier.size(56.dp), album.tracks.map { it.modifiedAtMs })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item(key = "actions") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onPlay(visibleTracks, null) }, enabled = visibleTracks.isNotEmpty(),
                            modifier = Modifier.testTag("album-play-all")
                        ) { Text(stringResource(R.string.media_album_play_all)) }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { onShuffle(visibleTracks) }, enabled = visibleTracks.isNotEmpty(),
                            modifier = Modifier.testTag("album-tracks-shuffle")
                        ) {
                            Icon(painterResource(R.drawable.ic_album_shuffle), stringResource(R.string.media_album_detail_shuffle))
                        }
                        IconButton(onClick = { sortOpen = true }, modifier = Modifier.testTag("album-tracks-sort")) {
                            Icon(painterResource(R.drawable.ic_album_sort), stringResource(R.string.media_album_detail_sort))
                        }
                    }
                }
                items(visibleTracks, key = { it.track.path }) { item ->
                    BrowserFileRow(
                        modifier = Modifier.testTag("album-track:${item.track.path}"),
                        file = item.track.file,
                        relativePathRoot = null,
                        isSelected = item.track.path in selectedPaths,
                        isSelectionMode = selectionMode,
                        onClick = { if (selectionMode) toggleSelection(item.track.path) else onPlay(visibleTracks, item.track.path) },
                        onLongClick = { selectedPaths = selectedPaths + item.track.path },
                        directoryPageStyle = true
                    )
                }
            }
            if (selectionMode) {
                MediaLibrarySelectionPanel(
                    selectedFiles = selectedTracks.map { it.track.file },
                    totalDurationMs = selectedTracks.sumOf { it.track.durationMs.coerceAtLeast(0) },
                    onRemove = { selectedPaths = selectedPaths - it },
                    onFavorite = {
                        val captured = selectedTracks.map { it.track }
                        val paths = captured.mapTo(hashSetOf()) { it.path }
                        onFavoriteSelection(captured) { finishSelection(paths) }
                    },
                    onAddToPlaylist = {
                        val captured = selectedTracks.map { it.track }
                        val paths = captured.mapTo(hashSetOf()) { it.path }
                        onAddSelectionToPlaylist(captured) { finishSelection(paths) }
                    },
                    onShowInfo = {
                        selectedTracks.singleOrNull()?.let { onShowInfo(it.track.path) }
                            ?: Toast.makeText(context, R.string.media_lib_files_info_single, Toast.LENGTH_SHORT).show()
                    },
                    onCancel = { selectedPaths = emptySet() },
                    onSelectAll = { select(MediaLibrarySelectionAction.SELECT_ALL) },
                    onDeselectAll = { select(MediaLibrarySelectionAction.DESELECT_ALL) },
                    onSelectRange = { select(MediaLibrarySelectionAction.SELECT_RANGE) },
                    onInvertSelection = { select(MediaLibrarySelectionAction.INVERT) },
                    modifier = Modifier.align(Alignment.BottomCenter).heightIn(max = maxHeight * 0.7f)
                        .onSizeChanged { panelHeight = with(density) { it.height.toDp() } }
                )
            }
        }
    }
    if (sortOpen) MediaAlbumTrackSortDialog(
        options = trackSort,
        onConfirm = onTrackSortChanged,
        onDismiss = { sortOpen = false }
    )
}

private val MediaCategory.detailGroupLabel: Int
    get() = when (this) {
        MediaCategory.ARTISTS -> R.string.media_album_detail_album_artist
        MediaCategory.COMPOSERS -> R.string.media_category_composers
        else -> R.string.media_category_singers
    }
