package com.subtitleedit.util

import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Watches a directory and coalesces bursts of filesystem events into one refresh.
 *
 * The watcher owns the platform [FileObserver] and its lifecycle. Consumers only
 * need to enable it while visible and provide a callback that reloads the directory.
 */
class DirectoryWatcher(
    private val onRefresh: () -> Unit,
    private val refreshDelayMs: Long = DEFAULT_REFRESH_DELAY_MS,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {

    private var observer: FileObserver? = null
    private var observedPath: String? = null
    private var enabled = false

    private val refreshRunnable = Runnable { onRefresh() }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) stop()
    }

    fun watch(directory: File) {
        if (!enabled) return
        val path = runCatching { directory.canonicalPath }
            .getOrElse { directory.absolutePath }
        if (path == observedPath && observer != null) return

        stop()
        observer = createObserver(directory).also {
            observedPath = path
            it.startWatching()
        }
    }

    fun stop() {
        handler.removeCallbacks(refreshRunnable)
        observer?.stopWatching()
        observer = null
        observedPath = null
    }

    private fun scheduleRefresh(event: Int) {
        if (!enabled || event and DIRECTORY_CHANGE_EVENTS == 0) return
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, refreshDelayMs)
    }

    @Suppress("DEPRECATION")
    private fun createObserver(directory: File): FileObserver =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(directory, DIRECTORY_CHANGE_EVENTS) {
                override fun onEvent(event: Int, path: String?) = scheduleRefresh(event)
            }
        } else {
            object : FileObserver(directory.absolutePath, DIRECTORY_CHANGE_EVENTS) {
                override fun onEvent(event: Int, path: String?) = scheduleRefresh(event)
            }
        }

    private companion object {
        const val DEFAULT_REFRESH_DELAY_MS = 250L
        val DIRECTORY_CHANGE_EVENTS = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or
            FileObserver.ATTRIB or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }
}
