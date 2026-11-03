package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleCoordinate
import com.subtitleedit.util.SubtitleCoordinateOps
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import org.junit.Assert.*
import org.junit.Test

class EditorCoordinatePreviewTest {
    @Test fun srtOverlayOwnsOnlyTargetRowsAndLeavesTheDocumentUntouched() {
        val rows = rows(3)
        val originals = rows.map { it.copy() }
        val revision = Any()
        val preview = EditorCoordinatePreview(rows, SubtitleFormat.SRT, setOf(1), revision)
        val coordinate = SubtitleCoordinate(25f, 75f)

        val overlay = preview.entries(rows, SubtitleFormat.SRT, revision, coordinate)!!

        assertSame(rows[0], overlay[0])
        assertNotSame(rows[1], overlay[1])
        assertSame(rows[2], overlay[2])
        assertEquals(rows[1].stableId, overlay[1].stableId)
        assertEquals(SubtitleCoordinateOps.set(originals[1].text, coordinate), overlay[1].text)
        assertEquals(originals, rows)
        assertEquals(setOf(1), preview.positions(rows, SubtitleFormat.SRT, revision))
    }

    @Test fun vttOverlayUsesNativeSettingsWithoutChangingTheLiveCue() {
        val row = SubtitleEntry(text = "<i>body</i>", cueIdentifier = "cue-id", cueSettings = "region:main size:80%")
        val rows = mutableListOf(row)
        val original = row.copy()
        val revision = Any()
        val preview = EditorCoordinatePreview(rows, SubtitleFormat.VTT, setOf(0), revision)

        val overlay = preview.entries(rows, SubtitleFormat.VTT, revision, SubtitleCoordinate(25f, 75f))!!

        assertNotSame(row, overlay[0])
        assertTrue(overlay[0].cueSettings.contains("region:main"))
        assertTrue(overlay[0].cueSettings.contains("size:80%"))
        assertEquals(SubtitleCoordinate(25f, 75f), SubtitleCoordinateOps.fromVttSettings(overlay[0].cueSettings))
        assertEquals("cue-id", overlay[0].cueIdentifier)
        assertEquals(original, row)
    }

    @Test fun laterDragDoesNotMutateAnAlreadyScheduledOverlay() {
        val rows = rows(2)
        val revision = Any()
        val preview = EditorCoordinatePreview(rows, SubtitleFormat.SRT, setOf(0), revision)
        val firstCoordinate = SubtitleCoordinate(25f, 75f)
        val secondCoordinate = SubtitleCoordinate(75f, 25f)
        val first = preview.entries(rows, SubtitleFormat.SRT, revision, firstCoordinate)!!
        val firstText = first[0].text

        val second = preview.entries(rows, SubtitleFormat.SRT, revision, secondCoordinate)!!

        assertNotSame(first[0], second[0])
        assertEquals(firstText, first[0].text)
        assertEquals(SubtitleCoordinateOps.set(rows[0].text, secondCoordinate), second[0].text)
        assertSame(first[1], second[1])
        assertEquals("row 0", rows[0].text)
    }

