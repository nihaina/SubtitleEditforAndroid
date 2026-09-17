package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.WhisperRecognizer.SubtitleSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class SemanticSubtitleMergerTest {
    @Test
    fun prepareForAiDropsPunctuationOnlyEntriesIncludingQuotesAndDashes() {
        val punctuation = listOf(
            "", " \t\n", "，", ".", "!", "…", "”", "—", "-", "·", "。\u00A0",
            "。”", "“”", "「」", "（？！）", "【】", "_", "‥", "—\n。”"
        )
        val source = punctuation.map { SubtitleEntry(text = it) }

        assertEquals(emptyList<SubtitleEntry>(), SemanticSubtitleMerger.prepareSubtitleEntriesForAi(source))
    }

    @Test
    fun prepareForAiDropsPunctuationWithInvisibleCharacters() {
        val source = listOf(".\u200B", "\uFEFF?\u200F", "\u200B", "\u0000—\u0000")
            .map { SubtitleEntry(text = it) }

        assertEquals(emptyList<SubtitleEntry>(), SemanticSubtitleMerger.prepareSubtitleEntriesForAi(source))
    }

    @Test
    fun prepareForAiPreservesContentAndMetadataWhileFormattingLineStartsAndEndings() {
        val cases = listOf(
            "，,、。．.？?！!：:；;…你好，世界！" to "你好，世界",
            " \t，　… Hello world! " to "Hello world",
            "“你好，世界！”" to "“你好，世界”",
            "Don't stop!" to "Don't stop",
            "3.14" to "3.14",
            "[笑声]" to "[笑声]",
            "♪" to "♪",
            "😊" to "😊",
            "，你好。\n…世界！" to "你好\n世界"
        )
        val source = subtitleEntries(cases.size).mapIndexed { index, entry ->
            entry.copy(text = cases[index].first)
        }

        val prepared = SemanticSubtitleMerger.prepareSubtitleEntriesForAi(source)

        assertEquals(source.mapIndexed { index, entry ->
            entry.copy(text = cases[index].second)
        }, prepared)
        assertEquals(cases.map { it.first }, source.map { it.text })
    }

    @Test
    fun punctuationOnlyEntriesAreExcludedBeforeBatchCountingAndSending() = runBlocking {
        val expected = subtitleEntries(301)
        val source = expected.flatMap { entry ->
            listOf(entry.copy(text = "，${entry.text}。"), entry.copy(text = "”"))
        }
        val requests = mutableListOf<String>()
        val prepared = SemanticSubtitleMerger.prepareSubtitleEntriesForAi(source)

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(prepared) { text ->
            requests += text
            text
        }

        assertEquals(listOf(
            expected.take(300).joinToString("\n") { it.text },
            expected.drop(299).joinToString("\n") { it.text }
        ), requests)
        assertEquals(expected, merged)
    }

    @Test
    fun punctuationOnlyDocumentDoesNotSendAnyRequests() = runBlocking {
        val prepared = SemanticSubtitleMerger.prepareSubtitleEntriesForAi(listOf(
            SubtitleEntry(text = "。”"),
            SubtitleEntry(text = "—\u200B")
        ))

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(prepared) {
            throw AssertionError("Pure punctuation must not be sent to AI")
        }

        assertEquals(emptyList<SubtitleEntry>(), merged)
    }

    @Test
    fun mergeByAiBoundariesMergesAdjacentShortSubtitles() {
        val source = listOf(
            SubtitleSegment(100L, 200L, "你好"),
            SubtitleSegment(210L, 300L, "世界"),
            SubtitleSegment(310L, 400L, "这里"),
            SubtitleSegment(410L, 500L, "是我的世界")
        )

        val merged = SemanticSubtitleMerger.mergeByAiBoundaries(source, "你好世界\n这里是我的世界")

        assertEquals(2, merged.size)
        assertEquals(100L, merged[0].startTime)
        assertEquals(300L, merged[0].endTime)
        assertEquals("你好世界", merged[0].text)
        assertEquals("这里是我的世界", merged[1].text)
    }

    @Test
    fun mergeByAiBoundariesRejectsChangedText() {
        val source = listOf(SubtitleSegment(0L, 100L, "你好"))

        assertThrows(IOException::class.java) {
            SemanticSubtitleMerger.mergeByAiBoundaries(source, "您好")
        }
    }

    @Test
    fun mergeSubtitleEntriesByAiBoundariesCarriesMergedEndTime() {
        val source = listOf(
            SubtitleEntry(index = 1, startTime = 100L, endTime = 200L, text = "你"),
            SubtitleEntry(index = 2, startTime = 210L, endTime = 300L, text = "好")
        )

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesByAiBoundaries(source, "你好")

        assertEquals(1, merged.size)
        assertEquals(100L, merged[0].startTime)
        assertEquals(300L, merged[0].endTime)
        assertEquals("你好", merged[0].text)
    }

    @Test
    fun matchesOriginalSubtitlesWithinReturnedLines() = runBlocking {
        val source = subtitleEntries(3).mapIndexed { index, entry ->
            entry.copy(text = listOf("12", "3 4", "56")[index])
        }

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
            assertEquals("12\n3 4\n56", text)
            "123 4\n56"
        }

        assertEquals(listOf(
            source[0].copy(text = "123 4", endTime = source[1].endTime),
            source[2]
        ), merged)
    }

    @Test
    fun spacesAndTabsWithinReturnedLineDoNotSeparateSubtitles() {
        val source = subtitleEntries(3).mapIndexed { index, entry ->
            entry.copy(text = listOf("12", "3 4", "56")[index])
        }

        for (separator in listOf(" ", "  ", "\t")) {
            val merged = SemanticSubtitleMerger.mergeSubtitleEntriesByAiBoundaries(
                source, "12${separator}3 4\n56"
            )

            assertEquals(listOf(
                source[0].copy(text = "123 4", endTime = source[1].endTime),
                source[2]
            ), merged)
        }
    }

    @Test
    fun repeatedSubtitlesMatchEachOccurrenceInOrder() {
        val source = subtitleEntries(4).mapIndexed { index, entry ->
            entry.copy(text = listOf("哈", "哈", "哈哈", "结束")[index])
        }

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesByAiBoundaries(source, "哈\n哈哈哈\n结束")

        assertEquals(listOf(
            source[0],
            source[1].copy(text = "哈哈哈", endTime = source[2].endTime),
            source[3]
        ), merged)
    }

    @Test
    fun lineMatchingSupportsBlankLinesAndWindowsLineEndings() {
        val source = subtitleEntries(3).mapIndexed { index, entry ->
            entry.copy(text = listOf("12", "3 4", "56")[index])
        }

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesByAiBoundaries(
            source, "\r\n 123 4 \r\n\r\n56\r\n"
        )

        assertEquals(listOf(
            source[0].copy(text = "123 4", endTime = source[1].endTime),
            source[2]
        ), merged)
    }

    @Test
    fun lineMatchingPreservesMultilineSourceCues() {
        val source = subtitleEntries(3).mapIndexed { index, entry ->
            entry.copy(text = listOf("12\n3 4", "56", "78")[index])
        }

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesByAiBoundaries(source, "12\n3 456\n78")

        assertEquals(listOf(
            source[0].copy(text = "12\n3 456", endTime = source[1].endTime),
            source[2]
        ), merged)
    }

    @Test
    fun unmatchedOrSplitSubtitlesFailInsteadOfBeingSilentlyKept() {
        val source = subtitleEntries(3).mapIndexed { index, entry ->
            entry.copy(text = listOf("12", "3 4", "56")[index])
        }
        for (response in listOf("12三四\n56", "123\n4\n56")) {
            assertThrows(IOException::class.java) {
                SemanticSubtitleMerger.mergeSubtitleEntriesByAiBoundaries(source, response)
            }
        }
    }

    @Test
    fun matchingIgnoresAddedRemovedAndRepeatedSpacesWithoutChangingSourceText() {
        val source = subtitleEntries(3).mapIndexed { index, entry ->
            entry.copy(text = listOf("1 2", "3  4", "5\t6")[index])
        }
        for (response in listOf("1234\n56", "1  2 3\t4\n5 6", "1\u00a02\u30003 4\n5\t6")) {
            assertEquals(listOf(
                source[0].copy(text = "1 23  4", endTime = source[1].endTime),
                source[2]
            ), SemanticSubtitleMerger.mergeSubtitleEntriesByAiBoundaries(source, response))
        }
    }

    @Test
    fun missingOrExtraTextMakesTheWholeBatchFail() {
        val source = subtitleEntries(3).mapIndexed { index, entry ->
            entry.copy(text = listOf("12", "3 4", "56")[index])
        }
        for (response in listOf("123 4", "合并后的字幕：\n123 4\n56", "123 4\n56\n处理完成")) {
            assertThrows(IOException::class.java) {
                SemanticSubtitleMerger.mergeSubtitleEntriesByAiBoundaries(source, response)
            }
        }
    }

    @Test
    fun missingRepeatedTextIsAnError() {
        val source = subtitleEntries(4).mapIndexed { index, entry ->
            entry.copy(text = listOf("甲", "乙", "甲", "丙")[index])
        }

        assertThrows(IOException::class.java) {
            SemanticSubtitleMerger.mergeSubtitleEntriesByAiBoundaries(source, "乙\n甲丙")
        }
    }

    @Test
    fun changedTextBetweenMatchesIsAnError() {
        val source = subtitleEntries(3).mapIndexed { index, entry ->
            entry.copy(text = listOf("12", "3 4", "56")[index])
        }

        assertThrows(IOException::class.java) {
            SemanticSubtitleMerger.mergeSubtitleEntriesByAiBoundaries(source, "12新增内容3 4\n56")
        }
    }

    @Test
    fun changedCueDoesNotCommitAnyOfItsBatch() = runBlocking {
        val source = subtitleEntries(600)
        val session = SemanticSubtitleMerger.Session(source)
        val progress = mutableListOf<Int>()
        var requestCount = 0

        val failure = runCatching {
            session.run(onProgress = { count, _ -> progress += count }) { text ->
                requestCount++
                text.replace("第150条", "第一百五十条")
            }
        }.exceptionOrNull()

        assertEquals(IOException::class.java, failure?.javaClass)
        assertEquals(1, requestCount)
        assertEquals(0, session.processedCount)
        assertEquals(listOf(0), progress)
        val retried = session.run { it }
        assertEquals(source, retried)
    }

    @Test
    fun laterBatchMatchesPreviousMergedLineIgnoringSpaceChanges() = runBlocking {
        val source = subtitleEntries(301).toMutableList().apply {
            this[298] = this[298].copy(text = "12")
            this[299] = this[299].copy(text = "3 4")
            this[300] = this[300].copy(text = "56")
        }
        var requestCount = 0

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
            if (++requestCount == 1) {
                text.replace("12\n3 4", "12 3 4")
            } else {
                assertEquals("12 3 4\n56", text)
                "1 2 3 4 5 6"
            }
        }

        assertEquals(source.take(298) +
            source[298].copy(text = "123 456", endTime = source[300].endTime), merged)
    }

    @Test
    fun batchesIncludePreviousFinalLineAfterEvery300NewEntries() = runBlocking {
        val cases = mapOf(
            0 to emptyList(),
            1 to listOf(1..1),
            300 to listOf(1..300),
            301 to listOf(1..300, 300..301),
            600 to listOf(1..300, 300..600),
            601 to listOf(1..300, 300..600, 600..601),
            900 to listOf(1..300, 300..600, 600..900)
        )
        for ((count, ranges) in cases) {
            val source = subtitleEntries(count)
            val requests = mutableListOf<String>()

            val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
                requests += text
                text
            }

            assertEquals("Requests for $count entries", ranges.map { range ->
                range.joinToString("\n") { "第${it}条" }
            }, requests)
            assertEquals(source, merged)
        }
    }

    @Test
    fun laterBatchCarriesEntireFinalMergedGroupAndItsTimeRange() = runBlocking {
        val source = subtitleEntries(600)
        var requestCount = 0

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
            when (++requestCount) {
                1 -> text.replace("第298条\n第299条\n第300条", "第298条第299条第300条")
                else -> {
                    assertEquals("第298条第299条第300条\n" +
                        source.drop(300).joinToString("\n") { it.text }, text)
                    text.replace("第300条\n第301条", "第300条第301条")
                }
            }
        }

        val expected = source.take(297) +
            source[297].copy(text = "第298条第299条第300条第301条", endTime = source[300].endTime) +
            source.drop(301)
        assertEquals(expected, merged)
    }

    @Test
    fun laterBatchKeepsPreviousMergedTailWhenItRemainsOnItsOwnLine() = runBlocking {
        val source = subtitleEntries(600)
        var requestCount = 0

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
            if (++requestCount == 1) {
                text.replace("第299条\n第300条", "第299条第300条")
            } else {
                assertEquals("第299条第300条", text.lineSequence().first())
                text
            }
        }

        val expected = source.take(298) +
            source[298].copy(text = "第299条第300条", endTime = source[299].endTime) +
            source.drop(300)
        assertEquals(expected, merged)
    }

    @Test
    fun thirdBatchCarriesSecondBatchFinalMergedLine() = runBlocking {
        val source = subtitleEntries(601)
        var requestCount = 0

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
            when (++requestCount) {
                2 -> text.replace("第599条\n第600条", "第599条第600条")
                3 -> {
                    assertEquals("第599条第600条\n第601条", text)
                    text.replace("\n", "")
                }
                else -> text
            }
        }

        assertEquals(source.take(598) +
            source[598].copy(text = "第599条第600条第601条", endTime = source[600].endTime), merged)
    }

    @Test
    fun mergedGroupsCanSpanMultipleBatchesWithoutDuplicateText() = runBlocking {
        val source = subtitleEntries(901)
        var requestCount = 0

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
            val processed = requestCount++ * 300
            if (processed > 0) {
                assertEquals(source.take(processed).joinToString("") { it.text }, text.lines().first())
                assertEquals(source.drop(processed).take(300).map { it.text }, text.lines().drop(1))
            }
            text.filterNot(Char::isWhitespace)
        }

        assertEquals(listOf(source.first().copy(
            text = source.joinToString("") { it.text },
            endTime = source.last().endTime
        )), merged)
    }

    @Test
    fun repeatedTextAcrossBatchBoundaryIsConsumedOnce() = runBlocking {
        val source = subtitleEntries(302).mapIndexed { index, entry ->
            if (index in 298..300) entry.copy(text = "哈") else entry
        }
        var requestCount = 0

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
            if (++requestCount == 1) {
                text.removeSuffix("哈\n哈") + "哈哈"
            } else {
                assertEquals("哈哈\n哈\n第302条", text)
                "哈哈哈\n第302条"
            }
        }

        assertEquals(source.take(298) + listOf(
            source[298].copy(text = "哈哈哈", endTime = source[300].endTime),
            source[301]
        ), merged)
    }

    @Test
    fun missingResponseTailRetriesTheSameBatch() = runBlocking {
        val source = subtitleEntries(301)
        val session = SemanticSubtitleMerger.Session(source)
        val requests = mutableListOf<String>()
        val failure = runCatching {
            session.run { text ->
                requests += text
                text.substringBeforeLast('\n')
            }
        }.exceptionOrNull()

        assertEquals(IOException::class.java, failure?.javaClass)
        assertEquals(0, session.processedCount)
        val merged = session.run { text ->
            requests += text
            text
        }

        assertEquals(requests[0], requests[1])
        assertEquals("第300条\n第301条", requests[2])
        assertEquals(source, merged)
    }

    @Test
    fun matchingFailureCanResumeWithPreviousMergedTailAndCompletedGroups() = runBlocking {
        val source = subtitleEntries(601)
        val session = SemanticSubtitleMerger.Session(source)
        val requests = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val failure = runCatching {
            session.run(onProgress = { count, total -> progress += count to total }) { text ->
                requests += text
                if (requests.size == 1) {
                    text.replace("第1条\n第2条", "第1条第2条")
                        .replace("第299条\n第300条", "第299条第300条")
                } else "AI修改了原文"
            }
        }.exceptionOrNull()

        assertEquals(IOException::class.java, failure?.javaClass)
        assertEquals(300, session.processedCount)
        assertEquals(listOf(0 to 601, 300 to 601), progress)
        val merged = session.run(onProgress = { count, total -> progress += count to total }) { text ->
            requests += text
            text.replace("第300条\n第301条", "第300条第301条")
        }

        assertEquals(requests[1], requests[2])
        assertEquals("第299条第300条", requests[2].lineSequence().first())
        assertEquals(listOf(0 to 601, 300 to 601, 300 to 601, 600 to 601, 601 to 601), progress)
        assertEquals(listOf(source[0].copy(text = "第1条第2条", endTime = source[1].endTime)) +
            source.subList(2, 298) +
            source[298].copy(text = "第299条第300条第301条", endTime = source[300].endTime) +
            source.drop(301), merged)
        assertEquals(merged, session.run { throw AssertionError("Completed session must not send again") })
    }

    @Test
    fun nextBatchWaitsForPreviousResponse() = runBlocking {
        val source = subtitleEntries(600)
        val firstResponse = CompletableDeferred<Unit>()
        val requests = mutableListOf<String>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
                requests += text
                if (requests.size == 1) firstResponse.await()
                text
            }
        }

        assertEquals(1, requests.size)
        firstResponse.complete(Unit)
        assertEquals(source, result.await())
        assertEquals(2, requests.size)
    }

    @Test
    fun failedRequestStopsBeforeSendingLaterBatches() = runBlocking {
        val failure = IOException("请求失败")
        var requestCount = 0

        val result = runCatching {
            SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(subtitleEntries(901)) { text ->
                if (++requestCount == 2) throw failure
                text
            }
        }

        assertSame(failure, result.exceptionOrNull())
        assertEquals(2, requestCount)
    }

    @Test
    fun progressCountsSourceCuesAndNetworkFailureOrCancellationCanResume() = runBlocking {
        for (failure in listOf(IOException("请求失败"), CancellationException("已取消"))) {
            val source = subtitleEntries(601)
            val session = SemanticSubtitleMerger.Session(source)
            var requestCount = 0
            val error = runCatching {
                session.run { text ->
                    if (++requestCount == 2) throw failure
                    text.replace("\n", "")
                }
            }.exceptionOrNull()

            assertSame(failure, error)
            assertEquals(300, session.processedCount)
            val progress = mutableListOf<Int>()
            val result = session.run(onProgress = { count, _ -> progress += count }) { text ->
                if (progress.size == 1) {
                    assertEquals(source.take(300).joinToString("") { it.text }, text.lines().first())
                }
                text.replace("\n", "")
            }

            assertEquals(listOf(300, 600, 601), progress)
            assertEquals(listOf(source.first().copy(
                text = source.joinToString("") { it.text }, endTime = source.last().endTime
            )), result)
        }
    }

    private fun subtitleEntries(count: Int): List<SubtitleEntry> = (1..count).map { position ->
        SubtitleEntry(
            index = position + 1000,
            startTime = position * 100L,
            endTime = position * 100L + 90L,
            text = "第${position}条",
            cueIdentifier = "cue-$position",
            cueSettings = "align:start"
        )
    }
}
