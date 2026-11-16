package com.subtitleedit.player

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.ui.MediaSelectionActionHost
import com.subtitleedit.ui.MediaSelectionController
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.util.FileDeletionManager
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.MediaCacheCleaner
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps both the selection and an accepted deletion alive across player recreation. */
internal class StandaloneMediaSelectionViewModel : ViewModel() {
    val selection = MediaSelectionController()
    var pendingDeletePaths by mutableStateOf<List<String>?>(null)
        private set
    var deleting by mutableStateOf(false)
        private set
    private var deleteSuccess: (() -> Unit)? = null

    fun requestDeletion(paths: List<String>, onSuccess: () -> Unit) {
        if (deleting) return
        val files = paths.distinct().map(::File).filter(::isMediaFile)
        if (files.isEmpty()) return
        pendingDeletePaths = files.map { it.absolutePath }
        deleteSuccess = onSuccess
    }

    fun dismissDeletion() {
        if (deleting) return
        pendingDeletePaths = null
        deleteSuccess = null
    }

    fun confirmDeletion(context: Context, player: PlayerViewModel) {
        val paths = pendingDeletePaths ?: return
        if (deleting) return
        val onSuccess = deleteSuccess
        val appContext = context.applicationContext
        deleting = true
        viewModelScope.launch {
            val suspendedPlayer = player.state.value.currentTrack?.path in paths && !player.outputSuspended.value
            if (suspendedPlayer) player.suspendOutput()
            var allDeleted = false
            try {
                allDeleted = withContext(Dispatchers.IO) {
                    val files = paths.map(::File).filter(::isMediaFile)
                    val cacheSnapshots = linkedMapOf<File, MediaCacheCleaner.CacheSnapshot>()
                    try {
                        files.forEach { file ->
                            ensureActive()
                            cacheSnapshots[file] = MediaCacheCleaner.snapshotForMedia(
                                listOf(file), MediaCacheCleaner.Kind.values().asList(), appContext.cacheDir
                            )
                        }
                        FileDeletionManager.delete(files) { _, _, _ -> }
                        paths.none { File(it).exists() }
                    } finally {
                        cacheSnapshots.forEach { (file, snapshot) ->
                            if (!file.exists()) MediaCacheCleaner.clear(snapshot)
                        }
                    }
                }
                Toast.makeText(appContext, if (allDeleted) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(appContext, "删除失败：${error.message ?: appContext.getString(R.string.error)}",
                    Toast.LENGTH_SHORT).show()
            } finally {
                withContext(NonCancellable) {
                    try {
                        val removed = withContext(Dispatchers.IO) {
                            paths.filterNot { File(it).exists() }.toSet()
                        }
                        selection.removePaths(removed)
                        player.removeQueueTracks(removed)
                        if (removed.isNotEmpty()) PlayerLibraryWriter.submit {
                            MediaLibraryRepository.getInstance(appContext).updateObservedPaths(removed)
                        }
                    } finally {
                        if (suspendedPlayer) player.resumeOutput()
                        deleting = false
                        pendingDeletePaths = null
                        deleteSuccess = null
                    }
                }
            }
            if (allDeleted) onSuccess?.invoke()
        }
    }

    private fun isMediaFile(file: File): Boolean = file.isFile &&
        (FileUtils.isAudioFile(file) || FileTypePolicy.isVideo(file))
}

@Composable
internal fun StandaloneMediaSelectionHost(
    model: StandaloneMediaSelectionViewModel,
    player: PlayerViewModel,
    onEditTags: (List<String>) -> Unit,
    onLocate: (String) -> Unit
) {
    val context = LocalContext.current
    MediaSelectionActionHost(
        controller = model.selection,
        onAddToQueue = { paths ->
            player.appendQueueTracks(paths.map { path ->
                val file = File(path)
                PlayerTrack(path, file.name, FileTypePolicy.isVideo(file))
            })
        },
        onRemoveFromQueue = { paths -> player.removeFromCurrentQueue(paths.toSet()) },
        onPlayNext = { paths ->
            player.enqueueNextTracks(paths.map { path ->
                val file = File(path)
                PlayerTrack(path, file.name, FileTypePolicy.isVideo(file))
            })
        },
        onEditTags = onEditTags,
        onLocate = onLocate,
        onDeleteFiles = model::requestDeletion,
        immersive = true
    )
    model.pendingDeletePaths?.let { paths ->
        AppDialog(
            onDismissRequest = model::dismissDeletion,
            properties = DialogProperties(
                dismissOnBackPress = !model.deleting,
                dismissOnClickOutside = !model.deleting,
                decorFitsSystemWindows = false
            ),
            title = {
                PlayerPanelImmersiveWindow()
                Text(stringResource(R.string.media_selection_delete_files))
            },
            text = {
                if (model.deleting) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text("正在删除…")
                    }
                } else {
                    Text("确定删除选中的 ${paths.size} 个文件吗？此操作无法撤销。")
                }
            },
            confirmButton = {
                TextButton(onClick = { model.confirmDeletion(context, player) }, enabled = !model.deleting) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = model::dismissDeletion, enabled = !model.deleting) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
