package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleEntryOpsTest {

    // ==================== deepCopy ====================

    @Test
    fun deepCopy_listIsIndependent() {
        val original = listOf(SubtitleEntry(index = 1, startTime = 0, endTime = 1000, text = "a"))
        val copied = SubtitleEntryOps.deepCopy(original)
        copied[0].text = "changed"
        assertEquals("a", original[0].text)
    }

    @Test
    fun deepCopy_listPreservesOrderAndFields() {
        val original = listOf(
            SubtitleEntry(index = 1, startTime = 0, endTime = 1000, text = "a"),
            SubtitleEntry(index = 2, startTime = 1000, endTime = 2000, text = "b")
        )
        val copied = SubtitleEntryOps.deepCopy(original)
        assertEquals(original.size, copied.size)
        assertEquals(original, copied)
        assertEquals(listOf(1, 2), copied.map { it.index })
    }

    @Test
    fun deepCopy_singleEntryIsIndependent() {
        val original = SubtitleEntry(index = 1, startTime = 0, endTime = 1000, text = "a")
        val copied = SubtitleEntryOps.deepCopy(original)

        assertEquals(original, copied)
        assertNotSame(original, copied)

        copied.text = "changed"
        copied.startTime = 500
        copied.endTime = 1500
        copied.index = 9
        assertEquals("a", original.text)
        assertEquals(0L, original.startTime)
        assertEquals(1000L, original.endTime)
        assertEquals(1, original.index)
    }

    @Test
    fun deepCopy_emptyListReturnsEmpty() {
        assertTrue(SubtitleEntryOps.deepCopy(emptyList()).isEmpty())
    }

    // ==================== createInsertedEntry（向后插入） ====================

    @Test
    fun insertAfter_noNext_usesDefaultDuration() {
        val entry = SubtitleEntryOps.createInsertedEntry(
            after = true,
            reference = SubtitleEntry(startTime = 1000, endTime = 2000),
            previous = null,
            next = null
        )
        assertEquals(2000L, entry.startTime)
        assertEquals(5000L, entry.endTime)
        assertEquals("新字幕", entry.text)
    }

    @Test
    fun insertAfter_closeNext_clampsToNextStart() {
        val entry = SubtitleEntryOps.createInsertedEntry(
            after = true,
            reference = SubtitleEntry(startTime = 1000, endTime = 2000),
            previous = null,
            next = SubtitleEntry(startTime = 3000, endTime = 4000)
        )
        assertEquals(2000L, entry.startTime)
        assertEquals(3000L, entry.endTime)
    }

    @Test
    fun insertAfter_farNext_keepsDefaultDuration() {
        val entry = SubtitleEntryOps.createInsertedEntry(
            after = true,
            reference = SubtitleEntry(startTime = 1000, endTime = 2000),
            previous = null,
            next = SubtitleEntry(startTime = 8000, endTime = 9000)
        )
        assertEquals(5000L, entry.endTime)
    }

    @Test
    fun insertAfter_overlappingNext_fallsBackToDefault() {
        // next 开始时间不在 (groupStart, defaultEnd) 区间内时使用默认时长（会与 next 重叠，记录现状）
        val entry = SubtitleEntryOps.createInsertedEntry(
            after = true,
            reference = SubtitleEntry(startTime = 1000, endTime = 2000),
            previous = null,
            next = SubtitleEntry(startTime = 1500, endTime = 1800)
        )
        assertEquals(2000L, entry.startTime)
        assertEquals(5000L, entry.endTime)
    }

    // ==================== createInsertedEntry（向前插入） ====================

    @Test
    fun insertBefore_noPrevious_usesDefaultDuration() {
        val entry = SubtitleEntryOps.createInsertedEntry(
            after = false,
            reference = SubtitleEntry(startTime = 10000, endTime = 12000),
            previous = null,
            next = null
        )
        assertEquals(7000L, entry.startTime)
        assertEquals(10000L, entry.endTime)
    }

    @Test
    fun insertBefore_closePrevious_clampsToPreviousEnd() {
        val entry = SubtitleEntryOps.createInsertedEntry(
            after = false,
            reference = SubtitleEntry(startTime = 10000, endTime = 12000),
            previous = SubtitleEntry(startTime = 7500, endTime = 8000),
            next = null
        )
        assertEquals(8000L, entry.startTime)
        assertEquals(10000L, entry.endTime)
    }

    @Test
    fun insertBefore_nearZero_clampsToZero() {
        val entry = SubtitleEntryOps.createInsertedEntry(
            after = false,
            reference = SubtitleEntry(startTime = 1000, endTime = 2000),
            previous = null,
            next = null
        )
        assertEquals(0L, entry.startTime)
        assertEquals(1000L, entry.endTime)
    }

    @Test
    fun insertBefore_referenceAtZero_producesZeroLengthEntry() {
        // 记录现状：参考条目从 0 开始时，向前插入会得到 0-0 的零时长条目
        val entry = SubtitleEntryOps.createInsertedEntry(
            after = false,
            reference = SubtitleEntry(startTime = 0, endTime = 2000),
            previous = null,
            next = null
        )
        assertEquals(0L, entry.startTime)
        assertEquals(0L, entry.endTime)
    }

    // ==================== createInsertedEntries（批量） ====================

    @Test
    fun insertMultiple_evenSplitWithDefaultDuration() {
        val entries = SubtitleEntryOps.createInsertedEntries(
            after = true,
            reference = SubtitleEntry(startTime = 0, endTime = 1000),
            previous = null,
            next = null,
            texts = listOf("a", "b", "c")
        )
        assertEquals(3, entries.size)
        assertEquals(1000L, entries[0].startTime)
        assertEquals(4000L, entries[0].endTime)
        assertEquals(4000L, entries[1].startTime)
        assertEquals(7000L, entries[1].endTime)
        assertEquals(7000L, entries[2].startTime)
        assertEquals(10000L, entries[2].endTime)
        assertEquals(listOf("a", "b", "c"), entries.map { it.text })
    }

    @Test
    fun insertMultiple_squeezedIntoGap_lastEndsExactlyAtNextStart() {
        val entries = SubtitleEntryOps.createInsertedEntries(
            after = true,
            reference = SubtitleEntry(startTime = 0, endTime = 1000),
            previous = null,
            next = SubtitleEntry(startTime = 2000, endTime = 3000),
            texts = listOf("a", "b", "c")
        )
        assertEquals(1000L, entries[0].startTime)
        assertEquals(2000L, entries[2].endTime)
        // 相邻条目首尾相接、无缝隙
        assertEquals(entries[0].endTime, entries[1].startTime)
        assertEquals(entries[1].endTime, entries[2].startTime)
    }

    @Test
    fun insertMultiple_emptyTextsReturnsEmpty() {
        val entries = SubtitleEntryOps.createInsertedEntries(
            after = true,
            reference = SubtitleEntry(startTime = 0, endTime = 1000),
            previous = null,
            next = null,
            texts = emptyList()
        )
        assertTrue(entries.isEmpty())
    }

    // ==================== applyOffset ====================

    @Test
    fun applyOffset_shiftsBothTimes() {
        val entry = SubtitleEntry(startTime = 1000, endTime = 2000)
        SubtitleEntryOps.applyOffset(entry, 500)
        assertEquals(1500L, entry.startTime)
        assertEquals(2500L, entry.endTime)
    }

    @Test
    fun applyOffset_clampsStartAtZero() {
        val entry = SubtitleEntry(startTime = 1000, endTime = 2000)
        SubtitleEntryOps.applyOffset(entry, -1500)
        assertEquals(0L, entry.startTime)
        assertEquals(500L, entry.endTime)
    }

    @Test
    fun applyOffset_keepsEndAfterStart() {
        val entry = SubtitleEntry(startTime = 1000, endTime = 2000)
        SubtitleEntryOps.applyOffset(entry, -3000)
        assertEquals(0L, entry.startTime)
        assertEquals(1L, entry.endTime)
    }

    @Test
    fun applyOffsetAll_appliesToEveryEntry() {
        val entries = listOf(
            SubtitleEntry(startTime = 1000, endTime = 2000),
            SubtitleEntry(startTime = 3000, endTime = 4000)
        )
        SubtitleEntryOps.applyOffsetAll(entries, 1000)
        assertEquals(2000L, entries[0].startTime)
        assertEquals(4000L, entries[1].startTime)
    }

    // ==================== waveform drag bounds ====================

    @Test
    fun clampMoveToNeighbors_stopsAtPreviousAndNextBoundaries() {
        val againstPrevious = SubtitleEntryOps.clampMoveToNeighbors(
            originalStartTime = 2000,
            originalEndTime = 3000,
            desiredStartTime = 500,
            previousEndTime = 1000,
            nextStartTime = 5000
        )
        assertEquals(1000L, againstPrevious.startTime)
        assertEquals(2000L, againstPrevious.endTime)

        val againstNext = SubtitleEntryOps.clampMoveToNeighbors(
            originalStartTime = 2000,
            originalEndTime = 3000,
            desiredStartTime = 6000,
            previousEndTime = 1000,
            nextStartTime = 5000
        )
        assertEquals(4000L, againstNext.startTime)
        assertEquals(5000L, againstNext.endTime)
    }

    @Test
    fun clampMoveToNeighbors_withoutRoomKeepsOriginalRange() {
        val result = SubtitleEntryOps.clampMoveToNeighbors(
            originalStartTime = 2000,
            originalEndTime = 4000,
            desiredStartTime = 2500,
            previousEndTime = 1500,
            nextStartTime = 3000
        )
        assertEquals(2000L, result.startTime)
        assertEquals(4000L, result.endTime)
    }

    @Test
    fun clampStartToNeighbors_stopsExactlyAtPreviousEnd() {
        val result = SubtitleEntryOps.clampStartToNeighbors(
            originalStartTime = 2000,
            currentEndTime = 4000,
            desiredStartTime = 500,
            previousEndTime = 1500,
            minimumDurationMs = 100
        )
        assertEquals(1500L, result)
    }

    @Test
    fun clampEndToNeighbors_stopsExactlyAtNextStart() {
        val result = SubtitleEntryOps.clampEndToNeighbors(
            originalEndTime = 3000,
            currentStartTime = 2000,
            desiredEndTime = 6000,
            nextStartTime = 5000,
            minimumDurationMs = 100
        )
        assertEquals(5000L, result)
    }

    // ==================== mergeAdjacent ====================

    @Test
    fun mergeAdjacent_mergesGapAtBoundaryAndJoinsTextInOrder() {
        val entries = listOf(
            SubtitleEntry(index = 1, startTime = 0, endTime = 1000, text = "a"),
            SubtitleEntry(index = 2, startTime = 1200, endTime = 2000, text = "b"),
            SubtitleEntry(index = 3, startTime = 2500, endTime = 3000, text = "c")
        )

        val merged = SubtitleEntryOps.mergeAdjacent(entries, maxGapMs = 200)

        assertEquals(2, merged.size)
        assertEquals(0L, merged[0].startTime)
        assertEquals(2000L, merged[0].endTime)
        assertEquals("a；b", merged[0].text)
        assertEquals("c", merged[1].text)
    }

    @Test
    fun mergeAdjacent_mergesConsecutiveMatchingEntriesIntoOneGroup() {
        val entries = listOf(
            SubtitleEntry(startTime = 0, endTime = 1000, text = "a"),
            SubtitleEntry(startTime = 1100, endTime = 2000, text = "b"),
            SubtitleEntry(startTime = 2100, endTime = 3000, text = "c")
        )

        val merged = SubtitleEntryOps.mergeAdjacent(entries, maxGapMs = 100)

        assertEquals(1, merged.size)
        assertEquals("a；b；c", merged.single().text)
        assertEquals(3000L, merged.single().endTime)
    }

    @Test
    fun mergeAdjacent_mergesOverlapsButUsesEachOriginalAdjacentGap() {
        val entries = listOf(
            SubtitleEntry(startTime = 0, endTime = 5000, text = "a"),
            SubtitleEntry(startTime = 1000, endTime = 2000, text = "b"),
            SubtitleEntry(startTime = 4000, endTime = 4500, text = "c")
        )

        val merged = SubtitleEntryOps.mergeAdjacent(entries, maxGapMs = 100)

        assertEquals(2, merged.size)
        assertEquals("a；b", merged[0].text)
        assertEquals(5000L, merged[0].endTime)
        assertEquals("c", merged[1].text)
    }

    @Test
    fun mergeAdjacent_doesNotModifyOriginalEntries() {
        val entries = listOf(
            SubtitleEntry(startTime = 0, endTime = 1000, text = "a"),
            SubtitleEntry(startTime = 1000, endTime = 2000, text = "b", endTimeModified = true)
        )

        val merged = SubtitleEntryOps.mergeAdjacent(entries, maxGapMs = 0)

        assertNotSame(entries[0], merged[0])
        assertEquals("a", entries[0].text)
        assertEquals(1000L, entries[0].endTime)
        assertEquals(true, merged[0].endTimeModified)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mergeAdjacent_rejectsNegativeGap() {
        SubtitleEntryOps.mergeAdjacent(emptyList(), maxGapMs = -1)
    }
}
