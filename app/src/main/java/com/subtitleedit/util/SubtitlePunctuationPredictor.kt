package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import java.io.IOException

internal const val PUNCTUATION_PREDICTION_PROMPT =
    "帮我添加标点符号，不修改原文，以原格式输出"

internal data class PunctuationProtocolIssue(
    val index: Int,
    val message: String
)

object SubtitlePunctuationPredictor {
    private val INLINE_SEQUENCE_LINE = Regex("""^[ \t]*\[([0-9]+)](.*)$""")
    private val matchingIgnoredCharacters = Regex("""[\p{P}\p{Z}\p{Cc}\p{Cf}\s]""")
    private val punctuationOnlyText = Regex("""[\p{P}\p{Z}\p{Cc}\p{Cf}\s]*""")

    fun prepareEntries(entries: List<SubtitleEntry>): List<SubtitleEntry> {
        val punctuation = "，,、。．.？?！!：:；;…".toSet()
        val options = SubtitleFormattingOptions(
            removeSpaces = false,
            startPunctuation = punctuation,
            endPunctuation = punctuation
        )
        return entries.mapNotNull { entry ->
            SubtitleTextFormatter.format(entry.text, options)
                .takeUnless { punctuationOnlyText.matches(it) }
                ?.let { entry.copy(text = it) }
        }
    }

