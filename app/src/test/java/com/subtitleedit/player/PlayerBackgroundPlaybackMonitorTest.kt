package com.subtitleedit.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBackgroundPlaybackMonitorTest {
    private val monitor = PlayerBackgroundPlaybackMonitor()
    private val playback = PlayerBackgroundPlaybackMonitor.Playback(
        loadId = 1L, positionMs = 5_000L, durationMs = 60_000L, speed = 1f,
    )

    @Test fun foregroundReturnCanDetectTheMissedHeartbeat() {
        monitor.enterBackground(0L, playback)
        monitor.recordHeartbeat(500L, playback.copy(positionMs = 5_500L))
        assertTrue(monitor.returnToForeground(6_000L, playback.copy(positionMs = 5_500L)))
        assertFalse(monitor.returnToForeground(7_000L, playback))
    }

    @Test fun heartbeatCanDetectTheGapBeforeForegroundCallbacksRun() {
        monitor.enterBackground(0L, playback)
        monitor.recordHeartbeat(6_000L, playback)
        monitor.recordHeartbeat(6_500L, playback.copy(positionMs = 5_500L))
        assertTrue(monitor.returnToForeground(7_000L, playback.copy(positionMs = 6_000L)))
    }

    @Test fun steadyProgressAtReducedSpeedDoesNotLookFrozen() {
        val slow = playback.copy(speed = 0.25f)
        monitor.enterBackground(0L, slow)
        assertFalse(monitor.returnToForeground(6_000L, slow.copy(positionMs = 6_500L)))
    }

    @Test fun ordinaryBufferingAndBriefBackgroundVisitsDoNotTrigger() {
        monitor.enterBackground(0L, playback)
        monitor.recordHeartbeat(1_000L, playback)
        monitor.recordHeartbeat(2_000L, playback)
        assertFalse(monitor.returnToForeground(3_000L, playback))
    }

    @Test fun pauseAndSeekResetPriorEvidence() {
        monitor.enterBackground(0L, playback)
        monitor.recordHeartbeat(6_000L, playback)
        monitor.resetPlayback()
        assertFalse(monitor.returnToForeground(7_000L, playback.copy(positionMs = 12_000L)))
        monitor.enterBackground(8_000L, playback)
        assertFalse(monitor.returnToForeground(14_000L, null))
    }

    @Test fun switchingTracksAndNaturalEndDoNotTrigger() {
        monitor.enterBackground(0L, playback)
        assertFalse(monitor.returnToForeground(6_000L, playback.copy(loadId = 2L)))
        monitor.enterBackground(7_000L, playback.copy(positionMs = 59_500L))
        assertFalse(monitor.returnToForeground(13_000L, playback.copy(positionMs = 60_000L)))
    }
}
