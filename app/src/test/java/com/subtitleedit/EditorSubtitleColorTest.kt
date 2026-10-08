package com.subtitleedit

import com.subtitleedit.util.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSubtitleColorTest {
    @Test
    fun vttColorSynchronizesRawSourceAndRestoresHeaderInOneUndo() {
        val source = "\uFEFFWEBVTT\r\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:90000\r\n\r\n" +
            "NOTE keep this\r\nmetadata\r\n\r\n" +
            "cue-id\r\n00:00:01.000 --> 00:00:02.000 align:start\r\n<i>Hello</i>\r\n\r\n" +
            "NOTE footer\r\nuntouched\r\n"
        val model = EditorViewModel()
        model.loadSubtitleContent(source, "test.vtt")
        val original = model.subtitleDocument
        val selected = setOf(original.entries.single().stableId)

        val result = model.applySubtitleColor(setOf(0), 0x80FF0000.toInt(), selected)
        val colored = model.subtitleDocument
        val coloredSource = model.sourceViewContent

        assertEquals(setOf(0), result.changedPositions)
        assertTrue(colored.header.contains("::cue(.ff000080)"))
        assertTrue(coloredSource.startsWith("\uFEFFWEBVTT\r\nX-TIMESTAMP-MAP="))
        assertTrue(coloredSource.contains("cue-id\r\n00:00:01.000 --> 00:00:02.000 align:start"))
        assertTrue(coloredSource.endsWith("NOTE footer\r\nuntouched\r\n"))
        assertEquals(colored.header, SubtitleParser.parseDocument(coloredSource, "test.vtt").header)
        assertEquals(original.entries.single().stableId, colored.entries.single().stableId)
        assertEquals(original.entries.single().startTime, colored.entries.single().startTime)
        assertEquals(original.entries.single().cueSettings, colored.entries.single().cueSettings)
        assertFalse(model.sourceViewNeedsListSync)
        assertTrue(model.buildSaveContent()!!.contains("::cue(.ff000080)"))

        assertTrue(model.undo(false) { command, undo ->
            val restored = model.executeHistoryCommand(command, undo)
            assertEquals(selected, restored.selectedIds)
        })
        assertEquals(original, model.subtitleDocument)
        assertEquals(source, model.sourceViewContent)
        assertNull(model.peekUndo())

        assertTrue(model.redo(false) { command, undo -> model.executeHistoryCommand(command, undo) })
        assertEquals(colored, model.subtitleDocument)
        assertEquals(coloredSource, model.sourceViewContent)
    }

    @Test
    fun repeatedColorAndBlankCuesDoNotCreateHistory() {
        val model = EditorViewModel()
        model.loadSubtitleContent("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n", "test.vtt")
        model.applySubtitleColor(setOf(0), 0xFFFFFF00.toInt(), emptySet())
        val first = model.peekUndo()
        val source = model.sourceViewContent

        assertTrue(model.applySubtitleColor(setOf(0), 0xFFFFFF00.toInt(), emptySet()).changedPositions.isEmpty())
        assertEquals(first, model.peekUndo())
        assertEquals(source, model.sourceViewContent)

        val blank = EditorViewModel()
        blank.loadSubtitleContent("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n \n", "test.vtt")
        assertTrue(blank.applySubtitleColor(setOf(0, 99), 0xFFFF0000.toInt(), emptySet()).changedPositions.isEmpty())
        assertNull(blank.peekUndo())
    }

    @Test
    fun srtBatchColorPreservesBlankRowsAndUnselectedRows() {
        val model = EditorViewModel()
        model.loadSubtitleContent(
            "1\n00:00:01,000 --> 00:00:02,000\n<b>Hello</b>\n\n" +
                "2\n00:00:02,000 --> 00:00:03,000\n \n\n" +
                "3\n00:00:03,000 --> 00:00:04,000\nUnselected\n",
            "test.srt"
        )
        val original = model.subtitleDocument

        model.applySubtitleColor(setOf(0, 1), 0x8000FF00.toInt(), emptySet())

        assertEquals(3, model.subtitleEntries.size)
        assertEquals("<font color=\"#00FF00\"><b>Hello</b></font>", model.subtitleEntries[0].text)
        assertEquals(original.entries[1], model.subtitleEntries[1])
        assertEquals(original.entries[2], model.subtitleEntries[2])
        assertTrue(model.sourceViewContent.contains("<font color=\"#00FF00\">"))
        assertTrue(model.undo(true) { command, undo -> model.executeHistoryCommand(command, undo) })
        assertEquals(original, model.subtitleDocument)
    }

    @Test
    fun missingVttRulePreservesExistingClassAndAddsAnUndoableColor() {
        val model = EditorViewModel()
        model.loadSubtitleContent(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<c.ffff00ff>Hello</c>\n",
            "test.vtt"
        )
        val before = model.subtitleDocument

        val result = model.applySubtitleColor(setOf(0), 0xFFFFFF00.toInt(), emptySet())

        assertEquals(setOf(0), result.changedPositions)
        assertEquals("<c.ffff00ff.ffff00ff-2>Hello</c>", model.subtitleDocument.entries.single().text)
        assertTrue(model.peekUndoWithoutSelection() != null)
        assertTrue(model.undo(true) { command, undo -> model.executeHistoryCommand(command, undo) })
        assertEquals(before, model.subtitleDocument)
    }

    @Test
    fun headerOnlyChangeIsRecordedAndRestoredInSourceMode() {
        val model = EditorViewModel()
        model.loadSubtitleContent("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n", "test.vtt")
        val beforeSource = model.sourceViewContent
        val state = EditorEditHistory.ListState(model.subtitleEntries.map { it.copy() }, emptySet())
        val afterHeader = "WEBVTT\n\nSTYLE\n::cue { color:red; }"
        val afterSource = beforeSource.replace("WEBVTT", afterHeader)
        model.documentHeader = afterHeader
        model.sourceViewContent = afterSource
        assertTrue(model.recordListHistory(state, state, "STYLE", beforeSource, afterSource, "WEBVTT", afterHeader))
        assertFalse(model.peekUndo()!!.isSelectionOnly())
        assertTrue(model.undo(true) { command, undo -> model.executeHistoryCommand(command, undo) })
        assertEquals("WEBVTT", model.documentHeader)
        assertEquals(beforeSource, model.sourceViewContent)
        assertTrue(model.redo(true) { command, undo -> model.executeHistoryCommand(command, undo) })
        assertEquals(afterHeader, model.documentHeader)
        assertEquals(afterSource, model.sourceViewContent)
    }

    @Test
    fun sourceHistoryWithCachedCuesAlsoRestoresColorStyleMetadata() {
        val before = "WEBVTT\n\nSTYLE\n::cue(.red) { color:red; }\n\n" +
            "00:00:01.000 --> 00:00:02.000\n<c.red>Hello</c>\n"
        val after = before.replace("color:red", "color:blue")
        val model = EditorViewModel()
        model.loadSubtitleContent(after, "test.vtt")
        val command = EditorEditHistory.Operation.SourceChange(
            beforeText = before,
            afterText = after,
            description = "STYLE",
            beforeEntries = model.subtitleEntries.map { it.copy() },
            beforeEntriesText = before,
            afterEntries = model.subtitleEntries.map { it.copy() },
            afterEntriesText = after
        )

        model.executeHistoryCommand(command, undo = true)
        assertTrue(model.document.value.header.contains("color:red"))
        model.executeHistoryCommand(command, undo = false)
        assertTrue(model.document.value.header.contains("color:blue"))
    }
}
