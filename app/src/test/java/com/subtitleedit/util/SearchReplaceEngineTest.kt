package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchReplaceEngineTest {

    private fun engineWith(results: List<Int>, query: String = "q"): SearchReplaceEngine {
        val engine = SearchReplaceEngine()
        engine.setQueryIfChanged(query)
        engine.setResults(results)
        return engine
    }

    // ==================== setQueryIfChanged ====================

    @Test
    fun setQueryIfChanged_onlyReturnsTrueOnChange() {
        val engine = SearchReplaceEngine()
        assertTrue(engine.setQueryIfChanged("abc"))
        assertFalse(engine.setQueryIfChanged("abc"))
        assertTrue(engine.setQueryIfChanged("abcd"))
        assertEquals("abcd", engine.query)
    }

    // ==================== setResults ====================

    @Test
    fun setResults_defaultSelectsFirst() {
        val engine = engineWith(listOf(10, 20, 30))
        assertEquals(0, engine.currentIndex)
        assertEquals(10, engine.currentResultPositionOrNull())
    }

    @Test
    fun setResults_emptyClearsIndex() {
        val engine = engineWith(emptyList())
        assertEquals(-1, engine.currentIndex)
        assertNull(engine.currentResultPositionOrNull())
    }

    @Test
    fun setResults_preferredResultValue_exactMatch() {
        val engine = SearchReplaceEngine()
        engine.setQueryIfChanged("q")
        engine.setResults(listOf(3, 7, 9), preferredResultValue = 7)
        assertEquals(1, engine.currentIndex)
    }

    @Test
    fun setResults_preferredResultValue_fallsToNextGreater() {
        val engine = SearchReplaceEngine()
        engine.setQueryIfChanged("q")
        engine.setResults(listOf(3, 7, 9), preferredResultValue = 5)
        assertEquals(1, engine.currentIndex)
    }

    @Test
    fun setResults_preferredResultValue_beyondAllUsesLast() {
        val engine = SearchReplaceEngine()
        engine.setQueryIfChanged("q")
        engine.setResults(listOf(3, 7, 9), preferredResultValue = 10)
        assertEquals(2, engine.currentIndex)
    }

    @Test
    fun setResults_preferredIndex_isCoerced() {
        val engine = SearchReplaceEngine()
        engine.setQueryIfChanged("q")
        engine.setResults(listOf(1, 2, 3), preferredIndex = 5)
        assertEquals(2, engine.currentIndex)
        engine.setResults(listOf(1, 2, 3), preferredIndex = -3)
        assertEquals(0, engine.currentIndex)
    }

    // ==================== 移动与循环 ====================

    @Test
    fun moveToNext_advancesAndWraps() {
        val engine = engineWith(listOf(10, 20))
        assertEquals(1, engine.moveToNext())
        assertEquals(0, engine.moveToNext())
    }

    @Test
    fun moveToPrevious_wrapsToLast() {
        val engine = engineWith(listOf(10, 20, 30))
        assertEquals(2, engine.moveToPrevious())
        assertEquals(1, engine.moveToPrevious())
    }

    @Test
    fun move_onEmptyResultsReturnsNull() {
        val engine = engineWith(emptyList())
        assertNull(engine.moveToNext())
        assertNull(engine.moveToPrevious())
    }

    // ==================== 清理与状态 ====================

    @Test
    fun clearResults_keepsQuery() {
        val engine = engineWith(listOf(1, 2))
        engine.clearResults()
        assertEquals("q", engine.query)
        assertTrue(engine.results.isEmpty())
        assertEquals(-1, engine.currentIndex)
        assertNull(engine.currentResultPositionOrNull())
    }

    @Test
    fun clearAll_resetsEverything() {
        val engine = engineWith(listOf(1, 2))
        engine.clearAll()
        assertEquals("", engine.query)
        assertTrue(engine.results.isEmpty())
        assertFalse(engine.hasSearchContext())
    }

    @Test
    fun hasSearchContext_requiresQueryAndResults() {
        assertTrue(engineWith(listOf(1)).hasSearchContext())
        assertFalse(engineWith(emptyList()).hasSearchContext())
        val noQuery = SearchReplaceEngine()
        noQuery.setResults(listOf(1))
        assertFalse(noQuery.hasSearchContext())
    }

    // ==================== findMatchesInText ====================

    @Test
    fun findMatchesInText_caseInsensitive() {
        val engine = SearchReplaceEngine()
        engine.setQueryIfChanged("ab")
        assertEquals(listOf(0, 2), engine.findMatchesInText("ABab"))
    }

    @Test
    fun findMatchesInText_findsOverlappingMatches() {
        val engine = SearchReplaceEngine()
        engine.setQueryIfChanged("aa")
        assertEquals(listOf(0, 1), engine.findMatchesInText("aaa"))
    }

    @Test
    fun findMatchesInText_respectsMatchOptions() {
        val engine = SearchReplaceEngine()
        engine.setQueryIfChanged("cat")
        assertEquals(
            listOf(12),
            engine.findMatchesInText("Cat scatter cat", matchCase = true, wholeWord = true)
        )
    }

    @Test
    fun findMatchesInText_emptyQueryOrContent() {
        val engine = SearchReplaceEngine()
        assertTrue(engine.findMatchesInText("content").isEmpty())
        engine.setQueryIfChanged("x")
        assertTrue(engine.findMatchesInText("").isEmpty())
    }
}
