package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import java.util.Locale

/** Applies list edits to the current in-memory source without rebuilding the whole document. */
object SubtitleSourceSynchronizer {

    fun apply(
        content: String,
        format: SubtitleParser.SubtitleFormat,
        oldEntries: List<SubtitleEntry>,
        newEntries: List<SubtitleEntry>
    ): String {
        // stableId is an editor-only association and must not make an otherwise unchanged
        // source document get reserialized (especially important for preserving raw LRC layout).
        if (sameSerializedEntries(oldEntries, newEntries)) return content
        // Parser results are freshly allocated and therefore have fresh IDs. Associate them
        // with the caller's in-memory rows before applying structural edits, enabling raw cue
        // blocks to follow their stable subtitle identity across insertions/deletions.
        val associatedOldEntries = if (
            oldEntries.any { old -> newEntries.any { new -> new.stableId == old.stableId } }
        ) {
            oldEntries
        } else {
            SubtitleEntryOps.retainStableIds(newEntries, oldEntries)
        }
        return when (format) {
            SubtitleParser.SubtitleFormat.SRT -> patchSrt(content, associatedOldEntries, newEntries)
            SubtitleParser.SubtitleFormat.VTT -> patchVtt(content, associatedOldEntries, newEntries)
            SubtitleParser.SubtitleFormat.LRC -> patchLrc(content, associatedOldEntries, newEntries)
            else -> content
        }
    }

    private fun sameSerializedEntries(
        oldEntries: List<SubtitleEntry>,
        newEntries: List<SubtitleEntry>
    ): Boolean = oldEntries.size == newEntries.size && oldEntries.zip(newEntries).all { (old, new) ->
        old.index == new.index &&
            old.startTime == new.startTime &&
            old.endTime == new.endTime &&
            old.text == new.text &&
            old.endTimeModified == new.endTimeModified &&
            old.cueIdentifier == new.cueIdentifier &&
            old.cueSettings == new.cueSettings
    }

    private data class RawLine(val text: String, val ending: String) {
        val serialized: String get() = text + ending
    }

    private data class CueSpan(val start: Int, val endExclusive: Int, val timeLine: Int)

    private data class LrcCue(
        val lineIndex: Int,
        val tags: List<MatchResult>,
        val entryStart: Int,
        var terminatorLineIndex: Int? = null
    )

    private fun patchSrt(
        content: String,
        oldEntries: List<SubtitleEntry>,
        newEntries: List<SubtitleEntry>
    ): String {
        val lines = splitLines(content)
        val timeLines = lines.indices.filter { isSrtTimeLine(lines[it].text) }
        val spans = timeLines.mapIndexed { position, timeLine ->
            val start = if (
                timeLine > 0 && lines[timeLine - 1].text.trim().toIntOrNull() != null
            ) timeLine - 1 else timeLine
            val nextTimeLine = timeLines.getOrNull(position + 1)
            val end = when {
                nextTimeLine == null -> lines.size
                nextTimeLine > 0 && lines[nextTimeLine - 1].text.trim().toIntOrNull() != null ->
                    nextTimeLine - 1
                else -> nextTimeLine
            }
            CueSpan(start, end, timeLine)
        }
        return patchCueSpans(
            content, lines, spans, oldEntries, newEntries, ::patchSrtCue, ::appendSrtCue
        )
    }

