package com.subtitleedit.editor

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
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
    private val entries: () -> List<SubtitleEntry>,
    private val replaceSourceContent: (String) -> Unit,
    private val applyEntryUpdates: (List<SearchReplaceOps.TextUpdate>) -> Unit,
    private val confirmReplaceAll: (matchCount: Int, onConfirm: () -> Unit) -> Unit,
    private val showMessage: (String) -> Unit
) {
    private val engine = SearchReplaceEngine()
    private val sourceHighlightSpans = mutableListOf<Any>()
    private var listResultEntries: List<SubtitleEntry> = emptyList()
    private var matchCase = false
    private var wholeWord = false
    private var applyingSourceReplacement = false
    private var applyingEntryReplacement = false

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
        binding.etSourceView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (applyingSourceReplacement || !isSourceViewMode()) return
                if (isSearchVisible() && engine.query.isNotEmpty()) {
                    searchInSourceView(announce = false, scrollToCurrent = false)
                }
            }
        })
    }

    private fun replaceOne() {
        if (!engine.hasSearchContext()) {
            showMessage("请先搜索内容")
            return
        }
        if (isSourceViewMode()) replaceOneInSourceView() else replaceOneInListView()
    }

    private fun replaceOneInSourceView() {
        val content = binding.etSourceView.text?.toString().orEmpty()
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

        applyingEntryReplacement = true
        try {
            applyEntryUpdates(listOf(SearchReplaceOps.TextUpdate(position, newText)))
        } finally {
            applyingEntryReplacement = false
        }
        searchInListView(
            preferredResultPosition = position,
            preferredIndex = currentIndex,
            announce = false,
            scrollToCurrent = false
        )
        showMessage("已替换 1 处")
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
            content = binding.etSourceView.text?.toString().orEmpty(),
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
            applyingEntryReplacement = true
            try {
                applyEntryUpdates(updates)
            } finally {
                applyingEntryReplacement = false
            }
            clearSearchInputAfterReplace()
            showMessage("已替换 $matchCount 处")
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
        val content = binding.etSourceView.text?.toString().orEmpty()
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
        val editable = binding.etSourceView.text ?: return
        val query = engine.query
        if (query.isEmpty() || engine.results.isEmpty()) return

        val normalColor = ContextCompat.getColor(context, R.color.inverse_primary)
        engine.results.forEach { start ->
            addSourceSpan(editable, BackgroundColorSpan(normalColor), start, start + query.length)
            addSourceSpan(editable, StyleSpan(Typeface.BOLD), start, start + query.length)
        }
        engine.currentResultPositionOrNull()?.let { start ->
            addSourceSpan(
                editable,
                BackgroundColorSpan(ContextCompat.getColor(context, R.color.secondary)),
                start,
                start + query.length
            )
            if (scrollToCurrent) scrollSourceViewToOffset(start)
        }
    }

    private fun addSourceSpan(editable: Editable, span: Any, start: Int, end: Int) {
        if (start !in 0 until editable.length || end > editable.length) return
        editable.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sourceHighlightSpans += span
    }

    private fun clearSourceHighlights() {
        val editable = binding.etSourceView.text
        sourceHighlightSpans.forEach { editable?.removeSpan(it) }
        sourceHighlightSpans.clear()
    }

    private fun clearHighlights() {
        clearSourceHighlights()
        subtitleAdapter.clearSearchHighlight()
    }

    private fun scrollSourceViewToOffset(offset: Int) {
        binding.etSourceView.post {
            val layout = binding.etSourceView.layout ?: return@post
            val safeOffset = offset.coerceIn(0, binding.etSourceView.length())
            val lineTop = layout.getLineTop(layout.getLineForOffset(safeOffset))
            val targetY = (lineTop - binding.svSourceView.height / 3).coerceAtLeast(0)
            binding.svSourceView.smoothScrollTo(0, targetY)
        }
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
