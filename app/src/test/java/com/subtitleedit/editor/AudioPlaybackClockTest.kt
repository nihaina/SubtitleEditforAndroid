package com.subtitleedit.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaybackClockTest {
    @Test
    fun advancesBetweenSparseMpvSamples() {
        val clock = AudioPlaybackClock()
        clock.reset(0L, 0L)
        clock.setPlaying(true, 0L)

        assertEquals(50L, clock.position(50_000_000L))
        clock.acceptSample(100L, 100_000_000L)
        assertEquals(150L, clock.position(150_000_000L))
        clock.acceptSample(200L, 200_000_000L)
        assertEquals(250L, clock.position(250_000_000L))
    }

    @Test
    fun pauseSpeedAndSeekResetThePrediction() {
        val clock = AudioPlaybackClock()
        clock.reset(1_000L, 0L)
        clock.setPlaying(true, 0L)
        clock.setSpeed(2.0f, 100_000_000L)
        assertEquals(1_300L, clock.position(200_000_000L))

        clock.setPlaying(false, 200_000_000L)
        assertEquals(1_300L, clock.position(400_000_000L))
        clock.reset(3_000L, 400_000_000L)
        assertEquals(3_000L, clock.position(500_000_000L))

        clock.setPlaying(true, 500_000_000L)
        assertEquals(3_100L, clock.position(550_000_000L))
    }

    @Test
    fun stopsPredictingWhenMpvStopsReportingProgress() {
        val clock = AudioPlaybackClock()
        clock.reset(0L, 0L)
        clock.setPlaying(true, 0L)

        assertEquals(300L, clock.position(500_000_000L))
        assertEquals(300L, clock.position(800_000_000L))
    }
}
