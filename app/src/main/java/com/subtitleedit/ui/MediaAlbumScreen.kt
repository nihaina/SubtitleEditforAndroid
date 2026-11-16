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
import com.subtitleedit.player.LocalPlayerLauncher
import com.subtitleedit.player.PlayerQueueSource
import com.subtitleedit.player.PlayerQueueSourceKind
import com.subtitleedit.player.putPlayerQueueSource
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.util.SettingsBackupManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    GENRES(R.string.media_category_genres)
}

private data class AlbumPlaylistRequest(val tracks: List<MediaLibraryTrack>, val onComplete: () -> Unit)

@Composable
internal fun MediaAlbumScreen(
    refreshVersion: Int,
    favoriteDirectories: List<File>,
    isActive: Boolean,
    pageState: MediaAlbumPageState = remember { MediaAlbumPageState() },
    catalogLoader: MediaAlbumCatalogLoader? = null
) {
    val context = LocalContext.current
    val playerLauncher = LocalPlayerLauncher.current
    val repository = remember(context) { MediaLibraryRepository.getInstance(context) }
    val revision by repository.revision.collectAsState()
    val catalogRevision by repository.catalogRevision.collectAsState()
    val preferences = remember(context) { MediaAlbumPreferences(context) }
    var options by remember { mutableStateOf(preferences.load()) }
    val creditPreferences = remember(context) {
        MediaCreditRole.entries.associateWith { MediaSingerPreferences(context, it) }
    }
    var creditOptions by remember(creditPreferences) {
        mutableStateOf(creditPreferences.mapValues { it.value.load() })
    }
    val restoredSettingsRevision by SettingsBackupManager.audioModeSettingsRevision.collectAsState()
    LaunchedEffect(restoredSettingsRevision) {
        options = preferences.load()
        creditOptions = creditPreferences.mapValues { it.value.load() }
    }
    val roots = remember(favoriteDirectories) {
        favoriteDirectories.map { it.absolutePath.trimEnd('/') + "/" }.distinct().sorted()
    }
    val catalogKey = MediaAlbumCatalogKey(catalogRevision, roots)
    val catalog = pageState.catalog
    val history = pageState.library?.history.orEmpty()
    val prepared = pageState.prepared?.takeIf { it.catalog.key.directories == roots }
    val revealRequest = pageState.revealRequest
    val albums = remember(prepared?.albums, pageState.revealTrack) {
        mediaAlbumsWithRevealTrack(prepared?.albums.orEmpty(), pageState.revealTrack)
    }
    var refreshing by remember { mutableStateOf(prepared == null) }
    val loading = prepared == null && (refreshing || catalog?.key?.directories == roots)
    val scope = rememberCoroutineScope()
    val mutationMutex = remember { Mutex() }
    val playlists = pageState.library?.playlists.orEmpty()
    var playlistRequest by remember { mutableStateOf<AlbumPlaylistRequest?>(null) }
    var infoPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(revealRequest?.token) {
        val request = revealRequest ?: return@LaunchedEffect
        try {
            val target = withContext(Dispatchers.IO) {
                val file = File(request.trackPath)
                if (!file.isFile || !file.canRead()) null else loadMediaCategoryRevealTrack(request.trackPath)
            }
            if (pageState.revealRequest?.token != request.token) return@LaunchedEffect
            if (target == null) {
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(context, R.string.media_lib_file_missing, Toast.LENGTH_SHORT).show()
                }
                pageState.revealRequest = null
            } else {
                pageState.revealTrack = target
                pageState.resolvedRevealToken = request.token
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (pageState.revealRequest?.token == request.token) {
                pageState.revealRequest = null
                withContext(Dispatchers.Main.immediate) { showLibraryError(context, error) }
            }
        }
    }

    fun updateCreditOptions(role: MediaCreditRole, next: MediaSingerViewOptions) {
        creditOptions = creditOptions + (role to next)
        creditPreferences.getValue(role).save(next)
    }

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

    LaunchedEffect(revision, refreshVersion) {
        val previous = pageState.library
        if (previous?.revision == revision && previous.refreshVersion == refreshVersion) return@LaunchedEffect
        try {
            val loaded = withContext(Dispatchers.IO) {
                repository.getPlaybackHistory(Int.MAX_VALUE).associateBy { it.path } to
                    repository.getPlaylists()
            }
            pageState.library = MediaAlbumLibrarySnapshot(revision, refreshVersion, loaded.first, loaded.second)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showLibraryError(context, error)
        }
    }

    LaunchedEffect(catalogKey) {
        if (pageState.catalog?.key == catalogKey) return@LaunchedEffect
        // Classification is a persisted index. File observers reconcile source changes
        // independently, so resuming or remounting this page never reparses media.
        refreshing = true
        try {
            if (catalogLoader != null) {
                val tracks = withContext(Dispatchers.IO) { catalogLoader(roots) }
                pageState.catalog = MediaAlbumCatalogSnapshot(catalogKey, tracks)
            } else {
                val (indexed, tracks) = withContext(Dispatchers.IO) {
                    val indexed = repository.getIndexedTracks().filter { track ->
                        roots.any { track.path.startsWith(it) }
                    }
                    val jobContext = coroutineContext
                    indexed to repository.getPersistedAlbumTracks(indexed) { !jobContext.isActive }
                }
                pageState.catalog = MediaAlbumCatalogSnapshot(catalogKey, tracks, indexed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showLibraryError(context, error)
        } finally {
            if (coroutineContext.isActive) refreshing = false
        }
    }

    LaunchedEffect(catalog, history) {
        val source = catalog ?: return@LaunchedEffect
        val previous = pageState.prepared
        if (previous?.catalog?.tracks == source.tracks && previous.history == history) {
            // A refreshed key with identical rows can reuse the already grouped albums too.
            pageState.prepared = previous.copy(catalog = source, history = history)
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.Default) {
            buildMediaAlbums(source.tracks.map { item ->
                val played = history[item.track.path]
                item.copy(lastPlayedAtMs = played?.playedAtMs ?: 0L, playCount = played?.playCount ?: 0)
            })
        }
        pageState.prepared = MediaAlbumPreparedSnapshot(source, history, loaded)
    }

    fun playAlbum(album: MediaAlbum, clickedPath: String?, shuffle: Boolean = false,
        queueSource: PlayerQueueSource = PlayerQueueSource(PlayerQueueSourceKind.ALBUM, album.key)) {
        val source = queueSource.copy(roots = roots)
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
                    val startIndex = if (shuffle) selection.paths.indices.random() else selection.startIndex
                    val initialMode = if (shuffle) PlayerPlayMode.SHUFFLE else null
                    if (playerLauncher != null) {
                        playerLauncher(selection.paths, startIndex, initialMode, source)
                    } else {
                        context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, ArrayList(selection.paths))
                            putExtra(PlayerActivity.EXTRA_START_INDEX, startIndex)
                            initialMode?.let { putExtra(PlayerActivity.EXTRA_PLAY_MODE, it.name) }
                            putPlayerQueueSource(source)
                        })
                    }
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
        metadataRefreshKey = catalogKey,
        options = options,
        loading = loading,
        isActive = isActive,
        revealRequest = revealRequest?.takeIf { pageState.resolvedRevealToken == it.token },
        onRevealFinished = { token ->
            if (pageState.revealRequest?.token == token) pageState.revealRequest = null
        },
        onOptionsChanged = { options = it; preferences.save(it) },
        singerOptions = creditOptions.getValue(MediaCreditRole.SINGER),
        onSingerOptionsChanged = { updateCreditOptions(MediaCreditRole.SINGER, it) },
        artistOptions = creditOptions.getValue(MediaCreditRole.ARTIST),
        onArtistOptionsChanged = { updateCreditOptions(MediaCreditRole.ARTIST, it) },
        composerOptions = creditOptions.getValue(MediaCreditRole.COMPOSER),
        onComposerOptionsChanged = { updateCreditOptions(MediaCreditRole.COMPOSER, it) },
        genreOptions = creditOptions.getValue(MediaCreditRole.GENRE),
        onGenreOptionsChanged = { updateCreditOptions(MediaCreditRole.GENRE, it) },
        onPlay = { album, path -> playAlbum(album, path) },
        onShuffle = { album -> playAlbum(album, null, shuffle = true) },
        onPlayFromSource = { album, path, source -> playAlbum(album, path, queueSource = source) },
        onShuffleFromSource = { album, source -> playAlbum(album, null, shuffle = true, queueSource = source) },
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
    onSingerOptionsChanged: (MediaSingerViewOptions) -> Unit = {},
    artistOptions: MediaSingerViewOptions = MediaSingerViewOptions(),
    onArtistOptionsChanged: (MediaSingerViewOptions) -> Unit = {},
    composerOptions: MediaSingerViewOptions = MediaSingerViewOptions(),
    onComposerOptionsChanged: (MediaSingerViewOptions) -> Unit = {},
    genreOptions: MediaSingerViewOptions = MediaSingerViewOptions(),
    onGenreOptionsChanged: (MediaSingerViewOptions) -> Unit = {},
    metadataRefreshKey: Any? = null,
    onPlayFromSource: ((MediaAlbum, String?, PlayerQueueSource) -> Unit)? = null,
    onShuffleFromSource: ((MediaAlbum, PlayerQueueSource) -> Unit)? = null,
    revealRequest: MediaCategoryRevealRequest? = null,
    onRevealFinished: (Long) -> Unit = {}
) {
    val globalSelection = LocalMediaSelection.current
    var category by rememberSaveable { mutableStateOf(MediaCategory.ALBUMS) }
    var query by rememberSaveable { mutableStateOf("") }
    var singerQuery by rememberSaveable { mutableStateOf("") }
    var artistQuery by rememberSaveable { mutableStateOf("") }
    var composerQuery by rememberSaveable { mutableStateOf("") }
    var genreQuery by rememberSaveable { mutableStateOf("") }
    var openedAlbumKey by rememberSaveable { mutableStateOf<String?>(null) }
    var openedCreditKey by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var handledRevealToken by rememberSaveable { mutableStateOf<Long?>(null) }

    fun cancelReveal() {
        revealRequest?.let {
            handledRevealToken = it.token
            onRevealFinished(it.token)
        }
    }

    LaunchedEffect(revealRequest?.token, albums, isActive) {
        val request = revealRequest ?: return@LaunchedEffect
        if (!isActive || handledRevealToken == request.token) return@LaunchedEffect
        category = request.category
        settingsOpen = false
        sortOpen = false
        when (request.category) {
            MediaCategory.ALBUMS -> query = ""
            MediaCategory.SINGERS -> singerQuery = ""
            MediaCategory.ARTISTS -> artistQuery = ""
            MediaCategory.COMPOSERS -> composerQuery = ""
            MediaCategory.GENRES -> genreQuery = ""
        }
        val album = albums.firstOrNull { item -> item.tracks.any { it.track.path == request.trackPath } }
            ?: return@LaunchedEffect
        val role = request.category.creditRole
        if (role == null) {
            openedAlbumKey = album.key
            openedCreditKey = null
        } else {
            val credit = buildMediaCredits(albums.flatMap { it.tracks }, role)
                .firstOrNull { item -> item.tracks.any { it.track.path == request.trackPath } }
                ?: return@LaunchedEffect
            openedAlbumKey = null
            openedCreditKey = credit.key
        }
        handledRevealToken = request.token
    }
    val openedAlbum = albums.firstOrNull { it.key == openedAlbumKey }
    val creditRole = category.creditRole
    val activeCreditOptions = when (creditRole) {
        MediaCreditRole.ARTIST -> artistOptions
        MediaCreditRole.COMPOSER -> composerOptions
        MediaCreditRole.GENRE -> genreOptions
        else -> singerOptions
    }
    val changeCreditOptions = when (creditRole) {
        MediaCreditRole.ARTIST -> onArtistOptionsChanged
        MediaCreditRole.COMPOSER -> onComposerOptionsChanged
        MediaCreditRole.GENRE -> onGenreOptionsChanged
        else -> onSingerOptionsChanged
    }
    val creditQuery = when (creditRole) {
        MediaCreditRole.ARTIST -> artistQuery
        MediaCreditRole.COMPOSER -> composerQuery
        MediaCreditRole.GENRE -> genreQuery
        else -> singerQuery
    }
    val credits = remember(albums, creditRole) {
        creditRole?.let { buildMediaCredits(albums.flatMap { album -> album.tracks }, it) }.orEmpty()
    }
    val openedCredit = credits.firstOrNull { it.key == openedCreditKey }
    val showingTracks = !settingsOpen &&
        ((openedCredit != null && creditRole != null) || openedAlbum != null)
    LaunchedEffect(isActive, showingTracks, globalSelection) {
        if (isActive && !showingTracks) globalSelection?.updateVisiblePaths(emptyList())
    }
    val visibleCredits = remember(credits, creditQuery, activeCreditOptions) {
        filterAndSortMediaSingers(credits, if (activeCreditOptions.quickSearch) creditQuery else "",
            activeCreditOptions.sortField, activeCreditOptions.ascending)
    }
    val visibleAlbums = remember(albums, query, options.sortField, options.ascending, options.quickSearch) {
        filterAndSortMediaAlbums(albums, if (options.quickSearch) query else "", options.sortField, options.ascending)
    }
    LaunchedEffect(credits, openedCreditKey, loading) {
        if (!loading && revealRequest == null && openedCreditKey != null && openedCredit == null) openedCreditKey = null
    }

    BackHandler(isActive && (settingsOpen || openedAlbumKey != null || openedCreditKey != null)) {
        cancelReveal()
        when {
            settingsOpen -> settingsOpen = false
            openedCreditKey != null -> openedCreditKey = null
            else -> openedAlbumKey = null
        }
    }
    Column(Modifier.fillMaxSize().background(settingsPageBackground()).testTag("media-categories-page")) {
        when {
            settingsOpen && creditRole != null -> MediaSingerSettings(
                options = activeCreditOptions,
                onChange = changeCreditOptions,
                onBack = { settingsOpen = false },
                role = creditRole
            )
            settingsOpen -> MediaAlbumSettings(
                options = options,
                onChange = onOptionsChanged,
                onBack = { settingsOpen = false }
            )
            openedCredit != null && creditRole != null -> MediaSingerDetailContent(
                metadataRefreshKey = metadataRefreshKey,
                singer = openedCredit,
                options = activeCreditOptions,
                onOptionsChanged = changeCreditOptions,
                isActive = isActive,
                onBack = { cancelReveal(); openedCreditKey = null },
                revealRequest = revealRequest?.takeIf { handledRevealToken == it.token && category == it.category },
                onRevealFinished = onRevealFinished,
                onPlay = { displayed, path ->
                    val album = MediaAlbum(openedCredit.name, displayed)
                    val source = PlayerQueueSource(creditRole.queueSourceKind, openedCredit.key)
                    onPlayFromSource?.invoke(album, path, source) ?: onPlay(album, path)
                },
                onShuffle = { displayed ->
                    val album = MediaAlbum(openedCredit.name, displayed)
                    val source = PlayerQueueSource(creditRole.queueSourceKind, openedCredit.key)
                    onShuffleFromSource?.invoke(album, source) ?: onShuffle(album)
                },
                onFavoriteSelection = onFavoriteSelection,
                onAddSelectionToPlaylist = onAddSelectionToPlaylist,
                onShowInfo = onShowInfo,
                role = creditRole
            )
            openedAlbum != null -> MediaAlbumDetailContent(
                metadataRefreshKey = metadataRefreshKey,
                album = openedAlbum,
                groupTracks = options.groupTracks,
                sortField = options.sortField,
                ascending = options.ascending,
                trackSort = options.trackSort,
                onTrackSortChanged = { onOptionsChanged(options.copy(trackSort = it)) },
                isActive = isActive,
                onBack = { cancelReveal(); openedAlbumKey = null },
                revealRequest = revealRequest?.takeIf { handledRevealToken == it.token && category == it.category },
                onRevealFinished = onRevealFinished,
                onPlay = { displayed, path ->
                    val album = openedAlbum.copy(tracks = displayed)
                    val source = PlayerQueueSource(PlayerQueueSourceKind.ALBUM, openedAlbum.key)
                    onPlayFromSource?.invoke(album, path, source) ?: onPlay(album, path)
                },
                onShuffle = { displayed ->
                    val album = openedAlbum.copy(tracks = displayed)
                    val source = PlayerQueueSource(PlayerQueueSourceKind.ALBUM, openedAlbum.key)
                    onShuffleFromSource?.invoke(album, source) ?: onShuffle(album)
                },
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
                            onClick = {
                                cancelReveal()
                                openedCreditKey = null
                                category = item
                            },
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
                            onOpenAlbum = { cancelReveal(); openedAlbumKey = it.key },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else if (creditRole != null) {
                    if (loading && credits.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    } else {
                        MediaSingerBrowseContent(
                            singers = visibleCredits,
                            options = activeCreditOptions,
                            query = creditQuery,
                            onQueryChanged = {
                                when (creditRole) {
                                    MediaCreditRole.SINGER -> singerQuery = it
                                    MediaCreditRole.ARTIST -> artistQuery = it
                                    MediaCreditRole.COMPOSER -> composerQuery = it
                                    MediaCreditRole.GENRE -> genreQuery = it
                                }
                            },
                            onSort = { sortOpen = true },
                            onSettings = { settingsOpen = true },
                            onOpenSinger = { cancelReveal(); openedCreditKey = it.key },
                            modifier = Modifier.weight(1f),
                            role = creditRole
                        )
                    }
                }
            }
        }
    }
    if (sortOpen && creditRole != null) {
        MediaSingerSortDialog(
            sortField = activeCreditOptions.sortField,
            ascending = activeCreditOptions.ascending,
            onChange = { field, ascending ->
                changeCreditOptions(activeCreditOptions.copy(sortField = field, ascending = ascending))
            },
            onDismiss = { sortOpen = false },
            role = creditRole
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

private val MediaCategory.creditRole: MediaCreditRole?
    get() = when (this) {
        MediaCategory.SINGERS -> MediaCreditRole.SINGER
        MediaCategory.ARTISTS -> MediaCreditRole.ARTIST
        MediaCategory.COMPOSERS -> MediaCreditRole.COMPOSER
        MediaCategory.GENRES -> MediaCreditRole.GENRE
        else -> null
    }

private val MediaCreditRole.queueSourceKind: PlayerQueueSourceKind
    get() = when (this) {
        MediaCreditRole.SINGER -> PlayerQueueSourceKind.SINGER
        MediaCreditRole.ARTIST -> PlayerQueueSourceKind.ARTIST
        MediaCreditRole.COMPOSER -> PlayerQueueSourceKind.COMPOSER
        MediaCreditRole.GENRE -> PlayerQueueSourceKind.GENRE
    }

