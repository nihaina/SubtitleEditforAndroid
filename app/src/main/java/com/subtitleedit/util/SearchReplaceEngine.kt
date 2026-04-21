package com.subtitleedit.util

class SearchReplaceEngine {
    var query: String = ""
        private set

    var results: List<Int> = emptyList()
        private set

    var currentIndex: Int = -1
        private set

    fun setQueryIfChanged(newQuery: String): Boolean {
        if (newQuery == query) return false
        query = newQuery
        return true
    }

    fun setResults(
        newResults: List<Int>,
        preferredResultValue: Int? = null,
        preferredIndex: Int? = null
    ) {
        results = newResults
        if (newResults.isEmpty()) {
            currentIndex = -1
            return
        }

        currentIndex = when {
            preferredResultValue != null -> {
                val exact = newResults.indexOf(preferredResultValue)
                if (exact >= 0) {
                    exact
                } else {
                    val next = newResults.indexOfFirst { it >= preferredResultValue }
                    if (next >= 0) next else newResults.lastIndex
                }
            }
            preferredIndex != null -> preferredIndex.coerceIn(0, newResults.lastIndex)
            else -> 0
        }
    }

    fun clearResults() {
        results = emptyList()
        currentIndex = -1
    }

    fun clearAll() {
        query = ""
        clearResults()
    }

    fun hasSearchContext(): Boolean {
        return query.isNotEmpty() && results.isNotEmpty()
    }

    fun currentResultPositionOrNull(): Int? {
        if (currentIndex !in results.indices) return null
        return results[currentIndex]
    }

    fun moveToPrevious(): Int? {
        if (results.isEmpty()) return null
        currentIndex = if (currentIndex <= 0) results.size - 1 else currentIndex - 1
        return currentIndex
    }

    fun moveToNext(): Int? {
        if (results.isEmpty()) return null
        currentIndex = if (currentIndex >= results.size - 1) 0 else currentIndex + 1
        return currentIndex
    }

    fun findMatchesInText(content: String): List<Int> {
        if (query.isEmpty() || content.isEmpty()) return emptyList()
        val matches = mutableListOf<Int>()
        var index = content.indexOf(query, ignoreCase = true)
        while (index >= 0) {
            matches.add(index)
            index = content.indexOf(query, index + 1, ignoreCase = true)
        }
        return matches
    }
}
