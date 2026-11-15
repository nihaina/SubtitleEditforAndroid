package com.subtitleedit.ui

internal enum class MediaLibrarySelectionAction {
    SELECT_ALL,
    DESELECT_ALL,
    SELECT_RANGE,
    INVERT
}

/** Applies an advanced selection command to the files in the current folder's display order. */
internal fun updateMediaLibrarySelection(
    visiblePaths: List<String>,
    selectedPaths: Set<String>,
    action: MediaLibrarySelectionAction
): Set<String> {
    val visible = visiblePaths.distinct()
    return when (action) {
        MediaLibrarySelectionAction.SELECT_ALL -> selectedPaths + visible
        MediaLibrarySelectionAction.DESELECT_ALL -> selectedPaths - visible.toSet()
        MediaLibrarySelectionAction.INVERT -> {
            val visibleSet = visible.toSet()
            (selectedPaths - visibleSet) + visible.filterNot { it in selectedPaths }
        }
        MediaLibrarySelectionAction.SELECT_RANGE -> {
            val first = visible.indexOfFirst { it in selectedPaths }
            val last = visible.indexOfLast { it in selectedPaths }
            if (first < 0 || first == last) selectedPaths
            else selectedPaths + visible.subList(first, last + 1)
        }
    }
}
