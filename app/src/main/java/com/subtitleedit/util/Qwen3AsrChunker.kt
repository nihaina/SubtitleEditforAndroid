package com.subtitleedit.util

import kotlin.math.abs
import kotlin.math.min

/** Audio and generated text share Qwen's decoder context. Keep both within the KV limit. */
internal class Qwen3AsrBudget private constructor(
    val totalTokens: Int,
    val outputTokens: Int,
    val promptTokens: Int,
) {
    val audioTokens: Int get() = totalTokens - outputTokens - promptTokens - SAFETY_TOKENS

    val maxSamples: Int by lazy {
        var low = 0
        var high = Int.MAX_VALUE / FRAME_SHIFT
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (audioTokensForFrames(mid) <= audioTokens) low = mid else high = mid - 1
        }
        low * FRAME_SHIFT
    }

    fun accepts(sampleCount: Int): Boolean = sampleCount >= 0 &&
        audioTokensForFrames(sampleCount / FRAME_SHIFT) <= audioTokens

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SHIFT = 160
        const val MIN_SAMPLES = SAMPLE_RATE / 2
        const val SAFETY_TOKENS = 8
        // sherpa's empty-system scaffold is 15 tokens; supported forced-language names
        // add a few tokens. Reserve 32 conservatively. Qwen hotwords are NOT enabled here.
        const val PROMPT_TOKENS = 32

        fun fromCacheLength(cacheLength: Int?): Qwen3AsrBudget {
            require(cacheLength == null || cacheLength > 0) { "Qwen KV cache 长度必须大于 0" }
            // A symbolic cache dimension is valid for sherpa's decoder; use its runtime
            // default rather than failing before recognition starts.
            val total = cacheLength ?: Qwen3AsrModelBudget.DEFAULT_DYNAMIC_CACHE_LENGTH
            require(total >= 128) { "Qwen decoder 上下文过小，无法预留音频及文本生成空间" }
            val output = min(256, maxOf(64, total / 2))
            return Qwen3AsrBudget(total, output, PROMPT_TOKENS)
        }

        // Official processor / sherpa FeatToAudioTokensLen, with centered 10 ms STFT frames.
        fun audioTokensForFrames(frames: Int): Int {
            require(frames >= 0)
            return frames / 100 * 13 + (frames % 100 + 7) / 8
        }
    }
}

/** Official low-energy-window idea, constrained to a hard token-budget boundary.
 * Uses an O(n) running sum of absolute amplitude, then a quiet sample in the best 100 ms
 * window. Search backwards up to 5 s; never look beyond the maximum allowed input.
 */
internal class Qwen3AsrChunker(val budget: Qwen3AsrBudget, requestedSeconds: Int) {
    val maxSamples: Int = min(
        budget.maxSamples.toLong(), requestedSeconds.toLong() * Qwen3AsrBudget.SAMPLE_RATE
    ).toInt().also { require(it >= Qwen3AsrBudget.MIN_SAMPLES) }

    data class Chunk(val startSample: Int, val endSample: Int) {
        val sampleCount: Int get() = endSample - startSample

        fun timeRangeMs(audioStartMs: Long, audioEndMs: Long, totalSamples: Int): LongRange {
            require(startSample >= 0 && endSample >= startSample && endSample <= totalSamples)
            val start = audioStartMs + startSample * 1000L / Qwen3AsrBudget.SAMPLE_RATE
            // Preserve the caller's absolute end. floor(start) + floor(duration) can differ
            // from floor(absolute end) by 1 ms at low-energy (non-millisecond) cuts.
            val end = if (endSample == totalSamples) audioEndMs else
                audioStartMs + endSample * 1000L / Qwen3AsrBudget.SAMPLE_RATE
            return start..end
        }
    }

    fun split(samples: FloatArray): List<Chunk> {
        val result = mutableListOf<Chunk>()
        var start = 0
        while (start < samples.size) {
            val end = nextEnd(samples, start)
            result += Chunk(start, end)
            start = end
        }
        return result
    }

    /** hasMoreAudio allows a bounded WAV read to use the same cutter as an in-memory segment. */
    fun nextEnd(samples: FloatArray, start: Int = 0, hasMoreAudio: Boolean = false): Int {
        require(start in 0..samples.size)
        if (samples.size - start <= maxSamples && !hasMoreAudio) return samples.size
        val hardEnd = min(samples.size.toLong(), start.toLong() + maxSamples).toInt()
        val window = Qwen3AsrBudget.SAMPLE_RATE / 10
        val left = maxOf(start + Qwen3AsrBudget.MIN_SAMPLES, hardEnd - 5 * Qwen3AsrBudget.SAMPLE_RATE)
        if (hardEnd - left < window) return hardEnd

        fun amplitude(index: Int): Double {
            val value = samples[index]
            require(value.isFinite()) { "Qwen 输入音频包含无效采样值" }
            return abs(value.toDouble())
        }
        var sum = 0.0
        for (i in left until left + window) sum += amplitude(i)
        var bestSum = sum
        var bestStart = left
        for (i in left + window until hardEnd) {
            sum += amplitude(i) - amplitude(i - window)
            if (sum <= bestSum) { // Equal-energy silence chooses the latest valid boundary.
                bestSum = sum
                bestStart = i - window + 1
            }
        }
        var quietest = bestStart
        for (i in bestStart + 1 until bestStart + window) {
            if (amplitude(i) <= amplitude(quietest)) quietest = i
        }
        return (quietest + 1).coerceIn(start + 1, hardEnd)
    }
}
