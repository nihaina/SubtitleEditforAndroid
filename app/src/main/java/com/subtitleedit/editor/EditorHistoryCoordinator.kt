package com.subtitleedit.editor

import com.subtitleedit.EditorDocumentState
import com.subtitleedit.EditorEditHistory
import com.subtitleedit.EditorEditHistory.Operation
import com.subtitleedit.EditorViewModel
import com.subtitleedit.model.SubtitleEntry

/** Coordinates history snapshots and stack movement without owning any Android UI. */
internal class EditorHistoryCoordinator(
    private val stateModel: EditorViewModel,
    private val documentState: EditorDocumentState,
    private val selectedIds: () -> Set<Long>,
    private val sourceMode: () -> Boolean,
    private val applyOperation: (Operation, Boolean) -> Unit,
    private val invalidateMenu: () -> Unit
) {
    fun currentState() = EditorEditHistory.ListState(documentState.subtitleEntries.map { it.copy() }, selectedIds())

    fun initialize(clear: Boolean) {
        val state = currentState()
        stateModel.setHistoryBaseline(state, documentState.sourceViewContent, clear)
    }

    fun sync() {
        val state = currentState()
        stateModel.syncHistoryBaseline(state, documentState.sourceViewContent, sourceMode())
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
            val beforeSource = documentState.originalFileContent
            if (contentChanged) stateModel.syncListChangesToSource(before.entries, after.entries)
            stateModel.recordListHistory(
                before, after,
                EditorHistoryDescriptionFormatter.describeListStateChange(difference),
                beforeSource.takeIf { contentChanged },
                documentState.originalFileContent.takeIf { contentChanged }
            )
            invalidateMenu()
        }
    }

    fun recordSource(edit: EditorSourceTextEdit, before: String) {
        if (!sourceMode() || !stateModel.historyBaselineInitialized || edit.isNoOp) return
        stateModel.recordSourceHistory(
            edit,
            EditorHistoryDescriptionFormatter.describeSourceTextChange(edit, before)
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
