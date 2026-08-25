package com.subtitleedit.util

data class SubtitleFormattingOptions(
    val removeSpaces: Boolean = false,
    val innerPunctuation: Set<Char> = emptySet(),
    val endPunctuation: Set<Char> = emptySet(),
    val replaceFrom: String = "",
    val replaceTo: String = "",
    val replacementScope: PunctuationReplacementScope = PunctuationReplacementScope.INNER,
    val addEndPunctuation: String = ""
)

enum class PunctuationReplacementScope { INNER, END }

object SubtitleTextFormatter {
    fun format(text: String, options: SubtitleFormattingOptions): String {
        if ('\n' !in text) return formatLine(text, options)

        // Avoid split/joinToString allocating a list for every line of a large cue.
        return buildString(text.length) {
            var lineStart = 0
            while (lineStart <= text.length) {
                val lineEnd = text.indexOf('\n', lineStart).let { if (it >= 0) it else text.length }
                append(formatLine(text.substring(lineStart, lineEnd), options))
                if (lineEnd == text.length) break
                append('\n')
                lineStart = lineEnd + 1
            }
        }
    }

    private fun formatLine(source: String, options: SubtitleFormattingOptions): String {
        var line = applyReplacement(source, options)

        if (options.removeSpaces) {
            line = line.replace(Regex("[ \\t\\u3000]+"), "")
        }

        if (options.innerPunctuation.isNotEmpty()) {
            var protectedSuffixStart = line.length
            while (protectedSuffixStart > 0 &&
                (line[protectedSuffixStart - 1].isWhitespace() ||
                    line[protectedSuffixStart - 1] in options.innerPunctuation)
            ) {
                protectedSuffixStart--
            }
            line = line.filterIndexed { index, char ->
                char !in options.innerPunctuation || index >= protectedSuffixStart
            }
        }

        if (options.endPunctuation.isNotEmpty()) {
            line = line.trimEnd()
            val (content, closingSuffix) = detachClosingSuffix(line)
            line = content
            while (line.isNotEmpty() && line.last() in options.endPunctuation) {
                line = line.dropLast(1).trimEnd()
            }
            line += closingSuffix
        }

        if (options.addEndPunctuation.isNotEmpty() && line.isNotBlank()) {
            val (content, closingSuffix) = detachClosingSuffix(line.trimEnd())
            line = if (content.endsWith(options.addEndPunctuation)) {
                content + closingSuffix
            } else {
                content + options.addEndPunctuation + closingSuffix
            }
        }
        return line
    }

    private fun applyReplacement(source: String, options: SubtitleFormattingOptions): String {
        val from = options.replaceFrom
        if (from.isEmpty()) return source
        val (content, closingSuffix) = detachClosingSuffix(source.trimEnd())
        return when (options.replacementScope) {
            PunctuationReplacementScope.END -> {
                if (content.endsWith(from)) content.dropLast(from.length) + options.replaceTo + closingSuffix
                else source
            }
            PunctuationReplacementScope.INNER -> {
                var body = content
                var protectedSuffix = ""
                while (body.endsWith(from)) {
                    protectedSuffix = from + protectedSuffix
                    body = body.dropLast(from.length)
                }
                body.replace(from, options.replaceTo) + protectedSuffix + closingSuffix
            }
        }
    }

    private fun detachClosingSuffix(source: String): Pair<String, String> {
        val closingChars = setOf('”', '’', '」', '』', '"', '\'', ')', '）', ']', '】')
        var content = source
        var suffix = ""
        while (content.isNotEmpty() && content.last() in closingChars) {
            suffix = content.last() + suffix
            content = content.dropLast(1).trimEnd()
        }
        return content to suffix
    }
}
