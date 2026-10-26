package com.subtitleedit.editor

/** A reversible source-text patch. History retains only the changed range. */
internal data class EditorSourceTextEdit(
    val startOffset: Int,
    val deletedText: String,
    val insertedText: String,
    val lineNumber: Int
) {
    val isNoOp: Boolean get() = deletedText == insertedText

    fun applyTo(source: String, undo: Boolean): String {
        val expected = if (undo) insertedText else deletedText
        val replacement = if (undo) deletedText else insertedText
        require(startOffset >= 0 && startOffset + expected.length <= source.length) {
            "Source history patch is outside the current document"
        }
        require(source.regionMatches(startOffset, expected, 0, expected.length)) {
            "Source history patch does not match the current document"
        }
        return buildString(source.length - expected.length + replacement.length) {
            append(source, 0, startOffset)
            append(replacement)
            append(source, startOffset + expected.length, source.length)
        }
    }

    companion object {
        fun fromRanges(
            before: String,
            after: String,
            oldStart: Int,
            oldEnd: Int,
            newStart: Int,
            newEnd: Int,
            lineNumber: Int
        ): EditorSourceTextEdit {
            val safeOldStart = oldStart.coerceIn(0, before.length)
            val safeOldEnd = oldEnd.coerceIn(safeOldStart, before.length)
            val safeNewStart = newStart.coerceIn(0, after.length)
            val safeNewEnd = newEnd.coerceIn(safeNewStart, after.length)
            require(safeOldStart == safeNewStart) { "Source edit ranges must share a start offset" }
            val oldLength = safeOldEnd - safeOldStart
            val newLength = safeNewEnd - safeNewStart
            val maxPrefix = minOf(oldLength, newLength)
            var prefix = 0
            while (prefix < maxPrefix && before[safeOldStart + prefix] == after[safeNewStart + prefix]) {
                prefix++
            }
            var suffix = 0
            while (suffix < oldLength - prefix && suffix < newLength - prefix &&
                before[safeOldEnd - suffix - 1] == after[safeNewEnd - suffix - 1]
            ) {
                suffix++
            }
            return EditorSourceTextEdit(
                startOffset = safeOldStart + prefix,
                deletedText = before.substring(safeOldStart + prefix, safeOldEnd - suffix),
                insertedText = after.substring(safeNewStart + prefix, safeNewEnd - suffix),
                lineNumber = lineNumber
            )
        }

        fun between(before: String, after: String): EditorSourceTextEdit {
            var prefix = 0
            val maxPrefix = minOf(before.length, after.length)
            while (prefix < maxPrefix && before[prefix] == after[prefix]) prefix++
            var suffix = 0
            while (suffix < before.length - prefix && suffix < after.length - prefix &&
                before[before.length - suffix - 1] == after[after.length - suffix - 1]
            ) {
                suffix++
            }
            return EditorSourceTextEdit(
                startOffset = prefix,
                deletedText = before.substring(prefix, before.length - suffix),
                insertedText = after.substring(prefix, after.length - suffix),
                lineNumber = countLineBreaks(before, prefix)
            )
        }

        fun lineTextBefore(source: String, offset: Int): String {
            val safeOffset = offset.coerceIn(0, source.length)
            var start = safeOffset
            while (start > 0 && !isLineBreak(source[start - 1])) start--
            var end = safeOffset
            while (end < source.length && !isLineBreak(source[end])) end++
            return source.substring(start, end).removeSuffix("\r")
        }

        private fun countLineBreaks(source: String, end: Int): Int {
            var count = 0
            var index = 0
            while (index < end) {
                when (source[index]) {
                    '\r' -> {
                        count++
                        if (index + 1 < end && source[index + 1] == '\n') index++
                    }
                    '\n', '\u2028', '\u2029', '\u0085' -> count++
                }
                index++
            }
            return count
        }

        private fun isLineBreak(char: Char): Boolean =
            char == '\r' || char == '\n' || char == '\u2028' || char == '\u2029' || char == '\u0085'
    }
}
