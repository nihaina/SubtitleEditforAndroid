package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry

/**
 * In-memory undo/redo stacks shared by the list and source editors.
 * Clipboard contents and transient UI state intentionally never enter this history.
 */
internal class EditorEditHistory {
    data class ListState(
        val entries: List<SubtitleEntry>,
        val selectedIds: Set<Long>
    )

    sealed class Operation {
        abstract val description: String

        data class ListChange(
            val before: ListState,
            val after: ListState,
            override val description: String
        ) : Operation()

        data class SourceChange(
            val beforeText: String,
            val afterText: String,
            override val description: String,
            val beforeEntries: List<SubtitleEntry> = emptyList(),
            var afterEntries: List<SubtitleEntry>? = null
        ) : Operation()
    }

    data class ListDifference(
        val deleted: List<SubtitleEntry>,
        val added: List<SubtitleEntry>,
        val modified: List<Pair<SubtitleEntry, SubtitleEntry>>,
        val selected: List<Long>,
        val deselected: List<Long>,
        val orderChanged: Boolean
    ) {
        val isEmpty: Boolean
            get() = deleted.isEmpty() && added.isEmpty() && modified.isEmpty() &&
                selected.isEmpty() && deselected.isEmpty() && !orderChanged
    }

    private val undoStack = ArrayDeque<Operation>()
    private val redoStack = ArrayDeque<Operation>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val canUndoWithoutSelection: Boolean get() = undoStack.any { !it.isSelectionOnly() }
    val canRedoWithoutSelection: Boolean get() = redoStack.any { !it.isSelectionOnly() }

    fun peekUndo(): Operation? = undoStack.lastOrNull()

    fun peekRedo(): Operation? = redoStack.lastOrNull()

    fun peekUndoWithoutSelection(): Operation? = undoStack.lastOrNull { !it.isSelectionOnly() }

    fun peekRedoWithoutSelection(): Operation? = redoStack.lastOrNull { !it.isSelectionOnly() }

    fun record(operation: Operation) {
        if (operation.isNoOp()) return
        undoStack.addLast(operation)
        redoStack.clear()
    }

    fun takeUndo(): Operation? = undoStack.removeLastOrNull()

    fun takeRedo(): Operation? = redoStack.removeLastOrNull()

    fun pushUndo(operation: Operation) {
        undoStack.addLast(operation)
    }

    fun pushRedo(operation: Operation) {
        redoStack.addLast(operation)
    }

    fun updateLatestSourceAfterEntries(afterText: String, entries: List<SubtitleEntry>) {
        val operation = undoStack.lastOrNull() as? Operation.SourceChange ?: return
        if (operation.afterText == afterText) {
            operation.afterEntries = entries.map { it.copy() }
        }
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun Operation.isNoOp(): Boolean = when (this) {
        is Operation.ListChange -> difference(before, after).isEmpty
        is Operation.SourceChange -> beforeText == afterText
    }

    fun Operation.isSelectionOnly(): Boolean =
        this is Operation.ListChange && haveSameEditableEntries(before.entries, after.entries)

    companion object {
        fun difference(before: ListState, after: ListState): ListDifference {
            val beforeById = before.entries.associateBy { it.stableId }
            val afterById = after.entries.associateBy { it.stableId }
            val selectedOrder = after.entries.map { it.stableId } + before.entries.map { it.stableId }

            return ListDifference(
                deleted = before.entries.filter { it.stableId !in afterById },
                added = after.entries.filter { it.stableId !in beforeById },
                modified = after.entries.mapNotNull { current ->
                    val old = beforeById[current.stableId] ?: return@mapNotNull null
                    (old to current).takeIf { hasEditableDifference(old, current) }
                },
                selected = selectedOrder.distinct().filter {
                    it in after.selectedIds && it !in before.selectedIds
                },
                deselected = selectedOrder.distinct().filter {
                    it in before.selectedIds && it !in after.selectedIds
                },
                orderChanged = before.entries.map { it.stableId } != after.entries.map { it.stableId }
            )
        }

        fun hasEditableDifference(old: SubtitleEntry, current: SubtitleEntry): Boolean =
            old.startTime != current.startTime ||
                old.endTime != current.endTime ||
                old.text != current.text

        fun haveSameEditableEntries(
            before: List<SubtitleEntry>,
            after: List<SubtitleEntry>
        ): Boolean = before.size == after.size && before.zip(after).all { (old, current) ->
            old.stableId == current.stableId && !hasEditableDifference(old, current)
        }
    }
}
