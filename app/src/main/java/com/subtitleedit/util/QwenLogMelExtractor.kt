package com.subtitleedit.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * Qwen3-ASR's official WhisperFeatureExtractor front end.
 *
 * The model config uses a 400 point FFT, 160 point hop and 128 Slaney mel filters. The
 * Transformers implementation centers frames with reflect padding, drops the final STFT frame,
 * clamps the log-mel range to 8 dB and scales it with `(log10 + 4) / 4`.
 */
internal class QwenLogMelExtractor(
    private val sampleRate: Int = 16_000,
    private val nMels: Int = 128,
    private val nFft: Int = 400,
    private val hopLength: Int = 160,
) {
    private val fftBins = nFft / 2 + 1
    private val window = FloatArray(nFft) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / nFft)).toFloat()
    }
    private val melFilters = createMelFilters()
    private val cosTable = Array(fftBins) { frequency ->
        FloatArray(nFft) { sample ->
            cos(2.0 * PI * frequency * sample / nFft).toFloat()
        }
    }
    private val sinTable = Array(fftBins) { frequency ->
        FloatArray(nFft) { sample ->
            sin(2.0 * PI * frequency * sample / nFft).toFloat()
        }
    }

    fun extract(samples: FloatArray): Array<FloatArray> {
        require(sampleRate == 16_000) { "Qwen3-ASR 仅支持 16kHz 音频" }
        require(nFft > 0 && hopLength > 0 && nMels > 0) { "Qwen log-mel 参数无效" }

        val frameCountBeforeTrim = 1 + samples.size / hopLength
        val frameCount = max(1, frameCountBeforeTrim - 1)
        val output = Array(nMels) { FloatArray(frameCount) }
        val logMel = Array(nMels) { FloatArray(frameCount) }
        var globalMaximum = Float.NEGATIVE_INFINITY

        for (frame in 0 until frameCount) {
            val start = frame * hopLength - nFft / 2
            val power = FloatArray(fftBins)
            for (frequency in 0 until fftBins) {
                var real = 0.0
                var imaginary = 0.0
                val cosValues = cosTable[frequency]
                val sinValues = sinTable[frequency]
                for (sampleIndex in 0 until nFft) {
                    val sourceIndex = start + sampleIndex
                    val sample = reflectedSample(samples, sourceIndex) * window[sampleIndex]
                    real += sample * cosValues[sampleIndex]
                    imaginary -= sample * sinValues[sampleIndex]
                }
                power[frequency] = (real * real + imaginary * imaginary).toFloat()
            }

            for (mel in 0 until nMels) {
                var energy = 0.0
                val filter = melFilters[mel]
                for (frequency in 0 until fftBins) {
                    energy += filter[frequency] * power[frequency]
                }
                val value = log10(max(energy, 1e-10).toDouble()).toFloat()
                logMel[mel][frame] = value
                if (value > globalMaximum) globalMaximum = value
            }
        }

        val floor = globalMaximum - 8f
        for (mel in 0 until nMels) {
            for (frame in 0 until frameCount) {
                output[mel][frame] = (max(logMel[mel][frame], floor) + 4f) / 4f
            }
        }
        return output
    }

    /** Reflect padding used by numpy/Transformers (`mode="reflect"`, edge values excluded). */
    private fun reflectedSample(samples: FloatArray, index: Int): Float {
        if (samples.isEmpty()) return 0f
        var source = index
        while (source < 0 || source >= samples.size) {
            source = if (source < 0) -source else 2 * samples.size - 2 - source
            if (samples.size == 1) return samples[0]
        }
        return samples[source]
    }

    private fun createMelFilters(): Array<FloatArray> {
        val melMinimum = hertzToMel(0.0)
        val melMaximum = hertzToMel(sampleRate / 2.0)
        val filterFrequencies = DoubleArray(nMels + 2) { index ->
            melToHertz(melMinimum + (melMaximum - melMinimum) * index / (nMels + 1))
        }
        val fftFrequencies = DoubleArray(fftBins) { it.toDouble() * (sampleRate / 2.0) / (fftBins - 1) }
        return Array(nMels) { mel ->
            val left = filterFrequencies[mel]
            val center = filterFrequencies[mel + 1]
            val right = filterFrequencies[mel + 2]
            val areaScale = 2.0 / (right - left)
            FloatArray(fftBins) { frequency ->
                val hz = fftFrequencies[frequency]
                val rising = if (center > left) (hz - left) / (center - left) else 0.0
                val falling = if (right > center) (right - hz) / (right - center) else 0.0
                (max(0.0, minOf(rising, falling)) * areaScale).toFloat()
            }
        }
    }

    private fun hertzToMel(hz: Double): Double {
        return if (hz < 1_000.0) {
            3.0 * hz / 200.0
        } else {
            15.0 + ln(hz / 1_000.0) * (27.0 / ln(6.4))
        }
    }

    private fun melToHertz(mel: Double): Double {
        return if (mel < 15.0) {
            200.0 * mel / 3.0
        } else {
            1_000.0 * exp((mel - 15.0) * (ln(6.4) / 27.0))
        }
    }
}