    private fun patchVtt(
        content: String,
        oldEntries: List<SubtitleEntry>,
        newEntries: List<SubtitleEntry>
    ): String {
        val lines = splitLines(content)
        val spans = mutableListOf<CueSpan>()
        var blockStart = 0
        while (blockStart < lines.size) {
            while (blockStart < lines.size && lines[blockStart].text.isBlank()) blockStart++
            if (blockStart >= lines.size) break
            var blockEnd = blockStart
            while (blockEnd < lines.size && lines[blockEnd].text.isNotBlank()) blockEnd++
            val first = lines[blockStart].text.trim()
            val isMetadata = first == "NOTE" || first.startsWith("NOTE ") ||
                first == "STYLE" || first.startsWith("STYLE ") ||
                first == "REGION" || first.startsWith("REGION ")
            if (!isMetadata) {
                val timeLine = (blockStart until blockEnd).firstOrNull {
                    lines[it].text.contains("-->")
                }
                if (timeLine != null && timeLine - blockStart <= 1) {
                    var endWithSeparator = blockEnd
                    while (endWithSeparator < lines.size && lines[endWithSeparator].text.isBlank()) {
                        endWithSeparator++
                    }
                    spans += CueSpan(blockStart, endWithSeparator, timeLine)
                }
            }
            blockStart = (blockEnd + 1).coerceAtMost(lines.size)
        }
        return patchCueSpans(
            content, lines, spans, oldEntries, newEntries, ::patchVttCue, ::appendVttCue
        )
    }

    private fun patchCueSpans(
        content: String,
        lines: List<RawLine>,
        spans: List<CueSpan>,
        oldEntries: List<SubtitleEntry>,
        newEntries: List<SubtitleEntry>,
        patch: (List<RawLine>, Int, SubtitleEntry, SubtitleEntry) -> String,
        appendCue: (SubtitleEntry, String) -> String
    ): String {
        if (spans.isEmpty()) {
            return if (newEntries.isEmpty()) content
            else appendEntries(content, lines, newEntries, appendCue)
        }

        // When callers have associated parsed rows with the in-memory stable IDs, use those
        // identities instead of shifting every following cue after an insertion/deletion.
        // This preserves the original raw block (including metadata and spacing) belonging to
        // each surviving subtitle. Positional mapping remains the fallback for legacy callers.
        val oldIndexById = oldEntries.mapIndexed { index, entry -> entry.stableId to index }.toMap()
        val newIndexById = newEntries.mapIndexed { index, entry -> entry.stableId to index }.toMap()
        val survivingOldOrder = oldEntries.map { it.stableId }.filter { it in newIndexById }
        val survivingNewOrder = newEntries.map { it.stableId }.filter { it in oldIndexById }
        val canUseStableMapping = spans.size == oldEntries.size &&
            oldIndexById.size == oldEntries.size &&
            newIndexById.size == newEntries.size &&
            survivingOldOrder == survivingNewOrder &&
            oldEntries.any { it.stableId in newIndexById }
        if (canUseStableMapping) {
            val insertionsBeforeOld = Array(oldEntries.size) { mutableListOf<SubtitleEntry>() }
            val trailingInsertions = mutableListOf<SubtitleEntry>()
            newEntries.forEachIndexed { newIndex, entry ->
                if (entry.stableId in oldIndexById) return@forEachIndexed
                val nextOldIndex = (newIndex + 1 until newEntries.size)
                    .firstNotNullOfOrNull { next -> oldIndexById[newEntries[next].stableId] }
                if (nextOldIndex == null) trailingInsertions += entry
                else insertionsBeforeOld[nextOldIndex] += entry
            }

            val output = StringBuilder(content.length)
            var cursor = 0
            spans.forEachIndexed { oldIndex, span ->
                output.append(lines.subList(cursor, span.start).joinToString("") { it.serialized })
                insertionsBeforeOld[oldIndex].forEach { output.append(appendCue(it, preferredEnding(lines))) }
                val oldEntry = oldEntries[oldIndex]
                val newEntry = newIndexById[oldEntry.stableId]?.let { newEntries[it] }
                if (newEntry != null) {
                    output.append(
                        patch(
                            lines.subList(span.start, span.endExclusive),
                            span.timeLine - span.start,
                            oldEntry,
                            newEntry
                        )
                    )
                }
                cursor = span.endExclusive
            }
            output.append(lines.drop(cursor).joinToString("") { it.serialized })
            trailingInsertions.forEach { output.append(appendCue(it, preferredEnding(lines))) }
            return output.toString()
        }

        val mappedCount = minOf(spans.size, oldEntries.size, newEntries.size)
        val output = StringBuilder(content.length)
        var cursor = 0
        spans.forEachIndexed { index, span ->
            output.append(lines.subList(cursor, span.start).joinToString("") { it.serialized })
            if (index < mappedCount) {
                output.append(
                    patch(
                        lines.subList(span.start, span.endExclusive),
                        span.timeLine - span.start,
                        oldEntries[index],
                        newEntries[index]
                    )
                )
            }
            cursor = span.endExclusive
        }
        output.append(lines.drop(cursor).joinToString("") { it.serialized })
        return appendEntries(output.toString(), lines, newEntries.drop(mappedCount), appendCue)
    }

