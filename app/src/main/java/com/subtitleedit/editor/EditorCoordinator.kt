package com.subtitleedit.editor

import android.view.Menu
import android.view.MenuItem
import com.subtitleedit.EditorEditHistory

/**
 * Single composition boundary for the editor screen.
 *
 * Menu routing, back navigation and lifecycle/resource coordination are exposed through
 * this facade so the Activity does not need to know which editor subsystem owns them.
 * The Activity keeps only the Android host callbacks; editor actions enter through
 * this composition boundary.
 */
internal class EditorCoordinator(
    private val menu: EditorMenuController,
    private val lifecycle: EditorLifecycleCoordinator,
    private val navigation: EditorNavigationCoordinator
) {
    fun bindNavigation() = navigation.bind()

    fun onNavigateUp() = navigation.onNavigateUp()

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
