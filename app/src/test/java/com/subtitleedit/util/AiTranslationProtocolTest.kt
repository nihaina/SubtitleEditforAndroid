package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class AiTranslationProtocolTest {

    @Test
    fun systemPrompt_containsOnlyRequestedInstruction() {
        assertEquals("帮我翻译成中文，以原格式输出", buildTranslationSystemPrompt("中文"))
    }

    @Test
    fun batches_areAlwaysSplitAtThreeHundredLines() {
        val batches = splitSubtitleTranslationBatches(List(601) { "字幕${it + 1}" })

        assertEquals(listOf(300, 300, 1), batches.map(List<String>::size))
    }

    @Test
    fun numberedInput_usesGlobalDotNumberingAndEscapesEmbeddedLines() {
        val content = buildNumberedSubtitleContent(
            listOf("字幕301", "两行\n字幕"),
            startNumber = 301
        )

        assertEquals("301.字幕301\n302.两行\\n字幕", content)
    }

    @Test
    fun parser_filtersCommentaryAndPreservesBlankNumberedRows() {
        val content = """
            以下是翻译结果：
            325.字幕325
            326.
            327.字幕327
            希望这些内容有帮助。
        """.trimIndent()

        assertEquals(
            listOf("字幕325", "", "字幕327"),
            parseNumberedTranslation(content, expectedStartNumber = 325, expectedCount = 3)
        )
    }

    @Test
    fun parser_acceptsLegacyBracketNumbersAndRestoresEmbeddedLines() {
        assertEquals(
            listOf("第一行\n第二行"),
            parseNumberedTranslation("[301] 第一行\\n第二行", 301, 1)
        )
    }

    @Test
    fun parser_rejectsMissingExpectedNumber() {
        assertThrows(IOException::class.java) {
            parseNumberedTranslation("301.字幕301\n303.字幕303", 301, 3)
        }
    }

    @Test
    fun parser_rejectsDuplicateExpectedNumber() {
        assertThrows(IOException::class.java) {
            parseNumberedTranslation("301.字幕301\n301.重复", 301, 1)
        }
    }

    @Test
    fun cancelledStream_keepsOnlyCompletedContinuousNumberedPrefix() {
        val completedLines = """
            翻译如下：
            301.字幕301
            302.
            304.字幕304
        """.trimIndent() + "\n"

        assertEquals(
            listOf("字幕301", ""),
            parseCompletedTranslationPrefix(completedLines, 301, 4)
        )
    }

    @Test
    fun cancelledStream_doesNotShiftResultsPastMissingFirstNumber() {
        assertEquals(
            emptyList<String>(),
            parseCompletedTranslationPrefix("302.字幕302\n", 301, 2)
        )
    }
}
