package com.subtitleedit.player

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVolumeTest {
    private class SystemVolume(
        var value: SystemMediaVolume = SystemMediaVolume(index = 3, minIndex = 0, maxIndex = 15),
    ) : PlayerSystemVolume {
        val writes = mutableListOf<Int>()
        var writeCeiling: Int? = null

        override fun read(): SystemMediaVolume = value

        override fun setIndex(index: Int) {
            writes += index
            val accepted = minOf(index, writeCeiling ?: value.maxIndex)
                .coerceIn(value.minIndex, value.maxIndex)
            value = value.copy(index = accepted, muted = accepted == 0)
        }
    }

    @Test fun initialAndExternallyChangedVolumeUseTheActualMediaStreamLevel() {
        val system = SystemVolume()
        val controller = PlayerVolumeController(system)

        assertEquals(20, controller.snapshot.percent)
        assertEquals(100, controller.snapshot.softwarePercent)
        assertTrue("Reading the existing volume must not change the phone", system.writes.isEmpty())

        system.value = system.value.copy(index = 6)
        val refreshed = controller.refresh()
        assertEquals(40, refreshed.percent)
        assertEquals(6, refreshed.system.index)
        assertEquals(100, refreshed.softwarePercent)
        assertTrue(system.writes.isEmpty())
    }

    @Test fun ordinaryVolumeUsesSystemStepsAndDisplaysTheAcceptedStep() {
        val system = SystemVolume()
        val controller = PlayerVolumeController(system)

        val halfway = controller.setVolume(50)
        assertEquals(8, system.value.index)
        assertEquals("Fifteen system steps cannot represent an exact 50 percent", 53, halfway.percent)
        assertEquals(100, halfway.softwarePercent)

        val silent = controller.setVolume(0)
        assertEquals(0, system.value.index)
        assertEquals(0, silent.percent)
        assertTrue(silent.muted)
        assertEquals(100, silent.softwarePercent)

        val maximum = controller.setVolume(100)
        assertEquals(15, system.value.index)
        assertEquals(100, maximum.percent)
        assertFalse(maximum.muted)
        assertEquals(100, maximum.softwarePercent)
    }

    @Test fun devicesWithANonzeroMinimumUseTheirActualVolumeRange() {
        val system = SystemVolume(SystemMediaVolume(index = 2, minIndex = 2, maxIndex = 12))
        val controller = PlayerVolumeController(system)

        val selected = controller.setVolume(30)
        assertEquals(5, selected.system.index)
        assertEquals(30, selected.percent)
        assertEquals(100, selected.softwarePercent)
    }

    @Test fun softwareBoostStartsOnlyAfterThePhoneAcceptsMaximumVolume() {
        val system = SystemVolume()
        val controller = PlayerVolumeController(system)

        val boosted = controller.setVolume(170)
        assertEquals(listOf(15), system.writes)
        assertEquals(15, boosted.system.index)
        assertEquals(170, boosted.softwarePercent)
        assertEquals(170, boosted.percent)

        val afterOwnWrite = controller.refresh()
        assertEquals("A readback of our own system write must retain boost", 170, afterOwnWrite.softwarePercent)
        assertEquals(170, afterOwnWrite.percent)
    }

    @Test fun loweringBoostKeepsThePhoneAtMaximumUntilTheOrdinaryRangeIsReached() {
        val system = SystemVolume()
        val controller = PlayerVolumeController(system)
        controller.setVolume(200)

        val lowerBoost = controller.setVolume(150)
        assertEquals(15, system.value.index)
        assertEquals(150, lowerBoost.softwarePercent)
        assertEquals(150, lowerBoost.percent)

        val unity = controller.setVolume(100)
        assertEquals(15, system.value.index)
        assertEquals(100, unity.softwarePercent)
        assertEquals(100, unity.percent)

        val ordinary = controller.setVolume(40)
        assertEquals(6, system.value.index)
        assertEquals(100, ordinary.softwarePercent)
        assertEquals(40, ordinary.percent)
    }

    @Test fun aRejectedSystemIncreaseCannotBeBypassedWithSoftwareGain() {
        val system = SystemVolume().apply { writeCeiling = 9 }
        val controller = PlayerVolumeController(system)

        val limited = controller.setVolume(200)
        assertEquals(15, system.writes.single())
        assertEquals(9, limited.system.index)
        assertEquals(60, limited.percent)
        assertEquals(100, limited.softwarePercent)
        assertEquals(100, controller.refresh().softwarePercent)
    }

    @Test fun externalVolumeMuteAndRangeChangesClearResidualBoost() {
        val system = SystemVolume()
        val controller = PlayerVolumeController(system)
        controller.setVolume(200)

        system.value = system.value.copy(index = 9)
        assertEquals(60, controller.refresh().percent)
        assertEquals(100, controller.snapshot.softwarePercent)

        controller.setVolume(180)
        system.value = system.value.copy(muted = true)
        val muted = controller.refresh()
        assertEquals(0, muted.percent)
        assertTrue(muted.muted)
        assertEquals(100, muted.softwarePercent)

        controller.setVolume(180)
        system.value = SystemMediaVolume(index = 15, minIndex = 0, maxIndex = 30)
        val newRange = controller.refresh()
        assertEquals(50, newRange.percent)
        assertEquals(100, newRange.softwarePercent)

        controller.setVolume(160)
        val reset = controller.resetBoost()
        assertEquals(100, reset.percent)
        assertEquals(100, reset.softwarePercent)
        assertEquals(30, reset.system.index)
    }

    @Test fun requestsClampToMuteAndExactlyDoubleTheSoftwareAmplitude() {
        val system = SystemVolume()
        val controller = PlayerVolumeController(system)
        assertEquals(0, controller.setVolume(Int.MIN_VALUE).percent)
        val maximum = controller.setVolume(Int.MAX_VALUE)
        assertEquals(200, maximum.percent)
        assertEquals(200, maximum.softwarePercent)

        assertEquals(0.0, PlayerVolumePolicy.mpvVolume(Int.MIN_VALUE), 0.0)
        assertEquals(100.0, PlayerVolumePolicy.mpvVolume(100), 0.000001)
        assertEquals(125.992104989, PlayerVolumePolicy.mpvVolume(200), 0.000001)
        assertEquals(PlayerVolumePolicy.mpvVolume(200), PlayerVolumePolicy.mpvVolume(Int.MAX_VALUE), 0.0)
        assertEquals(2.0, (PlayerVolumePolicy.mpvVolume(200) / 100.0).pow(3), 0.000001)
        assertEquals(1.5, (PlayerVolumePolicy.mpvVolume(150) / 100.0).pow(3), 0.000001)
    }

    @Test fun aDegenerateMutedSystemRangeStaysSilentWithoutDivisionByZero() {
        val system = SystemVolume(SystemMediaVolume(index = 0, minIndex = 0, maxIndex = 0, muted = true))
        val controller = PlayerVolumeController(system)
        assertEquals(0, controller.snapshot.percent)
        assertEquals(100, controller.snapshot.softwarePercent)
        assertEquals(0, controller.setVolume(200).percent)
        assertEquals(100, controller.refresh().softwarePercent)
    }
}
