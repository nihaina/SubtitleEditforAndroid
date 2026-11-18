package com.subtitleedit.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.feature.ui.BrowserFileRow
import com.subtitleedit.feature.ui.DirectoryImagePreview
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaFolderCatalog
import com.subtitleedit.library.MediaFolderOrder
import com.subtitleedit.library.MediaFolderListing
import com.subtitleedit.library.MediaLibraryFileObserver
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.buildMediaFolderCatalog
import com.subtitleedit.library.loadMediaFolderListing
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.AppLazyListScrollbar
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.SharedFileCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun MediaFolderCategoryContent(
    roots: List<String>,
    tracks: List<MediaAlbumTrack>?,
    isActive: Boolean,
    refreshVersion: Int,
    onPlay: (List<MediaLibraryTrack>, String?, Boolean) -> Unit,
    onOpenSubtitle: (File) -> Unit,
    tabs: @Composable () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember(context) { MediaFolderPreferences(context) }
    var options by remember(preferences) { mutableStateOf(preferences.loadViewOptions()) }
    var order by remember(preferences) { mutableStateOf(preferences.loadOrder()) }
    var query by rememberSaveable { mutableStateOf("") }
    var folderStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    val listStates = rememberSaveableStateHolder()
    val directoryRevision by MediaLibraryFileObserver.directoryUpdates.revision.collectRetainedPageState(isActive)
    val refreshKey = directoryRevision to refreshVersion
    val catalog by key(roots) {
        produceState<MediaFolderCatalog?>(null, tracks, isActive) {
            if (isActive && tracks != null) value = withContext(Dispatchers.Default) {
                buildMediaFolderCatalog(tracks, roots) { ensureActive() }
            }
        }
    }
    val folderCatalog = catalog
    val openedPath = folderStack.lastOrNull()?.takeIf { folderStack.firstOrNull() in folderCatalog?.categoryRoots.orEmpty() }
    LaunchedEffect(folderCatalog) {
        if (folderCatalog != null && openedPath == null) folderStack = emptyList()
    }
    val selection = LocalMediaSelection.current
    LaunchedEffect(isActive, openedPath, selection) {
        if (isActive && openedPath == null) selection?.updateVisiblePaths(emptyList())
    }
    fun navigateUp() { folderStack = folderStack.dropLast(1) }
    BackHandler(enabled = isActive && (settingsOpen || openedPath != null)) {
        if (settingsOpen) settingsOpen = false else navigateUp()
    }

    when {
        settingsOpen -> MediaCollectionViewSettings(
            tagPrefix = "folder",
            titleRes = R.string.media_category_folders,
            viewModeRes = R.string.media_folder_view_mode,
            moreInfoRes = R.string.media_folder_more_info,
            options = options,
            onChange = { updated ->
                options = updated
                preferences.saveViewOptions(updated)
            },
            onBack = { settingsOpen = false }
        )
        openedPath == null || folderCatalog == null -> {
            tabs()
            val activeQuery = query.takeIf { options.quickSearch }
            MediaCollectionBrowseHeader(
                tagPrefix = "folder", query = activeQuery, searchHint = R.string.media_folder_search,
                onQueryChanged = { query = it }, sortDescription = R.string.media_folder_sort,
                onSort = { sortOpen = true }, onSettings = { settingsOpen = true },
                settingsDescription = R.string.media_folder_settings
            )
            if (folderCatalog == null) FolderLoading()
            else listStates.SaveableStateProvider("overview") {
                MediaFolderOverview(folderCatalog, order, refreshKey, isActive, options, activeQuery) {
                    folderStack = listOf(it)
                }
            }
        }
        else -> {
            val artworkSize = with(LocalDensity.current) { 40.dp.roundToPx() }
            val headerArtwork = rememberMediaFolderHeaderArtwork(
                folderStack, order, refreshKey, isActive, artworkSize
            )
            listStates.SaveableStateProvider(openedPath) {
                MediaFolderDetail(
                    folderStack = folderStack, catalog = folderCatalog, order = order, refreshKey = refreshKey,
                    isActive = isActive, headerArtwork = headerArtwork, artworkSize = artworkSize,
                    onNavigateUp = ::navigateUp, onSort = { sortOpen = true },
                    onOpenFolder = { folderStack = folderStack + it }, onOpenSubtitle = onOpenSubtitle, onPlay = onPlay
                )
            }
        }
    }
    if (sortOpen) MediaFolderSortDialog(order, onDismiss = { sortOpen = false }) { updated ->
        order = updated
        preferences.saveOrder(updated)
        sortOpen = false
    }
}

