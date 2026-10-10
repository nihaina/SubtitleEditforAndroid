package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class WebVttTimeLine(
    val startTime: Long,
    val endTime: Long,
    val settings: String,
    val startRange: IntRange,
    val endRange: IntRange
)

internal data class WebVttCueLocation(
    val identifierLineIndex: Int?,
    val timeLineIndex: Int,
    val endExclusive: Int,
    val timestampOffsetMs: Long
)

internal data class WebVttParseResult(
    val document: SubtitleDocument,
    val errorCount: Int,
    val cues: List<List<WebVttCueLocation>>,
    val rawCues: List<WebVttCueLocation>,
    val finalTimestampOffsetMs: Long,
    val timestampMapLineIndices: List<Int>,
    val hasTextTransformations: Boolean
)

object WebVttSubtitleFormatHandler : SubtitleFormatHandler {
    override val format = SubtitleParser.SubtitleFormat.VTT
    override val extensions = setOf("vtt", "webvtt")

    private const val timestampToken = """-?\d+:-?\d+(?::-?\d+)?\.-?\d+"""
    private val timelinePattern = Regex("""^\s*($timestampToken)\s*-->\s*($timestampToken)(.*)$""")
    private val timestampPattern = Regex("""^(?:(-?\d+):)?(-?\d+):(-?\d+)\.(-?\d+)$""")
    private val timestampMapPattern = Regex("""^X-TIMESTAMP-MAP\s*=\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val localTimestampPattern = Regex("""LOCAL\s*:\s*([0-9:.]+)""", RegexOption.IGNORE_CASE)
    private val mpegTsPattern = Regex("""MPEGTS\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val mpegTsKey = Regex("""MPEGTS\s*:""", RegexOption.IGNORE_CASE)
    private val numericIdentifier = Regex("""\d+(?:x\d+)?""")

    override fun isMine(lines: List<String>, fileName: String?): Boolean {
        val parsed = parse(lines)
        return parsed.document.entries.size > parsed.errorCount
    }

    override fun load(lines: List<String>, fileName: String?): SubtitleDocument = parse(lines).document

    internal fun parse(lines: List<String>, decodeEntities: Boolean = true): WebVttParseResult {
        val prepared = lines.mapIndexed { index, line -> if (index == 0) line.removePrefix("\uFEFF") else line }
        val header = mutableListOf<String>()
        val footer = mutableListOf<String>()
        val rows = mutableListOf<WebVttParsedCue>()
        val rawCues = mutableListOf<WebVttCueLocation>()
        val maps = mutableListOf<Int>()
        var offset = 0L
        var errors = 0
        var current: SubtitleEntry? = null
        var identifierLine: Int? = null
        var pendingIdentifier: Int? = null
        var timeLineIndex = -1
        var cueOffset = 0L
        val text = mutableListOf<String>()
        var index = 0

        if (prepared.firstOrNull()?.startsWith("WEBVTT", ignoreCase = true) == true) {
            header += prepared.first()
            index++
        } else header += "WEBVTT"

        fun finish(at: Int) {
            val entry = current ?: return
            val location = WebVttCueLocation(identifierLine, timeLineIndex, at, cueOffset)
            val cueText = text.joinToString("\n").trimEnd()
            entry.text = WebVttCueProcessing.removeRepeatingHeader(
                if (decodeEntities) WebVttTextFormatting.decode(cueText) else cueText
            )
            rows += WebVttParsedCue(entry, mutableListOf(location))
            rawCues += location
            current = null
            identifierLine = null
            text.clear()
        }

        while (index < prepared.size) {
            val line = prepared[index]
            val trimmed = line.trim()
            val previousBlank = index == 0 || prepared[index - 1].isBlank()
            val nextTimeLine = prepared.getOrNull(index + 1)?.let(::parseTimeLine)

            if (isMetadataStart(trimmed) && (previousBlank || current == null)) {
                finish(index)
                val start = index
                while (index < prepared.size && prepared[index].isNotBlank()) index++
                appendBlock(if (rows.isEmpty()) header else footer, prepared.subList(start, index))
                pendingIdentifier = null
                continue
            }

            if (isTimestampMap(trimmed)) {
                finish(index)
                offset = timestampOffset(trimmed)
                maps += index
                pendingIdentifier = null
                index++
                continue
            }

            if (trimmed == "WEBVTT") {
                index++
                continue
            }

            val timeLine = parseTimeLine(line)
            if (timeLine != null) {
                finish(pendingIdentifier ?: index)
                identifierLine = pendingIdentifier
                pendingIdentifier = null
                timeLineIndex = index
                cueOffset = offset
                current = SubtitleEntry(
                    startTime = timeLine.startTime + offset,
                    endTime = timeLine.endTime + offset,
                    cueIdentifier = identifierLine?.let { prepared[it] }.orEmpty(),
                    cueSettings = timeLine.settings
                )
            } else if (timelinePattern.containsMatchIn(line)) {
                // Count recognized time codes that fail integer parsing, as Subtitle Edit
                // does; an arbitrary arrow in caption text is not a parse error.
                finish(index)
                pendingIdentifier = null
                errors++
            } else if (nextTimeLine != null && line.isNotBlank() &&
                (current == null || previousBlank ||
                    numericIdentifier.matches(trimmed.trim('$', '\u00A3', '\u00A5', '%', '*')) && text.isNotEmpty())
            ) {
                finish(index)
                pendingIdentifier = index
            } else if (current != null) {
                val prefix = if (text.isEmpty()) WebVttCueProcessing.positionInfo(current!!.cueSettings) else ""
                text += prefix + trimmed
            } else if (line.isNotBlank()) {
                appendBlock(if (rows.isEmpty()) header else footer, listOf(line))
            }
            index++
        }
        finish(prepared.size)

        val processed = WebVttCueProcessing.process(rows)
        val document = SubtitleDocument(
            format,
            processed.mapIndexed { cueIndex, row -> row.entry.copy(index = cueIndex + 1) },
            normalizeHeader(header),
            normalizeSection(footer)
        )
        return WebVttParseResult(
            document, errors, processed.map { it.locations.toList() }, rawCues,
            offset, maps, processed.size != rawCues.size || processed.any { it.textTransformed }
        )
    }

    override fun write(document: SubtitleDocument): String {
        val prepared = WebVttTextFormatting.prepareDocument(document)
        return buildString {
            appendLine(normalizeHeader(prepared.header.toSubtitleLines()))
            appendLine()
            prepared.entries.forEach { entry ->
                if (entry.cueIdentifier.isNotBlank()) appendLine(entry.cueIdentifier)
                append(formatTimestamp(entry.startTime)).append(" --> ").append(formatTimestamp(entry.endTime))
                val settings = getCueSettings(entry)
                if (settings.isNotBlank()) append(' ').append(settings)
                appendLine()
                appendLine(WebVttTextFormatting.writeText(entry.text))
                appendLine()
            }
            val footer = normalizeSection(prepared.footer.toSubtitleLines())
            if (footer.isNotBlank()) appendLine(footer)
        }.trim()
    }

    internal fun getCueSettings(entry: SubtitleEntry): String = WebVttCueProcessing.settingsFor(entry)

    internal fun updateCueSettings(entry: SubtitleEntry, settings: String) = WebVttCueProcessing.updateSettings(entry, settings)

    internal fun parseTimeLine(line: String): WebVttTimeLine? {
        val match = timelinePattern.matchEntire(line) ?: return null
        val suffix = match.groupValues[3]
        if (suffix.isNotEmpty() && !suffix.first().isWhitespace()) return null
        return WebVttTimeLine(
            parseTimestamp(match.groupValues[1]) ?: return null,
            parseTimestamp(match.groupValues[2]) ?: return null,
            suffix.trim(), match.groups[1]!!.range, match.groups[2]!!.range
        )
    }

    internal fun rewriteTimeLine(line: String, startTime: Long, endTime: Long): String? {
        val parsed = parseTimeLine(line) ?: return null
        return line.substring(0, parsed.startRange.first) + formatTimestamp(startTime) +
            line.substring(parsed.startRange.last + 1, parsed.endRange.first) + formatTimestamp(endTime) +
            line.substring(parsed.endRange.last + 1)
    }

    private fun parseTimestamp(value: String): Long? {
        val match = timestampPattern.matchEntire(value) ?: return null
        val hours = match.groupValues[1].ifEmpty { "0" }.toIntOrNull() ?: return null
        val minutes = match.groupValues[2].toIntOrNull() ?: return null
        val seconds = match.groupValues[3].toIntOrNull() ?: return null
        val millis = match.groupValues[4].toIntOrNull() ?: return null
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun timestampOffset(line: String): Long {
        val compact = line.replace(" ", "")
        val local = localTimestampPattern.find(compact)?.groupValues?.get(1)?.let(::parseTimestamp) ?: 0L
        val mpegTs = mpegTsPattern.find(compact)?.groupValues?.get(1)?.toLongOrNull() ?: return 0L
        val offset = mpegTs.toDouble() * 1_000 / 90_000 - local
        return if (offset > 0 && offset < 90_000_000) offset.roundToLong() else 0L
    }

    internal fun formatTimestamp(timeMs: Long): String {
        fun component(value: Long, digits: Int): String =
            (if (value < 0) "-" else "") + abs(value).toString().padStart(digits, '0')
        return component(timeMs / 3_600_000, 2) + ":" + component(timeMs % 3_600_000 / 60_000, 2) +
            ":" + component(timeMs % 60_000 / 1_000, 2) + "." + component(timeMs % 1_000, 3)
    }

    private fun isMetadataStart(line: String): Boolean = listOf("NOTE", "STYLE", "REGION").any {
        line == it || line.startsWith("$it ")
    }

    private fun isTimestampMap(line: String): Boolean =
        timestampMapPattern.matches(line) && mpegTsKey.containsMatchIn(line)

    private fun appendBlock(destination: MutableList<String>, block: List<String>) {
        if (destination.isNotEmpty() && destination.last().isNotEmpty()) destination += ""
        destination += block
    }

    private fun normalizeHeader(lines: List<String>): String {
        val result = lines.filterNot { isTimestampMap(it.trim()) }.toMutableList()
        if (result.firstOrNull()?.startsWith("WEBVTT", ignoreCase = true) != true) result.add(0, "WEBVTT")
        return result.joinToString("\n").trimEnd()
    }

    private fun normalizeSection(lines: List<String>): String = lines
        .filterNot { isTimestampMap(it.trim()) }.joinToString("\n").trim()
}