    class Session(
        private val entries: List<SubtitleEntry>,
        private val entriesPerBatch: Int = DEFAULT_AI_SUBTITLES_PER_REQUEST
    ) {
        init {
            require(entriesPerBatch > 0) { "每批字幕数量必须大于 0" }
        }

        val totalCount: Int get() = entries.size
        var processedCount: Int = 0
            private set
        private val completed = mutableListOf<SubtitleEntry>()

        suspend fun run(
            onProgress: (Int, Int) -> Unit = { _, _ -> },
            onStreamProgress: (Int, Int) -> Unit = { _, _ -> },
            requestStreamingPrediction: (suspend (String, (String) -> Unit) -> String)? = null,
            requestStreamingPredictionWithRetry: (suspend (String, Boolean, (String) -> Unit, () -> Boolean) -> String)? = null,
            requestPredictionWithRetry: (suspend (String, Boolean) -> String)? = null,
            requestPrediction: suspend (String) -> String = { error("标点预测请求未配置") }
        ): List<SubtitleEntry> {
            val recoveryEnabled = requestStreamingPredictionWithRetry != null || requestPredictionWithRetry != null
            onProgress(processedCount, totalCount)
            while (processedCount < entries.size) {
                val end = (processedCount + entriesPerBatch).coerceAtMost(entries.size)
                val batch = entries.subList(processedCount, end)
                val translated = arrayOfNulls<String>(batch.size)
                val pending = ArrayDeque<List<Int>>()
                pending.add(batch.indices.toList())
                var lastIssueIndex: Int? = null
                var retryLevel = 0
                var reportedProgress = 0
                var retrying = false

                fun reportProgress() {
                    val done = translated.count { it != null }
                    if (done <= reportedProgress) return
                    reportedProgress = done
                    runCatching {
                        onStreamProgress(
                            (processedCount + done).coerceAtMost(totalCount),
                            totalCount
                        )
                    }
                }

                fun enqueueMissingRanges() {
                    var start = -1
                    for (index in 0..translated.size) {
                        val missing = index < translated.size && translated[index] == null
                        if (missing && start < 0) start = index
                        if (!missing && start >= 0) {
                            pending.add((start until index).toList())
                            start = -1
                        }
                    }
                }

                while (pending.isNotEmpty()) {
                    val requestIndices = pending.removeFirst()
                    if (requestIndices.isEmpty()) continue
                    val request = buildTimedSubtitleContent(
                        requestIndices.map(batch::get),
                        startPosition = processedCount + requestIndices.first() + 1,
                        includeTimestamps = false,
                        includeBlockMarkers = true,
                        inlineSequence = true
                    )
                    val isRetry = retrying
                    var streamedBatchCount = 0
                    var streamingIssue: PunctuationProtocolIssue? = null
                    var stopStreaming = false
                    var lastPartialResponse = ""
                    val startPosition = processedCount + requestIndices.first() + 1
                    val requestEntries = requestIndices.map(batch::get)
                    val onPartial: (String) -> Unit = { partialResponse ->
                        lastPartialResponse = partialResponse
                        val completedInRequest = completedPrefixCount(
                            entries = requestEntries,
                            response = partialResponse,
                            startPosition = startPosition
                        )
                        if (completedInRequest > streamedBatchCount) {
                            streamedBatchCount = completedInRequest
                            reportedProgress = maxOf(
                                reportedProgress,
                                requestIndices.first() + completedInRequest
                            )
                            onStreamProgress(
                                (processedCount + requestIndices.take(completedInRequest).lastIndex + 1)
                                    .coerceAtMost(totalCount),
                                totalCount
                            )
                        }
                        if (streamingIssue == null) {
                            streamingIssue = findPunctuationProtocolIssue(
                                response = partialResponse,
                                entries = requestEntries,
                                startPosition = startPosition,
                                finalResponse = false
                            )
                            if (streamingIssue != null) stopStreaming = true
                        }
                    }
                    val response = try {
                        if (requestStreamingPredictionWithRetry != null) {
                            requestStreamingPredictionWithRetry.invoke(
                                request,
                                isRetry,
                                onPartial,
                                { stopStreaming }
                            )
                        } else {
                            requestStreamingPrediction?.invoke(request, onPartial)
                        } ?: requestPredictionWithRetry?.invoke(request, isRetry) ?: requestPrediction(request)
                    } catch (error: Throwable) {
                        if (streamingIssue != null && stopStreaming) "" else throw error
                    }

                    val attempt = if (streamingIssue != null) {
                        PunctuationAttempt.Failure(
                            streamingIssue!!,
                            completedPrefixTexts(
                                requestEntries,
                                response.ifBlank { lastPartialResponse },
                                startPosition
                            )
                        )
                    } else {
                        val issue = findPunctuationProtocolIssue(
                            response = response,
                            entries = requestEntries,
                            startPosition = startPosition,
                            finalResponse = true
                        )
                        if (issue != null) {
                            PunctuationAttempt.Failure(
                                issue,
                                completedPrefixTexts(requestEntries, response, startPosition)
                            )
                        } else {
                            PunctuationAttempt.Success(
                                matchSubtitleEntries(
                                    requestEntries,
                                    response,
                                    startPosition = startPosition
                                ).map(SubtitleEntry::text)
                            )
                        }
                    }
                    when (attempt) {
                        is PunctuationAttempt.Success -> {
                            requestIndices.zip(attempt.texts).forEach { (index, text) ->
                                translated[index] = text
                            }
                            reportProgress()
                            if (pending.isEmpty()) enqueueMissingRanges()
                        }

                        is PunctuationAttempt.Failure -> {
                            retrying = true
                            if (!recoveryEnabled) {
                                throw IOException("文本匹配失败：${attempt.issue.message}")
                            }
                            val issueOffset = attempt.issue.index.coerceIn(0, requestIndices.lastIndex)
                            requestIndices.take(attempt.completedPrefix.size.coerceAtMost(issueOffset))
                                .zip(attempt.completedPrefix)
                                .forEach { (index, text) -> translated[index] = text }
                            reportProgress()
                            val issueIndex = requestIndices[issueOffset]
                            if (lastIssueIndex != issueIndex) {
                                lastIssueIndex = issueIndex
                                retryLevel = 1
                            } else {
                                retryLevel++
                            }
                            if (retryLevel > 3) {
                                throw IOException(
                                    "字幕第 ${processedCount + issueIndex + 1} 条连续三次返回格式错误：${attempt.issue.message}"
                                )
                            }
                            pending.clear()
                            when (retryLevel) {
                                1 -> pending.add(((issueIndex - 1).coerceAtLeast(0) until batch.size).toList())
                                2 -> {
                                    val windowStart = (issueIndex - 1).coerceAtLeast(0)
                                    val windowEnd = (issueIndex + 2).coerceAtMost(batch.size)
                                    pending.add((windowStart until windowEnd).toList())
                                }
                                else -> {
                                    (issueIndex - 1).takeIf { it >= 0 }?.let { pending.add(listOf(it)) }
                                    pending.add(listOf(issueIndex))
                                    if (issueIndex + 1 < batch.size) {
                                        pending.add((issueIndex + 1 until batch.size).toList())
                                    }
                                }
                            }
                        }
                    }
                }
                if (translated.any { it == null }) throw IOException("AI 未完成当前批次标点预测")
                completed += batch.mapIndexed { index, entry ->
                    entry.copy(text = translated[index]!!)
                }
                processedCount = end
                onProgress(processedCount, totalCount)
            }
            return completed.toList()
        }
    }

