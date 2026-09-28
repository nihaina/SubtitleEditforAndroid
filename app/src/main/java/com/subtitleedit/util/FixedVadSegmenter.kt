package com.subtitleedit.util

import kotlin.math.abs

/** Places contiguous fixed-length cuts at nearby VAD silence or low speech probability. */
internal object FixedVadSegmenter {
    data class Range(val startSample: Long, val endSample: Long)

    /**
     * Use the VAD model's relative speech probability in each permitted cut range.
     * A short moving average avoids choosing one anomalous low-probability frame.
     */
    fun splitByLowestVadProbability(
        totalSamples: Long,
        periodSamples: Long,
        probabilities: FloatArray,
        frameSamples: Int,
        maxSegmentSamples: Long = periodSamples + periodSamples / 2L,
        checkCancelled: () -> Unit = {},
    ): List<Range> {
        require(periodSamples > 0L && maxSegmentSamples >= periodSamples)
        require(frameSamples > 0)
        require(probabilities.all { it.isFinite() })
        if (totalSamples <= 0L) return emptyList()
        require(probabilities.size.toLong() * frameSamples >= totalSamples - frameSamples)

        val output = mutableListOf<Range>()
        var start = 0L
        while (totalSamples - start > periodSamples) {
            checkCancelled()
            val target = start + periodSamples
            val lower = maxOf(start + 1L, target - periodSamples / 2L)
            val upper = minOf(totalSamples - 1L, target + periodSamples / 2L,
                start + maxSegmentSamples)
            val cut = lowestVadProbabilityCut(
                lower, upper, target, probabilities, frameSamples
            )
            output += Range(start, cut)
            start = cut
        }
        output += Range(start, totalSamples)
        return output
    }

    private fun lowestVadProbabilityCut(
        lower: Long,
        upper: Long,
        target: Long,
        probabilities: FloatArray,
        frameSamples: Int,
    ): Long {
        val firstFrame = ((lower + frameSamples - 1) / frameSamples).toInt()
        val lastFrame = minOf((upper / frameSamples).toInt(), probabilities.lastIndex)
        if (firstFrame > lastFrame) return target.coerceIn(lower, upper)

        val windowFrames = minOf(3, lastFrame - firstFrame + 1)
        var score = 0.0
        for (frame in firstFrame until firstFrame + windowFrames) {
            score += probabilities[frame]
        }
        var lowestScore = Double.POSITIVE_INFINITY
        var bestCut = target.coerceIn(lower, upper)
        for (first in firstFrame..lastFrame - windowFrames + 1) {
            val cut = (first.toLong() + windowFrames / 2L) * frameSamples
            val distance = abs(cut - target)
            val bestDistance = abs(bestCut - target)
            if (score < lowestScore - 1e-9 ||
                abs(score - lowestScore) <= 1e-9 && distance < bestDistance
            ) {
                lowestScore = score
                bestCut = cut
            }
            if (first + windowFrames <= lastFrame) {
                score += probabilities[first + windowFrames] - probabilities[first]
            }
        }
        return bestCut
    }

    fun silencesFromSpeech(totalSamples: Long, speech: List<Range>): List<Range> {
        if (totalSamples <= 0L || speech.isEmpty()) return emptyList()
        val silences = mutableListOf<Range>()
        var coveredUntil = 0L
        speech.sortedBy { it.startSample }.forEach { range ->
            val start = range.startSample.coerceIn(0L, totalSamples)
            val end = range.endSample.coerceIn(start, totalSamples)
            if (start > coveredUntil) silences += Range(coveredUntil, start)
            coveredUntil = maxOf(coveredUntil, end)
        }
        if (coveredUntil < totalSamples) silences += Range(coveredUntil, totalSamples)
        return silences
    }

    fun split(
        totalSamples: Long,
        periodSamples: Long,
        silences: List<Range>,
        maxSegmentSamples: Long = periodSamples + periodSamples / 2L,
    ): List<Range> {
        require(periodSamples > 0L && maxSegmentSamples >= periodSamples)
        if (totalSamples <= 0L) return emptyList()

        val sortedSilences = silences.sortedBy { it.startSample }
        val output = mutableListOf<Range>()
        var start = 0L
        var firstUnusedSilence = 0
        while (totalSamples - start > periodSamples) {
            val target = start + periodSamples
            val lower = maxOf(start + 1L, target - periodSamples / 2L)
            val upper = minOf(totalSamples - 1L, target + periodSamples / 2L,
                start + maxSegmentSamples)
            while (firstUnusedSilence < sortedSilences.size &&
                sortedSilences[firstUnusedSilence].endSample <= lower
            ) firstUnusedSilence++
            var bestCut = target
            var bestIndex = -1
            var bestDistance = Long.MAX_VALUE

            for (index in firstUnusedSilence until sortedSilences.size) {
                val silence = sortedSilences[index]
                if (silence.startSample > upper) break
                val availableStart = maxOf(silence.startSample, lower)
                val availableEnd = minOf(silence.endSample, upper)
                if (availableStart >= availableEnd) continue
                val candidate = target.coerceIn(availableStart, availableEnd - 1L)
                val distance = abs(candidate - target)
                if (distance < bestDistance || distance == bestDistance && candidate < bestCut) {
                    bestCut = candidate
                    bestIndex = index
                    bestDistance = distance
                }
            }

            val cut = if (bestIndex >= 0) bestCut else target
            output += Range(start, cut)
            start = cut
            if (bestIndex >= 0) firstUnusedSilence = bestIndex + 1
        }
        output += Range(start, totalSamples)
        return output
    }
}
