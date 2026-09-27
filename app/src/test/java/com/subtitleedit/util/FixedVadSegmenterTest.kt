package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FixedVadSegmenterTest {
    private fun range(start: Long, end: Long) = FixedVadSegmenter.Range(start, end)

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
