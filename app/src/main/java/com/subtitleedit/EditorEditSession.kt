package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.usecase.ApplySubtitleEditUseCase
import com.subtitleedit.usecase.SyncSourceDocumentUseCase

/** Single-row edits address the row captured by the UI, even if its position changes. */
internal sealed interface EditorEntryEdit {
    val stableId: Long
    fun command(position: Int): EditorCommand

    data class Text(override val stableId: Long, val text: String) : EditorEntryEdit {
        override fun command(position: Int) = EditorCommand.UpdateText(position, text)
    }

    data class Time(
        override val stableId: Long,
        val startTime: Long? = null,
        val endTime: Long? = null
    ) : EditorEntryEdit {
        override fun command(position: Int) = EditorCommand.UpdateTime(position, startTime, endTime)
    }

    data class Cue(override val stableId: Long, val identifier: String, val settings: String) : EditorEntryEdit {
        override fun command(position: Int) = EditorCommand.UpdateCue(position, identifier, settings)
    }
}

/** A committed change; the host only renders it and never finishes a partial edit. */
internal data class EditorEntryChange(val position: Int, val stableId: Long)

/** Commits a row, raw source, dirty state and history through one synchronous boundary. */
internal class EditorEditSession(
    private val state: EditorDocumentState,
    private val recordHistory: (
        before: EditorEditHistory.ListState,
        after: EditorEditHistory.ListState,
        beforeSource: String,
        afterSource: String
    ) -> Unit,
    private val synchronizeSource: SyncSourceDocumentUseCase = SyncSourceDocumentUseCase()
) {
    private val applyEdit = ApplySubtitleEditUseCase()

    fun commit(edit: EditorEntryEdit, selectedIds: Set<Long>): EditorEntryChange? {
        val position = state.subtitleEntries.indexOfFirst { it.stableId == edit.stableId }
        if (position < 0) return null
        val current = state.subtitleEntries[position]
        // Prepare all fallible formatting/source work before touching the live row.
        val prepared = EditorDocumentState().apply {
            currentFormat = state.currentFormat
            subtitleEntries = mutableListOf(current.copy())
        }
        applyEdit(prepared, edit.command(0))
        val updated = prepared.subtitleEntries.single()
        if (updated == current) return null

        val afterEntries = object : AbstractList<SubtitleEntry>() {
            override val size: Int get() = state.subtitleEntries.size
            override fun get(index: Int) = if (index == position) updated else state.subtitleEntries[index]
        }
        val beforeSource = state.originalFileContent
        val afterSource = synchronizeSource(beforeSource, state.currentFormat, state.subtitleEntries, afterEntries)
        val baseline = state.historyEntriesSnapshot.takeIf { entries ->
            state.historyBaselineInitialized && entries.size == state.subtitleEntries.size &&
                entries.indices.all { entries[it] == state.subtitleEntries[it] && entries[it] !== state.subtitleEntries[it] }
        } ?: state.subtitleEntries.map { it.copy() }.toMutableList()
        val before = EditorEditHistory.ListState(
            baseline,
            if (state.historyBaselineInitialized) state.historySelectionSnapshot else selectedIds.toSet()
        )

        recordHistory(before, EditorEditHistory.ListState(afterEntries, selectedIds.toSet()), beforeSource, afterSource)
        EditorDocumentOperations.updateFields(current, updated)
        state.originalFileContent = afterSource
        state.sourceViewContent = afterSource
        state.sourceViewNeedsListSync = false
        state.sourceHistoryTextSnapshot = afterSource
        state.historyBaselineInitialized = true
        state.hasUnsavedChanges = true
        return EditorEntryChange(position, current.stableId)
    }
}
