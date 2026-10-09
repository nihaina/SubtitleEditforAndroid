package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SubRip.cs parity cases, with edge values verified against an upstream execution harness. */
class SrtSubtitleEditAlignmentTest {

    private fun lines(text: String): List<String> = text.trimIndent().split("\n")

    @Test
    fun preservesSourceNumbersUntilAHeaderlessCueRequiresRenumbering() {
        val numbered = SrtSubtitleFormatHandler.load(
            lines(
                """
                5
                00:00:01,000 --> 00:00:02,000
                A

                9
                00:00:03,000 --> 00:00:04,000
                B
                """
            ),
            "numbered.srt"
        )
        assertEquals(listOf(5, 9), numbered.entries.map { it.index })

        val headerless = SrtSubtitleFormatHandler.load(
            lines(
                """
                00:00:01,000 --> 00:00:02,000
                A

                00:00:03,000 --> 00:00:04,000
                B
                """
            ),
            "headerless.srt"
        )
        assertEquals(listOf(1, 2), headerless.entries.map { it.index })
    }

    @Test
    fun isMineUsesStructuralErrorThresholdButWarningsDoNotRejectSrt() {
        val valid = lines(
            """
            1
            00:00:01,000 --> 00:00:02,000
            A
            """
        )
        val invalidNumber = lines(
            """
            garbage
            1
            00:00:01,000 --> 00:00:02,000
            A
            """
        )
        val malformed = SrtSubtitleFormatHandler.parse(invalidNumber, "bad.srt")
        assertEquals(1, malformed.errorCount)
        assertEquals(1, malformed.document.entries.size)
        assertFalse(SrtSubtitleFormatHandler.isMine(invalidNumber, "bad.srt"))
        assertTrue(SrtSubtitleFormatHandler.isMine(valid, "valid.srt"))
        val enoughCues = invalidNumber + listOf("", "2", "00:00:03,000 --> 00:00:04,000", "B")
        assertTrue(SrtSubtitleFormatHandler.isMine(enoughCues, "recoverable.srt"))
        assertFalse(SrtSubtitleFormatHandler.isMine(listOf("WEBVTT", "") + valid, "wrong.srt"))
        val invalidTiming = listOf("1", "not a time code", "2") + valid.drop(1)
        assertEquals(1, SrtSubtitleFormatHandler.parse(invalidTiming, "bad-time.srt").errorCount)
        assertFalse(SrtSubtitleFormatHandler.isMine(invalidTiming, "bad-time.srt"))

        // A range warning is reported by Subtitle Edit but does not count as a
        // structural parse error, so a one-cue file remains recognized as SRT.
        val outOfRange = lines(
            """
            1
            100:00:00,000 --> 100:00:01,000
            A
            """
        )
        val warningOnly = SrtSubtitleFormatHandler.parse(outOfRange, "range.srt")
        assertEquals(0, warningOnly.errorCount)
        assertTrue(SrtSubtitleFormatHandler.isMine(outOfRange, "range.srt"))
    }

    @Test
    fun readsRawMillisecondFieldsWhenFrameModeIsDisabled() {
        val document = SrtSubtitleFormatHandler.loadWithFrameRate(
            lines(
                """
                1
                00:00:01,5 --> 00:00:02,9
                A

                2
                00:00:03,1234 --> 00:00:04,1234
                B
                """
            ),
            "raw.srt"
        )
        assertEquals(1005L, document.entries[0].startTime)
        assertEquals(2009L, document.entries[0].endTime)
        // Subtitle Edit keeps four parsed digits as milliseconds; TimeCode then
        // carries the extra 234 ms into the seconds component.
        assertEquals(4234L, document.entries[1].startTime)
        assertEquals(5234L, document.entries[1].endTime)
    }

