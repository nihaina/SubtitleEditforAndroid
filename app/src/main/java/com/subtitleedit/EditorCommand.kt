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
}

internal data class EditorCommandResult(
    val changedPositions: Set<Int> = emptySet(),
    val structureChanged: Boolean = false
)
