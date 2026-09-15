package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.WhisperRecognizer.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticSubtitleMergerTest {
    @Test
    fun mergeByAiBoundariesMergesAdjacentShortSubtitles() {
        val source = listOf(
            SubtitleSegment(100L, 200L, "你好"),
            SubtitleSegment(210L, 300L, "世界"),
            SubtitleSegment(310L, 400L, "这里"),
            SubtitleSegment(410L, 500L, "是我的世界")
        )

        val merged = SemanticSubtitleMerger.mergeByAiBoundaries(source, "你好世界  这里是我的世界")

        assertEquals(2, merged.size)
        assertEquals(100L, merged[0].startTime)
        assertEquals(300L, merged[0].endTime)
        assertEquals("你好世界", merged[0].text)
        assertEquals("这里是我的世界", merged[1].text)
    }

    @Test
    fun mergeByAiBoundariesKeepsSourceWhenAiChangesText() {
        val source = listOf(SubtitleSegment(0L, 100L, "你好"))

        val result = SemanticSubtitleMerger.mergeByAiBoundaries(source, "您好")

        assertEquals(source, result)
    }

    @Test
    fun mergeSubtitleEntriesByAiBoundariesCarriesMergedEndTime() {
        val source = listOf(
            SubtitleEntry(index = 1, startTime = 100L, endTime = 200L, text = "你"),
            SubtitleEntry(index = 2, startTime = 210L, endTime = 300L, text = "好")
        )

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesByAiBoundaries(source, "你好")

        assertEquals(1, merged.size)
        assertEquals(100L, merged[0].startTime)
        assertEquals(300L, merged[0].endTime)
        assertEquals("你好", merged[0].text)
    }
}
