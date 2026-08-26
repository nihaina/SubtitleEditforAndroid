package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry

object SubtitleEntryOps {
    private const val DEFAULT_INSERT_DURATION_MS = 3_000L

    data class TimingRange(
        val startTime: Long,
        val endTime: Long
    )

    fun deepCopy(entry: SubtitleEntry): SubtitleEntry {
        return entry.copy()
    }

    fun deepCopy(entries: List<SubtitleEntry>): List<SubtitleEntry> {
        return entries.map { it.copy() }
    }

    fun createInsertedEntry(
        after: Boolean,
        reference: SubtitleEntry,
        previous: SubtitleEntry?,
        next: SubtitleEntry?
    ): SubtitleEntry = createInsertedEntries(
        after = after,
        reference = reference,
        previous = previous,
        next = next,
        texts = listOf("新字幕")
    ).first()

    fun createInsertedEntries(
        after: Boolean,
        reference: SubtitleEntry,
        previous: SubtitleEntry?,
        next: SubtitleEntry?,
        texts: List<String>
    ): List<SubtitleEntry> {
        if (texts.isEmpty()) return emptyList()

        val count = texts.size
        val defaultTotalDuration = DEFAULT_INSERT_DURATION_MS * count
        val groupStart: Long
        val groupEnd: Long

        if (after) {
            groupStart = reference.endTime
            val defaultEnd = groupStart + defaultTotalDuration
            groupEnd = next?.startTime
                ?.takeIf { it > groupStart && it < defaultEnd }
                ?: defaultEnd
        } else {
            groupEnd = reference.startTime
            val defaultStart = (groupEnd - defaultTotalDuration).coerceAtLeast(0L)
            groupStart = previous?.endTime
                ?.takeIf { it > defaultStart && it < groupEnd }
                ?: defaultStart
        }

        val totalDuration = groupEnd - groupStart
        return texts.mapIndexed { index, text ->
            SubtitleEntry(
                startTime = groupStart + totalDuration * index / count,
                endTime = groupStart + totalDuration * (index + 1) / count,
                text = text
            )
        }
    }

    fun applyOffset(entry: SubtitleEntry, offsetMs: Long) {
        entry.startTime = (entry.startTime + offsetMs).coerceAtLeast(0)
        entry.endTime = (entry.endTime + offsetMs).coerceAtLeast(entry.startTime + 1)
    }

    fun applyOffsetAll(entries: Iterable<SubtitleEntry>, offsetMs: Long) {
        entries.forEach { applyOffset(it, offsetMs) }
    }

    fun clampMoveToNeighbors(
        originalStartTime: Long,
        originalEndTime: Long,
        desiredStartTime: Long,
        previousEndTime: Long?,
        nextStartTime: Long?
    ): TimingRange {
        val duration = originalEndTime - originalStartTime
        if (duration <= 0L) return TimingRange(originalStartTime, originalEndTime)

        val minimumStart = maxOf(0L, previousEndTime ?: 0L)
        val maximumStart = nextStartTime?.minus(duration) ?: (Long.MAX_VALUE - duration)
        if (maximumStart < minimumStart) {
            return TimingRange(originalStartTime, originalEndTime)
        }

        val startTime = desiredStartTime.coerceIn(minimumStart, maximumStart)
        return TimingRange(startTime, startTime + duration)
    }

    fun clampStartToNeighbors(
        originalStartTime: Long,
        currentEndTime: Long,
        desiredStartTime: Long,
        previousEndTime: Long?,
        minimumDurationMs: Long
    ): Long {
        require(minimumDurationMs > 0L) { "minimumDurationMs must be positive" }
        val minimumStart = maxOf(0L, previousEndTime ?: 0L)
        val maximumStart = currentEndTime - minimumDurationMs
        return if (maximumStart < minimumStart) {
            originalStartTime
        } else {
            desiredStartTime.coerceIn(minimumStart, maximumStart)
        }
    }

    fun clampEndToNeighbors(
        originalEndTime: Long,
        currentStartTime: Long,
        desiredEndTime: Long,
        nextStartTime: Long?,
        minimumDurationMs: Long
    ): Long {
        require(minimumDurationMs > 0L) { "minimumDurationMs must be positive" }
        val minimumEnd = currentStartTime + minimumDurationMs
        val maximumEnd = nextStartTime ?: Long.MAX_VALUE
        return if (maximumEnd < minimumEnd) {
            originalEndTime
        } else {
            desiredEndTime.coerceIn(minimumEnd, maximumEnd)
        }
    }

    fun mergeAdjacent(entries: List<SubtitleEntry>, maxGapMs: Long): List<SubtitleEntry> {
        require(maxGapMs >= 0L) { "maxGapMs must not be negative" }
        if (entries.isEmpty()) return emptyList()

        val result = mutableListOf(entries.first().copy())
        var previousEntry = entries.first()

        entries.drop(1).forEach { entry ->
            val shouldMerge = entry.startTime <= previousEntry.endTime ||
                entry.startTime - previousEntry.endTime <= maxGapMs
            if (shouldMerge) {
                val merged = result.last()
                merged.startTime = minOf(merged.startTime, entry.startTime)
                merged.endTime = maxOf(merged.endTime, entry.endTime)
                merged.text = "${merged.text}；${entry.text}"
                merged.endTimeModified = merged.endTimeModified || entry.endTimeModified
            } else {
                result.add(entry.copy())
            }
            previousEntry = entry
        }

        return result
    }
}
