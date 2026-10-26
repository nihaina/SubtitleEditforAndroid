package com.subtitleedit.editor

import com.subtitleedit.EditorEditHistory
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSourceViewCoordinatorTest {
    @Test
    fun fullCueDeletionUsesThePatchAndCurrentRows() {
        val before = "1\n00:00:01,000 --> 00:00:02,000\nremove\n\n" +
            "2\n00:00:03,000 --> 00:00:04,000\nkeep\n"
        val after = "2\n00:00:03,000 --> 00:00:04,000\nkeep\n"
        val currentEntries = SubtitleParser.parseDocument(
            before,
            format = SubtitleParser.SubtitleFormat.SRT
        ).entries.toMutableList()
        val history = EditorEditHistory()
        history.record(
            EditorEditHistory.Operation.SourceChange(
                edit = EditorSourceTextEdit.between(before, after),
                description = "删除第一条"
            )
        )
        var appliedEntries: List<SubtitleEntry> = emptyList()
        var generationUpdated = false
        var pendingCleared = false
        val coordinator = EditorSourceViewCoordinator(
            format = { SubtitleParser.SubtitleFormat.SRT },
            peekUndo = { history.peekUndo() },
            entries = { currentEntries },
            applyEntries = { appliedEntries = it },
            setEntriesGeneration = { generationUpdated = true },
            clearPendingEdits = { pendingCleared = true }
        )

        assertTrue(coordinator.applyDeletionLocally(after))
        assertEquals(listOf("keep"), appliedEntries.map { it.text })
        assertTrue(generationUpdated)
        assertTrue(pendingCleared)
    }
}
