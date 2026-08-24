package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
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
    fun systemPrompt_remainsUnchanged() {
        assertEquals(
            "帮我翻译成中文，以原格式输出",
            buildTranslationSystemPrompt("中文")
        )
    }

    @Test
    fun batches_areAlwaysSplitAtThreeHundredSubtitles() {
        val batches = splitSubtitleTranslationBatches(
            List(601) { index -> testSubtitle(index + 1, "字幕${index + 1}") }
        )

        assertEquals(listOf(300, 300, 1), batches.map { it.size })
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
                AiTranslator.ChatMessage("user", timedTestContent(1, 1, "hello")),
                AiTranslator.ChatMessage("assistant", timedTestContent(1, 1, "你好"))
            )
        )

        assertEquals(
            state,
            translator().compactConversationForRequest(state, timedTestContent(2, 1, "world"))
        )
    }

    @Test
    fun emptyConversation_doesNotInjectASeparateSystemMessage() {
        assertEquals(
            emptyList<AiTranslator.ChatMessage>(),
            translator().compactConversationForRequest(
                AiTranslator.ConversationState(),
                buildTranslationUserContent(listOf(testSubtitle(1)), "中文")
            ).messages
        )
    }

    @Test
    fun contextOverBudget_compactsOldPairsAndKeepsRecentOrder() {
        val messages = mutableListOf(
            AiTranslator.ChatMessage("system", buildTranslationSystemPrompt("中文"))
        )
        repeat(12) { batchIndex ->
            val start = batchIndex * 120 + 1
            val source = timedTestContent(start, 120, "这是用于上下文压缩测试的较长字幕内容")
            val translation = timedTestContent(start, 120, "这是对应的较长翻译结果内容")
            messages += AiTranslator.ChatMessage("user", source)
            messages += AiTranslator.ChatMessage("assistant", translation)
        }
        val translator = translator(contextWindowTokens = 32_768)
        val pending = timedTestContent(1_441, 120, "这是等待翻译的较长字幕内容")

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
        assertTrue(history.last().content.contains(testSubtitle(1_440).getTimeAxisSRT()))
        assertFalse(history.first().content.contains(testSubtitle(1).getTimeAxisSRT()))
        assertTrue(
            translator.estimateConversationTokens(compacted.messages +
                AiTranslator.ChatMessage("user", pending)) < 32_768
        )
    }

    @Test
    fun oversizedCurrentBatch_isRejectedInsteadOfSilentlyTruncated() {
        val oversizedBatch = buildTimedSubtitleContent(
            List(300) { index ->
                testSubtitle(index + 1, "超长字幕内容".repeat(30))
            }
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
    fun streamEvent_withMultipleJsonLines_preservesEveryDelta() {
        val response = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"第一\"}}]}")
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"行\"}}]}")
            appendLine()
            appendLine("data: [DONE]")
        }.toResponseBody("text/event-stream".toMediaType())

        val content = response.use {
            translator().readStreamingContent(responseBody = it) {}
        }

        assertEquals("第一行", content)
    }

    @Test
    fun streamLengthFinish_doesNotImposeAClientSideOutputLimit() {
        val response = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"1.半截\"}}]}")
            appendLine()
            appendLine("data: {\"choices\":[{\"finish_reason\":\"length\",\"delta\":{}}]}")
            appendLine()
            appendLine("data: [DONE]")
        }.toResponseBody("text/event-stream".toMediaType())
        var captured: Pair<String, Boolean>? = null

        val content = response.use {
            translator().readStreamingContent(
                responseBody = it,
                onCaptured = { text, complete -> captured = text to complete }
            ) {}
        }

        assertEquals("1.半截", content)
        assertEquals("1.半截" to true, captured)
    }

    @Test
    fun streamedEofWithoutDone_isCapturedAsIncomplete() {
        val response = "data: {\"choices\":[{\"delta\":{\"content\":\"未完成\"}}]}\n\n"
            .toResponseBody("text/event-stream".toMediaType())
        var captured: Pair<String, Boolean>? = null

        val content = response.use {
            translator().readStreamingContent(
                responseBody = it,
                onCaptured = { text, complete -> captured = text to complete }
            ) {}
        }

        assertEquals("未完成", content)
        assertEquals("未完成" to false, captured)
    }

    @Test
    fun timedInput_usesSrtBlocksWithoutSequenceNumbersAndPreservesEmbeddedLines() {
        val content = buildTimedSubtitleContent(
            listOf(
                SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "字幕一"),
                SubtitleEntry(startTime = 3_000, endTime = 4_000, text = "两行\n字幕")
            )
        )

        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,000\n字幕一\n\n" +
                "2\n00:00:03,000 --> 00:00:04,000\n两行\n字幕",
            content
        )
        assertFalse(content.contains("1.字幕一"))
    }

    @Test
    fun userContent_appendsTranslationInstructionAfterOriginalSubtitleFormat() {
        val subtitles = listOf(testSubtitle(1, "hello"))

        assertEquals(
            "1\n00:00:02,000 --> 00:00:03,500\nhello\n\n翻译成中文，以原格式输出",
            buildTranslationUserContent(subtitles, "中文")
        )
    }

    @Test
    fun parser_matchesByTimeAndPreservesBlankSubtitleBlocks() {
        val expected = listOf(
            testSubtitle(325, "source one"),
            testSubtitle(326, "source two"),
            testSubtitle(327, "source three")
        )
        val content = """
            以下是翻译结果：
            ${expected[0].getTimeAxisSRT()}
            字幕325

            ${expected[1].getTimeAxisSRT()}

            ${expected[2].getTimeAxisSRT()}
            字幕327
        """.trimIndent()

        assertEquals(
            listOf("字幕325", "", "字幕327"),
            parseTimedSubtitleTranslation(content, expected)
        )
    }

    @Test
    fun parser_acceptsDotTimestampsAndPreservesMultilineTranslations() {
        val expected = listOf(
            SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "source")
        )
        assertEquals(
            listOf("第一行\n第二行"),
            parseTimedSubtitleTranslation(
                "1\n00:00:01.000 --> 00:00:02.000\n第一行\n第二行",
                expected
            )
        )
    }

    @Test
    fun parser_usesTimeRangesInsteadOfResponseOrder() {
        val expected = listOf(
            testSubtitle(11, "source one"),
            testSubtitle(12, "source two")
        )

        assertEquals(
            listOf("译文一", "译文二"),
            parseTimedSubtitleTranslation(
                "2\n${expected[1].getTimeAxisSRT()}\n译文二\n\n" +
                    "1\n${expected[0].getTimeAxisSRT()}\n译文一",
                expected
            )
        )
    }

    @Test
    fun parser_ignoresSequenceDriftAndCommentaryOutsideSrtFence() {
        val expected = listOf(
            SubtitleEntry(index = 598, startTime = 1_000, endTime = 2_000, text = "source one"),
            SubtitleEntry(index = 599, startTime = 3_000, endTime = 4_000, text = "source two")
        )
        val content = """
            以下是翻译结果：
            ```srt
            598
            ${expected[0].getTimeAxisSRT()}
            译文一

            599
            ${expected[1].getTimeAxisSRT()}
            译文二
            ```
            以上是完整翻译。
        """.trimIndent()

        assertEquals(
            listOf("译文一", "译文二"),
            parseTimedSubtitleTranslation(content, expected)
        )
    }

    @Test
    fun parser_ignoresSequenceNumbersThatDriftAfterAnUnrelatedLostCue() {
        val expected = listOf(
            SubtitleEntry(index = 599, startTime = 1_000, endTime = 2_000, text = "source one"),
            SubtitleEntry(index = 600, startTime = 3_000, endTime = 4_000, text = "source two")
        )

        assertEquals(
            listOf("译文一", "译文二"),
            parseTimedSubtitleTranslation(
                "598\n${expected[0].getTimeAxisSRT()}\n译文一\n\n" +
                    "599\n${expected[1].getTimeAxisSRT()}\n译文二",
                expected
            )
        )
    }

    @Test
    fun parser_rejectsMissingExpectedTimeRange() {
        val expected = List(3) { index -> testSubtitle(301 + index, "source") }
        val error = assertThrows(IOException::class.java) {
            parseTimedSubtitleTranslation(
                "301\n${expected[0].getTimeAxisSRT()}\n字幕301\n\n" +
                    "303\n${expected[2].getTimeAxisSRT()}\n字幕303",
                expected
            )
        }
        assertTrue(error.message.orEmpty().contains("原字幕 302"))
        assertTrue(error.message.orEmpty().contains(expected[1].getTimeAxisSRT()))
        assertTrue(error.message.orEmpty().contains("2/3 个匹配时间轴"))
    }

    @Test
    fun parser_rejectsMoreTimeRangesThanExpected() {
        val expected = listOf(testSubtitle(301, "source"))
        assertThrows(IOException::class.java) {
            parseTimedSubtitleTranslation(
                "301\n${expected[0].getTimeAxisSRT()}\n字幕301\n\n" +
                    "301\n${expected[0].getTimeAxisSRT()}\n重复",
                expected
            )
        }
    }

    @Test
    fun duplicateExpectedTimeRanges_areMatchedInResponseOrder() {
        val expected = listOf(
            SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "source one"),
            SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "source two")
        )

        assertEquals(
            listOf("译文一", "译文二"),
            parseTimedSubtitleTranslation(
                "1\n00:00:01,000 --> 00:00:02,000\n译文一\n\n" +
                    "2\n00:00:01,000 --> 00:00:02,000\n译文二",
                expected
            )
        )
    }

    @Test
    fun cancelledStream_keepsOnlyCompletedContinuousTimedPrefix() {
        val expected = List(4) { index -> testSubtitle(301 + index, "source") }
        val completedLines = """
            翻译如下：
            301
            ${expected[0].getTimeAxisSRT()}
            字幕301

            302
            ${expected[1].getTimeAxisSRT()}

            304
            ${expected[3].getTimeAxisSRT()}
            字幕304
        """.trimIndent() + "\n"

        assertEquals(
            listOf("字幕301", ""),
            parseCompletedTimedTranslationPrefix(completedLines, expected)
        )
    }

    @Test
    fun cancelledStream_doesNotShiftResultsPastMissingFirstTimeRange() {
        val expected = List(2) { index -> testSubtitle(301 + index, "source") }
        assertEquals(
            emptyList<String>(),
            parseCompletedTimedTranslationPrefix(
                "302\n${expected[1].getTimeAxisSRT()}\n字幕302\n",
                expected
            )
        )
    }

    private fun timedTestContent(startNumber: Int, count: Int, text: String): String =
        buildTimedSubtitleContent(
            List(count) { index -> testSubtitle(startNumber + index, text) }
        )

    private fun testSubtitle(number: Int, text: String = "字幕$number"): SubtitleEntry {
        val startTime = number * 2_000L
        return SubtitleEntry(
            index = number,
            startTime = startTime,
            endTime = startTime + 1_500L,
            text = text
        )
    }
}
