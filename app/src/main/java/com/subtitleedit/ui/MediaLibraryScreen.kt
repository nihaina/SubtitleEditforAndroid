package com.subtitleedit.ui

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.library.MediaArtworkLoader
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.MediaPlaylist
import com.subtitleedit.library.PlaybackHistoryEntry
import com.subtitleedit.player.PlayerActivity
import com.subtitleedit.player.formatPlaybackTime
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.theme.cardContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class MediaLibraryTab(val labelRes: Int) {
    FAVORITES(R.string.media_lib_tab_favorites),
    PLAYLIST(R.string.media_lib_tab_queue),
    CUSTOM_PLAYLISTS(R.string.media_lib_tab_playlists),
    HISTORY(R.string.media_lib_tab_history),
    SEARCH(R.string.media_lib_tab_search)
}

private data class MediaLibraryContent(
    val favorites: List<MediaLibraryTrack> = emptyList(),
    val currentQueue: Pair<List<MediaLibraryTrack>, Int> = emptyList<MediaLibraryTrack>() to 0,
    val playlists: List<MediaPlaylist> = emptyList(),
    val history: List<PlaybackHistoryEntry> = emptyList(),
    val playlistId: Long? = null,
    val playlistTracks: List<MediaLibraryTrack> = emptyList()
)

