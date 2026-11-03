package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleColorOps
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.SubtitleSourceSynchronizer
import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.util.subtitle.WebVttSubtitleFormatHandler

internal data class EditorFormatHistory(
    val before: EditorEditHistory.ListState,
    val after: EditorEditHistory.ListState,
    val beforeSource: String,
    val afterSource: String,
    val beforeHeader: String,
    val afterHeader: String,
    val beforeFooter: String,
    val afterFooter: String,
    val description: String
)

/** Formatting changes prepare only changed rows and finish all fallible raw-source work first. */
internal class EditorFormatEditSession(
    private val state: EditorDocumentState,
    private val recordHistory: (EditorFormatHistory) -> Unit,
    private val synchronizeSource: (
        String, SubtitleParser.SubtitleFormat, List<SubtitleEntry>, List<SubtitleEntry>
    ) -> String = SubtitleSourceSynchronizer::apply,
    private val rewriteVttSections: (String, String, String, String, String) -> String = ::rewriteSections
) {
    fun applyColor(positions: Set<Int>, argb: Int, selectedIds: Set<Long>): EditorCommandResult {
        val before = borrowedDocument()
        return commit(SubtitleColorOps.apply(before, positions, argb), selectedIds, "颜色", normalizeTextMetadata = true)
    }

    fun applyVtt(updated: SubtitleDocument, selectedIds: Set<Long>, description: String): EditorCommandResult {
        if (state.currentFormat != SubtitleParser.SubtitleFormat.VTT) return EditorCommandResult()
        return commit(updated, selectedIds, description)
    }

    fun applyVttEdit(
        selectedIds: Set<Long>,
        description: String,
        transform: (SubtitleDocument) -> SubtitleDocument
    ): EditorCommandResult {
        if (state.currentFormat != SubtitleParser.SubtitleFormat.VTT) return EditorCommandResult()
        return commit(transform(borrowedDocument()), selectedIds, description)
    }

    private fun borrowedDocument() = SubtitleDocument(
        state.currentFormat, state.subtitleEntries, state.documentHeader,
        state.documentFooter, state.documentLrcVariant
    )

    private fun commit(
        updated: SubtitleDocument,
        selectedIds: Set<Long>,
        description: String,
        normalizeTextMetadata: Boolean = false
    ): EditorCommandResult {
        val current = state.subtitleEntries
        if (updated.format != state.currentFormat || updated.entries.size != current.size ||
            updated.lrcVariant != state.documentLrcVariant
        ) return EditorCommandResult()
        val changes = linkedMapOf<Int, SubtitleEntry>()
        if (updated.entries !== current) {
            for (position in current.indices) {
                val before = current[position]
                val candidate = updated.entries[position]
                // This boundary owns formatting, not replacement/reordering or timing edits.
                if (candidate.stableId != before.stableId || candidate.index != before.index ||
                    candidate.startTime != before.startTime || candidate.endTime != before.endTime ||
                    candidate.endTimeModified != before.endTimeModified
                ) return EditorCommandResult()
                if (candidate === before || candidate == before) continue
                val prepared = candidate.copy()
                if (normalizeTextMetadata && state.currentFormat == SubtitleParser.SubtitleFormat.VTT) {
                    prepared.cueSettings = WebVttSubtitleFormatHandler.getCueSettings(prepared)
                }
                if (prepared != before) changes[position] = prepared
            }
        }
        val metadataChanged = updated.header != state.documentHeader || updated.footer != state.documentFooter
        if (changes.isEmpty() && !metadataChanged) return EditorCommandResult()

        val afterEntries = object : AbstractList<SubtitleEntry>() {
            override val size: Int get() = current.size
            override fun get(index: Int): SubtitleEntry = changes[index] ?: current[index]
        }
        val beforeSource = if (state.sourceViewNeedsListSync) {
            synchronizeSource(state.originalFileContent, state.currentFormat, state.historyEntriesSnapshot, current)
        } else state.originalFileContent
        var afterSource = if (changes.isEmpty()) beforeSource else
            synchronizeSource(beforeSource, state.currentFormat, current, afterEntries)
        if (state.currentFormat == SubtitleParser.SubtitleFormat.VTT && metadataChanged) {
            afterSource = rewriteVttSections(
                afterSource, state.documentHeader, updated.header, state.documentFooter, updated.footer
            )
        }

        // The existing owned history baseline is reusable. Legacy pending edits
        // may differ from it, so detach only those rows before recording this edit.
        val baseline = ownedCurrentRows(current)
        recordHistory(EditorFormatHistory(
            EditorEditHistory.ListState(baseline, selectedIds.toSet()),
            EditorEditHistory.ListState(afterEntries, selectedIds.toSet()),
            beforeSource, afterSource, state.documentHeader, updated.header,
            state.documentFooter, updated.footer, description
        ))
        changes.forEach { (position, prepared) -> EditorDocumentOperations.updateFields(current[position], prepared) }
        state.documentHeader = updated.header
        state.documentFooter = updated.footer
        state.originalFileContent = afterSource
        state.sourceViewContent = afterSource
        state.sourceHistoryTextSnapshot = afterSource
        state.sourceViewNeedsListSync = false
        state.historyBaselineInitialized = true
        state.hasUnsavedChanges = true
        return EditorCommandResult(changedPositions = changes.keys.toSet(), documentMetadataChanged = metadataChanged)
    }

    private fun ownedCurrentRows(current: List<SubtitleEntry>): List<SubtitleEntry> {
        val baseline = state.historyEntriesSnapshot
        if (!state.historyBaselineInitialized || baseline.size != current.size ||
            baseline.indices.any { baseline[it].stableId != current[it].stableId }
        ) return current.map { it.copy() }.toMutableList()
        var result: MutableList<SubtitleEntry>? = null
        current.forEachIndexed { position, entry ->
            if (baseline[position] !== entry && baseline[position] == entry) return@forEachIndexed
            if (result == null) result = baseline.toMutableList()
            result!![position] = entry.copy()
        }
        return result ?: baseline
    }

    companion object {
        /** Rewrite metadata sections without constructing another cue parse tree. */
        internal fun rewriteSections(
            source: String, oldHeader: String, newHeader: String, oldFooter: String, newFooter: String
        ): String {
            var result = source
            if (oldHeader != newHeader) result = rewriteHeader(result, oldHeader, newHeader)
            if (oldFooter != newFooter) result = rewriteFooter(result, oldFooter, newFooter)
            return result
        }

        private data class RawLine(val start: Int, val end: Int, val next: Int, val text: String)

        private fun readLine(source: String, start: Int): RawLine {
            var end = start
            while (end < source.length && source[end] != '\r' && source[end] != '\n') end++
            val next = end + when {
                end >= source.length -> 0
                source[end] == '\r' && source.getOrNull(end + 1) == '\n' -> 2
                else -> 1
            }
            return RawLine(start, end, next, source.substring(start, end))
        }

        private fun isMetadataStart(text: String): Boolean =
            text == "NOTE" || text.startsWith("NOTE ") || text == "STYLE" || text.startsWith("STYLE ") ||
                text == "REGION" || text.startsWith("REGION ")

        private fun isTimestampMap(text: String): Boolean = WebVttSubtitleFormatHandler.isTimestampMap(text)

        private fun firstCueStart(source: String): Int? {
            var cursor = 0
            var candidateIdentifier: Int? = null
            var insideMetadata = false
            while (cursor < source.length) {
                val line = readLine(source, cursor)
                val trimmed = line.text.trimStart('\uFEFF').trim()
                when {
                    trimmed.isEmpty() -> { candidateIdentifier = null; insideMetadata = false }
                    insideMetadata -> Unit
                    isMetadataStart(trimmed) -> { insideMetadata = true; candidateIdentifier = null }
                    isTimestampMap(trimmed) || trimmed == "WEBVTT" ||
                        (cursor == 0 && trimmed.startsWith("WEBVTT", ignoreCase = true)) -> candidateIdentifier = null
                    '>' in trimmed && WebVttSubtitleFormatHandler.parseTimeLine(line.text) != null ->
                        return candidateIdentifier ?: line.start
                    else -> candidateIdentifier = line.start
                }
                if (line.next == cursor) break
                cursor = line.next
            }
            return null
        }

        private fun preferredEnding(source: String): String {
            val index = source.indexOfAny(charArrayOf('\r', '\n'))
            return if (index < 0) "\n" else if (source[index] == '\r' && source.getOrNull(index + 1) == '\n') "\r\n"
                else source[index].toString()
        }

        private fun normalizeLines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

        private fun rewriteHeader(source: String, oldHeader: String, newHeader: String): String {
            val cueStart = firstCueStart(source)
            val ending = preferredEnding(source)
            val prefixEnd = cueStart ?: source.length
            val prefix = source.substring(0, prefixEnd)
            if (newHeader.startsWith(oldHeader) && oldHeader.isNotEmpty()) {
                val addition = normalizeLines(newHeader.removePrefix(oldHeader)).trim()
                if (addition.startsWith("STYLE")) {
                    val separator = when {
                        prefix.endsWith(ending + ending) -> ""
                        prefix.endsWith(ending) -> ending
                        else -> ending + ending
                    }
                    return prefix + separator + addition.replace("\n", ending) + ending + ending + source.substring(prefixEnd)
                }
            }
            val maps = mutableListOf<String>()
            var cursor = 0
            while (cursor < prefix.length) {
                val line = readLine(prefix, cursor)
                if (isTimestampMap(line.text.trim())) maps += line.text
                if (line.next == cursor) break
                cursor = line.next
            }
            val header = normalizeLines(newHeader.removePrefix("\uFEFF")).trimEnd()
            val firstEnd = header.indexOf('\n').let { if (it < 0) header.length else it }
            val preservedMaps = maps.filter { map -> header.lineSequence().none { it.trim() == map.trim() } }
            val combined = if (preservedMaps.isEmpty()) header else buildString {
                append(header, 0, firstEnd).append('\n')
                preservedMaps.forEach { append(it).append('\n') }
                append(header.substring(firstEnd).removePrefix("\n"))
            }.trimEnd()
            val bom = if (source.startsWith('\uFEFF')) "\uFEFF" else ""
            return bom + combined.replace("\n", ending) +
                (if (cueStart == null) "" else ending + ending + source.substring(cueStart))
        }

        private fun rewriteFooter(source: String, oldFooter: String, newFooter: String): String {
            val firstCue = firstCueStart(source)
            require(firstCue != null || oldFooter.isBlank()) { "WebVTT footer has no cue boundary" }
            val spans = mutableListOf<IntRange>()
            var cursor = firstCue ?: source.length
            var previousBlank = true
            while (cursor < source.length) {
                val line = readLine(source, cursor)
                val trimmed = line.text.trim()
                if (previousBlank && isMetadataStart(trimmed)) {
                    val start = cursor
                    var end = line.next
                    while (end < source.length) {
                        val following = readLine(source, end)
                        if (following.text.isBlank()) break
                        end = following.next
                    }
                    spans += start until end
                    cursor = end
                    previousBlank = true
                    continue
                }
                previousBlank = line.text.isBlank()
                if (line.next == cursor) break
                cursor = line.next
            }
            val found = spans.joinToString("\n\n") { normalizeLines(source.substring(it)).trim() }.trim()
            require(normalizeLines(oldFooter).trim() == found) { "WebVTT footer does not match the raw document" }
            val ending = preferredEnding(source)
            val replacement = normalizeLines(newFooter).trim().replace("\n", ending)
            if (spans.isEmpty()) {
                if (replacement.isEmpty()) return source
                return source.trimEnd('\r', '\n') + ending + ending + replacement + ending
            }
            return buildString {
                var from = 0
                spans.forEachIndexed { index, span ->
                    append(source, from, span.first)
                    if (index == 0 && replacement.isNotEmpty()) append(replacement).append(ending)
                    from = span.last + 1
                }
                append(source, from, source.length)
            }
        }
    }
}