    private fun patchSrtCue(
        block: List<RawLine>,
        timeLineOffset: Int,
        old: SubtitleEntry,
        new: SubtitleEntry
    ): String {
        if (timeLineOffset !in block.indices) return block.joinToString("") { it.serialized }
        val ending = preferredEnding(block)
        val prefix = block.take(timeLineOffset).joinToString("") { it.serialized }
        val timeline = block[timeLineOffset]
        val trailing = block.drop(timeLineOffset + 1).takeLastWhile { it.text.isBlank() }
        val originalBody = block.drop(timeLineOffset + 1).dropLast(trailing.size)
            .joinToString("") { it.serialized }
        val body = when {
            old.text == new.text -> originalBody
            new.text.isEmpty() -> ""
            else -> normalizeText(new.text, ending) + ending
        }
        val timeText = if (old.startTime == new.startTime && old.endTime == new.endTime) {
            timeline.text
        } else {
            patchTimeLine(timeline.text, new.startTime, new.endTime, vtt = false)
        }
        return prefix + timeText + timeline.ending.ifEmpty { ending } + body +
            trailing.joinToString("") { it.serialized }
    }

    private fun patchVttCue(
        block: List<RawLine>,
        timeLineOffset: Int,
        old: SubtitleEntry,
        new: SubtitleEntry
    ): String {
        if (timeLineOffset !in block.indices) return block.joinToString("") { it.serialized }
        val ending = preferredEnding(block)
        val identifier = when {
            old.cueIdentifier == new.cueIdentifier ->
                block.take(timeLineOffset).joinToString("") { it.serialized }
            new.cueIdentifier.isBlank() -> ""
            else -> new.cueIdentifier + ending
        }
        val timeline = block[timeLineOffset]
        val timeText = if (
            old.startTime == new.startTime && old.endTime == new.endTime &&
            old.cueSettings == new.cueSettings
        ) {
            timeline.text
        } else {
            val rawTimes = parseArrowTimes(timeline.text, vtt = true)
            patchTimeLine(
                timeline.text,
                rawTimes?.first?.plus(new.startTime - old.startTime) ?: new.startTime,
                rawTimes?.second?.plus(new.endTime - old.endTime) ?: new.endTime,
                vtt = true,
                settings = new.cueSettings
            )
        }
        val trailing = block.drop(timeLineOffset + 1).takeLastWhile { it.text.isBlank() }
        val originalBody = block.drop(timeLineOffset + 1).dropLast(trailing.size)
            .joinToString("") { it.serialized }
        val body = when {
            old.text == new.text -> originalBody
            new.text.isEmpty() -> ""
            else -> normalizeText(new.text, ending) + ending
        }
        return identifier + timeText + timeline.ending.ifEmpty { ending } + body +
            trailing.joinToString("") { it.serialized }
    }

