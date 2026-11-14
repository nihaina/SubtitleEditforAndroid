package com.subtitleedit.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLoudnessTest {
    @Test fun silenceAndUnavailableMetadataAreZero() {
        listOf(null, "", "-inf", "-Infinity", "NaN", "Infinity", "bad", "-90").forEach { value ->
            assertEquals("Metadata $value", 0f, PlayerLoudness.level(value, 100), 0f)
        }
    }

    @Test fun measuredDecibelsMapToTheMeterRange() {
        assertEquals(0f, PlayerLoudness.level("-60", 100), 0f)
        assertEquals(0.5f, PlayerLoudness.level("-30", 100), 0.0001f)
        assertEquals(1f, PlayerLoudness.level("0", 100), 0f)
        assertEquals(1f, PlayerLoudness.level("12", 100), 0f)
        assertTrue(PlayerLoudness.level("-12", 100) > PlayerLoudness.level("-40", 100))
    }

    @Test fun localVolumeGainAndMuteAreReflectedInMeasuredLevel() {
        assertEquals(0f, PlayerLoudness.level("-10", 0), 0f)
        assertEquals(0f, PlayerLoudness.level("-10", -5), 0f)
        assertEquals(0.19897f, PlayerLoudness.level("-30", 50), 0.0001f)
        assertEquals(0.80103f, PlayerLoudness.level("-30", 200), 0.0001f)
        assertEquals(PlayerLoudness.level("-30", 200), PlayerLoudness.level("-30", 300), 0f)
    }

    @Test fun disablingAnalysisPreservesTheExactEqualizerChain() {
        val eq = PlayerEqualizer.afFilter(PlayerEqualizerState(true, PlayerEqualizer.PRESETS[1].gains))
        assertEquals(eq, PlayerLoudness.audioFilters(eq, false))
        assertEquals("", PlayerLoudness.audioFilters("", false))
    }

    @Test fun analysisHasAStableMetadataLabelAndFollowsTheEqualizer() {
        val analysis = PlayerLoudness.audioFilters("", true)
        assertTrue(analysis.startsWith("@${PlayerLoudness.FILTER_LABEL}:lavfi=[astats="))
        assertTrue(analysis.contains("metadata=1:reset=1"))
        assertTrue(analysis.contains("measure_overall=RMS_level"))
        assertFalse(analysis.contains("firequalizer"))
        val eq = PlayerEqualizer.afFilter(PlayerEqualizerState(true, PlayerEqualizer.PRESETS[1].gains))
        assertEquals("$eq,$analysis", PlayerLoudness.audioFilters(eq, true))
        assertEquals("af-metadata/player-loudness/lavfi.astats.Overall.RMS_level", PlayerLoudness.RMS_PROPERTY)
    }
}
