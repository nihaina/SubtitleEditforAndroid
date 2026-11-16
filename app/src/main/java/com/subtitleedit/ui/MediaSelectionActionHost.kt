package com.subtitleedit.ui

import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.widget.Toast
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
import com.subtitleedit.library.MediaPlaylist
import com.subtitleedit.player.PlayerLibraryWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One action host keeps batch operations pinned to their captured cross-page selection. */
@Composable
internal fun MediaSelectionActionHost(
    controller: MediaSelectionController,
    onAddToQueue: (List<String>) -> Unit,
    onRemoveFromQueue: (List<String>) -> Unit,
    onPlayNext: (List<String>) -> Unit,
    onEditTags: (List<String>) -> Unit,
    onLocate: (String) -> Unit,
    onDeleteFiles: (paths: List<String>, onSuccess: () -> Unit) -> Unit,
    repository: MediaLibraryRepository = MediaLibraryRepository.getInstance(LocalContext.current),
    immersive: Boolean = false,
    onNavigateToCategory: ((MediaCategoryRevealRequest) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by repository.revision.collectAsState()
    var infoPath by remember { mutableStateOf<String?>(null) }
    var assigningPaths by remember { mutableStateOf<List<String>?>(null) }
    var playlists by remember { mutableStateOf<List<MediaPlaylist>>(emptyList()) }

    fun showMessage(message: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
    fun perform(snapshot: List<String>, action: () -> Unit, onSuccess: () -> Unit = {}) {
        scope.launch {
            try {
                PlayerLibraryWriter.execute(action)
                onSuccess()
                controller.finishAction(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(error.message ?: context.getString(R.string.error))
            }
        }
    }

    if (controller.optionsOpen) {
        MediaSelectionOptionsDialog(
            controller = controller,
            onDismiss = { controller.optionsOpen = false },
            immersive = immersive,
            onAction = { action, selected ->
                if (action.enabledFor(selected.size)) {
                    val snapshot = selected.toList()
                    controller.optionsOpen = false
                    when (action) {
                        MediaSelectionAction.PLAY_NEXT -> {
                            onPlayNext(snapshot)
                            controller.finishAction(snapshot)
                        }
                        MediaSelectionAction.ADD_TO_QUEUE -> {
                            onAddToQueue(snapshot)
                            controller.finishAction(snapshot)
                        }
                        MediaSelectionAction.REMOVE_FROM_QUEUE -> {
                            onRemoveFromQueue(snapshot)
                            controller.finishAction(snapshot)
                        }
                        MediaSelectionAction.ADD_TO_PLAYLIST -> assigningPaths = snapshot
                        MediaSelectionAction.ADD_TO_FAVORITES -> perform(snapshot, {
                            snapshot.forEach { path ->
                                repository.addTracksToPlaylists(listOf(MediaLibraryRepository.FAVORITES_PLAYLIST_ID), path)
                            }
                        }) {
                            showMessage(context.getString(R.string.media_lib_files_favorited, snapshot.size))
                        }
                        MediaSelectionAction.REMOVE_FROM_FAVORITES -> perform(snapshot, {
                            snapshot.forEach(repository::removeFavorite)
                        }) { showMessage(context.getString(R.string.player_action_unfavorited_hint)) }
                        MediaSelectionAction.FILE_INFO -> {
                            infoPath = snapshot.single()
                            controller.finishAction(snapshot)
                        }
                        MediaSelectionAction.EDIT_TAGS -> {
                            onEditTags(snapshot)
                            controller.finishAction(snapshot)
                        }
                        MediaSelectionAction.LOCATE_DIRECTORY -> {
                            onLocate(snapshot.single())
                            controller.finishAction(snapshot)
                        }
                        MediaSelectionAction.SHARE -> {
                            try {
                                context.startActivity(Intent.createChooser(
                                    mediaSelectionShareIntent(context, snapshot),
                                    context.getString(R.string.media_selection_share)
                                ))
                                controller.finishAction(snapshot)
                            } catch (error: Exception) {
                                showMessage(error.message ?: context.getString(R.string.error))
                            }
                        }
                        MediaSelectionAction.CLEAR_HISTORY -> perform(snapshot, {
                            snapshot.forEach(repository::removePlaybackHistory)
                        }) { showMessage(context.getString(R.string.media_selection_history_cleared)) }
                        MediaSelectionAction.DELETE_FILES -> {
                            onDeleteFiles(snapshot) { controller.finishAction(snapshot) }
                        }
                    }
                }
            }
        )
    }

    LaunchedEffect(assigningPaths != null, revision) {
        if (assigningPaths != null) {
            try {
                playlists = PlayerLibraryWriter.execute { repository.getPlaylists() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(error.message ?: context.getString(R.string.error))
            }
        }
    }
    assigningPaths?.let { snapshot ->
        LibraryPlaylistAssignDialog(
            playlists = playlists,
            onDismiss = { assigningPaths = null },
            onSelectPlaylist = { playlist ->
                perform(snapshot, {
                    snapshot.forEach { repository.addTrackToPlaylist(playlist.id, it) }
                }) { assigningPaths = null }
            },
            onCreatePlaylist = { name ->
                perform(snapshot, {
                    val id = checkNotNull(repository.createPlaylist(name))
                    snapshot.forEach { repository.addTrackToPlaylist(id, it) }
                }) { assigningPaths = null }
            },
            immersive = immersive
        )
    }
    infoPath?.let { path ->
        MediaSelectionFileInfoDialog(
            path = path,
            onDismiss = { infoPath = null },
            onEditTags = { onEditTags(listOf(it)) },
            immersive = immersive,
            onNavigateToCategory = onNavigateToCategory
        )
    }
}
