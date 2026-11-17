package com.subtitleedit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.subtitleedit.library.PlayerTrackDetails
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.player.PlayerTrack
import com.subtitleedit.player.PlayerTrackInfoDialog
import com.subtitleedit.player.PlayerUiState
import com.subtitleedit.util.FileTypePolicy
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The player's information view, loaded for an immutable selected file rather than live playback. */
@Composable
internal fun MediaSelectionFileInfoDialog(
    path: String,
    onDismiss: () -> Unit,
    onEditTags: (String) -> Unit,
    immersive: Boolean = false,
    onNavigateToCategory: ((MediaCategoryRevealRequest) -> Unit)? = null,
    onLocate: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val file = remember(path) { File(path) }
    var details by remember(path) { mutableStateOf<PlayerTrackDetails?>(null) }
    var loading by remember(path) { mutableStateOf(true) }
    LaunchedEffect(path) {
        try {
            details = withContext(Dispatchers.IO) { PlayerTrackMetadataReader.read(file, cacheDirectory = context.cacheDir) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            details = null
        } finally {
            loading = false
        }
    }
    PlayerTrackInfoDialog(
        state = PlayerUiState(
            playlist = listOf(PlayerTrack(path, file.name, FileTypePolicy.isVideo(file))),
            currentIndex = 0,
            showTrackInfoDialog = true,
            trackInfoLoading = loading,
            trackInfo = details
        ),
        onDismiss = onDismiss,
        onEditTags = { onEditTags(path) },
        onNavigateToCategory = onNavigateToCategory?.let { navigate ->
            { request -> onDismiss(); navigate(request) }
        },
        immersive = immersive,
        onLocate = onLocate
    )
}