    @Test
    fun convertsWholeFileTwoDigitFieldsAsFramesAtConfiguredRate() {
        val source = lines(
            """
            1
            00:00:01,12 --> 00:00:02,20
            A
            """
        )
        val defaultRate = SrtSubtitleFormatHandler.parse(source, "frames.srt")
        assertTrue(defaultRate.usesFrameTiming)
        assertEquals(1500L, defaultRate.document.entries.single().startTime)
        assertEquals(2834L, defaultRate.document.entries.single().endTime)

        val twentyFive = SrtSubtitleFormatHandler.parse(source, "frames.srt", frameRate = 25.0)
        assertTrue(twentyFive.usesFrameTiming)
        assertEquals(1480L, twentyFive.document.entries.single().startTime)
        assertEquals(2800L, twentyFive.document.entries.single().endTime)

        // A single three-digit field disables frame interpretation for the whole file.
        val mixed = SrtSubtitleFormatHandler.parse(
            lines(
                """
                1
                00:00:01,12 --> 00:00:02,20
                A

                2
                00:00:03,500 --> 00:00:04,500
                B
                """
            ),
            "mixed.srt"
        )
        assertFalse(mixed.usesFrameTiming)
        assertEquals(1012L, mixed.document.entries[0].startTime)

        val beyondFrameLimit = SrtSubtitleFormatHandler.parse(
            listOf("1", "00:00:01,31 --> 00:00:02,30", "A"),
            "not-frames.srt"
        )
        assertFalse(beyondFrameLimit.usesFrameTiming)
        assertEquals(1031L, beyondFrameLimit.document.entries.single().startTime)
        val capped = SrtSubtitleFormatHandler.loadWithFrameRate(
            listOf("1", "00:00:01,25 --> 00:00:02,30", "A"),
            frameRate = 25.0
        )
        assertEquals(1999L, capped.entries.single().startTime)
        assertEquals(2999L, capped.entries.single().endTime)
    }

    @Test
    fun acceptsSubtitleEditArrowAndTimestampVariants() {
        val variants = listOf("-->>", "- >", "-- >", "->>", "\u2014>", "\u2014\u2014>", ">")
        val source = buildString {
            variants.forEachIndexed { index, arrow ->
                append(index + 1).append('\n')
                append("00:00:").append((index + 1).toString().padStart(2, '0')).append(",000 ")
                    .append(arrow).append(" 00:00:").append((index + 2).toString().padStart(2, '0')).append(",000\n")
                append("cue").append(index).append("\n\n")
            }
        }
        val document = SrtSubtitleFormatHandler.load(source.trimEnd().split("\n"), "arrows.srt")
        assertEquals(variants.size, document.entries.size)

        val punctuation = SrtSubtitleFormatHandler.load(
            listOf("1", "00.00.02\u060C000 -> 00.00.04\u060C000 X1:100 X2:100 Y1:100 Y2:100", "A"),
            "punctuation.srt"
        )
        assertEquals(2000L, punctuation.entries.single().startTime)
        assertEquals(4000L, punctuation.entries.single().endTime)
    }

    @Test
    fun rejectsMalformedSyntaxAndAcceptsUnboundedIntegerFields() {
        val invalid = listOf(
            "log 00:00:01,000 --> 00:00:02,000",
            "00:00:01 --> 00:00:02",
            "1:01,000 --> 1:02,000",
            "0:0:1,000 --> 0:0:2,000 position",
            "00:00:01,000 - -> 00:00:02,000"
        )
        invalid.forEach { timeline ->
            val source = listOf("1", timeline, "A")
            assertFalse(timeline, SrtSubtitleFormatHandler.isMine(source))
            assertEquals(timeline, 0, SrtSubtitleFormatHandler.load(source).entries.size)
        }
        val fields = SrtSubtitleFormatHandler.load(
            listOf("1", "00:123:01,000 --> 00:124:02,000", "A")
        ).entries.single()
        assertEquals(7381000L, fields.startTime)
        assertEquals(7442000L, fields.endTime)
        val wideMillis = SrtSubtitleFormatHandler.load(
            listOf("1", "00:00:01,12345 --> 00:00:02,12345", "A")
        ).entries.single()
        assertEquals(13345L, wideMillis.startTime)
        assertEquals(14345L, wideMillis.endTime)
    }

    @Test
    fun preservesUpstreamBlankRecoveryAndEmptyTextNumbering() {
        val t1 = "00:00:01,000 --> 00:00:02,000"
        val t2 = "00:00:03,000 --> 00:00:04,000"
        val twoBlanks = SrtSubtitleFormatHandler.parse(listOf("5", t1, "A", "9", "", "", t2, "B"))
        assertEquals(listOf(1, 2), twoBlanks.document.entries.map { it.index })
        assertEquals(listOf("A\n9", "B"), twoBlanks.document.entries.map { it.text })
        assertEquals(0, twoBlanks.errorCount)

        val empty = SrtSubtitleFormatHandler.parse(listOf("5", t1, t2, "B"))
        assertEquals(listOf(5, 0), empty.document.entries.map { it.index })
        assertEquals(listOf("", "B"), empty.document.entries.map { it.text })
        assertEquals(0, empty.errorCount)

        val recovery = SrtSubtitleFormatHandler.parse(listOf("5", t1, "A", "", "999", "", "extra", "", "6", t2, "B"))
        assertEquals(listOf(5, 6), recovery.document.entries.map { it.index })
        assertEquals(listOf("A", "B"), recovery.document.entries.map { it.text })
        assertEquals(1, recovery.errorCount)
    }

