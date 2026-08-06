package com.subtitleedit.util

object SearchReplaceOps {
    data class TextUpdate(
        val index: Int,
        val newText: String
    )

    data class ReplaceAllInTextResult(
        val newContent: String,
        val matchCount: Int
    )

    fun replaceTextIfChanged(
        originalText: String,
        query: String,
        replacement: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): String? {
        val regex = SearchTextMatcher.literalRegex(query, matchCase, wholeWord) ?: return null
        if (!regex.containsMatchIn(originalText)) return null
        // escapeReplacement：替换内容中的 $ 和 \ 必须按字面处理，否则会被当作正则组引用导致异常
        val updated = originalText.replace(
            regex,
            Regex.escapeReplacement(replacement)
        )
        return if (updated != originalText) updated else null
    }

    fun replaceFirstTextIfChanged(
        originalText: String,
        query: String,
        replacement: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): String? {
        val match = SearchTextMatcher.literalRegex(query, matchCase, wholeWord)
            ?.find(originalText) ?: return null
        return originalText.replaceRange(match.range, replacement)
            .takeIf { it != originalText }
    }

    fun countMatches(
        content: String,
        query: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): Int = SearchTextMatcher.literalRegex(query, matchCase, wholeWord)
        ?.findAll(content)
        ?.count()
        ?: 0

    fun collectTextUpdates(
        texts: List<String>,
        query: String,
        replacement: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): List<TextUpdate> {
        if (query.isEmpty()) return emptyList()
        val updates = mutableListOf<TextUpdate>()
        texts.forEachIndexed { index, text ->
            val updated = replaceTextIfChanged(
                originalText = text,
                query = query,
                replacement = replacement,
                matchCase = matchCase,
                wholeWord = wholeWord
            )
            if (updated != null) {
                updates.add(TextUpdate(index, updated))
            }
        }
        return updates
    }

    fun replaceInContentAt(
        content: String,
        start: Int,
        queryLength: Int,
        replacement: String
    ): String? {
        if (queryLength <= 0) return null
        if (start < 0 || start >= content.length) return null
        val end = start + queryLength
        if (end > content.length) return null
        return content.replaceRange(start, end, replacement)
    }

    fun replaceAllInContent(
        content: String,
        query: String,
        replacement: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): ReplaceAllInTextResult {
        val regex = SearchTextMatcher.literalRegex(query, matchCase, wholeWord)
            ?: return ReplaceAllInTextResult(content, 0)
        val matchCount = regex.findAll(content).count()
        val newContent = content.replace(regex, Regex.escapeReplacement(replacement))
        return ReplaceAllInTextResult(newContent, matchCount)
    }
}
