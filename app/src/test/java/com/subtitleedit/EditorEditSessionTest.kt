package com.subtitleedit

import com.subtitleedit.editor.EditorHistoryCoordinator
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.WebVttSubtitleFormatHandler
import org.junit.Assert.*
import org.junit.Test

class EditorEditSessionTest {
    @Test fun unchangedTextAndTimeDoNotPublishDirtyStateOrHistory() {
        val model = loaded(SRT, "sample.srt")
        val row = model.subtitleEntries.first()
        val document = model.document.value
        val baseline = model.historyEntriesSnapshot

        assertNull(model.commitSingleEdit(EditorEntryEdit.Text(row.stableId, row.text), emptySet()))
        assertNull(model.commitSingleEdit(EditorEntryEdit.Time(row.stableId, row.startTime, row.endTime), emptySet()))

        assertSame(document, model.document.value)
        assertSame(baseline, model.historyEntriesSnapshot)
        assertEquals(SRT, model.originalFileContent)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
    }

    @Test fun unchangedCueDoesNotPublishOrCreateHistory() {
        val model = loaded(VTT, "sample.vtt")
        val row = model.subtitleEntries.single()
        val document = model.document.value
        assertNull(model.commitSingleEdit(EditorEntryEdit.Cue(
            row.stableId, row.cueIdentifier, WebVttSubtitleFormatHandler.getCueSettings(row)
        ), emptySet()))
        assertSame(document, model.document.value)
        assertEquals(VTT, model.originalFileContent)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
    }

