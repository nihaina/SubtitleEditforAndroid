package com.subtitleedit.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AiTranslationProtocolTest {

    private fun translator(contextWindowTokens: Int = DEFAULT_AI_CONTEXT_WINDOW_TOKENS) =
        AiTranslator(
            provider = AiProviderConfig.CUSTOM,
            apiKey = "test-key",
            model = "test-model",
            targetLanguage = "中文",
            baseUrl = "https://example.com/v1",
            contextWindowTokens = contextWindowTokens
        )

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
    fun contextWindow_defaultsToTwoHundredFiftySixK() {
        assertEquals(262_144, DEFAULT_AI_CONTEXT_WINDOW_TOKENS)
    }

    @Test
    fun contextWithinBudget_isKeptUnchanged() {
        val state = AiTranslator.ConversationState(
            listOf(
                AiTranslator.ChatMessage("system", buildTranslationSystemPrompt("中文")),
                AiTranslator.ChatMessage("user", "1.hello"),
                AiTranslator.ChatMessage("assistant", "1.你好")
            )
        )

        assertEquals(
            state,
            translator().compactConversationForRequest(state, "2.world")
        )
    }

    @Test
    fun contextOverBudget_compactsOldPairsAndKeepsRecentOrder() {
        val messages = mutableListOf(
            AiTranslator.ChatMessage("system", buildTranslationSystemPrompt("中文"))
        )
        repeat(12) { batchIndex ->
            val start = batchIndex * 120 + 1
            val source = numberedTestContent(start, 120, "这是用于上下文压缩测试的较长字幕内容")
            val translation = numberedTestContent(start, 120, "这是对应的较长翻译结果内容")
            messages += AiTranslator.ChatMessage("user", source)
            messages += AiTranslator.ChatMessage("assistant", translation)
        }
        val translator = translator(contextWindowTokens = 32_768)
        val pending = numberedTestContent(1_441, 120, "这是等待翻译的较长字幕内容")

        val compacted = translator.compactConversationForRequest(
            AiTranslator.ConversationState(messages),
            pending
        )
        val history = compacted.messages.drop(1)

        assertTrue(compacted.messages.size < messages.size)
        assertEquals("system", compacted.messages.first().role)
        assertTrue(history.chunked(2).all { pair ->
            pair.size == 2 && pair[0].role == "user" && pair[1].role == "assistant"
        })
        assertTrue(history.last().content.contains("1440."))
        assertFalse(history.first().content.lineSequence().first().startsWith("1."))
        assertTrue(
            translator.estimateConversationTokens(compacted.messages +
                AiTranslator.ChatMessage("user", pending)) < 32_768
        )
    }

    @Test
    fun oversizedCurrentBatch_isRejectedInsteadOfSilentlyTruncated() {
        val oversizedBatch = numberedTestContent(
            startNumber = 1,
            count = 300,
            text = "超长字幕内容".repeat(30)
        )

        assertThrows(IOException::class.java) {
            translator(contextWindowTokens = MIN_AI_CONTEXT_WINDOW_TOKENS)
                .compactConversationForRequest(AiTranslator.ConversationState(), oversizedBatch)
        }
    }

    @Test
    fun streamDoneEvent_finishesBeforeTrailingTransportContent() {
        val response = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"1.你好\"}}]}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
            appendLine("data: invalid trailing content")
        }.toResponseBody("text/event-stream".toMediaType())
        val deltas = mutableListOf<String>()
        var captured: Pair<String, Boolean>? = null

        val content = response.use {
            translator().readStreamingContent(
                responseBody = it,
                onCaptured = { text, complete -> captured = text to complete },
                onDelta = deltas::add
            )
        }

        assertEquals("1.你好", content)
        assertEquals(listOf("1.你好"), deltas)
        assertEquals("1.你好" to true, captured)
    }

    @Test
    fun streamLengthFinish_isRejectedAsIncomplete() {
        val response = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"1.半截\"}}]}")
            appendLine()
            appendLine("data: {\"choices\":[{\"finish_reason\":\"length\",\"delta\":{}}]}")
            appendLine()
            appendLine("data: [DONE]")
        }.toResponseBody("text/event-stream".toMediaType())
        var captured: Pair<String, Boolean>? = null

        assertThrows(IOException::class.java) {
            response.use {
                translator().readStreamingContent(
                    responseBody = it,
                    onCaptured = { text, complete -> captured = text to complete }
                ) {}
            }
        }
        assertEquals("1.半截" to false, captured)
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

    private fun numberedTestContent(startNumber: Int, count: Int, text: String): String =
        List(count) { index -> "${startNumber + index}.$text" }.joinToString("\n")
}
