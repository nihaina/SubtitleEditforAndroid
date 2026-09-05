package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AiTranslationProtocolTest {

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
    fun timedInput_usesSrtBlocksWithoutSequenceNumbersAndPreservesEmbeddedLines() {
        val content = buildTimedSubtitleContent(
            listOf(
                SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "字幕一"),
                SubtitleEntry(startTime = 3_000, endTime = 4_000, text = "两行\n字幕")
            )
        )

        assertEquals(
            "start\n1\n00:00:01,000 --> 00:00:02,000\n字幕一\n\n" +
                "2\n00:00:03,000 --> 00:00:04,000\n两行\n字幕\nend",
            content
        )
        assertFalse(content.contains("1.字幕一"))
    }

    @Test
    fun userContent_putsOnlyInstructionBeforeSourceText() {
        val subtitles = listOf(testSubtitle(1, "hello"))

        assertEquals(
            "帮我翻译成中文，以原格式输出\n\n" +
                "start\n1\n00:00:02,000 --> 00:00:03,500\nhello\nend",
            buildTranslationUserContent(subtitles, "中文")
        )
    }

    @Test
    fun userContent_appendsCustomPromptAfterDefaultInstruction() {
        val subtitles = listOf(testSubtitle(1, "hello"))

        assertEquals(
            "帮我翻译成中文，以原格式输出\n请使用正式语气\n\n" +
                "start\n1\n00:00:02,000 --> 00:00:03,500\nhello\nend",
            buildTranslationUserContent(subtitles, "中文", "请使用正式语气")
        )
    }

    @Test
    fun lrcInput_addsLocalSequenceWithoutConvertingStyleText() {
        val subtitles = listOf(
            SubtitleEntry(index = 1, startTime = 1_000, endTime = 2_000, text = "<b>字幕一</b>"),
            SubtitleEntry(index = 2, startTime = 3_250, endTime = 4_000, text = "字幕二")
        )

        assertEquals(
            "start\n1\n[00:01.00]<b>字幕一</b>\n\n" +
                "2\n[00:03.25]字幕二\nend",
            buildSubtitleTranslationContent(subtitles, SubtitleFormat.LRC)
        )
    }

    @Test
    fun lrcResponse_isMatchedByLocalSequenceAndPreservesBlankText() {
        val expected = listOf(
            SubtitleEntry(index = 1, startTime = 1_000, endTime = 2_000, text = "source one"),
            SubtitleEntry(index = 2, startTime = 3_250, endTime = 4_000, text = "source two")
        )

        assertEquals(
            listOf("译文一", ""),
            parseSubtitleTranslation(
                "2\n[00:03.25]\n\n1\n[00:01.00]译文一",
                expected,
                SubtitleFormat.LRC
            )
        )
    }

    @Test
    fun vttInputAndResponse_preserveCuePropertiesAndStyleText() {
        val expected = listOf(
            SubtitleEntry(
                index = 7,
                startTime = 1_000,
                endTime = 2_000,
                text = "<c.red>Hello</c>",
                cueIdentifier = "123",
                cueSettings = "align:start"
            )
        )

        assertEquals(
            "start\n7\n123\n00:00:01.000 --> 00:00:02.000 align:start\n<c.red>Hello</c>\nend",
            buildSubtitleTranslationContent(expected, SubtitleFormat.VTT)
        )
        assertEquals(
            listOf("<c.red>你好</c>"),
            parseSubtitleTranslation(
                "7\n123\n00:00:01.000 --> 00:00:02.000 align:start\n<c.red>你好</c>",
                expected,
                SubtitleFormat.VTT
            )
        )
    }

    @Test
    fun indexedResponse_rejectsChangedOriginalFormatTime() {
        val expected = listOf(
            SubtitleEntry(index = 1, startTime = 1_000, endTime = 2_000, text = "source")
        )

        val error = assertThrows(IOException::class.java) {
            parseSubtitleTranslation(
                "1\n[00:01.50]译文",
                expected,
                SubtitleFormat.LRC
            )
        }
        assertTrue(error.message.orEmpty().contains("序号 1 的时间轴与原字幕不一致"))
    }

    @Test
    fun lrcInput_roundsToItsSerializedCentisecondPrecisionForValidation() {
        val expected = listOf(
            SubtitleEntry(index = 1, startTime = 1_005, endTime = 2_000, text = "source")
        )

        assertEquals(
            listOf("译文"),
            parseSubtitleTranslation(
                "1\n[00:01.01]译文",
                expected,
                SubtitleFormat.LRC
            )
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
    fun parser_usesMarkedBlocksAndIgnoresCommentaryOutsideThem() {
        val expected = listOf(
            testSubtitle(301, "source one"),
            testSubtitle(302, "source two")
        )

        val content = """
            翻译结果如下：
            start
            301
            ${expected[0].getTimeAxisSRT()}
            译文一

            302
            ${expected[1].getTimeAxisSRT()}
            译文二
            end
            这里是模型补充说明，不应写入字幕。
            以上翻译完成。
        """.trimIndent()

        assertEquals(
            listOf("译文一", "译文二"),
            parseTimedSubtitleTranslation(content, expected)
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

    @Test
    fun cancelledMarkedStream_keepsCompletedCuesBeforeOpenEndMarker() {
        val expected = List(3) { index -> testSubtitle(301 + index, "source") }
        val content = """
            start
            301
            ${expected[0].getTimeAxisSRT()}
            字幕301

            302
            ${expected[1].getTimeAxisSRT()}
            字幕302

            303
            ${expected[2].getTimeAxisSRT()}
            字幕
        """.trimIndent()

        assertEquals(
            listOf("字幕301", "字幕302"),
            parseCompletedTimedTranslationPrefix(content, expected)
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
