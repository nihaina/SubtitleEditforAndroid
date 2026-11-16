package com.subtitleedit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.player.*

/** Saved queues retain occurrence identities while edits are being acknowledged by storage. */
private data class SavedPlaybackQueue(
    val entries: List<PlayerTrack>,
    val metadata: Map<String, MediaLibraryTrack>,
    val currentIndex: Int
) {
    val tracks: List<MediaLibraryTrack> get() = entries.map { metadata.getValue(it.queueEntryId) }
    val paths: List<String> get() = entries.map { it.path }

    fun edited(edit: PlayerQueueEdit) = copy(entries = edit.playlist, currentIndex = edit.currentIndex)
}

private fun savedPlaybackQueue(queue: Pair<List<MediaLibraryTrack>, Int>): SavedPlaybackQueue {
    val entries = queue.first.map { PlayerTrack(it.path, it.displayTitle, it.isVideo) }
    return SavedPlaybackQueue(entries, entries.zip(queue.first).associate { it.first.queueEntryId to it.second }, queue.second)
}

/** A saved queue is editable before playback starts; an active player always remains authoritative. */
@Composable
internal fun MediaLibraryQueueContent(
    savedQueue: Pair<List<MediaLibraryTrack>, Int>,
    onPlaySavedQueue: (List<MediaLibraryTrack>, Int, Boolean) -> Unit,
    isActive: Boolean = true,
    model: PlayerViewModel? = LocalPlayerQueueModel.current,
    repository: MediaLibraryRepository = MediaLibraryRepository.getInstance(LocalContext.current)
) {
    val live = model?.state?.collectAsState()?.value
    val ownsQueue = live != null && live.queueVersion > 0L
    var savedDraft by remember { mutableStateOf(savedPlaybackQueue(savedQueue)) }
    var pendingSavedWrite by remember { mutableStateOf(false) }
    val globalSelection = LocalMediaSelection.current
    LaunchedEffect(savedQueue, ownsQueue) {
        val matches = savedDraft.paths == savedQueue.first.map { it.path } &&
            (savedDraft.entries.isEmpty() || savedDraft.currentIndex == savedQueue.second)
        if (ownsQueue || matches) pendingSavedWrite = false
        else if (!pendingSavedWrite) savedDraft = savedPlaybackQueue(savedQueue)
    }
    val liveTracks = remember(live?.playlist, live?.currentTrack?.path, live?.durationMs) {
        live?.playlist?.map { track ->
            MediaLibraryTrack(track.path, track.name, isVideo = track.isVideo,
                durationMs = if (track.path == live.currentTrack?.path) live.durationMs else 0L)
        }.orEmpty()
    }
    val tracks = if (ownsQueue) liveTracks else savedDraft.tracks
    val entries = if (ownsQueue) live!!.playlist else savedDraft.entries
    val currentIndex = if (ownsQueue) live!!.currentIndex else savedDraft.currentIndex
    val actions = rememberPlaybackQueueLibraryActions(repository)

    fun editSaved(fromId: String? = null, toId: String? = null, removedIds: Set<String> = emptySet(), removedPaths: Set<String> = emptySet()) {
        if (model != null && model.state.value.queueVersion > 0L) {
            if (fromId != null && toId != null) model.moveQueueEntry(fromId, toId)
            else if (removedIds.isNotEmpty()) model.removeQueueEntries(removedIds)
            else model.removeQueueTracks(removedPaths)
            return
        }
        val before = savedDraft
        val source = before.entries
        val edit = if (fromId != null && toId != null)
            PlayerQueueEdits.moveEntries(source, before.currentIndex, source.indices.toList(), fromId, toId)
        else if (removedIds.isNotEmpty()) PlayerQueueEdits.removeEntries(source, before.currentIndex, source.indices.toList(), removedIds)
        else PlayerQueueEdits.remove(source, before.currentIndex, source.indices.toList(), removedPaths)
        if (edit == null) return
        val changed = before.edited(edit)
        savedDraft = changed
        pendingSavedWrite = true
        PlayerLibraryWriter.submit {
            if (model == null || model.state.value.queueVersion == 0L)
                repository.saveCurrentQueue(changed.paths, changed.currentIndex, allowDuplicates = true)
        }
    }

    fun playSaved(index: Int, all: Boolean) {
        if (model != null) {
            model.openQueue(savedDraft.entries, index, allowDuplicates = true)
        } else onPlaySavedQueue(tracks, index, all)
    }

    PlaybackQueueContent(
        tracks = tracks,
        entryIds = entries.map { it.queueEntryId },
        currentEntryId = entries.getOrNull(currentIndex)?.queueEntryId,
        currentPath = tracks.getOrNull(currentIndex)?.path,
        playing = ownsQueue && live!!.playing,
        onPlay = { id ->
            val active = model?.state?.value
            if (active != null && active.queueVersion > 0L) {
                model.playQueueEntry(id)
                model.expand()
            } else playSaved(entries.indexOfFirst { it.queueEntryId == id }, false)
        },
        onMove = { from, to -> if (ownsQueue) model!!.moveQueueEntry(from, to) else editSaved(from, to) },
        onRemove = { paths -> if (ownsQueue) model!!.removeQueueTracks(paths) else editSaved(removedPaths = paths) },
        onRemoveEntry = { id -> if (ownsQueue) model!!.removeQueueEntries(setOf(id)) else editSaved(removedIds = setOf(id)) },
        onFavorite = actions.favorite,
        onAddToPlaylist = actions.addToPlaylist,
        onShowInfo = actions.showInfo,
        modifier = Modifier.fillMaxSize(),
        isActive = isActive,
        queueVersion = if (ownsQueue) live!!.queueVersion else 0L,
        tagPrefix = "library-queue",
        showSelectionPanel = globalSelection == null
    ) { sortButton ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.media_lib_track_count, tracks.size),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            sortButton()
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    if (ownsQueue) { model!!.play(); model.expand() }
                    else playSaved(currentIndex.coerceAtLeast(0), true)
                },
                enabled = tracks.isNotEmpty(),
                modifier = Modifier.testTag("library-queue-play-all")
            ) {
                Icon(painterResource(R.drawable.ic_video_play), null, Modifier.size(18.dp))
                Text(stringResource(R.string.media_lib_play_all))
            }
        }
    }
}
