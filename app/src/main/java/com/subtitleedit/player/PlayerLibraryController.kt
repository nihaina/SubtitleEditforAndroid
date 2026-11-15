package com.subtitleedit.player

import android.app.Application
import android.util.Log
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.PlayerTrackMetadataReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps asynchronous library actions pinned to the track on which they were requested. */
internal class PlayerLibraryController(
    private val application: Application,
    private val repository: MediaLibraryRepository,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlayerUiState>,
    private val currentGeneration: () -> Long,
    private val showHint: (String) -> Unit
) {
    private var trackInfoJob: Job? = null
    private var playlistPickerJob: Job? = null
    private var favoriteReadJob: Job? = null
    private var playlistTarget: Pair<String, Long>? = null

    init {
        scope.launch {
            repository.revision.collectLatest {
                refreshFavorite()
                if (state.value.showPlaylistPickerDialog) {
                    playlistTarget?.let { (path, generation) -> refreshPlaylistPickerData(path, generation) }
                }
            }
        }
    }

    fun refreshFavorite() {
        val path = state.value.currentTrack?.path ?: return
        val generation = currentGeneration()
        favoriteReadJob?.cancel()
        favoriteReadJob = scope.launch {
            libraryAction {
                val favorite = PlayerLibraryWriter.execute { repository.isFavorite(path) }
                if (isCurrentTrack(path, generation)) state.value = state.value.copy(isFavorite = favorite)
            }
        }
    }

    fun toggleFavorite() {
        val current = state.value
        val track = current.currentTrack ?: return
        val generation = currentGeneration()
        scope.launch {
            libraryAction {
                val favorite = PlayerLibraryWriter.execute {
                    repository.toggleFavorite(track.path, current.durationMs)
                }
                if (isCurrentTrack(track.path, generation)) {
                    state.value = state.value.copy(isFavorite = favorite)
                    showHint(application.getString(if (favorite) R.string.player_action_favorited_hint else R.string.player_action_unfavorited_hint))
                }
            }
        }
    }

    fun openTrackInfoDialog() {
        val current = state.value
        val track = current.currentTrack ?: return
        val generation = currentGeneration()
        closePlaylistPickerDialog()
        trackInfoJob?.cancel()
        state.value = state.value.copy(
            showTrackInfoDialog = true,
            trackInfoLoading = true,
            trackInfo = null,
            panel = PlayerPanel.NONE,
            controlsVisible = true
        )
        trackInfoJob = scope.launch {
            try {
                val details = withContext(Dispatchers.IO) {
                    PlayerTrackMetadataReader.read(track.file, current.durationMs, application.cacheDir)
                }
                if (isCurrentTrack(track.path, generation) && state.value.showTrackInfoDialog) {
                    state.value = state.value.copy(trackInfo = details, trackInfoLoading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrentTrack(track.path, generation) && state.value.showTrackInfoDialog) {
                    state.value = state.value.copy(trackInfoLoading = false)
                    libraryFailure(error)
                }
            }
        }
    }

    fun closeTrackInfoDialog() {
        trackInfoJob?.cancel()
        trackInfoJob = null
        state.value = state.value.copy(showTrackInfoDialog = false, trackInfoLoading = false, trackInfo = null)
    }

    fun openPlaylistPickerDialog() {
        val track = state.value.currentTrack ?: return
        closeTrackInfoDialog()
        playlistTarget = track.path to currentGeneration()
        state.value = state.value.copy(
            showPlaylistPickerDialog = true,
            userPlaylists = emptyList(),
            trackContainedPlaylistIds = emptySet(),
            panel = PlayerPanel.NONE,
            controlsVisible = true
        )
        refreshPlaylistPickerData(track.path, currentGeneration())
    }

    fun closePlaylistPickerDialog() {
        playlistPickerJob?.cancel()
        playlistPickerJob = null
        playlistTarget = null
        state.value = state.value.copy(showPlaylistPickerDialog = false,
            userPlaylists = emptyList(), trackContainedPlaylistIds = emptySet())
    }

    private fun refreshPlaylistPickerData(trackPath: String, generation: Long) {
        playlistPickerJob?.cancel()
        playlistPickerJob = scope.launch {
            libraryAction {
                val (playlists, containedIds) = PlayerLibraryWriter.execute {
                    repository.getPlaylists() to
                        repository.getPlaylistIdsContainingTrack(trackPath)
                }
                if (playlistTarget == (trackPath to generation) &&
                    isCurrentTrack(trackPath, generation) && state.value.showPlaylistPickerDialog) {
                    state.value = state.value.copy(userPlaylists = playlists, trackContainedPlaylistIds = containedIds)
                }
            }
        }
    }

    fun createUserPlaylist(name: String, onCreated: (Long?) -> Unit) {
        val target = playlistTarget
        if (target == null || !state.value.showPlaylistPickerDialog) {
            onCreated(null)
            return
        }
        scope.launch {
            try {
                val id = PlayerLibraryWriter.execute { repository.createPlaylist(name) }
                if (playlistTarget == target && isCurrentTrack(target.first, target.second)) {
                    onCreated(id)
                    refreshPlaylistPickerData(target.first, target.second)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (playlistTarget == target) {
                    onCreated(null)
                    libraryFailure(error)
                }
            }
        }
    }

    fun addCurrentTrackToPlaylists(playlistIds: Set<Long>) {
        val target = playlistTarget ?: return
        val current = state.value
        if (!isCurrentTrack(target.first, target.second) || playlistIds.isEmpty()) {
            closePlaylistPickerDialog()
            return
        }
        scope.launch {
            libraryAction {
                val added = PlayerLibraryWriter.execute {
                    repository.addTracksToPlaylists(playlistIds.toSet(), target.first, current.durationMs)
                }
                if (playlistTarget == target) closePlaylistPickerDialog()
                if (isCurrentTrack(target.first, target.second)) {
                    if (added > 0) showHint(application.getString(R.string.player_playlist_picker_added, added))
                    else showHint(application.getString(R.string.player_playlist_picker_already_in))
                }
            }
        }
    }

    private fun isCurrentTrack(path: String, generation: Long): Boolean =
        state.value.currentTrack?.path == path && currentGeneration() == generation

    private suspend fun libraryAction(action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            libraryFailure(error)
        }
    }

    private fun libraryFailure(error: Exception) {
        Log.e("PlayerLibrary", "Player library action failed", error)
        showHint(application.getString(R.string.player_library_operation_failed))
    }

}
