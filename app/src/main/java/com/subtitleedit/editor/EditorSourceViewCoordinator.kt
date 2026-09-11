package com.subtitleedit.editor

import com.subtitleedit.EditorEditHistory
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser

/** Resolves small source edits against cached history entries without reparsing the full file. */
internal class EditorSourceViewCoordinator(
    private val format: () -> SubtitleParser.SubtitleFormat,
    private val peekUndo: () -> EditorEditHistory.Operation?,
    private val updateLatestHistory: (String, List<SubtitleEntry>) -> Unit,
    private val applyEntries: (List<SubtitleEntry>) -> Unit,
    private val setEntriesGeneration: () -> Unit,
    private val clearPendingEdits: () -> Unit
) {
    fun applyDeletionLocally(content: String): Boolean {
        val operation = peekUndo() as? EditorEditHistory.Operation.SourceChange ?: return false
        if (operation.afterText != content || operation.beforeEntriesText != operation.beforeText) return false
        val before = operation.beforeText
        val prefix = EditorSourceDiffUtils.commonTextPrefix(before, content)
        val suffix = EditorSourceDiffUtils.commonTextSuffix(before, content, prefix)
        if (prefix == before.length && suffix == content.length) return false
        val deletedText = before.substring(prefix, before.length - suffix)
        if (deletedText.isBlank()) return false
        val deletedEntries = SubtitleParser.parseDocument(deletedText, format = format()).entries
        if (deletedEntries.isEmpty()) return false
        val range = EditorSourceDiffUtils.findMatchingEntryRange(operation.beforeEntries, deletedEntries, format()) ?: return false
        val target = operation.beforeEntries.map { it.copy() }.toMutableList()
        repeat(range.second) { target.removeAt(range.first) }
        applyEntries(target)
        updateLatestHistory(content, target)
        setEntriesGeneration()
        clearPendingEdits()
        return true
    }
}
