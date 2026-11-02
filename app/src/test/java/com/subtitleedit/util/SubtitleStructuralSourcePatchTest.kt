package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStructuralSourcePatchTest {
    @Test fun srtInsertionRenumbersSurvivingRawCueWithoutRewritingTimelineOrBody() {
        val source = "\uFEFF  7  \r\n00:00:01.000   -->   00:00:02.000\r\nFirst\r\n\r\n" +
            "  9  \r\n00:00:03,000-->00:00:04,000\r\nSecond\r\n123\r\nlast line  \t\r\n\r\n"
        val old = SubtitleParser.parseSRT(source)
        val inserted = SubtitleEntry(index = 2, startTime = 2_200, endTime = 2_800, text = "inserted")
        val updated = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.SRT, old,
            listOf(old[0], inserted, old[1].copy(index = 3))
        )
        assertTrue(updated.startsWith(source.substringBefore("  9  \r\n")))
        assertTrue(updated.endsWith("  3  \r\n00:00:03,000-->00:00:04,000\r\nSecond\r\n123\r\nlast line  \t\r\n\r\n"))
        assertEquals(listOf("First", "inserted", old[1].text), SubtitleParser.parseSRT(updated).map { it.text })
    }

    @Test fun srtDeletionRenumbersSuffixWithLoneCarriageReturnLineEndings() {
        val source = "1\r00:00:01,000 --> 00:00:02,000\rFirst\r\r" +
            "2\r00:00:03,000-->00:00:04,000\rSecond\rcontinued\r\r"
        val old = SubtitleParser.parseSRT(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.SRT, old, listOf(old[1].copy(index = 1))
        )
        assertEquals("1\r00:00:03,000-->00:00:04,000\rSecond\rcontinued\r\r", updated)
    }

    @Test fun vttDeletionKeepsSurvivingCueBytesAndInterveningSectionsAfterRowNumbersShift() {
        val source = "WEBVTT\r\n\r\nfirst-id\r\n00:01.000 --> 00:02.000\r\nFirst\r\n\r\n" +
            "NOTE retained section\r\nmetadata\r\n\r\n" +
            "second-id\r\n00:03.000   -->   00:04.000 line:90%\r\n&lt;i&gt;Second&lt;/i&gt;\r\n\r\n" +
            "NOTE footer\r\nretained\r\n"
        val old = SubtitleParser.parseVTT(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.VTT, old, listOf(old[1].copy(index = 1))
        )
        assertEquals(
            "WEBVTT\r\n\r\nNOTE retained section\r\nmetadata\r\n\r\n" +
                "second-id\r\n00:03.000   -->   00:04.000 line:90%\r\n&lt;i&gt;Second&lt;/i&gt;\r\n\r\n" +
                "NOTE footer\r\nretained\r\n",
            updated
        )
    }

    @Test fun changedBodyInRenumberedSrtCueStillUsesItsTextPatch() {
        val source = "1\n00:00:01,000 --> 00:00:02,000\nFirst\n\n" +
            "2\n00:00:03,000-->00:00:04,000\nSecond\n\n"
        val old = SubtitleParser.parseSRT(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.SRT, old,
            listOf(old[1].copy(index = 1, text = "Changed\nbody"))
        )
        assertEquals("1\n00:00:03,000-->00:00:04,000\nChanged\nbody\n\n", updated)
    }
}
