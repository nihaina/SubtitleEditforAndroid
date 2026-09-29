package com.subtitleedit.editor

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.subtitleedit.R
import com.subtitleedit.adapter.SubtitleAdapter
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SearchResultRetention
import com.subtitleedit.util.SearchReplaceEngine
import com.subtitleedit.util.SearchReplaceOps
import com.subtitleedit.util.SearchTextMatcher
import com.subtitleedit.util.TimeUtils
import com.subtitleedit.usecase.SearchReplaceSubtitleUseCase
import com.subtitleedit.ui.EditorSourceEditorState
import com.subtitleedit.ui.EditorSourceHighlight

/** Coordinates the editor search bar without owning the editor document. */
internal class EditorSearchController(
    private val context: Context,
    private val sourceEditorState: EditorSourceEditorState,
    private val subtitleAdapter: SubtitleAdapter,
    private val scrollListToPosition: (Int) -> Unit,
    private val isSourceViewMode: () -> Boolean,
    private val ignoreSourceChanges: () -> Boolean,
    private val entries: () -> List<SubtitleEntry>,
    private val replaceSourceContent: (String) -> Unit,
    private val applyEntryUpdates: (List<com.subtitleedit.util.SearchReplaceOps.TextUpdate>) -> Int,
    private val confirmReplaceAll: (matchCount: Int, onConfirm: () -> Unit) -> Unit,
    private val showMessage: (String) -> Unit
) {
    var uiState by mutableStateOf(
        EditorSearchUiState(resultCount = context.getString(R.string.search_result_position_empty))
    )
        private set

    private val engine = SearchReplaceEngine()
    private val searchReplaceSubtitle = SearchReplaceSubtitleUseCase()
    private var listResultEntries: List<SubtitleEntry> = emptyList()
    private var matchCase = false
    private var wholeWord = false
    private var applyingSourceReplacement = false
    private var applyingEntryReplacement = false

    private companion object {
        // 单字符搜索可能命中数万处；只为有限数量的结果设置 Span，避免搜索本身耗尽内存。
        const val MAX_SOURCE_HIGHLIGHT_SPANS = 250
    }

    init {
        bindSourceChanges()
        updateSearchOptionButtons()
        updateResultCount()
    }

    fun show() {
        clearSearchState()
        uiState = uiState.copy(visible = true, query = "", replacement = "")
    }

    fun hide() {
        clearSearchState()
        uiState = uiState.copy(visible = false, query = "", replacement = "")
    }

    fun onQueryChanged(query: String) {
        uiState = uiState.copy(query = query)
        if (!engine.setQueryIfChanged(query)) return
        if (query.isEmpty()) {
            engine.clearResults()
            listResultEntries = emptyList()
            clearHighlights()
            updateResultCount()
        } else {
            performSearch()
        }
    }

    fun onReplacementChanged(replacement: String) {
        uiState = uiState.copy(replacement = replacement)
    }

    fun searchPrevious() = moveToPrevious()
    fun searchNext() = moveToNext()
    fun replaceOneFromUi() = replaceOne()
    fun replaceAllFromUi() = replaceAll()

    fun toggleMatchCase() {
        matchCase = !matchCase
        updateSearchOptionButtons()
        showMessage(context.getString(R.string.search_match_case_status, optionStateText(matchCase)))
        refreshSearchAfterOptionChanged()
    }

    fun toggleWholeWord() {
        wholeWord = !wholeWord
        updateSearchOptionButtons()
        showMessage(context.getString(R.string.search_whole_word_status, optionStateText(wholeWord)))
        refreshSearchAfterOptionChanged()
    }

    fun onEditorModeChanged() {
        if (!isSearchVisible() || engine.query.isEmpty()) {
            clearHighlights()
            return
        }
        performSearch(announce = false)
    }

    fun onDocumentChanged() {
        if (applyingEntryReplacement || !isSearchVisible() || engine.query.isEmpty()) return
        performSearch(
            announce = false,
            scrollToCurrent = false,
            retainCurrentListResult = true
        )
    }

    /** 在源码视图切换前丢弃搜索结果和高亮，避免旧 Spannable 继续保留大文本。 */
    fun clearSourceWorkForTransition() {
        applyingSourceReplacement = false
        engine.clearResults()
        clearSourceHighlights()
        updateResultCount()
    }

    private fun bindSourceChanges() {
        val listener = {
            if (!applyingSourceReplacement && !ignoreSourceChanges() && isSourceViewMode() &&
                isSearchVisible() && engine.query.isNotEmpty()
            ) {
                searchInSourceView(announce = false, scrollToCurrent = false)
            }
        }
        sourceEditorState.addOnDocumentChangedListener(listener)
    }

    private fun sourceDocumentText(): String = sourceEditorState.getDocumentText()

    private fun setSourceSearchHighlights(ranges: List<EditorSourceHighlight>) {
        sourceEditorState.setSearchHighlights(ranges)
    }

    private fun clearSourceSearchHighlights() {
        sourceEditorState.clearSearchHighlights()
    }

    private fun scrollSourceToOffset(offset: Int) {
        sourceEditorState.scrollToDocumentOffset(offset)
    }

    private fun replaceOne() {
        if (!engine.hasSearchContext()) {
            showMessage("请先搜索内容")
            return
        }
        if (isSourceViewMode()) replaceOneInSourceView() else replaceOneInListView()
    }

    private fun replaceOneInSourceView() {
        val content = sourceDocumentText()
        val position = engine.currentResultPositionOrNull()
        val query = engine.query
        if (
            position == null ||
            !SearchTextMatcher.isMatchAt(content, position, query, matchCase, wholeWord)
        ) {
            searchInSourceView(announce = false)
            showMessage("当前匹配项已变化，请重试")
            return
        }

        val newContent = searchReplaceSubtitle.replaceInContentAt(
            content = content,
            start = position,
            queryLength = query.length,
            replacement = uiState.replacement
        ) ?: return

        applyingSourceReplacement = true
        try {
            replaceSourceContent(newContent)
        } finally {
            applyingSourceReplacement = false
        }
        searchInSourceView(preferredResultPosition = position, announce = false, scrollToCurrent = false)
        showMessage("已替换 1 处")
    }

    private fun replaceOneInListView() {
        val currentIndex = engine.currentIndex
        val position = engine.currentResultPositionOrNull()
        val entry = position?.let { entries().getOrNull(it) }
        if (position == null || entry == null) {
            showMessage("没有可替换的匹配项")
            return
        }

        val newText = searchReplaceSubtitle.replaceFirstText(
            originalText = entry.text,
            query = engine.query,
            replacement = uiState.replacement,
            matchCase = matchCase,
            wholeWord = wholeWord
        )
        if (newText == null) {
            showMessage("当前项的文本中没有可替换内容")
            moveToNext()
            return
        }

        val removedCount: Int
        applyingEntryReplacement = true
        try {
            removedCount = applyEntryUpdates(listOf(SearchReplaceOps.TextUpdate(position, newText)))
        } finally {
            applyingEntryReplacement = false
        }
        searchInListView(
            preferredResultPosition = position,
            preferredIndex = currentIndex,
            announce = false,
            scrollToCurrent = false
        )
        showMessage(
            if (removedCount > 0) "替换后文本为空，已删除该条字幕" else "已替换 1 处"
        )
    }

    private fun replaceAll() {
        val query = engine.query
        if (query.isEmpty()) {
            showMessage("请先搜索内容")
            return
        }
        if (isSourceViewMode()) replaceAllInSourceView(query) else replaceAllInListView(query)
    }

    private fun replaceAllInSourceView(query: String) {
        val result = searchReplaceSubtitle.replaceAllInContent(
            content = sourceDocumentText(),
            query = query,
            replacement = uiState.replacement,
            matchCase = matchCase,
            wholeWord = wholeWord
        )
        if (result.matchCount == 0) {
            showMessage("没有找到可替换的内容")
            return
        }

        confirmReplaceAll(result.matchCount) {
            applyingSourceReplacement = true
            try {
                replaceSourceContent(result.newContent)
            } finally {
                applyingSourceReplacement = false
            }
            clearSearchInputAfterReplace()
            showMessage("已替换 ${result.matchCount} 处")
        }
    }

    private fun replaceAllInListView(query: String) {
        val texts = entries().map { it.text }
        val updates = searchReplaceSubtitle.collectEntryUpdates(
            entries = entries(),
            query = query,
            replacement = uiState.replacement,
            matchCase = matchCase,
            wholeWord = wholeWord
        )
        val matchCount = texts.sumOf {
            searchReplaceSubtitle.countMatches(it, query, matchCase, wholeWord)
        }
        if (updates.isEmpty() || matchCount == 0) {
            showMessage("没有找到可替换的内容")
            return
        }

        confirmReplaceAll(matchCount) {
            val removedCount: Int
            applyingEntryReplacement = true
            try {
                removedCount = applyEntryUpdates(updates)
            } finally {
                applyingEntryReplacement = false
            }
            clearSearchInputAfterReplace()
            showMessage(
                if (removedCount > 0) {
                    "已替换 $matchCount 处，删除 $removedCount 条空字幕"
                } else {
                    "已替换 $matchCount 处"
                }
            )
        }
    }

    private fun performSearch(
        announce: Boolean = true,
        scrollToCurrent: Boolean = true,
        retainCurrentListResult: Boolean = false
    ) {
        if (isSourceViewMode()) {
            searchInSourceView(announce = announce, scrollToCurrent = scrollToCurrent)
        } else {
            searchInListView(
                announce = announce,
                scrollToCurrent = scrollToCurrent,
                retainCurrentResult = retainCurrentListResult
            )
        }
    }

    private fun searchInSourceView(
        preferredResultPosition: Int? = null,
        announce: Boolean = true,
        scrollToCurrent: Boolean = true
    ) {
        val content = sourceDocumentText()
        listResultEntries = emptyList()
        if (engine.query.isEmpty() || content.isEmpty()) {
            engine.clearResults()
            clearSourceHighlights()
            updateResultCount()
            return
        }
        engine.setResults(
            newResults = engine.findMatchesInText(content, matchCase, wholeWord),
            preferredResultValue = preferredResultPosition
        )
        updateResultCount()
        if (announce) announceResults()
        highlightSourceResults(scrollToCurrent)
    }

    private fun searchInListView(
        preferredResultPosition: Int? = null,
        preferredIndex: Int? = null,
        announce: Boolean = true,
        scrollToCurrent: Boolean = true,
        retainCurrentResult: Boolean = false
    ) {
        val query = engine.query
        if (query.isEmpty()) {
            engine.clearResults()
            listResultEntries = emptyList()
            subtitleAdapter.clearSearchHighlight()
            updateResultCount()
            return
        }
        val previousResultEntries = listResultEntries
        val previousIndex = engine.currentIndex
        val matchingEntries = entries().mapIndexedNotNull { index, entry ->
            val matchesText = SearchTextMatcher.contains(entry.text, query, matchCase, wholeWord)
            val matchesTime = SearchTextMatcher.contains(
                TimeUtils.formatForDisplay(entry.startTime),
                query,
                matchCase,
                wholeWord
            )
            if (matchesText || matchesTime) index to entry else null
        }
        val results = matchingEntries.map { it.first }
        val newResultEntries = matchingEntries.map { it.second }
        val retainedIndex = if (
            retainCurrentResult &&
            preferredResultPosition == null &&
            preferredIndex == null
        ) {
            SearchResultRetention.preferredIndex(
                previousResults = previousResultEntries,
                previousIndex = previousIndex,
                newResults = newResultEntries
            )
        } else {
            null
        }
        engine.setResults(results, preferredResultPosition, preferredIndex ?: retainedIndex)
        listResultEntries = newResultEntries
        updateResultCount()
        if (announce) announceResults()
        if (scrollToCurrent) {
            scrollToCurrentResult()
        } else {
            highlightCurrentListResult()
        }
    }

    private fun highlightSourceResults(scrollToCurrent: Boolean) {
        clearSourceHighlights()
        val query = engine.query
        if (query.isEmpty() || engine.results.isEmpty()) return

        val current = engine.currentResultPositionOrNull()
        val positions = LinkedHashSet<Int>().apply {
            addAll(engine.results.take(MAX_SOURCE_HIGHLIGHT_SPANS))
            current?.let(::add)
        }
        setSourceSearchHighlights(
            positions.map { start ->
                EditorSourceHighlight(
                    start = start,
                    end = (start + query.length).coerceAtMost(sourceDocumentText().length),
                    current = start == current
                )
            }
        )
        if (scrollToCurrent) current?.let(::scrollSourceViewToOffset)
    }

    private fun clearSourceHighlights() {
        clearSourceSearchHighlights()
    }

    private fun clearHighlights() {
        clearSourceHighlights()
        subtitleAdapter.clearSearchHighlight()
    }

    private fun scrollSourceViewToOffset(offset: Int) {
        scrollSourceToOffset(offset)
    }

    private fun moveToPrevious() {
        if (engine.moveToPrevious() == null) return
        updateResultCount()
        announceResults()
        scrollToCurrentResult()
    }

    private fun moveToNext() {
        if (engine.moveToNext() == null) return
        updateResultCount()
        announceResults()
        scrollToCurrentResult()
    }

    private fun scrollToCurrentResult() {
        val position = engine.currentResultPositionOrNull() ?: return
        if (isSourceViewMode()) {
            highlightSourceResults(scrollToCurrent = true)
        } else {
            scrollListToPosition(position)
            subtitleAdapter.highlightSearchResult(
                position,
                engine.query,
                matchCase,
                wholeWord
            )
        }
    }

    private fun highlightCurrentListResult() {
        val position = engine.currentResultPositionOrNull()
        if (position == null) {
            subtitleAdapter.clearSearchHighlight()
        } else {
            subtitleAdapter.highlightSearchResult(
                position,
                engine.query,
                matchCase,
                wholeWord
            )
        }
    }

    private fun refreshSearchAfterOptionChanged() {
        if (engine.query.isEmpty()) return
        if (isSourceViewMode()) {
            searchInSourceView(
                preferredResultPosition = engine.currentResultPositionOrNull(),
                announce = false,
                scrollToCurrent = false
            )
        } else {
            performSearch(
                announce = false,
                scrollToCurrent = false,
                retainCurrentListResult = true
            )
        }
    }

    private fun updateSearchOptionButtons() {
        uiState = uiState.copy(matchCase = matchCase, wholeWord = wholeWord)
    }

    private fun optionStateText(enabled: Boolean): String = context.getString(
        if (enabled) R.string.search_option_on else R.string.search_option_off
    )

    private fun updateResultCount() {
        val current = if (engine.currentIndex in engine.results.indices) {
            engine.currentIndex + 1
        } else {
            0
        }
        uiState = uiState.copy(
            resultCount = context.getString(
                R.string.search_result_position,
                current,
                engine.results.size
            )
        )
    }

    private fun announceResults() {
        if (engine.results.isEmpty()) {
            OverwritingToast.makeText(
                context,
                context.getString(R.string.search_no_results),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            OverwritingToast.makeText(
                context,
                context.getString(
                    R.string.search_result_count,
                    engine.results.size,
                    engine.currentIndex + 1
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun clearSearchInputAfterReplace() {
        clearSearchState()
        uiState = uiState.copy(query = "")
    }

    private fun clearSearchState() {
        engine.clearAll()
        listResultEntries = emptyList()
        clearHighlights()
        updateResultCount()
    }

    private fun isSearchVisible(): Boolean = uiState.visible
}

data class EditorSearchUiState(
    val visible: Boolean = false,
    val query: String = "",
    val replacement: String = "",
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val resultCount: String = ""
)
