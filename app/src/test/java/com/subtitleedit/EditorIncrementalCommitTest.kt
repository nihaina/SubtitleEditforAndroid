package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.*
import org.junit.Test

class EditorIncrementalCommitTest {
    @Test fun oneRowBatchPreparesOnlyOneWritableRowAndNeverReplacesTheLiveList() {
        val model = loaded(10_000)
        val originalList = model.subtitleEntries
        val originalRows = originalList.toList()
        val change = model.commitListEdit(EditorCommand.UpdateTexts(listOf(5_000 to "changed"), false), emptySet())!!
        assertEquals(1, change.copiedEntryCount)
        assertTrue(change.ranges.isEmpty())
        assertSame(originalList, model.subtitleEntries)
        originalRows.indices.forEach { assertSame(originalRows[it], model.subtitleEntries[it]) }
    }

    @Test fun appendingRowsCopiesNoExistingRowsAndReturnsOnlyTheInsertionRange() {
        val model = loaded(10_000)
        val snapshot = model.document.value
        val history = model.historyEntriesSnapshot.toList()
        val change = model.commitListEdit(EditorCommand.Insert(10_000,
            listOf(SubtitleEntry(startTime = 2_000, endTime = 3_000, text = "new"))), emptySet())!!
        assertEquals(0, change.copiedEntryCount)
        assertEquals(1, change.ranges.size)
        assertEquals(10_000, change.ranges.single().start)
        assertEquals(0, change.ranges.single().removedCount)
        assertEquals(1, change.ranges.single().inserted.size)
        repeat(10_000) {
            assertSame(history[it], model.historyEntriesSnapshot[it])
            assertSame(snapshot.entries[it], model.document.value.entries[it])
        }
    }

    @Test fun contiguousDeletionProducesOneSpliceAndOnlyRenumbersTheSuffix() {
        val model = loaded(100)
        val rows = model.subtitleEntries.toList()
        val change = model.commitListEdit(EditorCommand.Delete((40..49).toSet()), emptySet())!!
        assertEquals(50, change.copiedEntryCount)
        assertEquals(listOf(EditorListRangeChange(40, 10, emptyList())), change.ranges)
        repeat(40) { assertSame(rows[it], model.subtitleEntries[it]) }
        repeat(50) { assertSame(rows[it + 50], model.subtitleEntries[it + 40]) }
        assertEquals((1..90).toList(), model.subtitleEntries.map { it.index })
    }

    @Test fun sourcePatchReceivesBorrowedUntouchedRowsWhileLiveRowsRemainUnmodified() {
        val model = loaded(100)
        val live = model.subtitleEntries
        val before = live[50].copy()
        var calls = 0
        val session = EditorListEditSession(model.documentState, { _, _, _, _ -> }, { _, _ -> },
            synchronizeSource = { source, _, old, updated ->
                calls++
                assertEquals(before, live[50])
                assertSame(live, old)
                assertNotSame(live[50], updated[50])
                assertSame(live[49], updated[49])
                assertSame(live[51], updated[51])
                source.replace("row 50", "changed")
            })
        val change = session.commit(EditorCommand.UpdateTexts(listOf(50 to "changed"), false), emptySet())!!
        assertEquals(1, calls)
        assertEquals(1, change.copiedEntryCount)
        assertEquals("changed", live[50].text)
    }

    @Test fun structuralPasteReportsTheSurvivingTextEditSeparatelyFromInsertedRows() {
        val model = loaded(100)
        val change = model.commitListEdit(EditorCommand.PasteAt(40, listOf("changed", "new")), emptySet())!!
        assertEquals(setOf(40), change.updatedPositions)
        assertEquals(1, change.ranges.size)
        assertEquals(41, change.ranges.single().start)
        assertEquals("new", change.ranges.single().inserted.single().text)
    }

    @Test fun replacingWithAFewerRowsReportsTheOriginalRemovedCount() {
        val model = loaded(100)
        val change = model.commitListEdit(EditorCommand.ReplaceEntries(model.subtitleEntries.take(80)), emptySet())!!
        assertEquals(20, change.result.removedCount)
        assertTrue(change.updatedPositions.isEmpty())
    }

    private fun loaded(size: Int): EditorViewModel {
        val source = buildString {
            repeat(size) { index -> append(index + 1).append("\n00:00:01,000 --> 00:00:02,000\nrow ").append(index).append("\n\n") }
        }
        return EditorViewModel().apply {
            loadSubtitleContent(source, "test.srt")
            setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
        }
    }
}