    @Test fun textCommitSynchronizesSourceSnapshotAndOneHistoryRecord() {
        val model = loaded(SRT, "sample.srt")
        val row = model.subtitleEntries.first()
        val selection = setOf(model.subtitleEntries.last().stableId)
        model.setHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, selection), SRT, true)
        val previous = model.document.value
        val change = model.commitSingleEdit(EditorEntryEdit.Text(row.stableId, "changed"), selection)

        assertEquals(EditorEntryChange(0, row.stableId), change)
        assertSame(row, model.subtitleEntries.first())
        assertEquals("changed", row.text)
        assertEquals(SRT.replace("first", "changed"), model.originalFileContent)
        assertEquals(model.originalFileContent, model.sourceViewContent)
        assertEquals(model.originalFileContent, model.sourceHistoryTextSnapshot)
        assertFalse(model.sourceViewNeedsListSync)
        assertTrue(model.hasUnsavedChanges)
        assertEquals("first", previous.entries.first().text)
        assertEquals("changed", model.document.value.entries.first().text)
        val operation = model.peekUndo() as EditorEditHistory.Operation.ListChange
        assertEquals(1, operation.updated.size)
        assertNotNull(operation.sourceEdit)
        assertTrue(operation.selected.isEmpty())
        assertTrue(operation.deselected.isEmpty())

        assertTrue(undo(model))
        assertEquals("first", row.text)
        assertEquals(SRT, model.sourceViewContent)
        assertEquals(selection, model.historySelectionSnapshot)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals("changed", row.text)
        assertEquals(SRT.replace("first", "changed"), model.originalFileContent)
        assertEquals(selection, model.historySelectionSnapshot)
        assertNull(model.peekRedo())
    }

    @Test fun timeCommitPreservesLrcLayoutAndRestoresEndMarkerState() {
        val source = "[ar:artist]\r\n[offset:15]\r\n\r\n[00:01.00]first\r\n[00:02.00]\r\n"
        val model = loaded(source, "sample.lrc")
        val row = model.subtitleEntries.single()
        val originalEnd = row.endTime
        val newEnd = originalEnd + 500L
        val originalEndModified = row.endTimeModified
        assertNotNull(model.commitSingleEdit(EditorEntryEdit.Time(row.stableId, endTime = newEnd), emptySet()))
        assertEquals(newEnd, row.endTime)
        assertTrue(row.endTimeModified)
        assertEquals(source.replace("[00:02.00]", "[00:02.50]"), model.sourceViewContent)
        assertTrue(undo(model))
        assertEquals(source, model.originalFileContent)
        assertEquals(originalEnd, row.endTime)
        assertEquals(originalEndModified, row.endTimeModified)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(newEnd, row.endTime)
        assertTrue(row.endTimeModified)
    }

    @Test fun cueCommitPreservesVttMetadataAndUndoRestoresAllFields() {
        val model = loaded(VTT, "sample.vtt")
        val row = model.subtitleEntries.single()
        val before = row.copy()
        val header = model.documentHeader
        val footer = model.documentFooter
        val options = "line:70% position:30% align:start region:r1"

        assertNotNull(model.commitSingleEdit(EditorEntryEdit.Cue(row.stableId, "changed-id", options), emptySet()))
        val changedSource = model.originalFileContent
        assertEquals("changed-id", row.cueIdentifier)
        assertEquals(options, row.cueSettings)
        assertTrue(changedSource.startsWith("\uFEFFWEBVTT\r\n"))
        assertTrue(changedSource.contains("STYLE\r\n::cue { color: lime; }\r\n"))
        assertTrue(changedSource.contains("NOTE retained footer\r\nleave this alone\r\n"))
        assertTrue(changedSource.contains("changed-id\r\n"))
        assertTrue(changedSource.contains(options))
        assertFalse(changedSource.replace("\r\n", "").contains('\n'))
        assertEquals(header, model.documentHeader)
        assertEquals(footer, model.documentFooter)
        assertEquals(row, model.document.value.entries.single())

        assertTrue(undo(model))
        assertEquals(before, row)
        assertEquals(VTT, model.originalFileContent)
        assertEquals(header, model.document.value.header)
        assertEquals(footer, model.document.value.footer)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(changedSource, model.sourceViewContent)
        assertEquals(options, row.cueSettings)
    }

    @Test fun removingDerivedVttAlignmentUpdatesTextAndSettingsInOneCommit() {
        val model = loaded(VTT, "sample.vtt")
        val row = model.subtitleEntries.single()
        assertTrue(row.text.startsWith("{\\an"))
        assertNotNull(model.commitSingleEdit(EditorEntryEdit.Text(row.stableId, "body"), emptySet()))
        assertEquals("body", row.text)
        assertEquals("region:r1", row.cueSettings)
        assertFalse(model.sourceViewContent.contains("position:10%"))
        assertTrue(model.sourceViewContent.contains("region:r1"))
        assertTrue(undo(model))
        assertEquals(VTT, model.sourceViewContent)
        assertTrue(row.text.startsWith("{\\an"))
    }

    @Test fun editFollowsCapturedStableIdAfterAReorder() {
        val model = loaded(SRT, "sample.srt")
        val captured = model.subtitleEntries.first().stableId
        val changedOrder = model.subtitleEntries.reversed().mapIndexed { index, row -> row.copy(index = index + 1) }
        model.subtitleEntries = changedOrder.toMutableList()
        model.originalFileContent = SubtitleParser.serialize(model.subtitleDocument)
        model.sourceViewContent = model.originalFileContent
        model.setHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, emptySet()), model.sourceViewContent, true)

        val change = model.commitSingleEdit(EditorEntryEdit.Text(captured, "target"), emptySet())
        assertEquals(1, change?.position)
        assertEquals("second", model.subtitleEntries.first().text)
        assertEquals("target", model.subtitleEntries.last().text)
    }

    @Test fun deletedRowIdDoesNotModifyAnotherRow() {
        val model = loaded(SRT, "sample.srt")
        val captured = model.subtitleEntries.removeAt(0).stableId
        val document = model.document.value
        assertNull(model.commitSingleEdit(EditorEntryEdit.Text(captured, "wrong row"), emptySet()))
        assertEquals("second", model.subtitleEntries.single().text)
        assertSame(document, model.document.value)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
    }

    @Test fun sourceModeAndModeTransitionRejectListEdits() {
        val model = loaded(SRT, "sample.srt")
        val edit = EditorEntryEdit.Text(model.subtitleEntries.first().stableId, "changed")
        model.isSourceViewMode = true
        assertNull(model.commitSingleEdit(edit, emptySet()))
        model.isSourceViewMode = false
        model.isSourceViewTransitioning = true
        assertNull(model.commitSingleEdit(edit, emptySet()))
        assertEquals(SRT, model.originalFileContent)
        assertEquals("first", model.subtitleEntries.first().text)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
    }

    @Test fun firstEditCreatesAnIndependentHistoryBaseline() {
        val model = EditorViewModel()
        model.loadSubtitleContent(SRT, "sample.srt")
        assertFalse(model.historyBaselineInitialized)
        assertNotNull(model.commitSingleEdit(EditorEntryEdit.Text(model.subtitleEntries.first().stableId, "changed"), emptySet()))
        assertTrue(model.historyBaselineInitialized)
        model.subtitleEntries.indices.forEach {
            assertNotSame(model.subtitleEntries[it], model.historyEntriesSnapshot[it])
        }
        assertTrue(undo(model))
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
    }

    @Test fun legacyHistoryRecorderDoesNotRecordTheCommittedChangeAgain() {
        val model = loaded(SRT, "sample.srt")
        val selected = setOf(model.subtitleEntries.first().stableId)
        model.setHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, selected), SRT, true)
        assertNotNull(model.commitSingleEdit(EditorEntryEdit.Text(selected.single(), "changed"), selected))
        val operation = model.peekUndo()
        EditorHistoryCoordinator(model, model.documentState, { selected }, { false }, { _, _ -> }, {}).recordList()
        assertSame(operation, model.peekUndo())
        assertTrue(undo(model))
        assertNull(model.peekUndo())
    }

    @Test fun invalidCueIsRejectedBeforeLiveStateChanges() {
        val model = loaded(VTT, "sample.vtt")
        val row = model.subtitleEntries.single()
        val before = row.copy()
        val document = model.document.value
        assertTrue(runCatching {
            model.commitSingleEdit(EditorEntryEdit.Cue(row.stableId, "id", "invalid:value"), emptySet())
        }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, row)
        assertEquals(VTT, model.originalFileContent)
        assertSame(document, model.document.value)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
    }

    @Test fun singleCommitAndLegacyBatchShareSourceAndUndoOrder() {
        val model = loaded(SRT, "sample.srt")
        val first = model.subtitleEntries.first().stableId
        assertNotNull(model.commitSingleEdit(EditorEntryEdit.Text(first, "single"), emptySet()))
        val singleSource = model.originalFileContent
        model.executeWithoutPublish(EditorCommand.UpdateTexts(listOf(1 to "batch"), removeBlankEntries = false))
        val recorder = EditorHistoryCoordinator(model, model.documentState, { emptySet() }, { false }, { _, _ -> }, {})
        recorder.recordList()
        val batchSource = model.originalFileContent
        assertEquals(SRT.replace("first", "single").replace("second", "batch"), batchSource)

        assertTrue(undo(model))
        assertEquals(listOf("single", "second"), model.subtitleEntries.map { it.text })
        assertEquals(singleSource, model.originalFileContent)
        assertTrue(undo(model))
        assertEquals(listOf("first", "second"), model.subtitleEntries.map { it.text })
        assertEquals(SRT, model.originalFileContent)
        assertNull(model.peekUndo())
        assertTrue(redo(model))
        assertEquals(singleSource, model.sourceViewContent)
        assertTrue(redo(model))
        assertEquals(batchSource, model.sourceViewContent)
        assertNull(model.peekRedo())
    }

    @Test fun largeDocumentReusesUntouchedHistoryAndDocumentRows() {
        val source = buildString {
            repeat(5_000) { index ->
                append(index + 1).append('\n')
                append("00:00:01,000 --> 00:00:02,000\nrow ").append(index).append("\n\n")
            }
        }
        val model = loaded(source, "large.srt")
        val position = 2_500
        val live = model.subtitleEntries[position]
        val oldDocument = model.document.value
        val oldHistoryRows = model.historyEntriesSnapshot.toList()
        assertNotNull(model.commitSingleEdit(EditorEntryEdit.Text(live.stableId, "changed"), emptySet()))
        assertSame(live, model.subtitleEntries[position])
        model.subtitleEntries.indices.forEach { index ->
            if (index == position) {
                assertNotSame(oldDocument.entries[index], model.document.value.entries[index])
                assertNotSame(oldHistoryRows[index], model.historyEntriesSnapshot[index])
            } else {
                assertSame(oldDocument.entries[index], model.document.value.entries[index])
                assertSame(oldHistoryRows[index], model.historyEntriesSnapshot[index])
            }
        }
        assertEquals("row $position", oldDocument.entries[position].text)
        assertEquals("row $position", oldHistoryRows[position].text)
        assertEquals(1, (model.peekUndo() as EditorEditHistory.Operation.ListChange).updated.size)
    }

    private fun loaded(source: String, fileName: String) = EditorViewModel().apply {
        loadSubtitleContent(source, fileName)
        setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
    }

    private fun undo(model: EditorViewModel): Boolean = moveHistory(model, true)
    private fun redo(model: EditorViewModel): Boolean = moveHistory(model, false)

    private fun moveHistory(model: EditorViewModel, undo: Boolean): Boolean {
        var result: EditorHistoryCommandResult? = null
        val apply: (EditorEditHistory.Operation, Boolean) -> Unit = { command, reversed ->
            result = model.executeHistoryCommand(command, reversed)
        }
        val applied = if (undo) model.undo(false, apply) else model.redo(false, apply)
        result?.let {
            model.syncHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, it.selectedIds), model.sourceViewContent, false)
        }
        return applied
    }

    private companion object {
        const val SRT = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nfirst\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nsecond\r\n"
        const val VTT = "\uFEFFWEBVTT\r\n\r\nSTYLE\r\n::cue { color: lime; }\r\n\r\ncue-one\r\n00:01.000 --> 00:02.000 position:10% line:10% region:r1\r\nbody\r\n\r\nNOTE retained footer\r\nleave this alone\r\n"
    }
}