    private sealed class PunctuationAttempt {
        data class Success(val texts: List<String>) : PunctuationAttempt()
        data class Failure(val issue: PunctuationProtocolIssue, val completedPrefix: List<String>) : PunctuationAttempt()
    }

    /** First malformed cue in a streaming punctuation response. Missing trailing cues are ignored until final. */
    internal fun findPunctuationProtocolIssue(
        response: String,
        entries: List<SubtitleEntry>,
        startPosition: Int = 1,
        finalResponse: Boolean = false
    ): PunctuationProtocolIssue? {
        if (entries.isEmpty() || response.isBlank()) {
            return if (finalResponse && entries.isNotEmpty()) {
                PunctuationProtocolIssue(0, "AI 未返回标点预测结果")
            } else null
        }
        val content = subtitleResponseContent(response)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val responseLines = content.lines()
        val sequences = entries.mapIndexed { offset, entry ->
            entry.index.takeIf { it > 0 } ?: (startPosition + offset)
        }
        val expectedBySequence = sequences.withIndex().associate { it.value to it.index }
        if (expectedBySequence.size != entries.size) {
            return PunctuationProtocolIssue(0, "原字幕序号重复")
        }
        val firstCueLine = firstRecognizedSequenceLine(responseLines)
        if (firstCueLine < 0 && !finalResponse) return null
        val lines = if (firstCueLine >= 0) responseLines.drop(firstCueLine) else responseLines
        val seen = mutableSetOf<Int>()
        var cursor = 0
        while (cursor < lines.size) {
            while (cursor < lines.size && lines[cursor].isBlank()) cursor++
            if (cursor >= lines.size) break
            val inline = INLINE_SEQUENCE_LINE.matchEntire(lines[cursor].trimEnd('\r'))
            val sequence = if (inline != null) {
                inline.groupValues[1].toIntOrNull()
                    ?: return PunctuationProtocolIssue(0, "返回了无效字幕序号")
            } else {
                lines[cursor].trim().toIntOrNull()
                    ?: return if (finalResponse || cursor < lines.lastIndex || content.endsWith('\n')) {
                        PunctuationProtocolIssue(
                            sequences.indexOfFirst { it !in seen }.coerceAtLeast(0),
                            "缺少有效序号"
                        )
                    } else {
                        null
                    }
            }
            val expectedIndex = expectedBySequence[sequence]
                ?: return PunctuationProtocolIssue(
                    sequences.indexOfFirst { it !in seen }.coerceAtLeast(0),
                    "返回了非本批次序号 $sequence"
                )
            if (!seen.add(sequence)) {
                return PunctuationProtocolIssue(expectedIndex, "序号 $sequence 重复")
            }
            val expectedLines = entries[expectedIndex].text.lines()
            val firstText = inline?.groupValues?.get(2).orEmpty()
            val inlinePresent = inline != null && firstText.isNotEmpty()
            val continuationCount = expectedLines.size - if (inlinePresent) 1 else 0
            cursor++
            val end = cursor + continuationCount
            if (end > lines.size) {
                if (finalResponse) {
                    return PunctuationProtocolIssue(expectedIndex, "序号 $sequence 的行数不匹配")
                }
                break
            }
            val returnedLines = buildList {
                if (inlinePresent) add(firstText)
                addAll(lines.subList(cursor, end))
            }
            // The current cue may still be receiving text. Wait until another
            // non-blank line starts before validating its content during streaming.
            if (!finalResponse && lines.subList(end, lines.size).none { it.isNotBlank() }) {
                break
            }
            expectedLines.zip(returnedLines).forEachIndexed { lineIndex, (original, returned) ->
                if (matchingText(original) != matchingText(returned)) {
                    return PunctuationProtocolIssue(
                        expectedIndex,
                        "序号 $sequence 的第 ${lineIndex + 1} 行不匹配"
                    )
                }
            }
            cursor = end
        }
        if (finalResponse) {
            val missing = sequences.indexOfFirst { it !in seen }
            if (missing >= 0) {
                return PunctuationProtocolIssue(missing, "缺少序号 ${sequences[missing]}")
            }
        }
        return null
    }

