package com.subtitleedit.util

object SearchTextMatcher {
    private const val WORD_CHARACTERS = "\\p{L}\\p{M}\\p{N}_"

    fun contains(
        content: String,
        query: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): Boolean = firstMatchRange(content, query, matchCase, wholeWord) != null

    fun firstMatchRange(
        content: String,
        query: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): IntRange? = literalRegex(query, matchCase, wholeWord)?.find(content)?.range

    fun isMatchAt(
        content: String,
        start: Int,
        query: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): Boolean {
        if (start !in content.indices) return false
        return literalRegex(query, matchCase, wholeWord)
            ?.find(content, start)
            ?.range
            ?.first == start
    }

    fun findMatchStarts(
        content: String,
        query: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): List<Int> {
        val regex = literalRegex(query, matchCase, wholeWord) ?: return emptyList()
        val starts = mutableListOf<Int>()
        var searchStart = 0
        while (searchStart < content.length) {
            val match = regex.find(content, searchStart) ?: break
            starts += match.range.first
            searchStart = match.range.first + 1
        }
        return starts
    }

    internal fun literalRegex(
        query: String,
        matchCase: Boolean,
        wholeWord: Boolean
    ): Regex? {
        if (query.isEmpty()) return null
        val literal = Regex.escape(query)
        val pattern = if (wholeWord) {
            "(?<![$WORD_CHARACTERS])$literal(?![$WORD_CHARACTERS])"
        } else {
            literal
        }
        val options = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(pattern, options)
    }
}