    private fun patchLrc(
        content: String,
        oldEntries: List<SubtitleEntry>,
        newEntries: List<SubtitleEntry>
    ): String {
        val lines = splitLines(content)
        val tagPattern = Regex("\\[-?\\d{1,4}:\\d{1,2}(?:[.:]\\d{1,3})?]")
        val cues = mutableListOf<LrcCue>()
        var entryIndex = 0
        var previousCue: LrcCue? = null
        lines.forEachIndexed { lineIndex, line ->
            val tags = tagPattern.findAll(line.text).toList()
            if (tags.isEmpty()) return@forEachIndexed

            val text = line.text.substring(tags.last().range.last + 1).trim()
            if (text.isEmpty()) {
                // Empty timed lines terminate the preceding cue. Keep the first one;
                // duplicate terminators are left untouched as unrelated source text.
                if (previousCue?.terminatorLineIndex == null) {
                    previousCue?.terminatorLineIndex = lineIndex
                }
            } else {
                val cue = LrcCue(lineIndex, tags, entryIndex)
                cues += cue
                previousCue = cue
                entryIndex += tags.size
            }
        }

        val cueByLine = cues.associateBy { it.lineIndex }
        val terminatorByLine = cues.mapNotNull { cue ->
            cue.terminatorLineIndex?.let { it to cue }
        }.toMap()

        if (
            oldEntries.any { old -> newEntries.any { new -> new.stableId == old.stableId } } &&
            oldEntries.map { it.stableId }.distinct().size == oldEntries.size &&
            newEntries.map { it.stableId }.distinct().size == newEntries.size &&
            oldEntries.map { it.stableId }.filter { id -> newEntries.any { it.stableId == id } } ==
                newEntries.map { it.stableId }.filter { id -> oldEntries.any { it.stableId == id } }
        ) {
            return rebuildLrcStableBlocks(lines, cues, oldEntries, newEntries)
        }

        val output = buildString(content.length) {
            lines.forEachIndexed { lineIndex, line ->
                val cue = cueByLine[lineIndex]
                if (cue != null) {
                    val oldSlice = oldEntries.drop(cue.entryStart).take(cue.tags.size)
                    val newSlice = newEntries.drop(cue.entryStart).take(cue.tags.size)
                    if (newSlice.isNotEmpty()) {
                        append(patchLrcCue(cue, line, oldSlice, newSlice))
                    }

                    val endIndex = cue.entryStart + cue.tags.size - 1
                    val oldEnd = oldEntries.getOrNull(endIndex)
                    val newEnd = newEntries.getOrNull(endIndex)
                    val oldNext = oldEntries.getOrNull(endIndex + 1)
                    val newNext = newEntries.getOrNull(endIndex + 1)
                    val timingChanged = oldEnd?.endTime != newEnd?.endTime ||
                        oldNext?.startTime != newNext?.startTime
                    val hasTerminator = cue.terminatorLineIndex != null
                    // LRC without a terminator is parsed as next.start - 24ms. Preserve
                    // that original form when the pair's timing was not edited.
                    val shouldHaveTerminator = when {
                        newEnd == null -> false
                        !timingChanged -> hasTerminator
                        newNext == null -> true
                        else -> newEnd.endTime != newNext.startTime
                    }
                    if (!hasTerminator && shouldHaveTerminator) {
                        append(formatLrcTag(newEnd!!.endTime)).append(line.ending)
                    }
                    return@forEachIndexed
                }

                val terminatorCue = terminatorByLine[lineIndex]
                if (terminatorCue == null) {
                    append(line.serialized)
                    return@forEachIndexed
                }

                val endIndex = terminatorCue.entryStart + terminatorCue.tags.size - 1
                val oldEnd = oldEntries.getOrNull(endIndex)
                val newEnd = newEntries.getOrNull(endIndex)
                val oldNext = oldEntries.getOrNull(endIndex + 1)
                val newNext = newEntries.getOrNull(endIndex + 1)
                val keptEnd = newEnd ?: return@forEachIndexed
                val timingChanged = oldEnd?.endTime != newEnd?.endTime ||
                    oldNext?.startTime != newNext?.startTime
                val shouldKeep = !timingChanged || newNext == null || keptEnd.endTime != newNext.startTime
                if (!shouldKeep) return@forEachIndexed

                if (timingChanged && oldEnd != null && oldEnd.endTime != keptEnd.endTime) {
                    val rawEnd = parseLrcTag(terminatorCue.tags.first().value)
                    append(
                        formatLrcTag(
                            rawEnd?.plus(keptEnd.endTime - oldEnd.endTime) ?: keptEnd.endTime
                        )
                    ).append(line.ending)
                } else {
                    append(line.serialized)
                }
            }
        }
        return appendLrcEntries(
            output,
            lines,
            newEntries.drop(entryIndex.coerceAtMost(newEntries.size))
        )
    }