    /** Each batch contains only the next configured cues, without carrying any previous cue. */
    suspend fun predictSubtitleEntriesInBatches(
        entries: List<SubtitleEntry>,
        entriesPerBatch: Int = DEFAULT_AI_SUBTITLES_PER_REQUEST,
        requestPrediction: suspend (String) -> String
    ): List<SubtitleEntry> = Session(entries, entriesPerBatch).run(requestPrediction = requestPrediction)

    fun matchSubtitleEntries(
        entries: List<SubtitleEntry>,
        response: String,
        startPosition: Int = 1
    ): List<SubtitleEntry> {
        val content = subtitleResponseContent(response)
            .replace("\r\n", "\n").replace('\r', '\n')
        val sequences = entries.mapIndexed { offset, entry ->
            entry.index.takeIf { it > 0 } ?: (startPosition + offset)
        }
        val expectedBySequence = sequences.zip(entries).toMap()
        if (expectedBySequence.size != entries.size) {
            throw IOException("标点预测文本匹配失败：原字幕序号重复")
        }
        val responseLines = content.lines()
        val firstCueLine = firstRecognizedSequenceLine(responseLines)
        val lines = if (firstCueLine >= 0) responseLines.drop(firstCueLine) else responseLines
        val inlineSequence = lines.any { INLINE_SEQUENCE_LINE.matches(it.trimEnd('\r')) }
        val returnedBySequence = mutableMapOf<Int, String>()
        var cursor = 0
        while (cursor < lines.size) {
            if (lines[cursor].isBlank()) {
                cursor++
                continue
            }
            val inlineMatch = if (inlineSequence) {
                INLINE_SEQUENCE_LINE.matchEntire(lines[cursor].trimEnd('\r'))
            } else {
                null
            }
            val sequence: Int
            val firstReturnedLine: String?
            if (inlineMatch != null) {
                sequence = inlineMatch.groupValues[1].toIntOrNull()
                    ?: throw IOException("标点预测文本匹配失败：缺少有效序号")
                firstReturnedLine = inlineMatch.groupValues[2]
                cursor++
            } else {
                sequence = lines[cursor++].trim().toIntOrNull()
                    ?: throw IOException("标点预测文本匹配失败：缺少有效序号")
                firstReturnedLine = null
            }
            val entry = expectedBySequence[sequence]
                ?: throw IOException("标点预测文本匹配失败：返回了多余序号 $sequence")
            if (sequence in returnedBySequence) {
                throw IOException("标点预测文本匹配失败：序号 $sequence 重复")
            }
            // Consume exactly this cue's lines, so numeric subtitle text is never a header.
            val expectedLines = entry.text.lines()
            val inlineTextPresent = firstReturnedLine != null && firstReturnedLine.isNotEmpty()
            val continuationCount = expectedLines.size - if (inlineTextPresent) 1 else 0
            val end = cursor + continuationCount
            if (end > lines.size) {
                throw IOException("标点预测文本匹配失败：序号 $sequence 的行数不匹配")
            }
            val returnedLines = buildList {
                if (inlineTextPresent) add(firstReturnedLine!!)
                addAll(lines.subList(cursor, end))
            }
            expectedLines.zip(returnedLines).forEachIndexed { index, (original, returned) ->
                if (matchingText(original) != matchingText(returned)) {
                    throw IOException("标点预测文本匹配失败：序号 $sequence 的第 ${index + 1} 行不匹配")
                }
            }
            returnedBySequence[sequence] = returnedLines.joinToString("\n")
            cursor = end
        }
        return entries.mapIndexed { index, entry ->
            val sequence = sequences[index]
            entry.copy(text = returnedBySequence[sequence]
                ?: throw IOException("标点预测文本匹配失败：缺少序号 $sequence"))
        }
    }

