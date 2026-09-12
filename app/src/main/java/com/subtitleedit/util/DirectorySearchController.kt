package com.subtitleedit.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.subtitleedit.model.FileBrowserOrder
import com.subtitleedit.model.FileBrowserSearch
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs cancellable recursive directory searches and publishes throttled results. */
class DirectorySearchController(
    private val scope: CoroutineScope,
    private val canEnterDirectory: (File) -> Boolean,
    private val onPartialResult: (List<File>) -> Unit,
    private val onCompleted: (List<File>) -> Unit,
    private val onFinished: () -> Unit,
    private val progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    private var job: Job? = null
    private var generation = 0L

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    fun search(
        root: File,
        query: String,
        includeHidden: Boolean,
        sortField: FileSortField,
        sortDirection: FileSortDirection,
        isCurrent: () -> Boolean
    ) {
        cancel()
        val searchGeneration = generation
        val rootPath = root.absolutePath
        job = scope.launch {
            try {
                val displayed = withContext(Dispatchers.IO) {
                    val workerContext = coroutineContext
                    var lastPublishedAt = 0L
                    var lastPublishedCount = -1
                    val matches = FileBrowserSearch.search(
                        root = root,
                        query = query,
                        includeHidden = includeHidden,
                        includeFile = { true },
                        canEnterDirectory = canEnterDirectory,
                        onEntryVisited = { workerContext.ensureActive() },
                        onDirectoryScanned = { partialMatches ->
                            workerContext.ensureActive()
                            val now = SystemClock.elapsedRealtime()
                            if (partialMatches.size != lastPublishedCount &&
                                now - lastPublishedAt >= progressIntervalMs
                            ) {
                                lastPublishedAt = now
                                lastPublishedCount = partialMatches.size
                                val partialDisplayed = FileBrowserOrder.sort(
                                    partialMatches.toList(), sortField, sortDirection
                                )
                                mainHandler.post {
                                    if (isCurrent() && generation == searchGeneration && job?.isActive == true) {
                                        onPartialResult(partialDisplayed)
                                    }
                                }
                            }
                        }
                    )
                    FileBrowserOrder.sort(matches, sortField, sortDirection)
                }
                if (generation == searchGeneration && isCurrent()) onCompleted(displayed)
            } finally {
                if (generation == searchGeneration) onFinished()
            }
        }
    }

    private companion object {
        const val DEFAULT_PROGRESS_INTERVAL_MS = 150L
    }
}
