package com.subtitleedit.editor

import com.subtitleedit.EditorEditHistory

/** Single composition boundary for editor menus, navigation, state and lifecycle work. */
internal class EditorCoordinator(
    private val menu: EditorMenuController,
    private val lifecycle: EditorLifecycleCoordinator,
    private val navigation: EditorNavigationCoordinator,
    private val state: EditorStateCoordinator
) {
    fun bindNavigation() = navigation.bind()

    fun onNavigateUp() = navigation.onNavigateUp()

    fun bindState() = state.bind()

    fun prepareMenu(
        sourceMode: Boolean,
        selectedCount: Int,
        undo: EditorEditHistory.Operation?,
        redo: EditorEditHistory.Operation?,
        sourceTransitioning: Boolean
    ): List<EditorMenuGroupModel> = menu.build(
        sourceMode, selectedCount, undo, redo, sourceTransitioning
    )

    fun handleMenu(action: EditorMenuController.Action): Boolean = menu.handle(action)

    fun onStart() = lifecycle.onStart()
    fun onStop() = lifecycle.onStop()
    fun onDestroy() = lifecycle.onDestroy()
    fun onWindowFocusChanged(hasFocus: Boolean) = lifecycle.onWindowFocusChanged(hasFocus)
    fun onConfigurationChanged() = lifecycle.onConfigurationChanged()
}