    /** Counts only complete consecutive cues from an in-flight response. */
    fun completedPrefixCount(
        entries: List<SubtitleEntry>,
        response: String,
        startPosition: Int = 1
    ): Int {
        if (entries.isEmpty() || response.isBlank()) return 0
        val responseLines = subtitleResponseContent(response)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        val sequences = entries.mapIndexed { offset, entry ->
            entry.index.takeIf { it > 0 } ?: (startPosition + offset)
        }
        val expectedBySequence = sequences.zip(entries).toMap()
        if (expectedBySequence.size != entries.size) return 0
        val firstCueLine = firstRecognizedSequenceLine(responseLines)
        if (firstCueLine < 0) return 0
        val normalized = responseLines.drop(firstCueLine)

        var cursor = 0
        var completed = 0
        while (completed < sequences.size) {
            while (cursor < normalized.size && normalized[cursor].isBlank()) cursor++
            val inlineMatch = normalized.getOrNull(cursor)
                ?.trimEnd('\r')
                ?.let(INLINE_SEQUENCE_LINE::matchEntire)
            val sequence = if (inlineMatch != null) {
                inlineMatch.groupValues[1].toIntOrNull() ?: break
            } else {
                normalized.getOrNull(cursor)?.trim()?.toIntOrNull() ?: break
            }
            val entry = expectedBySequence[sequence] ?: break
            cursor++
            val expectedLines = entry.text.lines()
            val inlineTextPresent = inlineMatch != null && inlineMatch.groupValues[2].isNotEmpty()
            val continuationCount = expectedLines.size - if (inlineTextPresent) 1 else 0
            val end = cursor + continuationCount
            if (end > normalized.size) break
            val returnedLines = buildList {
                if (inlineTextPresent) add(inlineMatch!!.groupValues[2])
                addAll(normalized.subList(cursor, end))
            }
            if (expectedLines.zip(returnedLines).any { (original, returned) ->
                    matchingText(original) != matchingText(returned)
                }) break
            cursor = end
            completed++
        }
        return completed
    }

    private fun firstRecognizedSequenceLine(lines: List<String>): Int {
        return lines.indexOfFirst { line ->
            val inline = INLINE_SEQUENCE_LINE.matchEntire(line.trimEnd('\r'))
            val sequence = inline?.groupValues?.get(1)?.toIntOrNull()
                ?: line.trim().toIntOrNull()
            sequence != null
        }
    }

    private fun matchingText(text: String): String = text.replace(matchingIgnoredCharacters, "")

    private fun completedPrefixTexts(
        entries: List<SubtitleEntry>,
        response: String,
        startPosition: Int
    ): List<String> {
        val count = completedPrefixCount(entries, response, startPosition)
        if (count == 0) return emptyList()
        val normalized = subtitleResponseContent(response)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val responseLines = normalized.lines()
        val sequences = entries.mapIndexed { offset, entry ->
            entry.index.takeIf { it > 0 } ?: (startPosition + offset)
        }
        val firstCueLine = firstRecognizedSequenceLine(responseLines)
        if (firstCueLine < 0) return emptyList()
        val lines = responseLines.drop(firstCueLine)
        val bySequence = sequences.zip(entries).toMap()
        val result = mutableListOf<String>()
        var cursor = 0
        while (result.size < count && cursor < lines.size) {
            while (cursor < lines.size && lines[cursor].isBlank()) cursor++
            if (cursor >= lines.size) break
            val inline = INLINE_SEQUENCE_LINE.matchEntire(lines[cursor].trimEnd('\r'))
            val sequence = inline?.groupValues?.get(1)?.toIntOrNull()
                ?: lines[cursor].trim().toIntOrNull()
                ?: break
            val entry = bySequence[sequence] ?: break
            val first = inline?.groupValues?.get(2).orEmpty()
            val inlinePresent = inline != null && first.isNotEmpty()
            val expectedLineCount = entry.text.lines().size - if (inlinePresent) 1 else 0
            cursor++
            val end = cursor + expectedLineCount
            if (end > lines.size) break
            result += buildList {
                if (inlinePresent) add(first)
                addAll(lines.subList(cursor, end))
            }.joinToString("\n")
            cursor = end
        }
        return result
    }
}
