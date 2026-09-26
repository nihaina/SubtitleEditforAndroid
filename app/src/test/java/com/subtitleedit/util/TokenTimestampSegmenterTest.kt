package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenTimestampSegmenterTest {

    @Test
    fun mergeSegments_mergesAtThresholdAndPreservesTextOrder() {
        val segments = listOf(
            TokenTimestampSegmenter.Segment(0L, 1000L, "第一"),
            TokenTimestampSegmenter.Segment(1200L, 1600L, "第二"),
            TokenTimestampSegmenter.Segment(1851L, 2200L, "第三")
        )

        val merged = TokenTimestampSegmenter.mergeSegments(segments, maxGapMs = 200)

        assertEquals(2, merged.size)
        assertEquals(0L, merged[0].startTimeMs)
        assertEquals(1600L, merged[0].endTimeMs)
        assertEquals("第一第二", merged[0].text)
        assertEquals("第三", merged[1].text)
    }

    @Test
    fun mergeSegments_zeroOnlyMergesTouchingOrOverlappingSegments() {
        val segments = listOf(
            TokenTimestampSegmenter.Segment(0L, 1000L, "A"),
            TokenTimestampSegmenter.Segment(1000L, 1500L, "B"),
            TokenTimestampSegmenter.Segment(1501L, 2000L, "C")
        )

        val merged = TokenTimestampSegmenter.mergeSegments(segments, maxGapMs = 0)

        assertEquals(2, merged.size)
        assertEquals("A B", merged[0].text)
        assertEquals(1500L, merged[0].endTimeMs)
        assertEquals("C", merged[1].text)
    }

    @Test
    fun fromTokens_usesFormerZeroSplitTimeBoundaries() {
        val tokens = TokenTimestampSegmenter.fromTokens(
            tokens = arrayOf("你", "好", "世界"),
            timestamps = floatArrayOf(0f, 0.1f, 0.46f),
            durations = floatArrayOf(0.1f, 0.1f, 0.1f),
            audioStartTimeMs = 1000L,
            audioEndTimeMs = 2000L
        )

        assertEquals(3, tokens.size)
        assertEquals(1000L, tokens[0].startTimeMs)
        assertEquals(1100L, tokens[0].endTimeMs)
        assertEquals(1100L, tokens[1].startTimeMs)
        assertEquals(1300L, tokens[1].endTimeMs)
        assertEquals(1360L, tokens[2].startTimeMs)
        assertEquals(1660L, tokens[2].endTimeMs)
        assertEquals(2, TokenTimestampSegmenter.mergeSegments(tokens, maxGapMs = 0).size)
        val merged = TokenTimestampSegmenter.mergeSegments(tokens, maxGapMs = 60)
        assertEquals(1, merged.size)
        assertEquals("你好世界", merged.single().text)
    }

    @Test
    fun fromTokens_withoutDurationAddsFormerZeroSplitTimeContext() {
        val tokens = TokenTimestampSegmenter.fromTokens(
            tokens = arrayOf("你"),
            timestamps = floatArrayOf(0.5f),
            durations = floatArrayOf(),
            audioStartTimeMs = 100L,
            audioEndTimeMs = 1100L
        )

        assertEquals(500L, tokens.single().startTimeMs)
        assertEquals(701L, tokens.single().endTimeMs)
    }

    @Test
    fun fromTokens_expandsSixtyMillisecondCtcRunsIntoAvailableContext() {
        val segments = TokenTimestampSegmenter.fromTokens(
            tokens = arrayOf("你", "好"),
            timestamps = floatArrayOf(0.5f, 0.8f),
            durations = floatArrayOf(0.06f, 0.06f),
            audioStartTimeMs = 0L,
            audioEndTimeMs = 1_000L
        )

        assertEquals(2, segments.size)
        assertEquals(400L, segments[0].startTimeMs)
        assertEquals(660L, segments[0].endTimeMs)
        assertEquals(700L, segments[1].startTimeMs)
        assertEquals(960L, segments[1].endTimeMs)
    }
}
