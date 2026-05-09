package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry

object SubtitlePasteOps {
    data class PasteAtPositionResult(
        val structureChanged: Boolean,
        val affectedPositions: Set<Int>
    )

    data class ReplaceSelectionResult(
        val insertedPositions: Set<Int>
    )

    fun pasteAtPosition(
        entries: MutableList<SubtitleEntry>,
        position: Int,
        clipboardEntries: List<SubtitleEntry>
    ): PasteAtPositionResult {
        if (clipboardEntries.isEmpty()) {
            return PasteAtPositionResult(structureChanged = false, affectedPositions = emptySet())
        }

        if (clipboardEntries.size == 1) {
            SubtitleEntryOps.copyContent(clipboardEntries.first(), entries[position])
            return PasteAtPositionResult(
                structureChanged = false,
                affectedPositions = setOf(position)
            )
        }

        // 多行策略：覆盖当前位置一行，剩余插入到后面
        entries[position] = SubtitleEntryOps.deepCopy(clipboardEntries.first())
        for (i in 1 until clipboardEntries.size) {
            entries.add(position + i, SubtitleEntryOps.deepCopy(clipboardEntries[i]))
        }
        val affected = (position until (position + clipboardEntries.size)).toSet()
        return PasteAtPositionResult(structureChanged = true, affectedPositions = affected)
    }

    fun replaceSelectionWithClipboard(
        entries: MutableList<SubtitleEntry>,
        selectedPositions: List<Int>,
        insertionPosition: Int,
        clipboardEntries: List<SubtitleEntry>
    ): ReplaceSelectionResult {
        selectedPositions.sortedDescending().forEach { position ->
            if (position in entries.indices) {
                entries.removeAt(position)
            }
        }

        val insertedPositions = mutableSetOf<Int>()
        clipboardEntries.forEachIndexed { index, sourceEntry ->
            val insertAt = (insertionPosition + index).coerceIn(0, entries.size)
            entries.add(insertAt, SubtitleEntryOps.deepCopy(sourceEntry))
            insertedPositions.add(insertAt)
        }
        return ReplaceSelectionResult(insertedPositions)
    }
}

