package com.subtitleedit.player

import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SubtitleParser
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PlayerLyricTimelineTest {
    @Test fun lrcUsesSharedOffsetsDuplicateEntriesAndInferredEndsWithoutRewriting() {
        val text = "[offset:+250]\n[00:01.125][00:12.375] 海风 🌊  \n[00:12.375]Translation\n[00:25.500]Last"
        assertSharedDocument(text, "song.lrc", SubtitleParser.SubtitleFormat.LRC)
        val lines = PlayerLyricTimeline.parse(text, "song.lrc", 90_000L)
        assertEquals(4, lines.size)
        assertEquals(2, lines.count { it.startMs == 12_625L })
        assertNotEquals("A player must not extend the parser's final cue to media duration", 90_000L, lines.last().endMs)
    }

    @Test fun lrcExplicitClearMarkersFollowTheExistingParser() {
        val text = "[00:01.00]Original\n[00:01.00]Translation\n[00:05.00]\n[00:08.00]Last"
        assertSharedDocument(text, "song.lrc", SubtitleParser.SubtitleFormat.LRC)
        val expected = SubtitleParser.parseDocument(text, "song.lrc", SubtitleParser.SubtitleFormat.LRC).entries
        assertEquals(expected.size, PlayerLyricTimeline.parse(text, "song.lrc", 30_000L).size)
    }

    @Test fun srtPreservesSharedEmptyCuesInternalBlankLinesAndDocumentOrder() {
        val text = "1\n00:00:10,000 --> 00:00:15,000\n  <i>First</i> &amp; <br/>tail\n\nInside the same cue\n\n" +
            "2\n00:00:01,000 --> 00:00:03,000\n\n" +
            "3\n00:00:01,000 --> 00:00:05,000\n[00:20.00]Dialogue\n"
        assertSharedDocument(text, "song.srt", SubtitleParser.SubtitleFormat.SRT)
        val lines = PlayerLyricTimeline.parse(text, "song.srt", 2_000L)
        assertEquals(10_000L, lines.first().startMs)
        assertTrue("The shared parser's blank cue must remain in the lyric list", lines.any { it.text.isEmpty() })
        assertTrue(lines.first().text.contains("\n\n"))
        assertTrue(lines.first().text.contains("<i>First</i> &amp; <br/>tail"))
        assertEquals(15_000L, lines.first().endMs)
    }

    @Test fun vttUsesTheSharedEntityAndCueProcessingRulesWithoutASecondDisplayPass() {
        val text = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n<v Singer><b>Hello</b> &amp; &lt;world&gt;<br/>Next</v>\n\n" +
            "00:00:10.000 --> 00:00:15.000\n[00:20.00]Dialogue\n"
        assertSharedDocument(text, "song.vtt", SubtitleParser.SubtitleFormat.VTT)
        assertEquals(15_000L, PlayerLyricTimeline.parse(text, "song.vtt", 12_000L).last().endMs)
    }

    @Test fun assAndSsaKeepExactTextFieldsIncludingMarkupEscapesWhitespaceAndEmptyEvents() {
        for (extension in listOf("ass", "ssa")) {
            val textField = "  {\\i1}Hello, world{\\i0}\\NSecond\\hline  "
            val text = "[Script Info]\nScriptType: v4.00+\n[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:10.25,0:00:14.50,Default,,0,0,0,,$textField\n" +
                "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,\n"
            assertEquals(listOf(PlayerLyricLine(10_250L, 14_500L, textField), PlayerLyricLine(1_000L, 3_000L, "")),
                PlayerLyricTimeline.parse(text, "song.$extension", 2_000L))
        }
    }

    @Test fun activeLineWorksWithOriginalDocumentOrderAndLatestStartingOverlap() {
        val lines = listOf(PlayerLyricLine(1_200L, 1_500L, "Third"), PlayerLyricLine(500L, 700L, "Second"),
            PlayerLyricLine(100L, 1_000L, "First"), PlayerLyricLine(500L, 700L, "Same start"))
        assertEquals(-1, PlayerLyricTimeline.activeLineIndex(lines, 99L))
        assertEquals(2, PlayerLyricTimeline.activeLineIndex(lines, 100L))
        assertEquals(3, PlayerLyricTimeline.activeLineIndex(lines, 600L))
        assertEquals(2, PlayerLyricTimeline.activeLineIndex(lines, 700L))
        assertEquals(-1, PlayerLyricTimeline.activeLineIndex(lines, 1_000L))
        assertEquals(0, PlayerLyricTimeline.activeLineIndex(lines, 1_200L))
        assertEquals(-1, PlayerLyricTimeline.activeLineIndex(emptyList(), 1_200L))
    }

    @Test fun malformedTimedDocumentsAreRejectedWithoutInventedTextTimings() {
        for ((text, fileName) in listOf("Words" to "song.txt", "Words" to "song.srt", "[00:broken]Words" to "song.lrc",
            "WEBVTT\nBroken" to "song.vtt", "[Events]\nDialogue: broken" to "song.ass")) {
            assertThrows(IllegalArgumentException::class.java) { PlayerLyricTimeline.parse(text, fileName, 30_000L) }
        }
        val outside = "[00:20.00]Outside"
        assertSharedDocument(outside, "song.lrc", SubtitleParser.SubtitleFormat.LRC)
        assertEquals(20_000L, PlayerLyricTimeline.parse(outside, "song.lrc", 10_000L).single().startMs)
    }

    @Test fun fileReadingUsesTheProjectEncodingReaderAndSharedParser() {
        val directory = Files.createTempDirectory("player-lyric-encoding").toFile()
        try {
            val file = java.io.File(directory, "song.srt")
            for ((text, charset) in listOf(
                "1\r\n00:00:01,000 --> 00:00:03,000\r\n你好，世界\r\n" to Charsets.UTF_8,
                "\uFEFF1\n00:00:01,000 --> 00:00:03,000\nUnicode 🌊\n" to Charsets.UTF_16LE
            )) {
                file.writeText(text, charset)
                val expected = SubtitleParser.parseDocument(FileUtils.readFile(file), file.name, SubtitleParser.SubtitleFormat.SRT)
                    .entries.map { PlayerLyricLine(it.startTime, it.endTime, it.text) }
                assertEquals(expected, PlayerLyricTimeline.read(file, 30_000L))
            }
        } finally { directory.deleteRecursively() }
    }

    private fun assertSharedDocument(text: String, name: String, format: SubtitleParser.SubtitleFormat) {
        val expected = SubtitleParser.parseDocument(text, name, format).entries
            .map { PlayerLyricLine(it.startTime, it.endTime, it.text) }
        assertTrue("Fixture must parse as a timed document", expected.isNotEmpty())
        assertEquals(expected, PlayerLyricTimeline.parse(text, name, 2_000L))
    }
}
