package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleTimeConflict
import org.junit.Assert.*
import org.junit.Test

class EditorListConflictBoundaryTest {
    @Test fun deletingBlankMiddleCueRefreshesBothSurvivorsAndClearsTheFollowingStartConflict() {
        val model = loaded(listOf(
            SubtitleEntry(startTime = 0L, endTime = 1_000L, text = "first"),
            SubtitleEntry(startTime = 1_500L, endTime = 3_000L, text = "blank target"),
            SubtitleEntry(startTime = 2_500L, endTime = 4_000L, text = "last")
        ))
        val list = model.subtitleEntries
        val first = list.first()
        val last = list.last()
        assertTrue(SubtitleTimeConflict.markers(list, 2).start)

        val change = model.commitListEdit(EditorCommand.UpdateTexts(listOf(1 to "")), emptySet())!!

        assertEquals(setOf(0, 1), change.timeConflictPositions)
        assertTrue(change.updatedPositions.isEmpty())
        assertSame(list, model.subtitleEntries)
        assertSame(first, list[0])
        assertSame(last, list[1])
        assertFalse(SubtitleTimeConflict.markers(list, 1).start)
        assertFalse(SubtitleTimeConflict.markers(list, 0).end)
        assertCommitted(model)
    }

    @Test fun separatedBlankRangesResolveEveryBoundaryAfterEarlierDeletionsShiftTheRows() {
        val model = loaded(rows(12))
        val originals = model.subtitleEntries.toList()

        val change = model.commitListEdit(
            EditorCommand.UpdateTexts(listOf(2 to "", 3 to " ", 4 to "", 8 to "")), emptySet()
        )!!

        assertEquals(setOf(1, 2, 4, 5), change.timeConflictPositions)
        assertEquals(listOf("row 1", "row 5", "row 7", "row 9"),
            change.timeConflictPositions.sorted().map { model.subtitleEntries[it].text })
        assertTrue(change.updatedPositions.isEmpty())
        model.subtitleEntries.forEach { entry ->
            assertSame(originals.single { it.stableId == entry.stableId }, entry)
        }
        assertCommitted(model)
    }

    @Test fun firstLastConsecutiveAndWholeDocumentDeletionOnlyReportSurvivingBoundaries() {
        val cases = listOf(
            setOf(0) to setOf(0),
            setOf(4) to setOf(3),
            setOf(1, 2) to setOf(0, 1),
            setOf(0, 1, 4) to setOf(0, 1),
            setOf(0, 1, 2, 3, 4) to emptySet()
        )
        cases.forEach { (deleted, expectedBoundaries) ->
            val model = loaded(rows(5))

            val change = model.commitListEdit(EditorCommand.Delete(deleted), emptySet())!!

            assertEquals("deleted=$deleted", expectedBoundaries, change.timeConflictPositions)
            assertTrue(change.timeConflictPositions.all { it in model.subtitleEntries.indices })
            assertCommitted(model)
        }
    }

    @Test fun plainTextEditsDoNotCreateStructuralColorNotificationsOrReplaceUntouchedSnapshots() {
        val model = loaded(rows(5))
        val list = model.subtitleEntries
        val originals = list.toList()
        val published = model.document.value.entries
        val history = model.historyEntriesSnapshot.toList()

        val change = model.commitListEdit(
            EditorCommand.UpdateTexts(listOf(2 to "changed"), false), emptySet()
        )!!

        assertTrue(change.timeConflictPositions.isEmpty())
        assertFalse(change.result.structureChanged)
        assertSame(list, model.subtitleEntries)
        originals.indices.forEach { position ->
            assertSame(originals[position], list[position])
            if (position != 2) {
                assertSame(published[position], model.document.value.entries[position])
                assertSame(history[position], model.historyEntriesSnapshot[position])
            }
        }
        assertCommitted(model)
    }

    @Test fun largeDocumentDeletionNearTheEndKeepsTheColorRefreshAndCopiesLocal() {
        val model = loaded(rows(2_000))
        val originals = model.subtitleEntries.toList()
        val published = model.document.value.entries

        val change = model.commitListEdit(EditorCommand.Delete(setOf(1_998)), emptySet())!!

        assertEquals(setOf(1_997, 1_998), change.timeConflictPositions)
        assertTrue(change.copiedEntryCount <= 1)
        assertSame(originals.last(), model.subtitleEntries.last())
        for (position in 0 until 1_998) {
            assertSame(originals[position], model.subtitleEntries[position])
            assertSame(published[position], model.document.value.entries[position])
        }
        assertCommitted(model)
    }

    @Test fun insertionSplitAndMergeAlsoRefreshTheExistingRowsOnBothSides() {
        val commands = listOf(
            EditorCommand.Insert(1, listOf(SubtitleEntry(
                startTime = 1_200L, endTime = 1_700L, text = "inserted"
            ))) to setOf(0, 2),
            EditorCommand.Split(1, 2_500L, "left", "right") to setOf(0, 3),
            EditorCommand.Merge(listOf(1, 2)) to setOf(0, 2)
        )
        commands.forEach { (command, expectedBoundaries) ->
            val model = loaded(rows(4))
            val first = model.subtitleEntries.first()
            val last = model.subtitleEntries.last()

            val change = model.commitListEdit(command, emptySet())!!

            assertTrue("command=$command", change.timeConflictPositions.containsAll(expectedBoundaries))
            assertTrue(change.timeConflictPositions.all { it in model.subtitleEntries.indices })
            assertSame(first, model.subtitleEntries.first())
            assertSame(last, model.subtitleEntries.last())
            assertCommitted(model)
        }
    }

    private fun loaded(entries: List<SubtitleEntry>) = EditorViewModel().apply {
        val source = SubtitleParser.toSRT(entries)
        loadSubtitleContent(source, "test.srt")
        setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
    }

    private fun rows(count: Int) = List(count) { position ->
        SubtitleEntry(index = position + 1, startTime = position * 2_000L,
            endTime = position * 2_000L + 1_000L, text = "row $position")
    }

    private fun assertCommitted(model: EditorViewModel) {
        assertTrue(model.hasUnsavedChanges)
        assertFalse(model.sourceViewNeedsListSync)
        assertEquals(model.originalFileContent, model.sourceViewContent)
        assertEquals(model.originalFileContent, model.sourceHistoryTextSnapshot)
        assertEquals(model.subtitleEntries, model.historyEntriesSnapshot)
        assertEquals(model.subtitleEntries, model.document.value.entries)
        val parsed = SubtitleParser.parseDocument(model.originalFileContent, "test.srt").entries
        assertEquals(model.subtitleEntries.map { Triple(it.startTime, it.endTime, it.text) },
            parsed.map { Triple(it.startTime, it.endTime, it.text) })
    }
}
