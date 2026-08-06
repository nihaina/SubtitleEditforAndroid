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
    private var applyingSourceReplacement = false
    private var applyingEntryReplacement = false

    init {
        bindSearchBar()
        bindSourceChanges()
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
        if (position == null || !content.regionMatches(position, query, 0, query.length, ignoreCase = true)) {
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
            replacement = binding.etReplace.text?.toString().orEmpty()
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
            replacement = binding.etReplace.text?.toString().orEmpty()
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
            replacement = binding.etReplace.text?.toString().orEmpty()
        )
        val matchCount = texts.sumOf { SearchReplaceOps.countMatches(it, query) }
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
            return
        }
        engine.setResults(
            newResults = engine.findMatchesInText(content),
            preferredResultValue = preferredResultPosition
        )
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
            return
        }
        val previousResultEntries = listResultEntries
        val previousIndex = engine.currentIndex
        val matchingEntries = entries().mapIndexedNotNull { index, entry ->
            val matchesText = entry.text.contains(query, ignoreCase = true)
            val matchesTime = TimeUtils.formatForDisplay(entry.startTime)
                .contains(query, ignoreCase = true)
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
        announceResults()
        scrollToCurrentResult()
    }

    private fun moveToNext() {
        if (engine.moveToNext() == null) return
        announceResults()
        scrollToCurrentResult()
    }

    private fun scrollToCurrentResult() {
        val position = engine.currentResultPositionOrNull() ?: return
        if (isSourceViewMode()) {
            highlightSourceResults(scrollToCurrent = true)
        } else {
            binding.rvSubtitles.scrollToPosition(position)
            subtitleAdapter.highlightSearchResult(position, engine.query)
        }
    }

    private fun highlightCurrentListResult() {
        val position = engine.currentResultPositionOrNull()
        if (position == null) {
            subtitleAdapter.clearSearchHighlight()
        } else {
            subtitleAdapter.highlightSearchResult(position, engine.query)
        }
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
    }

    private fun isSearchVisible(): Boolean = binding.searchBar.isVisible

    private fun inputMethodManager(): InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
}
