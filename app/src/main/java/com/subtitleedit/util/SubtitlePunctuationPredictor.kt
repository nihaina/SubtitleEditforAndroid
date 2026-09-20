package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import java.io.IOException

internal const val PUNCTUATION_PREDICTION_PROMPT =
    "以下为换行分离的字幕文本，帮我逐行添加标点符号，不修改原文，不做额外说明，以原格式输出"

object SubtitlePunctuationPredictor {
    private const val ENTRIES_PER_BATCH = 300
    private val matchingIgnoredCharacters = Regex("""[\p{P}\p{Z}\p{Cc}\p{Cf}\s]""")

    class Session(private val entries: List<SubtitleEntry>) {
        val totalCount: Int get() = entries.size
        var processedCount: Int = 0
            private set
        private val completed = mutableListOf<SubtitleEntry>()

        suspend fun run(
            onProgress: (Int, Int) -> Unit = { _, _ -> },
            requestPrediction: suspend (String) -> String
        ): List<SubtitleEntry> {
            onProgress(processedCount, totalCount)
            while (processedCount < entries.size) {
                val end = (processedCount + ENTRIES_PER_BATCH).coerceAtMost(entries.size)
                val batch = entries.subList(processedCount, end)
                val response = requestPrediction(buildTimedSubtitleContent(
                    batch,
                    startPosition = processedCount + 1,
                    includeTimestamps = false
                ))
                val punctuated = matchSubtitleEntries(batch, response, startPosition = processedCount + 1)
                completed += punctuated
                processedCount = end
                onProgress(processedCount, totalCount)
            }
            return completed.toList()
        }
    }

    /** Each batch contains only the next 300 prepared cues, without carrying any previous cue. */
    suspend fun predictSubtitleEntriesInBatches(
        entries: List<SubtitleEntry>,
        requestPrediction: suspend (String) -> String
    ): List<SubtitleEntry> = Session(entries).run(requestPrediction = requestPrediction)

    fun matchSubtitleEntries(
        entries: List<SubtitleEntry>,
        response: String,
        startPosition: Int = 1
    ): List<SubtitleEntry> {
        val content = extractSemanticMergeResponse(response)
            .replace("\r\n", "\n").replace('\r', '\n')
        val sequences = entries.mapIndexed { offset, entry ->
            entry.index.takeIf { it > 0 } ?: (startPosition + offset)
        }
        val expectedBySequence = sequences.zip(entries).toMap()
        if (expectedBySequence.size != entries.size) {
            throw IOException("标点预测文本匹配失败：原字幕序号重复")
        }
        val lines = (markedTranslationContent(content) ?: content).lines()
        val returnedBySequence = mutableMapOf<Int, String>()
        var cursor = 0
        while (cursor < lines.size) {
            if (lines[cursor].isBlank()) {
                cursor++
                continue
            }
            val sequence = lines[cursor++].trim().toIntOrNull()
                ?: throw IOException("标点预测文本匹配失败：缺少有效序号")
            val entry = expectedBySequence[sequence]
                ?: throw IOException("标点预测文本匹配失败：返回了多余序号 $sequence")
            if (sequence in returnedBySequence) {
                throw IOException("标点预测文本匹配失败：序号 $sequence 重复")
            }
            // Consume exactly this cue's lines, so numeric subtitle text is never a header.
            val expectedLines = entry.text.lines()
            val end = cursor + expectedLines.size
            if (end > lines.size) {
                throw IOException("标点预测文本匹配失败：序号 $sequence 的行数不匹配")
            }
            val returnedLines = lines.subList(cursor, end)
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

    private fun matchingText(text: String): String = text.replace(matchingIgnoredCharacters, "")
}