    @Test
    fun acceptsMissingHoursAndKeepsTextAroundMissingBlankLines() {
        val document = SrtSubtitleFormatHandler.load(
            lines(
                """
                5
                04:48,460 --> 04:52,364
                A
                9
                00:00:03,000 --> 00:00:04,000
                B
                """
            ),
            "missing-hours.srt"
        )
        assertEquals(2, document.entries.size)
        assertEquals(288460L, document.entries[0].startTime)
        assertEquals(292364L, document.entries[0].endTime)
        assertEquals(listOf(1, 2), document.entries.map { it.index })
        assertEquals(listOf("A", "B"), document.entries.map { it.text })

        val numericText = SrtSubtitleFormatHandler.load(
            lines(
                """
                1
                00:00:01,000 --> 00:00:02,000
                123
                A

                2
                00:00:03,000 --> 00:00:04,000
                456
                """
            ),
            "numeric-text.srt"
        )
        assertEquals("123\nA", numericText.entries[0].text)
        assertEquals("456", numericText.entries[1].text)

        val blankInsideText = SrtSubtitleFormatHandler.load(
            listOf("1", "00:00:01,000 --> 00:00:02,000", "A", "", "B", ""),
            "text-blank.srt"
        )
        assertEquals("A\n\nB", blankInsideText.entries.single().text)
    }

    @Test
    fun keepsEmptyCueAndConvertsWsrtItalicTags() {
        val empty = SrtSubtitleFormatHandler.load(
            lines(
                """
                1
                00:00:01,000 --> 00:00:02,000
                """
            ),
            "empty.srt"
        )
        assertEquals(1, empty.entries.size)
        assertEquals("", empty.entries.single().text)
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,000\n\n",
            SrtSubtitleFormatHandler.write(empty)
        )

        val wsrt = SrtSubtitleFormatHandler.load(
            lines(
                """
                1
                00:00:01,000 --> 00:00:02,000
                <30>Hello</30>
                """
            ),
            "styled.wsrt"
        )
        assertEquals("<i>Hello</i>", wsrt.entries.single().text)
        val unconverted = SrtSubtitleFormatHandler.load(
            listOf("1", "00:00:01,000 --> 00:00:02,000", "<30>Hello</30>"),
            "styled.srt"
        )
        assertEquals("<30>Hello</30>", unconverted.entries.single().text)
    }

    @Test
    fun preservesExactNegativeTimes() {
        val document = SrtSubtitleFormatHandler.load(
            lines(
                """
                1
                -00:00:01,250 --> 00:00:02,000
                A

                2
                -01:00:01,000 --> -00:00:02,250
                B
                """
            ),
            "negative.srt"
        )
        assertEquals(-1250L, document.entries[0].startTime)
        assertEquals(2000L, document.entries[0].endTime)
        assertEquals(-3_599_000L, document.entries[1].startTime)
        assertEquals(-2250L, document.entries[1].endTime)
    }

    @Test
    fun writesCanonicalSubRipAndUsesEntryNumber() {
        val document = SubtitleDocument(
            SubtitleParser.SubtitleFormat.SRT,
            listOf(SubtitleEntry(index = 7, startTime = 1000L, endTime = 2000L, text = "Hello"))
        )
        assertEquals(
            "7\n00:00:01,000 --> 00:00:02,000\nHello\n\n",
            SrtSubtitleFormatHandler.write(document)
        )
        assertEquals(
            "7\n-00:00:01,250 --> 00:00:02,000\nHello\n\n",
            SrtSubtitleFormatHandler.write(document.copy(entries = listOf(document.entries.single().copy(startTime = -1250L))))
        )
        assertEquals("\n\n", SrtSubtitleFormatHandler.write(SubtitleDocument(SubtitleParser.SubtitleFormat.SRT, emptyList())))
    }
}
