package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenLogMelExtractorTest {
    @Test
    fun usesOfficialMelShapeAndFrameCount() {
        val features = QwenLogMelExtractor().extract(FloatArray(16_000))

        assertEquals(128, features.size)
        assertEquals(100, features.first().size)
        assertTrue(features.all { row -> row.all { value -> value.isFinite() } })
    }

    @Test
    fun emptyAudioProducesFiniteExperimentalFrame() {
        val features = QwenLogMelExtractor().extract(FloatArray(0))

        assertEquals(128, features.size)
        assertFalse(features.isEmpty())
        assertTrue(features.all { row -> row.all { value -> value.isFinite() } })
    }
}
