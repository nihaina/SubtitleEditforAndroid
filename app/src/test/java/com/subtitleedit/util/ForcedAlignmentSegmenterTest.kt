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
}
