package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.editor.EditorSourceTextEdit
import com.subtitleedit.util.SubtitleSourceSynchronizer

/**
 * In-memory undo/redo stacks shared by the list and source editors.
 * Clipboard contents and transient UI state intentionally never enter this history.
 */
internal class EditorEditHistory {
    data class ListState(
        val entries: List<SubtitleEntry>,
        val selectedIds: Set<Long>
    )

    sealed class Operation : EditorHistoryCommand {

        data class ListChange(
            val added: List<IndexedEntry> = emptyList(),
            val deleted: List<IndexedEntry> = emptyList(),
            val updated: List<EntryUpdate> = emptyList(),
            val beforeOrder: List<Long>? = null,
            val afterOrder: List<Long>? = null,
            val selected: Set<Long> = emptySet(),
            val deselected: Set<Long> = emptySet(),
            val sourceEdit: EditorSourceTextEdit? = null,
            override val description: String = ""
        ) : Operation() {
            data class IndexedEntry(val position: Int, val entry: SubtitleEntry)

            data class EntryUpdate(
                val beforePosition: Int,
                val afterPosition: Int,
                val before: SubtitleEntry,
                val after: SubtitleEntry
            )

            companion object {
                fun fromStates(
                    before: ListState,
                    after: ListState,
                    description: String,
                    beforeSourceText: String? = null,
                    afterSourceText: String? = null,
                    beforeHeader: String? = null,
                    afterHeader: String? = null,
                    beforeFooter: String? = null,
                    afterFooter: String? = null
                ): ListChange {
                    val beforePositions = before.entries.withIndex().associate { it.value.stableId to it.index }
                    val afterPositions = after.entries.withIndex().associate { it.value.stableId to it.index }
                    val beforeById = before.entries.associateBy { it.stableId }
                    val afterById = after.entries.associateBy { it.stableId }
                    val beforeOrder = before.entries.map { it.stableId }
                    val afterOrder = after.entries.map { it.stableId }
                    val orderChanged = beforeOrder != afterOrder &&
                        beforeOrder.filter { it in afterById } != afterOrder.filter { it in beforeById }

                    return ListChange(
                        added = after.entries.mapIndexedNotNull { position, entry ->
                            if (entry.stableId in beforeById) null
                            else IndexedEntry(position, entry.copy())
                        },
                        deleted = before.entries.mapIndexedNotNull { position, entry ->
                            if (entry.stableId in afterById) null
                            else IndexedEntry(position, entry.copy())
                        },
                        updated = before.entries.mapNotNull { old ->
                            val new = afterById[old.stableId] ?: return@mapNotNull null
                            if (!hasEditableDifference(old, new)) return@mapNotNull null
                            EntryUpdate(
                                beforePosition = beforePositions.getValue(old.stableId),
                                afterPosition = afterPositions.getValue(old.stableId),
                                before = old.copy(),
                                after = new.copy()
                            )
                        },
                        beforeOrder = beforeOrder.takeIf { orderChanged },
                        afterOrder = afterOrder.takeIf { orderChanged },
                        selected = after.selectedIds - before.selectedIds,
                        deselected = before.selectedIds - after.selectedIds,
                        sourceEdit = if (beforeSourceText != null && afterSourceText != null &&
                            beforeSourceText != afterSourceText
                        ) EditorSourceTextEdit.between(beforeSourceText, afterSourceText) else null,
                        description = description
                    )
                }
            }
        }

        data class SourceChange(
            val edit: EditorSourceTextEdit,
            override val description: String
        ) : Operation()

        override fun isSelectionOnly(): Boolean = when (this) {
            is ListChange -> sourceEdit == null && added.isEmpty() && deleted.isEmpty() &&
                updated.isEmpty() && beforeOrder == null && afterOrder == null
            is SourceChange -> false
        }

        override fun execute(
            state: EditorDocumentState,
            undo: Boolean
        ): EditorHistoryCommandResult {
            return when (this) {
                is ListChange -> {
                    val previousEntries = state.subtitleEntries.map { it.copy() }
                    val entries = state.subtitleEntries
                    val byId = entries.associateBy { it.stableId }
                    updated.map { if (undo) it.before else it.after }.forEach { target ->
                        byId[target.stableId]?.let { current -> EditorDocumentOperations.updateFields(current, target) }
                    }
                    val removeIds = (if (undo) added else deleted).mapTo(mutableSetOf()) { it.entry.stableId }
                    for (position in entries.indices.reversed()) {
                        if (entries[position].stableId in removeIds) entries.removeAt(position)
                    }
                    val positioned = (if (undo) deleted else added).sortedBy { it.position }
                    positioned.forEach { item ->
                        entries.add(item.position.coerceIn(0, entries.size), item.entry.copy())
                    }
                    val targetOrder = if (undo) beforeOrder else afterOrder
                    if (targetOrder != null) {
                        val currentById = entries.associateBy { it.stableId }
                        val ordered = targetOrder.mapNotNull { currentById[it] }
                        entries.clear()
                        entries.addAll(ordered)
                    }
                    EditorDocumentOperations.renumber(entries)

                    val changedPositions = buildSet {
                        (if (undo) added else deleted).forEach { add(it.position) }
                        (if (undo) deleted else added).forEach { add(it.position) }
                        updated.forEach { add(if (undo) it.beforePosition else it.afterPosition) }
                        targetOrder?.indices?.forEach { add(it) }
                    }
                    val structureChanged = added.isNotEmpty() || deleted.isNotEmpty() || targetOrder != null
                    val source = when {
                        sourceEdit != null -> applySourceEdit(state, sourceEdit, undo)
                        added.isNotEmpty() || deleted.isNotEmpty() || updated.isNotEmpty() || targetOrder != null ->
                            SubtitleSourceSynchronizer.apply(
                                content = state.originalFileContent,
                                format = state.currentFormat,
                                oldEntries = previousEntries,
                                newEntries = entries
                            )
                        else -> state.originalFileContent
                    }
                    state.originalFileContent = source
                    state.sourceViewContent = source
                    state.sourceHistoryTextSnapshot = source
                    state.sourceViewNeedsListSync = false
                    state.restoreVttMetadata(source)
                    val currentSelection = state.historySelectionSnapshot
                    EditorHistoryCommandResult(
                        entries = entries.map { it.copy() },
                        sourceText = source,
                        selectedIds = (currentSelection -
                            (if (undo) selected else deselected) +
                            (if (undo) deselected else selected)),
                        changedPositions = changedPositions,
                        structureChanged = structureChanged
                    )
                }
                is SourceChange -> {
                    val targetText = edit.applyTo(state.originalFileContent, undo)
                    state.originalFileContent = targetText
                    state.sourceViewContent = targetText
                    state.restoreVttMetadata(targetText)
                    state.sourceHistoryTextSnapshot = targetText
                    state.sourceViewNeedsListSync = true
                    EditorHistoryCommandResult(
                        entries = state.subtitleEntries.map { it.copy() },
                        sourceText = targetText,
                        selectedIds = emptySet(),
                        entriesResolved = false
                    )
                }
            }
        }
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
    private var lastSourceChangeAtMs: Long? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val canUndoWithoutSelection: Boolean get() = undoStack.any { !it.isSelectionOnly() }
    val canRedoWithoutSelection: Boolean get() = redoStack.any { !it.isSelectionOnly() }

