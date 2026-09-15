package com.subtitleedit.util

import com.subtitleedit.util.WhisperRecognizer.SubtitleSegment

/** Maps AI whitespace boundaries back onto the original subtitle time ranges. */
object SemanticSubtitleMerger {
    fun mergeByAiBoundaries(
        segments: List<SubtitleSegment>,
        aiText: String
    ): List<SubtitleSegment> {
        if (segments.isEmpty()) return emptyList()
        val sourceText = segments.joinToString(separator = "") { it.text.filterNot(Char::isWhitespace) }
        val normalizedAiText = aiText.filterNot(Char::isWhitespace)
        if (sourceText != normalizedAiText) return segments

        val sourceBoundaries = mutableMapOf<Int, Int>()
        var sourceLength = 0
        segments.forEachIndexed { index, segment ->
            sourceLength += segment.text.count { !it.isWhitespace() }
            sourceBoundaries[sourceLength] = index + 1
        }

        val boundaries = linkedSetOf(0)
        var nonWhitespaceCount = 0
        var whitespaceRun = false
        aiText.forEach { char ->
            if (char.isWhitespace()) {
                whitespaceRun = true
            } else {
                if (whitespaceRun) {
                    sourceBoundaries[nonWhitespaceCount]?.let(boundaries::add)
                }
                whitespaceRun = false
                nonWhitespaceCount++
            }
        }
        sourceBoundaries[nonWhitespaceCount]?.let(boundaries::add)

        return boundaries.sorted().zipWithNext().mapNotNull { (start, end) ->
            if (start >= end) return@mapNotNull null
            val group = segments.subList(start, end)
            SubtitleSegment(
                startTime = group.first().startTime,
                endTime = group.last().endTime,
                text = group.joinToString(separator = "") { it.text }
            )
        }.ifEmpty { segments }
    }
}
