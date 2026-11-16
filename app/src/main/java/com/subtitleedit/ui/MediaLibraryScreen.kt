package com.subtitleedit.ui

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaTagEditorActivity
import com.subtitleedit.R
import com.subtitleedit.feature.ui.BrowserFileLayout
import com.subtitleedit.feature.ui.BrowserFileRow
import com.subtitleedit.feature.ui.browserFileContentPadding
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.util.SharedFileCache
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.MediaPlaylist
import com.subtitleedit.library.PlaybackHistoryEntry
import com.subtitleedit.library.PlayerTrackDetails
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.player.PlayerActivity
import com.subtitleedit.player.LocalPlayerLauncher
import com.subtitleedit.player.PlayerQueueSource
import com.subtitleedit.player.PlayerQueueSourceKind
import com.subtitleedit.player.putPlayerQueueSource
import com.subtitleedit.player.formatPlaybackTime
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.theme.cardContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class MediaLibraryTab(val labelRes: Int) {
    FILES(R.string.media_lib_tab_search),
    CUSTOM_PLAYLISTS(R.string.media_lib_tab_playlists),
    PLAYLIST(R.string.media_lib_tab_queue),
    HISTORY(R.string.media_lib_tab_history)
}

private data class MediaLibraryContent(
    val favorites: List<MediaLibraryTrack> = emptyList(),
    val currentQueue: Pair<List<MediaLibraryTrack>, Int> = emptyList<MediaLibraryTrack>() to 0,
    val playlists: List<MediaPlaylist> = emptyList(),
    val history: List<PlaybackHistoryEntry> = emptyList(),
    val allTracks: List<MediaLibraryTrack> = emptyList(),
    val playlistId: Long? = null,
    val playlistTracks: List<MediaLibraryTrack> = emptyList()
)

internal data class MediaFolderEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val mediaCount: Int,
    val totalDurationMs: Long
)

