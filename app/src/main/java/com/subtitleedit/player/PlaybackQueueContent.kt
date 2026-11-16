package com.subtitleedit.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.ui.MediaLibrarySelectionAction
import com.subtitleedit.ui.MediaLibrarySelectionPanel
import com.subtitleedit.ui.updateMediaLibrarySelection
import com.subtitleedit.ui.LocalMediaSelection
import com.subtitleedit.ui.MediaSelectionPanel
import kotlinx.coroutines.delay

/** Both queue surfaces use the same rows, temporary editing and explicit selection mode. */
@Composable
internal fun PlaybackQueueContent(
    tracks: List<MediaLibraryTrack>,
    currentPath: String?,
    playing: Boolean = false,
    onPlay: (String) -> Unit,
    onMove: (String, String) -> Unit,
    onRemove: (Set<String>) -> Unit,
    onFavorite: (List<MediaLibraryTrack>) -> Unit,
    onAddToPlaylist: (List<MediaLibraryTrack>) -> Unit,
    onShowInfo: (MediaLibraryTrack) -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    queueVersion: Any = Unit,
    tagPrefix: String = "playback-queue",
    entryIds: List<String>? = null,
    currentEntryId: String? = null,
    onRemoveEntry: ((String) -> Unit)? = null,
    showSelectionPanel: Boolean = true,
    overlaySelectionScope: Boolean = false,
    header: @Composable (@Composable () -> Unit) -> Unit
) {
    val globalSelection = LocalMediaSelection.current
    var localSelectionMode by remember(queueVersion) { mutableStateOf(false) }
    var sorting by remember(queueVersion) { mutableStateOf(false) }
    val selectionMode = globalSelection?.active ?: localSelectionMode
    var selectedPaths by remember(queueVersion) { mutableStateOf<Set<String>>(emptySet()) }
    val paths = remember(tracks) { tracks.map { it.path } }
    val pathCounts = remember(paths) { paths.groupingBy { it }.eachCount() }
    val effectiveSelected = globalSelection?.selectedPaths?.toSet() ?: selectedPaths
    val selectedTracks = remember(tracks, effectiveSelected) { tracks.filter { it.path in effectiveSelected }.distinctBy { it.path } }
    val keys = remember(tracks, entryIds) {
        entryIds?.takeIf { it.size == tracks.size } ?: run {
            paths.mapIndexed { index, path -> if (pathCounts[path] == 1) path else "$index:$path" }
        }
    }
    val listState = rememberLazyListState()
    val moveNow by rememberUpdatedState(onMove)
    val drag = remember(listState) { PlaybackQueueDragState(listState) { from, to -> moveNow(from, to) } }
    SideEffect { drag.onQueueComposed(keys) }
    val density = LocalDensity.current
    val edgeSize = with(density) { 56.dp.toPx() }
    var panelHeight by remember { mutableStateOf(112.dp) }

    fun exitSelection() { if (globalSelection != null) globalSelection.cancel() else { selectedPaths = emptySet(); localSelectionMode = false } }
    fun toggle(path: String) {
        if (globalSelection != null) globalSelection.toggle(path)
        else selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }
    fun beginSelection(path: String) {
        sorting = false
        if (globalSelection != null) globalSelection.select(path)
        else { selectedPaths = selectedPaths + path; localSelectionMode = true }
    }
    fun select(action: MediaLibrarySelectionAction) {
        selectedPaths = updateMediaLibrarySelection(paths, selectedPaths, action)
    }

    BackHandler(enabled = isActive && selectionMode) { exitSelection() }
    BackHandler(enabled = isActive && sorting && !selectionMode) { sorting = false }
    LaunchedEffect(paths, isActive, globalSelection) {
        if (isActive && !overlaySelectionScope) globalSelection?.updateVisiblePaths(paths)
    }
    DisposableEffect(paths, isActive, globalSelection, overlaySelectionScope) {
        if (isActive && overlaySelectionScope) globalSelection?.setOverlayPaths(paths)
        onDispose { if (isActive && overlaySelectionScope) globalSelection?.setOverlayPaths(null) }
    }
    LaunchedEffect(paths, keys) {
        selectedPaths = selectedPaths.intersect(paths.toSet())
        if (drag.path?.let { it !in keys } == true) drag.stop()
    }
    LaunchedEffect(sorting, queueVersion, isActive) { drag.stop() }
    LaunchedEffect(selectionMode) { if (selectionMode) sorting = false }
    LaunchedEffect(drag.path) {
        while (drag.path != null) {
            drag.scrollAtEdge(edgeSize)
            delay(16L)
        }
    }

    Column(modifier) {
        header {
            IconButton(
                onClick = { sorting = !sorting },
                enabled = tracks.isNotEmpty(),
                modifier = Modifier.testTag("$tagPrefix-sort").semantics { selected = sorting }
            ) {
                Icon(painterResource(R.drawable.ic_queue_sort),
                    contentDescription = stringResource(R.string.player_queue_sort),
                    tint = if (sorting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (tracks.isEmpty()) {
                Text(stringResource(R.string.player_playlist_empty),
                    modifier = Modifier.align(Alignment.Center).testTag("$tagPrefix-empty"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("$tagPrefix-list")
                        .pointerInput(sorting, queueVersion) {
                            if (!sorting) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                // Sorting is immediate from the visible handle, leaving row swipes to scroll.
                                if (down.position.x < size.width - 52.dp.toPx()) return@awaitEachGesture
                                drag.start(down.position.y)
                                if (drag.path == null) return@awaitEachGesture
                                down.consume()
                                var previousY = down.position.y
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (event.changes.count { it.pressed } > 1) break
                                        change.consume()
                                        if (!change.pressed) break
                                        drag.drag(change.position.y - previousY)
                                        previousY = change.position.y
                                    }
                                } finally {
                                    drag.stop()
                                }
                            }
                        },
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp,
                        bottom = if (selectionMode) panelHeight else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(tracks.indices.toList(), key = { keys[it] }) { index ->
                        val track = tracks[index]
                        val key = keys[index]
                        val dragging = drag.path == key
                        val current = if (currentEntryId != null) currentEntryId == key else currentPath == track.path
                        val tagId = if (pathCounts.getValue(track.path) > 1) "${track.path}:$key" else track.path
                        PlaybackQueueRow(track, current, playing && current,
                            selectionMode, track.path in effectiveSelected, sorting,
                            onClick = { if (selectionMode) toggle(track.path) else onPlay(key) },
                            onLongClick = { beginSelection(track.path) },
                            onRemove = { onRemoveEntry?.invoke(key) ?: onRemove(setOf(track.path)) },
                            onToggle = { toggle(track.path) },
                            modifier = Modifier.testTag("$tagPrefix-row:$tagId")
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = drag.translationFor(key)
                                    shadowElevation = if (dragging) 6.dp.toPx() else 0f
                                },
                            tagPrefix = tagPrefix, tagId = tagId)
                    }
                }
            }
            if (selectionMode && showSelectionPanel && globalSelection != null) {
                MediaSelectionPanel(globalSelection,
                    modifier = Modifier.align(Alignment.BottomCenter).heightIn(max = maxHeight * 0.7f)
                        .onSizeChanged { panelHeight = with(density) { it.height.toDp() } }, isActive = isActive)
            } else if (selectionMode && showSelectionPanel) {
                MediaLibrarySelectionPanel(
                    selectedFiles = selectedTracks.map { it.file },
                    totalDurationMs = selectedTracks.sumOf { it.durationMs.coerceAtLeast(0) },
                    showDuration = selectedTracks.isNotEmpty() && selectedTracks.all { it.durationMs > 0 },
                    onRemove = ::toggle,
                    onFavorite = { if (selectedTracks.isNotEmpty()) { onFavorite(selectedTracks); exitSelection() } },
                    onAddToPlaylist = { if (selectedTracks.isNotEmpty()) { onAddToPlaylist(selectedTracks); exitSelection() } },
                    onShowInfo = { selectedTracks.singleOrNull()?.let(onShowInfo) },
                    onCancel = ::exitSelection,
                    onSelectAll = { select(MediaLibrarySelectionAction.SELECT_ALL) },
                    onSelectRange = { select(MediaLibrarySelectionAction.SELECT_RANGE) },
                    onInvertSelection = { select(MediaLibrarySelectionAction.INVERT) },
                    onRemoveSelection = { if (selectedPaths.isNotEmpty()) { onRemove(selectedPaths); exitSelection() } },
                    modifier = Modifier.align(Alignment.BottomCenter).heightIn(max = maxHeight * 0.7f)
                        .onSizeChanged { panelHeight = with(density) { it.height.toDp() } }
                )
            }
        }
    }
}

