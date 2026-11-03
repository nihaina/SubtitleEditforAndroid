package com.subtitleedit

import com.subtitleedit.editor.EditorSourceTextEdit
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.usecase.ApplySubtitleEditUseCase
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleSourceSynchronizer
import java.util.IdentityHashMap

/** A structural splice, in the order in which the command applied it. */
internal data class EditorListRangeChange(val start: Int, val removedCount: Int, val inserted: List<SubtitleEntry>)

/** The UI consumes the same small changes used to commit the operation. */
internal data class EditorListChange(
    val result: EditorCommandResult,
    val selectedIds: Set<Long>,
    val ranges: List<EditorListRangeChange> = emptyList(),
    val updatedPositions: Set<Int> = result.changedPositions,
    val timeConflictPositions: Set<Int> = emptySet(),
    internal val copiedEntryCount: Int = 0
)

/**
 * Borrows untouched rows. Field edits allocate only writable rows; structure changes take a
 * shallow list and detach only rows whose fields or sequence numbers actually change.
 */
internal class EditorPreparedEntryList(private val source: List<SubtitleEntry>) : AbstractMutableList<SubtitleEntry>() {
    private var structure: MutableList<SubtitleEntry>? = null
    private val fields = mutableMapOf<Int, SubtitleEntry>()
    private val originals = IdentityHashMap<SubtitleEntry, SubtitleEntry>()
    private val owned = java.util.Collections.newSetFromMap(IdentityHashMap<SubtitleEntry, Boolean>())
    private val splices = mutableListOf<EditorListRangeChange>()
    private val timeConflictEntries = mutableMapOf<Long, SubtitleEntry>()
    var copiedEntryCount = 0
        private set
    val firstStructuralPosition: Int? get() = splices.minOfOrNull { it.start }

    override val size: Int get() = structure?.size ?: source.size
    override fun get(index: Int): SubtitleEntry = structure?.get(index) ?: fields[index] ?: source[index]

    fun editableEntry(index: Int): SubtitleEntry {
        val entry = get(index)
        if (entry in owned) return entry
        val copy = entry.copy()
        originals[copy] = entry
        owned.add(copy)
        copiedEntryCount++
        structure?.set(index, copy) ?: run { fields[index] = copy }
        return copy
    }

    fun renumberFrom(start: Int) {
        for (index in start.coerceAtLeast(0) until size) {
            if (get(index).index != index + 1) editableEntry(index).index = index + 1
        }
    }

    private fun mutableStructure(): MutableList<SubtitleEntry> = structure ?: source.toMutableList().also { list ->
        fields.forEach { (index, entry) -> list[index] = entry }
        fields.clear()
        structure = list
    }

    private fun recordSplice(splice: EditorListRangeChange) {
        // The splice is already applied. Both surviving neighbors now have a new adjacency.
        getOrNull(splice.start - 1)?.let { timeConflictEntries[it.stableId] = it }
        getOrNull(splice.start + splice.inserted.size)?.let { timeConflictEntries[it.stableId] = it }
        val previous = splices.lastOrNull()
        val combined = when {
            previous == null -> null
            previous.start + previous.inserted.size == splice.start -> previous.copy(
                removedCount = previous.removedCount + splice.removedCount,
                inserted = previous.inserted + splice.inserted
            )
            splice.start + splice.removedCount == previous.start -> splice.copy(
                removedCount = splice.removedCount + previous.removedCount,
                inserted = splice.inserted + previous.inserted
            )
            else -> null
        }
        if (combined == null) splices += splice
        else splices[splices.lastIndex] = combined
    }

    override fun add(index: Int, element: SubtitleEntry) {
        mutableStructure().add(index, element)
        owned.add(element)
        recordSplice(EditorListRangeChange(index, 0, listOf(element)))
    }

    override fun addAll(index: Int, elements: Collection<SubtitleEntry>): Boolean {
        if (elements.isEmpty()) return false
        val inserted = elements.toList()
        mutableStructure().addAll(index, inserted)
        owned.addAll(inserted)
        recordSplice(EditorListRangeChange(index, 0, inserted))
        return true
    }

    override fun removeAt(index: Int): SubtitleEntry {
        val removed = mutableStructure().removeAt(index)
        originals.remove(removed)
        recordSplice(EditorListRangeChange(index, 1, emptyList()))
        return removed
    }

    fun removeEntries(start: Int, endExclusive: Int) {
        if (start == endExclusive) return
        val list = mutableStructure()
        for (index in start until endExclusive) originals.remove(list[index])
        list.subList(start, endExclusive).clear()
        recordSplice(EditorListRangeChange(start, endExclusive - start, emptyList()))
    }

    override fun set(index: Int, element: SubtitleEntry): SubtitleEntry {
        val previous = get(index)
        if (previous === element) return previous
        mutableStructure()[index] = element
        originals.remove(previous)
        owned.add(element)
        recordSplice(EditorListRangeChange(index, 1, listOf(element)))
        return previous
    }

