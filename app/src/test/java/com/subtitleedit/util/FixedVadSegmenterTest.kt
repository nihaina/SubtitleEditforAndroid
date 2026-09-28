package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedVadSegmenterTest {
    private fun range(start: Long, end: Long) = FixedVadSegmenter.Range(start, end)

    @Test
    fun choosesLowestVadSpeechProbabilityInEachSearchWindow() {
        val probabilities = FloatArray(320) { 0.8f }
        probabilities[100] = 0f // A single anomalous frame is not a quiet interval.
        probabilities.fill(0.15f, 120, 123)
        probabilities.fill(0.25f, 240, 243)

        val ranges = FixedVadSegmenter.splitByLowestVadProbability(
            totalSamples = 32_000,
            periodSamples = 10_000,
            probabilities = probabilities,
            frameSamples = 100,
        )

        assertEquals(3, ranges.size)
        assertTrue(ranges[0].endSample in 12_000L..12_300L)
        assertTrue(ranges[1].endSample in 24_000L..24_300L)
        assertEquals(0L, ranges.first().startSample)
        assertEquals(32_000L, ranges.last().endSample)
        ranges.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endSample, right.startSample)
        }
    }

    @Test
    fun vadCutStaysWithinModelMaximum() {
        val probabilities = FloatArray(230) { 0.7f }
        probabilities.fill(0.2f, 80, 83)
        probabilities.fill(0f, 120, 123) // Beyond the first segment's hard maximum.

        val ranges = FixedVadSegmenter.splitByLowestVadProbability(
            totalSamples = 23_000,
            periodSamples = 10_000,
            probabilities = probabilities,
            frameSamples = 100,
            maxSegmentSamples = 10_000,
        )

        assertTrue(ranges.first().endSample in 8_000L..8_300L)
        assertTrue(ranges.all { it.endSample - it.startSample <= 10_000L })
    }

    @Test
    fun highVadProbabilitiesStillUseRelativeMinimumInsteadOfNominalCut() {
        val probabilities = FloatArray(220) { 0.95f }
        probabilities.fill(0.7f, 120, 123)
        val ranges = FixedVadSegmenter.splitByLowestVadProbability(
            totalSamples = 22_000,
            periodSamples = 10_000,
            probabilities = probabilities,
            frameSamples = 100,
        )
        assertTrue(ranges.first().endSample in 12_000L..12_300L)
    }

    @Test
    fun choosesNearbySilenceFromEachPreviousCutAndKeepsTail() {
        val cuts = FixedVadSegmenter.split(
            totalSamples = 35_000,
            periodSamples = 10_000,
            silences = listOf(range(9_000, 9_500), range(20_500, 21_500)),
        )
        assertEquals(
            listOf(range(0, 9_499), range(9_499, 20_500),
                range(20_500, 30_500), range(30_500, 35_000)),
            cuts,
        )
    }

    @Test
    fun neverReusesASilenceAndFallsBackToNominalCut() {
        assertEquals(
            listOf(range(0, 10_000), range(10_000, 20_000), range(20_000, 28_000)),
            FixedVadSegmenter.split(28_000, 10_000, listOf(range(9_000, 16_000))),
        )
    }

    @Test
    fun respectsModelMaximumAndNeverDropsShortRemainder() {
        assertEquals(
            listOf(range(0, 10_000), range(10_000, 20_000), range(20_000, 21_000)),
            FixedVadSegmenter.split(21_000, 10_000, listOf(range(10_500, 11_000)), 10_000),
        )
    }

    @Test
    fun derivesOnlyRealGapsFromOverlappingSpeech() {
        assertEquals(
            listOf(range(0, 1_000), range(5_000, 6_000), range(9_000, 10_000)),
            FixedVadSegmenter.silencesFromSpeech(
                10_000,
                listOf(range(1_000, 4_000), range(3_000, 5_000), range(6_000, 9_000)),
            ),
        )
    }
}
