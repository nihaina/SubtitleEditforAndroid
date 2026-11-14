package com.subtitleedit.player

import org.junit.Assert.*
import org.junit.Test

class PlayerListeningTrackerTest {
    private val wall = 1_800_000_000_000L

    @Test fun onlyActualPlayingTimeCountsAndPauseKeepsTheSubsecondTail() {
        val tracker = tracker()
        tracker.select("a", false, 0, wall)
        assertTrue(tracker.update(false, 1_000, wall + 1_000).isEmpty())
        assertFalse(tracker.hasPlayed)
        tracker.update(true, 2_000, wall + 2_000)
        val first = tracker.update(false, 2_150, wall + 2_150).single()
        assertEquals(150L, first.durationMs)
        assertEquals(wall + 2_000, first.startedAtMs)
        tracker.update(true, 20_000, wall + 20_000)
        val second = tracker.update(false, 20_300, wall + 20_300).single()
        assertEquals(300L, second.durationMs)
        assertEquals(first.playbackId, second.playbackId)
        assertTrue(tracker.flush(20_300, wall + 20_300).isEmpty())
    }

    @Test fun periodicFlushIsExactAndDoesNotStopTheRun() {
        val tracker = tracker()
        tracker.select("a", false, 0, wall)
        tracker.update(true, 0, wall)
        assertEquals(5_000L, tracker.flush(5_000, wall + 5_000).single().durationMs)
        assertTrue(tracker.playing)
        val tail = tracker.update(false, 5_123, wall + 5_123).single()
        assertEquals(123L, tail.durationMs)
        assertEquals(wall + 5_000, tail.startedAtMs)
    }

    @Test fun SurfaceAndTagReloadsKeepTheRunButRepeatsAndTrackChangesDoNot() {
        val tracker = tracker()
        tracker.select("a", false, 0, wall)
        tracker.update(true, 0, wall)
        val firstId = tracker.playbackId
        assertEquals(1_000L, tracker.select("a", true, 1_000, wall + 1_000).single().durationMs)
        assertEquals(firstId, tracker.playbackId)
        assertTrue(tracker.hasPlayed)
        tracker.update(true, 2_000, wall + 2_000)
        tracker.select("a", false, 3_000, wall + 3_000)
        assertNotEquals(firstId, tracker.playbackId)
        assertFalse(tracker.hasPlayed)
        val repeatedId = tracker.playbackId
        tracker.select("b", true, 4_000, wall + 4_000)
        assertNotEquals(repeatedId, tracker.playbackId)
        assertEquals("b", tracker.path)
    }

    @Test fun BufferingStopsTimeAndLongActiveIntervalsAreNotArbitrarilyClamped() {
        val tracker = tracker()
        tracker.select("a", false, 0, wall)
        tracker.update(true, 0, wall)
        assertEquals(30_000L, tracker.update(false, 30_000, wall + 30_000).single().durationMs)
        assertTrue(tracker.update(false, 60_000, wall + 60_000).isEmpty())
        tracker.update(true, 61_000, wall + 61_000)
        assertEquals(1_000L, tracker.update(false, 62_000, wall + 62_000).single().durationMs)
    }

    @Test fun wallClockChangesDoNotInventOrLoseElapsedListening() {
        val tracker = tracker()
        tracker.select("a", false, 0, wall)
        tracker.update(true, 0, wall)
        val beforeJump = tracker.update(true, 5_000, wall + 3_605_000).single()
        assertEquals(wall, beforeJump.startedAtMs)
        assertEquals(5_000L, beforeJump.durationMs)
        val afterJump = tracker.update(false, 5_500, wall + 3_605_500).single()
        assertEquals(wall + 3_605_000, afterJump.startedAtMs)
        assertEquals(500L, afterJump.durationMs)
    }

    @Test fun monotonicClockRegressionCannotCreateNegativeTime() {
        val tracker = tracker()
        tracker.select("a", false, 100, wall)
        tracker.update(true, 100, wall)
        tracker.update(true, 200, wall + 100)
        assertEquals(100L, tracker.update(true, 50, wall + 200).single().durationMs)
        assertEquals(100L, tracker.update(false, 150, wall + 300).single().durationMs)
    }

    private fun tracker(): PlayerListeningTracker {
        var id = 0
        return PlayerListeningTracker { "run-${++id}" }
    }
}
