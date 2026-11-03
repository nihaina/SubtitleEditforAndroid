package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import org.junit.Assert.*
import org.junit.Test

class EditorSplitPropertiesTest {
    @Test fun bothHalvesCopyCuePropertiesWithoutMutatingTheOriginalRow() {
        val original = SubtitleEntry(
            index = 1, startTime = 1_000L, endTime = 2_000L, text = "left right",
            endTimeModified = true, cueIdentifier = "original-cue", cueSettings = SETTINGS
        )
        val before = original.copy()
        val state = EditorDocumentState().apply { subtitleEntries = mutableListOf(original) }

        val result = EditorCommand.Split(0, 1_500L, "left", "right").execute(state)

        val left = state.subtitleEntries[0]
        val right = state.subtitleEntries[1]
        assertTrue(result.structureChanged)
        assertEquals(setOf(0, 1), result.changedPositions)
        assertEquals(before, original)
        assertEquals(listOf(1, 2), state.subtitleEntries.map { it.index })
        assertEquals(1_000L, left.startTime)
        assertEquals(1_500L, left.endTime)
        assertEquals(1_500L, right.startTime)
        assertEquals(2_000L, right.endTime)
        assertEquals("left", left.text)
        assertEquals("right", right.text)
        state.subtitleEntries.forEach { entry ->
            assertTrue(entry.endTimeModified)
            assertEquals(original.cueIdentifier, entry.cueIdentifier)
            assertEquals(original.cueSettings, entry.cueSettings)
            assertNotEquals(original.stableId, entry.stableId)
        }
        assertNotEquals(left.stableId, right.stableId)
    }

    @Test fun vttSplitPreservesCueSettingsRawSectionsAndOneUndoRedo() {
        val model = loaded(VTT, "test.vtt")
        val list = model.subtitleEntries
        val original = list.first().copy()
        val neighbor = list.last()
        val header = model.documentHeader
        val footer = model.documentFooter
        val selection = setOf(original.stableId, neighbor.stableId)
        model.setHistoryBaseline(EditorEditHistory.ListState(list, selection), VTT, true)

        val change = model.commitListEdit(
            EditorCommand.Split(0, 1_500L, "left body", "right body"), selection
        )!!

        val splitRows = list.take(2).map { it.copy() }
        val splitSource = model.originalFileContent
        assertSame(list, model.subtitleEntries)
        assertSame(neighbor, list[2])
        assertEquals(setOf(neighbor.stableId), change.selectedIds)
        assertEquals(header, model.documentHeader)
        assertEquals(footer, model.documentFooter)
        assertTrue(splitSource.startsWith("\uFEFFWEBVTT\r\n"))
        assertTrue(splitSource.contains(NEIGHBOR))
        assertTrue(splitSource.endsWith("NOTE footer\r\nretained\r\n"))
        assertSplitVttProperties(model, SETTINGS)
        assertConsistent(model)

        moveHistory(model, undo = true)

        assertEquals(original, list.first())
        assertSame(neighbor, list.last())
        assertEquals(selection, model.historySelectionSnapshot)
        assertEquals(VTT, model.originalFileContent)
        assertNull(model.peekUndo())
        assertConsistent(model)

        moveHistory(model, undo = false)

        assertEquals(splitRows, list.take(2))
        assertSame(neighbor, list.last())
        assertEquals(splitSource, model.originalFileContent)
        assertNull(model.peekRedo())
        assertSplitVttProperties(model, SETTINGS)
        assertConsistent(model)
    }

    @Test fun vttRightHalfKeepsExactNonDefaultPlacementWhenSaved() {
        val preciseSettings = SETTINGS.replace("position:50% line:80%", "position:23.5% line:12.5%")
        val source = VTT.replace(SETTINGS, preciseSettings)
        val model = loaded(source, "test.vtt")
        val originalText = model.subtitleEntries.first().text
        val splitAt = originalText.indexOf("right body")

        model.commitListEdit(EditorCommand.Split(
            0, 1_500L, originalText.take(splitAt), originalText.substring(splitAt)
        ), emptySet())!!

        assertSplitVttProperties(model, preciseSettings)
        assertTrue(model.subtitleEntries[1].text.endsWith("right body"))
        assertConsistent(model)
    }

