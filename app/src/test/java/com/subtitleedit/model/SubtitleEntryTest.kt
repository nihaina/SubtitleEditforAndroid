package com.subtitleedit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleEntryTest {

    @Test
    fun formatTimeSRT_matchesSrtLayout() {
        val entry = SubtitleEntry()
        assertEquals("00:00:00,000", entry.formatTimeSRT(0))
        assertEquals("01:01:01,234", entry.formatTimeSRT(3661234))
    }

    @Test
    fun formatTimeLRC_matchesLrcLayout() {
        val entry = SubtitleEntry()
        assertEquals("[00:01.23]", entry.formatTimeLRC(1230))
    }

    @Test
    fun formatTimeLRC_noSixtySecondRollover() {
        // 回归测试：旧实现在 59995ms 输出非法的 [00:60.00]
        assertEquals("[01:00.00]", SubtitleEntry().formatTimeLRC(59995))
    }

    @Test
    fun getTimeAxisSRT_joinsStartAndEnd() {
        val entry = SubtitleEntry(startTime = 1000, endTime = 2000)
        assertEquals("00:00:01,000 --> 00:00:02,000", entry.getTimeAxisSRT())
    }

    @Test
    fun getTimeAxisLRC_usesStartTimeOnly() {
        val entry = SubtitleEntry(startTime = 1000, endTime = 2000)
        assertEquals("[00:01.00]", entry.getTimeAxisLRC())
    }

    @Test
    fun copy_copiesAllFields() {
        val original = SubtitleEntry(
            index = 3,
            startTime = 1000,
            endTime = 2000,
            text = "hello",
            endTimeModified = true,
            cueIdentifier = "cue-1",
            cueSettings = "align:start"
        )
        val copied = original.copy()
        assertEquals(3, copied.index)
        assertEquals(1000L, copied.startTime)
        assertEquals(2000L, copied.endTime)
        assertEquals("hello", copied.text)
        assertTrue(copied.endTimeModified)
        assertEquals("cue-1", copied.cueIdentifier)
        assertEquals("align:start", copied.cueSettings)
    }

    @Test
    fun copy_isIndependentOfOriginal() {
        val original = SubtitleEntry(index = 1, startTime = 1000, endTime = 2000, text = "a")
        val copied = original.copy()
        copied.text = "changed"
        copied.startTime = 9999
        assertEquals("a", original.text)
        assertEquals(1000L, original.startTime)
    }
}
