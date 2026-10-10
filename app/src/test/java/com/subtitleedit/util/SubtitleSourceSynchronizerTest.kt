package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSourceSynchronizerTest {
    @Test
    fun srtBomAndCrOnlyLinesRemainEditable() {
        val source = "\uFEFF7\r00:00:01,000 --> 00:00:02,000\rA\r\r9\r00:00:03,000 --> 00:00:04,000\rB\r\r"
        val old = SubtitleParser.parseSRT(source)
        assertEquals(SubtitleParser.SubtitleFormat.SRT, SubtitleParser.detectFormat(source))
        val changed = listOf(old[0].copy(index = 1, text = "Changed"), old[1])
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.SRT, old, changed)
        assertEquals(source.replace("\uFEFF7", "\uFEFF1").replace("\rA\r", "\rChanged\r"), updated)
        assertEquals(changed.map { it.text }, SubtitleParser.parseSRT(updated).map { it.text })
    }

    @Test
    fun srtNonstandardTimesRemainEditableWithSuffixAndCrLf() {
        val source = "7\r\n00:04:48\u060C460 -- > 00:04:52\u060C364  X1:100 X2:100\r\nText\r\n\r\n"
        val old = SubtitleParser.parseSRT(source)
        val changed = old.single().copy(startTime = 289000L, endTime = 293000L)
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.SRT, old, listOf(changed))

        assertTrue(updated.contains("00:04:49,000 -- > 00:04:53,000  X1:100 X2:100\r\n"))
        assertEquals(changed.startTime, SubtitleParser.parseSRT(updated).single().startTime)
        assertTrue(updated.startsWith("7\r\n"))
        assertTrue(updated.endsWith("Text\r\n\r\n"))
    }

    @Test
    fun srtMissingHourTimesCanBePatched() {
        val source = "7\n04:48,460 --> 04:52,364\nText\n\n"
        val old = SubtitleParser.parseSRT(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.SRT, old,
            listOf(old.single().copy(startTime = 289000L))
        )
        assertTrue(updated.contains("00:04:49,000 --> 00:04:52,364"))
        assertEquals(289000L, SubtitleParser.parseSRT(updated).single().startTime)
    }

    @Test
    fun srtNumberSeparatedFromTimelineCanBeUpdatedAndDeleted() {
        val source = "5\n\n00:00:01,000 --> 00:00:02,000\nA\n\n9\n00:00:03,000 --> 00:00:04,000\nB\n\n"
        val old = SubtitleParser.parseSRT(source)
        val changed = listOf(old[0].copy(index = 1, text = "Changed"), old[1])
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.SRT, old, changed)
        assertTrue(updated.startsWith("1\n\n00:00:01,000"))
        assertEquals(changed.map { it.text }, SubtitleParser.parseSRT(updated).map { it.text })

        val deleted = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.SRT, old, listOf(old[1]))
        assertEquals("9\n00:00:03,000 --> 00:00:04,000\nB\n\n", deleted)
    }

    @Test
    fun srtNegativeTimeEditsAreNotClamped() {
        val source = "7\n00:00:01,000 --> 00:00:02,000\nA\n\n"
        val old = SubtitleParser.parseSRT(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.SRT, old,
            listOf(old.single().copy(startTime = -1250L))
        )
        assertTrue(updated.contains("-00:00:01,250 --> 00:00:02,000"))
        assertEquals(-1250L, SubtitleParser.parseSRT(updated).single().startTime)
    }

    @Test
    fun srtFrameTimesStayRawForTextEditsAndStableForTimeEdits() {
        val source = "5\r\n00:00:01,12 --> 00:00:02,20\r\nA\r\n\r\n9\r\n00:00:03,06 --> 00:00:04,24\r\nB\r\n\r\n"
        val old = SubtitleParser.parseSRT(source)
        val textOnly = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.SRT, old,
            listOf(old[0].copy(text = "Changed"), old[1])
        )
        assertEquals(source.replace("\r\nA\r\n", "\r\nChanged\r\n"), textOnly)

        val changed = listOf(old[0].copy(startTime = old[0].startTime + 100), old[1])
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.SRT, old, changed)
        val reparsed = SubtitleParser.parseSRT(updated)
        assertEquals(changed.map { it.startTime to it.endTime }, reparsed.map { it.startTime to it.endTime })
        assertTrue(updated.contains("00:00:03,250 --> 00:00:04,999\r\n"))
    }

    @Test
    fun srtInsertionDoesNotChangeUneditedFrameTimes() {
        val source = "5\n00:00:01,12 --> 00:00:02,20\nA\n\n9\n00:00:03,06 --> 00:00:04,24\nB\n\n"
        val old = SubtitleParser.parseSRT(source)
        val inserted = SubtitleEntry(index = 6, startTime = 2900L, endTime = 3000L, text = "New")
        val changed = listOf(old[0], inserted, old[1])
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.SRT, old, changed)
        val reparsed = SubtitleParser.parseSRT(updated)
        assertEquals(changed.map { it.startTime to it.endTime }, reparsed.map { it.startTime to it.endTime })
        assertEquals(changed.map { it.text }, reparsed.map { it.text })
    }

    @Test
    fun srtDeletingMillisecondCueDoesNotReinterpretRemainingTwoDigitTimes() {
        val source = "5\n00:00:01,12 --> 00:00:02,20\nA\n\n9\n00:00:03,500 --> 00:00:04,500\nB\n\n"
        val old = SubtitleParser.parseSRT(source)
        assertEquals(1012L, old[0].startTime)
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.SRT, old, listOf(old[0]))
        val reparsed = SubtitleParser.parseSRT(updated).single()
        assertEquals(old[0].startTime, reparsed.startTime)
        assertEquals(old[0].endTime, reparsed.endTime)
        assertTrue(updated.contains("00:00:01,012 --> 00:00:02,020"))
    }

    @Test
    fun manyInsertedSrtRowsKeepTheirOrderAndOriginalRows() {
        val source = "1\n00:00:01,000 --> 00:00:02,000\nOriginal\n\n"
        val original = SubtitleParser.parseSRT(source).single()
        val inserted = (0 until 1_000).map { index ->
            SubtitleEntry(index = index + 1, startTime = 0, endTime = 500, text = "New $index")
        }
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.SRT,
            listOf(original),
            inserted + original.copy(index = inserted.size + 1)
        )

        val result = SubtitleParser.parseSRT(updated)
        assertEquals(1_001, result.size)
        assertEquals("New 0", result.first().text)
        assertEquals("New 999", result[result.lastIndex - 1].text)
        assertEquals("Original", result.last().text)
    }

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
    fun vttArrowTextIsNotMistakenForCueTiming() {
        val source = "WEBVTT\n\njunk --> text\nmetadata\n\ncue-1\n00:01.000 --> 00:02.000\nOld\n"
        val old = SubtitleParser.parseVTT(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.VTT, old,
            listOf(old.single().copy(text = "Changed"))
        )
        assertTrue(updated.contains("junk --> text\nmetadata"))
        assertEquals("Changed", SubtitleParser.parseVTT(updated).single().text)
    }

    @Test
    fun vttRemovingAlignmentAlsoRemovesItsSourcePositionSettings() {
        val source = "WEBVTT\n\ncue\n00:01.000 --> 00:02.000 line:10% position:20% region:r1\nHello\n"
        val old = SubtitleParser.parseVTT(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.VTT, old,
            listOf(old.single().copy(text = "Hello"))
        )
        val entry = SubtitleParser.parseVTT(updated).single()
        assertEquals("Hello", entry.text)
        assertEquals("region:r1", entry.cueSettings)
        assertEquals("cue", entry.cueIdentifier)
    }

    @Test
    fun vttDeletingCueDirectlyAfterSignatureRetainsSignature() {
        val source = "WEBVTT\n00:01.000 --> 00:02.000\nOld\n"
        val old = SubtitleParser.parseVTT(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.VTT, old, emptyList()
        )
        assertEquals("WEBVTT\n", updated)
    }

    @Test
    fun vttMissingBlankSeparatorsUseParserCueLocations() {
        val source = "WEBVTT\n\n00:01.000 --> 00:02.000\nFirst\n00:03.000 --> 00:04.000\nSecond\n"
        val old = SubtitleParser.parseVTT(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.VTT, old,
            listOf(old[0], old[1].copy(text = "Changed"))
        )
        assertEquals(listOf("First", "Changed"), SubtitleParser.parseVTT(updated).map { it.text })
        val deleted = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.VTT, old, listOf(old[1])
        )
        assertEquals("Second", SubtitleParser.parseVTT(deleted).single().text)
    }

    @Test
    fun vttIntegerMillisecondsStayConsistentDuringTimeEdits() {
        val source = "WEBVTT\n\ncue-1\n  00:01.5   -->   00:02.1234  align:center\nOld\n"
        val old = SubtitleParser.parseVTT(source)
        val changed = listOf(old.single().copy(startTime = old.single().startTime + 100L))
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.VTT, old, changed)
        assertTrue(updated.contains("00:00:01.105   -->   00:00:03.234  align:center"))
        assertEquals(changed.single().startTime, SubtitleParser.parseVTT(updated).single().startTime)
    }

    @Test
    fun vttInsertedCueDoesNotApplyTimestampMapTwice() {
        val source = "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000\n\n00:01.000 --> 00:02.000\nOld\n"
        val old = SubtitleParser.parseVTT(source)
        val inserted = SubtitleEntry(startTime = 13_000L, endTime = 14_000L, text = "New")
        val changed = old + inserted
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.VTT, old, changed)
        assertTrue(updated.contains("X-TIMESTAMP-MAP"))
        assertEquals(changed.map { it.startTime to it.endTime }, SubtitleParser.parseVTT(updated).map { it.startTime to it.endTime })
        assertEquals(listOf("Old", "New"), SubtitleParser.parseVTT(updated).map { it.text })
    }

    @Test
    fun vttInsertingBeforeCueUsesThatCuesTimestampMap() {
        val source = "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000\n\n00:01.000 --> 00:02.000\nFirst\n\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:1800000\n\n00:01.000 --> 00:02.000\nLast\n"
        val old = SubtitleParser.parseVTT(source)
        val inserted = SubtitleEntry(startTime = 20_000L, endTime = 20_500L, text = "New")
        val changed = listOf(old[0], inserted, old[1])
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.VTT, old, changed)
        assertTrue(updated.contains("X-TIMESTAMP-MAP"))
        assertEquals(changed.map { it.startTime to it.endTime }, SubtitleParser.parseVTT(updated).map { it.startTime to it.endTime })
    }

    @Test
    fun vttEditingBeforeMapOriginConsumesMapsAndKeepsOtherCueTimes() {
        val source = "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000\n\ncue-1\n00:01.000 --> 00:02.000 align:center\nFirst\n\nNOTE middle\nkeep me\n\ncue-2\n00:03.000 --> 00:04.000\nLast\n"
        val old = SubtitleParser.parseVTT(source)
        val changed = listOf(old[0].copy(startTime = 1_000L, endTime = 2_000L), old[1])
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.VTT, old, changed)
        assertTrue(!updated.contains("X-TIMESTAMP-MAP"))
        assertTrue(updated.contains("NOTE middle\nkeep me"))
        val reparsed = SubtitleParser.parseVTT(updated)
        assertEquals(changed.map { it.startTime to it.endTime }, reparsed.map { it.startTime to it.endTime })
        assertEquals(listOf("cue-1", "cue-2"), reparsed.map { it.cueIdentifier })
    }

    @Test
    fun vttInsertedCueBeforeMapOriginKeepsAbsoluteTimes() {
        val source = "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000\n\n00:01.000 --> 00:02.000\nOld\n"
        val old = SubtitleParser.parseVTT(source)
        val inserted = SubtitleEntry(startTime = 1_000L, endTime = 2_000L, text = "New")
        val changed = listOf(inserted, old.single())
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.VTT, old, changed)
        assertTrue(!updated.contains("X-TIMESTAMP-MAP"))
        assertEquals(changed.map { it.startTime to it.endTime }, SubtitleParser.parseVTT(updated).map { it.startTime to it.endTime })
    }

    @Test
    fun vttAppendingAfterFooterNoteDoesNotMakeNewCuePartOfNote() {
        val source = "WEBVTT\n\n00:01.000 --> 00:02.000\nOld\n\nNOTE footer\nkeep me"
        val old = SubtitleParser.parseVTT(source)
        val inserted = SubtitleEntry(startTime = 3_000L, endTime = 4_000L, text = "New")
        val updated = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.VTT, old, old + inserted
        )
        assertTrue(updated.contains("NOTE footer\nkeep me"))
        assertEquals(listOf("Old", "New"), SubtitleParser.parseVTT(updated).map { it.text })
    }

    @Test
    fun vttEditedDecodedTextIsEscapedForReload() {
        val source = "WEBVTT\n\n00:01.000 --> 00:02.000\nA &amp; B &lt; C\n"
        val old = SubtitleParser.parseVTT(source)
        val changed = listOf(old.single().copy(text = "Changed & B < C"))
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.VTT, old, changed)
        assertTrue(updated.contains("Changed &amp; B &lt; C"))
        assertEquals(changed.single().text, SubtitleParser.parseVTT(updated).single().text)
    }

    @Test
    fun vttNegativeTimeEditsReloadWithoutClamping() {
        val source = "WEBVTT\n\n00:01.000 --> 00:02.000\nOld\n"
        val old = SubtitleParser.parseVTT(source)
        val changed = listOf(old.single().copy(startTime = -1_250L))
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.VTT, old, changed)
        assertEquals(-1_250L, SubtitleParser.parseVTT(updated).single().startTime)
    }

    @Test
    fun vttDeletingEarlierMapSegmentKeepsFollowingMapInPlace() {
        val source = "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000\n\n00:01.000 --> 00:02.000\nFirst\n\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:1800000\n\n00:01.000 --> 00:02.000\nLast\n"
        val old = SubtitleParser.parseVTT(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source, SubtitleParser.SubtitleFormat.VTT, old, listOf(old[1])
        )
        val reparsed = SubtitleParser.parseVTT(updated).single()
        assertEquals(old[1].startTime, reparsed.startTime)
        assertEquals(old[1].endTime, reparsed.endTime)
        assertEquals("Last", reparsed.text)
    }

    @Test
    fun vttEditingMergedCuesPreservesMetadataWithoutRestoringDuplicates() {
        val source = "WEBVTT\n\nSTYLE\n::cue { color: red; }\n\n00:01.000 --> 00:02.000\nFirst\n\n00:01.000 --> 00:02.000\nSecond\n\nNOTE footer\nkeep me\n"
        val old = SubtitleParser.parseVTT(source)
        val changed = listOf(old.single().copy(text = "Changed"))
        val updated = SubtitleSourceSynchronizer.apply(source, SubtitleParser.SubtitleFormat.VTT, old, changed)
        assertTrue(updated.contains("STYLE\n::cue { color: red; }"))
        assertTrue(updated.contains("NOTE footer\nkeep me"))
        assertEquals("Changed", SubtitleParser.parseVTT(updated).single().text)
        assertTrue(!updated.contains("Second"))
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
    fun lrcTimestampInsideCueTextIsNotParsedAsAnotherCue() {
        val source = "[00:01.00]A [00:02.00]\n[00:03.00]B\n"
        val old = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0], old[1].copy(text = "Changed"))
        )

        assertEquals("[00:01.00]A [00:02.00]\n[00:03.00]Changed\n", updated)
    }

    @Test
    fun lrcMillisecondSourceKeepsThreeDigitPrecisionWhenTimingChanges() {
        val source = "[00:01.234]Old\n[00:03.500]Next\n"
        val old = SubtitleParser.parseDocument(source, format = SubtitleParser.SubtitleFormat.LRC).entries
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0].copy(startTime = 2_234L, text = "New"), old[1])
        )

        assertTrue(updated.contains("[00:02.234]New"))
        assertTrue(updated.contains("[00:03.500]Next"))
    }

    @Test
    fun lrcNoEndTimeSourceDoesNotGainSyntheticTerminatorsAfterTimingChanges() {
        val source = "[re: Subtitle Edit - LRC No End Time]\n[00:01.00]Old\n[00:03.50]Next\n"
        val old = SubtitleParser.parseDocument(source, format = SubtitleParser.SubtitleFormat.LRC).entries
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0].copy(startTime = 2_000L, text = "New"), old[1])
        )

        assertEquals(
            "[re: Subtitle Edit - LRC No End Time]\n[00:02.00]New\n[00:03.50]Next\n",
            updated
        )
    }

    @Test
    fun lrcSourceSynchronizerIgnoresInlineTimeTagsInPlainText() {
        val source = "[00:01.00]Old\nA note [00:02.00] remains text\n[00:03.00]Next\n"
        val old = SubtitleParser.parseDocument(source, format = SubtitleParser.SubtitleFormat.LRC).entries
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0].copy(text = "Changed"), old[1])
        )

        assertEquals(
            "[00:01.00]Changed\nA note [00:02.00] remains text\n[00:03.00]Next\n",
            updated
        )
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
    fun lrcFormattedTextUsesTheWriterCleanupAndKeepsRawTimingAndMetadata() {
        val source = "[ti:<b>Demo</b>]\r\n[offset:500]\r\n[00:01.234]Old\r\n[00:02.567]\r\n"
        val old = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old.single().copy(text = "<i><b><font color=red>New\r\nsecond</font></b></i>"))
        )

        assertEquals("[ti:<b>Demo</b>]\r\n[offset:500]\r\n[00:01.234]New second\r\n[00:02.567]\r\n", updated)
        val entry = SubtitleParser.parseLRC(updated).single()
        assertEquals("New second", entry.text)
        assertEquals(old.single().startTime, entry.startTime)
        assertEquals(old.single().endTime, entry.endTime)
    }

    @Test
    fun lrcMultiTimeTagLineStaysGroupedWhenOnlyTheFormattingDiffers() {
        val source = "[00:01.234][00:03.500]Old\n[00:04.000]\n"
        val old = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0].copy(text = "<i>Hello</i>"), old[1].copy(text = "<b>Hello</b>"))
        )

        assertEquals("[00:01.234][00:03.500]Hello\n[00:04.000]\n", updated)
        assertEquals(listOf("Hello", "Hello"), SubtitleParser.parseLRC(updated).map { it.text })
    }

    @Test
    fun lrcMultiTimeTagLineSplitsDifferentLyricsWithoutLeakingTagsOrNewlines() {
        val source = "[00:01.234][00:03.500]Old\n[00:04.000]\n"
        val old = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0].copy(text = "<i>Hello\nthere</i>"), old[1].copy(text = "<font color=red>World</font>"))
        )

        assertEquals("[00:01.234]Hello there\n[00:03.500]World\n[00:04.000]\n", updated)
        assertEquals(listOf("Hello there", "World"), SubtitleParser.parseLRC(updated).map { it.text })
    }

    @Test
    fun lrcInsertedAndPartiallyDeletedMultiTagCuesUseTheSameTextCleanup() {
        val source = "[00:01.000][00:02.000]Shared\n[00:03.000]\n"
        val old = SubtitleParser.parseLRC(source)
        val inserted = SubtitleEntry(startTime = 4_000L, endTime = 5_000L, text = "<b>Added\nline</b>")
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[1].copy(text = "<i>Remaining</i>"), inserted)
        )

        assertEquals("[00:02.000]Remaining\n[00:03.000]\n[00:04.000]Added line\n[00:05.000]\n", updated)
        assertEquals(listOf("Remaining", "Added line"), SubtitleParser.parseLRC(updated).map { it.text })
    }

    @Test
    fun lrcNoEndTimeInsertedCueCleansFormattingWithoutAddingTerminators() {
        val source = "[re: Subtitle Edit - LRC No End Time]\n[offset:500]\n[00:01.234]First\n"
        val old = SubtitleParser.parseLRC(source)
        val inserted = SubtitleEntry(startTime = 3_000L, endTime = 4_000L, text = "<font color=red><i>Added\nline</i></font>")
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old.single(), inserted)
        )

        assertEquals("[re: Subtitle Edit - LRC No End Time]\n[offset:500]\n[00:01.234]First\n[00:02.50]Added line\n", updated)
        assertEquals(listOf("First", "Added line"), SubtitleParser.parseLRC(updated).map { it.text })
    }

    @Test
    fun lrcAppendingToMetadataOnlySourceCleansLyricsAndKeepsTheOffset() {
        val source = "[ti:<i>Title</i>]\r\n[offset:500]\r\n"
        val inserted = SubtitleEntry(startTime = 1_500L, endTime = 2_500L, text = "<b>Added\r\nline</b>")
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            emptyList(),
            listOf(inserted)
        )

        assertEquals("[ti:<i>Title</i>]\r\n[offset:500]\r\n[00:01.00]Added line\r\n[00:02.00]\r\n", updated)
        val restored = SubtitleParser.parseLRC(updated).single()
        assertEquals("Added line", restored.text)
        assertEquals(inserted.startTime, restored.startTime)
        assertEquals(inserted.endTime, restored.endTime)
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
    fun lrcDeletionUsesStableIdentityWhenRemovingMiddleCue() {
        val source = "[00:01.00]A\n[00:02.00]B\n[00:03.00]C\n"
        val old = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0], old[2].copy())
        )

        assertTrue(updated.contains("[00:01.00]A"))
        assertTrue(updated.contains("[00:03.00]C"))
        assertTrue(!updated.contains("[00:02.00]B"))
        assertEquals(listOf("A", "C"), SubtitleParser.parseLRC(updated).map { it.text })
    }

    @Test
    fun lrcInsertionKeepsExistingCueTextAndAddsOnlyNewCue() {
        val source = "[00:01.00]A\n[00:03.00]C\n"
        val old = SubtitleParser.parseLRC(source)
        val inserted = SubtitleEntry(
            startTime = 2_000L,
            endTime = 3_000L,
            text = "B"
        )
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            old,
            listOf(old[0], inserted, old[1].copy())
        )

        assertEquals(listOf("A", "B", "C"), SubtitleParser.parseLRC(updated).map { it.text })
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

    @Test
    fun restoringDeletedSrtCueRenumbersAllCueBlocks() {
        val source = "1\n00:00:01,000 --> 00:00:02,000\nFirst\n\n2\n00:00:03,000 --> 00:00:04,000\nSecond\n\n"
        val original = SubtitleParser.parseSRT(source)
        val deleted = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.SRT,
            original,
            listOf(original[1].copy(index = 1))
        )

        val restored = SubtitleSourceSynchronizer.apply(
            deleted,
            SubtitleParser.SubtitleFormat.SRT,
            SubtitleParser.parseSRT(deleted),
            original
        )

        assertTrue(restored.startsWith("1\n00:00:01,000 --> 00:00:02,000\nFirst"))
        assertTrue(restored.contains("2\n00:00:03,000 --> 00:00:04,000\nSecond"))
    }

    @Test
    fun restoringDeletedLrcCueRestoresItsTerminator() {
        val source = "[00:01.00]First\n[00:02.00]Removed\n[00:02.50]\n[00:03.00]Second\n"
        val original = SubtitleParser.parseLRC(source)
        val deleted = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            original,
            listOf(original[0], original[2].copy(index = 2))
        )

        val restored = SubtitleSourceSynchronizer.apply(
            deleted,
            SubtitleParser.SubtitleFormat.LRC,
            SubtitleParser.parseLRC(deleted),
            original
        )

        assertEquals(source, restored)
    }

    @Test
    fun lrcImplicitEndKeepsMissingTerminatorAtCentisecondPrecision() {
        val source = "[00:01.00]First\n[00:05.72]Second\n"
        val oldEntries = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            oldEntries,
            listOf(oldEntries[0].copy(text = "Changed"), oldEntries[1])
        )

        assertEquals("[00:01.00]Changed\n[00:05.72]Second\n", updated)
        assertEquals(5_696L, SubtitleParser.parseLRC(updated)[0].endTime)
    }

    @Test
    fun lrcTerminatorFormattingRoundsLikeTheParser() {
        val source = "[00:01.00]First\n[00:05.72]Second\n"
        val oldEntries = SubtitleParser.parseLRC(source)
        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.LRC,
            oldEntries,
            listOf(oldEntries[0].copy(endTime = 5_696L + 30L), oldEntries[1])
        )

        assertTrue(updated.contains("[00:05.73]"))
    }

    @Test
    fun structuralEditsFollowStableRowsAndPreserveSurvivingRawCueLayout() {
        val source = "NOTE header\n\n7\n00:00:01.000   -->   00:00:02.000\nFirst\n\n9\n00:00:03,000-->00:00:04,000\nSecond\n\n"
        val parsed = SubtitleParser.parseSRT(source)
        val remaining = parsed[1].copy(index = 1, stableId = parsed[1].stableId + 100_000L)

        val updated = SubtitleSourceSynchronizer.apply(
            source,
            SubtitleParser.SubtitleFormat.SRT,
            parsed,
            listOf(remaining)
        )

        assertTrue(updated.startsWith("NOTE header"))
        assertTrue(updated.contains("1\n00:00:03,000-->00:00:04,000\nSecond"))
        assertTrue(!updated.contains("7\n00:00:01.000"))
    }
}