    private fun rebuildLrcStableBlocks(
        lines: List<RawLine>,
        cues: List<LrcCue>,
        oldEntries: List<SubtitleEntry>,
        newEntries: List<SubtitleEntry>
    ): String {
        val newIndexById = newEntries.mapIndexed { index, entry -> entry.stableId to index }.toMap()
        val oldIdSet = oldEntries.mapTo(mutableSetOf()) { it.stableId }
        val added = newEntries.indices.filter { newEntries[it].stableId !in oldIdSet }
        val output = StringBuilder(lines.sumOf { it.serialized.length })
        var cursor = 0
        var lastNewIndex = -1
        val ending = preferredEnding(lines)

        fun appendLine(text: String) {
            if (output.isNotEmpty() && output.last() != '\n' && output.last() != '\r') {
                output.append(ending)
            }
            output.append(text).append(ending)
        }

        fun appendAddedBefore(limit: Int) {
            added.filter { it > lastNewIndex && it < limit }.forEach { index ->
                val entry = newEntries[index]
                appendLine(formatLrcTag(entry.startTime) + entry.text)
                lastNewIndex = index
            }
        }

        cues.forEach { cue ->
            output.append(lines.subList(cursor, cue.lineIndex).joinToString("") { it.serialized })
            val oldSlice = oldEntries.drop(cue.entryStart).take(cue.tags.size)
            val mapped = oldSlice.mapNotNull { old ->
                newIndexById[old.stableId]?.let { it to newEntries[it] }
            }.sortedBy { it.first }
            appendAddedBefore(mapped.firstOrNull()?.first ?: newEntries.size)
            if (mapped.isEmpty()) {
                cursor = cue.terminatorLineIndex?.plus(1) ?: cue.lineIndex + 1
                return@forEach
            }

            val mappedEntries = mapped.map { it.second }
            if (mapped.size == oldSlice.size) {
                output.append(patchLrcCue(cue, lines[cue.lineIndex], oldSlice, mappedEntries))
            } else {
                mappedEntries.forEach { entry ->
                    appendLine(formatLrcTag(entry.startTime) + entry.text)
                }
            }

            val lastOldIndex = oldSlice.indexOfLast { old -> newIndexById.containsKey(old.stableId) }
            val oldEnd = oldSlice.getOrNull(lastOldIndex)
            val lastNewIndexForCue = mapped.last().first
            val newEnd = newEntries[lastNewIndexForCue]
            val oldNext = oldEntries.getOrNull(cue.entryStart + oldSlice.size)
            val newNext = newEntries.getOrNull(lastNewIndexForCue + 1)
            val timingChanged = oldEnd?.endTime != newEnd.endTime || oldNext?.startTime != newNext?.startTime
            val hasTerminator = cue.terminatorLineIndex != null
            val shouldHaveTerminator = when {
                !timingChanged -> hasTerminator
                newNext == null -> true
                else -> newEnd.endTime != newNext.startTime
            }
            if (shouldHaveTerminator) {
                val terminator = cue.terminatorLineIndex?.let { lines[it] }
                if (terminator != null && !timingChanged) {
                    output.append(terminator.serialized)
                } else {
                    appendLine(formatLrcTag(newEnd.endTime))
                }
            }
            lastNewIndex = maxOf(lastNewIndex, mapped.maxOf { it.first })
            cursor = cue.terminatorLineIndex?.plus(1) ?: cue.lineIndex + 1
        }

        output.append(lines.drop(cursor).joinToString("") { it.serialized })
        added.filter { it > lastNewIndex }.forEach { index ->
            val entry = newEntries[index]
            appendLine(formatLrcTag(entry.startTime) + entry.text)
            val next = newEntries.getOrNull(index + 1)
            if (next == null || entry.endTime != next.startTime) {
                appendLine(formatLrcTag(entry.endTime))
            }
        }
        return output.toString()
    }