@Composable
internal fun MediaLibraryScreen(
    refreshVersion: Int,
    selectionVersion: Int,
    onToolbarChanged: (String, Boolean, (() -> Unit)?) -> Unit,
    onRequestScanDirectory: () -> Unit = {},
    favoriteDirectories: List<File> = emptyList(),
    isActive: Boolean = true,
    scanPickerPending: Boolean = false
) {
    val context = LocalContext.current
    val repository = remember(context) { MediaLibraryRepository.getInstance(context) }
    val revision by repository.revision.collectAsState()
    val scope = rememberCoroutineScope()
    val mutationMutex = remember { Mutex() }
    val toolbarCallback by rememberUpdatedState(onToolbarChanged)
    val requestScan by rememberUpdatedState(onRequestScanDirectory)
    val scanRunning by MediaLibraryScanCoordinator.scanning.collectAsState()
    val isScanning = scanRunning || scanPickerPending

    fun mutate(action: () -> Unit, onSuccess: () -> Unit = {}) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { mutationMutex.withLock { action() } }
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showLibraryError(context, error)
            }
        }
    }

    var selectedTab by rememberSaveable { mutableStateOf(MediaLibraryTab.FAVORITES) }
    var openedPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }

    var content by remember { mutableStateOf(MediaLibraryContent()) }
    val favorites = content.favorites
    val currentQueue = content.currentQueue
    val playlists = content.playlists
    val history = content.history
    val openedPlaylistTracks = if (content.playlistId == openedPlaylistId) content.playlistTracks else emptyList()
    val favoritePaths = remember(favorites) { favorites.mapTo(hashSetOf()) { it.path } }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MediaLibraryTrack>>(emptyList()) }
    var displayedSearchQuery by remember { mutableStateOf<String?>(null) }

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var renamingPlaylist by remember { mutableStateOf<MediaPlaylist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<MediaPlaylist?>(null) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var pickingPlaylistForTrack by remember { mutableStateOf<MediaLibraryTrack?>(null) }

    val openedPlaylist = remember(playlists, openedPlaylistId) {
        openedPlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }
    }

    val libraryTitle = stringResource(R.string.nav_library)
    LaunchedEffect(isActive, openedPlaylist, libraryTitle) {
        if (isActive) {
            if (openedPlaylist != null) {
                toolbarCallback(openedPlaylist.name, true) { openedPlaylistId = null }
            } else {
                toolbarCallback(libraryTitle, false, null)
            }
        }
    }

    var lastSelectionVersion by remember { mutableIntStateOf(selectionVersion) }
    LaunchedEffect(selectionVersion, isActive) {
        if (isActive && lastSelectionVersion != selectionVersion) {
            openedPlaylistId = null
            lastSelectionVersion = selectionVersion
        }
    }

    LaunchedEffect(revision, refreshVersion, openedPlaylistId) {
        val playlistId = openedPlaylistId
        try {
            val loaded = withContext(Dispatchers.IO) {
                MediaLibraryContent(
                    favorites = repository.getFavorites(),
                    currentQueue = repository.getCurrentQueue(),
                    playlists = repository.getPlaylists().filter { !it.isSystem },
                    history = repository.getPlaybackHistory(),
                    playlistId = playlistId,
                    playlistTracks = playlistId?.let(repository::getPlaylistTracks).orEmpty()
                )
            }
            content = loaded
            if (playlistId != null && loaded.playlists.none { it.id == playlistId }) openedPlaylistId = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showLibraryError(context, error)
        }
    }

    LaunchedEffect(searchQuery, revision, selectedTab) {
        if (selectedTab != MediaLibraryTab.SEARCH) return@LaunchedEffect
        val query = searchQuery
        if (query != displayedSearchQuery) searchResults = emptyList()
        try {
            if (query.isNotBlank()) delay(180L)
            val results = withContext(Dispatchers.IO) { repository.searchLibrary(query) }
            searchResults = results
            displayedSearchQuery = query
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showLibraryError(context, error)
        }
    }

    fun playTracks(tracks: List<MediaLibraryTrack>, startIndex: Int, playAll: Boolean = false) {
        val paths = tracks.map { it.path }
        scope.launch {
            try {
                val selection = withContext(Dispatchers.IO) {
                    LibraryPlaybackSelection.resolve(paths, startIndex, playAll) { File(it).isFile }
                }
                if (selection == null) {
                    Toast.makeText(context, context.getString(R.string.media_lib_file_missing), Toast.LENGTH_SHORT).show()
                } else {
                    context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                        putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, ArrayList(selection.paths))
                        putExtra(PlayerActivity.EXTRA_START_INDEX, selection.startIndex)
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showLibraryError(context, error)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(settingsPageBackground())
    ) {
        if (openedPlaylist != null) {
            PlaylistDetailContent(
                playlist = openedPlaylist,
                tracks = openedPlaylistTracks,
                onPlayTrack = { idx -> playTracks(openedPlaylistTracks, idx) },
                onPlayAll = { playTracks(openedPlaylistTracks, 0, playAll = true) },
                onRemoveTrack = { track ->
                    mutate({ repository.removeTrackFromPlaylist(openedPlaylist.id, track.path) })
                }
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(MediaLibraryTab.entries) { tab ->
                        val selected = selectedTab == tab
                        FilterChip(
                            selected = selected,
                            onClick = { selectedTab = tab },
                            label = { Text(stringResource(tab.labelRes)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )
                    }
                }

                when (selectedTab) {
                    MediaLibraryTab.FAVORITES -> FavoritesTabContent(
                        tracks = favorites,
                        onPlay = { idx -> playTracks(favorites, idx) },
                        onPlayAll = { playTracks(favorites, 0, playAll = true) },
                        onToggleFavorite = { track ->
                            mutate({ repository.removeFavorite(track.path) })
                        },
                        onAddToPlaylist = { pickingPlaylistForTrack = it }
                    )

                    MediaLibraryTab.PLAYLIST -> QueueTabContent(
                        tracks = currentQueue.first,
                        currentIndex = currentQueue.second,
                        onPlay = { idx -> playTracks(currentQueue.first, idx) },
                        onPlayAll = { playTracks(currentQueue.first, currentQueue.second, playAll = true) },
                        onAddToPlaylist = { pickingPlaylistForTrack = it }
                    )

                    MediaLibraryTab.CUSTOM_PLAYLISTS -> CustomPlaylistsTabContent(
                        playlists = playlists,
                        onOpen = { openedPlaylistId = it.id },
                        onRename = { renamingPlaylist = it },
                        onDelete = { deletingPlaylist = it }
                    )

                    MediaLibraryTab.HISTORY -> HistoryTabContent(
                        entries = history,
                        onPlay = { idx ->
                            val tracks = history.map {
                                MediaLibraryTrack(it.path, it.title, it.artist, it.album, it.isVideo, it.durationMs, it.playedAtMs)
                            }
                            playTracks(tracks, idx)
                        },
                        onRemove = { entry ->
                            mutate({ repository.removePlaybackHistory(entry.path) })
                        },
                        onClearAll = { showClearHistoryConfirm = true }
                    )

                    MediaLibraryTab.SEARCH -> SearchTabContent(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        results = searchResults,
                        isScanning = isScanning,
                        favoritePaths = favoritePaths,
                        onScanDirs = { if (!isScanning) requestScan() },
                        onQuickIndexFavorites = {
                            if (!isScanning) {
                                if (favoriteDirectories.isEmpty()) requestScan()
                                else MediaLibraryScanCoordinator.scan(context, favoriteDirectories)
                            }
                        },
                        onPlay = { idx -> playTracks(searchResults, idx) },
                        onToggleFavorite = { track ->
                            mutate({ repository.toggleFavorite(track.path, track.durationMs) })
                        },
                        onAddToPlaylist = { pickingPlaylistForTrack = it }
                    )
                }
            }

            if (selectedTab == MediaLibraryTab.CUSTOM_PLAYLISTS) {
                FloatingActionButton(
                    onClick = { showCreatePlaylistDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.media_lib_create_playlist),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        var name by remember { mutableStateOf("") }
        AppDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text(stringResource(R.string.media_lib_create_playlist)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.player_playlist_picker_new_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) {
                            mutate({ check(repository.createPlaylist(trimmed) != null) }) {
                                showCreatePlaylistDialog = false
                            }
                        }
                    },
                    enabled = name.trim().isNotBlank()
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    renamingPlaylist?.let { playlist ->
        var newName by remember(playlist.id) { mutableStateOf(playlist.name) }
        AppDialog(
            onDismissRequest = { renamingPlaylist = null },
            title = { Text(stringResource(R.string.media_lib_rename_playlist)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty()) {
                            mutate({ check(repository.renamePlaylist(playlist.id, trimmed)) }) {
                                renamingPlaylist = null
                            }
                        }
                    },
                    enabled = newName.trim().isNotBlank() && newName.trim() != playlist.name
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renamingPlaylist = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    deletingPlaylist?.let { playlist ->
        AppAlertDialog(
            title = stringResource(R.string.media_lib_delete_playlist),
            message = stringResource(R.string.media_lib_delete_playlist_confirm, playlist.name),
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                mutate({ repository.deletePlaylist(playlist.id) }) { deletingPlaylist = null }
            },
            onDismiss = { deletingPlaylist = null }
        )
    }

    if (showClearHistoryConfirm) {
        AppAlertDialog(
            title = stringResource(R.string.media_lib_clear_history),
            message = stringResource(R.string.media_lib_clear_history_confirm),
            confirmText = stringResource(R.string.confirm),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                mutate({ repository.clearPlaybackHistory() }) { showClearHistoryConfirm = false }
            },
            onDismiss = { showClearHistoryConfirm = false }
        )
    }

    pickingPlaylistForTrack?.let { track ->
        LibraryPlaylistAssignDialog(
            track = track,
            playlists = playlists,
            onDismiss = { pickingPlaylistForTrack = null },
            onSelectPlaylist = { playlist ->
                var added = false
                mutate({ added = repository.addTrackToPlaylist(playlist.id, track.path, track.durationMs) }) {
                    Toast.makeText(
                        context,
                        if (added) context.getString(R.string.media_lib_added_to_playlist, playlist.name)
                        else context.getString(R.string.player_playlist_picker_already_in),
                        Toast.LENGTH_SHORT
                    ).show()
                    pickingPlaylistForTrack = null
                }
            },
            onCreatePlaylist = { name ->
                mutate({
                    val playlistId = checkNotNull(repository.createPlaylist(name))
                    repository.addTrackToPlaylist(playlistId, track.path, track.durationMs)
                }) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.media_lib_added_to_playlist, name),
                        Toast.LENGTH_SHORT
                    ).show()
                    pickingPlaylistForTrack = null
                }
            }
        )
    }
}

@Composable
private fun FavoritesTabContent(
    tracks: List<MediaLibraryTrack>,
    onPlay: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onToggleFavorite: (MediaLibraryTrack) -> Unit,
    onAddToPlaylist: (MediaLibraryTrack) -> Unit
) {
    if (tracks.isEmpty()) {
        LibraryEmptyState(
            iconRes = R.drawable.ic_favorite_border,
            title = stringResource(R.string.media_lib_empty_favorites),
            subtitle = stringResource(R.string.media_lib_empty_favorites_hint)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PlayAllHeaderRow(count = tracks.size, onPlayAll = onPlayAll)
        }
        itemsIndexed(tracks, key = { _, item -> item.path }) { index, track ->
            MediaTrackCard(
                track = track,
                subtitle = track.artist.ifBlank { track.album.ifBlank { track.file.parentFile?.name.orEmpty() } },
                onClick = { onPlay(index) },
                trailingContent = {
                    IconButton(onClick = { onAddToPlaylist(track) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_playlist_add),
                            contentDescription = stringResource(R.string.player_action_add_to_playlist),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onToggleFavorite(track) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_favorite),
                            contentDescription = stringResource(R.string.player_action_unfavorite),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun QueueTabContent(
    tracks: List<MediaLibraryTrack>,
    currentIndex: Int,
    onPlay: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onAddToPlaylist: (MediaLibraryTrack) -> Unit
) {
    if (tracks.isEmpty()) {
        LibraryEmptyState(
            iconRes = R.drawable.ic_queue_music,
            title = stringResource(R.string.media_lib_empty_queue),
            subtitle = stringResource(R.string.media_lib_empty_queue_hint)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PlayAllHeaderRow(count = tracks.size, onPlayAll = onPlayAll)
        }
        itemsIndexed(tracks, key = { index, item -> "$index:${item.path}" }) { index, track ->
            val isCurrent = index == currentIndex
            MediaTrackCard(
                track = track,
                subtitle = if (isCurrent) stringResource(R.string.media_lib_last_playing) else track.file.parentFile?.name.orEmpty(),
                highlighted = isCurrent,
                onClick = { onPlay(index) },
                trailingContent = {
                    IconButton(onClick = { onAddToPlaylist(track) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_playlist_add),
                            contentDescription = stringResource(R.string.player_action_add_to_playlist),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun CustomPlaylistsTabContent(
    playlists: List<MediaPlaylist>,
    onOpen: (MediaPlaylist) -> Unit,
    onRename: (MediaPlaylist) -> Unit,
    onDelete: (MediaPlaylist) -> Unit
) {
    if (playlists.isEmpty()) {
        LibraryEmptyState(
            iconRes = R.drawable.ic_queue_music,
            title = stringResource(R.string.media_lib_empty_playlists),
            subtitle = stringResource(R.string.media_lib_empty_playlists_hint)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(playlists, key = { it.id }) { playlist ->
            PlaylistCardRow(
                playlist = playlist,
                onClick = { onOpen(playlist) },
                onRename = { onRename(playlist) },
                onDelete = { onDelete(playlist) }
            )
        }
    }
}

@Composable
private fun PlaylistDetailContent(
    playlist: MediaPlaylist,
    tracks: List<MediaLibraryTrack>,
    onPlayTrack: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onRemoveTrack: (MediaLibraryTrack) -> Unit
) {
    if (tracks.isEmpty()) {
        LibraryEmptyState(
            iconRes = R.drawable.ic_queue_music,
            title = stringResource(R.string.media_lib_playlist_empty, playlist.name),
            subtitle = stringResource(R.string.media_lib_playlist_empty_hint)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PlayAllHeaderRow(count = tracks.size, onPlayAll = onPlayAll)
        }
        itemsIndexed(tracks, key = { _, item -> item.path }) { index, track ->
            MediaTrackCard(
                track = track,
                subtitle = track.artist.ifBlank { track.album.ifBlank { track.file.parentFile?.name.orEmpty() } },
                onClick = { onPlayTrack(index) },
                trailingContent = {
                    IconButton(onClick = { onRemoveTrack(track) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.remove),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun HistoryTabContent(
    entries: List<PlaybackHistoryEntry>,
    onPlay: (Int) -> Unit,
    onRemove: (PlaybackHistoryEntry) -> Unit,
    onClearAll: () -> Unit
) {
    if (entries.isEmpty()) {
        LibraryEmptyState(
            iconRes = R.drawable.ic_history,
            title = stringResource(R.string.media_lib_empty_history),
            subtitle = stringResource(R.string.media_lib_empty_history_hint)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.media_lib_history_count, entries.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onClearAll) {
                    Text(stringResource(R.string.media_lib_clear_history))
                }
            }
        }
        itemsIndexed(entries, key = { _, item -> item.path }) { index, entry ->
            val track = remember(entry) {
                MediaLibraryTrack(
                    path = entry.path,
                    title = entry.title,
                    artist = entry.artist,
                    album = entry.album,
                    isVideo = entry.isVideo,
                    durationMs = entry.durationMs,
                    addedAtMs = entry.playedAtMs
                )
            }
            val timeText = remember(entry.playedAtMs) {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.playedAtMs))
            }
            val progressText = if (entry.positionMs > 0L) " · ${formatPlaybackTime(entry.positionMs)}" else ""
            MediaTrackCard(
                track = track,
                subtitle = "$timeText$progressText · ${entry.playCount}次",
                onClick = { onPlay(index) },
                trailingContent = {
                    IconButton(onClick = { onRemove(entry) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.delete),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun SearchTabContent(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<MediaLibraryTrack>,
    isScanning: Boolean,
    favoritePaths: Set<String>,
    onScanDirs: () -> Unit,
    onQuickIndexFavorites: () -> Unit,
    onPlay: (Int) -> Unit,
    onToggleFavorite: (MediaLibraryTrack) -> Unit,
    onAddToPlaylist: (MediaLibraryTrack) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = stringResource(R.string.media_lib_search_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.cancel),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
            IconButton(
                onClick = onScanDirs,
                enabled = !isScanning
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_favorite_directory),
                    contentDescription = stringResource(R.string.media_lib_scan_folder),
                    modifier = Modifier.size(30.dp),
                    tint = Color.Unspecified
                )
            }
        }

        if (results.isEmpty()) {
            LibraryEmptyState(
                iconRes = R.drawable.ic_search,
                title = if (query.isBlank()) stringResource(R.string.media_lib_search_empty_index)
                else stringResource(R.string.media_lib_search_no_match),
                subtitle = stringResource(R.string.media_lib_search_empty_hint),
                actionLabel = stringResource(R.string.media_lib_scan_folder),
                onAction = onQuickIndexFavorites
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(results, key = { _, item -> item.path }) { index, track ->
                    MediaTrackCard(
                        track = track,
                        subtitle = track.artist.ifBlank { track.album.ifBlank { track.file.parentFile?.name.orEmpty() } },
                        onClick = { onPlay(index) },
                        trailingContent = {
                            IconButton(onClick = { onAddToPlaylist(track) }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_playlist_add),
                                    contentDescription = stringResource(R.string.player_action_add_to_playlist),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onToggleFavorite(track) }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    painter = painterResource(if (track.path in favoritePaths) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
                                    contentDescription = stringResource(if (track.path in favoritePaths) R.string.player_action_unfavorite else R.string.player_action_favorite),
                                    modifier = Modifier.size(20.dp),
                                    tint = if (track.path in favoritePaths) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayAllHeaderRow(count: Int, onPlayAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.media_lib_track_count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onPlayAll) {
            Icon(
                painter = painterResource(R.drawable.ic_video_play),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.media_lib_play_all))
        }
    }
}

@Composable
private fun PlaylistCardRow(
    playlist: MediaPlaylist,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val cover by produceState<Bitmap?>(initialValue = null, key1 = playlist.firstTrackPath) {
        value = playlist.firstTrackPath?.let { path ->
            withContext(Dispatchers.IO) { MediaArtworkLoader.loadCover(path, 128) }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.cardContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                val bmp = cover
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_queue_music),
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.player_playlist_picker_count, playlist.itemCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vertical),
                        contentDescription = stringResource(R.string.activity_main_text_01),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.media_lib_rename_playlist)) },
                        onClick = { menuExpanded = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaTrackCard(
    track: MediaLibraryTrack,
    subtitle: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    trailingContent: @Composable () -> Unit = {}
) {
    val cover by produceState<Bitmap?>(initialValue = null, key1 = track.path) {
        value = withContext(Dispatchers.IO) { MediaArtworkLoader.loadCover(track.path, 128) }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.cardContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                val bmp = cover
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        painter = painterResource(
                            if (track.isVideo) R.drawable.ic_file_video else R.drawable.ic_file_audio
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.displayTitle,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildString {
                    if (subtitle.isNotBlank()) append(subtitle)
                    if (track.durationMs > 0L) {
                        if (isNotEmpty()) append(" · ")
                        append(formatPlaybackTime(track.durationMs))
                    }
                }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailingContent()
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    iconRes: Int,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
internal fun LibraryPlaylistAssignDialog(
    track: MediaLibraryTrack,
    playlists: List<MediaPlaylist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (MediaPlaylist) -> Unit,
    onCreatePlaylist: (String) -> Unit
) {
    var creatingPlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_action_add_to_playlist)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playlists.isEmpty()) {
                    Text(
                        text = stringResource(R.string.player_playlist_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false).testTag("library-playlist-choices"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectPlaylist(playlist) }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = stringResource(R.string.player_playlist_picker_count, playlist.itemCount),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (creatingPlaylist) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        placeholder = { Text(stringResource(R.string.player_playlist_picker_new_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedButton(
                        onClick = { creatingPlaylist = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.media_lib_create_playlist))
                    }
                }
            }
        },
        confirmButton = {
            if (creatingPlaylist) {
                TextButton(
                    onClick = { onCreatePlaylist(newPlaylistName.trim()) },
                    enabled = newPlaylistName.trim().isNotBlank()
                ) { Text(stringResource(R.string.player_playlist_picker_create)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
        dismissButton = if (creatingPlaylist) {
            {
                TextButton(onClick = { creatingPlaylist = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        } else null
    )
}
