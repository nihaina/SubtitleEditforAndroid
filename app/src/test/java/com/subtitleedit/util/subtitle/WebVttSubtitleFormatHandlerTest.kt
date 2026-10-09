package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WebVTT.cs and WebVttAutoCaptionsCleaner.cs parsing regressions. */
class WebVttSubtitleFormatHandlerTest {
    private fun load(content: String): SubtitleDocument =
        WebVttSubtitleFormatHandler.load(content.trimIndent().split("\n"), "sample.vtt")

    @Test
    fun isMineRequiresCuesRatherThanJustTheSignature() {
        assertFalse(WebVttSubtitleFormatHandler.isMine(listOf("WEBVTT", ""), "empty.vtt"))
        assertFalse(WebVttSubtitleFormatHandler.isMine(listOf("WEBVTT", "", "STYLE", "::cue { color: red; }"), "style.vtt"))
        assertTrue(WebVttSubtitleFormatHandler.isMine(listOf("00:01.000 --> 00:02.000", "Hello"), null))
    }

    @Test
    fun isMineComparesParsedCuesToTimeCodeParseErrors() {
        val invalidTimeCode = listOf("WEBVTT", "", "2147483648:00:00.000 --> 00:00:02.000", "Invalid", "")
        val firstCue = listOf("00:01.000 --> 00:02.000", "First", "")
        val secondCue = listOf("00:03.000 --> 00:04.000", "Second")
        assertFalse(WebVttSubtitleFormatHandler.isMine(invalidTimeCode + firstCue, "one.vtt"))
        assertTrue(WebVttSubtitleFormatHandler.isMine(invalidTimeCode + firstCue + secondCue, "two.vtt"))
    }

    @Test
    fun ignoresAnArrowThatIsNotARecognizableTimeCodeForDetectionErrors() {
        val content = listOf("junk --> text", "metadata", "", "00:01.000 --> 00:02.000", "Hello")
        assertTrue(WebVttSubtitleFormatHandler.isMine(content, null))
        assertEquals("Hello", WebVttSubtitleFormatHandler.load(content).entries.single().text)
    }

    @Test
    fun readsMillisecondComponentsAsIntegersWithoutPaddingOrTruncation() {
        val document = load(
            """
            WEBVTT

            00:00:01.5 --> 00:00:02.9
            Short field

            00:00:03.1234 --> 00:00:04.1234
            Long field
            """
        )
        assertEquals(listOf(1005L, 4234L), document.entries.map { it.startTime })
        assertEquals(listOf(2009L, 5234L), document.entries.map { it.endTime })
    }

    @Test
    fun acceptsSignedAndOutOfRangeTimeComponents() {
        val document = load(
            """
            WEBVTT

            00:00:-1.-5 --> 00:00:00.-1
            Negative

            00:65:70.1234 --> 00:65:71.1234
            Overflow
            """
        )
        assertEquals(-1005L, document.entries[0].startTime)
        assertEquals(-1L, document.entries[0].endTime)
        assertEquals(3_971_234L, document.entries[1].startTime)
        assertEquals(3_972_234L, document.entries[1].endTime)
    }

    @Test
    fun acceptsHourlessEndpointsIndependentlyAndUnspacedArrows() {
        val document = load(
            """
            WEBVTT

            00:00:05.000-->00:10.000
            First

            00:11.000-->00:00:14.000
            Second

            00:15.000-->00:16.000
            Third
            """
        )
        assertEquals(listOf(5000L, 11_000L, 15_000L), document.entries.map { it.startTime })
        assertEquals(listOf(10_000L, 14_000L, 16_000L), document.entries.map { it.endTime })
    }

    @Test
    fun recoversConsecutiveCuesWithoutBlankSeparators() {
        val document = load(
            """
            WEBVTT
            00:01.000 --> 00:02.000
              First  
            00:03.000 --> 00:04.000
              Second  
            """
        )
        assertEquals(listOf("First", "Second"), document.entries.map { it.text })
        assertEquals(listOf(1, 2), document.entries.map { it.index })
    }

    @Test
    fun numericIdentifiersAreNotLimitedToIntegerRange() {
        val document = load("WEBVTT\n\n00:01.000 --> 00:02.000\nA\n2147483648\n00:03.000 --> 00:04.000\nB")
        assertEquals(listOf("A", "B"), document.entries.map { it.text })
        assertEquals("2147483648", document.entries[1].cueIdentifier)
    }

