package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.*
import org.junit.Test

/** History commits must finish model state before the Activity consumes their render changes. */
class EditorHistoryCommitTest {
    @Test fun textUndoAndRedoSynchronizeSnapshotsAndHistoryWithoutAnExtraCallerSync() {
        val model = loaded()
        val first = model.subtitleEntries.first()
        val selected = setOf(first.stableId)
        model.setHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, selected), SRT, true)
        model.commitSingleEdit(EditorEntryEdit.Text(first.stableId, "edited"), selected)!!
        val afterSource = model.originalFileContent
        model.hasUnsavedChanges = false

        val undone = undo(model)

        assertEquals(setOf(0), undone.changedPositions)
        assertEquals(setOf(0), undone.updatedPositions)
        assertTrue(undone.ranges.isEmpty())
        assertEquals("first", first.text)
        assertEquals(SRT, model.originalFileContent)
        assertEquals(selected, model.historySelectionSnapshot)
        assertConsistent(model)
        assertNull(model.peekUndo())

        model.hasUnsavedChanges = false
        redo(model)

        assertEquals("edited", first.text)
        assertEquals(afterSource, model.originalFileContent)
        assertEquals(selected, model.historySelectionSnapshot)
        assertConsistent(model)
        assertNull(model.peekRedo())
    }

    @Test fun timeUndoRestoresThePublishedTimingAndEndModificationFlag() {
        val model = loaded()
        val row = model.subtitleEntries[1]
        val initial = row.copy()
        model.commitSingleEdit(EditorEntryEdit.Time(row.stableId, 3_200, 4_300), emptySet())!!
        assertTrue(row.endTimeModified)

        undo(model)

        assertEquals(initial, row)
        assertEquals(initial, model.document.value.entries[1])
        assertFalse(row.endTimeModified)
        assertConsistent(model)
        redo(model)
        assertEquals(3_200L, row.startTime)
        assertEquals(4_300L, row.endTime)
        assertTrue(row.endTimeModified)
        assertConsistent(model)
    }

    @Test fun aTwentyThousandRowUndoCopiesOnlyTheChangedRowAndReusesUntouchedSnapshots() {
        val model = loaded(largeSource(20_000))
        val target = 10_000
        model.commitSingleEdit(EditorEntryEdit.Text(model.subtitleEntries[target].stableId, "edited"), emptySet())!!
        val rows = model.subtitleEntries.toList()
        val liveList = model.subtitleEntries
        val history = model.historyEntriesSnapshot.toList()
        val document = model.document.value

        val result = undo(model)

        assertSame(liveList, model.subtitleEntries)
        assertEquals(1, result.copiedEntryCount)
        assertEquals(setOf(target), result.updatedPositions)
        assertTrue(result.ranges.isEmpty())
        rows.indices.forEach { position ->
            assertSame(rows[position], model.subtitleEntries[position])
            if (position == target) {
                assertNotSame(history[position], model.historyEntriesSnapshot[position])
                assertNotSame(document.entries[position], model.document.value.entries[position])
            } else {
                assertSame(history[position], model.historyEntriesSnapshot[position])
                assertSame(document.entries[position], model.document.value.entries[position])
            }
        }
        assertEquals("edited", document.entries[target].text)
        assertEquals("row $target", model.document.value.entries[target].text)
        assertConsistent(model)
    }

    @Test fun undoingAnAppendReturnsOnlyItsRemovalRangeAndReusesEveryExistingRow() {
        val model = loaded()
        val rows = model.subtitleEntries.toList()
        val list = model.subtitleEntries
        model.commitListEdit(EditorCommand.Insert(rows.size,
            listOf(SubtitleEntry(startTime = 7_000, endTime = 8_000, text = "appended"))), emptySet())!!

        val undone = undo(model)

        assertSame(list, model.subtitleEntries)
        assertEquals(listOf(EditorListRangeChange(3, 1, emptyList())), undone.ranges)
        assertTrue(undone.updatedPositions.isEmpty())
        assertEquals(0, undone.copiedEntryCount)
        rows.indices.forEach { assertSame(rows[it], model.subtitleEntries[it]) }
        assertEquals(SRT, model.originalFileContent)
        assertConsistent(model)

        val redone = redo(model)

        assertEquals(1, redone.ranges.size)
        assertEquals(3, redone.ranges.single().start)
        assertEquals(0, redone.ranges.single().removedCount)
        assertEquals("appended", redone.ranges.single().inserted.single().text)
        rows.indices.forEach { assertSame(rows[it], model.subtitleEntries[it]) }
        assertConsistent(model)
    }

    @Test fun undoingAMiddleDeletionReturnsTheInsertedRangeAndRetainsSurvivingObjects() {
        val model = loaded()
        val first = model.subtitleEntries[0]
        val deleted = model.subtitleEntries[1].copy()
        val third = model.subtitleEntries[2]
        val selected = setOf(first.stableId, deleted.stableId)
        model.setHistoryBaseline(EditorEditHistory.ListState(model.subtitleEntries, selected), SRT, true)
        model.commitListEdit(EditorCommand.Delete(setOf(1)), selected)!!

        val undone = undo(model)

        assertEquals(1, undone.ranges.size)
        assertEquals(1, undone.ranges.single().start)
        assertEquals(0, undone.ranges.single().removedCount)
        assertEquals(listOf(deleted), undone.ranges.single().inserted)
        assertTrue(undone.updatedPositions.isEmpty())
        assertSame(first, model.subtitleEntries[0])
        assertSame(third, model.subtitleEntries[2])
        assertEquals(selected, model.historySelectionSnapshot)
        assertEquals(listOf(1, 2, 3), model.subtitleEntries.map { it.index })
        assertEquals(SRT, model.originalFileContent)
        assertConsistent(model)

        val redone = redo(model)

        assertEquals(listOf(EditorListRangeChange(1, 1, emptyList())), redone.ranges)
        assertSame(first, model.subtitleEntries[0])
        assertSame(third, model.subtitleEntries[1])
        assertEquals(setOf(first.stableId), model.historySelectionSnapshot)
        assertEquals(listOf(1, 2), model.subtitleEntries.map { it.index })
        assertConsistent(model)
    }

    @Test fun separatedDeletionsCanBeReplayedFromTheReportedRanges() {
        val model = loaded(largeSource(10))
        val originalIds = model.subtitleEntries.map { it.stableId }
        model.commitListEdit(EditorCommand.Delete(setOf(2, 7)), emptySet())!!
        val reducedIds = model.subtitleEntries.map { it.stableId }

        val undone = undo(model)

        assertEquals(originalIds, replayRanges(reducedIds, undone.ranges))
        assertEquals(originalIds, model.subtitleEntries.map { it.stableId })
        assertConsistent(model)
        val redone = redo(model)
        assertEquals(reducedIds, replayRanges(originalIds, redone.ranges))
        assertEquals(reducedIds, model.subtitleEntries.map { it.stableId })
        assertConsistent(model)
    }

    @Test fun rowReorderingKeepsLiveObjectsAndReportsAReplayableReplacement() {
        val model = loaded()
        val rows = model.subtitleEntries.toList()
        val oldIds = rows.map { it.stableId }
        model.commitListEdit(EditorCommand.ReplaceEntries(listOf(rows[2], rows[0], rows[1])), emptySet())!!
        val reorderedIds = model.subtitleEntries.map { it.stableId }

        val undone = undo(model)

        assertEquals(oldIds, replayRanges(reorderedIds, undone.ranges))
        rows.indices.forEach { assertSame(rows[it], model.subtitleEntries[it]) }
        assertEquals(listOf(1, 2, 3), model.subtitleEntries.map { it.index })
        assertConsistent(model)
        val redone = redo(model)
        assertEquals(reorderedIds, replayRanges(oldIds, redone.ranges))
        assertSame(rows[2], model.subtitleEntries[0])
        assertSame(rows[0], model.subtitleEntries[1])
        assertSame(rows[1], model.subtitleEntries[2])
        assertConsistent(model)
    }

    @Test fun separatedDeletionBoundariesUseFinalPositionsForTimeColorPayloads() {
        val model = loaded(largeSource(12))
        model.commitListEdit(EditorCommand.Delete(setOf(2, 3, 4, 8)), emptySet())!!

        val restored = undo(model)

        assertTrue(restored.timeConflictPositions.containsAll(setOf(1, 5, 7, 9)))
        val deletedAgain = redo(model)
        assertEquals(setOf(1, 2, 4, 5), deletedAgain.timeConflictPositions)
        assertEquals(listOf("row 1", "row 5", "row 7", "row 9"),
            deletedAgain.timeConflictPositions.sorted().map { model.subtitleEntries[it].text })
        assertTrue(deletedAgain.updatedPositions.isEmpty())
        assertConsistent(model)
    }

    @Test fun vttHeaderAndFooterUndoPublishMetadataWithoutReplacingAnyCueSnapshot() {
        val model = loaded(VTT, "test.vtt")
        val before = model.subtitleDocument
        model.applyVttDocument(before.copy(
            header = before.header + "\n\nSTYLE\n::cue(.new) { color:red; }",
            footer = "NOTE replacement footer"
        ), emptySet(), "样式和注释")
        val published = model.document.value
        val afterSource = model.originalFileContent

        val undone = undo(model)

        assertTrue(undone.documentMetadataChanged)
        assertFalse(undone.structureChanged)
        assertTrue(undone.changedPositions.isEmpty())
        assertTrue(undone.updatedPositions.isEmpty())
        assertTrue(undone.ranges.isEmpty())
        assertEquals(0, undone.copiedEntryCount)
        assertSame(published.entries, model.document.value.entries)
        assertEquals(before.header, model.documentHeader)
        assertEquals(before.footer, model.documentFooter)
        assertEquals(VTT, model.originalFileContent)
        assertConsistent(model)

        val redone = redo(model)

        assertTrue(redone.documentMetadataChanged)
        assertEquals(afterSource, model.originalFileContent)
        assertEquals(published, model.document.value)
        assertConsistent(model)
    }

    @Test fun mixedRowStructureAndVttStyleHistoryUseOneConsistentUndoOrder() {
        val model = loaded(VTT, "test.vtt")
        val initial = model.subtitleDocument
        val first = model.subtitleEntries.first()
        model.commitSingleEdit(EditorEntryEdit.Text(first.stableId, "edited body"), emptySet())!!
        val afterText = model.originalFileContent
        model.commitSingleEdit(EditorEntryEdit.Time(first.stableId, 1_100, 2_100), emptySet())!!
        val afterTime = model.originalFileContent
        model.applyVttEdit(EditorVttEdit.SaveStyle(null, "new", mapOf("color" to "red")), emptySet())
        val afterStyle = model.originalFileContent
        model.commitListEdit(EditorCommand.Delete(setOf(1)), emptySet())!!
        val final = model.subtitleDocument
        val finalSource = model.originalFileContent

        undo(model)
        assertEquals(afterStyle, model.originalFileContent)
        undo(model)
        assertEquals(afterTime, model.originalFileContent)
        undo(model)
        assertEquals(afterText, model.originalFileContent)
        undo(model)
        assertEquals(VTT, model.originalFileContent)
        assertEquals(initial, model.subtitleDocument)
        assertNull(model.peekUndo())
        repeat(4) { redo(model) }
        assertEquals(finalSource, model.originalFileContent)
        assertEquals(final, model.subtitleDocument)
        assertNull(model.peekRedo())
    }

    @Test fun failedTextUndoCannotPartiallyRestoreTheLiveRowOrMoveItsHistory() {
        val model = loaded()
        model.commitSingleEdit(EditorEntryEdit.Text(model.subtitleEntries[0].stableId, "edited"), emptySet())!!
        assertFailedMoveIsAtomic(model, undo = true)
        assertEquals("edited", model.subtitleEntries[0].text)
    }

    @Test fun failedInsertionUndoCannotRemoveRowsOrRenumberTheirSurvivors() {
        val model = loaded()
        model.commitListEdit(EditorCommand.Insert(1,
            listOf(SubtitleEntry(startTime = 2_100, endTime = 2_900, text = "inserted"))), emptySet())!!
        assertFailedMoveIsAtomic(model, undo = true)
        assertEquals(4, model.subtitleEntries.size)
        assertEquals("inserted", model.subtitleEntries[1].text)
    }

    @Test fun failedDeletionUndoCannotInsertRowsBeforeTheSourcePatchSucceeds() {
        val model = loaded()
        model.commitListEdit(EditorCommand.Delete(setOf(1)), emptySet())!!
        assertFailedMoveIsAtomic(model, undo = true)
        assertEquals(listOf("first", "third"), model.subtitleEntries.map { it.text })
    }

    @Test fun failedVttColorUndoKeepsCueFieldsHeaderAndFooterUntouched() {
        val model = loaded(VTT, "test.vtt")
        model.applySubtitleColor(setOf(0), 0xFFFF0000.toInt(), emptySet())
        assertFailedMoveIsAtomic(model, undo = true)
        assertTrue(model.subtitleEntries[0].text.contains("<c."))
        assertTrue(model.documentHeader.contains("color:rgb(255,0,0)"))
    }

    @Test fun failedRedoKeepsTheRedoOperationAndTheUndoneDocument() {
        val model = loaded()
        model.commitSingleEdit(EditorEntryEdit.Text(model.subtitleEntries[0].stableId, "edited"), emptySet())!!
        undo(model)
        assertFailedMoveIsAtomic(model, undo = false)
        assertEquals("first", model.subtitleEntries[0].text)
    }

    @Test fun aNewEditAfterUndoReplacesTheRedoBranchWithoutASeparateBaselineSync() {
        val model = loaded()
        val first = model.subtitleEntries[0]
        model.commitSingleEdit(EditorEntryEdit.Text(first.stableId, "old branch"), emptySet())!!
        undo(model)
        assertNotNull(model.peekRedo())

        model.commitSingleEdit(EditorEntryEdit.Text(first.stableId, "new branch"), emptySet())!!

        assertNull(model.peekRedo())
        undo(model)
        assertEquals("first", first.text)
        assertNull(model.peekUndo())
        redo(model)
        assertEquals("new branch", first.text)
        assertConsistent(model)
    }

    @Test fun selectionOnlyHistoryDoesNotDirtyOrRepublishTheSubtitleDocument() {
        val model = loaded()
        val document = model.document.value
        val history = model.historyEntriesSnapshot
        val selection = setOf(model.subtitleEntries[1].stableId)
        recordSelection(model, selection)
        assertFalse(model.hasUnsavedChanges)

        val undone = undo(model, assertDirty = false)

        assertTrue(undone.changedPositions.isEmpty())
        assertTrue(undone.updatedPositions.isEmpty())
        assertTrue(undone.ranges.isEmpty())
        assertFalse(undone.documentMetadataChanged)
        assertEquals(emptySet<Long>(), model.historySelectionSnapshot)
        assertFalse(model.hasUnsavedChanges)
        assertSame(document, model.document.value)
        assertSame(history, model.historyEntriesSnapshot)
        redo(model, assertDirty = false)
        assertEquals(selection, model.historySelectionSnapshot)
        assertFalse(model.hasUnsavedChanges)
        assertSame(document, model.document.value)
    }

    @Test fun sourceModeStillSkipsSelectionOnlyOperationsWhenMovingListHistory() {
        val model = loaded()
        val first = model.subtitleEntries[0]
        model.commitSingleEdit(EditorEntryEdit.Text(first.stableId, "edited"), emptySet())!!
        val content = model.peekUndo()
        recordSelection(model, setOf(first.stableId))
        val selection = model.peekUndo()
        model.isSourceViewMode = true

        undo(model, sourceMode = true)

        assertSame(selection, model.peekUndo())
        assertSame(content, model.peekRedo())
        assertEquals("first", first.text)
        assertEquals(setOf(first.stableId), model.historySelectionSnapshot)
        assertConsistent(model)
        redo(model, sourceMode = true)
        assertSame(content, model.peekUndo())
        assertEquals("edited", first.text)
        assertConsistent(model)
    }

    @Test fun selectionOnlyUndoDoesNotOverwritePendingSourceOrRebuildItsRowBaseline() {
        val model = loaded()
        recordSelection(model, setOf(model.subtitleEntries[1].stableId))
        model.sourceViewContent = "pending source mirror"
        model.sourceHistoryTextSnapshot = "separate source baseline"
        model.sourceViewNeedsListSync = true
        val document = model.document.value
        val baseline = model.historyEntriesSnapshot
        val indexedCount = model.lastIndexedEntryCount

        move(model, true)

        assertEquals(emptySet<Long>(), model.historySelectionSnapshot)
        assertEquals("pending source mirror", model.sourceViewContent)
        assertEquals("separate source baseline", model.sourceHistoryTextSnapshot)
        assertEquals(SRT, model.originalFileContent)
        assertTrue(model.sourceViewNeedsListSync)
        assertSame(document, model.document.value)
        assertSame(baseline, model.historyEntriesSnapshot)
        assertEquals(indexedCount, model.lastIndexedEntryCount)
        assertFalse(model.hasUnsavedChanges)
    }

    private fun recordSelection(model: EditorViewModel, selected: Set<Long>) {
        assertTrue(model.recordListHistory(
            EditorEditHistory.ListState(model.historyEntriesSnapshot, model.historySelectionSnapshot),
            EditorEditHistory.ListState(model.subtitleEntries, selected), "选择", null, null
        ))
    }

    private fun assertFailedMoveIsAtomic(model: EditorViewModel, undo: Boolean) {
        model.originalFileContent = "unrelated source"
        model.sourceViewContent = "also unrelated source"
        model.hasUnsavedChanges = false
        val list = model.subtitleEntries
        val rows = list.toList()
        val values = list.map { it.copy() }
        val baseline = model.historyEntriesSnapshot
        val baselineValues = baseline.map { it.copy() }
        val selection = model.historySelectionSnapshot
        val document = model.document.value
        val header = model.documentHeader
        val footer = model.documentFooter
        val historyText = model.sourceHistoryTextSnapshot
        val needsSync = model.sourceViewNeedsListSync
        val indexedCount = model.lastIndexedEntryCount
        val top = if (undo) model.peekUndo() else model.peekRedo()
        val opposite = if (undo) model.peekRedo() else model.peekUndo()
        assertNotNull(top)

        val failure = runCatching { move(model, undo) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertSame(top, if (undo) model.peekUndo() else model.peekRedo())
        assertSame(opposite, if (undo) model.peekRedo() else model.peekUndo())
        assertSame(list, model.subtitleEntries)
        rows.indices.forEach { assertSame(rows[it], model.subtitleEntries[it]) }
        assertEquals(values, model.subtitleEntries)
        assertSame(baseline, model.historyEntriesSnapshot)
        assertEquals(baselineValues, baseline)
        assertEquals(selection, model.historySelectionSnapshot)
        assertSame(document, model.document.value)
        assertEquals(header, model.documentHeader)
        assertEquals(footer, model.documentFooter)
        assertEquals("unrelated source", model.originalFileContent)
        assertEquals("also unrelated source", model.sourceViewContent)
        assertEquals(historyText, model.sourceHistoryTextSnapshot)
        assertEquals(needsSync, model.sourceViewNeedsListSync)
        assertEquals(indexedCount, model.lastIndexedEntryCount)
        assertFalse(model.hasUnsavedChanges)
    }

    private fun replayRanges(before: List<Long>, ranges: List<EditorListRangeChange>): List<Long> =
        before.toMutableList().also { ids ->
            ranges.forEach { range ->
                ids.subList(range.start, range.start + range.removedCount).clear()
                ids.addAll(range.start, range.inserted.map { it.stableId })
            }
        }

    private fun undo(model: EditorViewModel, sourceMode: Boolean = false, assertDirty: Boolean = true) =
        move(model, true, sourceMode).also { assertConsistent(model, assertDirty) }

    private fun redo(model: EditorViewModel, sourceMode: Boolean = false, assertDirty: Boolean = true) =
        move(model, false, sourceMode).also { assertConsistent(model, assertDirty) }

    private fun move(model: EditorViewModel, undo: Boolean, sourceMode: Boolean = false): EditorHistoryCommandResult {
        var result: EditorHistoryCommandResult? = null
        val apply: (EditorEditHistory.Operation, Boolean) -> Unit = { operation, reversed ->
            result = model.executeHistoryCommand(operation, reversed)
        }
        assertTrue(if (undo) model.undo(sourceMode, apply) else model.redo(sourceMode, apply))
        return checkNotNull(result)
    }

    private fun assertConsistent(model: EditorViewModel, assertDirty: Boolean = true) {
        if (assertDirty) assertTrue(model.hasUnsavedChanges)
        assertFalse(model.sourceViewNeedsListSync)
        assertEquals(model.originalFileContent, model.sourceViewContent)
        assertEquals(model.originalFileContent, model.sourceHistoryTextSnapshot)
        assertEquals(model.subtitleEntries, model.historyEntriesSnapshot)
        assertEquals(model.subtitleEntries, model.document.value.entries)
        assertEquals(model.documentHeader, model.document.value.header)
        assertEquals(model.documentFooter, model.document.value.footer)
    }

    private fun loaded(source: String = SRT, fileName: String = "test.srt") = EditorViewModel().apply {
        loadSubtitleContent(source, fileName)
        setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
    }

    private fun largeSource(size: Int): String = buildString {
        repeat(size) { index ->
            append(index + 1).append("\n00:00:01,000 --> 00:00:02,000\nrow ").append(index).append("\n\n")
        }
    }

    private companion object {
        const val SRT = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nfirst\r\n\r\n" +
            "2\r\n00:00:03,000 --> 00:00:04,000\r\nsecond\r\n\r\n" +
            "3\r\n00:00:05,000 --> 00:00:06,000\r\nthird\r\n"
        const val VTT = "\uFEFFWEBVTT\r\n\r\none\r\n00:00:01.000 --> 00:00:02.000\r\nfirst body\r\n\r\n" +
            "two\r\n00:00:03.000 --> 00:00:04.000\r\nsecond body\r\n\r\nNOTE footer\r\nretained\r\n"
    }
}