    @Test fun vttRightHalfInheritsAlignmentDeclaredInTheOriginalText() {
        val source = VTT.replace(SETTINGS, "")
            .replace("left body right body", "{\\an9}left body right body")
        val model = loaded(source, "test.vtt")

        model.commitListEdit(EditorCommand.Split(
            0, 1_500L, "{\\an9}left body", "right body"
        ), emptySet())!!

        assertEquals("position:80% line:20%", model.subtitleEntries[1].cueSettings)
        assertEquals("{\\an9}right body", model.subtitleEntries[1].text)
        listOf(model.originalFileContent, model.buildSaveContent(requireNonEmptyList = true)!!).forEach {
            val saved = SubtitleParser.parseDocument(it, "test.vtt")
            assertEquals("position:80% line:20%", saved.entries[1].cueSettings)
        }
        assertConsistent(model)
    }

    @Test fun splittingNearTheEndRetainsUntouchedLiveRowsAndPublishedSnapshots() {
        val source = buildString {
            repeat(300) { index ->
                append(index + 1).append("\n00:00:01,000 --> 00:00:02,000\nrow ")
                    .append(index).append("\n\n")
            }
        }
        val model = loaded(source, "test.srt")
        val list = model.subtitleEntries
        val rows = list.toList()
        val published = model.document.value.entries
        val history = model.historyEntriesSnapshot.toList()
        val position = rows.lastIndex - 1

        val change = model.commitListEdit(
            EditorCommand.Split(position, 1_500L, "left", "right"), emptySet()
        )!!

        assertSame(list, model.subtitleEntries)
        assertEquals(301, list.size)
        for (index in 0 until position) {
            assertSame(rows[index], list[index])
            assertSame(published[index], model.document.value.entries[index])
            assertSame(history[index], model.historyEntriesSnapshot[index])
        }
        assertSame(rows.last(), list.last())
        assertTrue(change.copiedEntryCount <= 1)
        assertEquals(position, change.ranges.single().start)
        assertEquals(1, change.ranges.single().removedCount)
        assertEquals(list.subList(position, position + 2), change.ranges.single().inserted)
        assertConsistent(model)
    }

    @Test fun srtAndLrcKeepTheOriginalRightEndAndExplicitEndMarkerFlag() {
        val cases = listOf(
            Triple("test.srt", "1\n00:00:01,000 --> 00:00:02,000\nleft right\n\n" +
                "2\n00:00:03,000 --> 00:00:04,000\nnext\n", false),
            Triple("test.lrc", "[00:01.00]left right\n[00:03.00]next\n", false),
            Triple("test.lrc", "[00:01.00]left right\n[00:02.00]\n[00:03.00]next\n[00:04.00]\n", true)
        )
        cases.forEach { (fileName, source, explicitEnd) ->
            val model = loaded(source, fileName)
            val original = model.subtitleEntries.first().copy()
            assertEquals(explicitEnd, original.endTimeModified)

            model.commitListEdit(EditorCommand.Split(0, 1_500L, "left", "right"), emptySet())!!

            val left = model.subtitleEntries[0]
            val right = model.subtitleEntries[1]
            assertEquals(original.startTime, left.startTime)
            assertEquals(1_500L, left.endTime)
            assertTrue(left.endTimeModified)
            assertEquals(1_500L, right.startTime)
            assertEquals(original.endTime, right.endTime)
            assertEquals(explicitEnd, right.endTimeModified)
            val saved = SubtitleParser.parseDocument(
                model.buildSaveContent(requireNonEmptyList = true)!!, fileName
            )
            assertEquals(1_500L, saved.entries[1].startTime)
            assertEquals(original.endTime, saved.entries[1].endTime)
            assertConsistent(model)
        }
    }

