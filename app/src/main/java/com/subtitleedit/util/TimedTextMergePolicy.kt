package com.subtitleedit.util

internal object TimedTextMergePolicy {
    const val SMART_MAX_GAP_MS = 500
    private val punctuationTypes = setOf(
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt()
    )

    fun characterCount(text: String): Int = text.codePoints()
        .filter { codePoint ->
            val type = Character.getType(codePoint)
            !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint) &&
                type !in punctuationTypes
        }
        .count().toInt()

    fun <T> merge(
        segments: List<T>,
        maxGapMs: Int,
        smart: Boolean,
        maxCharacters: Int?,
        start: (T) -> Long,
        end: (T) -> Long,
        text: (T) -> String,
        combine: (T, T) -> T,
        split: (T, Int) -> Pair<T, T>,
        splitOnPunctuation: Boolean = true
    ): List<T> {
        if (segments.size < 2) return segments
        if (!smart) {
            val result = mutableListOf<T>()
            var current = segments.first()
            for (next in segments.drop(1)) {
                if (start(next) - end(current) <= maxGapMs.coerceIn(0, 5000)) {
                    current = combine(current, next)
                } else {
                    result += current
                    current = next
                }
            }
            result += current
            return result
        }

        val limit = maxCharacters?.coerceAtLeast(1)
        var result = segments.map { MergeUnit(it) }
        for (gapLimit in 0..SMART_MAX_GAP_MS) {
            while (result.size > 1) {
                val next = mutableListOf<MergeUnit<T>>()
                var changed = false
                var index = 0
                while (index < result.size) {
                    val first = index
                    var combined = result[first].value
                    var continuous = true
                    while (index < result.lastIndex) {
                        val left = result[index]
                        val right = result[index + 1]
                        val gap = start(right.value) - end(left.value)
                        if (right.splitBoundaryBefore || left.rejectedRight === right || gap > gapLimit ||
                            (gapLimit > 0 && splitOnPunctuation && endsInPunctuation(text(left.value)))
                        ) break
                        continuous = continuous && gap <= 0L
                        combined = combine(combined, right.value)
                        index++
                    }
                    if (index == first) {
                        next += result[index++]
                        continue
                    }

                    // Equal gaps become eligible in the same round. Validate the whole group
                    // before replacing any source cue, including every punctuation slice.
                    val pieces = when {
                        // Touching groups split unconditionally in the first round. Their
                        // punctuation boundaries also preserve the terminal direction rule.
                        gapLimit == 0 && splitOnPunctuation -> splitAtPunctuation(combined, text, split)
                        continuous || limit == null || characterCount(text(combined)) <= limit -> listOf(combined)
                        else -> splitWithinLimit(combined, limit, text, split)
                    }
                    if (pieces == null) {
                        val pairMerges = if (limit != null && punctuationSplitIndex(text(combined)) == null) {
                            mergeShortPairs(result.subList(first, index + 1), limit, start, end, text, combine)
                        } else {
                            null
                        }
                        if (pairMerges != null) {
                            next += pairMerges
                            changed = true
                        } else {
                            for (sourceIndex in first until index) {
                                // Retry a failed edge only after one of its neighbors changes.
                                result[sourceIndex].rejectedRight = result[sourceIndex + 1]
                            }
                            next.addAll(result.subList(first, index + 1))
                        }
                    } else {
                        pieces.forEachIndexed { pieceIndex, piece ->
                            next += MergeUnit(
                                value = piece,
                                splitBoundaryBefore = pieceIndex > 0 || result[first].splitBoundaryBefore
                            )
                        }
                        changed = true
                    }
                    index++
                }
                result = next
                // Every accepted group removes at least one available merge edge; new slice
                // boundaries stay protected even when the directional option is disabled.
                if (!changed) break
            }
        }
        return result.map { it.value }
    }

    private class MergeUnit<T>(val value: T, val splitBoundaryBefore: Boolean = false) {
        var rejectedRight: MergeUnit<T>? = null
    }

    private fun <T> mergeShortPairs(
        group: List<MergeUnit<T>>,
        limit: Int,
        start: (T) -> Long,
        end: (T) -> Long,
        text: (T) -> String,
        combine: (T, T) -> T
    ): List<MergeUnit<T>>? {
        // Without a punctuation split to validate, retain normal pair merging so one long
        // cue cannot prevent a shorter neighboring pair with the same gap from merging.
        val candidates = (0 until group.lastIndex).mapNotNull { index ->
            val combined = combine(group[index].value, group[index + 1].value)
            val count = characterCount(text(combined))
            if (count <= limit || start(group[index + 1].value) <= end(group[index].value)) {
                Triple(index, combined, count)
            } else {
                null
            }
        }.sortedWith(compareBy<Triple<Int, T, Int>> { it.third }.thenBy { it.first })
        if (candidates.isEmpty()) return null
        val used = BooleanArray(group.size)
        val selected = mutableMapOf<Int, T>()
        candidates.forEach { (index, combined, _) ->
            if (!used[index] && !used[index + 1]) {
                selected[index] = combined
                used[index] = true
                used[index + 1] = true
            }
        }
        val output = mutableListOf<MergeUnit<T>>()
        var index = 0
        while (index < group.size) {
            if (index in selected) {
                output += MergeUnit(selected.getValue(index), group[index].splitBoundaryBefore)
                index += 2
            } else {
                output += group[index++]
            }
        }
        return output
    }

    private fun <T> splitWithinLimit(
        combined: T,
        limit: Int,
        text: (T) -> String,
        split: (T, Int) -> Pair<T, T>
    ): List<T>? = splitAtPunctuation(combined, text, split).takeIf { pieces ->
        pieces.size > 1 && pieces.all { characterCount(text(it)) <= limit }
    }

    private fun <T> splitAtPunctuation(
        combined: T,
        text: (T) -> String,
        split: (T, Int) -> Pair<T, T>
    ): List<T> {
        val pieces = mutableListOf<T>()
        var remainder = combined
        while (true) {
            val splitIndex = punctuationSplitIndex(text(remainder)) ?: break
            val (left, right) = split(remainder, splitIndex)
            pieces += left
            remainder = right
        }
        pieces += remainder
        return pieces
    }

    private fun endsInPunctuation(text: String): Boolean {
        val trimmed = text.trimEnd()
        // A punctuation-only subtitle still participates normally in both directions.
        return trimmed.isNotEmpty() && characterCount(trimmed) > 0 &&
            Character.getType(trimmed.codePointBefore(trimmed.length)) in punctuationTypes
    }

    private fun punctuationSplitIndex(text: String): Int? {
        val totalCount = characterCount(text)
        var index = 0
        var count = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val type = Character.getType(codePoint)
            index += Character.charCount(codePoint)
            if (type !in punctuationTypes) {
                if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) count++
                continue
            }
            // Keep punctuation clusters together on the left, including closing quotes.
            while (index < text.length && Character.getType(text.codePointAt(index)) in punctuationTypes) {
                index += Character.charCount(text.codePointAt(index))
            }
            if (count == 0 || count == totalCount) continue
            return index
        }
        return null
    }
}
