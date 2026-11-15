package com.subtitleedit.util

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Serializes scans and event batches so cancelling a screen request cannot lose file changes. */
internal class DirectorySnapshotLoader(
    scope: CoroutineScope,
    private val cache: BrowserDirectoryCache,
    private val onLoaded: (BrowserDirectorySnapshot, Boolean) -> Unit,
    private val worker: CoroutineDispatcher = Dispatchers.IO
) {
    private sealed interface Request {
        data class Load(val directory: File, var restoreScrollPosition: Boolean, val generation: Long,
            val attributeGeneration: Long) : Request
        data class Change(val changes: DirectoryChanges, val generation: Long) : Request
    }

    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val pendingLoads = mutableMapOf<String, Request.Load>()
    private var generation = 0L

    init {
        scope.launch {
            for (request in requests) {
                when (request) {
                    is Request.Load -> {
                        if (request.generation != generation) {
                            if (pendingLoads[request.directory.absolutePath] === request) {
                                pendingLoads.remove(request.directory.absolutePath)
                            }
                            continue
                        }
                        val snapshot = withContext(worker) { cache.load(request.directory) }
                        if (pendingLoads[request.directory.absolutePath] === request) {
                            pendingLoads.remove(request.directory.absolutePath)
                        }
                        if (request.generation == generation) onLoaded(snapshot, request.restoreScrollPosition)
                    }
                    is Request.Change -> {
                        if (request.generation != generation) continue
                        val changes = request.changes
                        val snapshot = withContext(worker) {
                            cache.update(
                                File(changes.directoryPath), changes.changedNames,
                                changes.countChangedDirectories, changes.fullRefresh
                            )
                        }
                        if (request.generation == generation) onLoaded(snapshot, false)
                    }
                }
            }
        }.invokeOnCompletion { requests.close() }
    }

    // Requests originate on the UI thread; cache IO is confined to the serial worker above.
    fun load(directory: File, restoreScrollPosition: Boolean = false) {
        val path = directory.absolutePath
        val attributeGeneration = cache.attributes.generation
        pendingLoads[path]?.takeIf { it.generation == generation &&
            it.attributeGeneration == attributeGeneration }?.let {
            it.restoreScrollPosition = it.restoreScrollPosition || restoreScrollPosition
            return
        }
        val request = Request.Load(directory, restoreScrollPosition, generation, attributeGeneration)
        pendingLoads[path] = request
        requests.trySend(request)
    }

    fun update(changes: DirectoryChanges) {
        requests.trySend(Request.Change(changes, generation))
    }

    fun invalidate(path: String) {
        // This only marks the cache dirty. It must survive Activity-scope cancellation.
        generation++
        cache.invalidate(path)
    }
}
