package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import java.io.IOException

internal const val PUNCTUATION_PREDICTION_PROMPT =
    "以下为换行分离的字幕文本，帮我逐行添加标点符号，不修改原文，不做额外说明，以原格式输出"

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
        private val entriesPerBatch: Int = DEFAULT_AI_SUBTITLES_PER_REQUEST,
        private val includeBlockMarkers: Boolean = true,
        private val bracketSequence: Boolean = false
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
            requestPrediction: suspend (String) -> String
        ): List<SubtitleEntry> {
            onProgress(processedCount, totalCount)
            while (processedCount < entries.size) {
                val end = (processedCount + entriesPerBatch).coerceAtMost(entries.size)
                val batch = entries.subList(processedCount, end)
                val request = buildTimedSubtitleContent(
                    batch,
                    startPosition = processedCount + 1,
                    includeTimestamps = false,
                    includeBlockMarkers = includeBlockMarkers,
                    inlineSequence = bracketSequence
                )
                var streamedBatchCount = 0
                val response = requestStreamingPrediction?.invoke(request) { partialResponse ->
                    val completedInBatch = completedPrefixCount(
                        entries = batch,
                        response = partialResponse,
                        startPosition = processedCount + 1
                    )
                    if (completedInBatch > streamedBatchCount) {
                        streamedBatchCount = completedInBatch
                        onStreamProgress(
                            (processedCount + streamedBatchCount).coerceAtMost(totalCount),
                            totalCount
                        )
                    }
                } ?: requestPrediction(request)
                val punctuated = matchSubtitleEntries(batch, response, startPosition = processedCount + 1)
                completed += punctuated
                processedCount = end
                onProgress(processedCount, totalCount)
            }
            return completed.toList()
        }
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
        val content = extractSubtitleAiResponse(response)
            .replace("\r\n", "\n").replace('\r', '\n')
        val sequences = entries.mapIndexed { offset, entry ->
            entry.index.takeIf { it > 0 } ?: (startPosition + offset)
        }
        val expectedBySequence = sequences.zip(entries).toMap()
        if (expectedBySequence.size != entries.size) {
            throw IOException("标点预测文本匹配失败：原字幕序号重复")
        }
        val lines = (markedTranslationContent(content) ?: content).lines()
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
        val normalizedLines = extractSubtitleAiResponse(response)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        // Strip the protocol markers only for a marked response. In the local
        // marker-free protocol, a cue's actual text may itself be "end".
        val hasBlockStart = normalizedLines.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.equals("start", ignoreCase = true) == true
        val normalized = normalizedLines
            .let { lines -> if (hasBlockStart) lines.dropWhile { it.trim().equals("start", ignoreCase = true) } else lines }
            .let { lines -> if (hasBlockStart) lines.dropLastWhile { it.trim().equals("end", ignoreCase = true) } else lines }
        val sequences = entries.mapIndexed { offset, entry ->
            entry.index.takeIf { it > 0 } ?: (startPosition + offset)
        }
        val expectedBySequence = sequences.zip(entries).toMap()
        if (expectedBySequence.size != entries.size) return 0

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

    private fun matchingText(text: String): String = text.replace(matchingIgnoredCharacters, "")
}
