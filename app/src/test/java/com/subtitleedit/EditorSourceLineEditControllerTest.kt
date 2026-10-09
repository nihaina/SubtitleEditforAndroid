package com.subtitleedit

import com.subtitleedit.editor.EditorSourceLineEditController
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EditorSourceLineEditControllerTest {
    @Test
    fun resolvesTextEditToSrtCueAndPreservesStableId() {
        val lines = mutableListOf(
            "1", "00:00:00,000 --> 00:00:01,000", "旧文本", "",
            "2", "00:00:02,000 --> 00:00:03,000", "第二条"
        )
        val first = SubtitleEntry(1, 0, 1000, "旧文本", stableId = 77L)
        val second = SubtitleEntry(2, 2000, 3000, "第二条", stableId = 88L)
        val controller = EditorSourceLineEditController(
            lineCount = { lines.size },
            lineText = { lines[it] },
            currentFormat = { SubtitleParser.SubtitleFormat.SRT },
            entries = { listOf(first, second) }
        )

        lines[2] = "新文本"
        val update = controller.resolve(2, 1, 1)

        assertNotNull(update)
        assertEquals(0, update!!.entryIndex)
        assertEquals("新文本", update.entry.text)
        assertEquals(77L, update.entry.stableId)
    }

    @Test
    fun vttMappedTextEditWaitsForWholeDocumentParsing() {
        val source = "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000\n\n00:01.000 --> 00:02.000\nOld\n"
        val lines = source.trimEnd().split('\n').toMutableList()
        val entries = SubtitleParser.parseVTT(source)
        val controller = EditorSourceLineEditController(
            lineCount = { lines.size },
            lineText = { lines[it] },
            currentFormat = { SubtitleParser.SubtitleFormat.VTT },
            entries = { entries }
        )

        lines[4] = "Changed"
        assertNull(controller.resolve(4, 1, 1))
        val reparsed = SubtitleParser.parseVTT(lines.joinToString("\n")).single()
        assertEquals(11_000L, reparsed.startTime)
        assertEquals("Changed", reparsed.text)
    }

    @Test
    fun vttMergedCueEditDoesNotReplaceTheFollowingSubtitle() {
        val source = "WEBVTT\n\n00:01.000 --> 00:02.000\nFirst\n\n00:01.000 --> 00:02.000\nSecond\n\n00:03.000 --> 00:04.000\nLast\n"
        val lines = source.trimEnd().split('\n').toMutableList()
        val entries = SubtitleParser.parseVTT(source)
        assertEquals(2, entries.size)
        val controller = EditorSourceLineEditController(
            lineCount = { lines.size },
            lineText = { lines[it] },
            currentFormat = { SubtitleParser.SubtitleFormat.VTT },
            entries = { entries }
        )

        lines[6] = "Changed"
        assertNull(controller.resolve(6, 1, 1))
        val reparsed = SubtitleParser.parseVTT(lines.joinToString("\n"))
        assertEquals("First\nChanged", reparsed[0].text)
        assertEquals("Last", reparsed[1].text)
    }
}
