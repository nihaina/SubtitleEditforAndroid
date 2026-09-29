package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry

/** Which time labels in a subtitle row need the conflict color. */
internal object SubtitleTimeConflict {
    data class Markers(val start: Boolean, val end: Boolean)

    fun markers(entries: List<SubtitleEntry>, position: Int): Markers {
        val current = entries.getOrNull(position) ?: return Markers(false, false)
        if (current.startTime >= current.endTime) return Markers(true, true)

        val previous = entries.getOrNull(position - 1)
        val next = entries.getOrNull(position + 1)
        return Markers(
            start = previous?.endTime?.let { current.startTime < it } == true,
            end = next?.startTime?.let { it < current.endTime } == true
        )
    }
}
