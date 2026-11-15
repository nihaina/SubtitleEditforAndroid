package com.subtitleedit.util

import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

/** Watches the displayed directory and its direct child directories, never recursively. */
class DirectoryWatcher(
    private val onRefresh: (DirectoryChanges) -> Unit,
    private val refreshDelayMs: Long = DEFAULT_REFRESH_DELAY_MS,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val onWatchGap: (String) -> Unit = {}
) {
    private data class Observation(val token: Long, val observer: FileObserver)

    private var rootObserver: Observation? = null
    private val childObservers = linkedMapOf<String, Observation>()
    private var observedPath: String? = null
    private var enabled = false
    private var nextToken = 0L
    private var accumulator: DirectoryChangeAccumulator? = null
    private var refreshScheduled = false

    private val refreshRunnable = Runnable {
        refreshScheduled = false
        accumulator?.drain()?.let(onRefresh)
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) stop()
    }

    /** A null child list keeps the current child watches until a new snapshot is ready. */
    fun watch(directory: File, childDirectories: List<File>? = null) {
        if (!enabled) return
        val path = directory.absolutePath
        if (path != observedPath || rootObserver == null) {
            stop()
            observedPath = path
            accumulator = DirectoryChangeAccumulator(path)
            rootObserver = observe(directory, path, childPath = null)
        }
        if (childDirectories == null) return

        val desiredPaths = childDirectories.asSequence()
            .filter { it.parentFile?.absolutePath == path && it.name != ".." }
            .associateBy { it.absolutePath }
        val removedPaths = childObservers.keys.filter { it !in desiredPaths }
        removedPaths.forEach { childPath -> childObservers.remove(childPath)?.observer?.stopWatching() }
        desiredPaths.forEach { (childPath, childDirectory) ->
            if (childPath !in childObservers) {
                childObservers[childPath] = observe(childDirectory, path, childPath)
            }
        }
    }

    fun stop() {
        handler.removeCallbacks(refreshRunnable)
        refreshScheduled = false
        val previousPath = observedPath
        // Clear registrations before stopping them, rejecting already-posted native callbacks.
        val observers = listOfNotNull(rootObserver) + childObservers.values
        rootObserver = null
        childObservers.clear()
        observedPath = null
        accumulator = null
        observers.forEach { it.observer.stopWatching() }
        previousPath?.let(onWatchGap)
    }

    private fun onEvent(directoryPath: String, childPath: String?, token: Long, event: Int, name: String?) {
        if (!enabled || observedPath != directoryPath) return
        val activeToken = if (childPath == null) rootObserver?.token else childObservers[childPath]?.token
        if (activeToken != token || event and DIRECTORY_CHANGE_EVENTS == 0) return

        val batch = accumulator ?: return
        if (childPath == null) {
            val fullRefresh = name == null || event and UNKNOWN_CHANGE_EVENTS != 0
            batch.add(name, requireFullRefresh = fullRefresh)
            if (fullRefresh) {
                childObservers.keys.forEach { batch.add(null, countDirectory = it) }
            }
        } else {
            // Only direct membership changes affect a directory's item count. A child's
            // content write is handled when that child itself becomes the displayed directory.
            if (event and CHILD_MEMBERSHIP_EVENTS == 0 && name != null) return
            batch.add(File(childPath).name, countDirectory = childPath)
        }
        if (event and INVALIDATED_WATCH_EVENTS != 0) {
            if (childPath == null) {
                rootObserver?.observer?.stopWatching()
                rootObserver = null
                onWatchGap(directoryPath)
            } else {
                childObservers.remove(childPath)?.observer?.stopWatching()
            }
        }
        if (!refreshScheduled) {
            refreshScheduled = true
            handler.postDelayed(refreshRunnable, refreshDelayMs)
        }
    }

    @Suppress("DEPRECATION")
    private fun observe(directory: File, directoryPath: String, childPath: String?): Observation {
        val token = ++nextToken
        val receive: (Int, String?) -> Unit = { event, name ->
            // FileObserver delivers on its own thread. Keep batching and registrations on main.
            handler.post { onEvent(directoryPath, childPath, token, event, name) }
        }
        val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(directory, OBSERVED_EVENTS) {
                override fun onEvent(event: Int, path: String?) = receive(event, path)
            }
        } else {
            object : FileObserver(directory.absolutePath, OBSERVED_EVENTS) {
                override fun onEvent(event: Int, path: String?) = receive(event, path)
            }
        }
        observer.startWatching()
        return Observation(token, observer)
    }

    private companion object {
        const val DEFAULT_REFRESH_DELAY_MS = 250L
        // Linux inotify reports queue overflow / invalidated watches outside ALL_EVENTS.
        const val QUEUE_OVERFLOW = 0x00004000
        const val WATCH_IGNORED = 0x00008000
        val INVALIDATED_WATCH_EVENTS = FileObserver.DELETE_SELF or FileObserver.MOVE_SELF or WATCH_IGNORED
        val UNKNOWN_CHANGE_EVENTS = INVALIDATED_WATCH_EVENTS or QUEUE_OVERFLOW
        val CHILD_MEMBERSHIP_EVENTS = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or UNKNOWN_CHANGE_EVENTS
        val DIRECTORY_CHANGE_EVENTS = CHILD_MEMBERSHIP_EVENTS or FileObserver.CLOSE_WRITE or FileObserver.ATTRIB
        val OBSERVED_EVENTS = DIRECTORY_CHANGE_EVENTS and (QUEUE_OVERFLOW or WATCH_IGNORED).inv()
    }
}
