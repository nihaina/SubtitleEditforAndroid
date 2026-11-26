package com.subtitleedit.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerHardwareVolumeBarTest {
    @Test fun verticalTrackMapsTopToMaximumAndBottomToMinimum() {
        assertEquals(100, playerVolumePercentAt(positionY = 20f, trackTop = 20f, trackBottom = 120f))
        assertEquals(0, playerVolumePercentAt(positionY = 120f, trackTop = 20f, trackBottom = 120f))
        assertEquals(50, playerVolumePercentAt(positionY = 70f, trackTop = 20f, trackBottom = 120f))
        assertEquals(51, playerVolumePercentAt(positionY = 69.4f, trackTop = 20f, trackBottom = 120f))
    }

    @Test fun touchesOutsideTrackClampToHardwareLimits() {
        assertEquals(100, playerVolumePercentAt(positionY = -30f, trackTop = 20f, trackBottom = 120f))
        assertEquals(0, playerVolumePercentAt(positionY = 160f, trackTop = 20f, trackBottom = 120f))
        assertEquals(0, playerVolumePercentAt(positionY = 70f, trackTop = 80f, trackBottom = 20f))
    }
}
