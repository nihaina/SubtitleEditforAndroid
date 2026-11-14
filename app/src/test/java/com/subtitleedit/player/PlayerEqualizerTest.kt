package com.subtitleedit.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerEqualizerTest {
    @Test fun disabledOrFlatStateProducesNoFilter() {
        assertEquals("", PlayerEqualizer.afFilter(PlayerEqualizerState(enabled = true, gains = PlayerEqualizer.FLAT)))
        assertEquals("", PlayerEqualizer.afFilter(PlayerEqualizerState(enabled = false, gains = listOf(6f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))))
    }

    @Test fun enabledCurvePinsBothEndsAndCarriesEveryBand() {
        val af = PlayerEqualizer.afFilter(
            PlayerEqualizerState(enabled = true, gains = listOf(3f, 0f, -2.5f, 0f, 0f, 0f, 0f, 0f, 0f, 4f))
        )
        assertTrue(af.startsWith("lavfi=[firequalizer=gain_entry='entry(0,3);"))
        assertTrue(af.endsWith(";entry(20000,4)']"))
        assertEquals(
            PlayerEqualizer.BAND_COUNT + 2,
            af.removePrefix("lavfi=[firequalizer=gain_entry='").removeSuffix("']").split(";").size
        )
        assertTrue(af.contains("entry(125,-2.5)"))
        // Both mpv's filter-list parser and FFmpeg's graph parser need their own quoting.
        assertEquals(3, af.split("'").size)
        assertFalse(af.substringBefore("'").contains(","))
    }

    @Test fun gainsAreClampedAndShortListsArePadded() {
        val normalized = PlayerEqualizer.normalize(listOf(30f, -40f))
        assertEquals(PlayerEqualizer.BAND_COUNT, normalized.size)
        assertEquals(PlayerEqualizer.MAX_GAIN, normalized[0])
        assertEquals(PlayerEqualizer.MIN_GAIN, normalized[1])
        assertEquals(0f, normalized[9])
    }

    @Test fun corruptNonFiniteGainsNeverReachTheAudioFilter() {
        val normalized = PlayerEqualizer.normalize(listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
        assertTrue(normalized.all { it == 0f })
        assertEquals("", PlayerEqualizer.afFilter(PlayerEqualizerState(enabled = true, gains = normalized)))
    }

    @Test fun labelsFollowTheBandTable() {
        assertEquals("31", PlayerEqualizer.formatFrequency(31.5))
        assertEquals("1k", PlayerEqualizer.formatFrequency(1000.0))
        assertEquals("16k", PlayerEqualizer.formatFrequency(16000.0))
        assertEquals("+3dB", PlayerEqualizer.formatGain(3f))
        assertEquals("-2.5dB", PlayerEqualizer.formatGain(-2.5f))
        assertEquals("0dB", PlayerEqualizer.formatGain(0f))
    }

    @Test fun presetsStayInsideTheSliderRange() {
        PlayerEqualizer.PRESETS.forEach { preset ->
            assertEquals(PlayerEqualizer.BAND_COUNT, preset.gains.size)
            assertTrue(preset.gains.all { it in PlayerEqualizer.MIN_GAIN..PlayerEqualizer.MAX_GAIN })
        }
    }
}
