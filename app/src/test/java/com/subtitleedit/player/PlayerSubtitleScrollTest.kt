package com.subtitleedit.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSubtitleScrollTest {
    @Test fun holdsTheFirstAndLastLinesForShortAndLongCues() {
        assertEquals(0f, subtitleScrollProgress(1_250, 1_000, 2_000), 0f)
        assertEquals(.5f, subtitleScrollProgress(1_500, 1_000, 2_000), 0f)
        assertEquals(1f, subtitleScrollProgress(1_750, 1_000, 2_000), 0f)
        assertEquals(0f, subtitleScrollProgress(1_650, 1_000, 11_000), 0f)
        assertEquals(.5f, subtitleScrollProgress(6_000, 1_000, 11_000), 0f)
        assertEquals(1f, subtitleScrollProgress(10_350, 1_000, 11_000), 0f)
    }

    @Test fun staysWithinTheCueAndLeavesUntimedTextAtTheStart() {
        assertEquals(0f, subtitleScrollProgress(250, 1_000, 2_000), 0f)
        assertEquals(1f, subtitleScrollProgress(2_500, 1_000, 2_000), 0f)
        assertEquals(0f, subtitleScrollProgress(2_500, 1_000, 1_000), 0f)
    }

    @Test fun followsPlaybackSpeedAndFreezesWhenPaused() {
        val cue = PlayerOverlaySubtitleCue(1L, "cue", PlayerSubtitleCueTiming(0L, 4_000L), 1_000L, 5_000L, true, 2f)
        assertEquals(.5f, cue.progressAt(5_500L), 0f)
        assertEquals(cue.progressAt(5_000L), cue.copy(playing = false).progressAt(6_000L), 0f)
        assertEquals(0f, cue.copy(timing = null).progressAt(6_000L), 0f)
    }
}
