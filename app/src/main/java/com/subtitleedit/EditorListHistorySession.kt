package com.subtitleedit

import com.subtitleedit.EditorEditHistory.Operation.ListChange
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleSourceSynchronizer

/** Prepares a list-history result before installing any live row or raw-source changes. */
internal class EditorListHistorySession(
    private val state: EditorDocumentState,
    private val synchronizeSource: (
        String, SubtitleParser.SubtitleFormat, List<SubtitleEntry>, List<SubtitleEntry>
    ) -> String = SubtitleSourceSynchronizer::apply
) {
    fun execute(operation: ListChange, undo: Boolean): EditorHistoryCommandResult {
        val current = state.subtitleEntries
        val currentSelection = state.historySelectionSnapshot
        val selection = currentSelection - (if (undo) operation.selected else operation.deselected) +
            (if (undo) operation.deselected else operation.selected)
        if (operation.isSelectionOnly()) {
            return EditorHistoryCommandResult(current, state.sourceViewContent, selection)
        }
        val prepared = EditorPreparedEntryList(current)
        val targetOrder = if (undo) operation.beforeOrder else operation.afterOrder
        val structureChanged = operation.added.isNotEmpty() || operation.deleted.isNotEmpty() || targetOrder != null
        val targets = operation.updated.map { update -> if (undo) update.before else update.after }
        val originalUpdates = linkedMapOf<Long, SubtitleEntry>()
        val removeIds = (if (undo) operation.added else operation.deleted).mapTo(mutableSetOf()) { it.entry.stableId }
        val positioned = (if (undo) operation.deleted else operation.added).sortedBy { it.position }
        val boundaryIds = mutableSetOf<Long>()

        fun rememberBoundary(position: Int) {
            prepared.getOrNull(position)?.let { boundaryIds += it.stableId }
        }

        if (targetOrder == null) {
            // Address a surviving row by its expected current position first. A structural
            // history item can move it, so retain the stable-ID fallback used by the editor.
            operation.updated.forEachIndexed { targetIndex, update ->
                val target = targets[targetIndex]
                val expectedPosition = if (undo) update.afterPosition else update.beforePosition
                val position = findPosition(prepared, target.stableId, expectedPosition)
                if (position >= 0) {
                    val original = prepared[position]
                    originalUpdates[target.stableId] = original
                    if (original != target) {
                        EditorDocumentOperations.updateFields(prepared.editableEntry(position), target)
                    }
                }
            }
            if (removeIds.isNotEmpty()) {
                for (position in prepared.indices.reversed()) {
                    if (prepared[position].stableId in removeIds) {
                        rememberBoundary(position - 1)
                        rememberBoundary(position + 1)
                        prepared.removeAt(position)
                    }
                }
            }
            positioned.forEach { item ->
                val position = item.position.coerceIn(0, prepared.size)
                rememberBoundary(position - 1)
                rememberBoundary(position)
                prepared.add(position, item.entry.copy())
            }
        } else {
            // Explicit reordering is the one history operation that needs a new reference
            // order. Prepare it once so changed rows are not detached twice while reordering.
            val byId = current.associateByTo(linkedMapOf()) { it.stableId }
            targets.forEach { target ->
                byId[target.stableId]?.let { original ->
                    originalUpdates[target.stableId] = original
                    byId[target.stableId] = target
                }
            }
            removeIds.forEach(byId::remove)
            positioned.forEach { byId[it.entry.stableId] = it.entry }
            prepared.replaceEntries(targetOrder.mapNotNull { byId[it] })
            boundaryIds += targetOrder
        }
        if (structureChanged) {
            // A local insertion/removal only detaches rows whose suffix numbering changes.
            prepared.renumberFrom(prepared.firstStructuralPosition ?: 0)
        }

        val changedPositions = buildSet {
            (if (undo) operation.added else operation.deleted).forEach { add(it.position) }
            (if (undo) operation.deleted else operation.added).forEach { add(it.position) }
            operation.updated.forEach { add(if (undo) it.beforePosition else it.afterPosition) }
            targetOrder?.indices?.forEach { add(it) }
        }
        val updatedPositions = operation.updated.mapNotNullTo(mutableSetOf()) { update ->
            val target = if (undo) update.before else update.after
            val expectedPosition = if (undo) update.beforePosition else update.afterPosition
            val position = findPosition(prepared, target.stableId, expectedPosition)
            val original = originalUpdates[target.stableId]
            position.takeIf {
                it >= 0 && original != null &&
                    (EditorEditHistory.hasEditableDifference(original, prepared[it]) ||
                        original.endTimeModified != prepared[it].endTimeModified)
            }
        }
        val source = operation.sourceEdit?.let { edit ->
            runCatching { edit.applyTo(state.originalFileContent, undo) }
                .getOrElse { edit.applyTo(state.sourceViewContent, undo) }
        } ?: if (structureChanged || operation.updated.isNotEmpty()) {
            synchronizeSource(state.originalFileContent, state.currentFormat, current, prepared)
        } else state.originalFileContent

        val header = if (state.currentFormat == SubtitleParser.SubtitleFormat.VTT &&
            (operation.beforeHeader != null || operation.afterHeader != null)
        ) {
            if (undo) operation.beforeHeader.orEmpty() else operation.afterHeader.orEmpty()
        } else state.documentHeader
        val footer = if (state.currentFormat == SubtitleParser.SubtitleFormat.VTT &&
            (operation.beforeFooter != null || operation.afterFooter != null)
        ) {
            if (undo) operation.beforeFooter.orEmpty() else operation.afterFooter.orEmpty()
        } else state.documentFooter
        val metadataChanged = header != state.documentHeader || footer != state.documentFooter
        // Resolve after every splice: an earlier deletion may shift a later
        // boundary by many rows. Only time-color payloads are needed there.
        val timeConflictPositions = if (boundaryIds.isEmpty()) emptySet() else
            prepared.indices.filterTo(mutableSetOf()) { prepared[it].stableId in boundaryIds }
        // All source parsing/patch validation has succeeded. Install only the prepared fields
        // and splices, preserving the live list and surviving row objects used by the views.
        val ranges = prepared.commitTo(current)
        state.lastIndexedEntryCount = current.size
        state.originalFileContent = source
        state.sourceViewContent = source
        state.sourceHistoryTextSnapshot = source
        state.sourceViewNeedsListSync = false
        state.documentHeader = header
        state.documentFooter = footer
        return EditorHistoryCommandResult(
            entries = current,
            sourceText = source,
            selectedIds = selection,
            changedPositions = changedPositions,
            structureChanged = structureChanged,
            ranges = ranges,
            updatedPositions = updatedPositions,
            documentMetadataChanged = metadataChanged,
            timeConflictPositions = timeConflictPositions,
            copiedEntryCount = prepared.copiedEntryCount
        )
    }

    private fun findPosition(entries: List<SubtitleEntry>, stableId: Long, expectedPosition: Int): Int =
        expectedPosition.takeIf { entries.getOrNull(it)?.stableId == stableId }
            ?: entries.indexOfFirst { it.stableId == stableId }
}
