package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class EditorTimingDragSessionTest {
    @Test fun previewsKeepSourceHistoryAndDocumentSnapshotUntilOneFinalCommit() {
        val model = loaded()
        val row = model.subtitleEntries.first()
        val original = row.copy()
        val snapshot = model.document.value
        val baseline = model.historyEntriesSnapshot.toList()
        assertTrue(model.beginWaveformDrag(1L, row.stableId, emptySet()))
        repeat(5) { index ->
            assertNotNull(model.updateWaveformDrag(1L, EditorTimingRange(1_100L + index * 20, 2_100L + index * 20, false)))
            assertNull(model.peekUndo())
            assertSame(snapshot, model.document.value)
            assertEquals(SRT, model.originalFileContent)
            assertFalse(model.hasUnsavedChanges)
            assertEquals(original, baseline.first())
        }

        val result = model.finishWaveformDrag(1L, emptySet())
        assertEquals(EditorEntryChange(0, row.stableId), result?.change)
        assertSame(row, model.subtitleEntries.first())
        assertEquals(1_180L, row.startTime)
        assertEquals(2_180L, row.endTime)
        assertEquals(model.originalFileContent, model.sourceViewContent)
        assertEquals(model.originalFileContent, model.sourceHistoryTextSnapshot)
        assertTrue(model.hasUnsavedChanges)
        assertFalse(model.sourceViewNeedsListSync)
        val history = model.peekUndo() as EditorEditHistory.Operation.ListChange
        assertEquals(1, history.updated.size)
        assertEquals(original, history.updated.single().before)
        assertEquals(row, model.document.value.entries.first())
        assertEquals(original, snapshot.entries.first())

        assertTrue(undo(model))
        assertEquals(original, row)
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(1_180L, row.startTime)
        assertEquals(2_180L, row.endTime)
        assertNull(model.peekRedo())
    }

    @Test fun finishingWithoutMoveDoesNotDirtyPublishOrRecordHistory() {
        val model = loaded()
        val row = model.subtitleEntries.first()
        val snapshot = model.document.value
        assertTrue(model.beginWaveformDrag(2L, row.stableId, emptySet()))
        assertNull(model.finishWaveformDrag(2L, emptySet())?.change)
        assertEquals(SRT, model.originalFileContent)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
        assertSame(snapshot, model.document.value)
    }

    @Test fun movingBackToInitialRangeRestoresNoOpAndEndMarkerFlag() {
        val model = loaded()
        val row = model.subtitleEntries.first()
        val original = row.copy()
        val snapshot = model.document.value
        assertFalse(original.endTimeModified)
        assertTrue(model.beginWaveformDrag(3L, row.stableId, emptySet()))
        model.updateWaveformDrag(3L, EditorTimingRange(1_000L, 2_400L, true))
        assertTrue(row.endTimeModified)
        model.updateWaveformDrag(3L, EditorTimingRange(original.startTime, original.endTime, true))
        assertNull(model.finishWaveformDrag(3L, emptySet())?.change)
        assertEquals(original, row)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
        assertSame(snapshot, model.document.value)
    }

    @Test fun clampedResizeWithNoTimingChangeDoesNotChangeEndMarkerFlag() {
        val model = loaded()
        val row = model.subtitleEntries.first()
        assertTrue(model.beginWaveformDrag(4L, row.stableId, emptySet()))
        assertNotNull(model.updateWaveformDrag(4L, EditorTimingRange(row.startTime, row.endTime, true)))
        assertFalse(row.endTimeModified)
        assertNull(model.finishWaveformDrag(4L, emptySet())?.change)
        assertNull(model.peekUndo())
    }

    @Test fun resizingEndCommitsAndUndoRestoresOriginalEndMarkerFlag() {
        val model = loaded()
        val row = model.subtitleEntries.first()
        assertTrue(model.beginWaveformDrag(5L, row.stableId, emptySet()))
        model.updateWaveformDrag(5L, EditorTimingRange(row.startTime, 2_500L, true))
        assertNotNull(model.finishWaveformDrag(5L, emptySet())?.change)
        assertTrue(row.endTimeModified)
        assertTrue(undo(model))
        assertEquals(2_000L, row.endTime)
        assertFalse(row.endTimeModified)
        assertTrue(redo(model))
        assertEquals(2_500L, row.endTime)
        assertTrue(row.endTimeModified)
    }

    @Test fun movingAndResizingStartPreserveOriginalEndMarkerFlag() {
        val model = loaded()
        val row = model.subtitleEntries.first()
        assertTrue(model.beginWaveformDrag(6L, row.stableId, emptySet()))
        model.updateWaveformDrag(6L, EditorTimingRange(1_200L, 2_200L, false))
        assertNotNull(model.finishWaveformDrag(6L, emptySet())?.change)
        assertFalse(row.endTimeModified)
        assertTrue(model.beginWaveformDrag(7L, row.stableId, emptySet()))
        model.updateWaveformDrag(7L, EditorTimingRange(1_400L, 2_200L, false))
        assertNotNull(model.finishWaveformDrag(7L, emptySet())?.change)
        assertFalse(row.endTimeModified)
        assertTrue(undo(model))
        assertEquals(1_200L, row.startTime)
        assertEquals(2_200L, row.endTime)
        assertTrue(undo(model))
        assertEquals(1_000L, row.startTime)
        assertEquals(2_000L, row.endTime)
        assertNull(model.peekUndo())
    }

    @Test fun duplicateFinishAndLateEventsCannotChangeNextGesture() {
        val model = loaded()
        val row = model.subtitleEntries.first()
        assertTrue(model.beginWaveformDrag(8L, row.stableId, emptySet()))
        assertFalse(model.beginWaveformDrag(9L, row.stableId, emptySet()))
        model.updateWaveformDrag(8L, EditorTimingRange(1_100L, 2_100L, false))
        assertNotNull(model.finishWaveformDrag(8L, emptySet())?.change)
        val firstHistory = model.peekUndo()
        assertNull(model.finishWaveformDrag(8L, emptySet()))
        assertSame(firstHistory, model.peekUndo())
        assertTrue(model.beginWaveformDrag(9L, row.stableId, emptySet()))
        assertNull(model.updateWaveformDrag(8L, EditorTimingRange(7_000L, 8_000L, true)))
        assertNull(model.finishWaveformDrag(8L, emptySet()))
        assertEquals(1_100L, row.startTime)
        model.updateWaveformDrag(9L, EditorTimingRange(1_200L, 2_200L, false))
        assertNotNull(model.finishWaveformDrag(9L, emptySet())?.change)
        assertTrue(undo(model))
        assertEquals(1_100L, row.startTime)
        assertTrue(undo(model))
        assertEquals(1_000L, row.startTime)
        assertNull(model.peekUndo())
    }

    @Test fun replacingDocumentRejectsOldDragUpdatesAndFinish() {
        val model = loaded()
        val oldId = model.subtitleEntries.first().stableId
        assertTrue(model.beginWaveformDrag(10L, oldId, emptySet()))
        model.updateWaveformDrag(10L, EditorTimingRange(1_100L, 2_100L, false))
        model.loadSubtitleContent(REPLACEMENT, "replacement.srt")
        model.setHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, emptySet()), REPLACEMENT, true)
        val snapshot = model.document.value
        assertNull(model.updateWaveformDrag(10L, EditorTimingRange(9_000L, 10_000L, true)))
        assertNull(model.finishWaveformDrag(10L, emptySet()))
        assertEquals(REPLACEMENT, model.originalFileContent)
        assertSame(snapshot, model.document.value)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
        assertTrue(model.beginWaveformDrag(11L, model.subtitleEntries.first().stableId, emptySet()))
        assertNull(model.finishWaveformDrag(11L, emptySet())?.change)
    }

    @Test fun transitionRejectsBeginAndMissingStableIdDoesNotOpenSession() {
        val model = loaded()
        model.isSourceViewTransitioning = true
        assertFalse(model.beginWaveformDrag(12L, model.subtitleEntries.first().stableId, emptySet()))
        model.isSourceViewTransitioning = false
        assertFalse(model.beginWaveformDrag(12L, Long.MIN_VALUE, emptySet()))
        assertTrue(model.beginWaveformDrag(12L, model.subtitleEntries.first().stableId, emptySet()))
        assertNull(model.finishWaveformDrag(12L, emptySet())?.change)
    }

    @Test fun firstRoundEditDragAndSecondEditPreserveUndoOrder() {
        val model = loaded()
        val first = model.subtitleEntries.first()
        assertNotNull(model.commitSingleEdit(EditorEntryEdit.Text(first.stableId, "edited text"), emptySet()))
        val firstSource = model.originalFileContent
        assertTrue(model.beginWaveformDrag(13L, first.stableId, emptySet()))
        model.updateWaveformDrag(13L, EditorTimingRange(1_100L, 2_100L, false))
        assertNotNull(model.finishWaveformDrag(13L, emptySet())?.change)
        val dragSource = model.originalFileContent
        assertNotNull(model.commitSingleEdit(EditorEntryEdit.Time(first.stableId, endTime = 2_500L), emptySet()))

        assertTrue(undo(model))
        assertEquals(dragSource, model.originalFileContent)
        assertEquals(2_100L, first.endTime)
        assertEquals("edited text", first.text)
        assertTrue(undo(model))
        assertEquals(firstSource, model.originalFileContent)
        assertEquals(1_000L, first.startTime)
        assertEquals(2_000L, first.endTime)
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertEquals("first", first.text)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertTrue(redo(model))
        assertTrue(redo(model))
        assertEquals("edited text", first.text)
        assertEquals(1_100L, first.startTime)
        assertEquals(2_500L, first.endTime)
        assertNull(model.peekRedo())
    }

    @Test fun largeDocumentPreviewsRetainAllOwnedSnapshotsAndOnlyCommitChangedRow() {
        val source = buildString {
            repeat(2_000) { index ->
                append(index + 1).append("\n00:00:01,000 --> 00:00:02,000\nrow ").append(index).append("\n\n")
            }
        }
        val model = loaded(source)
        val position = 1_000
        val row = model.subtitleEntries[position]
        val snapshot = model.document.value
        val historyRows = model.historyEntriesSnapshot.toList()
        assertTrue(model.beginWaveformDrag(14L, row.stableId, emptySet()))
        repeat(20) { index ->
            model.updateWaveformDrag(14L, EditorTimingRange(1_000L + index * 10, 2_000L + index * 10, false))
            assertSame(snapshot, model.document.value)
            assertSame(historyRows[position], model.historyEntriesSnapshot[position])
        }
        assertNotNull(model.finishWaveformDrag(14L, emptySet())?.change)
        model.subtitleEntries.indices.forEach { index ->
            if (index == position) {
                assertNotSame(snapshot.entries[index], model.document.value.entries[index])
                assertNotSame(historyRows[index], model.historyEntriesSnapshot[index])
            } else {
                assertSame(snapshot.entries[index], model.document.value.entries[index])
                assertSame(historyRows[index], model.historyEntriesSnapshot[index])
            }
        }
        assertEquals(1_000L, snapshot.entries[position].startTime)
        assertEquals(1_000L, historyRows[position].startTime)
        assertEquals(1, (model.peekUndo() as EditorEditHistory.Operation.ListChange).updated.size)
    }

    @Test fun sourcePreviewsOnlyRecordHistoryWhenFinalSourceIsAccepted() {
        val model = loaded()
        model.isSourceViewMode = true
        val row = model.subtitleEntries.first()
        val snapshot = model.document.value
        assertTrue(model.beginWaveformDrag(15L, row.stableId, emptySet()))
        val drag = model.waveformDrag(15L)!!
        val range = EditorTimingRange(1_100L, 2_100L, false)
        model.updateWaveformDrag(15L, range)
        val source = EditorTimingDragSession.sourceFor(drag, range, SRT)
        assertTrue(model.applyWaveformSource(drag, SRT, source, false))
        assertEquals(source, model.sourceViewContent)
        assertSame(snapshot, model.document.value)
        assertNull(model.peekUndo())
        assertNull(model.finishWaveformDrag(15L, emptySet())?.change)
        assertTrue(model.applyWaveformSource(drag, SRT, source, true))
        assertTrue(model.peekUndo() is EditorEditHistory.Operation.SourceChange)
        assertEquals(row, model.document.value.entries.first())
        val history = model.peekUndo()
        assertFalse(model.applyWaveformSource(drag, SRT, source, true))
        assertSame(history, model.peekUndo())
    }

    @Test fun discardedSourceDragCannotApplyStaleResult() {
        val model = loaded()
        model.isSourceViewMode = true
        val row = model.subtitleEntries.first()
        assertTrue(model.beginWaveformDrag(16L, row.stableId, emptySet()))
        val drag = model.waveformDrag(16L)!!
        val range = EditorTimingRange(1_200L, 2_200L, false)
        model.updateWaveformDrag(16L, range)
        assertTrue(model.discardWaveformSourceEdits())
        assertFalse(model.applyWaveformSource(drag, SRT, EditorTimingDragSession.sourceFor(drag, range, SRT), true))
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.finishWaveformDrag(16L, emptySet()))
        assertNull(model.peekUndo())
    }

    private fun loaded(source: String = SRT) = EditorViewModel().apply {
        loadSubtitleContent(source, "sample.srt")
        setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
    }

    private fun undo(model: EditorViewModel): Boolean = moveHistory(model, true)
    private fun redo(model: EditorViewModel): Boolean = moveHistory(model, false)

    private fun moveHistory(model: EditorViewModel, undo: Boolean): Boolean {
        var result: EditorHistoryCommandResult? = null
        val apply: (EditorEditHistory.Operation, Boolean) -> Unit = { command, reversed ->
            result = model.executeHistoryCommand(command, reversed)
        }
        val moved = if (undo) model.undo(false, apply) else model.redo(false, apply)
        result?.let {
            model.syncHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, it.selectedIds), model.sourceViewContent, false)
        }
        return moved
    }

    private companion object {
        const val SRT = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nfirst\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nsecond\r\n"
        const val REPLACEMENT = "1\n00:00:08,000 --> 00:00:09,000\nreplacement\n"
    }
}
