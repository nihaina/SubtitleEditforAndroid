package com.subtitleedit

import com.subtitleedit.util.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaConvertLyricTextTest {
    @Test fun existingLrcRetainsMetadataOffsetsAndTimestampPrecision() {
        val content = "[ti:海风]\n[ar:Singer]\n[offset:+250]\n[00:01.125][00:03.375]海风 🌊\n[00:05.500]\n"
        assertEquals(content, MediaConvertLyricText.toLrc("\uFEFF$content", "歌.LRC", 20.0))
    }

    @Test fun srtAndVttUseTheProjectsExistingLrcConversion() {
        val srt = "1\n00:00:01,250 --> 00:00:02,500\n海风 🌊\n\n2\n00:00:04,000 --> 00:00:05,000\nSecond\n"
        val vtt = "WEBVTT\n\n00:00:01.250 --> 00:00:02.500\n<v Singer>海风 🌊\n\n00:00:04.000 --> 00:00:05.000\nSecond\n"
        for ((content, fileName, format) in listOf(
            Triple(srt, "captions.srt", SubtitleParser.SubtitleFormat.SRT),
            Triple(vtt, "captions.vtt", SubtitleParser.SubtitleFormat.VTT)
        )) {
            val expected = SubtitleParser.convertFormat(content, format, SubtitleParser.SubtitleFormat.LRC)
            val output = MediaConvertLyricText.toLrc(content, fileName, null)
            assertEquals(fileName, expected, output)
            assertEquals(listOf(1_250L, 4_000L), SubtitleParser.parseLRC(output).map { it.startTime })
        }
    }

    @Test fun plainTextUsesTheEditorsGeneratedLineTiming() {
        val content = "First line\n\n海风 🌊\n"
        val output = MediaConvertLyricText.toLrc(content, "lyrics.txt", null)
        assertEquals(SubtitleParser.convertFormat(content, SubtitleParser.SubtitleFormat.TXT,
            SubtitleParser.SubtitleFormat.LRC), output)
        val entries = SubtitleParser.parseLRC(output)
        assertEquals(listOf(0L, 3_000L), entries.map { it.startTime })
        assertEquals(listOf("First line", "海风 🌊"), entries.map { it.text })
    }

    @Test fun convertingToLrcHonorsMediaDurationAndKeepsMetadataWhenItClips() {
        val output = MediaConvertLyricText.toLrc(
            "[ti:Song]\n[00:01.00]First\n[00:03.00]Last\n[00:08.00]Outside\n", "lyrics.lrc", 4.25
        )
        val entries = SubtitleParser.parseLRC(output)
        assertTrue(output.contains("[ti:Song]"))
        assertEquals(listOf("First", "Last"), entries.map { it.text })
        assertEquals(4_250L, entries.last().endTime)
    }

    @Test fun unsupportedOrMalformedInputsCannotSilentlyBecomeTimedLyrics() {
        for ((content, name) in listOf(
            "" to "lyrics.txt", "[ti:Metadata only]" to "lyrics.lrc",
            "plain text" to "lyrics.lrc", "plain text" to "broken.srt",
            "plain text" to "broken.vtt", "[Script Info]\nTitle: Film" to "captions.ass"
        )) {
            try {
                MediaConvertLyricText.toLrc(content, name, null)
                throw AssertionError("Expected invalid input to be rejected: $name")
            } catch (_: IllegalArgumentException) {
                // ASS/SSA are normalized to SRT by the caller before using this helper.
            }
        }
    }

    @Test fun normalizedLyricsRoundTripToFiniteVideoSubtitles() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n海风 🌊\n\n2\n00:00:03,000 --> 00:00:04,000\nSecond\n"
        val lrc = MediaConvertLyricText.toLrc(srt, "lyrics.srt", null)
        val entries = SubtitleParser.parseSRT(MediaConvertLyricText.lrcToSrt(lrc, 3.75))
        assertEquals(listOf(1_000L, 3_000L), entries.map { it.startTime })
        assertEquals(listOf("海风 🌊", "Second"), entries.map { it.text })
        assertEquals(3_750L, entries.last().endTime)
        assertTrue(entries.all { it.endTime > it.startTime })
    }

    @Test fun repeatedTimestampsPreserveUnicodeLyricsAndHaveFiniteEnds() {
        val output = MediaConvertLyricText.lrcToSrt(
            "[ti:Song]\n[00:01.00][00:03.00]海风 🌊\n[00:05.00]Last line\n", null
        )
        val entries = SubtitleParser.parseSRT(output)
        assertEquals(listOf(1_000L, 3_000L, 5_000L), entries.map { it.startTime })
        assertEquals(listOf("海风 🌊", "海风 🌊", "Last line"), entries.map { it.text })
        assertTrue(entries.all { it.endTime > it.startTime })
        assertTrue(entries.last().endTime < 20_000L)
    }

    @Test fun knownDurationClipsTheLastCueAndDropsLinesAfterTheAudioEnds() {
        val output = MediaConvertLyricText.lrcToSrt(
            "[00:01.00]First\n[00:03.00]Last audible line\n[00:08.00]Outside audio\n", 4.25
        )
        val entries = SubtitleParser.parseSRT(output)
        assertEquals(listOf("First", "Last audible line"), entries.map { it.text })
        assertEquals(4_250L, entries.last().endTime)
        assertTrue(entries.all { it.endTime > it.startTime && it.endTime <= 4_250L })
    }

    @Test fun absentOrInvalidDurationStillUsesTheParsersFiniteFinalCue() {
        for (duration in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            val entries = SubtitleParser.parseSRT(
                MediaConvertLyricText.lrcToSrt("[00:10.00]End of song\n", duration)
            )
            assertEquals(1, entries.size)
            assertEquals(10_000L, entries.single().startTime)
            assertTrue(entries.single().endTime in 10_001L..20_000L)
        }
    }

    @Test fun untimedOrEntirelyOutOfRangeLyricsAreRejected() {
        for ((content, duration) in listOf(
            "" to null,
            "Plain lyrics without timestamps" to null,
            "[ti:Metadata only]\n[ar:Singer]\n" to null,
            "[00:01.00]" to null,
            "[00:10.00]After the audio\n" to 5.0
        )) {
            try {
                MediaConvertLyricText.lrcToSrt(content, duration)
                throw AssertionError("Expected invalid lyrics to be rejected: $content")
            } catch (_: IllegalArgumentException) {
                // A timed track cannot be created from this input.
            }
        }
    }
}
