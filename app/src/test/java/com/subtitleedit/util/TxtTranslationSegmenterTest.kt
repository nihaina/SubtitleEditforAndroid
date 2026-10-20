package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtTranslationSegmenterTest {
    private val noWord: (String, Int) -> Int = { _, _ -> -1 }

    @Test
    fun split_prefersNewlineOverCloserPunctuation() {
        val text = "甲".repeat(760) + "\r\n" + "乙".repeat(39) + "，" + "丙".repeat(100)
        val segments = TxtTranslationSegmenter.split(text, wordStart = noWord)
        assertEquals("甲".repeat(760) + "\r\n", segments.first().text)
        assertEquals(text, segments.joinToString("") { it.text })
    }

    @Test
    fun split_choosesNearestPunctuationAndKeepsPunctuationRunOnLeft() {
        val text = "甲".repeat(797) + "。！？”" + "乙".repeat(6) + "，" + "丙".repeat(100)
        val segments = TxtTranslationSegmenter.split(text, wordStart = noWord)
        assertEquals("甲".repeat(797) + "。！？”", segments.first().text)
        assertEquals(text, segments.joinToString("") { it.text })
    }

    @Test
    fun split_prefersEarlierBoundaryAtEqualDistance() {
        val text = "甲".repeat(795) + "，" + "乙".repeat(10) + "。" + "丙".repeat(100)
        assertEquals("甲".repeat(795) + "，", TxtTranslationSegmenter.split(text, wordStart = noWord).first().text)
    }

    @Test
    fun split_includesRadiusEndpointsAndIgnoresMoreDistantNewlines() {
        val atLimit = "甲".repeat(850) + "\n" + "乙".repeat(100)
        assertEquals(851, TxtTranslationSegmenter.split(atLimit, wordStart = noWord).first().text.length)
        val tooFar = "甲".repeat(799) + "。" + "乙".repeat(52) + "\n" + "丙".repeat(100)
        assertEquals("甲".repeat(799) + "。", TxtTranslationSegmenter.split(tooFar, wordStart = noWord).first().text)
    }

    @Test
    fun split_countsUnicodeCodePointsButNotPunctuationOrWhitespace() {
        val text = "𠮷， \t".repeat(3) + "文"
        var receivedPivot = -1
        val segments = TxtTranslationSegmenter.split(text, maxCharacters = 3, searchRadius = 0) { _, pivot ->
            receivedPivot = pivot
            -1
        }
        // The punctuation at the third effective character is an exact boundary.
        assertEquals("𠮷， \t".repeat(2) + "𠮷，", segments.first().text)
        assertEquals(-1, receivedPivot)
        assertEquals(text, segments.joinToString("") { it.text })
        assertFalse(segments.any { it.text.startsWith('\uDFB7') || it.text.endsWith('\uD842') })
    }

    @Test
    fun split_usesWordStartToKeepWordAt800TogetherInNextSegment() {
        val text = "甲".repeat(796) + "你好世界" + "乙".repeat(10)
        var receivedPivot = -1
        val segments = TxtTranslationSegmenter.split(text) { original, pivot ->
            assertEquals(text, original)
            receivedPivot = pivot
            pivot - 1
        }
        assertEquals(799, receivedPivot)
        assertEquals("甲".repeat(796) + "你好", segments[0].text)
        assertEquals("世界" + "乙".repeat(10), segments[1].text)
        assertEquals(text, segments.joinToString("") { it.text })
    }

    @Test
    fun split_restartsCountingAfterEveryPreviousEndWithoutOverlap() {
        val text = "甲".repeat(790) + "。" + "乙".repeat(800) + "。" + "丙".repeat(810)
        val segments = TxtTranslationSegmenter.split(text, wordStart = noWord)
        assertEquals(listOf("甲".repeat(790) + "。", "乙".repeat(800) + "。", "丙".repeat(800), "丙".repeat(10)), segments.map { it.text })
        assertEquals(text, segments.joinToString("") { it.text })
    }

    @Test
    fun split_invalidOrNonProgressingWordStartsFallBackSafely() {
        for (candidate in listOf(-1, 0, 2, 100)) {
            val text = "𠮷文"
            val segments = TxtTranslationSegmenter.split(text, maxCharacters = 1, searchRadius = 0) { _, _ -> candidate }
            assertEquals(listOf("𠮷", "文"), segments.map { it.text })
        }
    }

    @Test
    fun split_doesNotSplitSurrogatePairReturnedByWordMatcher() {
        val text = "甲𠮷文丁"
        val segments = TxtTranslationSegmenter.split(text, maxCharacters = 3, searchRadius = 0) { _, _ -> 2 }
        assertEquals(listOf("甲𠮷文", "丁"), segments.map { it.text })
    }

    @Test
    fun split_preservesTrailingWhitespacePunctuationAndBlankText() {
        for (text in listOf("", "\r\n。！ \t", "甲乙丙。！？\r\n \t")) {
            val segments = TxtTranslationSegmenter.split(text, maxCharacters = 1, searchRadius = 0, wordStart = noWord)
            assertEquals(text, segments.joinToString("") { it.text })
            assertTrue(segments.all { it.text.isNotEmpty() })
        }
        val trailingWhitespace = "甲".repeat(801) + "。！？\r\n \t"
        val segments = TxtTranslationSegmenter.split(trailingWhitespace, wordStart = noWord)
        assertEquals(listOf(trailingWhitespace), segments.map { it.text })
    }

    @Test
    fun merge_keepsEveryTranslationAndDoesNotRemoveRepeatedSentences() {
        val source = listOf("甲。", "乙。").map { TxtTranslationSegmenter.Segment(it) }
        assertEquals("Same. Same.", TxtTranslationSegmenter.merge(listOf("Same.", "Same."), source))
        assertEquals("相同。相同。", TxtTranslationSegmenter.merge(listOf("相同。", "相同。"), source))
    }

    @Test
    fun merge_restoresSourceParagraphWhitespaceWithoutDuplicatingModelWhitespace() {
        val source = listOf("甲。\r\n\r\n", " 乙。").map { TxtTranslationSegmenter.Segment(it) }
        assertEquals("First.\r\n\r\n Second.", TxtTranslationSegmenter.merge(listOf("First.\n", "\nSecond."), source))
    }

    @Test
    fun merge_separatesEnglishWordsAndPreservesExistingWhitespace() {
        val source = listOf("甲", "乙").map { TxtTranslationSegmenter.Segment(it) }
        assertEquals("Hello world", TxtTranslationSegmenter.merge(listOf("Hello", "world"), source))
        assertEquals("Hello. \"World\"", TxtTranslationSegmenter.merge(listOf("Hello.", "\"World\""), source))
        assertEquals("你好世界", TxtTranslationSegmenter.merge(listOf("你好", "世界"), source))
        assertEquals("Hello\nworld", TxtTranslationSegmenter.merge(listOf("Hello\n", "world"), source))
    }

    @Test
    fun merge_isIdentityForSourceSegmentsWithPunctuationAndParagraphs() {
        val text = "甲。\r\n 乙，丙。\n\n丁！？ \t"
        val source = TxtTranslationSegmenter.split(text, maxCharacters = 2, searchRadius = 1, wordStart = noWord)
        assertEquals(text, TxtTranslationSegmenter.merge(source.map { it.text }, source))
        assertEquals("", TxtTranslationSegmenter.merge(emptyList(), emptyList()))
    }
}
