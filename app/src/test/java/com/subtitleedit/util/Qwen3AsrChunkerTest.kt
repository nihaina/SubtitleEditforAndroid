package com.subtitleedit.util

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class Qwen3AsrChunkerTest {
    @Test
    fun reservesGenerationAndUsesActualModelCacheLength() {
        for (cache in listOf(256, 512, 1024, 4096)) {
            val budget = Qwen3AsrBudget.fromCacheLength(cache)
            assertEquals(cache, budget.totalTokens)
            assertTrue(budget.outputTokens >= 64)
            assertTrue(budget.accepts(budget.maxSamples))
            assertFalse(budget.accepts(budget.maxSamples + Qwen3AsrBudget.FRAME_SHIFT))
            assertEquals(budget.totalTokens, budget.audioTokens + budget.outputTokens +
                budget.promptTokens + Qwen3AsrBudget.SAFETY_TOKENS)
        }
        val budget = Qwen3AsrBudget.fromCacheLength(512)
        assertEquals(256, budget.outputTokens)
        assertEquals(216, budget.audioTokens)
        assertEquals(266240, budget.maxSamples)
        val largerBudget = Qwen3AsrBudget.fromCacheLength(4096)
        assertTrue(largerBudget.maxSamples > budget.maxSamples * 10)
    }

    @Test
    fun audioTokensMatchOfficialFrameBoundaryFixtures() {
        mapOf(0 to 0, 1 to 1, 8 to 1, 9 to 2, 99 to 13, 100 to 13,
            101 to 14, 199 to 26, 200 to 26, 3000 to 390).forEach { (frames, tokens) ->
            assertEquals(tokens, Qwen3AsrBudget.audioTokensForFrames(frames))
        }
    }

    @Test
    fun rejectsInsufficientCacheAndInvalidRequestedLengths() {
        for (cache in listOf(-1, 0, 64, 127)) {
            assertThrows(IllegalArgumentException::class.java) { Qwen3AsrBudget.fromCacheLength(cache) }
        }
        assertEquals(512, Qwen3AsrBudget.fromCacheLength(null).totalTokens)
        assertThrows(IllegalArgumentException::class.java) {
            Qwen3AsrChunker(Qwen3AsrBudget.fromCacheLength(512), 0)
        }
    }

    @Test
    fun cutsInsideQuietWindowWithoutChoosingSilenceBeyondBudget() {
        val chunker = Qwen3AsrChunker(Qwen3AsrBudget.fromCacheLength(512), 120)
        val audio = FloatArray(40 * 16000) { 0.4f }
        audio.fill(0.01f, 14 * 16000, 14 * 16000 + 3200)
        audio.fill(0f, 18 * 16000, 19 * 16000)
        val chunks = chunker.split(audio)
        assertTrue(chunks.first().endSample in 14 * 16000..14 * 16000 + 3200)
        checkCoverage(audio, chunks, chunker.maxSamples)
    }

    @Test
    fun checksWindowEnergyInsteadOfAnIsolatedZeroCrossing() {
        val chunker = Qwen3AsrChunker(Qwen3AsrBudget.fromCacheLength(512), 120)
        val audio = FloatArray(30 * 16000) { 0.9f }
        audio[12 * 16000] = 0f
        audio.fill(0.1f, 14 * 16000, 14 * 16000 + 3200)
        assertTrue(chunker.split(audio).first().endSample in 14 * 16000..14 * 16000 + 3200)
    }

    @Test
    fun silenceUsesLatestBoundaryAndShortTailKeepsItsOriginalLength() {
        val chunker = Qwen3AsrChunker(Qwen3AsrBudget.fromCacheLength(512), 120)
        val audio = FloatArray(chunker.maxSamples * 2 + 7)
        val chunks = chunker.split(audio)
        assertEquals(listOf(chunker.maxSamples, chunker.maxSamples, 7), chunks.map { it.sampleCount })
        checkCoverage(audio, chunks, chunker.maxSamples)
        assertTrue(chunker.split(FloatArray(0)).isEmpty())
        assertEquals(1, chunker.split(FloatArray(1)).single().sampleCount)
    }

    @Test
    fun neverDropsOrDuplicatesSamplesForDifferentUserLengthsAndModelBudgets() {
        val random = Random(42)
        val audio = FloatArray(123 * 16000 + 113) { random.nextFloat() * 2 - 1 }
        for (cache in listOf(128, 256, 512, 1024, 4096)) {
            for (requested in listOf(5, 15, 30, 60, 120)) {
                val chunker = Qwen3AsrChunker(Qwen3AsrBudget.fromCacheLength(cache), requested)
                val chunks = chunker.split(audio)
                checkCoverage(audio, chunks, chunker.maxSamples)
                assertTrue(chunks.all { chunker.budget.accepts(it.sampleCount) })
            }
        }
    }

    @Test
    fun boundedWavReadsChooseTheSameCutsAsInMemoryVadWindows() {
        val chunker = Qwen3AsrChunker(Qwen3AsrBudget.fromCacheLength(512), 30)
        val audio = FloatArray(75 * 16000) { if (it % 160000 < 8000) 0f else 0.3f }
        val expected = chunker.split(audio)
        val actual = mutableListOf<Qwen3AsrChunker.Chunk>()
        var cursor = 0
        while (cursor < audio.size) {
            val end = minOf(cursor + chunker.maxSamples, audio.size)
            val cut = chunker.nextEnd(audio.copyOfRange(cursor, end), hasMoreAudio = end < audio.size)
            actual += Qwen3AsrChunker.Chunk(cursor, cursor + cut)
            cursor += cut
        }
        assertEquals(expected, actual)
    }

    @Test
    fun preservesAbsoluteMillisecondsAcrossStreamingCutsAndVadSubchunks() {
        // These cuts land between milliseconds: converting start and duration separately
        // previously shortened the first range to 25057, leaving a visible 1 ms gap.
        val startSample = 199_149L
        val endSample = 400_935L
        val duration = (endSample - startSample).toInt()
        val single = Qwen3AsrChunker.Chunk(0, duration)
        val range = single.timeRangeMs(startSample * 1000 / 16000, endSample * 1000 / 16000, duration)
        assertEquals(12446L..25058L, range)

        val chunks = Qwen3AsrChunker(Qwen3AsrBudget.fromCacheLength(512), 5).split(FloatArray(duration))
        val ranges = chunks.map { it.timeRangeMs(range.first, range.last, duration) }
        assertEquals(range.first, ranges.first().first)
        assertEquals(range.last, ranges.last().last)
        ranges.zipWithNext().forEach { (left, right) -> assertEquals(left.last, right.first) }
    }

    private fun checkCoverage(audio: FloatArray, chunks: List<Qwen3AsrChunker.Chunk>, maxSamples: Int) {
        var cursor = 0
        chunks.forEach {
            assertEquals(cursor, it.startSample)
            assertTrue(it.sampleCount in 1..maxSamples)
            cursor = it.endSample
        }
        assertEquals(audio.size, cursor)
        assertEquals(audio.size, chunks.sumOf { it.sampleCount })
    }
}