@Composable
private fun MediaFolderOverview(
    catalog: MediaFolderCatalog,
    order: MediaFolderOrder,
    refreshKey: Any,
    isActive: Boolean,
    options: MediaCollectionViewOptions,
    query: String?,
    onOpen: (String) -> Unit
) {
    val folders by produceState<List<BrowserFileMetadata>?>(null, catalog, order, refreshKey, isActive) {
        if (isActive) value = withContext(Dispatchers.IO) {
            order.sort(catalog.categoryRoots.mapNotNull { path ->
                ensureActive()
                SharedFileCache.attributes.get(File(path))?.takeIf { it.isDirectory && !it.restricted }
            })
        }
    }
    val loaded = folders
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val search = query.orEmpty().trim()
    val rows = remember(loaded, search) {
        loaded.orEmpty().filter { it.file.name.contains(search, ignoreCase = true) }
    }
    when {
        loaded == null -> FolderLoading()
        rows.isEmpty() -> FolderEmpty(if (search.isBlank()) R.string.media_folder_empty else R.string.album_browse_no_results)
        options.viewMode == AlbumViewMode.GRID -> MediaCollectionBrowseGrid(
            entries = rows, gridSize = options.gridSize, state = gridState, key = { it.path },
            tag = "folder-grid", modifier = Modifier.fillMaxSize()
        ) { row, artworkWidth ->
            val mediaCount = catalog.mediaCount(row.path)
            OutlinedCard(
                onClick = { onOpen(row.path) },
                modifier = Modifier.fillMaxWidth().testTag("folder-item:${row.path}"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                MediaFolderArtwork(row.file, mediaCount > 0, order, refreshKey, isActive, artworkWidth,
                    Modifier.fillMaxWidth().aspectRatio(1f))
                FolderLabels(row.file.name, mediaCount, options.showMoreInfo,
                    Modifier.fillMaxWidth().padding(8.dp))
            }
        }
        else -> FolderList("folder-list", listState) {
            items(rows, key = { it.path }) { row ->
                FolderOverviewRow(row.file, catalog.mediaCount(row.path), order, refreshKey, isActive,
                    showArtwork = options.viewMode == AlbumViewMode.LIST_ART, showMoreInfo = options.showMoreInfo
                ) { onOpen(row.path) }
            }
        }
    }
}

@Composable
private fun MediaFolderDetail(
    folderStack: List<String>,
    catalog: MediaFolderCatalog,
    order: MediaFolderOrder,
    refreshKey: Any,
    isActive: Boolean,
    headerArtwork: Bitmap?,
    artworkSize: Int,
    onNavigateUp: () -> Unit,
    onSort: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenSubtitle: (File) -> Unit,
    onPlay: (List<MediaLibraryTrack>, String?, Boolean) -> Unit
) {
    val folder = File(folderStack.last())
    val listing by produceState<MediaFolderListing?>(null, folderStack, catalog, order, refreshKey, isActive) {
        if (isActive) value = withContext(Dispatchers.IO) { loadMediaFolderListing(folderStack, catalog, order) }
    }
    val entries = listing?.entries.orEmpty()
    val tracks = listing?.tracks.orEmpty()
    val listState = rememberLazyListState()
    val trackPaths = remember(tracks) { tracks.map { it.path } }
    val playablePaths = remember(trackPaths) { trackPaths.toHashSet() }
    val images = remember(entries) { entries.filter { it.isRegularFile && FileTypePolicy.isImage(it.file) }.map { it.file } }
    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }
    val previewFile = images.firstOrNull { it.absolutePath == previewPath }
    var previewControls by rememberSaveable(previewFile != null) { mutableStateOf(true) }
    val selection = LocalMediaSelection.current
    LaunchedEffect(trackPaths, isActive, selection, previewFile != null) {
        if (isActive) selection?.refreshVisiblePaths(if (previewFile == null) trackPaths else emptyList())
    }
    LaunchedEffect(listing, previewPath) {
        if (listing != null && previewFile == null) previewPath = null
    }
    BackHandler(enabled = isActive && previewFile != null) { previewPath = null }
    if (previewFile != null) {
        DirectoryImagePreview(
            file = previewFile, files = images, isSelected = false, selectionTitle = null,
            controlsVisible = previewControls, quarterTurns = 0, onPageChanged = { previewPath = it.absolutePath },
            onNavigationClick = { previewPath = null }, onToggleSelection = {},
            onToggleControls = { previewControls = !previewControls }, modifier = Modifier.fillMaxSize()
        )
        return
    }

    val hasTracks = tracks.isNotEmpty()
    val duration = remember(tracks) { tracks.sumOf { it.durationMs.coerceAtLeast(0L) } }
    Column(Modifier.fillMaxSize().testTag("folder-detail")) {
        MediaTrackDetailTopBar(folder.name, stringResource(R.string.media_folder_up), "folder", onNavigateUp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MediaTrackDurationSummary(tracks.size, duration)
            Spacer(Modifier.weight(1f))
            MediaFolderArtworkTile(headerArtwork,
                Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).testTag("folder-detail-artwork"))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (listing == null) FolderLoading() else {
            FolderList("folder-tracks", listState) {
                item(key = "actions") {
                    MediaTrackPlaylistActions(
                        tagPrefix = "folder", playbackEnabled = hasTracks,
                        onPlayAll = { onPlay(tracks, null, false) },
                        onShuffle = { onPlay(tracks, null, true) },
                        onSort = onSort, onUp = onNavigateUp, sortDescription = R.string.media_folder_sort
                    )
                }
                if (entries.isEmpty()) item(key = "empty") { FolderEmpty(R.string.directory_empty) }
                items(entries, key = { it.path }) { row ->
                    val isTrack = row.path in playablePaths
                    val isSelecting = isTrack && selection?.active == true
                    val tag = when {
                        row.isDirectory -> "folder-item"
                        isTrack -> "folder-track"
                        else -> "folder-file"
                    }
                    val directoryArtwork = if (row.isDirectory && catalog.mediaCount(row.path) > 0) {
                        rememberMediaFolderArtwork(row.file, order, refreshKey, isActive, artworkSize)
                    } else null
                    BrowserFileRow(
                        modifier = Modifier.testTag("$tag:${row.path}"),
                        file = row.file, relativePathRoot = null, metadata = row, directoryPageStyle = true,
                        directoryArtwork = directoryArtwork,
                        isSelected = isTrack && selection?.contains(row.path) == true, isSelectionMode = isSelecting,
                        onClick = {
                            when {
                                row.isDirectory -> onOpenFolder(row.path)
                                isSelecting -> selection?.toggle(row.path)
                                isTrack -> onPlay(tracks, row.path, false)
                                FileTypePolicy.isImage(row.file) -> previewPath = row.path
                                else -> onOpenSubtitle(row.file)
                            }
                        },
                        onLongClick = { if (isTrack) selection?.select(row.path) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderList(tag: String, listState: LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag(tag), state = listState, contentPadding = PaddingValues(bottom = 8.dp),
            content = content)
        AppLazyListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun FolderOverviewRow(
    folder: File,
    mediaCount: Int,
    order: MediaFolderOrder,
    refreshKey: Any,
    isActive: Boolean,
    showArtwork: Boolean,
    showMoreInfo: Boolean,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        .testTag("folder-item:${folder.absolutePath}"), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showArtwork) {
                val coverSize = with(LocalDensity.current) { 46.dp.roundToPx() }
                MediaFolderArtwork(folder, mediaCount > 0, order, refreshKey, isActive, coverSize,
                    Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)))
            }
            FolderLabels(folder.name, mediaCount, showMoreInfo, Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_arrow_right), null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FolderLabels(name: String, mediaCount: Int, showMoreInfo: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showMoreInfo && mediaCount > 0) {
            Text(stringResource(R.string.media_lib_files_folder_count, mediaCount),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FolderLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(32.dp))
    }
}

@Composable
private fun FolderEmpty(message: Int) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MediaFolderSortDialog(order: MediaFolderOrder, onDismiss: () -> Unit, onConfirm: (MediaFolderOrder) -> Unit) {
    var draft by remember(order) { mutableStateOf(order) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_folder_sort)) },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.media_album_sort_hint), style = MaterialTheme.typography.bodySmall)
                for (field in FileSortField.entries) {
                    val selected = field == draft.field
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton) {
                        draft = if (selected) draft.copy(direction = when (draft.direction) {
                            FileSortDirection.ASCENDING -> FileSortDirection.DESCENDING
                            FileSortDirection.DESCENDING -> FileSortDirection.ASCENDING
                        }) else draft.copy(field = field)
                    }.testTag("folder-sort:${field.name}"), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected, onClick = null)
                        Text(stringResource(when (field) {
                            FileSortField.NAME -> R.string.directory_sort_name
                            FileSortField.TYPE -> R.string.directory_sort_type
                            FileSortField.SIZE -> R.string.directory_sort_size
                            FileSortField.DATE -> R.string.directory_sort_date
                        }), Modifier.weight(1f))
                        if (selected) Text(stringResource(if (draft.direction == FileSortDirection.ASCENDING)
                            R.string.media_album_sort_ascending else R.string.media_album_sort_descending))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, modifier = Modifier.testTag("folder-sort-confirm")) {
                Text(stringResource(R.string.media_album_sort_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
