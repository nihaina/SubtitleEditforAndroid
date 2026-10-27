package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.editor.EditorSourceTextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEditHistoryTest {
    @Test
    fun newRecordClearsRedoStack() {
        val history = EditorEditHistory()
        val first = sourceChange("a", "b")
        history.record(first)
        assertSame(first, history.takeUndo())
        history.pushRedo(first)
        assertTrue(history.canRedo)

        history.record(sourceChange("b", "c"))

        assertFalse(history.canRedo)
        assertTrue(history.canUndo)
    }

    @Test
    fun undoAndRedoStacksAreIndependent() {
        val history = EditorEditHistory()
        val operation = listChange("before", "after")
        history.record(operation)

        val undo = history.takeUndo()
        assertSame(operation, undo)
        history.pushRedo(undo!!)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)

        val redo = history.takeRedo()
        assertSame(operation, redo)
        history.pushUndo(redo!!)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun noOpIsNotRecorded() {
        val history = EditorEditHistory()
        history.record(sourceChange("same", "same"))

        assertFalse(history.canUndo)
    }

    @Test
    fun consecutiveSourceDeletionsWithin400MsShareOneUndoOperation() {
        val history = EditorEditHistory()
        history.record(sourceChange("abcdef", "abcde"), timestampMs = 1_000L)
        history.record(sourceChange("abcde", "abcd"), timestampMs = 1_400L)

        val operation = history.takeUndo() as EditorEditHistory.Operation.SourceChange
        assertEquals(4, operation.edit.startOffset)
        assertEquals("ef", operation.edit.deletedText)
        assertEquals("", operation.edit.insertedText)
        assertFalse(history.canUndo)
    }

    @Test
    fun subtitleCopiesKeepStableId() {
        val entry = SubtitleEntry(text = "A")

        assertEquals(entry.stableId, entry.copy(text = "B").stableId)
        assertTrue(SubtitleEntry(text = "C").stableId != entry.stableId)
    }

    @Test
    fun differenceClassifiesContentAndSelectionChanges() {
        val unchanged = SubtitleEntry(startTime = 0, endTime = 1000, text = "same")
        val modified = SubtitleEntry(startTime = 1000, endTime = 2000, text = "old")
        val modifiedAfter = modified.copy(text = "new")
        val deleted = SubtitleEntry(startTime = 2000, endTime = 3000, text = "deleted")
        val added = SubtitleEntry(startTime = 3000, endTime = 4000, text = "added")

        val difference = EditorEditHistory.difference(
            EditorEditHistory.ListState(
                entries = listOf(unchanged, modified, deleted),
                selectedIds = setOf(unchanged.stableId)
            ),
            EditorEditHistory.ListState(
                entries = listOf(unchanged.copy(), modifiedAfter, added),
                selectedIds = setOf(modified.stableId)
            )
        )

        assertEquals(listOf(deleted.stableId), difference.deleted.map { it.stableId })
        assertEquals(listOf(added.stableId), difference.added.map { it.stableId })
        assertEquals(listOf(modified.stableId), difference.modified.map { it.first.stableId })
        assertEquals(listOf(modified.stableId), difference.selected)
        assertEquals(listOf(unchanged.stableId), difference.deselected)
    }

    @Test
    fun sourceViewPeekSkipsSelectionOnlyOperations() {
        val history = EditorEditHistory()
        val entry = SubtitleEntry(text = "same")
        val selection = EditorEditHistory.Operation.ListChange.fromStates(
            before = EditorEditHistory.ListState(listOf(entry), emptySet()),
            after = EditorEditHistory.ListState(listOf(entry.copy()), setOf(entry.stableId)),
            description = "select"
        )
        val source = sourceChange("a", "b")
        history.record(selection)
        history.record(source)

        assertSame(source, history.peekUndoWithoutSelection())
        history.takeUndo()
        history.pushRedo(source)
        assertEquals(null, history.peekUndoWithoutSelection())
        assertFalse(history.canUndoWithoutSelection)
    }

    @Test
    fun historyControllerMovesOnlyContentOperationsInSourceView() {
        val history = EditorEditHistory()
        val controller = EditorHistoryController(history)
        val entry = SubtitleEntry(text = "same")
        val selection = EditorEditHistory.Operation.ListChange.fromStates(
            before = EditorEditHistory.ListState(listOf(entry), emptySet()),
            after = EditorEditHistory.ListState(listOf(entry.copy()), setOf(entry.stableId)),
            description = "select"
        )
        val source = sourceChange("a", "b")
        history.record(selection)
        history.record(source)
        val applied = mutableListOf<EditorEditHistory.Operation>()

        assertTrue(controller.undo(true) { operation, _ -> applied += operation })
        assertEquals(listOf(source), applied)
        assertSame(source, history.peekRedo())
        assertSame(selection, history.peekUndo())
    }

    @Test
    fun historyControllerRestoresSkippedOperationsWhenNothingCanBeApplied() {
        val history = EditorEditHistory()
        val controller = EditorHistoryController(history)
        val entry = SubtitleEntry(text = "same")
        val selection = EditorEditHistory.Operation.ListChange.fromStates(
            before = EditorEditHistory.ListState(listOf(entry), emptySet()),
            after = EditorEditHistory.ListState(listOf(entry.copy()), setOf(entry.stableId)),
            description = "select"
        )
        history.record(selection)

        assertFalse(controller.undo(true) { _, _ -> error("不应应用选择操作") })
        assertSame(selection, history.peekUndo())
    }

    @Test
    fun differenceTreatsRowReorderingAsAContentStateChange() {
        val first = SubtitleEntry(text = "first")
        val second = SubtitleEntry(text = "second")
        val difference = EditorEditHistory.difference(
            EditorEditHistory.ListState(listOf(first, second), emptySet()),
            EditorEditHistory.ListState(listOf(second.copy(), first.copy()), emptySet())
        )

        assertTrue(difference.orderChanged)
        assertFalse(difference.isEmpty)
    }

    @Test
    fun listHistoryDeltaRestoresAddedDeletedAndUpdatedRows() {
        val first = SubtitleEntry(text = "first")
        val deleted = SubtitleEntry(text = "deleted")
        val added = SubtitleEntry(text = "added")
        val before = EditorEditHistory.ListState(listOf(first, deleted), selectedIds = setOf(deleted.stableId))
        val after = EditorEditHistory.ListState(listOf(first.copy(text = "changed"), added), selectedIds = setOf(added.stableId))
        val operation = EditorEditHistory.Operation.ListChange.fromStates(before, after, "编辑")
        val state = EditorDocumentState().apply {
            subtitleEntries = before.entries.map { it.copy() }.toMutableList()
            historySelectionSnapshot = before.selectedIds
            originalFileContent = ""
            sourceViewContent = ""
            currentFormat = com.subtitleedit.util.SubtitleParser.SubtitleFormat.TXT
        }

        val redo = operation.execute(state, undo = false)
        assertEquals(listOf("changed", "added"), state.subtitleEntries.map { it.text })
        assertEquals(setOf(added.stableId), redo.selectedIds)

        state.historySelectionSnapshot = redo.selectedIds
        val undo = operation.execute(state, undo = true)
        assertEquals(listOf("first", "deleted"), state.subtitleEntries.map { it.text })
        assertEquals(setOf(deleted.stableId), undo.selectedIds)
    }

    @Test
    fun cueMetadataChangesAreUndoableContentChanges() {
        val beforeEntry = SubtitleEntry(
            startTime = 0L,
            endTime = 1000L,
            text = "字幕",
            cueSettings = "align:center"
        )
        val afterEntry = beforeEntry.copy(cueSettings = "align:center position:50% line:80%")
        val before = EditorEditHistory.ListState(listOf(beforeEntry), emptySet())
        val after = EditorEditHistory.ListState(listOf(afterEntry), emptySet())
        val difference = EditorEditHistory.difference(before, after)
        val operation = EditorEditHistory.Operation.ListChange.fromStates(
            before = before,
            after = after,
            description = "排列和坐标",
            beforeSourceText = "before source",
            afterSourceText = "after source"
        )
        val state = EditorDocumentState().apply {
            subtitleEntries = mutableListOf(afterEntry.copy())
            originalFileContent = "after source"
            sourceViewContent = "after source"
            currentFormat = com.subtitleedit.util.SubtitleParser.SubtitleFormat.VTT
        }

        assertFalse(difference.isEmpty)
        assertEquals(listOf(beforeEntry.stableId), difference.modified.map { it.first.stableId })
        assertFalse(operation.isSelectionOnly())

        operation.execute(state, undo = true)

        assertEquals("align:center", state.subtitleEntries.single().cueSettings)
        assertEquals("before source", state.originalFileContent)
    }

    @Test
    fun historyOperationExecutesAgainstDocumentState() {
        val history = EditorEditHistory()
        val beforeEntry = SubtitleEntry(startTime = 0L, endTime = 1000L, text = "旧")
        val afterEntry = beforeEntry.copy(text = "新")
        val operation = EditorEditHistory.Operation.ListChange.fromStates(
            before = EditorEditHistory.ListState(listOf(beforeEntry), emptySet()),
            after = EditorEditHistory.ListState(listOf(afterEntry), emptySet()),
            description = "修改"
        )
        val state = EditorDocumentState().apply {
            subtitleEntries = mutableListOf(beforeEntry.copy())
            originalFileContent = "1\n00:00:00,000 --> 00:00:01,000\n旧\n"
            currentFormat = com.subtitleedit.util.SubtitleParser.SubtitleFormat.SRT
        }

        val result = operation.execute(state, undo = false)

        assertEquals("新", state.subtitleEntries.single().text)
        assertEquals("新", result.entries.single().text)
        assertTrue(result.sourceText?.contains("新") == true)
    }

    @Test
    fun rowHistoryResultReusesLiveDocumentEntries() {
        val first = SubtitleEntry(startTime = 0L, endTime = 1000L, text = "旧")
        val second = SubtitleEntry(startTime = 1000L, endTime = 2000L, text = "保留")
        val after = first.copy(text = "新")
        val operation = EditorEditHistory.Operation.ListChange.fromStates(
            before = EditorEditHistory.ListState(listOf(first, second), emptySet()),
            after = EditorEditHistory.ListState(listOf(after, second), emptySet()),
            description = "修改"
        )
        val liveFirst = first.copy()
        val liveSecond = second.copy()
        val state = EditorDocumentState().apply {
            subtitleEntries = mutableListOf(liveFirst, liveSecond)
            historyEntriesSnapshot = mutableListOf(first.copy(), second.copy())
            originalFileContent = ""
            currentFormat = com.subtitleedit.util.SubtitleParser.SubtitleFormat.TXT
        }

        val result = operation.execute(state, undo = false)

        assertSame(state.subtitleEntries, result.entries)
        assertSame(liveSecond, result.entries[1])
        assertEquals("新", liveFirst.text)
    }

    @Test
    fun sameOrderHistoryBaselineAdvancesChangedRowsInPlace() {
        val first = SubtitleEntry(text = "前")
        val second = SubtitleEntry(text = "保持")
        val viewModel = EditorViewModel()
        viewModel.subtitleEntries = mutableListOf(first.copy(), second.copy())
        viewModel.setHistoryBaseline(
            EditorEditHistory.ListState(viewModel.subtitleEntries.map { it.copy() }, emptySet()),
            sourceText = "",
            clear = true
        )
        val ownedBaseline = viewModel.historyEntriesSnapshot
        val previousFirst = ownedBaseline[0]
        val previousSecond = ownedBaseline[1]
        val before = EditorEditHistory.ListState(ownedBaseline, emptySet())
        viewModel.subtitleEntries[0].text = "后"

        viewModel.recordListHistory(
            before = before,
            after = EditorEditHistory.ListState(viewModel.subtitleEntries, emptySet()),
            description = "修改",
            beforeSourceText = null,
            afterSourceText = null
        )

        assertSame(ownedBaseline, viewModel.historyEntriesSnapshot)
        assertEquals(listOf("后", "保持"), viewModel.historyEntriesSnapshot.map { it.text })
        assertSame(previousSecond, viewModel.historyEntriesSnapshot[1])
        assertEquals("前", previousFirst.text)
        val recorded = viewModel.peekUndo() as EditorEditHistory.Operation.ListChange
        assertEquals("前", recorded.updated.single().before.text)
        assertEquals("保持", viewModel.historyEntriesSnapshot[1].text)
    }

    @Test
    fun viewModelExecutesHistoryCommandAndPublishesDocument() {
        val viewModel = EditorViewModel()
        val before = SubtitleEntry(text = "前")
        val after = before.copy(text = "后")
        val command = EditorEditHistory.Operation.ListChange.fromStates(
            before = EditorEditHistory.ListState(listOf(before), emptySet()),
            after = EditorEditHistory.ListState(listOf(after), emptySet()),
            description = "修改"
        )
        viewModel.subtitleEntries = mutableListOf(before.copy())

        viewModel.executeHistoryCommand(command, undo = false)

        assertEquals("后", viewModel.document.value.entries.single().text)
    }

    @Test
    fun sourceHistoryPatchRestoresTextWithoutKeepingAnEntrySnapshot() {
        val currentEntry = SubtitleEntry(startTime = 0L, endTime = 1000L, text = "新")
        val state = EditorDocumentState().apply {
            subtitleEntries = mutableListOf(currentEntry.copy())
            originalFileContent = "新"
            sourceViewContent = "新"
            sourceViewNeedsListSync = true
            currentFormat = com.subtitleedit.util.SubtitleParser.SubtitleFormat.TXT
        }
        val operation = EditorEditHistory.Operation.SourceChange(
            edit = EditorSourceTextEdit.between("旧", "新"),
            description = "修改"
        )

        val result = operation.execute(state, undo = true)

        assertEquals("旧", state.sourceViewContent)
        assertEquals("新", state.subtitleEntries.single().text)
        assertTrue(state.sourceViewNeedsListSync)
        assertFalse(result.entriesResolved)

        operation.execute(state, undo = false)
        assertEquals("新", state.sourceViewContent)
    }

    @Test
    fun sourceHistoryOperationRetainsOnlyTheChangedRange() {
        val operation = sourceChange("subtitle row", "subtitle rows") as EditorEditHistory.Operation.SourceChange

        assertEquals(12, operation.edit.startOffset)
        assertEquals("", operation.edit.deletedText)
        assertEquals("s", operation.edit.insertedText)
    }

    @Test
    fun newlineHistoryStoresOnlyTheInsertedLineEnding() {
        val before = "first\r\nsecond\r\nthird"
        val after = "first\r\nsec\r\nond\r\nthird"
        val edit = EditorSourceTextEdit.fromRanges(
            before = before,
            after = after,
            oldStart = 7,
            oldEnd = 15,
            newStart = 7,
            newEnd = 17,
            lineNumber = 1
        )

        assertEquals(10, edit.startOffset)
        assertEquals("", edit.deletedText)
        assertEquals("\r\n", edit.insertedText)
        assertEquals(before, edit.applyTo(after, undo = true))
        assertEquals(after, edit.applyTo(before, undo = false))
    }

    private fun sourceChange(before: String, after: String) =
        EditorEditHistory.Operation.SourceChange(EditorSourceTextEdit.between(before, after), "source")

    private fun listChange(beforeText: String, afterText: String): EditorEditHistory.Operation {
        val entry = SubtitleEntry(text = beforeText)
        return EditorEditHistory.Operation.ListChange.fromStates(
            before = EditorEditHistory.ListState(listOf(entry), emptySet()),
            after = EditorEditHistory.ListState(listOf(entry.copy(text = afterText)), emptySet()),
            description = "list"
        )
    }
}
