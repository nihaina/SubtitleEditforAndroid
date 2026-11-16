package com.subtitleedit.player

import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.MediaPlaylist
import com.subtitleedit.ui.LibraryPlaylistAssignDialog
import com.subtitleedit.ui.MediaFileInfoDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class PlaybackQueueLibraryActions(
    val favorite: (List<MediaLibraryTrack>) -> Unit,
    val addToPlaylist: (List<MediaLibraryTrack>) -> Unit,
    val showInfo: (MediaLibraryTrack) -> Unit
)

/** Queue actions capture the selected files rather than targeting the currently playing song. */
@Composable
internal fun rememberPlaybackQueueLibraryActions(
    repository: MediaLibraryRepository = MediaLibraryRepository.getInstance(LocalContext.current),
    immersive: Boolean = false
): PlaybackQueueLibraryActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by repository.revision.collectAsState()
    var assigning by remember { mutableStateOf<List<MediaLibraryTrack>?>(null) }
    var playlists by remember { mutableStateOf<List<MediaPlaylist>>(emptyList()) }
    var infoPath by remember { mutableStateOf<String?>(null) }

    fun showMessage(message: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    fun perform(action: () -> Unit, onSuccess: () -> Unit = {}) {
        scope.launch {
            try {
                PlayerLibraryWriter.execute(action)
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(error.message ?: context.getString(R.string.error))
            }
        }
    }

    LaunchedEffect(assigning != null, revision) {
        if (assigning != null) {
            try {
                playlists = PlayerLibraryWriter.execute { repository.getPlaylists() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(error.message ?: context.getString(R.string.error))
            }
        }
    }
    assigning?.let { tracks ->
        LibraryPlaylistAssignDialog(
            playlists = playlists,
            onDismiss = { assigning = null },
            onSelectPlaylist = { playlist ->
                perform({
                    tracks.forEach { repository.addTrackToPlaylist(playlist.id, it.path, it.durationMs) }
                }) { assigning = null }
            },
            onCreatePlaylist = { name ->
                perform({
                    val id = checkNotNull(repository.createPlaylist(name))
                    tracks.forEach { repository.addTrackToPlaylist(id, it.path, it.durationMs) }
                }) { assigning = null }
            },
            immersive = immersive
        )
    }
    infoPath?.let { path ->
        MediaFileInfoDialog(path, onDismiss = { infoPath = null }, immersive = immersive, allowTagEditing = false)
    }
    return PlaybackQueueLibraryActions(
        favorite = { tracks ->
            val selected = tracks.toList()
            perform({ selected.forEach {
                repository.addTracksToPlaylists(listOf(MediaLibraryRepository.FAVORITES_PLAYLIST_ID), it.path, it.durationMs)
            } }) {
                showMessage(context.getString(R.string.media_lib_files_favorited, selected.size))
            }
        },
        addToPlaylist = { assigning = it.toList() },
        showInfo = { infoPath = it.path }
    )
}
