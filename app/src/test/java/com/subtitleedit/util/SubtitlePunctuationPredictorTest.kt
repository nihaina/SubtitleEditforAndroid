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
    fun batchesContinueWithTheNext300EntriesWithoutRepeatingThePreviousTail() = runBlocking {
        val cases = mapOf(
            0 to emptyList(),
            1 to listOf(1..1),
            300 to listOf(1..300),
            301 to listOf(1..300, 301..301),
            600 to listOf(1..300, 301..600),
            601 to listOf(1..300, 301..600, 601..601),
            900 to listOf(1..300, 301..600, 601..900)
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
    fun formattingAndResponseExtractionMatchSemanticMerge() = runBlocking {
        val source = subtitleEntries(301).flatMap { entry ->
            listOf(entry.copy(text = "，${entry.text}！"), entry.copy(text = "。”"))
        }
        val prepared = SemanticSubtitleMerger.prepareSubtitleEntriesForAi(source)
        val requests = mutableListOf<String>()

        val result = SubtitlePunctuationPredictor.predictSubtitleEntriesInBatches(prepared) { text ->
            requests += text
            val blocks = punctuateRequest(text, "。").removePrefix("start\n").removeSuffix("\nend")
                .split("\n\n")
            val reply = blocks.chunked(150).joinToString("\n\n") { chunk ->
                "```text\n" + chunk.joinToString("\n\n") + "\n```"
            }
            extractSemanticMergeResponse(reply)
        }

        assertEquals(listOf(300, 1), requests.map { text -> text.lines().count { it.startsWith("第") } })
        assertEquals("start\n1301\n第301条\nend", requests.last())
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
            extractSemanticMergeResponse(
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
        for (lineCount in listOf(0, 299, 301)) {
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
        val prepared = SemanticSubtitleMerger.prepareSubtitleEntriesForAi(listOf(
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
        val source = subtitleEntries(600)
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
        val source = subtitleEntries(601)
        val session = SubtitlePunctuationPredictor.Session(source)
        val requests = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val error = runCatching {
            session.run(onProgress = { count, total -> progress += count to total }) { text ->
                requests += text
                val reply = punctuateRequest(text, "！")
                if (requests.size == 2) reply.replace("第450条", "错误内容") else reply
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(300, session.processedCount)
        assertEquals(listOf(0 to 601, 300 to 601), progress)
        val result = session.run(onProgress = { count, total -> progress += count to total }) { text ->
            requests += text
            punctuateRequest(text, "？")
        }

        assertEquals(requests[1], requests[2])
        assertEquals(listOf("start", "1301", "第301条"), requests[2].lines().take(3))
        assertEquals("start\n1601\n第601条\nend", requests[3])
        assertEquals(listOf(0 to 601, 300 to 601, 300 to 601, 600 to 601, 601 to 601), progress)
        assertEquals(source.mapIndexed { index, entry ->
            entry.copy(text = entry.text + if (index < 300) "！" else "？")
        }, result)
        assertEquals(result, session.run { throw AssertionError("Completed session must not send again") })
    }

    @Test
    fun networkFailureAndCancellationRetainTheLastCheckpoint() = runBlocking {
        for (failure in listOf(IOException("请求失败"), CancellationException("已取消"))) {
            val source = subtitleEntries(601)
            val session = SubtitlePunctuationPredictor.Session(source)
            var requestCount = 0
            val error = runCatching {
                session.run { text ->
                    if (++requestCount == 2) throw failure
                    text
                }
            }.exceptionOrNull()

            assertSame(failure, error)
            assertEquals(300, session.processedCount)
            val requests = mutableListOf<String>()
            val result = session.run { text -> requests += text; text }
            assertEquals(listOf("start", "1301", "第301条"), requests.first().lines().take(3))
            assertEquals(2, requests.size)
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
        val source = subtitleEntries(301).map { it.copy(index = 0) }
        val requests = mutableListOf<String>()

        val result = SubtitlePunctuationPredictor.predictSubtitleEntriesInBatches(source) { text ->
            requests += text
            text
        }

        assertEquals(source, result)
        assertEquals("start\n301\n第301条\nend", requests.last())
    }

    @Test
    fun numericSubtitleTextIsNotMistakenForSequenceNumbers() {
        val source = listOf(SubtitleEntry(text = "123"), SubtitleEntry(text = "456"))

        assertEquals(source, SubtitlePunctuationPredictor.matchSubtitleEntries(source, "1\n123\n\n2\n456"))
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
