package com.subtitleedit.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePlaybackSelectionTrackerTest {

    @Test
    fun selectsEachSubtitleOnlyOnceUntilReset() {
        val tracker = SubtitlePlaybackSelectionTracker()

        assertTrue(tracker.selectIfNeeded(1L) { true })
        assertFalse(tracker.selectIfNeeded(1L) { true })
        assertTrue(tracker.selectIfNeeded(2L) { true })
        assertFalse(tracker.selectIfNeeded(2L) { true })
    }

    @Test
    fun resetAllowsASelectedSubtitleToBeSelectedAgain() {
        val tracker = SubtitlePlaybackSelectionTracker()

        assertTrue(tracker.selectIfNeeded(1L) { true })
        tracker.reset()

        assertTrue(tracker.selectIfNeeded(1L) { true })
    }

    @Test
    fun failedSelectionIsRetriedUntilTheSubtitleIsAvailable() {
        val tracker = SubtitlePlaybackSelectionTracker()

        assertFalse(tracker.selectIfNeeded(1L) { false })
        assertTrue(tracker.selectIfNeeded(1L) { true })
    }
}
