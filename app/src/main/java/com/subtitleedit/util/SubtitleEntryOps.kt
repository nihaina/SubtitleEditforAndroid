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

    /**
     * Reassociate parsed source rows with the in-memory rows they came from. Exact subtitle
     * content is matched first, so inserting or deleting a cue does not shift every later ID.
     * Remaining rows represent edits and retain the nearest available identity.
     */
    fun retainStableIds(
        previous: List<SubtitleEntry>,
        parsed: List<SubtitleEntry>
    ): List<SubtitleEntry> {
        if (previous.isEmpty() || parsed.isEmpty()) return parsed.map { it.copy() }

        data class ContentKey(val startTime: Long, val endTime: Long, val text: String)

        val previousByContent = mutableMapOf<ContentKey, ArrayDeque<Int>>()
        previous.forEachIndexed { index, entry ->
            val key = ContentKey(entry.startTime, entry.endTime, entry.text)
            previousByContent.getOrPut(key, ::ArrayDeque).addLast(index)
        }

        val matchedPrevious = BooleanArray(previous.size)
        val previousIndexByParsed = IntArray(parsed.size) { -1 }
        parsed.forEachIndexed { index, entry ->
            val key = ContentKey(entry.startTime, entry.endTime, entry.text)
            val previousIndex = previousByContent[key]?.removeFirstOrNull() ?: return@forEachIndexed
            previousIndexByParsed[index] = previousIndex
            matchedPrevious[previousIndex] = true
        }

        val unmatchedPrevious = previous.indices.filterNot { matchedPrevious[it] }.toMutableSet()
        val unmatchedParsed = parsed.indices.filter { previousIndexByParsed[it] < 0 }
        if (previous.size == parsed.size) {
            unmatchedParsed.forEach { parsedIndex ->
                if (parsedIndex in unmatchedPrevious) {
                    previousIndexByParsed[parsedIndex] = parsedIndex
                    unmatchedPrevious.remove(parsedIndex)
                }
            }
        }

        val stillUnmatchedParsed = unmatchedParsed.filter { previousIndexByParsed[it] < 0 }
        if (stillUnmatchedParsed.size.toLong() * unmatchedPrevious.size <= 40_000L) {
            data class Candidate(val score: Long, val parsedIndex: Int, val previousIndex: Int)

            val candidates = stillUnmatchedParsed.flatMap { parsedIndex ->
                unmatchedPrevious.map { previousIndex ->
                    val old = previous[previousIndex]
                    val current = parsed[parsedIndex]
                    val contentPenalty =
                        (if (old.startTime == current.startTime) 0L else 4L) +
                            (if (old.endTime == current.endTime) 0L else 4L) +
                            (if (old.text == current.text) 0L else 2L)
                    Candidate(
                        score = contentPenalty * (previous.size + parsed.size + 1L) +
                            kotlin.math.abs(previousIndex - parsedIndex),
                        parsedIndex = parsedIndex,
                        previousIndex = previousIndex
                    )
                }
            }.sortedBy { it.score }

            val unmatchedParsedSet = stillUnmatchedParsed.toMutableSet()
            candidates.forEach { candidate ->
                if (candidate.parsedIndex in unmatchedParsedSet &&
                    candidate.previousIndex in unmatchedPrevious
                ) {
                    previousIndexByParsed[candidate.parsedIndex] = candidate.previousIndex
                    unmatchedParsedSet.remove(candidate.parsedIndex)
                    unmatchedPrevious.remove(candidate.previousIndex)
                }
            }
        } else {
            stillUnmatchedParsed.zip(unmatchedPrevious.sorted()).forEach { (parsedIndex, previousIndex) ->
                previousIndexByParsed[parsedIndex] = previousIndex
            }
        }

        return parsed.mapIndexed { index, entry ->
            val previousIndex = previousIndexByParsed[index]
            if (previousIndex >= 0) {
                entry.copy(stableId = previous[previousIndex].stableId)
            } else {
                entry.copy()
            }
        }
    }

    /** Applies only undoable list fields to surviving rows; format-only fields remain current. */
    fun applyEditableHistoryTarget(
        current: List<SubtitleEntry>,
        target: List<SubtitleEntry>
    ): List<SubtitleEntry> {
        val currentById = current.associateBy { it.stableId }
        return target.mapIndexed { index, historical ->
            currentById[historical.stableId]?.copy(
                index = index + 1,
                startTime = historical.startTime,
                endTime = historical.endTime,
                text = historical.text,
                endTimeModified = historical.endTimeModified
            ) ?: historical.copy(index = index + 1)
        }
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