    fun peekUndo(): Operation? = undoStack.lastOrNull()

    fun peekRedo(): Operation? = redoStack.lastOrNull()

    fun peekUndoWithoutSelection(): Operation? = undoStack.lastOrNull { !it.isSelectionOnly() }

    fun peekRedoWithoutSelection(): Operation? = redoStack.lastOrNull { !it.isSelectionOnly() }

    fun record(operation: Operation, timestampMs: Long = System.currentTimeMillis()) {
        if (operation.isNoOp()) return
        val previous = undoStack.lastOrNull() as? Operation.SourceChange
        val merged = operation as? Operation.SourceChange
        if (merged != null && previous != null &&
            lastSourceChangeAtMs?.let { timestampMs - it in 0..SOURCE_DELETE_MERGE_WINDOW_MS } == true &&
            previous.edit.insertedText.isEmpty() && previous.edit.deletedText.isNotEmpty() &&
            merged.edit.insertedText.isEmpty() && merged.edit.deletedText.isNotEmpty()
        ) {
            val combined = mergeDeletions(previous.edit, merged.edit)
            if (combined == null) {
                undoStack.addLast(operation)
            } else {
                undoStack.removeLast()
                undoStack.addLast(
                    Operation.SourceChange(
                        edit = combined,
                        description = listOf(previous.description, merged.description)
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                    )
                )
            }
        } else {
            undoStack.addLast(operation)
        }
        redoStack.clear()
        lastSourceChangeAtMs = if (operation is Operation.SourceChange) timestampMs else null
    }

    fun takeUndo(): Operation? {
        lastSourceChangeAtMs = null
        return undoStack.removeLastOrNull()
    }

    fun takeRedo(): Operation? {
        lastSourceChangeAtMs = null
        return redoStack.removeLastOrNull()
    }

    fun pushUndo(operation: Operation) {
        lastSourceChangeAtMs = null
        undoStack.addLast(operation)
    }

    fun pushRedo(operation: Operation) {
        lastSourceChangeAtMs = null
        redoStack.addLast(operation)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastSourceChangeAtMs = null
    }

    private fun mergeDeletions(
        previous: EditorSourceTextEdit,
        next: EditorSourceTextEdit
    ): EditorSourceTextEdit? {
        return when {
            next.startOffset + next.deletedText.length == previous.startOffset ->
                EditorSourceTextEdit(
                    startOffset = next.startOffset,
                    deletedText = next.deletedText + previous.deletedText,
                    insertedText = "",
                    lineNumber = next.lineNumber
                )
            next.startOffset == previous.startOffset ->
                EditorSourceTextEdit(
                    startOffset = previous.startOffset,
                    deletedText = previous.deletedText + next.deletedText,
                    insertedText = "",
                    lineNumber = previous.lineNumber
                )
            else -> null
        }
    }

    private fun Operation.isNoOp(): Boolean = when (this) {
        is Operation.ListChange -> sourceEdit == null && added.isEmpty() && deleted.isEmpty() &&
            updated.isEmpty() && beforeOrder == null && afterOrder == null &&
            selected.isEmpty() && deselected.isEmpty()
        is Operation.SourceChange -> edit.isNoOp
    }

    companion object {
        private const val SOURCE_DELETE_MERGE_WINDOW_MS = 400L

        private fun applySourceEdit(
            state: EditorDocumentState,
            edit: EditorSourceTextEdit,
            undo: Boolean
        ): String {
            return runCatching { edit.applyTo(state.originalFileContent, undo) }
                .getOrElse { edit.applyTo(state.sourceViewContent, undo) }
        }

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
                old.text != current.text ||
                old.cueIdentifier != current.cueIdentifier ||
                old.cueSettings != current.cueSettings

        fun haveSameEditableEntries(
            before: List<SubtitleEntry>,
            after: List<SubtitleEntry>
        ): Boolean = before.size == after.size && before.zip(after).all { (old, current) ->
            old.stableId == current.stableId && !hasEditableDifference(old, current)
        }
    }
}
