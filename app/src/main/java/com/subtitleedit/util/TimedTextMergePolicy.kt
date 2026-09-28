package com.subtitleedit.util

internal object TimedTextMergePolicy {
    val SMART_GAPS_MS = listOf(150, 200, 230, 250, 300, 350, 400)
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
        combine: (T, T) -> T
    ): List<T> {
        if (segments.size < 2) return segments
        val limit = maxCharacters?.coerceIn(15, 50)
        if (!smart) {
            val merged = mutableListOf<T>()
            var current = segments.first()
            for (next in segments.drop(1)) {
                if (start(next) - end(current) <= maxGapMs.coerceIn(0, 5000) &&
                    (limit == null || characterCount(text(current)) + characterCount(text(next)) <= limit)
                ) {
                    current = combine(current, next)
                } else {
                    merged += current
                    current = next
                }
            }
            merged += current
            return merged
        }

        var result = segments
        for (gap in SMART_GAPS_MS) {
            while (result.size > 1) {
                val candidates = (0 until result.lastIndex).mapNotNull { index ->
                    val left = result[index]
                    val right = result[index + 1]
                    val count = characterCount(text(left)) + characterCount(text(right))
                    if (start(right) - end(left) <= gap && (limit == null || count <= limit)) {
                        index to count
                    } else null
                }.sortedWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
                if (candidates.isEmpty()) break

                val used = BooleanArray(result.size)
                val selected = BooleanArray(result.lastIndex)
                candidates.forEach { (index, _) ->
                    if (!used[index] && !used[index + 1]) {
                        selected[index] = true
                        used[index] = true
                        used[index + 1] = true
                    }
                }
                val next = mutableListOf<T>()
                var index = 0
                while (index < result.size) {
                    if (index < selected.size && selected[index]) {
                        next += combine(result[index], result[index + 1])
                        index += 2
                    } else {
                        next += result[index]
                        index++
                    }
                }
                result = next
            }
        }
        return result
    }
}