    private fun patchLrcCue(
        cue: LrcCue,
        line: RawLine,
        oldSlice: List<SubtitleEntry>,
        newSlice: List<SubtitleEntry>
    ): String {
        if (newSlice.map { it.text }.distinct().size == 1) {
            return buildString(line.text.length + line.ending.length) {
                newSlice.forEachIndexed { offset, entry ->
                    val old = oldSlice.getOrNull(offset)
                    append(
                        if (old?.startTime == entry.startTime) cue.tags[offset].value
                        else {
                            val rawStart = parseLrcTag(cue.tags[offset].value)
                            formatLrcTag(
                                rawStart?.plus(entry.startTime - (old?.startTime ?: entry.startTime))
                                    ?: entry.startTime
                            )
                        }
                    )
                }
                append(newSlice.first().text).append(line.ending)
            }
        }
        return buildString(newSlice.sumOf { it.text.length + 16 }) {
            newSlice.forEach { entry ->
                append(formatLrcTag(entry.startTime)).append(entry.text).append(line.ending)
            }
        }
    }

    private fun appendLrcEntries(
        content: String,
        lines: List<RawLine>,
        entries: List<SubtitleEntry>
    ): String {
        if (entries.isEmpty()) return content
        val ending = preferredEnding(lines)
        return buildString(content.length + entries.sumOf { it.text.length + 24 }) {
            append(content)
            if (isNotEmpty() && !endsWith("\n")) append(ending)
            entries.forEachIndexed { offset, entry ->
                append(formatLrcTag(entry.startTime)).append(entry.text).append(ending)
                val next = entries.getOrNull(offset + 1)
                if (next == null || entry.endTime != next.startTime) {
                    append(formatLrcTag(entry.endTime)).append(ending)
                }
            }
        }
    }

    private fun appendEntries(
        content: String,
        lines: List<RawLine>,
        entries: List<SubtitleEntry>,
        appendCue: (SubtitleEntry, String) -> String
    ): String {
        if (entries.isEmpty()) return content
        val ending = preferredEnding(lines)
        return buildString(content.length + entries.sumOf { it.text.length + 48 }) {
            append(content)
            if (isNotEmpty() && !endsWith("\n")) append(ending)
            entries.forEach { append(appendCue(it, ending)) }
        }
    }

    private fun appendSrtCue(entry: SubtitleEntry, ending: String): String = buildString {
        append(entry.index.coerceAtLeast(1)).append(ending)
        append(formatTimestamp(entry.startTime, vtt = false)).append(" --> ")
            .append(formatTimestamp(entry.endTime, vtt = false)).append(ending)
        if (entry.text.isNotEmpty()) append(normalizeText(entry.text, ending)).append(ending)
        append(ending)
    }

    private fun appendVttCue(entry: SubtitleEntry, ending: String): String = buildString {
        if (entry.cueIdentifier.isNotBlank()) append(entry.cueIdentifier).append(ending)
        append(formatTimestamp(entry.startTime, vtt = true)).append(" --> ")
            .append(formatTimestamp(entry.endTime, vtt = true))
        if (entry.cueSettings.isNotBlank()) append(' ').append(entry.cueSettings.trim())
        append(ending)
        if (entry.text.isNotEmpty()) append(normalizeText(entry.text, ending)).append(ending)
        append(ending)
    }

    private fun patchTimeLine(
        original: String,
        startTime: Long,
        endTime: Long,
        vtt: Boolean,
        settings: String = ""
    ): String {
        val arrow = original.indexOf("-->")
        if (arrow < 0) {
            return formatTimestamp(startTime, vtt) + " --> " + formatTimestamp(endTime, vtt)
        }
        val leading = original.takeWhile { it.isWhitespace() }
        val beforeArrow = original.substring(0, arrow)
        val leftSpacing = beforeArrow.takeLastWhile { it.isWhitespace() }
        val afterArrow = original.substring(arrow + 3)
        val rightSpacing = afterArrow.takeWhile { it.isWhitespace() }
        val endAndSuffix = afterArrow.drop(rightSpacing.length)
        val endTokenLength = endAndSuffix.indexOfFirst { it.isWhitespace() }
            .let { if (it < 0) endAndSuffix.length else it }
        val suffix = if (vtt) {
            if (settings.isBlank()) "" else " ${settings.trim()}"
        } else {
            endAndSuffix.drop(endTokenLength)
        }
        return leading + formatTimestamp(startTime, vtt) + leftSpacing + "-->" + rightSpacing +
            formatTimestamp(endTime, vtt) + suffix
    }

