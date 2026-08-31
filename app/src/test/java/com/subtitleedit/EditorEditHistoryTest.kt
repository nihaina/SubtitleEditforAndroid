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
