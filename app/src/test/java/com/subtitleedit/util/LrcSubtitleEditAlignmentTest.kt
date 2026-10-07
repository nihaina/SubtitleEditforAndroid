package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.subtitle.LrcVariant
import com.subtitleedit.util.subtitle.SubtitleDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression cases for the LRC behaviours shared with Subtitle Edit. */
class LrcSubtitleEditAlignmentTest {

    @Test
    fun inlineTimeTagDoesNotClassifyPlainTextAsLrc() {
        assertEquals(
            SubtitleParser.SubtitleFormat.TXT,
            SubtitleParser.detectFormat("A note containing [00:01.00] a timestamp")
        )
    }

    @Test
    fun metadataAfterFirstCueIsNotPartOfLrcHeader() {
        val document = SubtitleParser.parseDocument(
            "[ti:Title]\n[00:01.00]First\n[ar:Artist]\n[00:02.00]Second",
            "song.lrc"
        )

        assertEquals("[ti:Title]", document.header)
        assertEquals(listOf("First", "Second"), document.entries.map { it.text })
    }

    @Test
    fun offsetMovesBothStartAndEndAfterDurationInference() {
        val entry = SubtitleParser.parseDocument(
            "[offset:-500]\n[00:01.00]First\n[00:03.00]Second",
            "song.lrc"
        ).entries.first()

        assertEquals(500L, entry.startTime)
        assertEquals(2476L, entry.endTime)
    }

    @Test
    fun negativeOffsetClampsShiftedTimesToZero() {
        val entry = SubtitleParser.parseDocument(
            "[offset:-5000]\n[00:01.00]First",
            "song.lrc"
        ).entries.first()

        assertEquals(0L, entry.startTime)
        assertEquals(0L, entry.endTime)
    }

    @Test
    fun millisecondVariantSurvivesParseAndSerialization() {
        val source = "[00:01.234]First\n[00:03.500]Second"
        val document = SubtitleParser.parseDocument(source, "song.lrc")

        assertEquals(LrcVariant.MILLISECONDS, document.lrcVariant)
        assertEquals(1_234L, document.entries.first().startTime)

        val serialized = SubtitleParser.serialize(document)
        assertTrue(serialized.contains("[00:01.234]First"))
        assertTrue(serialized.contains("[00:03.500]Second"))
    }

    @Test
    fun noEndTimeVariantDoesNotWriteSyntheticTerminators() {
        val document = SubtitleDocument(
            format = SubtitleParser.SubtitleFormat.LRC,
            entries = listOf(
                SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "First"),
                SubtitleEntry(startTime = 3_000, endTime = 4_000, text = "Second")
            ),
            lrcVariant = LrcVariant.NO_END_TIME
        )

        val serialized = SubtitleParser.serialize(document)
        assertEquals(
            "[re: Subtitle Edit - LRC No End Time]\n[00:01.00]First\n[00:03.00]Second\n",
            serialized
        )
        assertFalse(serialized.contains("[00:02.00]\n"))

        // The serialized form must identify this variant so a subsequent open/save keeps
        // the no-end-time contract.
        val reparsed = SubtitleParser.parseDocument(serialized, "song.lrc")
        assertEquals(LrcVariant.NO_END_TIME, reparsed.lrcVariant)
    }

    @Test
    fun noEndTimeVariantAddsMarkerAlongsideExistingMetadata() {
        val serialized = SubtitleParser.serialize(
            SubtitleDocument(
                format = SubtitleParser.SubtitleFormat.LRC,
                entries = listOf(SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "First")),
                header = "[ti:Title]",
                lrcVariant = LrcVariant.NO_END_TIME
            )
        )

        assertEquals(
            "[re: Subtitle Edit - LRC No End Time]\n[ti:Title]\n[00:01.00]First\n",
            serialized
        )
    }

    @Test
    fun sourceSynchronizerKeepsNoEndTimeVariantWhenTextChanges() {
        val source = "[re: Subtitle Edit - LRC No End Time]\n[00:01.00]First\n[00:03.50]Second\n"
        val oldEntries = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            oldEntries,
            oldEntries.mapIndexed { index, entry -> entry.copy(text = "Updated $index") }
        )

        assertTrue(updated.contains("[00:01.00]Updated 0"))
        assertTrue(updated.contains("[00:03.50]Updated 1"))
        assertFalse(updated.contains("[00:02.00]\n"))
    }

    @Test
    fun sourceSynchronizerPreservesMillisecondPrecisionWhenTextChanges() {
        val source = "[00:01.234]First\n[00:03.500]Second\n"
        val oldEntries = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            oldEntries,
            oldEntries.map { it.copy(text = "Changed") }
        )

        assertTrue(updated.contains("[00:01.234]Changed"))
        assertTrue(updated.contains("[00:03.500]Changed"))
    }
}
