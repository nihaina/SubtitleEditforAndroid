package com.subtitleedit.editor

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.isVisible
import com.subtitleedit.R
import com.subtitleedit.adapter.SubtitleAdapter
import com.subtitleedit.databinding.ActivityEditorBinding
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SearchResultRetention
import com.subtitleedit.util.SearchReplaceEngine
import com.subtitleedit.util.SearchReplaceOps
import com.subtitleedit.util.SearchTextMatcher
import com.subtitleedit.util.TimeUtils

/** Coordinates the editor search bar without owning the editor document. */
class EditorSearchController(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val subtitleAdapter: SubtitleAdapter,
    private val isSourceViewMode: () -> Boolean,
    private val ignoreSourceChanges: () -> Boolean,
    private val entries: () -> List<SubtitleEntry>,
    private val replaceSourceContent: (String) -> Unit,
    private val applyEntryUpdates: (List<SearchReplaceOps.TextUpdate>) -> Int,
    private val confirmReplaceAll: (matchCount: Int, onConfirm: () -> Unit) -> Unit,
    private val showMessage: (String) -> Unit
) {
    private val engine = SearchReplaceEngine()
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
        bindSearchBar()
        bindSourceChanges()
        updateSearchOptionButtons()
        updateResultCount()
    }

    fun show() {
        clearSearchState()
        binding.searchBar.isVisible = true
        binding.etSearch.requestFocus()
        binding.etSearch.text?.clear()
        binding.etReplace.text?.clear()
        inputMethodManager().showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        binding.searchBar.isVisible = false
        clearSearchState()
        binding.etSearch.text?.clear()
        binding.etReplace.text?.clear()
        inputMethodManager().hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
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

    private fun bindSearchBar() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
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
        })

        binding.btnSearchPrevious.setOnClickListener { moveToPrevious() }
        binding.btnSearchNext.setOnClickListener { moveToNext() }
        binding.btnSearchClose.setOnClickListener { hide() }
        binding.btnReplace.setOnClickListener { replaceOne() }
        binding.btnReplaceAll.setOnClickListener { replaceAll() }
        binding.btnSearchMatchCase.setOnClickListener {
            matchCase = !matchCase
            updateSearchOptionButtons()
            showMessage(
                context.getString(
                    R.string.search_match_case_status,
                    optionStateText(matchCase)
                )
            )
            refreshSearchAfterOptionChanged()
        }
        binding.btnSearchWholeWord.setOnClickListener {
            wholeWord = !wholeWord
            updateSearchOptionButtons()
            showMessage(
                context.getString(
                    R.string.search_whole_word_status,
                    optionStateText(wholeWord)
                )
            )
            refreshSearchAfterOptionChanged()
        }
        binding.etSearch.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                performSearch()
                true
            } else {
                false
            }
        }
    }

    private fun bindSourceChanges() {
        binding.etSourceView.addOnDocumentChangedListener {
            if (!applyingSourceReplacement && !ignoreSourceChanges() && isSourceViewMode() &&
                isSearchVisible() && engine.query.isNotEmpty()
            ) {
                searchInSourceView(announce = false, scrollToCurrent = false)
            }
        }
    }

    private fun replaceOne() {
        if (!engine.hasSearchContext()) {
            showMessage("请先搜索内容")
            return
        }
        if (isSourceViewMode()) replaceOneInSourceView() else replaceOneInListView()
    }

    private fun replaceOneInSourceView() {
        val content = binding.etSourceView.getDocumentText()
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

        val newContent = SearchReplaceOps.replaceInContentAt(
            content = content,
            start = position,
            queryLength = query.length,
            replacement = binding.etReplace.text?.toString().orEmpty()
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

        val newText = SearchReplaceOps.replaceFirstTextIfChanged(
            originalText = entry.text,
            query = engine.query,
            replacement = binding.etReplace.text?.toString().orEmpty(),
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
        val result = SearchReplaceOps.replaceAllInContent(
            content = binding.etSourceView.getDocumentText(),
            query = query,
            replacement = binding.etReplace.text?.toString().orEmpty(),
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
        val updates = SearchReplaceOps.collectTextUpdates(
            texts = texts,
            query = query,
            replacement = binding.etReplace.text?.toString().orEmpty(),
            matchCase = matchCase,
            wholeWord = wholeWord
        )
        val matchCount = texts.sumOf {
            SearchReplaceOps.countMatches(it, query, matchCase, wholeWord)
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
        val content = binding.etSourceView.getDocumentText()
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
        binding.etSourceView.setSearchHighlights(
            positions.map { start ->
                com.subtitleedit.view.SourceEditorView.Highlight(
                    start = start,
                    end = (start + query.length).coerceAtMost(binding.etSourceView.getDocumentText().length),
                    current = start == current
                )
            }
        )
        if (scrollToCurrent) current?.let(::scrollSourceViewToOffset)
    }

    private fun clearSourceHighlights() {
        binding.etSourceView.clearSearchHighlights()
    }

    private fun clearHighlights() {
        clearSourceHighlights()
        subtitleAdapter.clearSearchHighlight()
    }

    private fun scrollSourceViewToOffset(offset: Int) {
        binding.etSourceView.scrollToDocumentOffset(offset)
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
            binding.rvSubtitles.scrollToPosition(position)
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
        binding.btnSearchMatchCase.isChecked = matchCase
        binding.btnSearchWholeWord.isChecked = wholeWord
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
        binding.tvSearchResultCount.text = context.getString(
            R.string.search_result_position,
            current,
            engine.results.size
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
        binding.etSearch.text?.clear()
    }

    private fun clearSearchState() {
        engine.clearAll()
        listResultEntries = emptyList()
        clearHighlights()
        updateResultCount()
    }

    private fun isSearchVisible(): Boolean = binding.searchBar.isVisible

    private fun inputMethodManager(): InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
}
