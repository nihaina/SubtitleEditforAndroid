package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
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
            val before: ListState,
            val after: ListState,
            override val description: String,
            val beforeSourceText: String? = null,
            val afterSourceText: String? = null
        ) : Operation()

        data class SourceChange(
            val beforeText: String,
            val afterText: String,
            override val description: String,
            val beforeEntries: List<SubtitleEntry> = emptyList(),
            val beforeEntriesText: String? = null,
            var afterEntries: List<SubtitleEntry>? = null,
            var afterEntriesText: String? = null
        ) : Operation()

        override fun isSelectionOnly(): Boolean = when (this) {
            is ListChange -> haveSameEditableEntries(before.entries, after.entries)
            is SourceChange -> false
        }

        override fun execute(
            state: EditorDocumentState,
            undo: Boolean
        ): EditorHistoryCommandResult {
            return when (this) {
                is ListChange -> {
                    val target = if (undo) before else after
                    val targetSource = if (undo) beforeSourceText else afterSourceText
                    val entries = target.entries.map { it.copy() }
                    val previousEntries = state.subtitleEntries.toList()
                    val contentChanged = !haveSameEditableEntries(previousEntries, entries)
                    val source = targetSource ?: if (contentChanged) {
                        SubtitleSourceSynchronizer.apply(
                            content = state.originalFileContent,
                            format = state.currentFormat,
                            oldEntries = previousEntries,
                            newEntries = entries
                        )
                    } else {
                        state.originalFileContent
                    }
                    val changedPositions = entries.indices.filter { position ->
                        val current = previousEntries.getOrNull(position)
                        val targetEntry = entries[position]
                        current == null || current != targetEntry
                    }.toSet()
                    val structureChanged = previousEntries.size != entries.size ||
                        previousEntries.map { it.stableId } != entries.map { it.stableId }
                    if (!structureChanged && previousEntries.size == entries.size &&
                        previousEntries.zip(entries).all { (current, targetEntry) ->
                            current.stableId == targetEntry.stableId &&
                                current.cueIdentifier == targetEntry.cueIdentifier &&
                                current.cueSettings == targetEntry.cueSettings
                        }
                    ) {
                        previousEntries.forEachIndexed { position, current ->
                            EditorDocumentOperations.updateFields(current, entries[position])
                        }
                    } else {
                        state.subtitleEntries = entries.map { it.copy() }.toMutableList()
                    }
                    state.originalFileContent = source
                    state.sourceViewContent = source
                    state.sourceHistoryTextSnapshot = source
                    state.sourceViewNeedsListSync = false
                    EditorHistoryCommandResult(
                        entries = state.subtitleEntries.map { it.copy() },
                        sourceText = source,
                        selectedIds = target.selectedIds,
                        changedPositions = changedPositions,
                        structureChanged = structureChanged
                    )
                }
                is SourceChange -> {
                    val targetText = if (undo) beforeText else afterText
                    val cachedEntries = if (undo) {
                        beforeEntries.takeIf { beforeEntriesText == beforeText }
                    } else {
                        afterEntries?.takeIf { afterEntriesText == afterText }
                    }
                    val entries = cachedEntries ?: state.subtitleEntries.toList()
                    val previousEntries = state.subtitleEntries.toList()
                    val changedPositions = if (cachedEntries == null) {
                        emptySet()
                    } else {
                        entries.indices.filter { position ->
                            previousEntries.getOrNull(position) != entries[position]
                        }.toSet()
                    }
                    val structureChanged = cachedEntries != null && (
                        previousEntries.size != entries.size ||
                            previousEntries.map { it.stableId } != entries.map { it.stableId }
                        )
                    state.originalFileContent = targetText
                    state.sourceViewContent = targetText
                    state.sourceHistoryTextSnapshot = targetText
                    state.sourceViewNeedsListSync = cachedEntries == null
                    if (cachedEntries != null) {
                        if (!structureChanged && previousEntries.size == entries.size) {
                            previousEntries.forEachIndexed { position, current ->
                                EditorDocumentOperations.updateFields(current, entries[position])
                            }
                        } else {
                            state.subtitleEntries = entries.map { it.copy() }.toMutableList()
                        }
                    }
                    EditorHistoryCommandResult(
                        entries = entries.map { it.copy() },
                        sourceText = targetText,
                        selectedIds = emptySet(),
                        changedPositions = changedPositions,
                        structureChanged = structureChanged,
                        entriesResolved = cachedEntries != null
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
            previous.afterText == merged.beforeText &&
            isPureDeletion(previous.beforeText, previous.afterText) &&
            isPureDeletion(merged.beforeText, merged.afterText)
        ) {
            undoStack.removeLast()
            undoStack.addLast(
                Operation.SourceChange(
                    beforeText = previous.beforeText,
                    afterText = merged.afterText,
                    description = listOf(previous.description, merged.description)
                        .filter { it.isNotBlank() }
                        .joinToString("\n"),
                    beforeEntries = previous.beforeEntries,
                    beforeEntriesText = previous.beforeEntriesText,
                    afterEntries = merged.afterEntries,
                    afterEntriesText = merged.afterEntriesText
                )
            )
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

    fun updateLatestSourceAfterEntries(afterText: String, entries: List<SubtitleEntry>) {
        val operation = undoStack.lastOrNull() as? Operation.SourceChange ?: return
        if (operation.afterText == afterText) {
            operation.afterEntries = entries.map { it.copy() }
            operation.afterEntriesText = afterText
        }
    }

    fun updateSourceAfterEntries(afterText: String, entries: List<SubtitleEntry>) {
        val copied = entries.map { it.copy() }
        listOf(undoStack.lastOrNull(), redoStack.lastOrNull())
            .filterIsInstance<Operation.SourceChange>()
            .forEach { operation ->
                if (operation.afterText == afterText) {
                    operation.afterEntries = copied.map { it.copy() }
                    operation.afterEntriesText = afterText
                }
            }
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastSourceChangeAtMs = null
    }

    private fun isPureDeletion(before: String, after: String): Boolean {
        if (after.length >= before.length) return false
        var prefix = 0
        while (prefix < after.length && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < after.length - prefix &&
            before[before.length - 1 - suffix] == after[after.length - 1 - suffix]
        ) suffix++
        return prefix + suffix == after.length
    }

    private fun Operation.isNoOp(): Boolean = when (this) {
        is Operation.ListChange -> difference(before, after).isEmpty
        is Operation.SourceChange -> beforeText == afterText
    }

    companion object {
        private const val SOURCE_DELETE_MERGE_WINDOW_MS = 400L

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
