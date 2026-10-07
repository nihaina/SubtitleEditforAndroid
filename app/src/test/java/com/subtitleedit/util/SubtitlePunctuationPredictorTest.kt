package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SubtitlePunctuationPredictorTest {
    @Test
    fun batchesContinueWithTheNext200EntriesWithoutRepeatingThePreviousTail() = runBlocking {
        val cases = mapOf(
            0 to emptyList(),
            1 to listOf(1..1),
            200 to listOf(1..200),
            201 to listOf(1..200, 201..201),
            400 to listOf(1..200, 201..400),
            401 to listOf(1..200, 201..400, 401..401),
            600 to listOf(1..200, 201..400, 401..600)
        )
        for ((count, ranges) in cases) {
            val source = subtitleEntries(count)
            val requests = mutableListOf<String>()

            val result = SubtitlePunctuationPredictor.predictSubtitleEntriesInBatches(source) { text ->
                requests += text
                punctuateRequest(text, "。")
            }

            assertEquals("Requests for $count entries", ranges.map { range ->
                "start\n" + range.joinToString("\n\n") { "${it + 1000}\n第${it}条" } + "\nend"
            }, requests)
            assertEquals(source.map { it.copy(text = "${it.text}。") }, result)
        }
    }

    @Test
    fun localModelSession_usesConfiguredEntriesPerRequest() = runBlocking {
        val requests = mutableListOf<String>()
        val source = subtitleEntries(61)

        val result = SubtitlePunctuationPredictor.Session(
            source,
            entriesPerBatch = DEFAULT_LOCAL_AI_SUBTITLES_PER_REQUEST
        ).run(requestPrediction = { text ->
            requests += text
            punctuateRequest(text, "。")
        })

        assertEquals(listOf(20, 20, 20, 1), requests.map { text ->
            text.lines().count { it.startsWith("第") }
        })
        assertEquals(source.map { it.copy(text = "${it.text}。") }, result)
    }

    @Test
    fun remoteModelSession_usesMaximumConfiguredBatchSize() = runBlocking {
        val source = subtitleEntries(2001)
        val requests = mutableListOf<String>()
        val batchSize = normalizeAiSubtitlesPerRequest(AiProviderConfig.OPENAI, 1000)

        val result = SubtitlePunctuationPredictor.Session(
            source,
            entriesPerBatch = batchSize
        ).run(requestPrediction = { text ->
            requests += text
            punctuateRequest(text, "。")
        })

        assertEquals(listOf(1000, 1000, 1), requests.map { text ->
            text.lines().count { it.startsWith("第") }
        })
        assertEquals(source.map { it.copy(text = "${it.text}。") }, result)
    }

    @Test
    fun localModelSession_omitsTranslationBlockMarkers() = runBlocking {
        val source = subtitleEntries(2)
        val requests = mutableListOf<String>()

        SubtitlePunctuationPredictor.Session(
            source,
            includeBlockMarkers = false,
            bracketSequence = true
        ).run(requestPrediction = { text ->
            requests += text
            punctuateRequest(text, "。")
        })

        assertEquals("[1001]第1条\n\n[1002]第2条", requests.single())
    }

    @Test
    fun streamingProgressKeepsMarkerFreeCueTextThatIsEnd() {
        val source = listOf(subtitleEntries(1).single().copy(text = "end"))

        assertEquals(
            1,
            SubtitlePunctuationPredictor.completedPrefixCount(
                entries = source,
                response = "1001\nend。",
                startPosition = 1
            )
        )
    }

    @Test
    fun streamingSessionReportsCompletedCueProgressBeforeBatchCommit() = runBlocking {
        val source = subtitleEntries(3)
        val streamProgress = mutableListOf<Pair<Int, Int>>()

        val result = SubtitlePunctuationPredictor.Session(
            source,
            entriesPerBatch = 2
        ).run(
            onStreamProgress = { done, total -> streamProgress += done to total },
            requestStreamingPrediction = { text, emit ->
                val response = punctuateRequest(text, "。")
                emit(response.lines().take(3).joinToString("\n"))
                emit(response)
                response
            },
            requestPrediction = { error("streaming request should be used") }
        )

        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), streamProgress)
        assertEquals(source.map { it.copy(text = "${it.text}。") }, result)
    }

    @Test
    fun formattingAndResponseExtractionKeepPunctuationPredictionIndependent() = runBlocking {
        val source = subtitleEntries(301).flatMap { entry ->
            listOf(entry.copy(text = "，${entry.text}！"), entry.copy(text = "。”"))
        }
        val prepared = SubtitlePunctuationPredictor.prepareEntries(source)
        val requests = mutableListOf<String>()

        val result = SubtitlePunctuationPredictor.predictSubtitleEntriesInBatches(prepared) { text ->
            requests += text
            val blocks = punctuateRequest(text, "。").removePrefix("start\n").removeSuffix("\nend")
                .split("\n\n")
            val reply = blocks.chunked(150).joinToString("\n\n") { chunk ->
                "```text\n" + chunk.joinToString("\n\n") + "\n```"
            }
            extractSubtitleAiResponse(reply)
        }

        assertEquals(listOf(200, 101), requests.map { text -> text.lines().count { it.startsWith("第") } })
        assertEquals("start\n1201\n第201条", requests.last().lines().take(3).joinToString("\n"))
        assertEquals(prepared.map { it.copy(text = "${it.text}。") }, result)
    }

    @Test
    fun repeatedTextUsesSequenceNumbersAndKeepsAllCueMetadata() = runBlocking {
        val source = subtitleEntries(3).map { it.copy(text = "你好") }
        val result = SubtitlePunctuationPredictor.predictSubtitleEntriesInBatches(source) {
            "1003\n你好。\n\n1001\n你好！\n\n1002\n你好？"
        }

        assertEquals(listOf(
            source[0].copy(text = "你好！"),
            source[1].copy(text = "你好？"),
            source[2].copy(text = "你好。")
        ), result)
        assertEquals(listOf("你好", "你好", "你好"), source.map { it.text })
        val saved = SubtitleParser.parseSRT(SubtitleParser.toSRT(result))
        assertEquals(result.map { it.text }, saved.map { it.text })
        assertEquals(source.map { it.startTime to it.endTime }, saved.map { it.startTime to it.endTime })
    }

    @Test
    fun multilineCuesKeepTheirLineBreaksAndInternalSpaces() = runBlocking {
        val source = subtitleEntries(2).mapIndexed { index, entry ->
            entry.copy(text = if (index == 0) "你好\n\n世界" else "Hello  world")
        }
        val result = SubtitlePunctuationPredictor.predictSubtitleEntriesInBatches(source) { text ->
            assertEquals("start\n1001\n你好\n\n世界\n\n1002\nHello  world\nend", text)
            extractSubtitleAiResponse(
                "[[PUNCTUATED_TEXT]]\r\n1001\r\n你好，\r\n\r\n世界！\r\n\r\n1002\r\nHello,  world!\r\n[[/PUNCTUATED_TEXT]]"
            )
        }

        assertEquals(listOf(
            source[0].copy(text = "你好，\n\n世界！"),
            source[1].copy(text = "Hello,  world!")
        ), result)
    }

    @Test
    fun missingOrExtraLinesStopProcessingBeforeLaterBatches() = runBlocking {
        for (lineCount in listOf(0, 149, 151)) {
            var requestCount = 0
            val result = runCatching {
                SubtitlePunctuationPredictor.predictSubtitleEntriesInBatches(subtitleEntries(601)) {
                    requestCount++
                    (1..lineCount).joinToString("\n\n") { "${it + 1000}\n第${it}条。" }
                }
            }

            assertTrue(result.exceptionOrNull() is IOException)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("文本匹配失败"))
            assertEquals(1, requestCount)
        }
    }

    @Test
    fun punctuationOnlyDocumentDoesNotSendRequests() = runBlocking {
        val prepared = SubtitlePunctuationPredictor.prepareEntries(listOf(
            SubtitleEntry(text = "。”"), SubtitleEntry(text = "—\u200B")
        ))
        val result = SubtitlePunctuationPredictor.predictSubtitleEntriesInBatches(prepared) {
            throw AssertionError("Pure punctuation must not be sent to AI")
        }

        assertEquals(emptyList<SubtitleEntry>(), result)
    }

    @Test
    fun requestsRunSequentially() = runBlocking {
        val firstResponse = CompletableDeferred<Unit>()
        var requestCount = 0
        val source = subtitleEntries(300)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            SubtitlePunctuationPredictor.predictSubtitleEntriesInBatches(source) { text ->
                if (++requestCount == 1) firstResponse.await()
                text
            }
        }

        assertEquals(1, requestCount)
        firstResponse.complete(Unit)
        assertEquals(source, result.await())
        assertEquals(2, requestCount)
    }

    @Test
    fun failedOrCancelledRequestStopsBeforeSendingLaterBatches() = runBlocking {
        for (failure in listOf(IOException("请求失败"), CancellationException("已取消"))) {
            var requestCount = 0
            val result = runCatching {
                SubtitlePunctuationPredictor.predictSubtitleEntriesInBatches(subtitleEntries(601)) { text ->
                    if (++requestCount == 2) throw failure
                    text
                }
            }

            assertSame(failure, result.exceptionOrNull())
            assertEquals(2, requestCount)
        }
    }

    @Test
    fun matchesBySequenceEvenWhenResponseBlocksAreReordered() {
        val source = subtitleEntries(3).mapIndexed { index, entry ->
            entry.copy(text = listOf("你好世界", "Hello  world", "再见")[index])
        }

        val result = SubtitlePunctuationPredictor.matchSubtitleEntries(
            source, "1003\n再见！\n\n1001\n你好，世界。\n\n1002\nHello, world!"
        )

        assertEquals(listOf(
            source[0].copy(text = "你好，世界。"),
            source[1].copy(text = "Hello, world!"),
            source[2].copy(text = "再见！")
        ), result)
    }

    @Test
    fun equalLineCountsDoNotHideChangedMissingOrDuplicatedText() {
        val source = subtitleEntries(2).mapIndexed { index, entry ->
            entry.copy(text = listOf("你好", "世界")[index])
        }
        for (reply in listOf(
            "1001\n您好！\n\n1002\n世界。",
            "1001\n你好！\n\n1002\n你好。",
            "1001\n你好新增！\n\n1002\n世界。"
        )) {
            assertThrows(IOException::class.java) {
                SubtitlePunctuationPredictor.matchSubtitleEntries(source, reply)
            }
        }
    }

    @Test
    fun symbolsAndNumbersRemainPartOfTheMatch() {
        for ((original, changed) in listOf("1+2=3" to "1+2=4", "你好😊" to "你好😢", "价格$5" to "价格5")) {
            assertThrows(IOException::class.java) {
                SubtitlePunctuationPredictor.matchSubtitleEntries(
                    listOf(SubtitleEntry(text = original)), "1\n$changed。"
                )
            }
        }
    }

    @Test
    fun matchingFailureResumesAtFailedBatchWithoutRepeatingCompletedRequests() = runBlocking {
        val source = subtitleEntries(301)
        val session = SubtitlePunctuationPredictor.Session(source)
        val requests = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val error = runCatching {
            session.run(onProgress = { count, total -> progress += count to total }) { text ->
                requests += text
                val reply = punctuateRequest(text, "！")
                if (requests.size == 2) reply.replace("第250条", "错误内容") else reply
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(200, session.processedCount)
        assertEquals(listOf(0 to 301, 200 to 301), progress)
        val result = session.run(onProgress = { count, total -> progress += count to total }) { text ->
            requests += text
            punctuateRequest(text, "？")
        }

        assertEquals(requests[1], requests[2])
        assertEquals(listOf("start", "1201", "第201条"), requests[2].lines().take(3))
        assertEquals(listOf(0 to 301, 200 to 301, 200 to 301, 301 to 301), progress)
        assertEquals(source.mapIndexed { index, entry ->
            entry.copy(text = entry.text + if (index < 200) "！" else "？")
        }, result)
        assertEquals(result, session.run { throw AssertionError("Completed session must not send again") })
    }

    @Test
    fun networkFailureAndCancellationRetainTheLastCheckpoint() = runBlocking {
        for (failure in listOf(IOException("请求失败"), CancellationException("已取消"))) {
            val source = subtitleEntries(301)
            val session = SubtitlePunctuationPredictor.Session(source)
            var requestCount = 0
            val error = runCatching {
                session.run { text ->
                    if (++requestCount == 2) throw failure
                    text
                }
            }.exceptionOrNull()

            assertSame(failure, error)
            assertEquals(200, session.processedCount)
            val requests = mutableListOf<String>()
            val result = session.run { text -> requests += text; text }
            assertEquals(listOf("start", "1201", "第201条"), requests.first().lines().take(3))
            assertEquals(1, requests.size)
            assertEquals(source, result)
        }
    }

    @Test
    fun numberedRepliesPreserveNumericAndMultilineSubtitleText() {
        val source = listOf(
            SubtitleEntry(index = 7, text = "你好\n\n123\n世界"),
            SubtitleEntry(index = 9, text = "456")
        )
        val response = "```text\r\nstart\r\n9\r\n456！\r\n\r\n7\r\n你好，\r\n\r\n123\r\n世界。\r\nend\r\n```"

        assertEquals(listOf(
            source[0].copy(text = "你好，\n\n123\n世界。"),
            source[1].copy(text = "456！")
        ), SubtitlePunctuationPredictor.matchSubtitleEntries(source, response))
    }

    @Test
    fun numericParagraphBeforeTheNextCueIsKeptAsSubtitleText() {
        val source = listOf(SubtitleEntry(text = "你好\n\n123"), SubtitleEntry(text = "世界"))

        assertEquals(source, SubtitlePunctuationPredictor.matchSubtitleEntries(
            source, "start\n1\n你好\n\n123\n\n2\n世界\nend"
        ))
    }

    @Test
    fun fallbackSequenceNumbersContinueAcrossBatches() = runBlocking {
        val source = subtitleEntries(201).map { it.copy(index = 0) }
        val requests = mutableListOf<String>()

        val result = SubtitlePunctuationPredictor.predictSubtitleEntriesInBatches(source) { text ->
            requests += text
            text
        }

        assertEquals(source, result)
        assertEquals("start\n201\n第201条\nend", requests.last())
    }

    @Test
    fun numericSubtitleTextIsNotMistakenForSequenceNumbers() {
        val source = listOf(SubtitleEntry(text = "123"), SubtitleEntry(text = "456"))

        assertEquals(source, SubtitlePunctuationPredictor.matchSubtitleEntries(source, "1\n123\n\n2\n456"))
    }

    @Test
    fun bracketRepliesKeepInlineFirstLinesAndMultilineText() {
        val source = listOf(
            SubtitleEntry(index = 7, text = "你好\n世界"),
            SubtitleEntry(index = 9, text = "结束")
        )

        assertEquals(
            listOf(
                source[0].copy(text = "你好！\n世界。"),
                source[1].copy(text = "结束！")
            ),
            SubtitlePunctuationPredictor.matchSubtitleEntries(
                source,
                "[9]结束！\n\n[7]你好！\n世界。"
            )
        )
        assertEquals(
            2,
            SubtitlePunctuationPredictor.completedPrefixCount(
                source,
                "[7]你好！\n世界。\n\n[9]结束！"
            )
        )
    }

    @Test
    fun swappedTextUnderTheWrongSequenceNumbersIsRejected() {
        val source = listOf(SubtitleEntry(index = 7, text = "你好"), SubtitleEntry(index = 9, text = "世界"))

        assertThrows(IOException::class.java) {
            SubtitlePunctuationPredictor.matchSubtitleEntries(source, "7\n世界！\n\n9\n你好。")
        }
    }

    @Test
    fun validatesEveryLineInOrderAndRejectsChangedLineCounts() {
        val source = listOf(SubtitleEntry(text = "你好\n世界"))
        for (text in listOf("世界！\n你好。", "你好世界。", "你\n好世界。", "你好！\n世界。\n多余")) {
            assertThrows(IOException::class.java) {
                SubtitlePunctuationPredictor.matchSubtitleEntries(source, "start\n1\n$text\nend")
            }
        }
        assertEquals(listOf(source[0].copy(text = "你 好！\n世，界。")),
            SubtitlePunctuationPredictor.matchSubtitleEntries(source, "1\n你 好！\n世，界。"))
    }

    @Test
    fun missingUnknownAndRepeatedSequenceNumbersAreRejected() {
        val source = listOf(SubtitleEntry(text = "你好"), SubtitleEntry(text = "你好"))
        for (reply in listOf("你好！\n你好。", "1\n你好！", "1\n你好！\n\n1\n你好。", "1\n你好！\n\n9\n你好。")) {
            assertThrows(IOException::class.java) {
                SubtitlePunctuationPredictor.matchSubtitleEntries(source, reply)
            }
        }
    }

    @Test
    fun numericParagraphsCanEqualOtherCueSequenceNumbers() {
        val source = listOf(SubtitleEntry(text = "你好\n\n2\n世界"), SubtitleEntry(text = "结束"))
        assertEquals(source, SubtitlePunctuationPredictor.matchSubtitleEntries(
            source, "start\n1\n你好\n\n2\n世界\n\n2\n结束\nend"
        ))
    }

    @Test
    fun numberedRepliesRejectMissingDuplicatedAndChangedText() {
        val source = listOf(SubtitleEntry(text = "你好"), SubtitleEntry(text = "世界"))
        for (body in listOf("1\n你好！", "1\n你好！\n\n2\n你好。", "1\n您好！\n\n2\n世界。")) {
            assertThrows(IOException::class.java) {
                SubtitlePunctuationPredictor.matchSubtitleEntries(source, "start\n$body\nend")
            }
        }
    }

    private fun punctuateRequest(text: String, punctuation: String): String =
        text.lines().joinToString("\n") { if (it.startsWith("第")) it + punctuation else it }

    private fun subtitleEntries(count: Int): List<SubtitleEntry> = (1..count).map { position ->
        SubtitleEntry(
            index = position + 1000,
            startTime = position * 100L,
            endTime = position * 100L + 90L,
            text = "第${position}条",
            endTimeModified = true,
            cueIdentifier = "cue-$position",
            cueSettings = "align:start"
        )
    }
}
