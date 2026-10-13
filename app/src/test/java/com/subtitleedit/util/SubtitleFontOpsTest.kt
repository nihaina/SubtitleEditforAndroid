package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.util.subtitle.WebVttPreviewWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFontOpsTest {
    @Test
    fun matchesSubtitleEditNonAssFontInsertionRules() {
        assertEquals(
            "{\\an8}<font face=\"Noto Sans\">Hello</font>",
            SubtitleFontOps.setFontName("{\\an8}Hello", "Noto Sans")
        )
        assertEquals(
            "<font face=\"Noto Sans\" color=\"red\">Hello</font>",
            SubtitleFontOps.setFontName("<font color=\"red\">Hello</font>", "Noto Sans")
        )
        assertEquals(
            "<font face=\"Noto Sans\" color=\"red\">Hello</font>",
            SubtitleFontOps.setFontName("<font face=\"Arial\" color=\"red\">Hello</font>", "Noto Sans")
        )
    }

    @Test
    fun fontNameSurvivesSrtAndVttSerialization() {
        val srt = SubtitleDocument(
            SubtitleFormat.SRT,
            listOf(SubtitleEntry(startTime = 1_000, endTime = 2_000, text = SubtitleFontOps.setFontName("Hello", "Arial")))
        )
        assertTrue(SubtitleParser.serialize(srt).contains("<font face=\"Arial\">Hello</font>"))

        val vtt = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(startTime = 1_000, endTime = 2_000, text = SubtitleFontOps.setFontName("Hello", "Noto Sans")))
        )
        assertTrue(SubtitleParser.serialize(vtt).contains("<font face=\"Noto Sans\">Hello</font>"))
    }

    @Test
    fun inlineVttFontIsConvertedToAssFontOverrideForVideoPreview() {
        val text = SubtitleFontOps.setFontName("Hello", "Noto Sans")
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(startTime = 1_000, endTime = 2_000, text = text))
        )
        val preview = WebVttPreviewWriter.write(document)
        assertTrue(preview.contains("{\\fnNoto Sans}"))
    }

    @Test
    fun assUsesFontOverrideAndLrcWriterRemovesHtmlFontTags() {
        assertEquals(
            "{\\fnNoto Sans}{\\an8}Hello",
            SubtitleFontOps.setFontName("{\\fnArial}{\\an8}Hello", "Noto Sans", SubtitleFormat.ASS)
        )

        val lrc = SubtitleParser.serialize(
            SubtitleDocument(
                SubtitleFormat.LRC,
                listOf(SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "<font face=\"Arial\">Hello</font>"))
            )
        )
        assertTrue(lrc.contains("[00:01.00]Hello"))
        assertTrue(!lrc.contains("<font"))
    }
}