    @Test
    fun negativeTimesSerializeWithSignedComponentsAndReloadUnchanged() {
        val document = load("WEBVTT\n\n00:00:-1.-250 --> 00:00:00.-5\nNegative")
        val serialized = WebVttSubtitleFormatHandler.write(document)
        assertTrue(serialized.contains("00:00:-01.-250 --> 00:00:00.-005"))
        val reparsed = load(serialized).entries.single()
        assertEquals(-1250L, reparsed.startTime)
        assertEquals(-5L, reparsed.endTime)
    }

    @Test
    fun trimsTextAndDecodesOnlyTheThreeUpstreamEntitiesInOrder() {
        val document = load("WEBVTT\n\n00:01.000 --> 00:02.000\n  &lt;i&gt;Hello&lt;/i&gt; &amp; &nbsp; &quot; &amp;lt;  ")
        assertEquals("<i>Hello</i> & &nbsp; &quot; &lt;", document.entries.single().text)
    }

    @Test
    fun representsCuePositionAsAssAlignmentWhileKeepingExactSettings() {
        val document = load("WEBVTT\n\ncue-id\n00:01.000 --> 00:02.000 line:10% position:20% vertical:rl\nHello")
        val entry = document.entries.single()
        assertEquals("{\\an7}Hello", entry.text)
        assertEquals("cue-id", entry.cueIdentifier)
        assertEquals("line:10% position:20% vertical:rl", entry.cueSettings)
        val serialized = WebVttSubtitleFormatHandler.write(document)
        assertTrue(serialized.contains("line:10% position:20% vertical:rl"))
        assertFalse(serialized.contains("{\\an7}"))
        assertEquals(entry.text, load(serialized).entries.single().text)
    }

    @Test
    fun removingOrChangingAlignmentUpdatesSettingsWithoutDiscardingOtherCueProperties() {
        val document = load("WEBVTT\n\n00:01.000 --> 00:02.000 line:10% position:20% size:70% region:r1 vertical:rl\nHello")
        val removed = document.copy(entries = listOf(document.entries.single().copy(text = "Hello")))
        val saved = load(WebVttSubtitleFormatHandler.write(removed)).entries.single()
        assertEquals("Hello", saved.text)
        assertEquals("size:70% region:r1 vertical:rl", saved.cueSettings)

        val moved = document.copy(entries = listOf(document.entries.single().copy(text = "{\\an9}Hello")))
        val movedSaved = load(WebVttSubtitleFormatHandler.write(moved)).entries.single()
        assertEquals("{\\an9}Hello", movedSaved.text)
        assertTrue(movedSaved.cueSettings.contains("position:80% line:20%"))
        assertTrue(movedSaved.cueSettings.contains("region:r1"))
    }

    @Test
    fun cuePropertyEditsUpdateTheDerivedAlignmentInText() {
        val entry = load("WEBVTT\n\n00:01.000 --> 00:02.000 line:10% position:20%\nHello").entries.single()
        WebVttSubtitleFormatHandler.updateCueSettings(entry, "line:50% position:80% size:80%")
        assertEquals("{\\an6}Hello", entry.text)
        assertEquals("line:50% position:80% size:80%", WebVttSubtitleFormatHandler.getCueSettings(entry))
        WebVttSubtitleFormatHandler.updateCueSettings(entry, "align:center")
        assertEquals("Hello", entry.text)
    }

    @Test
    fun mergesAdjacentCuesWithIdenticalTimesAndDropsExactDuplicates() {
        val document = load(cues(
            cue(1000, 4000, "Hello"),
            cue(1000, 4000, "World"),
            cue(5000, 8000, "Duplicate"),
            cue(5000, 8000, "Duplicate")
        ))
        assertEquals(listOf("Hello\nWorld", "Duplicate"), document.entries.map { it.text })
        assertEquals(listOf(1, 2), document.entries.map { it.index })
    }

    @Test
    fun doesNotMergeNonAdjacentCuesWithIdenticalTimes() {
        val document = load(cues(
            cue(1000, 4000, "First"),
            cue(5000, 8000, "Middle"),
            cue(1000, 4000, "Last")
        ))
        assertEquals(listOf("First", "Middle", "Last"), document.entries.map { it.text })
    }

