package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.WhisperRecognizer.SubtitleSegment

/** Matches original subtitle text to AI output lines to recover merged time ranges. */
object SemanticSubtitleMerger {
    private const val NEW_ENTRIES_PER_BATCH = 300
    private val punctuationOnlyText = Regex("""[\p{P}\p{Z}\p{Cc}\p{Cf}\s]*""")

    /** Format cue endings and drop cues containing only punctuation or invisible characters. */
    fun prepareSubtitleEntriesForAi(entries: List<SubtitleEntry>): List<SubtitleEntry> {
        val options = SubtitleFormattingOptions(
            removeSpaces = false,
            endPunctuation = "，,、。．.？?！!：:；;…".toSet()
        )
        return entries.mapNotNull { entry ->
            SubtitleTextFormatter.format(entry.text, options)
                .takeUnless { punctuationOnlyText.matches(it) }
                ?.let { text -> entry.copy(text = text) }
        }
    }

    /** Carry the previous response's final merged cue into the next 300 source entries. */
    suspend fun mergeSubtitleEntriesInBatches(
        entries: List<SubtitleEntry>,
        requestMerge: suspend (String) -> String
    ): List<SubtitleEntry> {
        if (entries.isEmpty()) return emptyList()
        val completed = mutableListOf<SubtitleEntry>()
        var pending: SubtitleEntry? = null
        var pendingText = ""
        var processed = 0
        while (processed < entries.size) {
            val end = (processed + NEW_ENTRIES_PER_BATCH).coerceAtMost(entries.size)
            val batch = buildList {
                pending?.let(::add)
                addAll(entries.subList(processed, end))
            }
            val request = batch.mapIndexed { index, entry ->
                if (index == 0 && pending != null) pendingText else entry.text
            }.joinToString("\n")
            val aiText = requestMerge(request)
            val merged = mergeSubtitleEntriesByAiBoundaries(batch, aiText)
            completed.addAll(merged.dropLast(1))
            val last = merged.last()
            pending = last
            val lastLine = aiText.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
            // Preserve the AI line's spacing when sending it again. If the response
            // omitted/changed the tail or added commentary, carry the intact source
            // cue instead; never lose unmatched subtitles or send commentary as a cue.
            pendingText = lastLine?.takeIf {
                it.filterNot(Char::isWhitespace) == last.text.filterNot(Char::isWhitespace)
            } ?: last.text
            processed = end
        }
        pending?.let(completed::add)
        return completed
    }

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
        val matches = arrayOfNulls<CueMatch>(sourceTexts.size)
        var nextSource = 0
        offset = 0
        // Walk the reply from front to back. Both cursors only advance: a repeated
        // phrase cannot reuse a previous occurrence, and a missing source cue does
        // not prevent later cues from matching and merging.
        while (offset < response.length && nextSource < cues.size) {
            var matched = false
            for (index in nextSource until cues.size) {
                val cue = cues[index]
                if (cue.text.isEmpty() || !response.startsWith(cue.text, offset)) continue
                var partStart = offset
                val fitsLines = cue.partLengths.all { length ->
                    val partEnd = partStart + length
                    val fits = lineAt[partStart] == lineAt[partEnd - 1]
                    partStart = partEnd
                    fits
                }
                if (!fitsLines) continue
                matches[index] = CueMatch(offset, offset + cue.text.length)
                offset += cue.text.length
                nextSource = index + 1
                matched = true
                break
            }
            if (!matched) offset++
        }
        val boundaries = (0..sourceTexts.size).toMutableSet()
        for (index in 1 until sourceTexts.size) {
            val previous = matches[index - 1] ?: continue
            val current = matches[index] ?: continue
            if (previous.end == current.start && lineAt[previous.end - 1] == lineAt[current.start]) {
                boundaries.remove(index)
            }
        }
        return boundaries
    }
}
