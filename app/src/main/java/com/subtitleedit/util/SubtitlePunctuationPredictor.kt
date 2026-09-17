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
                val response = requestPrediction(batch.joinToString("\n") { it.text })
                val punctuated = matchSubtitleEntries(batch, response)
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

    /** Match text without punctuation/spacing, consuming repeated lines one occurrence at a time. */
    fun matchSubtitleEntries(entries: List<SubtitleEntry>, response: String): List<SubtitleEntry> {
        val returnedLines = mutableMapOf<String, ArrayDeque<String>>()
        response.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            returnedLines.getOrPut(matchingText(line)) { ArrayDeque() }.addLast(line)
        }
        val result = entries.mapIndexed { index, entry ->
            entry.copy(text = entry.text.lines().joinToString("\n") { line ->
                if (line.isBlank()) line else {
                    returnedLines[matchingText(line)]?.removeFirstOrNull()
                        ?: throw IOException("标点预测文本匹配失败：本批第 ${index + 1} 条字幕不匹配")
                }
            })
        }
        if (returnedLines.values.any { it.isNotEmpty() }) {
            throw IOException("标点预测文本匹配失败：返回了多余或重复文本")
        }
        return result
    }

    private fun matchingText(text: String): String = text.replace(matchingIgnoredCharacters, "")
}
