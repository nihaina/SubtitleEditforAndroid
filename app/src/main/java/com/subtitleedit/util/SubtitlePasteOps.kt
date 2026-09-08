package com.subtitleedit.util

import com.subtitleedit.EditorDocumentOperations
import com.subtitleedit.model.SubtitleEntry

object SubtitlePasteOps {
    data class PasteAtPositionResult(
        val structureChanged: Boolean,
        val affectedPositions: Set<Int>
    )

    data class PasteToSelectionResult(
        val affectedPositions: Set<Int>
    )

    fun pasteAtPosition(
        entries: MutableList<SubtitleEntry>,
        position: Int,
        clipboardTexts: List<String>
    ): PasteAtPositionResult {
        if (clipboardTexts.isEmpty() || position !in entries.indices) {
            return PasteAtPositionResult(structureChanged = false, affectedPositions = emptySet())
        }

        EditorDocumentOperations.updateText(entries[position], clipboardTexts.first())
        if (clipboardTexts.size == 1) {
            return PasteAtPositionResult(
                structureChanged = false,
                affectedPositions = setOf(position)
            )
        }

        // 多出的文本统一复用“向后粘贴”的整组时间分配规则。
        val insertedEntries = SubtitleEntryOps.createInsertedEntries(
            after = true,
            reference = entries[position],
            previous = entries[position],
            next = entries.getOrNull(position + 1),
            texts = clipboardTexts.drop(1)
        )
        EditorDocumentOperations.addAllAt(entries, position + 1, insertedEntries)
        val affected = (position until (position + clipboardTexts.size)).toSet()
        return PasteAtPositionResult(structureChanged = true, affectedPositions = affected)
    }

    fun pasteToSelection(
        entries: MutableList<SubtitleEntry>,
        selectedPositions: List<Int>,
        clipboardTexts: List<String>
    ): PasteToSelectionResult {
        val validPositions = selectedPositions.distinct().sorted().filter { it in entries.indices }
        if (validPositions.isEmpty() || clipboardTexts.size < validPositions.size) {
            return PasteToSelectionResult(emptySet())
        }

        validPositions.forEachIndexed { index, position ->
            EditorDocumentOperations.updateText(entries[position], clipboardTexts[index])
        }

        val affectedPositions = validPositions.toMutableSet()
        val extraTexts = clipboardTexts.drop(validPositions.size)
        if (extraTexts.isNotEmpty()) {
            val lastTargetPosition = validPositions.last()
            val insertedEntries = SubtitleEntryOps.createInsertedEntries(
                after = true,
                reference = entries[lastTargetPosition],
                previous = entries[lastTargetPosition],
                next = entries.getOrNull(lastTargetPosition + 1),
                texts = extraTexts
            )
            val insertionPosition = lastTargetPosition + 1
            EditorDocumentOperations.addAllAt(entries, insertionPosition, insertedEntries)
            affectedPositions.addAll(
                insertionPosition until (insertionPosition + insertedEntries.size)
            )
        }
        return PasteToSelectionResult(affectedPositions)
    }
}
