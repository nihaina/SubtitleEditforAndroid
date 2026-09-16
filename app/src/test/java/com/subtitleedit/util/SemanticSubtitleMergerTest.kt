package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.WhisperRecognizer.SubtitleSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class SemanticSubtitleMergerTest {
    @Test
    fun mergeByAiBoundariesMergesAdjacentShortSubtitles() {
        val source = listOf(
            SubtitleSegment(100L, 200L, "你好"),
            SubtitleSegment(210L, 300L, "世界"),
            SubtitleSegment(310L, 400L, "这里"),
            SubtitleSegment(410L, 500L, "是我的世界")
        )

        val merged = SemanticSubtitleMerger.mergeByAiBoundaries(source, "你好世界  这里是我的世界")

        assertEquals(2, merged.size)
        assertEquals(100L, merged[0].startTime)
        assertEquals(300L, merged[0].endTime)
        assertEquals("你好世界", merged[0].text)
        assertEquals("这里是我的世界", merged[1].text)
    }

    @Test
    fun mergeByAiBoundariesKeepsSourceWhenAiChangesText() {
        val source = listOf(SubtitleSegment(0L, 100L, "你好"))

        val result = SemanticSubtitleMerger.mergeByAiBoundaries(source, "您好")

        assertEquals(source, result)
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
    fun batchesIncludePreviousTwoEntriesAfterEvery300NewEntries() = runBlocking {
        val cases = mapOf(
            0 to emptyList(),
            1 to listOf(1..1),
            300 to listOf(1..300),
            301 to listOf(1..300, 299..301),
            600 to listOf(1..300, 299..600),
            601 to listOf(1..300, 299..600, 599..601),
            900 to listOf(1..300, 299..600, 599..900)
        )
        for ((count, ranges) in cases) {
            val source = subtitleEntries(count)
            val requests = mutableListOf<String>()

            val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
                requests += text
                text
            }

            assertEquals("Requests for $count entries", ranges.map { range ->
                range.joinToString("  ") { "第${it}条" }
            }, requests)
            assertEquals(source, merged)
        }
    }

    @Test
    fun laterBatchCanRestoreOverlapBoundaryAndMergeAcrossBatchEnd() = runBlocking {
        val source = subtitleEntries(600)
        var requestCount = 0

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
            when (++requestCount) {
                1 -> text.replace("第298条  第299条  第300条", "第298条第299条第300条")
                else -> text.replace("第300条  第301条", "第300条第301条")
            }
        }

        // The earlier 298/299 decision survives; 299/300 is decided again by batch two.
        val expected = source.take(297) + listOf(
            source[297].copy(text = "第298条第299条", endTime = source[298].endTime),
            source[299].copy(text = "第300条第301条", endTime = source[300].endTime)
        ) + source.drop(301)
        assertEquals(expected, merged)
    }

    @Test
    fun laterBatchCanRemoveOverlapBoundary() = runBlocking {
        val source = subtitleEntries(600)
        var requestCount = 0

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
            if (++requestCount == 1) text else {
                text.replace("第299条  第300条  第301条", "第299条第300条第301条")
            }
        }

        val expected = source.take(298) +
            source[298].copy(text = "第299条第300条第301条", endTime = source[300].endTime) +
            source.drop(301)
        assertEquals(expected, merged)
    }

    @Test
    fun thirdBatchOverridesSecondBatchAtNextOverlap() = runBlocking {
        val source = subtitleEntries(601)
        var requestCount = 0

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
            when (++requestCount) {
                2 -> text.replace("第599条  第600条", "第599条第600条")
                3 -> text.replace("第600条  第601条", "第600条第601条")
                else -> text
            }
        }

        assertEquals(source.take(599) +
            source[599].copy(text = "第600条第601条", endTime = source[600].endTime), merged)
    }

    @Test
    fun mergedGroupsCanSpanMultipleBatchesWithoutDuplicateText() = runBlocking {
        val source = subtitleEntries(901)

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
            text.filterNot(Char::isWhitespace)
        }

        assertEquals(listOf(source.first().copy(
            text = source.joinToString("") { it.text },
            endTime = source.last().endTime
        )), merged)
    }

    @Test
    fun invalidLaterResponseKeepsSourceBoundariesWithoutUndoingEarlierBatch() = runBlocking {
        val source = subtitleEntries(301)
        var requestCount = 0

        val merged = SemanticSubtitleMerger.mergeSubtitleEntriesInBatches(source) { text ->
            if (++requestCount == 1) {
                text.replace("第1条  第2条", "第1条第2条")
                    .replace("第299条  第300条", "第299条第300条")
            } else {
                "AI修改了原文"
            }
        }

        assertEquals(listOf(source[0].copy(text = "第1条第2条", endTime = source[1].endTime)) +
            source.drop(2), merged)
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
