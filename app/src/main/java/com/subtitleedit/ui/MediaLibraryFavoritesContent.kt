package com.subtitleedit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryTrack

/** Favorites retain their playback actions while sharing the file list's selection tools. */
@Composable
internal fun FavoritesTabContent(
    tracks: List<MediaLibraryTrack>,
    onPlay: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onToggleFavorite: (MediaLibraryTrack) -> Unit,
    onAddToPlaylist: (MediaLibraryTrack) -> Unit,
    onRemoveFavorites: (List<MediaLibraryTrack>) -> Unit,
    onAddSelectionToPlaylist: (List<MediaLibraryTrack>) -> Unit,
    onShowSelectionInfo: (List<MediaLibraryTrack>) -> Unit,
    isActive: Boolean = true
) {
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    val globalSelection = LocalMediaSelection.current
    val visiblePaths = remember(tracks) { tracks.map { it.path } }
    val selectedTracks = remember(tracks, selectedPaths) { tracks.filter { it.path in selectedPaths } }
    val selectionMode = globalSelection?.active ?: selectedTracks.isNotEmpty()
    val localSelectionMode = globalSelection == null && selectionMode
    val density = LocalDensity.current
    var panelHeight by remember { mutableStateOf(112.dp) }

    LaunchedEffect(visiblePaths, isActive, globalSelection) {
        if (globalSelection != null) {
            if (isActive) globalSelection.refreshVisiblePaths(visiblePaths)
        } else selectedPaths = selectedPaths.intersect(visiblePaths.toSet())
    }
    BackHandler(enabled = isActive && localSelectionMode) { selectedPaths = emptySet() }

    fun toggle(path: String) {
        if (globalSelection != null) globalSelection.toggle(path)
        else selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }
    fun select(action: MediaLibrarySelectionAction) {
        selectedPaths = updateMediaLibrarySelection(visiblePaths, selectedPaths, action)
    }

    if (tracks.isEmpty()) {
        LibraryEmptyState(
            iconRes = R.drawable.ic_favorite_border,
            title = stringResource(R.string.media_lib_empty_favorites),
            subtitle = stringResource(R.string.media_lib_empty_favorites_hint)
        )
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("library-favorites-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = if (localSelectionMode) panelHeight else 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { PlayAllHeaderRow(count = tracks.size, onPlayAll = onPlayAll) }
            itemsIndexed(tracks, key = { _, item -> item.path }) { index, track ->
                MediaTrackCard(
                    track = track,
                    subtitle = track.artist.ifBlank { track.album.ifBlank { track.file.parentFile?.name.orEmpty() } },
                    onClick = { if (selectionMode) toggle(track.path) else onPlay(index) },
                    onLongClick = {
                        if (globalSelection != null) globalSelection.select(track.path)
                        else selectedPaths = selectedPaths + track.path
                    },
                    isSelected = globalSelection?.contains(track.path) ?: (track.path in selectedPaths),
                    isSelectionMode = selectionMode,
                    modifier = Modifier.testTag("library-favorite:${track.path}"),
                    trailingContent = {
                        if (!selectionMode) {
                            IconButton(onClick = { onAddToPlaylist(track) }, modifier = Modifier.size(36.dp)) {
                                Icon(painterResource(R.drawable.ic_playlist_add),
                                    contentDescription = stringResource(R.string.player_action_add_to_playlist),
                                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onToggleFavorite(track) }, modifier = Modifier.size(36.dp)) {
                                Icon(painterResource(R.drawable.ic_favorite),
                                    contentDescription = stringResource(R.string.player_action_unfavorite),
                                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                )
            }
        }
        if (localSelectionMode) {
            MediaLibrarySelectionPanel(
                selectedFiles = selectedTracks.map { it.file },
                totalDurationMs = selectedTracks.sumOf { it.durationMs },
                onRemove = ::toggle,
                onFavorite = { onRemoveFavorites(selectedTracks); selectedPaths = emptySet() },
                onAddToPlaylist = { onAddSelectionToPlaylist(selectedTracks); selectedPaths = emptySet() },
                onShowInfo = { onShowSelectionInfo(selectedTracks) },
                onCancel = { selectedPaths = emptySet() },
                onSelectAll = { select(MediaLibrarySelectionAction.SELECT_ALL) },
                onSelectRange = { select(MediaLibrarySelectionAction.SELECT_RANGE) },
                onInvertSelection = { select(MediaLibrarySelectionAction.INVERT) },
                modifier = Modifier.align(Alignment.BottomCenter).heightIn(max = maxHeight * 0.7f)
                    .onSizeChanged { panelHeight = with(density) { it.height.toDp() } },
                favoriteActionLabelRes = R.string.player_action_unfavorite
            )
        }
    }
}
