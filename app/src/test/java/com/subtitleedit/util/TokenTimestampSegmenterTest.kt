package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenTimestampSegmenterTest {

    @Test
    fun mergeSegments_mergesAtThresholdAndPreservesTextOrder() {
        val segments = listOf(
            TokenTimestampSegmenter.Segment(0L, 1000L, "第一", hardBoundaryBefore = false),
            TokenTimestampSegmenter.Segment(1200L, 1600L, "第二", hardBoundaryBefore = true),
            TokenTimestampSegmenter.Segment(1851L, 2200L, "第三", hardBoundaryBefore = true)
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
}
