package com.subtitleedit.editor

import com.subtitleedit.EditorEditHistory
import com.subtitleedit.EditorEditHistory.Operation
import com.subtitleedit.EditorViewModel
import com.subtitleedit.model.SubtitleEntry

/** Coordinates history snapshots and stack movement without owning any Android UI. */
internal class EditorHistoryCoordinator(
    private val stateModel: EditorViewModel,
    private val entries: () -> List<SubtitleEntry>,
    private val selectedIds: () -> Set<Long>,
    private val sourceMode: () -> Boolean,
    private val sourceText: () -> String,
    private val sourceEntriesReady: () -> Boolean,
    private val currentOriginalText: () -> String,
    private val applyOperation: (Operation, Boolean) -> Unit,
    private val invalidateMenu: () -> Unit
) {
    fun currentState() = EditorEditHistory.ListState(entries().map { it.copy() }, selectedIds())

    fun initialize(clear: Boolean) {
        val state = currentState()
        stateModel.setHistoryBaseline(state, sourceText(), clear)
    }

    fun sync() {
        val state = currentState()
        stateModel.syncHistoryBaseline(state, sourceText(), sourceMode())
    }

    fun recordList(selectedOverride: Set<Long>? = null) {
        if (sourceMode() || !stateModel.historyBaselineInitialized) return
        val before = EditorEditHistory.ListState(
            stateModel.historyEntriesSnapshot,
            stateModel.historySelectionSnapshot
        )
        val current = currentState()
        val after = selectedOverride?.let { current.copy(selectedIds = it) } ?: current
        val difference = EditorEditHistory.difference(before, after)
        if (!difference.isEmpty) {
            val contentChanged = difference.deleted.isNotEmpty() || difference.added.isNotEmpty() ||
                difference.orderChanged || difference.modified.isNotEmpty()
            val beforeSource = currentOriginalText()
            if (contentChanged) stateModel.syncListChangesToSource(before.entries, after.entries)
            stateModel.recordListHistory(
                before, after,
                EditorHistoryDescriptionFormatter.describeListStateChange(difference),
                beforeSource.takeIf { contentChanged },
                currentOriginalText().takeIf { contentChanged }
            )
            invalidateMenu()
        }
    }

    fun recordSource(before: String, after: String, beforeEntries: List<SubtitleEntry>? = null) {
        if (!sourceMode() || !stateModel.historyBaselineInitialized || before == after) return
        val cached = beforeEntries?.map { it.copy() }
            ?: entries().takeIf {
                sourceEntriesReady() && stateModel.sourceHistoryTextSnapshot == before
            }?.map { it.copy() }
        stateModel.recordSourceHistory(
            before, after,
            EditorHistoryDescriptionFormatter.describeSourceTextChange(before, after),
            cached ?: emptyList(),
            before.takeIf { cached != null }
        )
        invalidateMenu()
    }

    fun undo(): Boolean = move(true)
    fun redo(): Boolean = move(false)

    private fun move(undo: Boolean): Boolean {
        var applied = false
        val callback: (Operation, Boolean) -> Unit = { operation, reversed ->
            applyOperation(operation, reversed)
            applied = true
        }
        val result = if (undo) stateModel.undo(sourceMode(), callback)
        else stateModel.redo(sourceMode(), callback)
        if (result && applied) sync()
        return result
    }
}
