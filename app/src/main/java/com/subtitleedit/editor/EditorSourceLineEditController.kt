package com.subtitleedit.editor

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleEntryOps
import com.subtitleedit.util.subtitle.SrtSubtitleFormatHandler
import com.subtitleedit.util.subtitle.WebVttSubtitleFormatHandler

/** Resolves a source-editor line change to one parsed subtitle row. */
internal class EditorSourceLineEditController(
    private val lineCount: () -> Int,
    private val lineText: (Int) -> String,
    private val currentFormat: () -> SubtitleParser.SubtitleFormat,
    private val entries: () -> List<SubtitleEntry>
) {
    data class Update(val entryIndex: Int, val entry: SubtitleEntry)

    /** Parsed replacement for the smallest source block touched by one editor operation. */
    data class BlockUpdate(
        val entryIndex: Int,
        val oldEntryCount: Int,
        val entries: List<SubtitleEntry>
    )

    private var cueIndexByLine: IntArray? = null
    private val lrcTimeTagPattern = Regex("\\[-?\\d{1,4}[:.]\\d{1,2}(?:[.:]\\d{1,3})?]")
    private val lrcOffsetLinePattern = Regex("^\\s*\\[offset:\\s*[+-]?\\d+(?:\\.\\d+)?]\\s*$", RegexOption.IGNORE_CASE)
    private val lrcOffsetPrefixPattern = Regex("\\[offset\\s*:", RegexOption.IGNORE_CASE)
    private val vttWordTimestampPattern = Regex("<\\d+:\\d+:\\d+\\.\\d+>")
    private var documentFormat: SubtitleParser.SubtitleFormat? = null
    private var documentHasGlobalCueContext = false

    fun invalidateLineIndex() {
        cueIndexByLine = null
    }

    /** Cache global cue features once when a source document is installed. */
    fun prepareDocument(content: String, format: SubtitleParser.SubtitleFormat) {
        documentFormat = format
        documentHasGlobalCueContext = hasGlobalCueContext(content, format)
        invalidateLineIndex()
    }

    /**
     * Whether an edit must be interpreted with document-wide cue context.  The common path
     * stays block-local; LRC offsets and WebVTT cross-cue transformations use the established
     * full parser so their global semantics are retained.
     */
    fun requiresFullParse(
        before: String,
        after: String,
        format: SubtitleParser.SubtitleFormat,
        startOffset: Int,
        oldEndOffset: Int,
        newEndOffset: Int
    ): Boolean {
        if (format == SubtitleParser.SubtitleFormat.UNKNOWN ||
            format == SubtitleParser.SubtitleFormat.ASS ||
            format == SubtitleParser.SubtitleFormat.SSA
        ) return true
        if (documentFormat != format) prepareDocument(before, format)
        if (documentHasGlobalCueContext) return true

        val oldStartLine = lineTextAtOffset(before, startOffset)
        val oldEndLine = lineTextAtOffset(before, oldEndOffset)
        val newStartLine = lineTextAtOffset(after, startOffset)
        val newEndLine = lineTextAtOffset(after, newEndOffset)
        val touchedLines = listOf(oldStartLine, oldEndLine, newStartLine, newEndLine)
        val globalMarkerChanged = when (format) {
            SubtitleParser.SubtitleFormat.LRC ->
                touchedLines.any(lrcOffsetPrefixPattern::containsMatchIn)
            SubtitleParser.SubtitleFormat.VTT ->
                touchedLines.any { it.contains("X-TIMESTAMP-MAP", ignoreCase = true) } ||
                    touchedLines.any(vttWordTimestampPattern::containsMatchIn) ||
                    (touchedLines.any { WebVttSubtitleFormatHandler.parseTimeLine(it) != null } &&
                        (hasDuplicateVttTimelines(before) || hasDuplicateVttTimelines(after)))
            else -> false
        }
        if (globalMarkerChanged) {
            documentHasGlobalCueContext = true
            return true
        }
        return false
    }

    private fun hasGlobalCueContext(
        content: String,
        format: SubtitleParser.SubtitleFormat
    ): Boolean = when (format) {
        SubtitleParser.SubtitleFormat.LRC -> containsLrcOffset(content)
        SubtitleParser.SubtitleFormat.VTT ->
            vttWordTimestampPattern.containsMatchIn(content) || hasDuplicateVttTimelines(content)
        else -> false
    }

    private fun containsLrcOffset(content: String): Boolean {
        var cursor = 0
        while (cursor <= content.length) {
            val end = content.indexOfAny(charArrayOf('\r', '\n'), cursor)
                .takeIf { it >= 0 } ?: content.length
            if (lrcOffsetLinePattern.matches(content.substring(cursor, end).trimStart('\uFEFF'))) {
                return true
            }
            if (end >= content.length) break
            cursor = nextLineStart(content, end) ?: break
        }
        return false
    }

    private fun hasDuplicateVttTimelines(content: String): Boolean {
        val seen = HashSet<Pair<Long, Long>>()
        var cursor = 0
        while (cursor <= content.length) {
            val end = content.indexOfAny(charArrayOf('\r', '\n'), cursor)
                .takeIf { it >= 0 } ?: content.length
            val timeline = WebVttSubtitleFormatHandler.parseTimeLine(content.substring(cursor, end))
            if (timeline != null && !seen.add(timeline.startTime to timeline.endTime)) return true
            if (end >= content.length) break
            cursor = nextLineStart(content, end) ?: break
        }
        return false
    }

    private fun lineTextAtOffset(content: String, offset: Int): String {
        val start = lineStartAtOffset(content, offset)
        return content.substring(start, lineEnd(content, start))
    }

    fun resolve(startLine: Int, oldLineCount: Int, newLineCount: Int): Update? {
        // VTT timestamp maps and merged/roll-up cue groups depend on the whole document.
        // Let the existing debounced source parser resolve these edits with that context.
        if (currentFormat() == SubtitleParser.SubtitleFormat.VTT) return null
        if (oldLineCount != newLineCount) return null
        val count = lineCount()
        if (startLine !in 0 until count) return null

        var blockStart = startLine
        while (blockStart > 0 && lineText(blockStart - 1).isNotBlank()) blockStart--
        var blockEnd = startLine
        while (blockEnd + 1 < count && lineText(blockEnd + 1).isNotBlank()) blockEnd++
        val block = buildString {
            for (line in blockStart..blockEnd) {
                if (line > blockStart) append('\n')
                append(lineText(line))
            }
        }
        val parsed = SubtitleParser.parseDocument(block, format = currentFormat()).entries
        if (parsed.size != 1) return null

        val index = cueIndexBeforeLine(blockStart)
        val current = entries().getOrNull(index) ?: return null
        val updated = parsed.single().copy(stableId = current.stableId)
        if (currentFormat() == SubtitleParser.SubtitleFormat.TXT) {
            updated.startTime = index * TXT_ENTRY_DURATION_MS
            updated.endTime = updated.startTime + TXT_ENTRY_DURATION_MS
        }
        return Update(index, updated)
    }

    /**
     * Parse only the source block touched by an edit.  The editor already has the pre-edit
     * snapshot for history, so structural changes can compare the old and new block without
     * reparsing the document around it.
     */
    fun resolveBlock(
        before: String,
        after: String,
        startLine: Int,
        oldLineCount: Int,
        newLineCount: Int,
        oldStartOffset: Int? = null,
        newStartOffset: Int? = null
    ): BlockUpdate? {
        val format = currentFormat()
        if (format == SubtitleParser.SubtitleFormat.UNKNOWN ||
            format == SubtitleParser.SubtitleFormat.ASS ||
            format == SubtitleParser.SubtitleFormat.SSA
        ) return null

        if (oldStartOffset != null && newStartOffset != null) {
            val oldBlock = blockTextAtOffset(before, oldStartOffset, oldLineCount, format)
            val newBlock = blockTextAtOffset(after, newStartOffset, newLineCount, format)
            val oldParsed = SubtitleParser.parseDocument(oldBlock.text, format = format).entries
            val newParsed = SubtitleParser.parseDocument(newBlock.text, format = format).entries
            val entryIndex = cueIndexBeforeLine(newBlock.startLine)
            val currentEntries = entries()
            if (entryIndex < 0 || entryIndex + oldParsed.size > currentEntries.size) return null
            val replacement = associateBlockStableIds(
                currentEntries, entryIndex, oldParsed, newParsed, format
            )
            if ((oldParsed.isNotEmpty() && newParsed.isEmpty()) ||
                (oldParsed.isEmpty() && replacement.isEmpty())
            ) return null
            return BlockUpdate(entryIndex, oldParsed.size, replacement)
        }

        val oldLines = splitLines(before)
        val newLines = splitLines(after)
        val oldBounds = blockBounds(oldLines, startLine, oldLineCount, format)
        val newBounds = blockBounds(newLines, startLine, newLineCount, format)
        val oldBlock = slice(oldLines, oldBounds, format)
        val newBlock = slice(newLines, newBounds, format)
        val oldParsed = SubtitleParser.parseDocument(oldBlock, format = format).entries
        val newParsed = SubtitleParser.parseDocument(newBlock, format = format).entries
        val entryIndex = countEntriesBefore(oldLines, oldBounds.first, format)
        val currentEntries = entries()
        if (entryIndex < 0 || entryIndex + oldParsed.size > currentEntries.size) return null

        val replacement = associateBlockStableIds(
            currentEntries, entryIndex, oldParsed, newParsed, format
        )
        if ((oldParsed.isNotEmpty() && newParsed.isEmpty()) ||
            (oldParsed.isEmpty() && replacement.isEmpty())
        ) return null
        return BlockUpdate(entryIndex, oldParsed.size, replacement)
    }

    private data class TextBlock(val text: String, val startLine: Int)

    private fun associateBlockStableIds(
        currentEntries: List<SubtitleEntry>,
        entryIndex: Int,
        oldParsed: List<SubtitleEntry>,
        newParsed: List<SubtitleEntry>,
        format: SubtitleParser.SubtitleFormat
    ): List<SubtitleEntry> {
        val oldEnd = (entryIndex + oldParsed.size).coerceAtMost(currentEntries.size)
        val previous = if (entryIndex in 0..oldEnd) currentEntries.subList(entryIndex, oldEnd) else emptyList()
        val globallyTimed = if (format == SubtitleParser.SubtitleFormat.TXT) {
            newParsed.mapIndexed { index, entry ->
                entry.copy(
                    startTime = (entryIndex + index) * TXT_ENTRY_DURATION_MS,
                    endTime = (entryIndex + index + 1) * TXT_ENTRY_DURATION_MS
                )
            }
        } else newParsed

        // A one-row-to-two-row split creates two new subtitle identities. For other structural
        // edits, match only against rows inside the replaced block; never borrow an ID from the
        // untouched suffix that remains in the document.
        val isSplitIntoTwo = oldParsed.size == 1 && globallyTimed.size == 2
        if (isSplitIntoTwo) return globallyTimed.map { it.copy() }
        if (oldParsed.size == globallyTimed.size) {
            return globallyTimed.mapIndexed { index, entry ->
                entry.copy(stableId = previous.getOrNull(index)?.stableId ?: entry.stableId)
            }
        }
        return SubtitleEntryOps.retainStableIds(previous, globallyTimed)
    }

    /** Extract only the affected block from the source string; do not split the whole document. */
    private fun blockTextAtOffset(
        content: String,
        offset: Int,
        affectedCount: Int,
        format: SubtitleParser.SubtitleFormat
    ): TextBlock {
        if (content.isEmpty()) return TextBlock("", 0)
        var start = lineStartAtOffset(content, offset)
        var startLine = lineIndexAtOffset(content, offset)
        var end = lineEnd(content, start)
        repeat(affectedCount.coerceAtLeast(1) - 1) {
            val next = nextLineStart(content, end) ?: return@repeat
            end = lineEnd(content, next)
        }
        if (format == SubtitleParser.SubtitleFormat.TXT) {
            return TextBlock(content.substring(start, end), startLine)
        }
        if (format == SubtitleParser.SubtitleFormat.LRC) {
            while (start > 0) {
                val previousStart = previousLineStart(content, start) ?: break
                val previousEnd = lineEnd(content, previousStart)
                if (lrcTimeTagPattern.containsMatchIn(content.substring(previousStart, previousEnd))) {
                    start = previousStart
                    startLine--
                    break
                }
                start = previousStart
                startLine--
            }
            val next = nextLineStart(content, end)
            if (next != null) end = lineEnd(content, next)
        } else {
            val touchesSeparator = content.substring(start, end).lineSequence().any { it.isBlank() }
            while (start > 0) {
                val previousStart = previousLineStart(content, start) ?: break
                val previousEnd = lineEnd(content, previousStart)
                if (content.substring(previousStart, previousEnd).isBlank()) break
                start = previousStart
                startLine--
            }
            while (true) {
                val next = nextLineStart(content, end) ?: break
                val nextEnd = lineEnd(content, next)
                if (content.substring(next, nextEnd).isBlank()) break
                end = nextEnd
            }
            if (touchesSeparator) {
                val previousStart = previousLineStart(content, start)
                if (previousStart != null) {
                    start = previousStart
                    startLine--
                }
            }
        }
        val body = content.substring(start, end)
        return TextBlock(
            if (format == SubtitleParser.SubtitleFormat.VTT &&
                !body.trimStart('\uFEFF').startsWith("WEBVTT", ignoreCase = true)
            ) vttParsePrefix(content.lineSequence()) + body else body,
            startLine
        )
    }

    /** Keep a VTT timestamp map in the block-local parse so cue times remain media-relative. */
    private fun vttParsePrefix(lines: Sequence<String>): String {
        val mapLine = lines.firstOrNull {
            it.contains("X-TIMESTAMP-MAP", ignoreCase = true)
        }
        return buildString {
            append("WEBVTT\n")
            if (mapLine != null) append(mapLine).append('\n')
            append('\n')
        }
    }

    private fun lineStartAtOffset(content: String, offset: Int): Int {
        var index = offset.coerceIn(0, content.length)
        while (index > 0 && content[index - 1] != '\n' && content[index - 1] != '\r') index--
        return index
    }

    private fun lineIndexAtOffset(content: String, offset: Int): Int {
        var count = 0
        var index = 0
        val end = offset.coerceIn(0, content.length)
        while (index < end) {
            if (content[index] == '\r') {
                count++
                if (index + 1 < end && content[index + 1] == '\n') index++
            } else if (content[index] == '\n') count++
            index++
        }
        return count
    }

    private fun lineEnd(content: String, start: Int): Int {
        var index = start.coerceIn(0, content.length)
        while (index < content.length && content[index] != '\r' && content[index] != '\n') index++
        return index
    }

    private fun nextLineStart(content: String, end: Int): Int? {
        if (end >= content.length) return null
        var index = end
        if (content[index] == '\r' && index + 1 < content.length && content[index + 1] == '\n') index++
        return index + 1
    }

    private fun previousLineStart(content: String, start: Int): Int? {
        if (start <= 0) return null
        var index = start - 1
        if (index >= 0 && content[index] == '\n' && index > 0 && content[index - 1] == '\r') index--
        while (index > 0 && content[index - 1] != '\n' && content[index - 1] != '\r') index--
        return index
    }

    private fun splitLines(content: String): List<String> =
        content.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    private fun slice(
        lines: List<String>,
        bounds: IntRange,
        format: SubtitleParser.SubtitleFormat
    ): String {
        val body = if (lines.isEmpty() || bounds.first > bounds.last || bounds.first !in lines.indices) {
            ""
        } else {
            lines.subList(bounds.first, (bounds.last + 1).coerceAtMost(lines.size)).joinToString("\n")
        }
        return if (format == SubtitleParser.SubtitleFormat.VTT &&
            !body.trimStart('\uFEFF').startsWith("WEBVTT", ignoreCase = true)
        ) {
            vttParsePrefix(lines.asSequence()) + body
        } else body
    }
    private fun blockBounds(
        lines: List<String>,
        startLine: Int,
        affectedCount: Int,
        format: SubtitleParser.SubtitleFormat
    ): IntRange {
        if (lines.isEmpty()) return 0..-1
        val safeStart = startLine.coerceIn(0, lines.lastIndex)
        val safeEnd = (safeStart + affectedCount.coerceAtLeast(1) - 1).coerceIn(safeStart, lines.lastIndex)
        if (format == SubtitleParser.SubtitleFormat.TXT) return safeStart..safeEnd

        if (format == SubtitleParser.SubtitleFormat.LRC) {
            var first = safeStart
            var last = safeEnd
            while (first > 0 && !hasLrcTimeTag(lines[first])) first--
            while (last + 1 < lines.size && !hasLrcTimeTag(lines[last + 1])) last++
            if (first > 0 && hasLrcTimeTag(lines[first - 1])) first--
            while (last + 1 < lines.size && hasLrcTimeTag(lines[last + 1])) last++
            return first..last
        }

        var first = safeStart
        var last = safeEnd
        while (first > 0 && lines[first - 1].isNotBlank()) first--
        while (last + 1 < lines.size && lines[last + 1].isNotBlank()) last++
        // Include neighboring cues only when the edit touches a blank separator. A normal
        // subtitle line split should replace one cue; deleting/inserting a separator may merge
        // or split two cues and therefore needs both sides of the boundary.
        val touchesSeparator = (safeStart..safeEnd).any { lines[it].isBlank() }
        if (touchesSeparator) {
            if (first > 0) {
                var previous = first - 1
                while (previous > 0 && lines[previous - 1].isNotBlank()) previous--
                first = previous
            }
            if (last + 1 < lines.size) {
                var next = last + 1
                while (next + 1 < lines.size && lines[next + 1].isNotBlank()) next++
                last = next
            }
        }
        return first..last
    }

    private fun hasLrcTimeTag(line: String): Boolean = lrcTimeTagPattern.containsMatchIn(line)

    private fun countEntriesBefore(
        lines: List<String>,
        lineIndex: Int,
        format: SubtitleParser.SubtitleFormat
    ): Int {
        var count = 0
        for (index in 0 until lineIndex.coerceIn(0, lines.size)) {
            val line = lines[index]
            count += when (format) {
                SubtitleParser.SubtitleFormat.SRT ->
                    if (SrtSubtitleFormatHandler.parseTimeLine(line) != null) 1 else 0
                SubtitleParser.SubtitleFormat.VTT ->
                    if (WebVttSubtitleFormatHandler.parseTimeLine(line) != null) 1 else 0
                SubtitleParser.SubtitleFormat.LRC -> lrcTimeTagPattern.findAll(line).count()
                SubtitleParser.SubtitleFormat.TXT -> if (line.isNotBlank()) 1 else 0
                else -> 0
            }
        }
        return count
    }

    private fun cueIndexBeforeLine(lineIndex: Int): Int {
        val count = lineCount()
        val map = cueIndexByLine ?: IntArray(count).also { result ->
            var cueCount = 0
            for (line in 0 until count) {
                result[line] = cueCount
                val text = lineText(line)
                cueCount += when (currentFormat()) {
                    SubtitleParser.SubtitleFormat.SRT,
                    SubtitleParser.SubtitleFormat.VTT -> if (text.contains("-->")) 1 else 0
                    SubtitleParser.SubtitleFormat.LRC -> lrcTimeTagPattern.findAll(text).count()
                    SubtitleParser.SubtitleFormat.TXT -> if (text.isNotBlank()) 1 else 0
                    else -> 0
                }
            }
        }.also { cueIndexByLine = it }
        return map.getOrElse(lineIndex) { entries().size }
    }

    private companion object {
        const val TXT_ENTRY_DURATION_MS = 3_000L
    }
}
