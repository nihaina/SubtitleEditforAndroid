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
                text.lines().joinToString("\n") { "$it。" }
            }

            assertEquals("Requests for $count entries", ranges.map { range ->
                range.joinToString("\n") { "第${it}条" }
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
            val reply = text.lines().chunked(150).joinToString("\n\n") { lines ->
                "```text\n" + lines.joinToString("\n") { "$it。" } + "\n```"
            }
            extractSemanticMergeResponse(reply)
        }

        assertEquals(listOf(300, 1), requests.map { it.lines().size })
        assertEquals("第301条", requests.last())
        assertEquals(prepared.map { it.copy(text = "${it.text}。") }, result)
    }

    @Test
    fun repeatedTextConsumesDistinctMatchesAndKeepsAllCueMetadata() = runBlocking {
        val source = subtitleEntries(3).map { it.copy(text = "你好") }
        val result = SubtitlePunctuationPredictor.predictSubtitleEntriesInBatches(source) {
            "你好！\n你好？\n你好。"
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
            assertEquals("你好\n\n世界\nHello  world", text)
            extractSemanticMergeResponse(
                "[[PUNCTUATED_TEXT]]\r\n你好，\r\n\r\n世界！\r\nHello,  world!\r\n[[/PUNCTUATED_TEXT]]"
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
                    (1..lineCount).joinToString("\n") { "第${it}条。" }
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
    fun matchesByTextEvenWhenResponseLinesAreReordered() {
        val source = subtitleEntries(3).mapIndexed { index, entry ->
            entry.copy(text = listOf("你好世界", "Hello  world", "再见")[index])
        }

        val result = SubtitlePunctuationPredictor.matchSubtitleEntries(
            source, "再见！\n你好，世界。\nHello, world!"
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
        for (reply in listOf("您好！\n世界。", "你好！\n你好。", "你好新增！\n世界。")) {
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
                    listOf(SubtitleEntry(text = original)), "$changed。"
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
                val reply = text.lines().joinToString("\n") { "$it！" }
                if (requests.size == 2) reply.replace("第450条", "错误内容") else reply
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(300, session.processedCount)
        assertEquals(listOf(0 to 601, 300 to 601), progress)
        val result = session.run(onProgress = { count, total -> progress += count to total }) { text ->
            requests += text
            text.lines().joinToString("\n") { "$it？" }
        }

        assertEquals(requests[1], requests[2])
        assertEquals("第301条", requests[2].lines().first())
        assertEquals("第601条", requests[3])
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
            assertEquals("第301条", requests.first().lines().first())
            assertEquals(2, requests.size)
            assertEquals(source, result)
        }
    }

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
