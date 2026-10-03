package com.subtitleedit

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.subtitleedit.model.ArchivePreviewBrowser
import com.subtitleedit.model.ArchivePreviewItem
import com.subtitleedit.util.ArchivePreviewCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class ArchivePreviewUiState(
    val archiveName: String = "",
    val currentDirectory: String = "",
    val items: List<ArchivePreviewItem> = emptyList(),
    val entryCount: Int? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

internal class ArchivePreviewViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AppViewModel<ArchivePreviewUiState, Nothing>(application, ArchivePreviewUiState()) {
    private companion object {
        const val STATE_DIRECTORY = "state_directory"
    }

    private var browser: ArchivePreviewBrowser? = null
    private var previewFile: File? = null

    /** Reads the intent extras once; later calls (after recreation) are ignored. */
    fun initialize(archiveName: String, previewPath: String) {
        if (previewFile != null) return
        previewFile = File(previewPath)
        // The current directory survives process death like the legacy onSaveInstanceState.
        val restoredDirectory = savedState.get<String>(STATE_DIRECTORY).orEmpty()
        setState { copy(archiveName = archiveName, currentDirectory = restoredDirectory) }
        loadPreview()
    }

    /** Returns true when the page may close (already at the archive root). */
    fun navigateUp(): Boolean {
        val directory = currentState.currentDirectory
        if (directory.isEmpty()) return true
        showDirectory(ArchivePreviewBrowser.parentOf(directory))
        return false
    }

    fun showDirectory(directory: String) {
        val loadedBrowser = browser ?: return
        val normalized = directory.trim('/')
        savedState[STATE_DIRECTORY] = normalized
        setState { copy(currentDirectory = normalized, items = loadedBrowser.itemsAt(normalized)) }
    }

    private fun loadPreview() {
        val file = previewFile ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ArchivePreviewCache.read(file) }
            }
            setState { copy(isLoading = false) }
            result.onSuccess { entries ->
                browser = ArchivePreviewBrowser(entries)
                setState { copy(entryCount = entries.size, errorMessage = null) }
                showDirectory(currentState.currentDirectory)
            }.onFailure { error ->
                val message = string(
                    R.string.archive_preview_read_failed,
                    error.message ?: string(R.string.error_unknown)
                )
                setState { copy(items = emptyList(), errorMessage = message) }
            }
        }
    }

    override fun onCleared() {
        // Only reached when the page really finishes (not on configuration change).
        previewFile?.delete()
        super.onCleared()
    }
}