    @Test fun dragLeavesPublishedSourceHistoryAndDirtyStateUntouched() {
        val model = EditorViewModel().apply {
            loadSubtitleContent(SRT, "test.srt")
            setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), SRT, true)
        }
        val list = model.subtitleEntries
        val originals = list.map { it.copy() }
        val document = model.document.value
        val history = model.historyEntriesSnapshot
        val preview = EditorCoordinatePreview(list, SubtitleFormat.SRT, setOf(0), document)

        repeat(20) { index ->
            preview.entries(list, SubtitleFormat.SRT, model.document.value, SubtitleCoordinate(index.toFloat(), 75f))!!
        }

        assertSame(list, model.subtitleEntries)
        assertEquals(originals, list)
        assertSame(document, model.document.value)
        assertSame(history, model.historyEntriesSnapshot)
        assertEquals(SRT, model.originalFileContent)
        assertEquals(SRT, model.sourceViewContent)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
    }

    @Test fun committedEditsDuringPreviewRemainInTheDocumentAndTheNextOverlay() {
        val model = EditorViewModel().apply {
            loadSubtitleContent(SRT, "test.srt")
            setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), SRT, true)
        }
        val preview = EditorCoordinatePreview(model.subtitleEntries, SubtitleFormat.SRT, setOf(0), model.document.value)
        preview.entries(model.subtitleEntries, SubtitleFormat.SRT, model.document.value, SubtitleCoordinate(25f, 75f))!!
        val first = model.subtitleEntries[0]
        val second = model.subtitleEntries[1]

        model.commitSingleEdit(EditorEntryEdit.Text(second.stableId, "committed other row"), emptySet())!!
        model.commitSingleEdit(EditorEntryEdit.Text(first.stableId, "committed selected row"), emptySet())!!
        val source = model.originalFileContent
        val history = model.peekUndo()
        val document = model.document.value
        val overlay = preview.entries(model.subtitleEntries, SubtitleFormat.SRT, document, SubtitleCoordinate(75f, 25f))!!

        assertEquals("committed other row", overlay[1].text)
        assertSame(second, overlay[1])
        assertTrue(overlay[0].text.endsWith("committed selected row"))
        assertEquals("committed selected row", first.text)
        assertEquals(source, model.originalFileContent)
        assertSame(history, model.peekUndo())
        assertSame(document, model.document.value)
        assertTrue(model.hasUnsavedChanges)
    }

    @Test fun insertionAndReorderingKeepTargetsAssociatedByStableId() {
        val rows = rows(4)
        val targets = setOf(rows[1].stableId, rows[3].stableId)
        val preview = EditorCoordinatePreview(rows, SubtitleFormat.SRT, setOf(1, 3), Any())
        rows.add(0, SubtitleEntry(text = "inserted"))
        rows.reverse()
        val revision = Any()

        val positions = preview.positions(rows, SubtitleFormat.SRT, revision)!!
        val overlay = preview.entries(rows, SubtitleFormat.SRT, revision, SubtitleCoordinate(25f, 75f))!!

        assertEquals(targets, positions.map { rows[it].stableId }.toSet())
        rows.indices.forEach { index ->
            if (rows[index].stableId in targets) assertNotSame(rows[index], overlay[index])
            else assertSame(rows[index], overlay[index])
        }
    }

    @Test fun deletedTargetsAreDroppedAndNeverAppliedToTheRowTakingTheirPlace() {
        val rows = rows(4)
        val retainedTarget = rows[3]
        val preview = EditorCoordinatePreview(rows, SubtitleFormat.SRT, setOf(1, 3), Any())
        rows.removeAt(1)
        val revision = Any()

        assertEquals(setOf(2), preview.positions(rows, SubtitleFormat.SRT, revision))
        val overlay = preview.entries(rows, SubtitleFormat.SRT, revision, SubtitleCoordinate(25f, 75f))!!

        assertSame(rows[1], overlay[1])
        assertEquals(retainedTarget.stableId, overlay[2].stableId)
        assertNotSame(retainedTarget, overlay[2])
        rows.remove(retainedTarget)
        val afterDeletion = Any()
        assertTrue(preview.positions(rows, SubtitleFormat.SRT, afterDeletion)!!.isEmpty())
        val noTargets = preview.entries(rows, SubtitleFormat.SRT, afterDeletion, SubtitleCoordinate(25f, 75f))!!
        rows.indices.forEach { assertSame(rows[it], noTargets[it]) }
    }

    @Test fun aScheduledOverlayKeepsItsStructureWhenTheLiveListChanges() {
        val rows = rows(3)
        val originalLast = rows[2]
        val revision = Any()
        val preview = EditorCoordinatePreview(rows, SubtitleFormat.SRT, setOf(1), revision)
        val scheduled = preview.entries(rows, SubtitleFormat.SRT, revision, SubtitleCoordinate(25f, 75f))!!

        rows.removeAt(0)
        rows.add(SubtitleEntry(text = "new row"))
        rows.add(SubtitleEntry(text = "another new row"))
        val next = preview.entries(rows, SubtitleFormat.SRT, Any(), SubtitleCoordinate(75f, 25f))!!

        assertEquals(3, scheduled.size)
        assertEquals("row 0", scheduled[0].text)
        assertSame(originalLast, scheduled[2])
        assertEquals(4, next.size)
        assertEquals("new row", next[2].text)
    }

    @Test fun anotherDocumentOrFormatCannotUseTheOldOverlay() {
        val rows = rows(2)
        val revision = Any()
        val preview = EditorCoordinatePreview(rows, SubtitleFormat.SRT, setOf(0), revision)
        val coordinate = SubtitleCoordinate(25f, 75f)
        val anotherDocument = rows.toMutableList()

        assertNull(preview.positions(anotherDocument, SubtitleFormat.SRT, Any()))
        assertNull(preview.entries(anotherDocument, SubtitleFormat.SRT, Any(), coordinate))
        assertNull(preview.positions(rows, SubtitleFormat.VTT, Any()))
        assertNull(preview.entries(rows, SubtitleFormat.VTT, Any(), coordinate))
        assertNotNull(preview.entries(rows, SubtitleFormat.SRT, revision, coordinate))
    }

    @Test fun repeatedSingleTargetDragsDoNotTraverseTheTwentyThousandLiveRows() {
        val backing = rows(20_000)
        val rows = CountingList(backing)
        val revision = Any()
        val target = 10_000
        val preview = EditorCoordinatePreview(rows, SubtitleFormat.SRT, setOf(target), revision)
        rows.reads = 0

        repeat(100) { index ->
            val overlay = preview.entries(rows, SubtitleFormat.SRT, revision, SubtitleCoordinate(index.toFloat(), 75f))!!
            assertEquals(20_000, overlay.size)
            assertSame(backing[0], overlay[0])
            assertSame(backing.last(), overlay.last())
            assertNotSame(backing[target], overlay[target])
        }

        assertEquals(0, rows.reads)
        assertEquals("row $target", backing[target].text)
    }

    private fun rows(count: Int) = MutableList(count) { index ->
        SubtitleEntry(index = index + 1, startTime = index * 1_000L, endTime = (index + 1) * 1_000L, text = "row $index")
    }

    private class CountingList(private val backing: List<SubtitleEntry>) : AbstractList<SubtitleEntry>() {
        var reads = 0
        override val size: Int get() = backing.size
        override fun get(index: Int): SubtitleEntry {
            reads++
            return backing[index]
        }
    }

    private companion object {
        const val SRT = "1\n00:00:01,000 --> 00:00:02,000\nfirst\n\n2\n00:00:03,000 --> 00:00:04,000\nsecond\n"
    }
}
