package com.subtitleedit.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.library.*
import com.subtitleedit.player.PlayerActivity
import com.subtitleedit.player.PlayerPlayMode
import com.subtitleedit.ui.settings.settingsPageBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.coroutineContext

internal enum class MediaCategory(val label: Int) {
    ALBUMS(R.string.media_category_albums),
    SINGERS(R.string.media_category_singers),
    ARTISTS(R.string.media_category_artists),
    COMPOSERS(R.string.media_category_composers),
    TAGS(R.string.media_category_tags)
}

private data class AlbumPlaylistRequest(val tracks: List<MediaLibraryTrack>, val onComplete: () -> Unit)

@Composable
internal fun MediaAlbumScreen(
    refreshVersion: Int,
    favoriteDirectories: List<File>,
    isActive: Boolean
) {
    val context = LocalContext.current
    val repository = remember(context) { MediaLibraryRepository.getInstance(context) }
    val revision by repository.revision.collectAsState()
    val preferences = remember(context) { MediaAlbumPreferences(context) }
    var options by remember { mutableStateOf(preferences.load()) }
    val singerPreferences = remember(context) { MediaSingerPreferences(context) }
    var singerOptions by remember { mutableStateOf(singerPreferences.load()) }
    var tracks by remember { mutableStateOf(emptyList<MediaAlbumTrack>()) }
    var loading by remember { mutableStateOf(true) }
    val albums = remember(tracks) { buildMediaAlbums(tracks) }
    val scope = rememberCoroutineScope()
    val mutationMutex = remember { Mutex() }
    var playlists by remember { mutableStateOf(emptyList<MediaPlaylist>()) }
    var playlistRequest by remember { mutableStateOf<AlbumPlaylistRequest?>(null) }
    var infoPath by remember { mutableStateOf<String?>(null) }

    fun mutate(action: () -> Unit, onSuccess: () -> Unit) {
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

    LaunchedEffect(revision, refreshVersion, favoriteDirectories) {
        try {
            val loaded = withContext(Dispatchers.IO) {
                val roots = favoriteDirectories.map { it.absolutePath.trimEnd('/') + "/" }
                val indexed = repository.getIndexedTracks().filter { track ->
                    roots.any { track.path.startsWith(it) }
                }
                val jobContext = coroutineContext
                val history = repository.getPlaybackHistory(Int.MAX_VALUE).associateBy { it.path }
                val catalog = AlbumCatalogLoader.load(indexed) { !jobContext.isActive }.map { item ->
                    val played = history[item.track.path]
                    item.copy(lastPlayedAtMs = played?.playedAtMs ?: 0L, playCount = played?.playCount ?: 0)
                }
                ensureActive()
                catalog to repository.getPlaylists().filterNot { it.isSystem }
            }
            tracks = loaded.first
            playlists = loaded.second
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showLibraryError(context, error)
        } finally {
            loading = false
        }
    }

    fun playAlbum(album: MediaAlbum, clickedPath: String?, shuffle: Boolean = false) {
        scope.launch {
            try {
                val paths = album.tracks.map { it.track.path }
                val clickedIndex = clickedPath?.let(paths::indexOf) ?: 0
                val selection = withContext(Dispatchers.IO) {
                    LibraryPlaybackSelection.resolve(paths, clickedIndex, clickedPath == null) { File(it).isFile }
                }
                if (selection == null) {
                    Toast.makeText(context, R.string.media_lib_file_missing, Toast.LENGTH_SHORT).show()
                } else {
                    context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                        putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, ArrayList(selection.paths))
                        putExtra(PlayerActivity.EXTRA_START_INDEX,
                            if (shuffle) selection.paths.indices.random() else selection.startIndex)
                        if (shuffle) putExtra(PlayerActivity.EXTRA_PLAY_MODE, PlayerPlayMode.SHUFFLE.name)
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showLibraryError(context, error)
            }
        }
    }

    MediaAlbumPageContent(
        albums = albums,
        options = options,
        loading = loading,
        isActive = isActive,
        onOptionsChanged = { options = it; preferences.save(it) },
        singerOptions = singerOptions,
        onSingerOptionsChanged = { singerOptions = it; singerPreferences.save(it) },
        onPlay = { album, path -> playAlbum(album, path) },
        onShuffle = { album -> playAlbum(album, null, shuffle = true) },
        onFavoriteSelection = { selected, onComplete ->
            val unique = selected.distinctBy { it.path }
            mutate({
                unique.forEach { track ->
                    if (!repository.isFavorite(track.path)) repository.toggleFavorite(track.path, track.durationMs)
                }
            }) {
                Toast.makeText(context, context.getString(R.string.media_lib_files_favorited, unique.size), Toast.LENGTH_SHORT).show()
                onComplete()
            }
        },
        onAddSelectionToPlaylist = { selected, onComplete ->
            playlistRequest = AlbumPlaylistRequest(selected.distinctBy { it.path }, onComplete)
        },
        onShowInfo = { infoPath = it }
    )

    playlistRequest?.let { request ->
        LibraryPlaylistAssignDialog(
            playlists = playlists,
            onDismiss = { playlistRequest = null },
            onSelectPlaylist = { playlist ->
                var added = 0
                mutate({
                    request.tracks.forEach { track ->
                        if (repository.addTrackToPlaylist(playlist.id, track.path, track.durationMs)) added++
                    }
                }) {
                    Toast.makeText(context,
                        if (added > 0) context.getString(R.string.media_lib_added_tracks_to_playlist, added, playlist.name)
                        else context.getString(R.string.player_playlist_picker_already_in), Toast.LENGTH_SHORT).show()
                    playlistRequest = null
                    request.onComplete()
                }
            },
            onCreatePlaylist = { name ->
                mutate({
                    val id = checkNotNull(repository.createPlaylist(name))
                    request.tracks.forEach { repository.addTrackToPlaylist(id, it.path, it.durationMs) }
                }) {
                    Toast.makeText(context, context.getString(R.string.media_lib_added_tracks_to_playlist, request.tracks.size, name), Toast.LENGTH_SHORT).show()
                    playlistRequest = null
                    request.onComplete()
                }
            }
        )
    }
    infoPath?.let { path -> MediaFileInfoDialog(path, onDismiss = { infoPath = null }) }
}

/** State and navigation shared by the real catalog and focused Compose regressions. */
@Composable
internal fun MediaAlbumPageContent(
    albums: List<MediaAlbum>,
    options: MediaAlbumViewOptions,
    loading: Boolean = false,
    isActive: Boolean = true,
    onOptionsChanged: (MediaAlbumViewOptions) -> Unit,
    onPlay: (MediaAlbum, String?) -> Unit,
    onShuffle: (MediaAlbum) -> Unit = { onPlay(it, null) },
    onFavoriteSelection: (List<MediaLibraryTrack>, () -> Unit) -> Unit = { _, done -> done() },
    onAddSelectionToPlaylist: (List<MediaLibraryTrack>, () -> Unit) -> Unit = { _, done -> done() },
    onShowInfo: (String) -> Unit = {},
    singerOptions: MediaSingerViewOptions = MediaSingerViewOptions(),
    onSingerOptionsChanged: (MediaSingerViewOptions) -> Unit = {}
) {
    var category by rememberSaveable { mutableStateOf(MediaCategory.ALBUMS) }
    var query by rememberSaveable { mutableStateOf("") }
    var singerQuery by rememberSaveable { mutableStateOf("") }
    var openedAlbumKey by rememberSaveable { mutableStateOf<String?>(null) }
    var openedSingerKey by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    val openedAlbum = albums.firstOrNull { it.key == openedAlbumKey }
    val singers = remember(albums) { buildMediaSingers(albums.flatMap { it.tracks }) }
    val openedSinger = singers.firstOrNull { it.key == openedSingerKey }
    val visibleSingers = remember(singers, singerQuery, singerOptions) {
        filterAndSortMediaSingers(singers, if (singerOptions.quickSearch) singerQuery else "",
            singerOptions.sortField, singerOptions.ascending)
    }
    val visibleAlbums = remember(albums, query, options.sortField, options.ascending, options.quickSearch) {
        filterAndSortMediaAlbums(albums, if (options.quickSearch) query else "", options.sortField, options.ascending)
    }

    BackHandler(isActive && (settingsOpen || openedAlbumKey != null || openedSingerKey != null)) {
        when {
            settingsOpen -> settingsOpen = false
            openedSingerKey != null -> openedSingerKey = null
            else -> openedAlbumKey = null
        }
    }
    Column(Modifier.fillMaxSize().background(settingsPageBackground()).testTag("media-categories-page")) {
        when {
            settingsOpen && category == MediaCategory.SINGERS -> MediaSingerSettings(
                options = singerOptions,
                onChange = onSingerOptionsChanged,
                onBack = { settingsOpen = false }
            )
            settingsOpen -> MediaAlbumSettings(
                options = options,
                onChange = onOptionsChanged,
                onBack = { settingsOpen = false }
            )
            openedSinger != null -> MediaSingerDetailContent(
                singer = openedSinger,
                options = singerOptions,
                onOptionsChanged = onSingerOptionsChanged,
                isActive = isActive,
                onBack = { openedSingerKey = null },
                onPlay = { displayed, path -> onPlay(MediaAlbum(openedSinger.name, displayed), path) },
                onShuffle = { displayed -> onShuffle(MediaAlbum(openedSinger.name, displayed)) },
                onFavoriteSelection = onFavoriteSelection,
                onAddSelectionToPlaylist = onAddSelectionToPlaylist,
                onShowInfo = onShowInfo
            )
            openedAlbum != null -> MediaAlbumDetailContent(
                album = openedAlbum,
                groupTracks = options.groupTracks,
                sortField = options.sortField,
                ascending = options.ascending,
                trackSort = options.trackSort,
                onTrackSortChanged = { onOptionsChanged(options.copy(trackSort = it)) },
                isActive = isActive,
                onBack = { openedAlbumKey = null },
                onPlay = { displayed, path -> onPlay(openedAlbum.copy(tracks = displayed), path) },
                onShuffle = { displayed -> onShuffle(openedAlbum.copy(tracks = displayed)) },
                onFavoriteSelection = onFavoriteSelection,
                onAddSelectionToPlaylist = onAddSelectionToPlaylist,
                onShowInfo = onShowInfo
            )
            else -> {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().testTag("media-category-tabs"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(MediaCategory.entries) { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            label = { Text(stringResource(item.label)) },
                            modifier = Modifier.testTag("media-category:${item.name}")
                        )
                    }
                }
                if (category == MediaCategory.ALBUMS) {
                    if (loading && albums.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        }
                    } else {
                        MediaAlbumBrowseContent(
                            albums = visibleAlbums,
                            options = options,
                            query = query,
                            onQueryChanged = { query = it },
                            onSort = { sortOpen = true },
                            onSettings = { settingsOpen = true },
                            onOpenAlbum = { openedAlbumKey = it.key },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else if (category == MediaCategory.SINGERS) {
                    if (loading && singers.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    } else {
                        MediaSingerBrowseContent(
                            singers = visibleSingers,
                            options = singerOptions,
                            query = singerQuery,
                            onQueryChanged = { singerQuery = it },
                            onSort = { sortOpen = true },
                            onSettings = { settingsOpen = true },
                            onOpenSinger = { openedSingerKey = it.key },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f).fillMaxWidth().testTag("media-category-empty"))
                }
            }
        }
    }
    if (sortOpen && category == MediaCategory.SINGERS) {
        MediaSingerSortDialog(
            sortField = singerOptions.sortField,
            ascending = singerOptions.ascending,
            onChange = { field, ascending ->
                onSingerOptionsChanged(singerOptions.copy(sortField = field, ascending = ascending))
            },
            onDismiss = { sortOpen = false }
        )
    } else if (sortOpen) {
        MediaAlbumSortDialog(
            sortField = options.sortField,
            ascending = options.ascending,
            onChange = { field, ascending ->
                onOptionsChanged(options.copy(sortField = field, ascending = ascending))
            },
            onDismiss = { sortOpen = false }
        )
    }
}

