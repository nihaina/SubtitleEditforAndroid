package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser

/** 源视图撤销/重做所需的纯文本与字幕范围匹配工具。 */
internal object EditorSourceDiffUtils {
    fun commonTextPrefix(before: String, after: String): Int {
        var index = 0
        val limit = minOf(before.length, after.length)
        while (index < limit && before[index] == after[index]) index++
        return index
    }

    fun commonTextSuffix(before: String, after: String, prefix: Int): Int {
        var count = 0
        while (before.length - 1 - count >= prefix &&
            after.length - 1 - count >= prefix &&
            before[before.length - 1 - count] == after[after.length - 1 - count]
        ) count++
        return count
    }

    fun findMatchingEntryRange(
        entries: List<SubtitleEntry>,
        deleted: List<SubtitleEntry>,
        format: SubtitleParser.SubtitleFormat
    ): Pair<Int, Int>? {
        if (deleted.isEmpty() || deleted.size > entries.size) return null
        for (start in 0..entries.size - deleted.size) {
            val matches = deleted.indices.all { offset ->
                val current = entries[start + offset]
                val removed = deleted[offset]
                val timingMatches = if (format == SubtitleParser.SubtitleFormat.LRC) {
                    current.startTime == removed.startTime &&
                        kotlin.math.abs(current.endTime - removed.endTime) <= 100L
                } else {
                    current.startTime == removed.startTime && current.endTime == removed.endTime
                }
                timingMatches && current.text == removed.text
            }
            if (matches) return start to deleted.size
        }
        return null
    }
}
