package com.subtitleedit.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3FileIssuesTest {
    @Test
    fun matchingDataRatesDoNotWarn() {
        // Ten seconds of audio at 320 kbps is 400,000 bytes.
        assertFalse(Mp3FileIssues.from(0.0, 400_000L, 10.0, 320_000.0).hasIssues)
    }

    @Test
    fun anyDataRateAboveNominalWarnsWithoutTolerance() {
        for (audioBytes in listOf(400_001L, 410_000L)) {
            val issues = Mp3FileIssues.from(0.0, audioBytes, 10.0, 320_000.0)
            assertTrue(issues.dataRateAboveNominalBitrate)
            assertFalse(issues.dataRateBelowNominalBitrate)
            assertTrue(issues.hasIssues)
        }
    }

    @Test
    fun anyDataRateBelowNominalWarnsWithoutTolerance() {
        // Even one byte less than the nominal size must trigger the warning.
        assertTrue(Mp3FileIssues.from(0.0, 399_999L, 10.0, 320_000.0).dataRateBelowNominalBitrate)
        assertTrue(Mp3FileIssues.from(0.0, 398_000L, 10.0, 320_000.0).dataRateBelowNominalBitrate)
        // 128 kbps, 44.1 kHz MP3 frames can use 417 bytes before padding is added.
        val unpaddedFrames = Mp3FileIssues.from(0.0, 417L * 100, 1152.0 * 100 / 44100, 128_000.0)
        assertTrue(unpaddedFrames.dataRateBelowNominalBitrate)
    }

    @Test
    fun lowerMeasuredRateWarnsWithoutAStartTimeOffset() {
        val issues = Mp3FileIssues.from(0.0, 397_500L, 10.0, 320_000.0)
        assertTrue(issues.dataRateBelowNominalBitrate)
        assertFalse(issues.dataRateAboveNominalBitrate)
        assertTrue(issues.hasIssues)
    }

    @Test
    fun missingOrInvalidMeasurementsAreNotFileIssues() {
        assertFalse(Mp3FileIssues.from(null, null, null, null).hasIssues)
        for (size in listOf(null, 0L, -1L)) {
            assertFalse(Mp3FileIssues.from(0.0, size, 10.0, 320_000.0).hasIssues)
        }
        for (duration in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(Mp3FileIssues.from(0.0, 100_000L, duration, 320_000.0).hasIssues)
        }
        for (bitrate in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(Mp3FileIssues.from(0.0, 100_000L, 10.0, bitrate).hasIssues)
        }
    }

    @Test
    fun startTimeIssueCanOccurIndependentlyOrTogetherWithLowDataRate() {
        assertEquals(0.025, Mp3FileIssues.from(0.025, null, null, null).nonZeroStartTimeSeconds)
        assertEquals(-0.01, Mp3FileIssues.from(-0.01, null, null, null).nonZeroStartTimeSeconds)
        assertFalse(Mp3FileIssues.from(Double.NaN, null, null, null).hasIssues)
        val both = Mp3FileIssues.from(0.0001, 397_500L, 10.0, 320_000.0)
        assertEquals(0.0001, both.nonZeroStartTimeSeconds)
        assertTrue(both.dataRateBelowNominalBitrate)
    }

    @Test
    fun startTimeIssueCanOccurTogetherWithHighDataRate() {
        val issues = Mp3FileIssues.from(0.025, 400_001L, 10.0, 320_000.0)
        assertEquals(0.025, issues.nonZeroStartTimeSeconds)
        assertTrue(issues.dataRateAboveNominalBitrate)
        assertFalse(issues.dataRateBelowNominalBitrate)
    }
}
