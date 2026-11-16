package com.subtitleedit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.io.File

/** Every media page exposes the same window-owned selection and options. */
@Composable
internal fun MediaSelectionPanel(
    controller: MediaSelectionController,
    modifier: Modifier = Modifier,
    isActive: Boolean = true
) {
    if (!controller.active) return
    BackHandler(enabled = isActive && !controller.optionsOpen) { controller.cancel() }
    MediaLibrarySelectionPanel(
        selectedFiles = controller.selectedPaths.map(::File),
        totalDurationMs = 0L,
        showDuration = false,
        onRemove = controller::toggle,
        onFavorite = {},
        onAddToPlaylist = {},
        onShowInfo = {},
        onCancel = controller::cancel,
        onSelectAll = { controller.selectVisible(MediaLibrarySelectionAction.SELECT_ALL) },
        onSelectRange = { controller.selectVisible(MediaLibrarySelectionAction.SELECT_RANGE) },
        onInvertSelection = { controller.selectVisible(MediaLibrarySelectionAction.INVERT) },
        onOptionsRequested = { controller.optionsOpen = true },
        modifier = modifier
    )
}
