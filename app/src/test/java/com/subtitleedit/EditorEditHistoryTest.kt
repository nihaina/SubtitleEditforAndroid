package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
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
        val selection = EditorEditHistory.Operation.ListChange(
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
        val selection = EditorEditHistory.Operation.ListChange(
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
        val selection = EditorEditHistory.Operation.ListChange(
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
    fun historyOperationExecutesAgainstDocumentState() {
        val history = EditorEditHistory()
        val beforeEntry = SubtitleEntry(startTime = 0L, endTime = 1000L, text = "旧")
        val afterEntry = beforeEntry.copy(text = "新")
        val operation = EditorEditHistory.Operation.ListChange(
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
    fun viewModelExecutesHistoryCommandAndPublishesDocument() {
        val viewModel = EditorViewModel()
        val before = SubtitleEntry(text = "前")
        val after = before.copy(text = "后")
        val command = EditorEditHistory.Operation.ListChange(
            before = EditorEditHistory.ListState(listOf(before), emptySet()),
            after = EditorEditHistory.ListState(listOf(after), emptySet()),
            description = "修改"
        )
        viewModel.subtitleEntries = mutableListOf(before.copy())

        viewModel.executeHistoryCommand(command, undo = false)

        assertEquals("后", viewModel.document.value.entries.single().text)
    }

    private fun sourceChange(before: String, after: String) =
        EditorEditHistory.Operation.SourceChange(before, after, "source")

    private fun listChange(beforeText: String, afterText: String): EditorEditHistory.Operation {
        val entry = SubtitleEntry(text = beforeText)
        return EditorEditHistory.Operation.ListChange(
            before = EditorEditHistory.ListState(listOf(entry), emptySet()),
            after = EditorEditHistory.ListState(listOf(entry.copy(text = afterText)), emptySet()),
            description = "list"
        )
    }
}
