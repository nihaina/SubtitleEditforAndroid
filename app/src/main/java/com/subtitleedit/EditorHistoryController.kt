package com.subtitleedit

internal class EditorHistoryController(
    private val history: EditorEditHistory
) {
    fun undo(
        isSourceViewMode: Boolean,
        apply: (EditorEditHistory.Operation, Boolean) -> Unit
    ): Boolean = undoCommand(isSourceViewMode) { command, reversed ->
        apply(command as EditorEditHistory.Operation, reversed)
    }

    fun undoCommand(
        isSourceViewMode: Boolean,
        apply: (EditorHistoryCommand, Boolean) -> Unit
    ): Boolean = move(
        isSourceViewMode = isSourceViewMode,
        take = history::takeUndo,
        restore = history::pushUndo,
        destination = history::pushRedo,
        apply = { operation -> apply(operation, true) }
    )

    fun redo(
        isSourceViewMode: Boolean,
        apply: (EditorEditHistory.Operation, Boolean) -> Unit
    ): Boolean = redoCommand(isSourceViewMode) { command, reversed ->
        apply(command as EditorEditHistory.Operation, reversed)
    }

    fun redoCommand(
        isSourceViewMode: Boolean,
        apply: (EditorHistoryCommand, Boolean) -> Unit
    ): Boolean = move(
        isSourceViewMode = isSourceViewMode,
        take = history::takeRedo,
        restore = history::pushRedo,
        destination = history::pushUndo,
        apply = { operation -> apply(operation, false) }
    )

    private fun move(
        isSourceViewMode: Boolean,
        take: () -> EditorEditHistory.Operation?,
        restore: (EditorEditHistory.Operation) -> Unit,
        destination: (EditorEditHistory.Operation) -> Unit,
        apply: (EditorHistoryCommand) -> Unit
    ): Boolean {
        val skippedSelectionOperations = mutableListOf<EditorEditHistory.Operation>()
        var operation = take()
        while (operation != null && isSourceViewMode && history.run { operation.isSelectionOnly() }) {
            skippedSelectionOperations += operation
            operation = take()
        }
        if (operation == null) {
            skippedSelectionOperations.asReversed().forEach(restore)
            return false
        }

        skippedSelectionOperations.asReversed().forEach(restore)
        try {
            apply(operation)
        } catch (error: Throwable) {
            // The operation was removed before applying it. Put it back when the current
            // document no longer matches its patch so a failed undo/redo cannot lose history.
            restore(operation)
            throw error
        }
        destination(operation)
        return true
    }
}
