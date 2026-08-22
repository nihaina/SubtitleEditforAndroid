package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {

    private val srtSample = """
        1
        00:00:01,000 --> 00:00:02,500
        Hello
        World

        2
        00:00:03,000 --> 00:00:04,000
        Second
    """.trimIndent()

    // ==================== detectFormat ====================

    @Test
    fun detectFormat_srt() {
        assertEquals(SubtitleParser.SubtitleFormat.SRT, SubtitleParser.detectFormat(srtSample))
        // 没有序号行、只有时间轴的内容也应识别为 SRT
        assertEquals(
            SubtitleParser.SubtitleFormat.SRT,
            SubtitleParser.detectFormat("00:00:01,000 --> 00:00:02,000\nHello")
        )
    }

    @Test
    fun detectFormat_lrc() {
        assertEquals(SubtitleParser.SubtitleFormat.LRC, SubtitleParser.detectFormat("[00:01.00]hi"))
        assertEquals(
            SubtitleParser.SubtitleFormat.LRC,
            SubtitleParser.detectFormat("[ti:title]\n[00:01.00]hi")
        )
    }

    @Test
    fun detectFormat_txt() {
        assertEquals(SubtitleParser.SubtitleFormat.TXT, SubtitleParser.detectFormat("hello\nworld"))
        // 数字开头但没有时间轴标记的纯文本
        assertEquals(SubtitleParser.SubtitleFormat.TXT, SubtitleParser.detectFormat("5 dollars"))
    }

    @Test
    fun detectFormat_emptyIsUnknown() {
        assertEquals(SubtitleParser.SubtitleFormat.UNKNOWN, SubtitleParser.detectFormat(""))
        assertEquals(SubtitleParser.SubtitleFormat.UNKNOWN, SubtitleParser.detectFormat("   \n  "))
    }

    @Test
    fun detectFormat_vttUsesContentEvenWithWrongExtension() {
        val content = "WEBVTT\n\n00:00.000 --> 00:01.000\nHello"
        assertEquals(
            SubtitleParser.SubtitleFormat.VTT,
            SubtitleParser.detectFormat(content, "wrong.srt")
        )
        assertEquals(SubtitleParser.SubtitleFormat.VTT, SubtitleParser.detectFormat("WEBVTT\n", "empty.vtt"))
    }

    // ==================== parseSRT ====================

    @Test
    fun parseSRT_standardEntries() {
        val entries = SubtitleParser.parseSRT(srtSample)
        assertEquals(2, entries.size)

        assertEquals(1, entries[0].index)
        assertEquals(1000L, entries[0].startTime)
        assertEquals(2500L, entries[0].endTime)
        assertEquals("Hello\nWorld", entries[0].text)

        assertEquals(2, entries[1].index)
        assertEquals(3000L, entries[1].startTime)
        assertEquals(4000L, entries[1].endTime)
        assertEquals("Second", entries[1].text)
    }

    @Test
    fun parseSRT_renumbersEntries() {
        val content = "5\n00:00:01,000 --> 00:00:02,000\nA\n\n9\n00:00:03,000 --> 00:00:04,000\nB"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(listOf(1, 2), entries.map { it.index })
    }

    @Test
    fun parseSRT_dropsEntriesWithoutText() {
        val content = "1\n00:00:01,000 --> 00:00:02,000\n\n2\n00:00:03,000 --> 00:00:04,000\nKept"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(1, entries.size)
        assertEquals("Kept", entries[0].text)
        assertEquals(1, entries[0].index)
    }

    @Test
    fun parseSRT_acceptsDotMillis() {
        val content = "1\n00:00:01.000 --> 00:00:02.000\nText"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(1000L, entries[0].startTime)
        assertEquals(2000L, entries[0].endTime)
    }

    @Test
    fun parseSRT_ignoresLeadingGarbage() {
        val content = "WEBVTT-like junk\n1\n00:00:01,000 --> 00:00:02,000\nText"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(1, entries.size)
        assertEquals("Text", entries[0].text)
    }

    @Test
    fun parseSRT_lastEntryWithoutTrailingBlankLine() {
        val content = "1\n00:00:01,000 --> 00:00:02,000\nOnly"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(1, entries.size)
        assertEquals("Only", entries[0].text)
    }

    @Test
    fun parseSRT_toleratesSpacesAroundArrow() {
        val content = "1\n00:00:01,000   -->   00:00:02,000\nText"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(1000L, entries[0].startTime)
        assertEquals(2000L, entries[0].endTime)
    }

    @Test
    fun parseSRT_acceptsMissingNumbersAndCommonBrokenArrow() {
        val content = "00:00:01,000 —> 00:00:02,000\nA\n00:00:03,000 -> 00:00:04,000\nB"
        val entries = SubtitleParser.parseSRT(content)
        assertEquals(2, entries.size)
        assertEquals(listOf("A", "B"), entries.map { it.text })
    }

    // ==================== parseLRC ====================

    @Test
    fun parseLRC_endTimeClampedToNextStart() {
        val entries = SubtitleParser.parseLRC("[00:01.00]Hello\n[00:03.50]World")
        assertEquals(2, entries.size)
        assertEquals(1000L, entries[0].startTime)
        assertEquals(3500L, entries[0].endTime)
        assertFalse(entries[0].endTimeModified)
        // 最后一行使用默认 6 秒时长
        assertEquals(3500L, entries[1].startTime)
        assertEquals(9500L, entries[1].endTime)
    }

    @Test
    fun parseLRC_blankTagActsAsTerminator() {
        val entries = SubtitleParser.parseLRC("[00:01.00]Hello\n[00:02.00]\n[00:10.00]World")
        assertEquals(2, entries.size)
        assertEquals(2000L, entries[0].endTime)
        assertTrue(entries[0].endTimeModified)
        assertEquals(10000L, entries[1].startTime)
    }

    @Test
    fun parseLRC_defaultSixSecondsWhenGapIsLarge() {
        val entries = SubtitleParser.parseLRC("[00:01.00]A\n[00:20.00]B")
        assertEquals(7000L, entries[0].endTime)
        assertFalse(entries[0].endTimeModified)
    }

    @Test
    fun parseLRC_skipsMetadataTags() {
        val entries = SubtitleParser.parseLRC("[ti:Title]\n[ar:Artist]\n[00:01.00]A")
        assertEquals(1, entries.size)
        assertEquals("A", entries[0].text)
    }

    @Test
    fun parseLRC_twoAndThreeDigitMillis() {
        assertEquals(1230L, SubtitleParser.parseLRC("[00:01.23]X")[0].startTime)
        assertEquals(1234L, SubtitleParser.parseLRC("[00:01.234]X")[0].startTime)
    }

    @Test
    fun parseLRC_trimsText() {
        assertEquals("spaced", SubtitleParser.parseLRC("[00:01.00]  spaced  ")[0].text)
    }

    @Test
    fun parseLRC_assignsSequentialIndices() {
        val entries = SubtitleParser.parseLRC("[00:01.00]A\n[00:02.00]B\n[00:03.00]C")
        assertEquals(listOf(1, 2, 3), entries.map { it.index })
    }

    @Test
    fun parseLRC_supportsMultipleTagsAndOffset() {
        val document = SubtitleParser.parseDocument(
            "[ti:Title]\n[offset:500]\n[00:01.00][00:02.00]Same\n[00:03.00]Next",
            "song.lrc"
        )
        assertEquals(3, document.entries.size)
        assertEquals(listOf(1500L, 2500L, 3500L), document.entries.map { it.startTime })
        assertEquals("[ti:Title]", document.header)
        assertEquals(listOf("Same", "Same", "Next"), document.entries.map { it.text })
    }

    // ==================== parseVTT / toVTT ====================

    @Test
    fun parseVTT_preservesHeaderIdentifierAndSettings() {
        val content = """
            WEBVTT - Example

            STYLE
            ::cue(.red) { color: red; }

            intro
            00:01.000 --> 00:03.250 line:90% align:start
            <c.red>Hello</c>
            World
        """.trimIndent()

        val document = SubtitleParser.parseDocument(content, "sample.vtt")
        assertEquals(SubtitleParser.SubtitleFormat.VTT, document.format)
        assertTrue(document.header.contains("STYLE"))
        assertEquals(1, document.entries.size)
        assertEquals(1000L, document.entries[0].startTime)
        assertEquals(3250L, document.entries[0].endTime)
        assertEquals("intro", document.entries[0].cueIdentifier)
        assertEquals("line:90% align:start", document.entries[0].cueSettings)
        assertEquals("<c.red>Hello</c>\nWorld", document.entries[0].text)
    }

    @Test
    fun vttRoundTrip_preservesCueData() {
        val original = """
            WEBVTT

            cue-1
            00:00:01.000 --> 00:00:02.500 position:50%
            Hello
        """.trimIndent()
        val document = SubtitleParser.parseDocument(original, "sample.vtt")
        val reparsed = SubtitleParser.parseDocument(SubtitleParser.serialize(document), "sample.vtt")
        assertEquals(1, reparsed.entries.size)
        assertEquals(1000L, reparsed.entries[0].startTime)
        assertEquals(2500L, reparsed.entries[0].endTime)
        assertEquals("cue-1", reparsed.entries[0].cueIdentifier)
        assertEquals("position:50%", reparsed.entries[0].cueSettings)
        assertEquals("Hello", reparsed.entries[0].text)
    }

    @Test
    fun parseVTT_preservesBlocksAfterCuesInFooter() {
        val content = """
            WEBVTT

            00:00.000 --> 00:01.000
            First

            NOTE trailing note
            remains outside cues

            REGION
            id:bottom
            width:80%
        """.trimIndent()

        val document = SubtitleParser.parseDocument(content, "sample.vtt")
        assertEquals(1, document.entries.size)
        assertTrue(document.footer.contains("NOTE trailing note"))
        assertTrue(document.footer.contains("REGION"))

        val reparsed = SubtitleParser.parseDocument(SubtitleParser.serialize(document), "sample.vtt")
        assertEquals("First", reparsed.entries.single().text)
        assertTrue(reparsed.footer.contains("remains outside cues"))
        assertTrue(reparsed.footer.contains("id:bottom"))
    }

    @Test
    fun parseVTT_appliesAndConsumesXTimestampMap() {
        val content = """
            WEBVTT
            X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000

            00:01.000 --> 00:02.500
            Shifted
        """.trimIndent()

        val document = SubtitleParser.parseDocument(content, "segment.vtt")
        assertEquals(11_000L, document.entries.single().startTime)
        assertEquals(12_500L, document.entries.single().endTime)
        assertFalse(document.header.contains("X-TIMESTAMP-MAP", ignoreCase = true))

        val serialized = SubtitleParser.serialize(document)
        assertFalse(serialized.contains("X-TIMESTAMP-MAP", ignoreCase = true))
        val reparsed = SubtitleParser.parseDocument(serialized, "segment.vtt")
        assertEquals(11_000L, reparsed.entries.single().startTime)
    }

    @Test
    fun parseVTT_supportsMultipleXTimestampMaps() {
        val content = """
            WEBVTT

            X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000

            00:01.000 --> 00:02.000
            First

            X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:1800000

            00:01.000 --> 00:02.000
            Second
        """.trimIndent()

        val document = SubtitleParser.parseDocument(content, "segments.vtt")
        assertEquals(listOf(11_000L, 21_000L), document.entries.map { it.startTime })
    }

    @Test
    fun parseVTT_doesNotTreatPostCueNoteAsCueText() {
        val content = """
            WEBVTT

            cue
            00:00.000 --> 00:01.000 align:center
            Text

            NOTE ignored by cue parser
            00:05.000 --> 00:06.000 is text inside the note
        """.trimIndent()

        val document = SubtitleParser.parseDocument(content, "note.vtt")
        assertEquals(1, document.entries.size)
        assertEquals("Text", document.entries.single().text)
        assertTrue(document.footer.startsWith("NOTE ignored"))
    }

    // ==================== toSRT / toLRC / TXT ====================

    @Test
    fun toSRT_singleEntryLayout() {
        val content = SubtitleParser.toSRT(
            listOf(SubtitleEntry(index = 1, startTime = 1000, endTime = 2000, text = "Hello"))
        )
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHello\n\n", content)
    }

    @Test
    fun toSRT_renumbersFromOne() {
        val content = SubtitleParser.toSRT(
            listOf(SubtitleEntry(index = 99, startTime = 0, endTime = 1000, text = "A"))
        )
        assertTrue(content.startsWith("1\n"))
    }

    @Test
    fun toLRC_contiguousEntriesHaveNoInnerTerminator() {
        val content = SubtitleParser.toLRC(
            listOf(
                SubtitleEntry(startTime = 1000, endTime = 2000, text = "A"),
                SubtitleEntry(startTime = 2000, endTime = 3000, text = "B")
            )
        )
        assertEquals("[00:01.00]A\n[00:02.00]B\n[00:03.00]\n", content)
    }

    @Test
    fun toLRC_gapInsertsTerminator() {
        val content = SubtitleParser.toLRC(
            listOf(
                SubtitleEntry(startTime = 1000, endTime = 2000, text = "A"),
                SubtitleEntry(startTime = 5000, endTime = 6000, text = "B")
            )
        )
        assertEquals("[00:01.00]A\n[00:02.00]\n[00:05.00]B\n[00:06.00]\n", content)
    }

    @Test
    fun parseTXT_skipsBlankLinesAndTrims() {
        val entries = SubtitleParser.parseTXT("Hello\n\n  World  \n")
        assertEquals(2, entries.size)
        assertEquals("Hello", entries[0].text)
        assertEquals(0L, entries[0].startTime)
        assertEquals(3000L, entries[0].endTime)
        assertEquals("World", entries[1].text)
        assertEquals(3000L, entries[1].startTime)
        assertEquals(6000L, entries[1].endTime)
    }

    @Test
    fun toTXT_outputsOneLinePerEntry() {
        val content = SubtitleParser.toTXT(
            listOf(
                SubtitleEntry(text = "Hello"),
                SubtitleEntry(text = "World")
            )
        )
        assertEquals("Hello\nWorld\n", content)
    }

    // ==================== 往返一致性 ====================

    @Test
    fun srt_roundTrip_preservesTimesAndText() {
        val original = listOf(
            SubtitleEntry(index = 1, startTime = 1000, endTime = 2500, text = "Hello\nWorld"),
            SubtitleEntry(index = 2, startTime = 3000, endTime = 4000, text = "第二条")
        )
        val reparsed = SubtitleParser.parseSRT(SubtitleParser.toSRT(original))
        assertEquals(original.size, reparsed.size)
        original.zip(reparsed).forEach { (o, r) ->
            assertEquals(o.startTime, r.startTime)
            assertEquals(o.endTime, r.endTime)
            assertEquals(o.text, r.text)
        }
    }

    @Test
    fun lrc_roundTrip_preservesCentisecondAlignedTimes() {
        // LRC 精度是厘秒，用 10ms 对齐的时间验证
        val original = listOf(
            SubtitleEntry(startTime = 1230, endTime = 2560, text = "A"),
            SubtitleEntry(startTime = 5000, endTime = 8000, text = "B")
        )
        val reparsed = SubtitleParser.parseLRC(SubtitleParser.toLRC(original))
        assertEquals(2, reparsed.size)
        assertEquals(1230L, reparsed[0].startTime)
        assertEquals(2560L, reparsed[0].endTime)
        assertEquals(5000L, reparsed[1].startTime)
        assertEquals(8000L, reparsed[1].endTime)
    }

    @Test
    fun lrc_roundTrip_contiguousEntries() {
        val original = listOf(
            SubtitleEntry(startTime = 1000, endTime = 2000, text = "A"),
            SubtitleEntry(startTime = 2000, endTime = 3000, text = "B")
        )
        val reparsed = SubtitleParser.parseLRC(SubtitleParser.toLRC(original))
        assertEquals(2000L, reparsed[0].endTime)
        assertEquals(3000L, reparsed[1].endTime)
    }

    // ==================== parse 分派 / convertFormat ====================

    @Test
    fun parse_dispatchesByDetectedFormat() {
        assertEquals(2, SubtitleParser.parse(srtSample).size)
        assertEquals(1, SubtitleParser.parse("[00:01.00]hi").size)
        assertEquals(2, SubtitleParser.parse("line one\nline two").size)
        assertTrue(SubtitleParser.parse("").isEmpty())
    }

    @Test
    fun convertFormat_srtToLrc() {
        val lrc = SubtitleParser.convertFormat(
            srtSample,
            SubtitleParser.SubtitleFormat.SRT,
            SubtitleParser.SubtitleFormat.LRC
        )
        assertTrue(lrc.contains("[00:01.00]Hello"))
    }

    @Test
    fun convertFormat_lrcToSrt() {
        val srt = SubtitleParser.convertFormat(
            "[00:01.00]Hello\n[00:02.00]\n",
            SubtitleParser.SubtitleFormat.LRC,
            SubtitleParser.SubtitleFormat.SRT
        )
        assertTrue(srt.contains("00:00:01,000 --> 00:00:02,000"))
    }

    @Test
    fun convertFormat_vttToSrt() {
        val srt = SubtitleParser.convertFormat(
            "WEBVTT\n\n00:01.000 --> 00:02.000\nHello",
            SubtitleParser.SubtitleFormat.VTT,
            SubtitleParser.SubtitleFormat.SRT
        )
        assertTrue(srt.contains("00:00:01,000 --> 00:00:02,000"))
        assertTrue(srt.contains("Hello"))
    }

    @Test
    fun convertFormat_unknownTargetReturnsOriginal() {
        val result = SubtitleParser.convertFormat(
            srtSample,
            SubtitleParser.SubtitleFormat.SRT,
            SubtitleParser.SubtitleFormat.UNKNOWN
        )
        assertEquals(srtSample, result)
    }
}
