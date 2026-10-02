package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry

internal object SubtitleStableRange {
    fun commonPrefix(previous: List<SubtitleEntry>, updated: List<SubtitleEntry>): Int {
        var index = 0
        while (index < previous.size && index < updated.size &&
            previous[index].stableId == updated[index].stableId
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
            previous[previous.size - 1 - count].stableId ==
            updated[updated.size - 1 - count].stableId
        ) count++
        return count
    }
}
