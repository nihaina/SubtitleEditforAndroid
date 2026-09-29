package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry

internal sealed interface EditorCommand {
    fun execute(state: EditorDocumentState): EditorCommandResult

    data class UpdateText(
        val position: Int,
        val text: String
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            val entry = state.subtitleEntries.getOrNull(position) ?: return EditorCommandResult()
            if (entry.text == text) return EditorCommandResult()
            entry.text = text
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
            startTime?.let { entry.startTime = it }
            endTime?.let {
                entry.endTime = it
                entry.endTimeModified = true
            }
            return EditorCommandResult(changedPositions = setOf(position))
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
                val entry = state.subtitleEntries[position]
                entry.startTime += offsetMs
                entry.endTime += offsetMs
            }
            return EditorCommandResult(changedPositions = validPositions)
        }
    }

    data class UpdateTexts(
        val updates: List<Pair<Int, String>>
    ) : EditorCommand {
        override fun execute(state: EditorDocumentState): EditorCommandResult {
            val validUpdates = linkedMapOf<Int, String>()
            updates.forEach { (position, text) ->
                if (position in state.subtitleEntries.indices) validUpdates[position] = text
            }
            if (validUpdates.isEmpty()) return EditorCommandResult()

            val removedPositions = validUpdates
                .filterValues { it.isBlank() }
                .keys
                .sortedDescending()
            validUpdates
                .filterValues { it.isNotBlank() }
                .forEach { (position, text) -> state.subtitleEntries[position].text = text }
            removedPositions.forEach { state.subtitleEntries.removeAt(it) }
            if (removedPositions.isNotEmpty()) {
                state.subtitleEntries.forEachIndexed { index, entry -> entry.index = index + 1 }
            }
            return EditorCommandResult(
                changedPositions = validUpdates.keys,
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
            validPositions.sortedDescending().forEach { state.subtitleEntries.removeAt(it) }
            return EditorCommandResult(
                changedPositions = validPositions,
                structureChanged = validPositions.isNotEmpty()
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
            val merged = sourceEntries.first()
            merged.startTime = sourceEntries.minOf { it.startTime }
            merged.endTime = sourceEntries.maxOf { it.endTime }
            merged.text = sourceEntries.joinToString("\n") { it.text }
            merged.endTimeModified = sourceEntries.any { it.endTimeModified }

            validPositions.drop(1).sortedDescending().forEach { position ->
                state.subtitleEntries.removeAt(position)
            }
            state.subtitleEntries.forEachIndexed { index, entry -> entry.index = index + 1 }
            return EditorCommandResult(
                changedPositions = validPositions.toSet(),
                structureChanged = true,
                removedCount = validPositions.size - 1
            )
        }
    }

    /** Split one cue at a playback position while keeping the left cue's stable identity. */
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
            val originalEnd = entry.endTime
            val originalEndModified = entry.endTimeModified
            entry.endTime = splitTime
            entry.endTimeModified = true
            entry.text = leftText
            val right = SubtitleEntry(
                index = position + 2,
                startTime = splitTime,
                endTime = originalEnd,
                text = rightText,
                endTimeModified = originalEndModified
            )
            state.subtitleEntries.add(position + 1, right)
            state.subtitleEntries.forEachIndexed { index, subtitle -> subtitle.index = index + 1 }
            return EditorCommandResult(
                changedPositions = setOf(position, position + 1),
                structureChanged = true
            )
        }
    }
}

internal data class EditorCommandResult(
    val changedPositions: Set<Int> = emptySet(),
    val structureChanged: Boolean = false,
    val removedCount: Int = 0
)
