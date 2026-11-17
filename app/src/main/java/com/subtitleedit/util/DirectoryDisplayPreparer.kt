package com.subtitleedit.util

import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.model.FileBrowserOrder
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DirectoryDisplayOptions(
    val query: String,
    val showHiddenFiles: Boolean,
    val showAllFileTypes: Boolean,
    val sortField: FileSortField,
    val sortDirection: FileSortDirection,
    val revealPath: String? = null
)

internal data class PreparedDirectoryDisplay(
    val snapshot: BrowserDirectorySnapshot,
    val options: DirectoryDisplayOptions,
    val files: List<File>,
    val metadata: Map<String, BrowserFileMetadata>,
    val childDirectories: List<File>
)

/** Keeps full-directory filtering and sorting off main, publishing only the latest request. */
internal class DirectoryDisplayPreparer(
    private val scope: CoroutineScope,
    private val onPrepared: (PreparedDirectoryDisplay, Boolean) -> Unit,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val includeFile: (BrowserFileMetadata, Boolean) -> Boolean = { entry, showAll ->
        FileBrowserPolicy.shouldDisplayFile(
            entry.file, showAll, FileTypePolicy.videoExtensions,
            { it.extension.lowercase() in ArchiveManager.recognizedExtensions }, isFile = entry.isRegularFile
        )
    }
) {
    private var job: Job? = null
    private var generation = 0L
    private var pendingDirectoryPath: String? = null
    private var pendingRestoreScroll = false
    private var lastPrepared: PreparedDirectoryDisplay? = null

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        pendingDirectoryPath = null
        pendingRestoreScroll = false
    }

    fun prepare(snapshot: BrowserDirectorySnapshot, options: DirectoryDisplayOptions, restoreScroll: Boolean = false) {
        val restore = restoreScroll ||
            (pendingDirectoryPath == snapshot.directory.absolutePath && pendingRestoreScroll)
        cancel()
        // Snapshot identity changes when the cache changes; returning to an unchanged
        // directory can reuse its filtered rows without another sort or list allocation.
        lastPrepared?.takeIf { it.snapshot === snapshot && it.options == options }?.let {
            onPrepared(it, restore)
            return
        }
        pendingDirectoryPath = snapshot.directory.absolutePath
        pendingRestoreScroll = restore
        val requestGeneration = generation
        job = scope.launch {
            val prepared = withContext(worker) {
                val entries = snapshot.entries.filter { entry ->
                    ensureActive()
                    entry.path == options.revealPath || (options.showHiddenFiles || !entry.file.name.startsWith(".")) &&
                        (options.query.isEmpty() || entry.file.name.contains(options.query, ignoreCase = true)) &&
                        (entry.isDirectory || includeFile(entry, options.showAllFileTypes))
                }
                val ordered = FileBrowserOrder.sortMetadata(entries, options.sortField, options.sortDirection)
                ensureActive()
                PreparedDirectoryDisplay(
                    snapshot, options, ordered.map { it.file }, ordered.associateBy { it.path },
                    snapshot.entries.filter { it.isDirectory && !it.restricted }.map { it.file }
                )
            }
            if (generation == requestGeneration) {
                pendingDirectoryPath = null
                pendingRestoreScroll = false
                lastPrepared = prepared
                onPrepared(prepared, restore)
            }
        }
    }
}
