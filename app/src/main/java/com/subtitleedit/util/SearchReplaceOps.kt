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
        replacement: String
    ): String? {
        if (!originalText.contains(query, ignoreCase = true)) return null
        val updated = originalText.replace(
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE),
            replacement
        )
        return if (updated != originalText) updated else null
    }

    fun collectTextUpdates(
        texts: List<String>,
        query: String,
        replacement: String
    ): List<TextUpdate> {
        if (query.isEmpty()) return emptyList()
        val updates = mutableListOf<TextUpdate>()
        texts.forEachIndexed { index, text ->
            val updated = replaceTextIfChanged(text, query, replacement)
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
        replacement: String
    ): ReplaceAllInTextResult {
        val newContent = content.replace(Regex(Regex.escape(query)), replacement)
        val matchCount = content.split(
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        ).size - 1
        return ReplaceAllInTextResult(newContent, matchCount)
    }
}
