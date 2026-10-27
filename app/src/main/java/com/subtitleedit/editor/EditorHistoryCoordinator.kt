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
    /**
     * Build a history state from the document model.  The current state used while recording an
     * edit is only read by the diff builder, so it can borrow the live entry list.  Baselines and
     * undo/redo synchronization still request an owned snapshot below.
     */
    private fun currentState(copyEntries: Boolean = true) = EditorEditHistory.ListState(
        if (copyEntries) documentState.subtitleEntries.map { it.copy() }
        else documentState.subtitleEntries,
        selectedIds()
    )

    fun initialize(clear: Boolean) {
        val state = currentState()
        stateModel.setHistoryBaseline(state, documentState.sourceViewContent, clear)
    }

    fun sync() {
        // syncHistoryBaseline() owns only the changed rows for same-order list edits. Passing the
        // live list avoids a redundant whole-document deep copy here after undo/redo.
        val state = currentState(copyEntries = false)
        stateModel.syncHistoryBaseline(state, documentState.sourceViewContent, sourceMode())
    }

    fun recordList(selectedOverride: Set<Long>? = null) {
        if (sourceMode() || !stateModel.historyBaselineInitialized) return
        val before = EditorEditHistory.ListState(
            stateModel.historyEntriesSnapshot,
            stateModel.historySelectionSnapshot
        )
        // The command has already mutated the document model.  Avoid copying every subtitle just
        // to compare it with the previous baseline; changed entries are copied when the baseline
        // is advanced by EditorViewModel.recordListHistory().
        val current = currentState(copyEntries = false)
        val after = selectedOverride?.let { current.copy(selectedIds = it) } ?: current
        val difference = EditorEditHistory.difference(before, after)
        if (!difference.isEmpty) {
            val contentChanged = difference.deleted.isNotEmpty() || difference.added.isNotEmpty() ||
                difference.orderChanged || difference.modified.isNotEmpty()
            val beforeSource = documentState.originalFileContent
            if (contentChanged) {
                // The list presenter has already published the mutated rows before history
                // recording.  Avoid rebuilding a full SubtitleDocument a second time merely
                // to expose the synchronized raw source string.
                stateModel.syncListChangesToSource(before.entries, after.entries, publish = false)
            }
            stateModel.recordListHistory(
                before, after,
                EditorHistoryDescriptionFormatter.describeListStateChange(difference),
                beforeSource.takeIf { contentChanged },
                documentState.originalFileContent.takeIf { contentChanged },
                difference = difference
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
