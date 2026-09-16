package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.WhisperRecognizer.SubtitleSegment

/** Maps AI whitespace boundaries back onto the original subtitle time ranges. */
object SemanticSubtitleMerger {
    private const val NEW_ENTRIES_PER_BATCH = 300
    private const val OVERLAP_ENTRIES = 2

    /** Each request adds up to 300 entries, with the previous two included as context. */
    suspend fun mergeSubtitleEntriesInBatches(
        entries: List<SubtitleEntry>,
        requestMerge: suspend (String) -> String
    ): List<SubtitleEntry> {
        if (entries.isEmpty()) return emptyList()
        val boundaries = (0..entries.size).toMutableSet()
        var processed = 0
        while (processed < entries.size) {
            val start = (processed - OVERLAP_ENTRIES).coerceAtLeast(0)
            val end = (processed + NEW_ENTRIES_PER_BATCH).coerceAtMost(entries.size)
            val batch = entries.subList(start, end)
            val aiText = requestMerge(batch.joinToString("  ") { it.text })
            val batchBoundaries = collectEntryBoundaries(batch, aiText)
            // Only internal boundaries were judged by AI. Keep earlier decisions at
            // the request edges, and let this response replace overlapping decisions.
            for (localBoundary in 1 until batch.size) {
                val boundary = start + localBoundary
                if (batchBoundaries == null || localBoundary in batchBoundaries) {
                    boundaries.add(boundary)
                } else {
                    boundaries.remove(boundary)
                }
            }
            processed = end
        }
        return mergeEntriesAtBoundaries(entries, boundaries)
    }

    fun mergeSubtitleEntriesByAiBoundaries(
        entries: List<SubtitleEntry>,
        aiText: String
    ): List<SubtitleEntry> {
        if (entries.isEmpty()) return emptyList()
        val boundaries = collectEntryBoundaries(entries, aiText) ?: return entries
        return mergeEntriesAtBoundaries(entries, boundaries)
    }

    private fun collectEntryBoundaries(
        entries: List<SubtitleEntry>,
        aiText: String
    ): Set<Int>? {
        val sourceText = entries.joinToString(separator = "") { it.text.filterNot(Char::isWhitespace) }
        if (sourceText != aiText.filterNot(Char::isWhitespace)) return null

        val sourceBoundaries = mutableMapOf<Int, Int>()
        var sourceLength = 0
        entries.forEachIndexed { index, entry ->
            sourceLength += entry.text.count { !it.isWhitespace() }
            sourceBoundaries[sourceLength] = index + 1
        }
        return collectBoundaries(aiText, sourceBoundaries, sourceLength)
    }

    private fun mergeEntriesAtBoundaries(
        entries: List<SubtitleEntry>,
        boundaries: Set<Int>
    ): List<SubtitleEntry> {
        return boundaries.sorted().zipWithNext().mapNotNull { (start, end) ->
            if (start >= end) return@mapNotNull null
            val group = entries.subList(start, end)
            group.first().copy(
                endTime = group.last().endTime,
                text = group.joinToString(separator = "") { it.text }
            )
        }.ifEmpty { entries }
    }

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

        val boundaries = collectBoundaries(aiText, sourceBoundaries, sourceLength)
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

    private fun collectBoundaries(
        aiText: String,
        sourceBoundaries: Map<Int, Int>,
        sourceLength: Int
    ): Set<Int> {
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
        sourceBoundaries[nonWhitespaceCount.coerceAtMost(sourceLength)]?.let(boundaries::add)
        return boundaries
    }
}
