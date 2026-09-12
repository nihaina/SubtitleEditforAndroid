package com.subtitleedit.util

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Coordinates file-browser visibility, refresh and one-shot startup update checks. */
internal class MainLifecycleCoordinator(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    private val scope: CoroutineScope,
    private val directoryWatcher: DirectoryWatcher,
    private val shouldShowDirectory: () -> Boolean,
    private val refreshDirectory: () -> Unit,
    private val saveDirectoryScroll: () -> Unit,
    private val readFileFilters: () -> Pair<Boolean, Boolean>,
    private val applyFileFilters: (Boolean, Boolean) -> Unit,
    private val shouldCheckUpdates: () -> Boolean,
    private val showUpdate: (UpdateChecker.UpdateInfo) -> Unit
) {
    private var updateCheckStarted = false
    private var pendingUpdate: UpdateChecker.UpdateInfo? = null

    fun onResume() {
        directoryWatcher.setEnabled(true)
        val (showAll, showHidden) = readFileFilters()
        applyFileFilters(showAll, showHidden)
        if (shouldShowDirectory()) refreshDirectory()
        pendingUpdate?.let {
            pendingUpdate = null
            showUpdate(it)
        }
        if (!updateCheckStarted && shouldCheckUpdates()) {
            updateCheckStarted = true
            scope.launch {
                val update = UpdateChecker.check(activity) ?: return@launch
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    showUpdate(update)
                } else {
                    pendingUpdate = update
                }
            }
        }
    }

    fun onPause() {
        if (shouldShowDirectory()) saveDirectoryScroll()
        directoryWatcher.setEnabled(false)
    }

    fun onDestroy() = directoryWatcher.stop()
}