    @Test
    fun requiresAnExactRegionMatchForSameTimeMerging() {
        val different = load(cues(
            cue(1000, 4000, "First", "region:top"),
            cue(1000, 4000, "Second", "region:bottom")
        ))
        assertEquals(2, different.entries.size)
        val same = load(cues(
            cue(1000, 4000, "First", "region:top"),
            cue(1000, 4000, "Second", "region:top")
        ))
        assertEquals("First\nSecond", same.entries.single().text)
    }

    @Test
    fun mergesVerticallyNearbyRowsEvenWhenHorizontalPositionsDiffer() {
        val document = load(cues(
            cue(1000, 4000, "First", "position:36.67%,start align:start size:36.67% line:79.29%"),
            cue(1000, 4000, "Second", "position:23.33%,start align:start size:61.43% line:84.62%")
        ))
        assertEquals("First\n{\\an1}Second", document.entries.single().text)
    }

    @Test
    fun doesNotMergeVerticallyDistantCues() {
        val document = load(cues(
            cue(1000, 4000, "Dialogue", "line:79.33% position:50%"),
            cue(1000, 4000, "Prompt", "line:10% position:50%")
        ))
        assertEquals(listOf("Dialogue", "{\\an8}Prompt"), document.entries.map { it.text })
    }

    @Test
    fun comparesVerticalLineNumbersIncludingTheirAlignmentSuffixes() {
        val nearby = load(cues(
            cue(1000, 4000, "First", "line:-2,start"),
            cue(1000, 4000, "Second", "line:-1,start")
        ))
        assertEquals(1, nearby.entries.size)
        val distant = load(cues(
            cue(1000, 4000, "Top", "line:0,start"),
            cue(1000, 4000, "Bottom", "line:-1,end")
        ))
        assertEquals(2, distant.entries.size)
    }

    @Test
    fun includesExactlyFifteenPercentVerticalSeparationButRejectsMore() {
        val atBoundary = load(cues(
            cue(1000, 4000, "First", "line:10%"),
            cue(1000, 4000, "Second", "line:25%")
        ))
        assertEquals(1, atBoundary.entries.size)
        val beyondBoundary = load(cues(
            cue(1000, 4000, "First", "line:10%"),
            cue(1000, 4000, "Second", "line:25.01%")
        ))
        assertEquals(2, beyondBoundary.entries.size)
    }

    @Test
    fun mergesWhenEitherVerticalPositionIsUnknown() {
        listOf("", "line:auto").forEach { unknown ->
            val document = load(cues(
                cue(1000, 4000, "First", "line:10%"),
                cue(1000, 4000, "Second", unknown)
            ))
            assertEquals(1, document.entries.size)
        }
    }

    @Test
    fun mergesAThreeRowCaptionFromTheLastPairBackward() {
        val document = load(cues(
            cue(1000, 4000, "First", "line:60%"),
            cue(1000, 4000, "Second", "line:74%"),
            cue(1000, 4000, "Third", "line:88%")
        ))
        assertEquals("{\\an5}First\n{\\an5}Second\nThird", document.entries.single().text)
    }

    @Test
    fun appliesMultipleTimestampMapsWithoutRequiringDedicatedBlocks() {
        val document = load(
            """
            WEBVTT
            X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000

            00:01.000 --> 00:02.000
            First

            X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:1800000
            00:01.000 --> 00:02.000
            Second
            """
        )
        assertEquals(listOf(11_000L, 21_000L), document.entries.map { it.startTime })
        assertFalse(WebVttSubtitleFormatHandler.write(document).contains("X-TIMESTAMP-MAP"))
    }

    @Test
    fun resetsTheTimestampOffsetWhenALaterMapIsOutsideTheValidRange() {
        val document = load(
            """
            WEBVTT
            X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000

            00:01.000 --> 00:02.000
            First

            X-TIMESTAMP-MAP=LOCAL:00:00:20.000,MPEGTS:900000
            00:01.000 --> 00:02.000
            Second
            """
        )
        assertEquals(listOf(11_000L, 1000L), document.entries.map { it.startTime })
    }

    @Test
    fun aMapWithoutMpegTsDoesNotResetThePreviousValidTimeBase() {
        val document = load("WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000\n\n00:01.000 --> 00:02.000\nA\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000\n00:03.000 --> 00:04.000\nB")
        assertEquals(listOf(11_000L, 13_000L), document.entries.map { it.startTime })
        assertEquals("A\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000", document.entries[0].text)
    }

