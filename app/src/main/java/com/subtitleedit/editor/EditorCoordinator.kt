package com.subtitleedit.editor

import android.view.Menu
import android.view.MenuItem
import com.subtitleedit.EditorEditHistory

/**
 * Single composition boundary for the editor screen.
 *
 * Menu routing and lifecycle/resource coordination are deliberately exposed through
 * this facade so the Activity does not need to know which editor subsystem owns them.
 * Document, source-view and media callbacks can be moved behind the same boundary
 * without changing the Activity's lifecycle contract.
 */
internal class EditorCoordinator(
    private val menu: EditorMenuController,
    private val lifecycle: EditorLifecycleCoordinator
) {
    fun prepareMenu(
        menuView: Menu,
        sourceMode: Boolean,
        selectedCount: Int,
        undo: EditorEditHistory.Operation?,
        redo: EditorEditHistory.Operation?,
        sourceTransitioning: Boolean
    ): Boolean = menu.prepare(
        menuView, sourceMode, selectedCount, undo, redo, sourceTransitioning
    )

    fun handleMenu(item: MenuItem): Boolean = menu.handle(item)

    fun onStart() = lifecycle.onStart()
    fun onStop() = lifecycle.onStop()
    fun onDestroy() = lifecycle.onDestroy()
    fun onWindowFocusChanged(hasFocus: Boolean) = lifecycle.onWindowFocusChanged(hasFocus)
    fun onConfigurationChanged() = lifecycle.onConfigurationChanged()
}
