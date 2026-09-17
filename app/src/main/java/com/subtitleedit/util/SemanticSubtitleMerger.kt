package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.WhisperRecognizer.SubtitleSegment
import java.io.IOException

/** Matches original subtitle text to AI output lines to recover merged time ranges. */
object SemanticSubtitleMerger {
    private const val NEW_ENTRIES_PER_BATCH = 300
    private val punctuationOnlyText = Regex("""[\p{P}\p{Z}\p{Cc}\p{Cf}\s]*""")

    /** Format cue starts and endings and drop cues containing only punctuation or invisible characters. */
    fun prepareSubtitleEntriesForAi(entries: List<SubtitleEntry>): List<SubtitleEntry> {
        val punctuation = "，,、。．.？?！!：:；;…".toSet()
        val options = SubtitleFormattingOptions(
            removeSpaces = false,
            startPunctuation = punctuation,
            endPunctuation = punctuation
        )
        return entries.mapNotNull { entry ->
            SubtitleTextFormatter.format(entry.text, options)
                .takeUnless { punctuationOnlyText.matches(it) }
                ?.let { text -> entry.copy(text = text) }
        }
    }

    /** A file keeps this session so a failed batch can resume with its previous merged tail. */
    class Session(private val entries: List<SubtitleEntry>) {
        val totalCount: Int get() = entries.size
        var processedCount: Int = 0
            private set
        private val completed = mutableListOf<SubtitleEntry>()
        private var pending: SubtitleEntry? = null
        private var pendingText = ""

        suspend fun run(
            onProgress: (Int, Int) -> Unit = { _, _ -> },
            requestMerge: suspend (String) -> String
        ): List<SubtitleEntry> {
            onProgress(processedCount, totalCount)
            while (processedCount < entries.size) {
                val end = (processedCount + NEW_ENTRIES_PER_BATCH).coerceAtMost(entries.size)
                val batch = buildList {
                    pending?.let(::add)
                    addAll(entries.subList(processedCount, end))
                }
                val request = batch.mapIndexed { index, entry ->
                    if (index == 0 && pending != null) pendingText else entry.text
                }.joinToString("\n")
                val aiText = requestMerge(request)
                val merged = mergeSubtitleEntriesByAiBoundaries(batch, aiText)
                val last = merged.last()
                val lastLine = aiText.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
                val nextPendingText = lastLine?.takeIf {
                    it.filterNot(Char::isWhitespace) == last.text.filterNot(Char::isWhitespace)
                } ?: last.text
                // Commit only after the entire reply matches. Failed requests leave this
                // checkpoint, including the previous batch's tail, untouched.
                completed.addAll(merged.dropLast(1))
                pending = last
                pendingText = nextPendingText
                processedCount = end
                onProgress(processedCount, totalCount)
            }
            return completed + listOfNotNull(pending)
        }
    }

    /** Carry the previous response's final merged cue into the next 300 source entries. */
    suspend fun mergeSubtitleEntriesInBatches(
        entries: List<SubtitleEntry>,
        requestMerge: suspend (String) -> String
    ): List<SubtitleEntry> = Session(entries).run(requestMerge = requestMerge)

    fun mergeSubtitleEntriesByAiBoundaries(
        entries: List<SubtitleEntry>,
        aiText: String
    ): List<SubtitleEntry> {
        if (entries.isEmpty()) return emptyList()
        val boundaries = collectEntryBoundaries(entries, aiText)
        return mergeEntriesAtBoundaries(entries, boundaries)
    }

    private fun collectEntryBoundaries(
        entries: List<SubtitleEntry>,
        aiText: String
    ): Set<Int> = collectBoundaries(entries.map { it.text }, aiText)

    private fun mergeEntriesAtBoundaries(
        entries: List<SubtitleEntry>,
        boundaries: Set<Int>
    ): List<SubtitleEntry> {
        return boundaries.sorted().zipWithNext().mapNotNull { (start, end) ->
            if (start >= end) return@mapNotNull null
            val group = entries.subList(start, end)
            group.first().copy(
                endTime = group.last().endTime,
                text = group.joinToString(separator = "") { it.text }
            )
        }.ifEmpty { entries }
    }

    fun mergeByAiBoundaries(
        segments: List<SubtitleSegment>,
        aiText: String
    ): List<SubtitleSegment> {
        if (segments.isEmpty()) return emptyList()
        val boundaries = collectBoundaries(segments.map { it.text }, aiText)
        return boundaries.sorted().zipWithNext().mapNotNull { (start, end) ->
            if (start >= end) return@mapNotNull null
            val group = segments.subList(start, end)
            SubtitleSegment(
                startTime = group.first().startTime,
                endTime = group.last().endTime,
                text = group.joinToString(separator = "") { it.text }
            )
        }.ifEmpty { segments }
    }

    private data class CueMatch(val start: Int, val end: Int)

    private data class CueText(val text: String, val partLengths: List<Int>)

    private fun collectBoundaries(
        sourceTexts: List<String>,
        aiText: String
    ): Set<Int> {
        // Ignore spacing for matching, but retain each character's output line so
        // spaces never become subtitle boundaries and line breaks still do.
        val lines = aiText.lineSequence().map { it.filterNot(Char::isWhitespace) }
            .filter { it.isNotEmpty() }.toList()
        val response = lines.joinToString("")
        val lineAt = IntArray(response.length)
        var offset = 0
        lines.forEachIndexed { lineIndex, line ->
            lineAt.fill(lineIndex, offset, offset + line.length)
            offset += line.length
        }

        val cues = sourceTexts.map { text ->
            val parts = text.lineSequence().map { it.filterNot(Char::isWhitespace) }
                .filter { it.isNotEmpty() }.toList()
            CueText(parts.joinToString(""), parts.map { it.length })
        }
        val matches = mutableListOf<CueMatch>()
        offset = 0
        // Every source cue must occur in order, with no omitted, changed or added text.
        // Matching a prefix of an altered line is not a successful match.
        cues.forEachIndexed { index, cue ->
            if (cue.text.isEmpty() || !response.startsWith(cue.text, offset)) {
                throw IOException("语义合并文本匹配失败：本批第 ${index + 1} 条字幕不匹配")
            }
            var partStart = offset
            val fitsLines = cue.partLengths.all { length ->
                val partEnd = partStart + length
                val fits = lineAt[partStart] == lineAt[partEnd - 1]
                partStart = partEnd
                fits
            }
            if (!fitsLines) {
                throw IOException("语义合并文本匹配失败：本批第 ${index + 1} 条字幕被拆行")
            }
            matches += CueMatch(offset, offset + cue.text.length)
            offset += cue.text.length
        }
        if (offset != response.length) {
            throw IOException("语义合并文本匹配失败：返回了多余文本")
        }
        val boundaries = (0..sourceTexts.size).toMutableSet()
        for (index in 1 until sourceTexts.size) {
            val previous = matches[index - 1]
            val current = matches[index]
            if (previous.end == current.start && lineAt[previous.end - 1] == lineAt[current.start]) {
                boundaries.remove(index)
            }
        }
        return boundaries
    }
}
