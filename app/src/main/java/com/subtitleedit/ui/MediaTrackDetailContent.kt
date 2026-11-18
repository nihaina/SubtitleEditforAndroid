package com.subtitleedit.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppLazyListScrollbar
import com.subtitleedit.feature.ui.BrowserFileRow
import com.subtitleedit.library.AlbumTrackSortOptions
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.player.formatPlaybackTime
import com.subtitleedit.ui.settings.settingsPageBackground

/** Shared file playback and selection behavior for album and singer details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaTrackDetailContent(
    collectionKey: String,
    title: String,
    backDescription: String,
    visibleTracks: List<MediaAlbumTrack>,
    metadataRefreshKey: Any? = null,
    selectionResetKey: Any,
    trackSort: AlbumTrackSortOptions,
    onTrackSortChanged: (AlbumTrackSortOptions) -> Unit,
    isActive: Boolean,
    onBack: () -> Unit,
    onPlay: (List<MediaAlbumTrack>, String?) -> Unit,
    onShuffle: (List<MediaAlbumTrack>) -> Unit,
    onFavoriteSelection: (List<MediaLibraryTrack>, () -> Unit) -> Unit,
    onAddSelectionToPlaylist: (List<MediaLibraryTrack>, () -> Unit) -> Unit,
    onShowInfo: (String) -> Unit,
    tagPrefix: String,
    revealRequest: MediaCategoryRevealRequest? = null,
    onRevealFinished: (Long) -> Unit = {},
    header: LazyListScope.() -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var sortOpen by remember { mutableStateOf(false) }
    var selectedPaths by remember(collectionKey) { mutableStateOf<Set<String>>(emptySet()) }
    val globalSelection = LocalMediaSelection.current
    var panelHeight by remember(collectionKey) { mutableStateOf(112.dp) }
    val visiblePaths = remember(visibleTracks) { visibleTracks.map { it.track.path } }
    val selectedTracks = remember(visibleTracks, selectedPaths) {
        visibleTracks.filter { it.track.path in selectedPaths }
    }
    val selectionMode = globalSelection?.active ?: selectedPaths.isNotEmpty()
    val localSelectionMode = globalSelection == null && selectionMode
    val (revealOpacity, revealPulse) = rememberMediaTrackRevealHighlight(collectionKey, revealRequest,
        visiblePaths, isActive, listState, onRevealFinished)
    val revealColor = MaterialTheme.colorScheme.primaryContainer
    LaunchedEffect(revealRequest?.token) {
        if (globalSelection == null && revealRequest != null) selectedPaths = emptySet()
    }
    LaunchedEffect(collectionKey, selectionResetKey) { if (globalSelection == null) selectedPaths = emptySet() }
    LaunchedEffect(visiblePaths, isActive, globalSelection) {
        if (globalSelection != null) {
            if (isActive) globalSelection.refreshVisiblePaths(visiblePaths)
        } else selectedPaths = selectedPaths.intersect(visiblePaths.toSet())
    }
    BackHandler(enabled = isActive && localSelectionMode) { selectedPaths = emptySet() }
    fun toggleSelection(path: String) {
        if (globalSelection != null) globalSelection.toggle(path)
        else selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }
    fun select(action: MediaLibrarySelectionAction) {
        selectedPaths = updateMediaLibrarySelection(visiblePaths, selectedPaths, action)
    }
    fun finishSelection(paths: Set<String>) { selectedPaths = selectedPaths - paths }

    Column(Modifier.fillMaxSize().testTag("$tagPrefix-detail")) {
        MediaTrackDetailTopBar(title, backDescription, tagPrefix) {
            if (localSelectionMode) selectedPaths = emptySet() else onBack()
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(bottom = if (localSelectionMode) panelHeight else 0.dp)
                    .testTag("$tagPrefix-tracks"),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                header()
                item(key = "actions") {
                    MediaTrackPlaylistActions(
                        tagPrefix = tagPrefix,
                        playbackEnabled = visibleTracks.isNotEmpty(),
                        onPlayAll = { onPlay(visibleTracks, null) },
                        onShuffle = { onShuffle(visibleTracks) },
                        onSort = { sortOpen = true }
                    )
                }
                items(visibleTracks, key = { it.track.path }) { item ->
                    BrowserFileRow(
                        modifier = Modifier.testTag("$tagPrefix-track:${item.track.path}")
                            .semantics {
                                if (selectionMode) selected = globalSelection?.contains(item.track.path)
                                    ?: (item.track.path in selectedPaths)
                            }.then(
                            if (isActive && item.track.path == revealRequest?.trackPath) Modifier
                                .background(revealColor.copy(alpha = revealOpacity))
                                .semantics {
                                    mediaTrackRevealPulse = revealPulse
                                    mediaTrackRevealOpacity = revealOpacity
                                }
                            else Modifier),
                        file = item.track.file,
                        metadataRefreshKey = metadataRefreshKey ?: item,
                        relativePathRoot = null,
                        isSelected = globalSelection?.contains(item.track.path) ?: (item.track.path in selectedPaths),
                        isSelectionMode = selectionMode,
                        onClick = { if (selectionMode) toggleSelection(item.track.path) else onPlay(visibleTracks, item.track.path) },
                        onLongClick = {
                            if (globalSelection != null) globalSelection.select(item.track.path)
                            else selectedPaths = selectedPaths + item.track.path
                        },
                        directoryPageStyle = true
                    )
                }
            }
            AppLazyListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                .padding(bottom = if (localSelectionMode) panelHeight else 0.dp))
            if (localSelectionMode) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaTrackDetailTopBar(title: String, backDescription: String, tagPrefix: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("$tagPrefix-detail-back")) {
                Icon(painterResource(R.drawable.ic_back), backDescription)
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = settingsPageBackground())
    )
}

@Composable
internal fun MediaTrackPlaylistActions(
    tagPrefix: String,
    playbackEnabled: Boolean,
    onPlayAll: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    onSort: () -> Unit,
    onUp: (() -> Unit)? = null,
    sortDescription: Int = R.string.media_album_detail_sort
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onUp != null) {
            IconButton(onClick = onUp, modifier = Modifier.testTag("$tagPrefix-up")) {
                Icon(painterResource(R.drawable.ic_folder_up), stringResource(R.string.media_folder_up))
            }
        }
        Spacer(Modifier.weight(1f))
        if (onPlayAll != null) {
            TextButton(onClick = onPlayAll, enabled = playbackEnabled, modifier = Modifier.testTag("$tagPrefix-play-all")) {
                Text(stringResource(R.string.media_album_play_all))
            }
        }
        if (onShuffle != null) {
            IconButton(onClick = onShuffle, enabled = playbackEnabled, modifier = Modifier.testTag("$tagPrefix-tracks-shuffle")) {
                Icon(painterResource(R.drawable.ic_album_shuffle), stringResource(R.string.media_album_detail_shuffle))
            }
        }
        IconButton(onClick = onSort, modifier = Modifier.testTag("$tagPrefix-tracks-sort")) {
            Icon(painterResource(R.drawable.ic_album_sort), stringResource(sortDescription))
        }
    }
}

@Composable
internal fun MediaTrackDurationSummary(trackCount: Int, durationMs: Long) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodyMedium
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.media_album_detail_song_count, trackCount), style = style, color = color)
        Icon(painterResource(R.drawable.ic_player_alarm), stringResource(R.string.player_info_duration),
            modifier = Modifier.size(16.dp), tint = color)
        Text(formatPlaybackTime(durationMs), style = style, color = color)
    }
}
