package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextMatcherTest {
    @Test
    fun contains_isCaseInsensitiveByDefault() {
        assertTrue(SearchTextMatcher.contains("Hello", "hello"))
    }

    @Test
    fun contains_canMatchCase() {
        assertFalse(SearchTextMatcher.contains("Hello", "hello", matchCase = true))
        assertTrue(SearchTextMatcher.contains("Hello", "Hello", matchCase = true))
    }

    @Test
    fun contains_wholeWordRejectsPartOfLatinWord() {
        assertFalse(SearchTextMatcher.contains("scatter", "cat", wholeWord = true))
        assertTrue(SearchTextMatcher.contains("a cat!", "cat", wholeWord = true))
    }

    @Test
    fun contains_wholeWordUsesUnicodeWordCharacters() {
        assertFalse(SearchTextMatcher.contains("そうです", "う", wholeWord = true))
        assertTrue(SearchTextMatcher.contains("「う」", "う", wholeWord = true))
    }

    @Test
    fun findMatchStarts_keepsOverlappingMatches() {
        assertEquals(listOf(0, 1), SearchTextMatcher.findMatchStarts("aaa", "aa"))
    }

    @Test
    fun queryRegexCharactersAreLiteral() {
        assertEquals(listOf(0), SearchTextMatcher.findMatchStarts("a.c abc", "a.c"))
    }
}
