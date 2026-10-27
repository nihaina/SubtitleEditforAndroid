package com.subtitleedit.view

/** Text and original line ending for one logical source-editor row. */
internal data class SourceDocumentLine(
    val text: String,
    val lineEnding: String
) {
    val serializedLength: Int
        get() = text.length + lineEnding.length
}

/** Smallest changed row range between two source documents. The unchanged suffix is reusable. */
internal data class SourceDocumentLineDiff(
    val unchangedPrefix: Int,
    val oldChangedEnd: Int,
    val replacement: List<SourceDocumentLine>
) {
    val oldChangedCount: Int
        get() = oldChangedEnd - unchangedPrefix

    val newChangedCount: Int
        get() = replacement.size
}

internal object SourceDocumentLineDiffs {
    fun split(content: String): List<SourceDocumentLine> {
        if (content.isEmpty()) return listOf(SourceDocumentLine("", ""))
        val result = ArrayList<SourceDocumentLine>()
        var lineStart = 0
        var index = 0
        while (index < content.length) {
            val endingLength = when (content[index]) {
                '\r' -> if (index + 1 < content.length && content[index + 1] == '\n') 2 else 1
                '\n', '\u2028', '\u2029', '\u0085' -> 1
                else -> 0
            }
            if (endingLength == 0) {
                index++
                continue
            }
            result += SourceDocumentLine(
                content.substring(lineStart, index),
                content.substring(index, index + endingLength)
            )
            index += endingLength
            lineStart = index
        }
        result += SourceDocumentLine(content.substring(lineStart), "")
        return result
    }

    /** Find the changed row range without allocating a temporary line object for each old row. */
    fun diff(
        oldCount: Int,
        updatedContent: String,
        oldLineLength: (Int) -> Int,
        oldLineMatchesAt: (Int, String, Int) -> Boolean
    ): SourceDocumentLineDiff {
        if (updatedContent.isEmpty()) {
            val existingBlankRowIsReusable = oldCount == 1 && oldLineMatchesAt(0, updatedContent, 0)
            return if (existingBlankRowIsReusable) {
                SourceDocumentLineDiff(1, 1, emptyList())
            } else {
                SourceDocumentLineDiff(0, oldCount, listOf(SourceDocumentLine("", "")))
            }
        }
        var prefix = 0
        var newStartOffset = 0
        while (prefix < oldCount) {
            if (!oldLineMatchesAt(prefix, updatedContent, newStartOffset)) break
            prefix++
            newStartOffset += oldLineLength(prefix - 1)
        }
        if (prefix == oldCount) {
            return SourceDocumentLineDiff(prefix, oldCount, emptyList())
        }

        var oldEnd = oldCount
        var newEndOffset = updatedContent.length
        while (oldEnd > prefix) {
            val candidateStart = newEndOffset - oldLineLength(oldEnd - 1)
            if (candidateStart < newStartOffset ||
                !oldLineMatchesAt(oldEnd - 1, updatedContent, candidateStart)
            ) break
            oldEnd--
            newEndOffset = candidateStart
        }
        return SourceDocumentLineDiff(
            unchangedPrefix = prefix,
            oldChangedEnd = oldEnd,
            replacement = splitRange(
                updatedContent,
                newStartOffset,
                newEndOffset,
                appendTerminalEmptyRow = oldEnd == oldCount
            )
        )
    }

    fun matchesAt(content: String, start: Int, text: String, lineEnding: String): Boolean {
        val serializedLength = text.length + lineEnding.length
        if (start < 0 || start + serializedLength > content.length) return false
        if (!content.regionMatches(start, text, 0, text.length)) return false
        val endingStart = start + text.length
        if (lineEnding.isNotEmpty()) {
            return content.regionMatches(endingStart, lineEnding, 0, lineEnding.length)
        }
        if (endingStart != content.length) return false
        return text.isNotEmpty() || endingStart == 0 || isLineBreak(content[endingStart - 1])
    }

    internal fun splitRange(
        content: String,
        start: Int,
        end: Int,
        appendTerminalEmptyRow: Boolean
    ): List<SourceDocumentLine> {
        if (start == end) {
            return if (appendTerminalEmptyRow &&
                (start == 0 || isLineBreak(content[start - 1]))
            ) {
                listOf(SourceDocumentLine("", ""))
            } else {
                emptyList()
            }
        }
        val result = ArrayList<SourceDocumentLine>()
        var lineStart = start
        var index = start
        while (index < end) {
            val endingLength = when (content[index]) {
                '\r' -> if (index + 1 < end && content[index + 1] == '\n') 2 else 1
                '\n', '\u2028', '\u2029', '\u0085' -> 1
                else -> 0
            }
            if (endingLength == 0) {
                index++
                continue
            }
            result += SourceDocumentLine(
                content.substring(lineStart, index),
                content.substring(index, index + endingLength)
            )
            index += endingLength
            lineStart = index
        }
        if (lineStart < end) {
            result += SourceDocumentLine(content.substring(lineStart, end), "")
        } else if (appendTerminalEmptyRow && end == content.length) {
            result += SourceDocumentLine("", "")
        }
        return result
    }

    private fun isLineBreak(char: Char): Boolean = when (char) {
        '\r', '\n', '\u2028', '\u2029', '\u0085' -> true
        else -> false
    }
}