    /** Only an explicit whole-list replacement needs to associate an arbitrary new order. */
    fun replaceEntries(entries: List<SubtitleEntry>) {
        val byId = source.associateBy { it.stableId }
        val replacement = entries.map { entry ->
            val previous = byId[entry.stableId]
            if (previous == entry) previous!! else entry.copy().also { copy ->
                owned.add(copy)
                if (previous != null) {
                    originals[copy] = previous
                    copiedEntryCount++
                }
            }
        }
        var prefix = 0
        while (prefix < source.size && prefix < replacement.size &&
            source[prefix].stableId == replacement[prefix].stableId
        ) prefix++
        var suffix = 0
        while (suffix < source.size - prefix && suffix < replacement.size - prefix &&
            source[source.lastIndex - suffix].stableId == replacement[replacement.lastIndex - suffix].stableId
        ) suffix++
        val splice = if (prefix < source.size - suffix || prefix < replacement.size - suffix) {
            EditorListRangeChange(
                prefix, source.size - prefix - suffix,
                replacement.subList(prefix, replacement.size - suffix).toList()
            )
        } else null
        structure = replacement.toMutableList()
        fields.clear()
        if (splice != null) {
            recordSplice(splice)
            // Reordered surviving rows can keep their holders while their neighbors change.
            splice.inserted.forEach { timeConflictEntries[it.stableId] = it }
        }
    }

    fun hasChanges(): Boolean = splices.isNotEmpty() || originals.any { (updated, original) -> updated != original }

    fun changedFieldPositions(): Set<Int> = originals.mapNotNullTo(mutableSetOf()) { (updated, original) ->
        val position = updated.index - 1
        position.takeIf {
            position in indices && get(position) === updated &&
                (EditorEditHistory.hasEditableDifference(original, updated) ||
                    original.endTimeModified != updated.endTimeModified)
        }
    }

    /** Call after commitTo has updated the surviving suffix rows' final sequence numbers. */
    fun timeConflictPositions(committed: List<SubtitleEntry>): Set<Int> {
        val positions = timeConflictEntries.values.mapNotNullTo(mutableSetOf()) { entry ->
            (entry.index - 1).takeIf { committed.getOrNull(it)?.stableId == entry.stableId }
        }
        // The untouched prefix may retain non-sequential cue numbers from the source file.
        firstStructuralPosition?.minus(1)?.takeIf { it in committed.indices }?.let(positions::add)
        return positions
    }

    fun commitTo(target: MutableList<SubtitleEntry>): List<EditorListRangeChange> {
        originals.forEach { (updated, original) ->
            if (updated != original) {
                EditorDocumentOperations.updateFields(original, updated)
            }
        }
        return splices.map { splice ->
            val inserted = splice.inserted.map { originals[it] ?: it }
            if (splice.removedCount > 0) target.subList(splice.start, splice.start + splice.removedCount).clear()
            if (inserted.isNotEmpty()) target.addAll(splice.start, inserted)
            splice.copy(inserted = inserted)
        }
    }
}

/** Prepares batch/structural edits before committing source, rows and history together. */
internal class EditorListEditSession(
    private val state: EditorDocumentState,
    private val recordList: (EditorEditHistory.ListState, EditorEditHistory.ListState, String, String) -> Unit,
    private val recordSource: (EditorSourceTextEdit, String) -> Unit,
    private val synchronizeSource: (
        String, SubtitleParser.SubtitleFormat, List<SubtitleEntry>, List<SubtitleEntry>
    ) -> String = SubtitleSourceSynchronizer::apply
) {
    private val applyEdit = ApplySubtitleEditUseCase()

    fun commit(
        command: EditorCommand,
        selectedIds: Set<Long>,
        selectedAfter: Set<Long> = selectedIds,
        sourceMode: Boolean = false
    ): EditorListChange? {
        val current = state.subtitleEntries
        val entries = EditorPreparedEntryList(current)
        val prepared = EditorDocumentState().apply {
            currentFormat = state.currentFormat
            documentHeader = state.documentHeader
            documentFooter = state.documentFooter
            documentLrcVariant = state.documentLrcVariant
            subtitleEntries = entries
        }
        val result = applyEdit(prepared, command)
        if ((result.changedPositions.isEmpty() && !result.structureChanged) || !entries.hasChanges()) return null
        entries.firstStructuralPosition?.let(entries::renumberFrom)

        val beforeSource = state.originalFileContent
        val afterSource = synchronizeSource(beforeSource, state.currentFormat, current, entries)
        val selection = if (selectedAfter.isEmpty()) emptySet() else {
            val remainingIds = entries.mapTo(mutableSetOf()) { it.stableId }
            selectedAfter.filterTo(mutableSetOf()) { it in remainingIds }
        }
        if (sourceMode) {
            val edit = EditorSourceTextEdit.between(beforeSource, afterSource)
            // Source text is authoritative in this mode. Unsupported structural
            // writes must not install rows that saving/undo cannot reproduce.
            if (edit.isNoOp) return null
            recordSource(edit, beforeSource)
        } else {
            val baseline = state.historyEntriesSnapshot.takeIf {
                state.historyBaselineInitialized && it.size == current.size
            } ?: current.map { it.copy() }.toMutableList()
            recordList(
                EditorEditHistory.ListState(
                    baseline,
                    if (state.historyBaselineInitialized) state.historySelectionSnapshot else selectedIds.toSet()
                ),
                EditorEditHistory.ListState(entries, selection), beforeSource, afterSource
            )
        }

        val updatedPositions = entries.changedFieldPositions()
        val ranges = entries.commitTo(current)
        state.lastIndexedEntryCount = current.size
        state.originalFileContent = afterSource
        state.sourceViewContent = afterSource
        state.sourceHistoryTextSnapshot = afterSource
        state.sourceViewNeedsListSync = false
        state.historyBaselineInitialized = true
        state.hasUnsavedChanges = true
        if (sourceMode) {
            state.sourceViewEditGeneration++
            state.sourceViewEntriesGeneration = state.sourceViewEditGeneration
        }
        return EditorListChange(
            result, selection, ranges, updatedPositions,
            timeConflictPositions = entries.timeConflictPositions(current),
            copiedEntryCount = entries.copiedEntryCount
        )
    }
}
