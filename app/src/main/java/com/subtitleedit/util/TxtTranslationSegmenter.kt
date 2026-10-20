package com.subtitleedit.util

/** Splits plain text without losing source characters or overlapping adjacent requests. */
internal object TxtTranslationSegmenter {
    data class Segment(val text: String)

    private data class Boundary(val end: Int, val characters: Int)

    fun split(
        text: String,
        maxCharacters: Int = 800,
        searchRadius: Int = 50,
        wordStart: (String, Int) -> Int
    ): List<Segment> {
        require(maxCharacters > 0) { "maxCharacters must be positive" }
        require(searchRadius >= 0) { "searchRadius must not be negative" }
        if (text.isEmpty()) return emptyList()

        val characters = ArrayList<Int>()
        val lineBreaks = ArrayList<Boundary>()
        val punctuation = ArrayList<Boundary>()
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            when {
                isLineBreak(codePoint) -> {
                    do {
                        offset += Character.charCount(text.codePointAt(offset))
                    } while (offset < text.length && isLineBreak(text.codePointAt(offset)))
                    lineBreaks.add(Boundary(offset, characters.size))
                }
                isPunctuation(codePoint) -> {
                    do {
                        offset += Character.charCount(text.codePointAt(offset))
                    } while (offset < text.length && isPunctuation(text.codePointAt(offset)))
                    punctuation.add(Boundary(offset, characters.size))
                }
                else -> {
                    if (!isWhitespace(codePoint)) characters.add(offset)
                    offset += Character.charCount(codePoint)
                }
            }
        }

