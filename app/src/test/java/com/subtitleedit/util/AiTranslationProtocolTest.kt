package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AiTranslationProtocolTest {

    @Test
    fun streamingSequenceProtocol_detectsSkippedCueImmediately() {
        val expected = listOf(
            testSubtitle(1, "one"),
            testSubtitle(2, "two"),
            testSubtitle(3, "three")
        )

        val issue = findTranslationProtocolIssue(
            content = "[1]一\n\n[3]三",
            expectedSubtitles = expected,
            format = SubtitleFormat.SRT,
            sequenceOnly = true
        )

        assertNotNull(issue)
        assertEquals(1, issue!!.index)
    }

    @Test
    fun streamingIndexedProtocol_detectsSkippedSequenceImmediately() {
        val expected = listOf(
            testSubtitle(1, "one"),
            testSubtitle(2, "two"),
            testSubtitle(3, "three")
        )
        val content = "1\n[00:02.000]一\n\n3\n[00:06.000]三"

        val issue = findTranslationProtocolIssue(
            content = content,
            expectedSubtitles = expected,
            format = SubtitleFormat.LRC
        )

        assertNotNull(issue)
        assertEquals(1, issue!!.index)
    }

    @Test
    fun streamingTimedProtocol_detectsWrongTimestampBeforeBatchEnds() {
        val expected = listOf(
            testSubtitle(1, "one"),
            testSubtitle(2, "two")
        )
        val issue = findTranslationProtocolIssue(
            content = "${expected[0].getTimeAxisSRT()}\n一\n\n" +
                "00:00:99 --> 00:01:00,000\n二\n",
            expectedSubtitles = expected,
            format = SubtitleFormat.SRT
        )

        assertNotNull(issue)
        assertEquals(1, issue!!.index)
    }

    @Test
    fun streamingTimedProtocol_ignoresIncompleteTimestampLineUntilItEnds() {
        val expected = listOf(testSubtitle(1, "one"))
        val partial = "00:00:01,000 --> 00:00:"

        val issue = findTranslationProtocolIssue(
            content = partial,
            expectedSubtitles = expected,
            format = SubtitleFormat.SRT
        )

        assertEquals(null, issue)
    }

    @Test
    fun streamingVttProtocol_waitsForTimestampAfterSequence() {
        val expected = listOf(testSubtitle(1, "one"))
        val issue = findTranslationProtocolIssue(
            content = "",
            expectedSubtitles = expected,
            format = SubtitleFormat.VTT
        )

        assertEquals(null, issue)
    }

    @Test
    fun streamingVttProtocol_waitsAfterNumericCueIdentifier() {
        val expected = listOf(
            SubtitleEntry(
                index = 7,
                startTime = 1_000,
                endTime = 2_000,
                cueIdentifier = "123",
                text = "one"
            )
        )
        val issue = findTranslationProtocolIssue(
            content = "123\n",
            expectedSubtitles = expected,
            format = SubtitleFormat.VTT
        )

        assertEquals(null, issue)
    }

    @Test
    fun streamingVttProtocol_detectsMalformedCompletedTimestampLine() {
        val expected = listOf(testSubtitle(1, "one"))
        val issue = findTranslationProtocolIssue(
            content = "not-a-time --> 00:00:03.500\ntext\n",
            expectedSubtitles = expected,
            format = SubtitleFormat.VTT
        )

        assertNotNull(issue)
        assertEquals(0, issue!!.index)
    }

    @Test
    fun vttProtocol_numericCueIdentifierIsNotTreatedAsSequence() {
        val expected = listOf(
            SubtitleEntry(
                index = 7,
                startTime = 1_000,
                endTime = 2_000,
                cueIdentifier = "8",
                text = "one"
            ),
            SubtitleEntry(index = 8, startTime = 3_000, endTime = 4_000, text = "two")
        )
        val response = "00:00:01.000 --> 00:00:02.000\n一\n\n" +
            "00:00:03.000 --> 00:00:04.000\n二"

        val issue = findTranslationProtocolIssue(
            content = response,
            expectedSubtitles = expected,
            format = SubtitleFormat.VTT,
            finalResponse = true
        )

        assertEquals(null, issue)
        assertEquals(
            listOf("一", "二"),
            parseSubtitleTranslation(response, expected, SubtitleFormat.VTT)
        )
    }

    @Test
    fun streamingLrcProtocol_detectsMalformedTimestampTag() {
        val expected = listOf(testSubtitle(1, "one"))
        val issue = findTranslationProtocolIssue(
            content = "[not-a-time]\ntext",
            expectedSubtitles = expected,
            format = SubtitleFormat.LRC
        )

        assertNotNull(issue)
        assertEquals(0, issue!!.index)
    }

    @Test
    fun streamingTimestampedProtocol_acceptsNonAdjacentRepeatedTimestamp() {
        val expected = listOf(
            SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "first"),
            SubtitleEntry(startTime = 3_000, endTime = 4_000, text = "second"),
            SubtitleEntry(startTime = 1_000, endTime = 5_000, text = "third")
        )

        val issue = findTranslationProtocolIssue(
            content = "[00:01.00]一\n\n[00:03.00]二\n\n[00:01.00]三",
            expectedSubtitles = expected,
            format = SubtitleFormat.LRC,
            finalResponse = true
        )

        assertEquals(null, issue)
    }

    @Test
    fun finalProtocolCheck_detectsMissingCue() {
        val expected = listOf(testSubtitle(1, "one"), testSubtitle(2, "two"))
        val issue = findTranslationProtocolIssue(
            content = "${expected[0].getTimeAxisSRT()}\n一",
            expectedSubtitles = expected,
            format = SubtitleFormat.SRT,
            finalResponse = true
        )

        assertNotNull(issue)
        assertEquals(1, issue!!.index)
    }

    @Test
    fun batches_defaultToTwoHundredSubtitles() {
        val batches = splitSubtitleTranslationBatches(
            List(401) { index -> testSubtitle(index + 1, "字幕${index + 1}") }
        )

        assertEquals(listOf(200, 200, 1), batches.map { it.size })
    }

    @Test
    fun localModelBatches_useConfiguredSubtitleCount() {
        val batches = splitSubtitleTranslationBatches(
            List(61) { index -> testSubtitle(index + 1, "字幕${index + 1}") },
            maxSubtitlesPerBatch = DEFAULT_LOCAL_AI_SUBTITLES_PER_REQUEST
        )

        assertEquals(listOf(20, 20, 20, 1), batches.map { it.size })
    }

    @Test
    fun modelBatchSize_usesProviderSpecificDefaultsAndRanges() {
        assertEquals(20, DEFAULT_LOCAL_AI_SUBTITLES_PER_REQUEST)
        assertEquals(200, DEFAULT_REMOTE_AI_SUBTITLES_PER_REQUEST)
        assertEquals(20, defaultAiSubtitlesPerRequest(AiProviderConfig.LOCAL))
        assertEquals(200, defaultAiSubtitlesPerRequest(AiProviderConfig.OPENAI))
        assertEquals(1, normalizeAiSubtitlesPerRequest(AiProviderConfig.LOCAL, 0))
        assertEquals(100, normalizeAiSubtitlesPerRequest(AiProviderConfig.LOCAL, 101))
        assertEquals(1, normalizeAiSubtitlesPerRequest(AiProviderConfig.OPENAI, 0))
        assertEquals(1000, normalizeAiSubtitlesPerRequest(AiProviderConfig.OPENAI, 1001))

        val batches = splitSubtitleTranslationBatches(
            List(2001) { index -> testSubtitle(index + 1, "字幕${index + 1}") },
            maxSubtitlesPerBatch = normalizeAiSubtitlesPerRequest(AiProviderConfig.OPENAI, 1000)
        )
        assertEquals(listOf(1000, 1000, 1), batches.map { it.size })
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
            "<start>\n1\n00:00:01,000 --> 00:00:02,000\n字幕一\n" +
                "2\n00:00:03,000 --> 00:00:04,000\n两行\n字幕\n<end>",
            content
        )
        assertFalse(content.contains("1.字幕一"))
    }

    @Test
    fun untimedInputKeepsTranslationMarkersAndOriginalOrFallbackSequenceNumbers() {
        val subtitles = listOf(
            SubtitleEntry(index = 7, startTime = 1_000, endTime = 2_000, text = "123"),
            SubtitleEntry(startTime = 3_000, endTime = 4_000, text = "两行\r\n字幕")
        )

        assertEquals(
            "<start>\n7\n123\n302\n两行\n字幕\n<end>",
            buildTimedSubtitleContent(subtitles, startPosition = 301, includeTimestamps = false)
        )
    }

    @Test
    fun userContent_putsOnlyInstructionBeforeSourceText() {
        val subtitles = listOf(testSubtitle(1, "hello"))

        assertEquals(
            "帮我翻译成中文，以原格式输出\n" +
                "<start>\n00:00:02,000 --> 00:00:03,500\nhello\n<end>",
            buildTranslationUserContent(subtitles, "中文")
        )
    }

    @Test
    fun userContent_appendsCustomPromptAfterDefaultInstruction() {
        val subtitles = listOf(testSubtitle(1, "hello"))

        assertEquals(
            "帮我翻译成中文，以原格式输出\n请使用正式语气\n" +
                "<start>\n00:00:02,000 --> 00:00:03,500\nhello\n<end>",
            buildTranslationUserContent(subtitles, "中文", "请使用正式语气")
        )
    }

    @Test
    fun indexTranslateUserContent_omitsFormatSuffix() {
        val subtitles = listOf(testSubtitle(1, "hello"))

        assertEquals(
            "帮我翻译成中文\n" +
                "<start>\n[1]hello\n<end>",
            buildTranslationUserContent(
                subtitles,
                "中文",
                sequenceOnly = true,
                indexTranslate = true
            )
        )
    }

    @Test
    fun localUserContent_usesSequenceAndTextWithoutTimestamps() {
        val subtitles = listOf(
            testSubtitle(1, "字幕1"),
            testSubtitle(2, "字幕2")
        )

        assertEquals(
            "帮我翻译成中文，以原格式输出\n" +
                "<start>\n[1]字幕1\n[2]字幕2\n<end>",
            buildTranslationUserContent(
                subtitles,
                "中文",
                sequenceOnly = true
            )
        )
    }

    @Test
    fun localUserContent_omitsTranslationBlockMarkers() {
        val subtitles = listOf(
            testSubtitle(1, "字幕1"),
            testSubtitle(2, "字幕2")
        )

        assertEquals(
            "帮我翻译成中文，以原格式输出\n" +
                "[1]字幕1\n[2]字幕2",
            buildTranslationUserContent(
                subtitles,
                "中文",
                sequenceOnly = true,
                includeBlockMarkers = false
            )
        )
    }

    @Test
    fun sequenceOnlyParser_matchesBySequenceAndPreservesMultilineText() {
        val expected = listOf(
            SubtitleEntry(index = 7, startTime = 1_000, endTime = 2_000, text = "source one"),
            SubtitleEntry(index = 8, startTime = 3_000, endTime = 4_000, text = "source two")
        )

        assertEquals(
            listOf("译文一\n第二行", "译文二"),
            parseSequenceSubtitleTranslation(
                "start\n[8]译文二\n\n[7]译文一\n第二行\nend",
                expected
            )
        )
    }

    @Test
    fun subtitleResponseContent_acceptsTaggedLegacyAndTaglessResponses() {
        assertEquals("[1]译文", subtitleResponseContent("<start>\n[1]译文\n<end>"))
        assertEquals("[1]译文", subtitleResponseContent("start\n[1]译文\nend"))
        assertEquals("[1]译文", subtitleResponseContent("[1]译文"))
    }

    @Test
    fun subtitleResponseContent_usesAvailableBoundaryWhenOneMarkerIsMissing() {
        assertEquals("[1]译文", subtitleResponseContent("[1]译文\n<end>\n说明"))
        assertEquals("[1]译文", subtitleResponseContent("<start>\n[1]译文"))
    }

    @Test
    fun taglessSubtitleContentMayContainTheWordEndAsCueText() {
        assertEquals("1\nend", subtitleResponseContent("1\nend"))
        assertEquals(
            listOf("end"),
            parseSequenceSubtitleTranslation("1\nend", listOf(testSubtitle(1, "source")))
        )
    }

    @Test
    fun cueTextMayContainStartAndEndInsideOrWithoutTags() {
        val expected = listOf(testSubtitle(1, "source"))
        val body = "1\nstart\nend"
        for (response in listOf(body, "<start>\n$body\n<end>", "<start>\n$body")) {
            assertEquals(listOf("start\nend"), parseSequenceSubtitleTranslation(response, expected))
        }
    }

    @Test
    fun streamingCueTextDoesNotCompleteALegacyBlock() {
        val expected = listOf(testSubtitle(1, "source"))
        for (response in listOf("1\nstart\nend", "<start>\n1\nstart\nend")) {
            assertEquals(
                emptyList<String>(),
                parseCompletedSequenceTranslationPrefix(response, expected, 1)
            )
        }
        assertEquals(
            listOf("start\nend"),
            parseCompletedSequenceTranslationPrefix(
                "说明\nstart\n1\nstart\nend\nend\n补充说明", expected, 1
            )
        )
    }

    @Test
    fun subtitleResponseContent_removesIncompleteStreamingMarkerLines() {
        assertEquals("[1]译文", subtitleResponseContent("<start>\n[1]译文\n<en"))
        assertEquals("[1]译文", subtitleResponseContent("<sta\n[1]译文"))
    }

    @Test
    fun sequenceParser_matchesTaggedAndTaglessRepliesBySequence() {
        val expected = listOf(testSubtitle(1, "source one"), testSubtitle(2, "source two"))

        assertEquals(
            listOf("一", "二"),
            parseSequenceSubtitleTranslation("<start>\n[1]一\n[2]二\n<end>", expected)
        )
        assertEquals(
            listOf("一", "二"),
            parseSequenceSubtitleTranslation("[1]一\n[2]二", expected)
        )
    }

    @Test
    fun inlineSequenceStreamingPrefix_acceptsTrailingCueDelimiter() {
        val expected = listOf(
            testSubtitle(1, "source one"),
            testSubtitle(2, "source two")
        )

        assertEquals(
            listOf("译文一"),
            parseCompletedSequenceTranslationPrefix(
                "[1]译文一\n\n",
                expected,
                expectedStartPosition = 1
            )
        )
    }

    @Test
    fun lrcApiInput_containsOnlyTimestampAndSubtitleText() {
        val subtitles = listOf(
            SubtitleEntry(index = 1, startTime = 1_000, endTime = 2_000, text = "<b>字幕一</b>"),
            SubtitleEntry(index = 2, startTime = 3_250, endTime = 4_000, text = "字幕二")
        )

        assertEquals(
            "<start>\n[00:01.00]<b>字幕一</b>\n" +
                "[00:03.25]字幕二\n<end>",
            buildSubtitleTranslationContent(subtitles, SubtitleFormat.LRC)
        )
    }

    @Test
    fun lrcResponse_isMatchedByTimestampAndPreservesBlankText() {
        val expected = listOf(
            SubtitleEntry(index = 1, startTime = 1_000, endTime = 2_000, text = "source one"),
            SubtitleEntry(index = 2, startTime = 3_250, endTime = 4_000, text = "source two")
        )

        assertEquals(
            listOf("译文一", ""),
            parseSubtitleTranslation(
                "[00:03.25]\n\n[00:01.00]译文一",
                expected,
                SubtitleFormat.LRC
            )
        )
    }

    @Test
    fun lrcResponse_supportsGroupedTimestampsAndNumericSubtitleText() {
        val expected = listOf(
            SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "source one"),
            SubtitleEntry(startTime = 2_000, endTime = 3_000, text = "source two")
        )

        assertEquals(
            listOf("42", "42"),
            parseSubtitleTranslation(
                "[00:01.00][00:02.00]42",
                expected,
                SubtitleFormat.LRC
            )
        )
    }

    @Test
    fun vttApiInputOmitsSequenceAndCueProperties_responseMatchesByTimestamp() {
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
            "<start>\n00:00:01.000 --> 00:00:02.000\n<c.red>Hello</c>\n<end>",
            buildSubtitleTranslationContent(expected, SubtitleFormat.VTT)
        )
        assertEquals(
            listOf("<c.red>你好</c>"),
            parseSubtitleTranslation(
                "00:00:01.000 --> 00:00:02.000\n<c.red>你好</c>",
                expected,
                SubtitleFormat.VTT
            )
        )
    }

    @Test
    fun vttResponse_preservesNumericLastLineOfSubtitleText() {
        val expected = listOf(
            SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "source")
        )

        assertEquals(
            listOf("Year\n2024"),
            parseSubtitleTranslation(
                "00:00:01.000 --> 00:00:02.000\nYear\n2024",
                expected,
                SubtitleFormat.VTT
            )
        )
    }

    @Test
    fun srtResponse_preservesNumericLastLineBeforeNextCue() {
        val expected = listOf(
            SubtitleEntry(startTime = 1_000, endTime = 2_000, text = "source one"),
            SubtitleEntry(startTime = 3_000, endTime = 4_000, text = "source two")
        )

        assertEquals(
            listOf("Year\n2024", "Next"),
            parseSubtitleTranslation(
                "00:00:01,000 --> 00:00:02,000\nYear\n2024\n\n" +
                    "00:00:03,000 --> 00:00:04,000\nNext",
                expected,
                SubtitleFormat.SRT
            )
        )
    }

    @Test
    fun timestampedResponse_rejectsChangedOriginalFormatTime() {
        val expected = listOf(
            SubtitleEntry(index = 1, startTime = 1_000, endTime = 2_000, text = "source")
        )

        val error = assertThrows(IOException::class.java) {
            parseSubtitleTranslation(
                "[00:01.50]译文",
                expected,
                SubtitleFormat.LRC
            )
        }
        assertTrue(error.message.orEmpty().contains("非本批次时间轴"))
    }

    @Test
    fun lrcInput_roundsToItsSerializedCentisecondPrecisionForValidation() {
        val expected = listOf(
            SubtitleEntry(index = 1, startTime = 1_005, endTime = 2_000, text = "source")
        )

        assertEquals(
            listOf("译文"),
            parseSubtitleTranslation(
                "[00:01.01]译文",
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
                "${expected[1].getTimeAxisSRT()}\n译文二\n\n" +
                    "${expected[0].getTimeAxisSRT()}\n译文一",
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
            ${expected[0].getTimeAxisSRT()}
            译文一

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
    fun parser_matchesTimestampAfterAnUnrelatedLostCue() {
        val expected = listOf(
            SubtitleEntry(index = 599, startTime = 1_000, endTime = 2_000, text = "source one"),
            SubtitleEntry(index = 600, startTime = 3_000, endTime = 4_000, text = "source two")
        )

        assertEquals(
            listOf("译文一", "译文二"),
            parseTimedSubtitleTranslation(
                "${expected[0].getTimeAxisSRT()}\n译文一\n\n" +
                    "${expected[1].getTimeAxisSRT()}\n译文二",
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
            ${expected[0].getTimeAxisSRT()}
            译文一

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
    fun parser_matchesAngleMarkedAndUnmarkedRepliesByTimeRange() {
        val expected = listOf(testSubtitle(301, "source"))
        val timedTranslation = "${expected.single().getTimeAxisSRT()}\n译文"

        assertEquals(
            listOf("译文"),
            parseTimedSubtitleTranslation("说明\n<start>\n$timedTranslation\n<end>\n结束", expected)
        )
        assertEquals(
            listOf("译文"),
            parseTimedSubtitleTranslation("$timedTranslation\n<end>", expected)
        )
    }

    @Test
    fun parser_rejectsMissingExpectedTimeRange() {
        val expected = List(3) { index -> testSubtitle(301 + index, "source") }
        val error = assertThrows(IOException::class.java) {
            parseTimedSubtitleTranslation(
                "${expected[0].getTimeAxisSRT()}\n字幕301\n\n" +
                    "${expected[2].getTimeAxisSRT()}\n字幕303",
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
                "${expected[0].getTimeAxisSRT()}\n字幕301\n\n" +
                    "${expected[0].getTimeAxisSRT()}\n重复",
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
                "00:00:01,000 --> 00:00:02,000\n译文一\n\n" +
                    "00:00:01,000 --> 00:00:02,000\n译文二",
                expected
            )
        )
    }

    @Test
    fun cancelledStream_keepsOnlyCompletedContinuousTimedPrefix() {
        val expected = List(4) { index -> testSubtitle(301 + index, "source") }
        val completedLines = """
            翻译如下：
            ${expected[0].getTimeAxisSRT()}
            字幕301

            ${expected[1].getTimeAxisSRT()}

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
                "${expected[1].getTimeAxisSRT()}\n字幕302\n",
                expected
            )
        )
    }

    @Test
    fun cancelledMarkedStream_keepsCompletedCuesBeforeOpenEndMarker() {
        val expected = List(3) { index -> testSubtitle(301 + index, "source") }
        val content = """
            start
            ${expected[0].getTimeAxisSRT()}
            字幕301

            ${expected[1].getTimeAxisSRT()}
            字幕302

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