    @Test fun invalidSplitsDoNotChangeCuePropertiesOrPublishHistory() {
        val model = loaded(VTT, "test.vtt")
        val rows = model.subtitleEntries.toList()
        val before = rows.map { it.copy() }
        val document = model.document.value
        val history = model.historyEntriesSnapshot
        val commands = listOf(
            EditorCommand.Split(-1, 1_500L, "left", "right"),
            EditorCommand.Split(rows.size, 1_500L, "left", "right"),
            EditorCommand.Split(0, 999L, "left", "right"),
            EditorCommand.Split(0, 1_000L, "left", "right"),
            EditorCommand.Split(0, 2_000L, "left", "right"),
            EditorCommand.Split(0, 2_001L, "left", "right")
        )

        commands.forEach { assertNull(model.commitListEdit(it, emptySet())) }

        assertEquals(before, model.subtitleEntries)
        rows.indices.forEach { assertSame(rows[it], model.subtitleEntries[it]) }
        assertSame(document, model.document.value)
        assertSame(history, model.historyEntriesSnapshot)
        assertEquals(VTT, model.originalFileContent)
        assertFalse(model.hasUnsavedChanges)
        assertNull(model.peekUndo())
        assertNull(model.peekRedo())
    }

    private fun assertSplitVttProperties(model: EditorViewModel, settings: String) {
        model.subtitleEntries.take(2).forEach { entry ->
            assertEquals("original-cue", entry.cueIdentifier)
            assertEquals(settings, entry.cueSettings)
        }
        val sources = listOf(model.originalFileContent, model.buildSaveContent(requireNonEmptyList = true)!!)
        sources.forEach { source ->
            val parsed = SubtitleParser.parseDocument(source, "test.vtt")
            assertEquals(3, parsed.entries.size)
            parsed.entries.take(2).forEach { entry ->
                assertEquals("original-cue", entry.cueIdentifier)
                assertEquals(settings, entry.cueSettings)
            }
            assertEquals(1_500L, parsed.entries[0].endTime)
            assertEquals(1_500L, parsed.entries[1].startTime)
            assertEquals(2_000L, parsed.entries[1].endTime)
        }
    }

    private fun assertConsistent(model: EditorViewModel) {
        assertFalse(model.sourceViewNeedsListSync)
        assertEquals(model.originalFileContent, model.sourceViewContent)
        assertEquals(model.originalFileContent, model.sourceHistoryTextSnapshot)
        assertEquals(model.subtitleEntries, model.historyEntriesSnapshot)
        assertEquals(model.subtitleEntries, model.document.value.entries)
    }

    private fun moveHistory(model: EditorViewModel, undo: Boolean) {
        val apply: (EditorEditHistory.Operation, Boolean) -> Unit = { operation, reversed ->
            model.executeHistoryCommand(operation, reversed)
        }
        assertTrue(if (undo) model.undo(false, apply) else model.redo(false, apply))
    }

    private fun loaded(source: String, fileName: String) = EditorViewModel().apply {
        loadSubtitleContent(source, fileName)
        setHistoryBaseline(EditorEditHistory.ListState(subtitleEntries, emptySet()), source, true)
    }

    private companion object {
        const val SETTINGS = "region:r size:60% vertical:rl position:50% line:80% align:start"
        const val NEIGHBOR = "neighbor\r\n00:00:03.000 --> 00:00:04.000\r\nnext body\r\n"
        const val VTT = "\uFEFFWEBVTT\r\n\r\nREGION\r\nid:r\r\nwidth:60%\r\n\r\n" +
            "original-cue\r\n00:00:01.000 --> 00:00:02.000 $SETTINGS\r\nleft body right body\r\n\r\n" +
            NEIGHBOR + "\r\nNOTE footer\r\nretained\r\n"
    }
}