        val result = ArrayList<Segment>()
        var start = 0
        var consumedCharacters = 0
        while (start < text.length) {
            if (characters.size - consumedCharacters <= maxCharacters) {
                result.add(Segment(text.substring(start)))
                break
            }
            val target = consumedCharacters + maxCharacters
            val pivot = characters[target - 1]
            val fallbackEnd = pivot + Character.charCount(text.codePointAt(pivot))
            val boundary = nearestBoundary(lineBreaks, target, searchRadius, consumedCharacters)
                ?: nearestBoundary(punctuation, target, searchRadius, consumedCharacters)
            val proposedEnd = boundary?.end ?: wordStart(text, pivot).let { candidate ->
                if (candidate > start && candidate <= pivot && isCodePointBoundary(text, candidate) &&
                    firstAtOrAfter(characters, candidate) > consumedCharacters) {
                    candidate
                } else {
                    fallbackEnd
                }
            }
            val consumedAtEnd = firstAtOrAfter(characters, proposedEnd)
            // Keep a trailing punctuation/whitespace suffix in the last real request.
            val end = if (consumedAtEnd == characters.size) text.length else proposedEnd
            result.add(Segment(text.substring(start, end)))
            start = end
            consumedCharacters = consumedAtEnd
        }
        return result
    }

    /** Joins independent translations, restoring whitespace at the source segment boundaries. */
    fun merge(translations: List<String>, segments: List<Segment>): String {
        require(translations.size == segments.size) { "Translation and segment counts must match" }
        if (translations.isEmpty()) return ""

        val result = StringBuilder(translations.first())
        for (index in 1 until translations.size) {
            val translation = translations[index]
            val sourceGap = trailingWhitespace(segments[index - 1].text) +
                leadingWhitespace(segments[index].text)
            if (sourceGap.isNotEmpty()) {
                while (result.isNotEmpty()) {
                    val codePoint = Character.codePointBefore(result, result.length)
                    if (!isWhitespace(codePoint)) break
                    result.setLength(result.length - Character.charCount(codePoint))
                }
                result.append(sourceGap)
                result.append(translation.substring(leadingWhitespaceEnd(translation)))
            } else {
                if (needsEnglishSpace(result, translation)) result.append(' ')
                result.append(translation)
            }
        }
        val sourceLeading = leadingWhitespace(segments.first().text)
        val sourceTrailing = trailingWhitespace(segments.last().text)
        val merged = result.toString()
        val withLeading = if (sourceLeading.isEmpty()) merged else {
            sourceLeading + merged.substring(leadingWhitespaceEnd(merged))
        }
        return if (sourceTrailing.isEmpty()) withLeading else {
            withLeading.dropLast(trailingWhitespace(withLeading).length) + sourceTrailing
        }
    }

    private fun nearestBoundary(
        boundaries: List<Boundary>,
        target: Int,
        radius: Int,
        consumedCharacters: Int
    ): Boundary? {
        val minimum = maxOf(consumedCharacters.toLong() + 1, target.toLong() - radius)
        val maximum = target.toLong() + radius
        var low = 0
        var high = boundaries.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (boundaries[middle].characters < minimum) low = middle + 1 else high = middle
        }
        var best: Boundary? = null
        var bestDistance = Long.MAX_VALUE
        while (low < boundaries.size) {
            val boundary = boundaries[low++]
            if (boundary.characters > maximum) break
            val distance = kotlin.math.abs(boundary.characters.toLong() - target)
            if (distance < bestDistance) {
                best = boundary
                bestDistance = distance
            }
        }
        return best
    }

    private fun firstAtOrAfter(offsets: List<Int>, position: Int): Int {
        var low = 0
        var high = offsets.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (offsets[middle] < position) low = middle + 1 else high = middle
        }
        return low
    }

    private fun isCodePointBoundary(text: String, offset: Int): Boolean =
        offset < text.length && !(Character.isLowSurrogate(text[offset]) &&
            offset > 0 && Character.isHighSurrogate(text[offset - 1]))

    private fun leadingWhitespaceEnd(text: String): Int {
        var end = 0
        while (end < text.length && isWhitespace(text.codePointAt(end))) {
            end += Character.charCount(text.codePointAt(end))
        }
        return end
    }

    private fun leadingWhitespace(text: String): String = text.substring(0, leadingWhitespaceEnd(text))

    private fun trailingWhitespace(text: String): String {
        var start = text.length
        while (start > 0) {
            val codePoint = text.codePointBefore(start)
            if (!isWhitespace(codePoint)) break
            start -= Character.charCount(codePoint)
        }
        return text.substring(start)
    }

    private fun needsEnglishSpace(left: CharSequence, right: String): Boolean {
        if (left.isEmpty() || right.isEmpty()) return false
        val last = Character.codePointBefore(left, left.length)
        val first = right.codePointAt(0)
        if (isWhitespace(last) || isWhitespace(first)) return false
        var leftEnd = left.length
        while (leftEnd > 0 && isClosingPunctuation(Character.codePointBefore(left, leftEnd))) {
            leftEnd -= Character.charCount(Character.codePointBefore(left, leftEnd))
        }
        var rightStart = 0
        while (rightStart < right.length && isOpeningPunctuation(right.codePointAt(rightStart))) {
            rightStart += Character.charCount(right.codePointAt(rightStart))
        }
        return leftEnd > 0 && rightStart < right.length &&
            isEnglishWord(Character.codePointBefore(left, leftEnd)) &&
            isEnglishWord(right.codePointAt(rightStart))
    }

    private fun isEnglishWord(codePoint: Int): Boolean = Character.isDigit(codePoint) ||
        (Character.isLetter(codePoint) && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN)

    private fun isOpeningPunctuation(codePoint: Int): Boolean =
        Character.getType(codePoint) == Character.START_PUNCTUATION.toInt() ||
            Character.getType(codePoint) == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            codePoint == '"'.code || codePoint == 0x27

    private fun isClosingPunctuation(codePoint: Int): Boolean =
        Character.getType(codePoint) == Character.END_PUNCTUATION.toInt() ||
            Character.getType(codePoint) == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            codePoint == '.'.code || codePoint == ','.code || codePoint == '!'.code ||
            codePoint == '?'.code || codePoint == ';'.code || codePoint == ':'.code ||
            codePoint == '"'.code || codePoint == 0x27

    private fun isLineBreak(codePoint: Int): Boolean = codePoint == '\r'.code ||
        codePoint == '\n'.code || codePoint == 0x85 || codePoint == 0x2028 || codePoint == 0x2029

    private fun isWhitespace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85

    private fun isPunctuation(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt() -> true
        else -> false
    }
}
