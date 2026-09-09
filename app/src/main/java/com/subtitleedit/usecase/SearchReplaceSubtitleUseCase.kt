package com.subtitleedit.usecase

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SearchReplaceOps

internal class SearchReplaceSubtitleUseCase {
    fun replaceFirstText(
        originalText: String,
        query: String,
        replacement: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): String? = SearchReplaceOps.replaceFirstTextIfChanged(
        originalText,
        query,
        replacement,
        matchCase,
        wholeWord
    )

    fun replaceInContentAt(
        content: String,
        start: Int,
        queryLength: Int,
        replacement: String
    ): String? = SearchReplaceOps.replaceInContentAt(content, start, queryLength, replacement)

    fun replaceAllInContent(
        content: String,
        query: String,
        replacement: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): SearchReplaceOps.ReplaceAllInTextResult = SearchReplaceOps.replaceAllInContent(
        content,
        query,
        replacement,
        matchCase,
        wholeWord
    )

    fun collectEntryUpdates(
        entries: List<SubtitleEntry>,
        query: String,
        replacement: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): List<SearchReplaceOps.TextUpdate> = SearchReplaceOps.collectTextUpdates(
        entries.map { it.text },
        query,
        replacement,
        matchCase,
        wholeWord
    )

    fun countMatches(
        content: String,
        query: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): Int = SearchReplaceOps.countMatches(content, query, matchCase, wholeWord)
}
