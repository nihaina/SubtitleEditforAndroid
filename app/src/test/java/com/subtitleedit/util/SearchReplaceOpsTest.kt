package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchReplaceOpsTest {

    @Test
    fun replaceFirstTextIfChanged_replacesOnlyFirstMatch() {
        assertEquals(
            "x a A",
            SearchReplaceOps.replaceFirstTextIfChanged("a a A", "a", "x")
        )
    }

    @Test
    fun replaceFirstTextIfChanged_treatsReplacementLiterally() {
        assertEquals(
            "$5 a",
            SearchReplaceOps.replaceFirstTextIfChanged("a a", "a", "$5")
        )
    }

    @Test
    fun replaceFirstTextIfChanged_respectsMatchCase() {
        assertEquals(
            "Cat x",
            SearchReplaceOps.replaceFirstTextIfChanged(
                "Cat cat",
                "cat",
                "x",
                matchCase = true
            )
        )
    }

    @Test
    fun countMatches_countsLiteralCaseInsensitiveMatches() {
        assertEquals(3, SearchReplaceOps.countMatches("a A a.c", "a"))
        assertEquals(1, SearchReplaceOps.countMatches("a.c abc", "a.c"))
        assertEquals(0, SearchReplaceOps.countMatches("abc", ""))
    }

    // ==================== replaceTextIfChanged ====================

    @Test
    fun replaceTextIfChanged_caseInsensitive() {
        assertEquals(
            "Hello Kotlin",
            SearchReplaceOps.replaceTextIfChanged("Hello World", "world", "Kotlin")
        )
    }

    @Test
    fun replaceTextIfChanged_replacesAllOccurrences() {
        assertEquals("xxx", SearchReplaceOps.replaceTextIfChanged("aAa", "a", "x"))
    }

    @Test
    fun replaceTextIfChanged_noMatchReturnsNull() {
        assertNull(SearchReplaceOps.replaceTextIfChanged("Hello", "xyz", "!"))
    }

    @Test
    fun replaceTextIfChanged_unchangedResultReturnsNull() {
        assertNull(SearchReplaceOps.replaceTextIfChanged("abc", "b", "b"))
        // 大小写不敏感命中，但替换结果与原文相同
        assertNull(SearchReplaceOps.replaceTextIfChanged("Hello", "hello", "Hello"))
    }

    @Test
    fun replaceTextIfChanged_dollarInReplacementIsLiteral() {
        // 回归测试：旧实现把替换串直接交给 Regex 替换，"$5" 会被当作组引用抛异常
        assertEquals(
            "the $5 is high",
            SearchReplaceOps.replaceTextIfChanged("the cost is high", "cost", "$5")
        )
    }

    @Test
    fun replaceTextIfChanged_backslashInReplacementIsLiteral() {
        assertEquals("a c\\d", SearchReplaceOps.replaceTextIfChanged("a b", "b", "c\\d"))
    }

    // ==================== collectTextUpdates ====================

    @Test
    fun collectTextUpdates_onlyChangedIndices() {
        val updates = SearchReplaceOps.collectTextUpdates(
            listOf("apple", "banana", "grape"),
            "an",
            "AN"
        )
        assertEquals(1, updates.size)
        assertEquals(1, updates[0].index)
        assertEquals("bANANa", updates[0].newText)
    }

    @Test
    fun collectTextUpdates_emptyQueryReturnsEmpty() {
        assertEquals(
            emptyList<SearchReplaceOps.TextUpdate>(),
            SearchReplaceOps.collectTextUpdates(listOf("a", "b"), "", "x")
        )
    }

    // ==================== replaceInContentAt ====================

    @Test
    fun replaceInContentAt_replacesRange() {
        assertEquals("hello kotlin", SearchReplaceOps.replaceInContentAt("hello world", 6, 5, "kotlin"))
        assertEquals("x", SearchReplaceOps.replaceInContentAt("abc", 0, 3, "x"))
    }

    @Test
    fun replaceInContentAt_invalidRangesReturnNull() {
        assertNull(SearchReplaceOps.replaceInContentAt("abc", -1, 1, "x"))
        assertNull(SearchReplaceOps.replaceInContentAt("abc", 3, 1, "x"))
        assertNull(SearchReplaceOps.replaceInContentAt("abc", 2, 5, "x"))
        assertNull(SearchReplaceOps.replaceInContentAt("abc", 0, 0, "x"))
    }

    // ==================== replaceAllInContent ====================

    @Test
    fun replaceAllInContent_countsAndReplacesCaseInsensitive() {
        val result = SearchReplaceOps.replaceAllInContent("aAbA", "a", "x")
        assertEquals(3, result.matchCount)
        assertEquals("xxbx", result.newContent)
    }

    @Test
    fun replaceAllInContent_queryRegexCharsAreLiteral() {
        val result = SearchReplaceOps.replaceAllInContent("a.c and abc", "a.c", "X")
        assertEquals(1, result.matchCount)
        assertEquals("X and abc", result.newContent)
    }

    @Test
    fun replaceAllInContent_dollarReplacementIsLiteral() {
        val result = SearchReplaceOps.replaceAllInContent("total cost", "cost", "$9")
        assertEquals(1, result.matchCount)
        assertEquals("total $9", result.newContent)
    }

    @Test
    fun replaceAllInContent_noMatch() {
        val result = SearchReplaceOps.replaceAllInContent("abc", "zzz", "x")
        assertEquals(0, result.matchCount)
        assertEquals("abc", result.newContent)
    }

    @Test
    fun replaceAllInContent_respectsWholeWord() {
        val result = SearchReplaceOps.replaceAllInContent(
            content = "cat scatter cat",
            query = "cat",
            replacement = "x",
            wholeWord = true
        )
        assertEquals(2, result.matchCount)
        assertEquals("x scatter x", result.newContent)
    }
}
