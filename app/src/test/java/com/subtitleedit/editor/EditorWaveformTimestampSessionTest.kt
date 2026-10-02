package com.subtitleedit.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorWaveformTimestampSessionTest {
    @Test
    fun panningKeepsTheStartAtTheOriginalScreenAnchor() {
        val session = EditorWaveformTimestampSession.start(
            startMs = 90_000L,
            visibleStartMs = 0L,
            visibleDurationMs = 180_000L,
            viewportWidthPx = 1_000
        )

        val movedStart = session.panViewport(
            visibleStartMs = 0L,
            deltaMs = 30_000L,
            visibleDurationMs = 180_000L,
            durationMs = 600_000L
        )

        assertEquals(30_000L, movedStart)
        assertEquals(
            120_000L,
            session.endTime(movedStart, 180_000L, 1_000, 600_000L)
        )
    }

    @Test
    fun panningAllowsTheVirtualAudioPaddingAtBothEnds() {
        val session = EditorWaveformTimestampSession.start(
            startMs = 10_000L,
            visibleStartMs = 0L,
            visibleDurationMs = 180_000L,
            viewportWidthPx = 1_000
        )

        assertEquals(
            -180_000L,
            session.panViewport(0L, -180_000L, 180_000L, 600_000L)
        )
        assertEquals(
            600_000L,
            session.panViewport(420_000L, 180_000L, 180_000L, 600_000L)
        )
    }

    @Test
    fun endTimeIsClampedToMediaDuration() {
        val session = EditorWaveformTimestampSession.start(
            startMs = 170_000L,
            visibleStartMs = 100_000L,
            visibleDurationMs = 180_000L,
            viewportWidthPx = 1_000
        )

        assertEquals(180_000L, session.endTime(120_000L, 180_000L, 1_000, 180_000L))
    }
}