    private fun isSrtTimeLine(line: String): Boolean = SRT_TIME_LINE.matches(line)

    private fun splitLines(content: String): List<RawLine> {
        if (content.isEmpty()) return emptyList()
        val result = mutableListOf<RawLine>()
        var offset = 0
        while (offset < content.length) {
            val newline = content.indexOf('\n', offset)
            if (newline < 0) {
                result += RawLine(content.substring(offset), "")
                break
            }
            val crlf = newline > offset && content[newline - 1] == '\r'
            result += RawLine(
                content.substring(offset, if (crlf) newline - 1 else newline),
                if (crlf) "\r\n" else "\n"
            )
            offset = newline + 1
        }
        return result
    }

    private fun preferredEnding(lines: List<RawLine>): String =
        lines.firstOrNull { it.ending.isNotEmpty() }?.ending ?: "\n"

    private fun normalizeText(text: String, ending: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", ending)

    private fun formatTimestamp(timeMs: Long, vtt: Boolean): String {
        val safe = timeMs.coerceAtLeast(0L)
        return String.format(
            Locale.US,
            if (vtt) "%02d:%02d:%02d.%03d" else "%02d:%02d:%02d,%03d",
            safe / 3_600_000,
            safe % 3_600_000 / 60_000,
            safe % 60_000 / 1_000,
            safe % 1_000
        )
    }

    private fun formatLrcTag(timeMs: Long): String {
        val safe = timeMs.coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "[%02d:%02d.%02d]",
            safe / 60_000,
            safe % 60_000 / 1_000,
            safe % 1_000 / 10
        )
    }

    private fun parseArrowTimes(line: String, vtt: Boolean): Pair<Long, Long>? {
        val arrow = line.indexOf("-->")
        if (arrow < 0) return null
        val start = parseTimestamp(line.substring(0, arrow).trim(), vtt) ?: return null
        val end = parseTimestamp(
            line.substring(arrow + 3).trimStart().takeWhile { !it.isWhitespace() },
            vtt
        ) ?: return null
        return start to end
    }

    private fun parseTimestamp(value: String, vtt: Boolean): Long? {
        val normalized = if (vtt) value else value.replace(',', '.')
        val parts = normalized.split(':')
        if (parts.size !in 2..3) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val secondParts = parts.last().split('.', limit = 2)
        val seconds = secondParts[0].toLongOrNull() ?: return null
        val millis = secondParts.getOrNull(1)?.take(3)?.padEnd(3, '0')?.toLongOrNull() ?: 0L
        return hours * 3_600_000 + minutes * 60_000 + seconds * 1_000 + millis
    }

    private fun parseLrcTag(value: String): Long? {
        val match = Regex("\\[(-?\\d{1,4}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
            .matchEntire(value) ?: return null
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = match.groupValues[3]
        val millis = if (fraction.isEmpty()) 0L else fraction.take(3).padEnd(3, '0').toLong()
        return minutes * 60_000 + seconds * 1_000 + millis
    }

    private val SRT_TIME_LINE = Regex(
        "^\\s*-?\\d{1,3}[:.]\\d{1,2}[:.]\\d{1,2}(?:[,.;:]\\d{1,4})?" +
            "\\s*(?:-->|->|—>|——>|-+\\s*>)\\s*" +
            "-?\\d{1,3}[:.]\\d{1,2}[:.]\\d{1,2}(?:[,.;:]\\d{1,4})?(?:\\s+.*)?$"
    )
}
