package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSourceLineEditControllerTest {
    @Test
    fun srtLineSplitReplacesOnlyTheTouchedCue() {
        val before = "1\n00:00:01,000 --> 00:00:02,000\nHello\n\n2\n00:00:03,000 --> 00:00:04,000\nWorld"
        val after = "1\n00:00:01,000 --> 00:00:02,000\nHello\nAgain\n\n2\n00:00:03,000 --> 00:00:04,000\nWorld"
        val entries = SubtitleParser.parseDocument(before, format = SubtitleParser.SubtitleFormat.SRT).entries
            .map { it.copy() }
        val lines = after.split('\n')
        val controller = controller(SubtitleParser.SubtitleFormat.SRT, lines, entries)

        val update = controller.resolveBlock(before, after, startLine = 2, oldLineCount = 1, newLineCount = 2)

        assertNotNull(update)
        assertEquals(0, update!!.entryIndex)
        assertEquals(1, update.oldEntryCount)
        assertEquals("Hello\nAgain", update.entries.single().text)
    }

    @Test
    fun vttCueSettingsAndTextAreParsedAsOneBlock() {
        val before = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello"
        val after = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000 align:start\nHello <b>there</b>"
        val entries = SubtitleParser.parseDocument(before, format = SubtitleParser.SubtitleFormat.VTT).entries
            .map { it.copy() }
        val lines = after.split('\n')
        val controller = controller(SubtitleParser.SubtitleFormat.VTT, lines, entries)

        val update = controller.resolveBlock(before, after, startLine = 2, oldLineCount = 1, newLineCount = 1)

        assertNotNull(update)
        assertEquals("align:start", update!!.entries.single().cueSettings)
        assertEquals("Hello <b>there</b>", update.entries.single().text)
    }

    @Test
    fun txtLineInsertionProducesOneNewEntryWithoutParsingTheDocument() {
        val before = "one\ntwo"
        val after = "one\nnew\ntwo"
        val entries = SubtitleParser.parseDocument(before, format = SubtitleParser.SubtitleFormat.TXT).entries
            .map { it.copy() }
        val lines = after.split('\n')
        val controller = controller(SubtitleParser.SubtitleFormat.TXT, lines, entries)

        val update = controller.resolveBlock(before, after, startLine = 1, oldLineCount = 1, newLineCount = 2)

        assertNotNull(update)
        assertEquals(1, update!!.entryIndex)
        assertEquals(1, update.oldEntryCount)
        assertEquals(listOf("new", "two"), update.entries.map { it.text })
    }

    @Test
    fun txtEditKeepsSyntheticTimelineRelativeToTheDocument() {
        val before = "one\ntwo\nthree"
        val after = "one\nchanged\nthree"
        val entries = SubtitleParser.parseDocument(before, format = SubtitleParser.SubtitleFormat.TXT).entries
            .map { it.copy() }
        val lines = after.split('\n')
        val controller = controller(SubtitleParser.SubtitleFormat.TXT, lines, entries)

        val update = controller.resolveBlock(before, after, startLine = 1, oldLineCount = 1, newLineCount = 1)

        assertNotNull(update)
        assertEquals(3_000L, update!!.entries.single().startTime)
        assertEquals(6_000L, update.entries.single().endTime)
    }

    @Test
    fun sourceBlockSplitDoesNotReuseFollowingStableId() {
        val before = "1\n00:00:01,000 --> 00:00:02,000\nOld\n\n2\n00:00:03,000 --> 00:00:04,000\nFollowing"
        val after = "1\n00:00:01,000 --> 00:00:01,500\nLeft\n2\n00:00:01,500 --> 00:00:02,000\nRight\n\n2\n00:00:03,000 --> 00:00:04,000\nFollowing"
        val entries = SubtitleParser.parseDocument(before, format = SubtitleParser.SubtitleFormat.SRT).entries
            .map { it.copy() }
        val lines = after.split('\n')
        val controller = controller(SubtitleParser.SubtitleFormat.SRT, lines, entries)

        val update = controller.resolveBlock(
            before,
            after,
            startLine = 2,
            oldLineCount = 1,
            newLineCount = 1,
            oldStartOffset = before.indexOf("Old"),
            newStartOffset = after.indexOf("Left")
        )

        assertNotNull(update)
        assertEquals(2, update!!.entries.size)
        assertEquals(2, update.entries.map { it.stableId }.distinct().size)
        assertTrue(update.entries.none { it.stableId == entries[1].stableId })
    }

    @Test
    fun lrcOffsetRequiresDocumentWideReparse() {
        val content = "[offset:500]\n[00:01.00]One\n[00:02.00]Two"
        val lines = content.split('\n')
        val controller = controller(
            SubtitleParser.SubtitleFormat.LRC,
            lines,
            SubtitleParser.parseDocument(content, format = SubtitleParser.SubtitleFormat.LRC).entries
        )

        controller.prepareDocument(content, SubtitleParser.SubtitleFormat.LRC)

        assertTrue(controller.requiresFullParse(content, content.replace("500", "700"), SubtitleParser.SubtitleFormat.LRC, 8, 11, 11))
    }

    private fun controller(
        format: SubtitleParser.SubtitleFormat,
        lines: List<String>,
        entries: List<SubtitleEntry>
    ) = EditorSourceLineEditController(
        lineCount = { lines.size },
        lineText = { lines[it] },
        currentFormat = { format },
        entries = { entries }
    )
}
