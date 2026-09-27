package com.subtitleedit.util

import kotlin.math.abs

/** Moves nominal fixed-length cuts into nearby VAD silence without dropping audio. */
internal object FixedVadSegmenter {
    data class Range(val startSample: Long, val endSample: Long)

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
