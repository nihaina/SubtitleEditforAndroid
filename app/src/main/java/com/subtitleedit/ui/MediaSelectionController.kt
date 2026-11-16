package com.subtitleedit.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Audio-mode selection belongs to the window, so navigating does not discard selected files. */
@Stable
internal class MediaSelectionController(private val fileWorker: CoroutineDispatcher = Dispatchers.IO) {
    var active by mutableStateOf(false)
        private set
    var selectedPaths by mutableStateOf<List<String>>(emptyList())
        private set
    var closeAfterAction by mutableStateOf(true)
    var optionsOpen by mutableStateOf(false)
    private var visiblePaths by mutableStateOf<List<String>>(emptyList())
    private var overlayPaths: List<String>? = null
    private var visibleScopeRevision = 0L

    fun contains(path: String): Boolean = File(path).absolutePath in selectedPaths

    fun isVisibleMedia(path: String): Boolean = File(path).absolutePath in visiblePaths

    fun select(path: String) {
        val file = File(path).absoluteFile
        if (!isMedia(file) || !file.isFile) return
        active = true
        if (file.path !in selectedPaths) selectedPaths = selectedPaths + file.path
    }

    fun toggle(path: String) {
        val normalized = File(path).absolutePath
        if (normalized in selectedPaths) selectedPaths = selectedPaths - normalized else select(normalized)
    }

    fun updateVisiblePaths(paths: List<String>) {
        visibleScopeRevision++
        visiblePaths = mediaPaths(paths)
    }

    /** Page effects are cancelled on exit, so obsolete file checks cannot replace a new page's scope. */
    suspend fun refreshVisiblePaths(
        paths: List<String>,
        metadata: Map<String, BrowserFileMetadata> = emptyMap()
    ) {
        val revision = ++visibleScopeRevision
        // Range commands during a page change must never act on the previous page's files.
        visiblePaths = emptyList()
        val loaded = withContext(fileWorker) {
            mediaPaths(paths) { file -> metadata[file.path]?.isRegularFile ?: file.isFile }
        }
        if (revision == visibleScopeRevision) visiblePaths = loaded
    }

    fun setOverlayPaths(paths: List<String>?) {
        overlayPaths = paths?.let { mediaPaths(it) }
    }

    fun selectVisible(action: MediaLibrarySelectionAction) {
        active = true
        selectedPaths = updateMediaLibrarySelection(overlayPaths ?: visiblePaths, selectedPaths.toSet(), action).toList()
    }

    fun removePaths(paths: Collection<String>) {
        val removed = paths.mapTo(hashSetOf()) { File(it).absolutePath }
        selectedPaths = selectedPaths.filterNot { it in removed }
    }

    fun cancel() {
        active = false
        optionsOpen = false
        selectedPaths = emptyList()
    }

    fun finishAction(snapshot: List<String>) {
        optionsOpen = false
        if (closeAfterAction) {
            removePaths(snapshot)
            if (selectedPaths.isEmpty()) cancel()
        }
    }

    private fun isMedia(file: File): Boolean = FileUtils.isAudioFile(file) || FileTypePolicy.isVideo(file)

    private fun mediaPaths(
        paths: List<String>,
        isRegularFile: (File) -> Boolean = File::isFile
    ): List<String> = paths.asSequence().map { File(it).absoluteFile }
        .filter { isMedia(it) && isRegularFile(it) }.map { it.path }.distinct().toList()
}

internal val LocalMediaSelection = staticCompositionLocalOf<MediaSelectionController?> { null }
