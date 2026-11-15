package com.subtitleedit.player

import com.subtitleedit.MediaConvertEmbeddedLyric
import com.subtitleedit.MediaConvertEmbeddedLyricReader
import com.subtitleedit.MediaConvertId3Lyrics
import com.subtitleedit.MediaConvertLyric
import com.subtitleedit.util.SubtitleParser
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PlayerEmbeddedLyricsTest {
    @Test fun generatedMp3RepresentationsCollapseWithoutRewritingTheOriginalVersion() {
        val root = Files.createTempDirectory("player-id3-versions").toFile()
        try {
            val file = java.io.File(root, "song.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            MediaConvertId3Lyrics.writeMp3(file, listOf(
                MediaConvertLyric("Original", "[00:00.00]First\n[00:10.00]Last"),
                MediaConvertLyric("Translation", "[00:00.00]First\n[00:10.00]Last")
            ))
            val raw = file.inputStream().use(MediaConvertEmbeddedLyricReader::read)
            assertEquals(4, raw.size)
            val versions = PlayerEmbeddedLyrics.prepare(raw, 30_000L)
            assertEquals(listOf("Original", "Translation"), versions.map { it.source.title })
            val synchronizedOnly = PlayerEmbeddedLyrics.prepare(raw.filter { it.field == "SYLT" }, 30_000L)
            assertEquals(2, synchronizedOnly.size)
            synchronizedOnly.forEach { prepared ->
                assertEquals(SubtitleParser.toSRT(SubtitleParser.parseLRC(prepared.source.text)), prepared.srt)
            }
            versions.forEach { prepared ->
                assertEquals("USLT", prepared.source.field)
                assertEquals(SubtitleParser.toSRT(SubtitleParser.parseLRC(prepared.source.text)), prepared.srt)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun timedDialogueContainingLrcTextRetainsSrtAndVttTiming() {
        for (header in listOf("1\n00:00:01,000 --> 00:00:03,000", "WEBVTT\n\n00:00:01.000 --> 00:00:03.000")) {
            val entries = prepared("$header\n[00:10.00]meeting starts\n", 30_000L)
            assertEquals(1_000L, entries.single().startTime)
            assertEquals(3_000L, entries.single().endTime)
            assertEquals("[00:10.00]meeting starts", entries.single().text)
        }
    }

    @Test fun lrcUsesExistingOffsetsTimingAndRepeatedTagsWithoutPlayerRewrites() {
        val text = "[ti:Song]\n[offset:+250]\n[00:01.125][00:12.375]海风 🌊\n[00:25.500]Last\n"
        val output = PlayerEmbeddedLyrics.prepare(listOf(lyric(text)), 90_000L).single()
        assertEquals(SubtitleParser.toSRT(SubtitleParser.parseLRC(text)), output.srt)
    }

    @Test fun explicitEmptyTimestampEndsTheFinalLrcLine() {
        val entries = prepared("[00:01.00]First\n[00:05.00]\n", 60_000L)
        assertEquals(1, entries.size)
        assertEquals(5_000L, entries.single().endTime)
    }

    @Test fun lrcWithoutFractionalDigitsStillUsesExistingTolerantReader() {
        val entries = prepared("[00:01]First\n[00:10]Second", 20_000L)
        assertEquals(listOf(1_000L, 10_000L), entries.map { it.startTime })
        assertEquals(SubtitleParser.parseLRC("[00:01]First\n[00:10]Second").last().endTime, entries.last().endTime)
    }

    @Test fun untimedTextHasOneWholeTrackCueWithoutInventedLineTimings() {
        val entries = prepared("First line\r\n\r\nSecond line 🌊", 120_000L)
        assertEquals(1, entries.size)
        assertEquals(0L, entries.single().startTime)
        assertEquals(120_000L, entries.single().endTime)
        assertEquals("First line\n\nSecond line 🌊", entries.single().text)
        assertTrue(PlayerEmbeddedLyrics.prepare(listOf(lyric("Untimed text")), 0L).isEmpty())
    }

    @Test fun srtAndVttUseExistingParserEntriesWithoutMediaDurationClipping() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nFirst\n\n2\n00:00:04,000 --> 00:00:06,000\nSecond\n"
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nFirst\n\n00:00:04.000 --> 00:00:06.000\nSecond\n"
        for ((text, existing) in listOf(srt to SubtitleParser.parseSRT(srt), vtt to SubtitleParser.parseVTT(vtt))) {
            val output = PlayerEmbeddedLyrics.prepare(listOf(lyric(text)), 5_000L).single()
            assertEquals(SubtitleParser.toSRT(existing), output.srt)
        }
    }

    @Test fun malformedTimedTagsNeverAcquirePlainTextTiming() {
        for (text in listOf("[00:broken]First", "WEBVTT\nBroken timing", "00:broken --> broken\nWords")) {
            assertTrue(text, PlayerEmbeddedLyrics.prepare(listOf(lyric(text)), 30_000L).isEmpty())
        }
        assertTrue(PlayerEmbeddedLyrics.prepare(listOf(lyric("MPEG frame timing is unknown").copy(synced = true)), 30_000L).isEmpty())
    }

    @Test fun simultaneousAndOutOfRangeLinesAreNotCombinedOrRemoved() {
        val text = "[00:01.00]Original\n[00:01.00]Translation\n[00:05.00]Last\n[00:20.00]Outside"
        val output = PlayerEmbeddedLyrics.prepare(listOf(lyric(text)), 10_000L).single()
        assertEquals(SubtitleParser.toSRT(SubtitleParser.parseLRC(text)), output.srt)
        assertEquals(4, SubtitleParser.parseSRT(output.srt).size)
    }

    @Test fun plainTextKeepsBlankLinesIndentationAndMetadataLookingText() {
        val text = "  [ti:Literal title]\n\n  <i>Indented lyric</i>  \n\nSecond &amp; third\n  "
        val output = PlayerEmbeddedLyrics.prepare(listOf(lyric(text)), 30_000L).single()
        assertEquals(text, output.source.text)
        assertEquals(SubtitleParser.toSRT(listOf(com.subtitleedit.model.SubtitleEntry(1, 0L, 30_000L, text))), output.srt)
        assertTrue(output.srt.contains("\n\n  <i>Indented lyric</i>  \n\nSecond &amp; third"))
    }

    @Test fun srtBlankCuesSpacesAndFormattingUseTheExistingSerializerVerbatim() {
        val text = "1\n00:00:01,000 --> 00:00:03,000\n  <b>First</b> &amp; value\n\n\nInside blank lines\n\n2\n00:00:10,000 --> 00:00:15,000\n\n\n3\n00:00:20,000 --> 00:00:25,000\nLast\n"
        val expected = SubtitleParser.toSRT(SubtitleParser.parseSRT(text))
        assertEquals(expected, PlayerEmbeddedLyrics.prepare(listOf(lyric(text)), 5_000L).single().srt)
    }

    @Test fun multipleVersionsGetStablePrivateFilesAndDescriptiveOptions() {
        val root = Files.createTempDirectory("player-embedded-lyrics").toFile()
        try {
            val sources = listOf(
                lyric("[00:01.00]Original").copy(title = "原文", language = "jpn"),
                lyric("[00:01.00]Translation").copy(title = "译文", language = "zho"),
                lyric("[00:02.00]Another version")
            )
            val prepared = PlayerEmbeddedLyrics.prepare(sources, 30_000L)
            val options = PlayerEmbeddedLyrics.writePrepared(prepared, root, "内嵌歌词")
            assertEquals(listOf("内嵌歌词 · 原文 · jpn", "内嵌歌词 · 译文 · zho", "内嵌歌词 · 3"), options.map { it.label })
            assertEquals(3, options.map { it.path }.distinct().size)
            options.forEachIndexed { index, option ->
                assertEquals(PlayerSubtitleKind.EXTERNAL, option.kind)
                assertEquals("external:${option.path}", option.id)
                assertEquals(root.canonicalFile, requireNotNull(java.io.File(option.path).parentFile).canonicalFile)
                assertEquals(prepared[index].srt, java.io.File(option.path).readText())
            }
            assertEquals(options, PlayerEmbeddedLyrics.writePrepared(prepared, root, "内嵌歌词"))
            assertEquals(3, root.listFiles()!!.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun emptyDocumentsDoNotCreateAnyCacheFiles() {
        val root = Files.createTempDirectory("player-empty-lyrics").toFile()
        try {
            val output = PlayerEmbeddedLyrics.writePrepared(PlayerEmbeddedLyrics.prepare(listOf(lyric("  ")), 1_000), root, "内嵌歌词")
            assertTrue(output.isEmpty())
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun lyric(text: String) = MediaConvertEmbeddedLyric("USLT", text = text)
    private fun prepared(text: String, durationMs: Long) = SubtitleParser.parseSRT(
        PlayerEmbeddedLyrics.prepare(listOf(lyric(text)), durationMs).single().srt
    )
}
