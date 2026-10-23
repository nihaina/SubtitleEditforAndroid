package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForcedAlignmentSegmenterTest {
    @Test
    fun splitsAlignedUnitsAtLargeSilenceAndKeepsWindowOffset() {
        val segments = ForcedAlignmentSegmenter.split(
            units = listOf(
                ForcedAlignmentUnit("你好", 100, 500),
                ForcedAlignmentUnit("世界", 1_300, 1_700),
            ),
            audioStartTimeMs = 10_000,
            audioEndTimeMs = 12_000,
            splitGapMs = 500,
        )

        assertEquals(2, segments.size)
        assertEquals(10_000L, segments[0].startTimeMs)
        assertEquals(10_750L, segments[0].endTimeMs)
        assertEquals(11_050L, segments[1].startTimeMs)
        assertEquals(2, segments[1].text.length)
        assertTrue(segments[1].hardBoundaryBefore)
    }

    @Test
    fun insertsSpacesOnlyBetweenAsciiWords() {
        val segments = ForcedAlignmentSegmenter.split(
            units = listOf(
                ForcedAlignmentUnit("hello", 0, 100),
                ForcedAlignmentUnit("world", 120, 240),
                ForcedAlignmentUnit("你", 260, 320),
                ForcedAlignmentUnit("好", 340, 400),
            ),
            audioStartTimeMs = 0,
            audioEndTimeMs = 1_000,
            splitGapMs = 2_000,
        )

        assertEquals(1, segments.size)
        assertEquals("hello world你好", segments.single().text)
    }

    @Test
    fun mergeUsesAlignedGapRatherThanPaddedSubtitleBoundary() {
        val segments = ForcedAlignmentSegmenter.split(
            units = listOf(
                ForcedAlignmentUnit("hello", 100, 200),
                ForcedAlignmentUnit("world", 460, 560),
            ),
            audioStartTimeMs = 1_000,
            audioEndTimeMs = 2_000,
            splitGapMs = 250,
        )

        assertEquals(2, segments.size)
        assertEquals(10L, segments[1].startTimeMs - segments[0].endTimeMs)
        assertEquals(2, ForcedAlignmentSegmenter.mergeSegments(segments, maxGapMs = 150).size)
        val merged = ForcedAlignmentSegmenter.mergeSegments(segments, maxGapMs = 260)
        assertEquals(1, merged.size)
        assertEquals("hello world", merged.single().text)
    }

    @Test
    fun zeroSplitGapKeepsShortGappedAlignedUnitsAvailableToPostProcessing() {
        val units = listOf(
            ForcedAlignmentUnit("甲", 100, 150),
            ForcedAlignmentUnit("乙", 170, 220),
            ForcedAlignmentUnit("丙", 240, 290),
        )
        val preGrouped = ForcedAlignmentSegmenter.split(
            units = units,
            audioStartTimeMs = 0,
            audioEndTimeMs = 1_000,
            splitGapMs = 250,
        )
        assertEquals(listOf("甲乙丙"), preGrouped.map { it.text })
        assertEquals(
            listOf("甲乙丙"),
            ForcedAlignmentSegmenter.mergeSegments(
                preGrouped,
                maxGapMs = 20,
                smart = true,
                maxCharacters = 2,
                splitOnPunctuation = false,
            ).map { it.text },
        )

        val segments = ForcedAlignmentSegmenter.split(
            units = units,
            audioStartTimeMs = 0,
            audioEndTimeMs = 1_000,
            splitGapMs = 0,
        )

        assertEquals(listOf("甲", "乙", "丙"), segments.map { it.text })
        assertEquals(listOf(100L, 170L, 240L), segments.map { it.alignedStartTimeMs })
        assertEquals(listOf(150L, 220L, 290L), segments.map { it.alignedEndTimeMs })

        val merged = ForcedAlignmentSegmenter.mergeSegments(
            segments,
            maxGapMs = 20,
            smart = true,
            maxCharacters = 2,
            splitOnPunctuation = false,
        )
        assertEquals(listOf("甲乙", "丙"), merged.map { it.text })
    }

    @Test
    fun mergeKeepsSpaceAfterEnglishSentencePunctuation() {
        val segments = ForcedAlignmentSegmenter.split(
            units = listOf(
                ForcedAlignmentUnit("Hello.", 0, 200),
                ForcedAlignmentUnit("World", 250, 450),
            ),
            audioStartTimeMs = 0,
            audioEndTimeMs = 1_000,
            splitGapMs = 250,
        )

        assertEquals(2, segments.size)
        assertEquals("Hello. World", ForcedAlignmentSegmenter.mergeSegments(segments, 50).single().text)
    }

    @Test
    fun punctuationSplitKeepsOriginalDisplayAndAlignmentBoundaries() {
        val segments = listOf(
            ForcedAlignmentSegmenter.Segment(
                900, 1_250, "1234567890。", alignedStartTimeMs = 1_000, alignedEndTimeMs = 1_200,
            ),
            ForcedAlignmentSegmenter.Segment(
                1_250, 1_700, "abcdefgh", alignedStartTimeMs = 1_300, alignedEndTimeMs = 1_600,
            ),
            ForcedAlignmentSegmenter.Segment(
                1_750, 1_900, "ix", alignedStartTimeMs = 1_800, alignedEndTimeMs = 1_850,
            ),
        )

        val merged = ForcedAlignmentSegmenter.mergeSegments(segments, 200, smart = true, maxCharacters = 2, splitOnPunctuation = true)

        assertEquals(listOf("1234567890。", "abcdefgh ix"), merged.map { it.text })
        assertEquals(900L, merged[0].startTimeMs)
        assertEquals(1_250L, merged[0].endTimeMs)
        assertEquals(1_000L, merged[0].alignedStartTimeMs)
        assertEquals(1_200L, merged[0].alignedEndTimeMs)
        assertEquals(1_250L, merged[1].startTimeMs)
        assertEquals(1_900L, merged[1].endTimeMs)
        assertEquals(1_300L, merged[1].alignedStartTimeMs)
        assertEquals(1_850L, merged[1].alignedEndTimeMs)
    }

    @Test
    fun punctuationSplitInsideAGroupedSegmentUsesItsAlignedUnitBoundaries() {
        val original = ForcedAlignmentSegmenter.split(
            units = listOf(
                ForcedAlignmentUnit("12345678", 100, 300),
                ForcedAlignmentUnit("，", 320, 340),
                ForcedAlignmentUnit("甲乙丙丁戊己庚辛", 400, 800),
            ),
            audioStartTimeMs = 1_000,
            audioEndTimeMs = 3_000,
            splitGapMs = 250,
        )
        assertEquals(1, original.size)
        val segments = original + ForcedAlignmentSegmenter.Segment(1_950, 2_200, "尾")

        val merged = ForcedAlignmentSegmenter.mergeSegments(segments, 150, smart = true, maxCharacters = 9)

        assertEquals(listOf("12345678，", "甲乙丙丁戊己庚辛尾"), merged.map { it.text })
        assertEquals(1_000L, merged[0].startTimeMs)
        assertEquals(1_340L, merged[0].endTimeMs)
        assertEquals(1_100L, merged[0].alignedStartTimeMs)
        assertEquals(1_340L, merged[0].alignedEndTimeMs)
        assertEquals(1_400L, merged[1].startTimeMs)
        assertEquals(2_200L, merged[1].endTimeMs)
        assertEquals(1_400L, merged[1].alignedStartTimeMs)
        assertEquals(2_200L, merged[1].alignedEndTimeMs)
    }

    @Test
    fun lengthFilterDoesNotTreatPaddedTouchingBoundariesAsContinuousSpeech() {
        val segments = listOf(
            ForcedAlignmentSegmenter.Segment(
                0, 1_000, "1234567890", alignedStartTimeMs = 100, alignedEndTimeMs = 900,
            ),
            ForcedAlignmentSegmenter.Segment(
                1_000, 2_000, "abcdefghij", alignedStartTimeMs = 1_100, alignedEndTimeMs = 1_900,
            ),
        )

        val merged = ForcedAlignmentSegmenter.mergeSegments(segments, 0, smart = true, maxCharacters = 15)

        assertEquals(segments, merged)
    }

    @Test
    fun lengthFilterAllowsAlignedTouchingSpeechToRemainIntact() {
        val segments = listOf(
            ForcedAlignmentSegmenter.Segment(0, 1_000, "1234567890"),
            ForcedAlignmentSegmenter.Segment(1_000, 2_000, "abcdefghij"),
        )

        val merged = ForcedAlignmentSegmenter.mergeSegments(segments, 0, smart = true, maxCharacters = 15)

        assertEquals("1234567890 abcdefghij", merged.single().text)
        assertEquals(2_000L, merged.single().alignedEndTimeMs)
    }

    @Test
    fun zeroGapRoundSplitsEveryPunctuationBoundaryAndPreservesDisplayAndAlignmentTimes() {
        val parts = listOf(
            TimedTextPart("甲乙", 900, 1_050, 1_000, 1_050),
            TimedTextPart("，", 1_050, 1_060, 1_050, 1_060),
            TimedTextPart("丙丁", 1_060, 1_250, 1_060, 1_200),
            TimedTextPart("。", 1_250, 1_300, 1_200, 1_250),
            TimedTextPart("戊己", 1_300, 1_600, 1_250, 1_500),
        )
        val segments = listOf(
            ForcedAlignmentSegmenter.Segment(
                900, 1_250, "甲乙，丙丁", hardBoundaryBefore = true,
                alignedStartTimeMs = 1_000, alignedEndTimeMs = 1_200,
                parts = parts.take(3),
            ),
            ForcedAlignmentSegmenter.Segment(
                1_250, 1_600, "。戊己", alignedStartTimeMs = 1_200, alignedEndTimeMs = 1_500,
                parts = parts.drop(3),
            ),
        )

        for (limit in listOf(null, 1, 50)) {
            val merged = ForcedAlignmentSegmenter.mergeSegments(
                segments, 0, smart = true, maxCharacters = limit,
            )

            assertEquals(listOf("甲乙，", "丙丁。", "戊己"), merged.map { it.text })
            assertEquals(listOf(900L, 1_060L, 1_300L), merged.map { it.startTimeMs })
            assertEquals(listOf(1_060L, 1_300L, 1_600L), merged.map { it.endTimeMs })
            assertEquals(listOf(1_000L, 1_060L, 1_250L), merged.map { it.alignedStartTimeMs })
            assertEquals(listOf(1_060L, 1_250L, 1_500L), merged.map { it.alignedEndTimeMs })
            assertTrue(merged.all { it.hardBoundaryBefore })
            assertEquals(parts, merged.flatMap { it.parts })
        }

        val disabled = ForcedAlignmentSegmenter.mergeSegments(
            segments, 0, smart = true, maxCharacters = 1, splitOnPunctuation = false,
        )
        assertEquals("甲乙，丙丁。戊己", disabled.single().text)
        assertEquals(900L, disabled.single().startTimeMs)
        assertEquals(1_600L, disabled.single().endTimeMs)
        assertEquals(1_000L, disabled.single().alignedStartTimeMs)
        assertEquals(1_500L, disabled.single().alignedEndTimeMs)
        assertEquals(parts, disabled.single().parts)
    }

    @Test
    fun zeroGapRoundSplitsOverlappingAlignedSourcesAtTheirOriginalPunctuationBoundaries() {
        val parts = listOf(
            TimedTextPart("甲", 0, 100, 50, 100),
            TimedTextPart("，", 100, 110, 100, 110),
            TimedTextPart("乙", 110, 300, 110, 250),
            TimedTextPart("丙", 250, 450, 200, 400),
            TimedTextPart("。", 450, 460, 400, 410),
            TimedTextPart("丁", 460, 600, 410, 550),
        )
        val segments = listOf(
            ForcedAlignmentSegmenter.Segment(
                0, 300, "甲，乙", alignedStartTimeMs = 50, alignedEndTimeMs = 250,
                parts = parts.take(3),
            ),
            ForcedAlignmentSegmenter.Segment(
                250, 600, "丙。丁", alignedStartTimeMs = 200, alignedEndTimeMs = 550,
                parts = parts.drop(3),
            ),
        )

        val merged = ForcedAlignmentSegmenter.mergeSegments(segments, 0, smart = true)

        assertEquals(listOf("甲，", "乙丙。", "丁"), merged.map { it.text })
        assertEquals(listOf(0L, 110L, 460L), merged.map { it.startTimeMs })
        assertEquals(listOf(110L, 460L, 600L), merged.map { it.endTimeMs })
        assertEquals(listOf(50L, 110L, 410L), merged.map { it.alignedStartTimeMs })
        assertEquals(listOf(110L, 410L, 550L), merged.map { it.alignedEndTimeMs })
        assertTrue(merged.drop(1).all { it.hardBoundaryBefore })
        assertEquals(parts, merged.flatMap { it.parts })
    }

    @Test
    fun paddedTouchingTimesDoNotTriggerTheZeroGapPunctuationSplit() {
        val segments = listOf(
            ForcedAlignmentSegmenter.Segment(
                0, 1_000, "甲，乙", alignedStartTimeMs = 100, alignedEndTimeMs = 900,
            ),
            ForcedAlignmentSegmenter.Segment(
                1_000, 2_000, "丙", alignedStartTimeMs = 1_100, alignedEndTimeMs = 1_900,
            ),
        )

        val merged = ForcedAlignmentSegmenter.mergeSegments(segments, 200, smart = true)

        assertEquals("甲，乙丙", merged.single().text)
        assertEquals(0L, merged.single().startTimeMs)
        assertEquals(2_000L, merged.single().endTimeMs)
        assertEquals(100L, merged.single().alignedStartTimeMs)
        assertEquals(1_900L, merged.single().alignedEndTimeMs)
    }

    @Test
    fun manualMerge_ignoresSmartOnlyOptions() {
        val segments = listOf(
            ForcedAlignmentSegmenter.Segment(0, 100, "1234567890。"),
            ForcedAlignmentSegmenter.Segment(200, 300, "甲乙丙丁戊己庚辛"),
        )

        val merged = ForcedAlignmentSegmenter.mergeSegments(segments, 100, smart = false, maxCharacters = 15)

        assertEquals(listOf("1234567890。甲乙丙丁戊己庚辛"), merged.map { it.text })
    }

    @Test
    fun smartMerge_punctuationDirectionUsesTheTextWhileGapsUseAlignmentTimes() {
        val segments = listOf(
            ForcedAlignmentSegmenter.Segment(0, 150, "甲", alignedStartTimeMs = 10, alignedEndTimeMs = 100),
            ForcedAlignmentSegmenter.Segment(150, 250, "乙。", alignedStartTimeMs = 101, alignedEndTimeMs = 200),
            ForcedAlignmentSegmenter.Segment(250, 400, "丙", alignedStartTimeMs = 200, alignedEndTimeMs = 350),
        )

        val merged = ForcedAlignmentSegmenter.mergeSegments(segments, 1, smart = true)

        assertEquals(listOf("甲乙。", "丙"), merged.map { it.text })
        assertEquals(200L, merged.first().alignedEndTimeMs)
        assertEquals(250L, merged.first().endTimeMs)
    }
}
