package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTimeConflictTest {
    @Test
    fun overlapMarksBothSidesOfTheSharedBoundary() {
        val entries = listOf(
            SubtitleEntry(startTime = 0, endTime = 2000),
            SubtitleEntry(startTime = 1500, endTime = 3000)
        )

        assertEquals(SubtitleTimeConflict.Markers(false, true), SubtitleTimeConflict.markers(entries, 0))
        assertEquals(SubtitleTimeConflict.Markers(true, false), SubtitleTimeConflict.markers(entries, 1))
    }

    @Test
    fun middleRowCanHaveBothTimesInConflict() {
        val entries = listOf(
            SubtitleEntry(startTime = 0, endTime = 2000),
            SubtitleEntry(startTime = 1500, endTime = 3000),
            SubtitleEntry(startTime = 2500, endTime = 4000)
        )

        assertEquals(SubtitleTimeConflict.Markers(true, true), SubtitleTimeConflict.markers(entries, 1))
    }

    @Test
    fun reversedRowOrderStillMarksNegativeGapAfterBlocksPassEachOther() {
        val entries = listOf(
            SubtitleEntry(startTime = 5000, endTime = 6000),
            SubtitleEntry(startTime = 1000, endTime = 2000)
        )

        assertEquals(SubtitleTimeConflict.Markers(false, true), SubtitleTimeConflict.markers(entries, 0))
        assertEquals(SubtitleTimeConflict.Markers(true, false), SubtitleTimeConflict.markers(entries, 1))
    }

    @Test
    fun touchingBoundariesStayNormalAndInvalidRangeMarksBothTimes() {
        val entries = listOf(
            SubtitleEntry(startTime = 0, endTime = 1000),
            SubtitleEntry(startTime = 1000, endTime = 2000),
            SubtitleEntry(startTime = 3000, endTime = 3000)
        )

        assertEquals(SubtitleTimeConflict.Markers(false, false), SubtitleTimeConflict.markers(entries, 0))
        assertEquals(SubtitleTimeConflict.Markers(false, false), SubtitleTimeConflict.markers(entries, 1))
        assertEquals(SubtitleTimeConflict.Markers(true, true), SubtitleTimeConflict.markers(entries, 2))
    }
}
