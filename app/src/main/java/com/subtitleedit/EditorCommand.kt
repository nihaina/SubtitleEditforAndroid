package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitlePasteOps
import com.subtitleedit.util.WebVttCuePolicy
import com.subtitleedit.util.subtitle.WebVttSubtitleFormatHandler

internal sealed interface EditorCommand {
    fun execute(state: EditorDocumentState): EditorCommandResult

    data class UpdateText(
        val position: Int,
        val text: String
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            val entry = state.subtitleEntries.getOrNull(position) ?: return EditorCommandResult()
            if (entry.text == text) return EditorCommandResult()
            editableEntry(state, position).text = text
            return EditorCommandResult(changedPositions = setOf(position))
        }
    }

    data class UpdateTime(
        val position: Int,
        val startTime: Long? = null,
        val endTime: Long? = null
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            val entry = state.subtitleEntries.getOrNull(position) ?: return EditorCommandResult()
            val changed = entry.startTime != (startTime ?: entry.startTime) ||
                entry.endTime != (endTime ?: entry.endTime)
            if (!changed) return EditorCommandResult()
            val editable = editableEntry(state, position)
            startTime?.let { editable.startTime = it }
            endTime?.let {
                editable.endTime = it
                editable.endTimeModified = true
            }
            return EditorCommandResult(changedPositions = setOf(position))
        }
    }

    data class UpdateCue(
        val position: Int,
        val identifier: String,
        val settings: String
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            if (state.currentFormat != SubtitleParser.SubtitleFormat.VTT) return EditorCommandResult()
            val entry = state.subtitleEntries.getOrNull(position) ?: return EditorCommandResult()
            val id = identifier.trim()
            val options = settings.trim()
            val error = WebVttCuePolicy.validate(id, options)
            require(error == null) { error.orEmpty() }
            val updated = entry.copy(cueIdentifier = id)
            WebVttSubtitleFormatHandler.updateCueSettings(updated, options)
            if (entry == updated) return EditorCommandResult()
            EditorDocumentOperations.updateFields(editableEntry(state, position), updated)
            return EditorCommandResult(changedPositions = setOf(position))
        }
    }

    /** Extend selected cues to the end of the previous cue or the start of the next cue. */
    data class ExtendToAdjacent(
        val positions: Set<Int>,
        val towardPrevious: Boolean
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            val validPositions = positions.filter { it in state.subtitleEntries.indices }.distinct()
            val updates = validPositions.mapNotNull { position ->
                if (towardPrevious) {
                    if (position == 0) null
                    else position to state.subtitleEntries[position - 1].endTime
                } else {
                    if (position + 1 >= state.subtitleEntries.size) null
                    else position to state.subtitleEntries[position + 1].startTime
                }
            }
            val changedPositions = updates.filter { (position, targetTime) ->
                val entry = state.subtitleEntries[position]
                if (towardPrevious) entry.startTime != targetTime else entry.endTime != targetTime
            }
            if (changedPositions.isEmpty()) return EditorCommandResult()
            changedPositions.forEach { (position, targetTime) ->
                val entry = editableEntry(state, position)
                if (towardPrevious) {
                    entry.startTime = targetTime
                } else {
                    entry.endTime = targetTime
                    entry.endTimeModified = true
                }
            }
            return EditorCommandResult(changedPositions = changedPositions.map { it.first }.toSet())
        }
    }

    data class ApplyOffset(
        val positions: Set<Int>,
        val offsetMs: Long
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            val validPositions = positions.filter { it in state.subtitleEntries.indices }.toSet()
            if (validPositions.isEmpty() || offsetMs == 0L) return EditorCommandResult()
            validPositions.forEach { position ->
                val entry = editableEntry(state, position)
                entry.startTime += offsetMs
                entry.endTime += offsetMs
            }
            return EditorCommandResult(changedPositions = validPositions)
        }
    }

    data class UpdateTexts(
        val updates: List<Pair<Int, String>>,
        val removeBlankEntries: Boolean = true
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            val validUpdates = linkedMapOf<Int, String>()
            updates.forEach { (position, text) ->
                if (position in state.subtitleEntries.indices) validUpdates[position] = text
            }
            if (validUpdates.isEmpty()) return EditorCommandResult()

            val removedPositions = if (removeBlankEntries) {
                validUpdates.filterValues { it.isBlank() }.keys.sortedDescending()
            } else {
                emptyList()
            }
            val changedPositions = validUpdates
                .filter { (_, text) -> !removeBlankEntries || text.isNotBlank() }
                .filter { (position, text) -> state.subtitleEntries[position].text != text }
                .keys
            changedPositions.forEach { position ->
                val entry = editableEntry(state, position)
                entry.text = validUpdates.getValue(position)
                // Preserve the original position until text and derived cue
                // placement have changed together. Removing blank cues below
                // can shift every later row.
                normalizeTextMetadata(state, entry)
            }
            removePositions(state, removedPositions)
            if (removedPositions.isNotEmpty()) {
                renumberFrom(state, removedPositions.last())
            }
            val ascendingRemoved = removedPositions.asReversed()
            return EditorCommandResult(
                changedPositions = changedPositions.mapTo(mutableSetOf()) { position ->
                    val precedingCount = ascendingRemoved.binarySearch(position).let { if (it >= 0) it else -it - 1 }
                    position - precedingCount
                },
                structureChanged = removedPositions.isNotEmpty(),
                removedCount = removedPositions.size
            )
        }
    }

    data class Delete(
        val positions: Set<Int>
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            val validPositions = positions.filter { it in state.subtitleEntries.indices }.toSet()
            removePositions(state, validPositions.sortedDescending())
            return EditorCommandResult(
                changedPositions = validPositions,
                structureChanged = validPositions.isNotEmpty(),
                removedCount = validPositions.size
            )
        }
    }

    data class Insert(
        val position: Int,
        val entries: List<SubtitleEntry>
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            if (entries.isEmpty()) return EditorCommandResult()
            val insertPosition = position.coerceIn(0, state.subtitleEntries.size)
            state.subtitleEntries.addAll(insertPosition, entries.map { it.copy() })
            return EditorCommandResult(
                changedPositions = (insertPosition until insertPosition + entries.size).toSet(),
                structureChanged = true
            )
        }
    }

    /** Replace the target text, inserting extra clipboard rows after it. */
    data class PasteAt(
        val position: Int,
        val texts: List<String>,
        val fallbackEntry: SubtitleEntry? = null
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            if (texts.isEmpty()) return EditorCommandResult()
            val target = state.subtitleEntries.getOrNull(position)?.copy()
                ?: fallbackEntry?.copy()?.takeIf { state.subtitleEntries.isEmpty() }
                ?: return EditorCommandResult()
            val restoredTarget = state.subtitleEntries.isEmpty()
            if (restoredTarget) state.subtitleEntries.add(target)
            val pastePosition = position.coerceIn(0, state.subtitleEntries.lastIndex)
            editableEntry(state, pastePosition)
            val result = SubtitlePasteOps.pasteAtPosition(state.subtitleEntries, pastePosition, texts)
            result.affectedPositions.forEach { normalizeTextMetadata(state, editableEntry(state, it)) }
            return EditorCommandResult(
                changedPositions = result.affectedPositions,
                structureChanged = restoredTarget || result.structureChanged
            )
        }
    }

    /** Fill selected rows in order, then insert surplus clipboard text after the last target. */
    data class PasteToSelection(
        val positions: List<Int>,
        val texts: List<String>
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            val targets = positions.distinct().sorted().filter { it in state.subtitleEntries.indices }
            if (targets.isEmpty() || texts.size < targets.size) {
                return EditorCommandResult()
            }
            targets.forEach { editableEntry(state, it) }
            val result = SubtitlePasteOps.pasteToSelection(state.subtitleEntries, targets, texts)
            result.affectedPositions.forEach { normalizeTextMetadata(state, editableEntry(state, it)) }
            return EditorCommandResult(
                changedPositions = result.affectedPositions,
                structureChanged = texts.size > targets.size
            )
        }
    }

    data class ReplaceEntries(val entries: List<SubtitleEntry>) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            if (state.subtitleEntries == entries) return EditorCommandResult()
            val before = state.subtitleEntries
            val beforeSize = before.size
            val structureChanged = before.size != entries.size || before.indices.any { index ->
                before[index].stableId != entries[index].stableId
            }
            val changedPositions = entries.indices.filterTo(mutableSetOf()) { index -> before.getOrNull(index) != entries[index] }
            val prepared = state.subtitleEntries as? EditorPreparedEntryList
            if (prepared != null) prepared.replaceEntries(entries)
            else state.subtitleEntries = entries.map { it.copy() }.toMutableList()
            return EditorCommandResult(
                changedPositions = changedPositions,
                structureChanged = structureChanged,
                removedCount = (beforeSize - entries.size).coerceAtLeast(0)
            )
        }
    }

    data class Merge(
        val positions: List<Int>
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            val validPositions = positions.distinct().sorted()
                .filter { it in state.subtitleEntries.indices }
            if (validPositions.size < 2 ||
                validPositions.zipWithNext().any { (left, right) -> right != left + 1 }
            ) return EditorCommandResult()

            val sourceEntries = validPositions.map { state.subtitleEntries[it] }
            val merged = sourceEntries.first().copyWithNewStableId().apply {
                startTime = sourceEntries.minOf { it.startTime }
                endTime = sourceEntries.maxOf { it.endTime }
                text = sourceEntries.joinToString("\n") { it.text }
                endTimeModified = sourceEntries.any { it.endTimeModified }
            }

            removePositions(state, validPositions.drop(1).asReversed())
            state.subtitleEntries[validPositions.first()] = merged
            renumberFrom(state, validPositions.first())
            return EditorCommandResult(
                changedPositions = validPositions.toSet(),
                structureChanged = true,
                removedCount = validPositions.size - 1
            )
        }
    }

    /** Split one cue at a playback position; both resulting cues are new subtitle rows. */
    data class Split(
        val position: Int,
        val splitTime: Long,
        val leftText: String,
        val rightText: String
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            val entry = state.subtitleEntries.getOrNull(position) ?: return EditorCommandResult()
            if (splitTime <= entry.startTime || splitTime >= entry.endTime) {
                return EditorCommandResult()
            }
            val left = entry.copyWithNewStableId().apply {
                endTime = splitTime
                endTimeModified = true
                text = leftText
            }
            val right = entry.copyWithNewStableId().apply {
                index = position + 2
                startTime = splitTime
                text = rightText
            }
            if (state.currentFormat == SubtitleParser.SubtitleFormat.VTT) {
                // The right text may no longer contain the derived placement prefix.
                // Keep it consistent with the inherited settings so saving preserves them.
                WebVttSubtitleFormatHandler.updateCueSettings(
                    right, WebVttSubtitleFormatHandler.getCueSettings(entry)
                )
            }
            state.subtitleEntries[position] = left
            state.subtitleEntries.add(position + 1, right)
            renumberFrom(state, position)
            return EditorCommandResult(
                changedPositions = setOf(position, position + 1),
                structureChanged = true
            )
        }
    }

    companion object {
        private fun removePositions(state: EditorDocumentState, descending: List<Int>) {
            if (descending.isEmpty()) return
            val prepared = state.subtitleEntries as? EditorPreparedEntryList
            fun removeRange(start: Int, end: Int) {
                if (prepared != null) prepared.removeEntries(start, end)
                else state.subtitleEntries.subList(start, end).clear()
            }
            var start = descending.first()
            var end = start + 1
            for (position in descending.drop(1)) {
                if (position == start - 1) start = position
                else {
                    removeRange(start, end)
                    start = position
                    end = position + 1
                }
            }
            removeRange(start, end)
        }

        private fun editableEntry(state: EditorDocumentState, position: Int): SubtitleEntry =
            (state.subtitleEntries as? EditorPreparedEntryList)?.editableEntry(position)
                ?: state.subtitleEntries[position]

        private fun renumberFrom(state: EditorDocumentState, start: Int) {
            // Prepared commands number the final suffix once, after every splice is known.
            if (state.subtitleEntries is EditorPreparedEntryList) return
            for (index in start.coerceAtLeast(0) until state.subtitleEntries.size) {
                state.subtitleEntries[index].index = index + 1
            }
        }

        private fun normalizeTextMetadata(state: EditorDocumentState, entry: SubtitleEntry) {
            if (state.currentFormat == SubtitleParser.SubtitleFormat.VTT) {
                entry.cueSettings = WebVttSubtitleFormatHandler.getCueSettings(entry)
            }
        }
    }
}

internal data class EditorCommandResult(
    val changedPositions: Set<Int> = emptySet(),
    val structureChanged: Boolean = false,
    val removedCount: Int = 0,
    val documentMetadataChanged: Boolean = false
)
