package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry

internal object SubtitleStableRange {
    fun commonPrefix(previous: List<SubtitleEntry>, updated: List<SubtitleEntry>): Int {
        var index = 0
        while (index < previous.size && index < updated.size &&
            isUnchanged(previous[index], updated[index])
        ) index++
        return index
    }

    fun commonSuffix(
        previous: List<SubtitleEntry>,
        updated: List<SubtitleEntry>,
        prefix: Int
    ): Int {
        var count = 0
        while (previous.size - 1 - count >= prefix &&
            updated.size - 1 - count >= prefix &&
            isUnchanged(
                previous[previous.size - 1 - count],
                updated[updated.size - 1 - count]
            )
        ) count++
        return count
    }

    /**
     * A stable ID identifies the same logical cue, while changed fields still need replacement.
     * The index is intentionally ignored because callers renumber entries after replacement.
     */
    private fun isUnchanged(previous: SubtitleEntry, updated: SubtitleEntry): Boolean =
        previous.stableId == updated.stableId &&
            previous.startTime == updated.startTime &&
            previous.endTime == updated.endTime &&
            previous.text == updated.text &&
            previous.endTimeModified == updated.endTimeModified &&
            previous.cueIdentifier == updated.cueIdentifier &&
            previous.cueSettings == updated.cueSettings
}
