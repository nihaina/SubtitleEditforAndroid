package com.subtitleedit.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerUsbHardwareVolumeTest {
    @Test fun displayFollowsTheDacFromPreviousLevelToQuantizedLevel() {
        val volume = PlayerUsbHardwareVolume()
        volume.readBack(28, nowMs = 0L)
        assertEquals(33, volume.request(nowMs = 1L) { it + 5 })
        assertEquals(28, volume.confirmedPercent)

        volume.readBack(28, nowMs = 250L)
        assertEquals(28, volume.confirmedPercent)
        volume.readBack(32, nowMs = 500L)
        assertEquals(32, volume.confirmedPercent)
    }

    @Test fun rapidKeysAccumulateBeforeTheDacHasAppliedTheLatestWrite() {
        val volume = PlayerUsbHardwareVolume()
        volume.readBack(28, nowMs = 0L)
        assertEquals(33, volume.request(nowMs = 1L) { it + 5 })
        volume.readBack(32, nowMs = 250L)
        assertEquals(38, volume.request(nowMs = 251L) { it + 5 })
        assertEquals(32, volume.confirmedPercent)
        assertEquals(38, volume.readBack(38, nowMs = 500L))
        volume.readBack(37, nowMs = 750L)
        assertEquals(37, volume.confirmedPercent)
        assertEquals(42, volume.request(nowMs = 751L) { it + 5 })
    }

    @Test fun expiredRequestsStopAffectingKeyStepsAndCanBeRetried() {
        val volume = PlayerUsbHardwareVolume()
        volume.readBack(28, nowMs = 0L)
        assertEquals(33, volume.request(nowMs = 1L) { it + 5 })
        assertEquals(33, volume.readBack(28, nowMs = 1_501L))
        assertEquals(33, volume.request(nowMs = 1_502L) { it + 5 })
        assertEquals(33, volume.request(nowMs = 3_002L) { it + 5 })
    }

    @Test fun cachedReadbackDoesNotAcknowledgeAReversalBeforeItReachesTheDac() {
        val volume = PlayerUsbHardwareVolume()
        volume.readBack(28, nowMs = 0L)
        assertEquals(33, volume.request(nowMs = 1L) { it + 5 })
        assertEquals(28, volume.request(nowMs = 2L) { it - 5 })
        assertNull(volume.readBack(28, nowMs = 250L))
        assertNull(volume.readBack(32, nowMs = 500L))
        assertEquals(32, volume.confirmedPercent)
        assertEquals(33, volume.request(nowMs = 501L) { it + 5 })
    }

    @Test fun newTrackClearsPendingKeyStepsAndDisconnectClearsTheReadback() {
        val volume = PlayerUsbHardwareVolume()
        volume.readBack(28, nowMs = 0L)
        assertEquals(33, volume.request(nowMs = 1L) { it + 5 })
        volume.clearRequest()
        assertEquals(33, volume.request(nowMs = 2L) { it + 5 })
        volume.reset()
        assertNull(volume.confirmedPercent)
        assertNull(volume.request(nowMs = 3L) { it + 5 })
    }
}
