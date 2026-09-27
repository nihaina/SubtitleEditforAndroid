package com.subtitleedit.util

import kotlin.math.abs
import kotlin.math.roundToInt

/** Splits a transcript in order, using voiced duration and nearby text boundaries. */
internal object TranscriptWindowAllocator {
    fun assign(source: String, voicedSamples: List<Long>): List<String> {
        require(voicedSamples.isNotEmpty() && voicedSamples.all { it > 0L }) { "未检测到有效语音段" }
        val positions = source.indices.filter { source[it].isLetterOrDigit() || source[it] == '\'' }
        require(positions.size >= voicedSamples.size) { "文稿过短，无法分配到全部语音段" }
        val totalVoiced = voicedSamples.sum()
        require(totalVoiced > 0L) { "未检测到有效语音段" }
        val output = mutableListOf<String>()
        var usedVoiced = 0L
        var previousCharacter = 0
        var previousSource = 0
        voicedSamples.forEachIndexed { index, voiced ->
            usedVoiced += voiced
            val cutCharacter = if (index == voicedSamples.lastIndex) positions.size else {
                val desired = (positions.size * usedVoiced.toDouble() / totalVoiced).roundToInt()
                val minimum = previousCharacter + 1
                val maximum = positions.size - (voicedSamples.size - index - 1)
                val center = desired.coerceIn(minimum, maximum)
                val radius = maxOf(3, (center - previousCharacter) / 3)
                val lower = maxOf(minimum, center - radius)
                val upper = minOf(maximum, center + radius)
                (lower..upper).minBy { candidate ->
                    abs(candidate - center) * 10 - boundaryBonus(source, positions, candidate)
                }
            }
            val sourceEnd = if (index == voicedSamples.lastIndex) source.length else positions[cutCharacter]
            val part = source.substring(previousSource, sourceEnd).trim()
            require(part.isNotEmpty()) { "文稿无法分配到语音段" }
            output += part
            previousCharacter = cutCharacter
            previousSource = sourceEnd
        }
        return output
    }

    private fun boundaryBonus(source: String, positions: List<Int>, cut: Int): Int {
        val between = source.substring(positions[cut - 1] + 1, positions[cut])
        return when {
            between.any { it in "。！？!?；;.!?" || it == '\n' } -> 30
            between.any(Char::isWhitespace) -> 12
            else -> 0
        }
    }
}
