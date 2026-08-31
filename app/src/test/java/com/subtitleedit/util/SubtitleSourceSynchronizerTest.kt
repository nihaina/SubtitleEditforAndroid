package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSourceSynchronizerTest {
    @Test
    fun unchangedEntriesKeepSourceByteForByte() {
        val source = "7\r\n00:00:01.000   -->   00:00:02.000\r\nText\r\n"
        val entries = SubtitleParser.parseDocument(
            source,
            format = SubtitleParser.SubtitleFormat.SRT
        ).entries

        assertEquals(
            source,
            SubtitleSourceSynchronizer.apply(
                source,
                SubtitleParser.SubtitleFormat.SRT,
                entries,
                entries.map { it.copy() }
            )
        )
    }

    @Test
    fun srtChangesPatchOriginalCueTextAndTimes() {
        val source = """NOTE keep this header

7
00:00:01.000   -->   00:00:02.000
Old text

9
00:00:03,000 --> 00:00:04,000
Second
"""
        val oldEntries = SubtitleParser.parseDocument(source, format = SubtitleParser.SubtitleFormat.SRT).entries
        val newEntries = oldEntries.mapIndexed { index, entry ->
            entry.copy(
                index = index + 1,
                startTime = entry.startTime + 500,
                text = if (index == 0) "New text" else entry.text
            )
        }

        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.SRT,
            oldEntries,
            newEntries
        )

        assertTrue(updated.startsWith("NOTE keep this header"))
        assertTrue(updated.contains("00:00:01,500   -->   00:00:02,000"))
        assertTrue(updated.contains("New text"))
        assertTrue(updated.contains("Second"))
        assertEquals(newEntries.map { it.text }, SubtitleParser.parseSRT(updated).map { it.text })
    }

    @Test
    fun vttChangesKeepHeaderAndCueMetadata() {
        val source = "WEBVTT - demo\n\nSTYLE\n::cue { color: red; }\n\ncue-1\n00:01.000 --> 00:02.000 align:center\nOld\n\nNOTE footer\nkeep me\n"
        val old = SubtitleParser.parseDocument(source, format = SubtitleParser.SubtitleFormat.VTT).entries
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.VTT,
            old,
            listOf(old.single().copy(text = "New", startTime = 1500))
        )

        assertTrue(updated.startsWith("WEBVTT - demo"))
        assertTrue(updated.contains("STYLE\n::cue { color: red; }"))
        assertTrue(updated.contains("NOTE footer\nkeep me"))
        val entry = SubtitleParser.parseVTT(updated).single()
        assertEquals(1500L, entry.startTime)
        assertEquals("cue-1", entry.cueIdentifier)
        assertEquals("align:center", entry.cueSettings)
        assertEquals("New", entry.text)
    }

    @Test
    fun lrcChangesPatchTimedLineInPlace() {
        val source = "[ti:Demo]\n[00:01.00]Old\n[00:03.00]Next\n"
        val old = SubtitleParser.parseDocument(source, format = SubtitleParser.SubtitleFormat.LRC).entries
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0].copy(startTime = 2000, text = "New"), old[1])
        )
        assertTrue(updated.startsWith("[ti:Demo]"))
        assertTrue(updated.contains("[00:02.00]New"))
        assertEquals("New", SubtitleParser.parseLRC(updated).first().text)
    }

    @Test
    fun lrcContiguousCuesKeepMissingTerminatorWhenTextChanges() {
        val source = "[00:01.00]A\n[00:02.00]B\n"
        val old = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0].copy(text = "Changed"), old[1])
        )

        assertEquals("[00:01.00]Changed\n[00:02.00]B\n", updated)
    }

    @Test
    fun lrcContiguousCuesDoNotInsertTerminatorWhenEndIsSetToNextStart() {
        val source = "[00:01.00]A\n[00:02.00]B\n"
        val old = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0].copy(endTime = old[1].startTime), old[1])
        )

        assertEquals(source, updated)
    }

    @Test
    fun lrcChangingContiguousCueToGapInsertsTerminator() {
        val source = "[00:01.00]A\n[00:02.00]B\n"
        val old = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0].copy(endTime = 1_500L), old[1])
        )

        assertEquals("[00:01.00]A\n[00:01.50]\n[00:02.00]B\n", updated)
    }

    @Test
    fun lrcChangingGapToContiguousCueRemovesExistingTerminator() {
        val source = "[00:01.00]A\n[00:01.50]\n[00:02.00]B\n"
        val old = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0].copy(endTime = old[1].startTime), old[1])
        )

        assertEquals("[00:01.00]A\n[00:02.00]B\n", updated)
    }

    @Test
    fun timeBaseMetadataIsNotAppliedTwice() {
        val vtt = "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000\n\n00:01.000 --> 00:02.000\nOld\n"
        val vttOld = SubtitleParser.parseDocument(vtt, format = SubtitleParser.SubtitleFormat.VTT).entries
        val vttUpdated = SubtitleSourceSynchronizer.apply(
            vtt,
            SubtitleParser.SubtitleFormat.VTT,
            vttOld,
            listOf(vttOld.single().copy(startTime = 12_000L))
        )
        assertTrue(vttUpdated.contains("X-TIMESTAMP-MAP"))
        assertEquals(12_000L, SubtitleParser.parseVTT(vttUpdated).single().startTime)

        val lrc = "[offset:500]\n[00:01.00]Old\n"
        val lrcOld = SubtitleParser.parseDocument(lrc, format = SubtitleParser.SubtitleFormat.LRC).entries
        val lrcUpdated = SubtitleSourceSynchronizer.apply(
            lrc,
            SubtitleParser.SubtitleFormat.LRC,
            lrcOld,
            listOf(lrcOld.single().copy(startTime = 2_500L))
        )
        assertTrue(lrcUpdated.contains("[offset:500]"))
        assertEquals(2_500L, SubtitleParser.parseLRC(lrcUpdated).single().startTime)
    }

    @Test
    fun listDeletionRemovesOnlyTheCorrespondingCueData() {
        val source = "1\n00:00:01,000 --> 00:00:02,000\nFirst\n\n2\n00:00:03,000 --> 00:00:04,000\nSecond\n\n"
        val old = SubtitleParser.parseSRT(source)
        val remaining = old[1].copy(index = 1)

        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.SRT,
            old,
            listOf(remaining)
        )

        val reparsed = SubtitleParser.parseSRT(updated)
        assertEquals(1, reparsed.size)
        assertEquals("Second", reparsed.single().text)
        assertEquals(3_000L, reparsed.single().startTime)
    }
}
