package com.subtitleedit.editor

import com.subtitleedit.EditorEditHistory
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser

/** Resolves full-cue deletions against the current rows without retaining a history snapshot. */
internal class EditorSourceViewCoordinator(
    private val format: () -> SubtitleParser.SubtitleFormat,
    private val peekUndo: () -> EditorEditHistory.Operation?,
    private val entries: () -> List<SubtitleEntry>,
    private val applyEntries: (List<SubtitleEntry>) -> Unit,
    private val setEntriesGeneration: () -> Unit,
    private val clearPendingEdits: () -> Unit
) {
    fun applyDeletionLocally(content: String): Boolean {
        val operation = peekUndo() as? EditorEditHistory.Operation.SourceChange ?: return false
        val edit = operation.edit
        if (edit.insertedText.isNotEmpty() || edit.deletedText.isBlank()) return false
        if (edit.startOffset !in 0..content.length) return false

        val deletedEntries = SubtitleParser.parseDocument(edit.deletedText, format = format()).entries
        if (deletedEntries.isEmpty()) return false
        val currentEntries = entries()
        val range = EditorSourceDiffUtils.findMatchingEntryRange(currentEntries, deletedEntries, format()) ?: return false
        val target = currentEntries.map { it.copy() }.toMutableList()
        repeat(range.second) { target.removeAt(range.first) }
        applyEntries(target)
        setEntriesGeneration()
        clearPendingEdits()
        return true
    }

}
