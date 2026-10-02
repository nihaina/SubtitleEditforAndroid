package com.subtitleedit.util

/** Tracks subtitle blocks already selected during the current playback pass. */
internal class SubtitlePlaybackSelectionTracker {
    private val selectedStableIds = mutableSetOf<Long>()

    fun reset() {
        selectedStableIds.clear()
    }

    fun selectIfNeeded(stableId: Long, select: () -> Boolean): Boolean {
        if (stableId in selectedStableIds) return false
        if (!select()) return false
        selectedStableIds.add(stableId)
        return true
    }
}
