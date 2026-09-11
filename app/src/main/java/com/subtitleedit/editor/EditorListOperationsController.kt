package com.subtitleedit.editor

import com.subtitleedit.EditorDocumentOperations
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.CutPasteController
import com.subtitleedit.util.SubtitleEntryOps
import com.subtitleedit.util.SubtitlePasteOps

/** Pure list editing operations shared by menu actions and gesture handlers. */
internal class EditorListOperationsController {
    data class CutSelection(val texts: List<String>, val positions: List<Int>)

    fun copy(entries: List<SubtitleEntry>, positions: List<Int>): List<String> =
        positions.distinct().sorted().mapNotNull { entries.getOrNull(it)?.text }

    fun cut(entries: List<SubtitleEntry>, positions: List<Int>, cuts: CutPasteController): CutSelection {
        val valid = positions.distinct().sorted()
        val texts = copy(entries, valid)
        cuts.markMultiCut(valid)
        return CutSelection(texts, valid)
    }

    fun removePendingCut(entries: MutableList<SubtitleEntry>, cuts: CutPasteController): Set<Int> {
        val deleted = cuts.snapshotDeletedIndices()
        EditorDocumentOperations.removeAtDescending(entries, cuts.consumeDeletedIndicesDesc())
        return deleted
    }

    fun pasteAt(
        entries: MutableList<SubtitleEntry>,
        position: Int,
        clipboardTexts: List<String>
    ): SubtitlePasteOps.PasteAtPositionResult =
        SubtitlePasteOps.pasteAtPosition(entries, position, clipboardTexts)

    fun createInserted(
        after: Boolean,
        reference: SubtitleEntry,
        previous: SubtitleEntry?,
        next: SubtitleEntry?,
        texts: List<String>,
        insertPosition: Int
    ): List<SubtitleEntry> = SubtitleEntryOps.createInsertedEntries(
        after, reference, previous, next, texts
    ).onEachIndexed { index, entry -> entry.index = insertPosition + index + 1 }

    fun applyOffset(entries: List<SubtitleEntry>, positions: List<Int>, offsetMs: Long) {
        positions.distinct().forEach { position ->
            entries.getOrNull(position)?.let { entry ->
                EditorDocumentOperations.applyOffset(entry, offsetMs)
            }
        }
    }
}