@Composable
private fun PlaybackQueueRow(
    track: MediaLibraryTrack,
    current: Boolean,
    playing: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    sorting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier,
    tagPrefix: String,
    tagId: String
) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(when {
                checked -> MaterialTheme.colorScheme.primaryContainer
                current -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> Color.Transparent
            }).combinedClickable(onClick = onClick, onLongClick = onLongClick).semantics { if (selectionMode) selected = checked }
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val artworkTrack = remember(track.path, track.displayTitle, track.isVideo) {
            PlayerTrack(track.path, track.displayTitle, track.isVideo)
        }
        PlayerPlaylistArtwork(artworkTrack, current)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(track.displayTitle, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = track.artist.ifBlank { track.file.parentFile?.name.orEmpty() }
            if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (playing) Icon(painterResource(R.drawable.ic_video_play), contentDescription = null,
            modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        if (sorting) {
            val sortLabel = stringResource(R.string.player_queue_sort)
            Box(Modifier.size(48.dp).testTag("$tagPrefix-drag:$tagId")
                .semantics { contentDescription = sortLabel }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_queue_drag), contentDescription = null,
                    modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (selectionMode) {
            if (checked) {
                val selectionLabel = stringResource(R.string.player_queue_deselect_track, track.displayTitle)
                IconButton(onClick = onToggle,
                    modifier = Modifier.testTag("$tagPrefix-check:$tagId").semantics {
                        selected = true
                        contentDescription = selectionLabel
                    }) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null,
                            modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            } else Spacer(Modifier.size(48.dp))
        } else {
            IconButton(onClick = onRemove, modifier = Modifier.testTag("$tagPrefix-remove:$tagId")) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.player_queue_remove),
                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
