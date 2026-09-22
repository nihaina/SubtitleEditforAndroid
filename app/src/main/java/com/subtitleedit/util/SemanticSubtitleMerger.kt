package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.WhisperRecognizer.SubtitleSegment
import java.io.IOException

/** Matches source cues from left to right to recover the AI's merged groups and time ranges. */
object SemanticSubtitleMerger {
    private const val NEW_ENTRIES_PER_BATCH = 300
    private val punctuationOnlyText = Regex("""[\p{P}\p{Z}\p{Cc}\p{Cf}\s]*""")
    private val matchingIgnoredCharacters = Regex("""[\p{P}\p{Z}\p{Cc}\p{Cf}\s]""")
    private val responseSeparator = Regex(" {3,}|\\r\\n|[\\r\\n]")

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
                val lastLine = responseGroups(aiText).lastOrNull()
                val nextPendingText = lastLine?.takeIf {
                    matchingText(it) == matchingText(last.text)
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

    private data class TolerantCueMatch(
        val start: Int,
        val end: Int,
        val groupStart: Int,
        val groupEnd: Int
    )

    private const val MAX_TOLERATED_UNMATCHED_CUES = 5

    private fun matchingText(text: String): String = text.replace(matchingIgnoredCharacters, "")

    private fun responseGroups(text: String): Sequence<String> =
        responseSeparator.splitToSequence(text).map(String::trim)
            .filter { matchingText(it).isNotEmpty() }

    private fun collectBoundaries(
        sourceTexts: List<String>,
        aiText: String
    ): Set<Int> = try {
        collectStrictBoundaries(sourceTexts, aiText)
    } catch (strictFailure: IOException) {
        collectTolerantBoundaries(sourceTexts, aiText, strictFailure)
    }

    private fun collectStrictBoundaries(
        sourceTexts: List<String>,
        aiText: String
    ): Set<Int> {
        // Split groups before removing punctuation/spacing, then advance a single
        // cursor through the response so repeated source text is consumed in order.
        val lines = responseGroups(aiText).map(::matchingText).toList()
        val response = lines.joinToString("")
        val lineAt = IntArray(response.length)
        var offset = 0
        lines.forEachIndexed { lineIndex, line ->
            lineAt.fill(lineIndex, offset, offset + line.length)
            offset += line.length
        }

        val cues = sourceTexts.map { text ->
            val parts = text.lineSequence().map(::matchingText)
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

    /**
     * Recover a small malformed part without allowing a later occurrence to satisfy an
     * earlier cue. A candidate is chosen from left to right; candidates that overlap or
     * move backwards form one failed range and are all kept as their source cues.
     */
    private fun collectTolerantBoundaries(
        sourceTexts: List<String>,
        aiText: String,
        strictFailure: IOException
    ): Set<Int> {
        val groups = responseGroups(aiText).toList()
        val normalizedGroups = groups.map(::matchingText)
        val groupStarts = IntArray(normalizedGroups.size)
        var responseLength = 0
        normalizedGroups.forEachIndexed { index, group ->
            groupStarts[index] = responseLength
            responseLength += group.length
        }
        val response = normalizedGroups.joinToString("")
        val groupAt = { position: Int ->
            normalizedGroups.indices.firstOrNull { index ->
                position < groupStarts[index] + normalizedGroups[index].length
            } ?: normalizedGroups.lastIndex
        }
        val cues = sourceTexts.map { text -> matchingText(text) }
        val occurrences = cues.map { cue ->
            if (cue.isEmpty()) emptyList()
            else buildList {
                var searchFrom = 0
                while (searchFrom <= response.length - cue.length) {
                    val found = response.indexOf(cue, searchFrom)
                    if (found < 0) break
                    add(TolerantCueMatch(
                        start = found,
                        end = found + cue.length,
                        groupStart = groupAt(found),
                        groupEnd = groupAt(found + cue.length - 1)
                    ))
                    searchFrom = found + 1
                }
            }
        }

        val chosen = arrayOfNulls<TolerantCueMatch>(cues.size)
        var cursor = 0
        occurrences.forEachIndexed { index, candidates ->
            val match = candidates.firstOrNull { it.start >= cursor }
                ?: candidates.firstOrNull()
            chosen[index] = match
            if (match != null) cursor = match.end
        }

        val failed = mutableSetOf<Int>()
        chosen.forEachIndexed { index, match ->
            if (match == null || match.groupStart != match.groupEnd) failed += index
        }
        // A backward move or overlap proves that independent substring matches crossed
        // positions. Keep the complete conflict interval, including its valid-looking cues.
        val conflictGroupRanges = mutableListOf<IntRange>()
        for (left in chosen.indices) {
            val leftMatch = chosen[left] ?: continue
            for (right in left + 1 until chosen.size) {
                val rightMatch = chosen[right] ?: continue
                if (rightMatch.start < leftMatch.start || rightMatch.start < leftMatch.end) {
                    for (index in left..right) failed += index
                    conflictGroupRanges += minOf(leftMatch.groupStart, rightMatch.groupStart)..
                        maxOf(leftMatch.groupEnd, rightMatch.groupEnd)
                }
            }
        }

        // Once a candidate moved across positions, every cue represented by the affected
        // response groups is unreliable. This catches 123/45/678/123 against 1245/678123:
        // the first 123 was found in the later group, so all four cues stay unchanged.
        conflictGroupRanges.forEach { range ->
            chosen.forEachIndexed { index, match ->
                if (match != null && match.groupEnd >= range.first && match.groupStart <= range.last) {
                    failed += index
                }
            }
        }

        // A response group containing unmatched characters is not a valid merge group.
        // Mark every source cue found in that group so the original cues are retained.
        normalizedGroups.forEachIndexed { groupIndex, group ->
            val groupStart = groupStarts[groupIndex]
            val groupEnd = groupStart + group.length
            val inGroup = chosen.mapIndexedNotNull { index, match ->
                match?.takeIf { it.end > groupStart && it.start < groupEnd }
                    ?.let { index to it }
            }
            if (inGroup.isEmpty()) {
                // An entirely unknown group still belongs to the nearest source cue;
                // retain that cue instead of silently accepting extra AI text.
                val nearest = chosen.mapIndexedNotNull { index, match ->
                    match?.let { index to it }
                }.firstOrNull { it.second.start >= groupEnd }
                    ?: chosen.mapIndexedNotNull { index, match -> match?.let { index to it } }
                        .lastOrNull { it.second.end <= groupStart }
                nearest?.first?.let { failed += it }
                return@forEachIndexed
            }
            val ordered = inGroup.sortedBy { it.second.start }
            var previous: Pair<Int, TolerantCueMatch>? = null
            var coveredEnd = groupStart
            ordered.forEach { current ->
                val start = maxOf(current.second.start, groupStart)
                val end = minOf(current.second.end, groupEnd)
                if (start != coveredEnd) {
                    // Keep only the cues adjacent to an unexplained response span;
                    // a single changed cue must not make an entire large group fail.
                    previous?.first?.let { failed += it }
                    failed += current.first
                }
                coveredEnd = maxOf(coveredEnd, end)
                previous = current
            }
            if (coveredEnd != groupEnd) {
                previous?.first?.let { failed += it }
            }
        }

        if (failed.size > MAX_TOLERATED_UNMATCHED_CUES) {
            throw strictFailure
        }

        val boundaries = (0..sourceTexts.size).toMutableSet()
        for (index in 1 until sourceTexts.size) {
            if (index - 1 in failed || index in failed) continue
            val previous = chosen[index - 1] ?: continue
            val current = chosen[index] ?: continue
            if (previous.end == current.start &&
                previous.groupEnd == current.groupStart
            ) {
                boundaries.remove(index)
            }
        }
        return boundaries
    }
}