    @Test
    fun cleansYouTubeRollUpCaptionsAndKeepsOriginalSpokenLineTimings() {
        val document = load(autoCaptions())
        assertEquals(listOf("One two", "Three four", "Five six"), document.entries.map { it.text })
        assertEquals(listOf(0L, 1010L, 2010L), document.entries.map { it.startTime })
        assertEquals(listOf(1000L, 2000L, 3000L), document.entries.map { it.endTime })
        assertEquals(listOf(1, 2, 3), document.entries.map { it.index })
        assertTrue(document.entries.all { it.cueSettings.isEmpty() })
    }

    @Test
    fun keepsOrdinaryKaraokeWithoutShortBridgeCues() {
        val document = load(cues(
            cue(1000, 4000, "One<00:00:02.000><c> two</c>"),
            cue(5000, 8000, "Three<00:00:06.000><c> four</c>"),
            cue(9000, 12_000, "Five<00:00:10.000><c> six</c>"),
            cue(13_000, 16_000, "Seven<00:00:14.000><c> eight</c>")
        ))
        assertEquals(4, document.entries.size)
        assertEquals("One<00:00:02.000><c> two</c>", document.entries.first().text)
    }

    @Test
    fun requiresAtLeastThreeWordTimestampCuesForAutomaticCleanup() {
        val document = load(cues(
            cue(0, 1000, "One<00:00:00.500><c> two</c>"),
            cue(1000, 1010, "One two"),
            cue(1010, 2000, "Three<00:00:01.500><c> four</c>"),
            cue(2000, 2010, "Three four")
        ))
        assertEquals(4, document.entries.size)
        assertTrue(document.entries.first().text.contains("<00:00:00.500>"))
    }

    @Test
    fun requiresTwoBridgeCuesNoLongerThanOneHundredMilliseconds() {
        val atBoundary = load(autoCaptions(firstBridgeDuration = 100, secondBridgeDuration = 100))
        assertEquals(3, atBoundary.entries.size)
        val overBoundary = load(autoCaptions(firstBridgeDuration = 100, secondBridgeDuration = 101))
        assertEquals(5, overBoundary.entries.size)
        assertTrue(overBoundary.entries.first().text.contains("<00:00:00.500>"))
    }

    @Test
    fun requiresWordTimestampCuesToRepresentAtLeastOneQuarterOfTheFile() {
        fun withTail(tailCount: Int): String = autoCaptions() + "\n\n" + (0 until tailCount).joinToString("\n\n") {
            cue(4000L + it * 2000L, 5000L + it * 2000L, "Tail $it")
        }
        val atBoundary = load(withTail(7))
        assertEquals(10, atBoundary.entries.size)
        assertEquals("One two", atBoundary.entries.first().text)
        val belowBoundary = load(withTail(8))
        assertEquals(13, belowBoundary.entries.size)
        assertTrue(belowBoundary.entries.first().text.contains("<00:00:00.500>"))
    }

    @Test
    fun cleanupRetainsNewTextEvenWhenTheCueIsAShortBridge() {
        val document = load(autoCaptions().replace("\nOne two\n\n", "\nOne two\nNew text\n\n"))
        assertEquals(4, document.entries.size)
        assertEquals("New text", document.entries[1].text)
        assertEquals(1000L, document.entries[1].startTime)
        assertEquals(1010L, document.entries[1].endTime)
    }

    private fun autoCaptions(firstBridgeDuration: Long = 10L, secondBridgeDuration: Long = 10L): String = cues(
        cue(0, 1000, "One<00:00:00.500><c> two</c>", "align:start position:0%"),
        cue(1000, 1000 + firstBridgeDuration, "One two", "align:start position:0%"),
        cue(1010, 2000, "One two\nThree<00:00:01.500><c> four</c>", "align:start position:0%"),
        cue(2000, 2000 + secondBridgeDuration, "Three four", "align:start position:0%"),
        cue(2010, 3000, "Three four\nFive<00:00:02.500><c> six</c>", "align:start position:0%")
    )

    private fun cues(vararg cues: String): String = "WEBVTT\n\n" + cues.joinToString("\n\n")

    private fun cue(startMs: Long, endMs: Long, text: String, settings: String = ""): String {
        val entry = SubtitleEntry(startTime = startMs, endTime = endMs)
        return "${entry.formatTimeSRT(startMs).replace(',', '.')} --> ${entry.formatTimeSRT(endMs).replace(',', '.')}" +
            (if (settings.isEmpty()) "" else " $settings") + "\n$text"
    }
}