@Composable
internal fun MediaLibraryScreen(
    refreshVersion: Int,
    selectionVersion: Int,
    onToolbarChanged: (String, Boolean, (() -> Unit)?) -> Unit,
    onRequestScanDirectory: () -> Unit = {},
    onRemoveFavoriteDirectory: (File) -> Unit = {},
    favoriteDirectories: List<File> = emptyList(),
    isActive: Boolean = true,
    scanPickerPending: Boolean = false
) {
    val context = LocalContext.current
    val playerLauncher = LocalPlayerLauncher.current
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

    var selectedTab by rememberSaveable { mutableStateOf(MediaLibraryTab.FILES) }
    var openedPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }

    var content by remember { mutableStateOf(MediaLibraryContent()) }
    val favorites = content.favorites
    val currentQueue = content.currentQueue
    val playlists = content.playlists
    val history = content.history
    val openedPlaylistTracks = if (content.playlistId == openedPlaylistId) content.playlistTracks else emptyList()
    val favoritePaths = remember(favorites) { favorites.mapTo(hashSetOf()) { it.path } }

    var browseRootPath by rememberSaveable { mutableStateOf<String?>(null) }
    var browsePath by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFilePaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingFolderRemoval by remember { mutableStateOf<File?>(null) }
    var infoFilePath by remember { mutableStateOf<String?>(null) }

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var renamingPlaylist by remember { mutableStateOf<MediaPlaylist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<MediaPlaylist?>(null) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var pickingPlaylistForTracks by remember { mutableStateOf<List<MediaLibraryTrack>?>(null) }

    fun openRootFolder(directory: File) {
        browseRootPath = directory.absolutePath
        browsePath = directory.absolutePath
        selectedFilePaths = emptySet()
    }

    fun openChildFolder(directory: File) {
        if (browseRootPath == null) browseRootPath = directory.absolutePath
        browsePath = directory.absolutePath
        selectedFilePaths = emptySet()
    }

    fun leaveFolder() {
        val current = browsePath ?: return
        val root = browseRootPath?.let(::File)
        val parent = File(current).parentFile
        val next = when {
            root == null -> null
            File(current).absolutePath == root.absolutePath -> null
            parent != null && (parent.absolutePath == root.absolutePath ||
                parent.absolutePath.startsWith(root.absolutePath + File.separator)) -> parent.absolutePath
            else -> null
        }
        browsePath = next
        if (next == null) browseRootPath = null
        selectedFilePaths = emptySet()
    }

    val browseDirectory = browsePath?.let(::File)
    val folderEntries = remember(content.allTracks, browsePath) {
        browseDirectory?.let { buildMediaFolderEntries(it, content.allTracks) }.orEmpty()
    }
    val rootMediaCounts = remember(content.allTracks, favoriteDirectories) {
        favoriteDirectories.associate { it.absolutePath to countMediaUnder(it, content.allTracks) }
    }
    val selectedDurationMs = remember(selectedFilePaths, content.allTracks) {
        content.allTracks.filter { it.path in selectedFilePaths }.sumOf { it.durationMs }
    }

    val openedPlaylist = remember(playlists, openedPlaylistId) {
        openedPlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }
    }

    val libraryTitle = stringResource(R.string.nav_library)
    // Selecting the current top-level tab resets its toolbar while retaining this page.
    LaunchedEffect(isActive, selectionVersion, openedPlaylist, browsePath, libraryTitle) {
        if (isActive) {
            when {
                openedPlaylist != null -> toolbarCallback(openedPlaylist.name, true) { openedPlaylistId = null }
                browsePath != null -> toolbarCallback(File(browsePath!!).name, true) { leaveFolder() }
                else -> toolbarCallback(libraryTitle, false, null)
            }
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab != MediaLibraryTab.FILES) {
            browsePath = null
            browseRootPath = null
            selectedFilePaths = emptySet()
        }
    }

    BackHandler(enabled = isActive && browsePath != null) {
        if (selectedFilePaths.isNotEmpty()) selectedFilePaths = emptySet() else leaveFolder()
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
                    playlists = repository.getPlaylists(),
                    history = repository.getPlaybackHistory(),
                    allTracks = repository.searchLibrary(""),
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

    fun playTracks(tracks: List<MediaLibraryTrack>, startIndex: Int, playAll: Boolean = false,
        queueSource: PlayerQueueSource? = null) {
        val paths = tracks.map { it.path }
        scope.launch {
            try {
                val selection = withContext(Dispatchers.IO) {
                    LibraryPlaybackSelection.resolve(paths, startIndex, playAll) { File(it).isFile }
                }
                if (selection == null) {
                    Toast.makeText(context, context.getString(R.string.media_lib_file_missing), Toast.LENGTH_SHORT).show()
                } else if (playerLauncher != null) {
                    playerLauncher(selection.paths, selection.startIndex, null, queueSource)
                } else {
                    context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                        putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, ArrayList(selection.paths))
                        putExtra(PlayerActivity.EXTRA_START_INDEX, selection.startIndex)
                        putPlayerQueueSource(queueSource)
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
            val queueSource = PlayerQueueSource(PlayerQueueSourceKind.PLAYLIST, openedPlaylist.id.toString())
            if (openedPlaylist.id == MediaLibraryRepository.FAVORITES_PLAYLIST_ID) {
                FavoritesTabContent(
                    tracks = favorites,
                    onPlay = { idx -> playTracks(favorites, idx, queueSource = queueSource) },
                    onPlayAll = { playTracks(favorites, 0, playAll = true, queueSource = queueSource) },
                    onToggleFavorite = { track -> mutate({ repository.removeFavorite(track.path) }) },
                    onAddToPlaylist = { pickingPlaylistForTracks = listOf(it) },
                    onRemoveFavorites = { tracks ->
                        mutate({ tracks.forEach { repository.removeFavorite(it.path) } })
                    },
                    onAddSelectionToPlaylist = { pickingPlaylistForTracks = it },
                    onShowSelectionInfo = { tracks ->
                        val track = tracks.singleOrNull()
                        if (track == null) {
                            Toast.makeText(context, context.getString(R.string.media_lib_files_info_single), Toast.LENGTH_SHORT).show()
                        } else {
                            infoFilePath = track.path
                        }
                    },
                    isActive = isActive
                )
            } else {
                PlaylistDetailContent(
                    playlist = openedPlaylist,
                    tracks = openedPlaylistTracks,
                    onPlayTrack = { idx -> playTracks(openedPlaylistTracks, idx, queueSource = queueSource) },
                    onPlayAll = { playTracks(openedPlaylistTracks, 0, playAll = true, queueSource = queueSource) },
                    onRemoveTrack = { track ->
                        mutate({ repository.removeTrackFromPlaylist(openedPlaylist.id, track.path) })
                    }
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                if (browsePath == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MediaLibraryTab.entries.forEach { tab ->
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
                }

                when (selectedTab) {
                    MediaLibraryTab.PLAYLIST -> QueueTabContent(
                        tracks = currentQueue.first,
                        currentIndex = currentQueue.second,
                        onPlay = { idx -> playTracks(currentQueue.first, idx) },
                        onPlayAll = { playTracks(currentQueue.first, currentQueue.second, playAll = true) },
                        onAddToPlaylist = { pickingPlaylistForTracks = listOf(it) }
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

                    MediaLibraryTab.FILES -> FilesTabContent(
                        roots = favoriteDirectories,
                        metadataRefreshKey = refreshVersion,
                        rootPath = browseRootPath,
                        currentPath = browsePath,
                        entries = folderEntries,
                        rootMediaCounts = rootMediaCounts,
                        selectedPaths = selectedFilePaths,
                        selectedDurationMs = selectedDurationMs,
                        onOpenRoot = ::openRootFolder,
                        onOpenFolder = ::openChildFolder,
                        onPlayFile = { entry ->
                            val queue = mediaFolderPlaybackQueue(folderEntries)
                            val index = queue.indexOfFirst { it.path == entry.path }
                            if (index >= 0) playTracks(queue, index, queueSource =
                                File(entry.path).absoluteFile.parent?.let {
                                    PlayerQueueSource(PlayerQueueSourceKind.DIRECTORY, it)
                                })
                        },
                        onLongPressFolder = { pendingFolderRemoval = it },
                        onLongPressFile = { selectedFilePaths = selectedFilePaths + it },
                        onToggleSelection = { path ->
                            selectedFilePaths = if (path in selectedFilePaths) {
                                selectedFilePaths - path
                            } else {
                                selectedFilePaths + path
                            }
                        },
                        onClearSelection = { selectedFilePaths = emptySet() },
                        onAdvancedSelection = { action ->
                            selectedFilePaths = updateMediaLibrarySelection(
                                visiblePaths = folderEntries.filterNot { it.isDirectory }.map { it.path },
                                selectedPaths = selectedFilePaths,
                                action = action
                            )
                        },
                        onFavoriteSelection = {
                            val paths = selectedFilePaths
                            mutate({
                                paths.forEach { path ->
                                    if (!repository.isFavorite(path)) repository.toggleFavorite(path)
                                }
                            }) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.media_lib_files_favorited, paths.size),
                                    Toast.LENGTH_SHORT
                                ).show()
                                selectedFilePaths = emptySet()
                            }
                        },
                        onAddSelectionToPlaylist = {
                            pickingPlaylistForTracks = selectedFilePaths.map { path ->
                                MediaLibraryTrack(path = path, title = File(path).nameWithoutExtension)
                            }
                            selectedFilePaths = emptySet()
                        },
                        onShowInfo = {
                            val path = selectedFilePaths.singleOrNull()
                            if (path == null) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.media_lib_files_info_single),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                infoFilePath = path
                            }
                        }
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

            if (selectedTab == MediaLibraryTab.FILES && browsePath == null && selectedFilePaths.isEmpty()) {
                FloatingActionButton(
                    onClick = { if (!isScanning) requestScan() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.media_lib_scan_folder),
                            modifier = Modifier.size(24.dp)
                        )
                    }
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

    pickingPlaylistForTracks?.let { tracks ->
        LibraryPlaylistAssignDialog(
            playlists = playlists,
            onDismiss = { pickingPlaylistForTracks = null },
            onSelectPlaylist = { playlist ->
                var added = 0
                mutate({
                    tracks.forEach {
                        if (repository.addTrackToPlaylist(playlist.id, it.path, it.durationMs)) added++
                    }
                }) {
                    Toast.makeText(
                        context,
                        if (added > 0) context.getString(R.string.media_lib_added_tracks_to_playlist, added, playlist.name)
                        else context.getString(R.string.player_playlist_picker_already_in),
                        Toast.LENGTH_SHORT
                    ).show()
                    pickingPlaylistForTracks = null
                }
            },
            onCreatePlaylist = { name ->
                mutate({
                    val playlistId = checkNotNull(repository.createPlaylist(name))
                    tracks.forEach { repository.addTrackToPlaylist(playlistId, it.path, it.durationMs) }
                }) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.media_lib_added_tracks_to_playlist, tracks.size, name),
                        Toast.LENGTH_SHORT
                    ).show()
                    pickingPlaylistForTracks = null
                }
            }
        )
    }

    pendingFolderRemoval?.let { directory ->
        AppAlertDialog(
            title = stringResource(R.string.media_lib_files_remove_folder),
            message = stringResource(R.string.media_lib_files_remove_folder_confirm, directory.name),
            confirmText = stringResource(R.string.confirm),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                if (browseRootPath == directory.absolutePath) {
                    browsePath = null
                    browseRootPath = null
                    selectedFilePaths = emptySet()
                }
                onRemoveFavoriteDirectory(directory)
                pendingFolderRemoval = null
            },
            onDismiss = { pendingFolderRemoval = null }
        )
    }

    infoFilePath?.let { path ->
        MediaFileInfoDialog(path = path, onDismiss = { infoFilePath = null })
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
internal fun CustomPlaylistsTabContent(
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

private fun buildMediaFolderEntries(
    directory: File,
    tracks: List<MediaLibraryTrack>
): List<MediaFolderEntry> {
    val base = directory.absolutePath.trimEnd(File.separatorChar)
    val directories = LinkedHashMap<String, MediaFolderEntry>()
    val files = ArrayList<MediaFolderEntry>()
    tracks.forEach { track ->
        val path = track.path
        val relative = when {
            path == base -> ""
            path.startsWith("$base/") -> path.substring(base.length + 1)
            else -> return@forEach
        }
        if (relative.isEmpty()) return@forEach
        val segments = relative.split('/')
        if (segments.size == 1) {
            files += MediaFolderEntry(
                path = path,
                name = segments.first(),
                isDirectory = false,
                mediaCount = 1,
                totalDurationMs = track.durationMs
            )
        } else {
            val childPath = "$base/${segments.first()}"
            val existing = directories[childPath]
            directories[childPath] = MediaFolderEntry(
                path = childPath,
                name = segments.first(),
                isDirectory = true,
                mediaCount = (existing?.mediaCount ?: 0) + 1,
                totalDurationMs = (existing?.totalDurationMs ?: 0L) + track.durationMs
            )
        }
    }
    return directories.values.sortedBy { it.name.lowercase(Locale.ROOT) } +
        files.sortedBy { it.name.lowercase(Locale.ROOT) }
}

private fun countMediaUnder(directory: File, tracks: List<MediaLibraryTrack>): Int {
    val base = directory.absolutePath.trimEnd(File.separatorChar)
    return tracks.count { it.path.startsWith("$base/") }
}

@Composable
internal fun FilesTabContent(
    roots: List<File>,
    rootPath: String?,
    currentPath: String?,
    entries: List<MediaFolderEntry>,
    rootMediaCounts: Map<String, Int>,
    selectedPaths: Set<String>,
    selectedDurationMs: Long,
    onOpenRoot: (File) -> Unit,
    onOpenFolder: (File) -> Unit,
    onPlayFile: (MediaFolderEntry) -> Unit,
    onLongPressFolder: (File) -> Unit,
    onLongPressFile: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onClearSelection: () -> Unit,
    onAdvancedSelection: (MediaLibrarySelectionAction) -> Unit,
    onFavoriteSelection: () -> Unit,
    onAddSelectionToPlaylist: () -> Unit,
    onShowInfo: () -> Unit,
    metadataRefreshKey: Any? = null
) {
    val selectionMode = selectedPaths.isNotEmpty()
    val density = LocalDensity.current
    var selectionPanelHeight by remember(currentPath) { mutableStateOf(112.dp) }
    val folderListState = remember(currentPath) { LazyListState() }
    val selectedFiles = remember(entries, selectedPaths) {
        entries.filter { !it.isDirectory && it.path in selectedPaths }.map { File(it.path) }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val bottomInset = if (selectionMode) selectionPanelHeight else 4.dp
        if (currentPath == null) {
            if (roots.isEmpty()) {
                LibraryEmptyState(
                    iconRes = R.drawable.ic_folder,
                    title = stringResource(R.string.media_lib_files_empty),
                    subtitle = stringResource(R.string.media_lib_files_empty_hint)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(roots, key = { _, directory -> directory.absolutePath }) { _, directory ->
                        RootFolderRow(
                            directory = directory,
                            mediaCount = rootMediaCounts[directory.absolutePath] ?: 0,
                            onClick = { onOpenRoot(directory) },
                            onLongClick = { onLongPressFolder(directory) }
                        )
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                FolderBreadcrumb(rootPath = rootPath, currentPath = currentPath)
                if (entries.isEmpty()) {
                    LibraryEmptyState(
                        iconRes = R.drawable.ic_folder,
                        title = stringResource(R.string.media_lib_files_folder_empty),
                        subtitle = ""
                    )
                } else {
                    BrowserFileLayout(
                        items = entries,
                        key = { it.path },
                        viewMode = FileBrowserViewMode.DETAILED,
                        listState = folderListState,
                        modifier = Modifier.fillMaxSize().testTag("library-files-list"),
                        contentPadding = browserFileContentPadding(FileBrowserViewMode.DETAILED, bottom = bottomInset)
                    ) { entry ->
                        BrowserFileRow(
                            modifier = Modifier.testTag("library-file:${entry.path}"),
                            file = File(entry.path),
                            isDirectoryHint = entry.isDirectory,
                            metadataRefreshKey = metadataRefreshKey ?: entry,
                            relativePathRoot = null,
                            isSelected = entry.path in selectedPaths,
                            isSelectionMode = selectionMode,
                            onClick = {
                                if (entry.isDirectory) onOpenFolder(File(entry.path))
                                else if (selectionMode) onToggleSelection(entry.path)
                                else onPlayFile(entry)
                            },
                            onLongClick = {
                                if (!entry.isDirectory) onLongPressFile(entry.path)
                            },
                            viewMode = FileBrowserViewMode.DETAILED,
                            directoryPageStyle = true
                        )
                    }
                }
            }
        }

        if (selectionMode) {
            MediaLibrarySelectionPanel(
                selectedFiles = selectedFiles,
                totalDurationMs = selectedDurationMs,
                onRemove = onToggleSelection,
                onFavorite = onFavoriteSelection,
                onAddToPlaylist = onAddSelectionToPlaylist,
                onShowInfo = onShowInfo,
                onCancel = onClearSelection,
                onSelectAll = { onAdvancedSelection(MediaLibrarySelectionAction.SELECT_ALL) },
                onSelectRange = { onAdvancedSelection(MediaLibrarySelectionAction.SELECT_RANGE) },
                onInvertSelection = { onAdvancedSelection(MediaLibrarySelectionAction.INVERT) },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .heightIn(max = maxHeight * 0.7f)
                    .onSizeChanged { selectionPanelHeight = with(density) { it.height.toDp() } }
            )
        }
    }
}

@Composable
private fun RootFolderRow(
    directory: File,
    mediaCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = directory.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = directory.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.media_lib_files_folder_count, mediaCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FolderBreadcrumb(rootPath: String?, currentPath: String) {
    val segments = remember(rootPath, currentPath) {
        val root = rootPath?.let(::File) ?: return@remember emptyList()
        val base = root.absolutePath.trimEnd(File.separatorChar)
        val relative = when {
            File(currentPath).absolutePath == base -> ""
            currentPath.startsWith("$base/") -> currentPath.substring(base.length + 1)
            else -> ""
        }
        listOf(root.name) + relative.split('/').filter { it.isNotBlank() }
    }
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                Text(
                    text = "›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = segment,
                style = MaterialTheme.typography.labelMedium,
                color = if (index == segments.lastIndex) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun MediaFileInfoDialog(path: String, onDismiss: () -> Unit) {
    var details by remember(path) { mutableStateOf<PlayerTrackDetails?>(null) }
    var loaded by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        details = withContext(Dispatchers.IO) {
            runCatching { PlayerTrackMetadataReader.read(File(path)) }.getOrNull()
        }
        loaded = true
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_lib_files_info)) },
        text = {
            val current = details
            if (current == null && loaded) {
                Text(
                    text = stringResource(R.string.media_lib_file_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (current == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                }
            } else {
                val unknown = stringResource(R.string.player_info_unknown)
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FileInfoField(stringResource(R.string.player_info_file_name), current.fileName)
                    FileInfoField(
                        stringResource(R.string.player_info_location),
                        current.locationDisplay.ifBlank { current.filePath }
                    )
                    FileInfoField(stringResource(R.string.player_info_duration), current.durationText.ifBlank { unknown })
                    FileInfoField(stringResource(R.string.player_info_file_size), current.fileSizeText.ifBlank { unknown })
                    FileInfoField(stringResource(R.string.player_info_genre), current.genre.ifBlank { unknown })
                    FileInfoField(stringResource(R.string.player_info_track_title), current.title.ifBlank { unknown })
                    FileInfoField(stringResource(R.string.player_info_artist), current.artist.ifBlank { unknown })
                    FileInfoField(stringResource(R.string.player_info_album), current.album.ifBlank { unknown })
                    FileInfoField(stringResource(R.string.player_info_year), current.year.ifBlank { unknown })
                    FileInfoField(stringResource(R.string.player_info_album_artist), current.albumArtist.ifBlank { unknown })
                    FileInfoField(stringResource(R.string.player_info_composer), current.composer.ifBlank { unknown })
                    FileInfoField(stringResource(R.string.player_info_track_number), current.trackNumber.ifBlank { "0" })
                    FileInfoField(stringResource(R.string.player_info_disc_number), current.discNumber.ifBlank { "1" })
                    FileInfoField(stringResource(R.string.player_info_comment), current.comment.ifBlank { unknown })
                    FileInfoField(stringResource(R.string.player_info_lyricist), current.lyricist.ifBlank { unknown })
                    FileInfoField(stringResource(R.string.player_info_copyright), current.copyright.ifBlank { unknown })
                    FileInfoField(stringResource(R.string.player_info_language), current.language.ifBlank { unknown })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_info_done)) }
        },
        dismissButton = {
            val context = LocalContext.current
            TextButton(
                enabled = details?.canEditTags == true,
                onClick = {
                    onDismiss()
                    context.startActivity(Intent(context, MediaTagEditorActivity::class.java).apply {
                        putStringArrayListExtra(MediaTagEditorActivity.EXTRA_FILE_PATHS, arrayListOf(path))
                    })
                }
            ) { Text(stringResource(R.string.player_info_edit_tags)) }
        }
    )
}

@Composable
private fun FileInfoField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun PlayAllHeaderRow(count: Int, onPlayAll: () -> Unit) {
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
            withContext(Dispatchers.IO) { SharedFileCache.attributes.get(File(path))?.let { metadata -> SharedMediaCache.artwork(metadata, 128) } }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).testTag("library-playlist:${playlist.id}"),
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
                        painter = painterResource(if (playlist.id == MediaLibraryRepository.FAVORITES_PLAYLIST_ID)
                            R.drawable.ic_favorite else R.drawable.ic_queue_music),
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
                    text = if (playlist.isSystem) stringResource(R.string.media_lib_system_playlist_count, playlist.itemCount)
                        else stringResource(R.string.player_playlist_picker_count, playlist.itemCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!playlist.isSystem) Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.testTag("library-playlist-actions:${playlist.id}")) {
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
internal fun MediaTrackCard(
    track: MediaLibraryTrack,
    subtitle: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    trailingContent: @Composable () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cover by produceState<Bitmap?>(initialValue = null, key1 = track.path) {
        value = withContext(Dispatchers.IO) { SharedFileCache.attributes.get(File(track.path))?.let { metadata -> SharedMediaCache.artwork(metadata, 128) } }
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (onLongClick == null) Modifier.clickable(onClick = onClick)
                else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))
            .semantics { if (isSelectionMode) selected = isSelected },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
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
                if (isSelectionMode) Checkbox(checked = isSelected, onCheckedChange = null)
                trailingContent()
            }
        }
    }
}

@Composable
internal fun LibraryEmptyState(
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
                                    .testTag("library-playlist-choice:${playlist.id}")
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (playlist.isSystem) Text(
                                        text = stringResource(R.string.media_lib_system_playlist),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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
