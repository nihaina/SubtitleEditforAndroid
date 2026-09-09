package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry

internal interface EditorHistoryCommand {
    val description: String

    fun isSelectionOnly(): Boolean

    fun execute(state: EditorDocumentState, undo: Boolean): EditorHistoryCommandResult
}

internal data class EditorHistoryCommandResult(
    val entries: List<SubtitleEntry>,
    val sourceText: String?,
    val selectedIds: Set<Long>,
    val changedPositions: Set<Int> = emptySet(),
    val structureChanged: Boolean = false,
    val entriesResolved: Boolean = true
)
