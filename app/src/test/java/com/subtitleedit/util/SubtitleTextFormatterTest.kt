package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTextFormatterTest {

    @Test
    fun defaultOptions_isNoOp() {
        val options = SubtitleFormattingOptions()
        assertEquals("Hello, world!", SubtitleTextFormatter.format("Hello, world!", options))
    }

    // ==================== removeSpaces ====================

    @Test
    fun removeSpaces_stripsHalfAndFullWidthSpaces() {
        val options = SubtitleFormattingOptions(removeSpaces = true)
        assertEquals("abcd", SubtitleTextFormatter.format("a b\tc　d", options))
    }

    @Test
    fun removeSpaces_preservesLineBreaks() {
        val options = SubtitleFormattingOptions(removeSpaces = true)
        assertEquals("ab\ncd", SubtitleTextFormatter.format("a b\nc d", options))
    }

    // ==================== innerPunctuation ====================

    @Test
    fun innerPunctuation_removedButTrailingRunProtected() {
        val options = SubtitleFormattingOptions(innerPunctuation = setOf('，'))
        assertEquals("你好世界，", SubtitleTextFormatter.format("你好，世界，", options))
    }

    @Test
    fun innerPunctuation_allRemovedWhenNoTrailingRun() {
        val options = SubtitleFormattingOptions(innerPunctuation = setOf(','))
        assertEquals("abc", SubtitleTextFormatter.format("a,b,c", options))
    }

    // ==================== endPunctuation ====================

    @Test
    fun endPunctuation_stripsTrailingRun() {
        val options = SubtitleFormattingOptions(endPunctuation = setOf('!'))
        assertEquals("Hello", SubtitleTextFormatter.format("Hello!!", options))
    }

    @Test
    fun endPunctuation_preservesClosingQuote() {
        val options = SubtitleFormattingOptions(endPunctuation = setOf('!'))
        assertEquals("Hi”", SubtitleTextFormatter.format("Hi!”", options))
    }

    @Test
    fun endPunctuation_handlesTrailingWhitespace() {
        val options = SubtitleFormattingOptions(endPunctuation = setOf('。'))
        assertEquals("Hi", SubtitleTextFormatter.format("Hi。 ", options))
    }

    @Test
    fun endPunctuation_canClearPunctuationOnlyText() {
        val options = SubtitleFormattingOptions(endPunctuation = setOf('!', '。'))
        assertEquals("", SubtitleTextFormatter.format("!。!!", options))
    }

    // ==================== addEndPunctuation ====================

    @Test
    fun addEndPunctuation_appendsWhenMissing() {
        val options = SubtitleFormattingOptions(addEndPunctuation = "。")
        assertEquals("Hi。", SubtitleTextFormatter.format("Hi", options))
    }

    @Test
    fun addEndPunctuation_noDuplicate() {
        val options = SubtitleFormattingOptions(addEndPunctuation = "。")
        assertEquals("Hi。", SubtitleTextFormatter.format("Hi。", options))
    }

    @Test
    fun addEndPunctuation_insertedBeforeClosingBracket() {
        val options = SubtitleFormattingOptions(addEndPunctuation = "。")
        assertEquals("Hi。」", SubtitleTextFormatter.format("Hi」", options))
    }

    @Test
    fun addEndPunctuation_skipsBlankLines() {
        val options = SubtitleFormattingOptions(addEndPunctuation = "。")
        assertEquals("", SubtitleTextFormatter.format("", options))
    }

    // ==================== 标点替换 ====================

    @Test
    fun replaceInner_basic() {
        val options = SubtitleFormattingOptions(
            replaceFrom = ".",
            replaceTo = "-",
            replacementScope = PunctuationReplacementScope.INNER
        )
        assertEquals("1-2-3", SubtitleTextFormatter.format("1.2.3", options))
    }

    @Test
    fun replaceInner_protectsTrailingRun() {
        val options = SubtitleFormattingOptions(
            replaceFrom = ".",
            replaceTo = ",",
            replacementScope = PunctuationReplacementScope.INNER
        )
        assertEquals("a,b..", SubtitleTextFormatter.format("a.b..", options))
    }

    @Test
    fun replaceEnd_onlyAffectsLineEnd() {
        val options = SubtitleFormattingOptions(
            replaceFrom = ".",
            replaceTo = "!",
            replacementScope = PunctuationReplacementScope.END
        )
        assertEquals("a.b!", SubtitleTextFormatter.format("a.b.", options))
        assertEquals("abc", SubtitleTextFormatter.format("abc", options))
    }

    @Test
    fun replaceEnd_worksThroughClosingBracket() {
        val options = SubtitleFormattingOptions(
            replaceFrom = ".",
            replaceTo = "!",
            replacementScope = PunctuationReplacementScope.END
        )
        assertEquals("abc!」", SubtitleTextFormatter.format("abc.」", options))
    }

    // ==================== 多行与组合 ====================

    @Test
    fun format_appliesPerLine() {
        val options = SubtitleFormattingOptions(endPunctuation = setOf('!'))
        assertEquals("Hello\nWorld", SubtitleTextFormatter.format("Hello!\nWorld!", options))
    }

    @Test
    fun format_combinesRemoveSpacesAndAddEnd() {
        val options = SubtitleFormattingOptions(removeSpaces = true, addEndPunctuation = "。")
        assertEquals("Helloworld。", SubtitleTextFormatter.format("Hello world", options))
    }
}
