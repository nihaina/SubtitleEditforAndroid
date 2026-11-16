package com.subtitleedit.player

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class PlayerSheetMotionTest {
    @Test fun dragTracksFingerAndClampsAtBothEndpoints() {
        assertEquals(0.75f, PlayerSheetMotion.progressAfterDrag(1f, 200f, 800f), 0.0001f)
        assertEquals(0.25f, PlayerSheetMotion.progressAfterDrag(0f, -200f, 800f), 0.0001f)
        assertEquals(0f, PlayerSheetMotion.progressAfterDrag(0.1f, 400f, 800f), 0f)
        assertEquals(1f, PlayerSheetMotion.progressAfterDrag(0.9f, -400f, 800f), 0f)
    }

    @Test fun aFlingCanCompleteAnOtherwiseShortDrag() {
        assertTrue(PlayerSheetMotion.settleExpanded(0.1f, -900f, 700f))
        assertFalse(PlayerSheetMotion.settleExpanded(0.9f, 900f, 700f))
        assertTrue(PlayerSheetMotion.settleExpanded(0.6f, 0f, 700f))
        assertFalse(PlayerSheetMotion.settleExpanded(0.4f, 0f, 700f))
    }

    @Test fun mediaArrivesAtMeasuredMiniBarAndReturnsToViewport() {
        val mini = Rect(12f, 700f, 84f, 748f)
        val full = Rect(0f, 0f, 400f, 800f)
        assertEquals(mini, PlayerSheetMotion.mediaBounds(mini, full, 0f))
        assertEquals(full, PlayerSheetMotion.mediaBounds(mini, full, 1f))
        val middle = PlayerSheetMotion.mediaBounds(mini, full, 0.5f)
        assertEquals(Rect(6f, 350f, 242f, 774f), middle)
        assertTrue(middle.width > mini.width && middle.width < full.width)
    }
}
