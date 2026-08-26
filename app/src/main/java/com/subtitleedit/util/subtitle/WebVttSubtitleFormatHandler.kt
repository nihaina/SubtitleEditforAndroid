package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import java.util.Locale

object WebVttSubtitleFormatHandler : SubtitleFormatHandler {
    override val format = SubtitleParser.SubtitleFormat.VTT
    override val extensions = setOf("vtt", "webvtt")

    private val timestampPattern = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{1,2})[.](\d{1,4})$""")
    private val timestampMapPattern = Regex("""^X-TIMESTAMP-MAP\s*=\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val localTimestampPattern = Regex("""LOCAL\s*:\s*([0-9:.]+)""", RegexOption.IGNORE_CASE)
    private val mpegTsPattern = Regex("""MPEGTS\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)

    override fun isMine(lines: List<String>, fileName: String?): Boolean {
        if (hasWebVttSignature(lines)) return true

        val extension = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return extension in extensions && lines.any { parseTimeLine(it) != null }
    }

    override fun load(lines: List<String>, fileName: String?): SubtitleDocument {
        val entries = mutableListOf<SubtitleEntry>()
        val header = mutableListOf<String>()
        val footer = mutableListOf<String>()
        var index = 0
        var timestampOffsetMs = 0L

        if (hasWebVttSignature(lines)) {
            header += lines.first().trimStart('\uFEFF')
            index = 1

            // The signature may be followed by header metadata without a blank separator.
            while (index < lines.size && lines[index].isNotBlank()) {
                if (parseTimeLine(lines[index]) != null) break
                updateTimestampOffset(lines[index])?.let { timestampOffsetMs = it }
                    ?: header.add(lines[index])
                index++
            }
        } else {
            // Subtitle Edit also loads headerless WebVTT when the file extension selected
            // this handler. Add the required signature only when serializing it again.
            header += "WEBVTT"
        }
        while (index < lines.size && lines[index].isBlank()) index++

        var hasSeenCue = false
        splitBlocks(lines, index).forEach { block ->
            if (block.isEmpty()) return@forEach

            if (isMetadataBlock(block)) {
                val destination = if (hasSeenCue) footer else header
                appendBlock(destination, block)
                return@forEach
            }

            // Subtitle Edit consumes X-TIMESTAMP-MAP as a time-base conversion. Keeping it
            // after converting the cue times would apply the offset twice on the next load.
            if (block.size == 1 && updateTimestampOffset(block.first())?.also {
                    timestampOffsetMs = it
                } != null
            ) {
                return@forEach
            }

            val cue = parseCueBlock(block, timestampOffsetMs)
            if (cue != null) {
                entries += cue.copy(index = entries.size + 1)
                hasSeenCue = true
            } else {
                val destination = if (hasSeenCue) footer else header
                appendBlock(destination, block)
            }
        }

        return SubtitleDocument(
            format = format,
            entries = entries,
            header = normalizeHeader(header),
            footer = normalizeSection(footer)
        )
    }

    override fun write(document: SubtitleDocument): String = buildString {
        val header = normalizeHeader(document.header.toSubtitleLines())
        appendLine(header)
        appendLine()

        document.entries.forEach { entry ->
            if (entry.cueIdentifier.isNotBlank()) appendLine(entry.cueIdentifier)
            append(formatTimestamp(entry.startTime))
            append(" --> ")
            append(formatTimestamp(entry.endTime))
            if (entry.cueSettings.isNotBlank()) {
                append(' ')
                append(entry.cueSettings.trim())
            }
            appendLine()
            appendLine(entry.text)
            appendLine()
        }

        val footer = normalizeSection(document.footer.toSubtitleLines())
        if (footer.isNotBlank()) appendLine(footer)
    }.trimEnd() + "\n"

    private data class TimeLine(val startTime: Long, val endTime: Long, val settings: String)

    private fun parseTimeLine(line: String): TimeLine? {
        val arrowIndex = line.indexOf("-->")
        if (arrowIndex < 0) return null
        val startText = line.substring(0, arrowIndex).trim()
        val remainder = line.substring(arrowIndex + 3).trim()
        val settingsStart = remainder.indexOfFirst { it.isWhitespace() }
        val endText = if (settingsStart < 0) remainder else remainder.substring(0, settingsStart)
        val settings = if (settingsStart < 0) "" else remainder.substring(settingsStart + 1).trim()
        val start = parseTimestamp(startText) ?: return null
        val end = parseTimestamp(endText) ?: return null
        return TimeLine(start, end, settings)
    }

    private fun parseTimestamp(value: String): Long? {
        val match = timestampPattern.matchEntire(value) ?: return null
        val hours = match.groupValues[1].ifEmpty { "0" }.toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val millis = match.groupValues[4].take(3).padEnd(3, '0').toLongOrNull() ?: return null
        if (minutes !in 0..59 || seconds !in 0..59) return null
        return (hours * 3_600_000 + minutes * 60_000 + seconds * 1_000 + millis)
            .coerceAtLeast(0L)
    }

    private fun parseCueBlock(block: List<String>, timestampOffsetMs: Long): SubtitleEntry? {
        var identifier = ""
        var timeLine = parseTimeLine(block.first())
        var textStartIndex = 1
        if (timeLine == null && block.size >= 2) {
            timeLine = parseTimeLine(block[1])
            if (timeLine != null) {
                identifier = block.first()
                textStartIndex = 2
            }
        }
        val parsedTimeLine = timeLine ?: return null
        return SubtitleEntry(
            startTime = (parsedTimeLine.startTime + timestampOffsetMs).coerceAtLeast(0L),
            endTime = (parsedTimeLine.endTime + timestampOffsetMs).coerceAtLeast(0L),
            text = block.drop(textStartIndex).joinToString("\n") { it.trimEnd() },
            cueIdentifier = identifier,
            cueSettings = parsedTimeLine.settings
        )
    }

    private fun splitBlocks(lines: List<String>, startIndex: Int): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        for (index in startIndex until lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                if (current.isNotEmpty()) {
                    blocks += current.toList()
                    current.clear()
                }
            } else {
                current += line
            }
        }
        if (current.isNotEmpty()) blocks += current
        return blocks
    }

    private fun isMetadataBlock(block: List<String>): Boolean {
        val first = block.firstOrNull()?.trim().orEmpty()
        return first == "NOTE" || first.startsWith("NOTE ") ||
            first == "STYLE" || first.startsWith("STYLE ") ||
            first == "REGION" || first.startsWith("REGION ")
    }

    private fun appendBlock(destination: MutableList<String>, block: List<String>) {
        if (destination.isNotEmpty() && destination.last().isNotEmpty()) destination += ""
        destination += block
    }

    private fun updateTimestampOffset(line: String): Long? {
        if (!timestampMapPattern.matches(line.trim())) return null
        val local = localTimestampPattern.find(line)?.groupValues?.getOrNull(1)
            ?.let(::parseTimestamp) ?: return null
        val mpegTs = mpegTsPattern.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return null
        val offset = mpegTs * 1_000L / 90_000L - local
        // Match Subtitle Edit's protective range: accept only a plausible positive media offset.
        return offset.takeIf { it in 0 until 90_000_000L }
    }

    private fun formatTimestamp(timeMs: Long): String {
        val safeTime = timeMs.coerceAtLeast(0L)
        val hours = safeTime / 3_600_000
        val minutes = safeTime % 3_600_000 / 60_000
        val seconds = safeTime % 60_000 / 1_000
        val millis = safeTime % 1_000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    private fun normalizeHeader(lines: List<String>): String {
        val result = lines
            .filterNot { timestampMapPattern.matches(it.trim()) }
            .dropLastWhile { it.isBlank() }
            .toMutableList()
        if (result.isEmpty() || !result.first().startsWith("WEBVTT", ignoreCase = true)) {
            result.add(0, "WEBVTT")
        }
        return result.joinToString("\n")
    }

    private fun normalizeSection(lines: List<String>): String = lines
        .filterNot { timestampMapPattern.matches(it.trim()) }
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
        .joinToString("\n")

    private fun hasWebVttSignature(lines: List<String>): Boolean =
        lines.firstOrNull()?.trimStart('\uFEFF')
            ?.startsWith("WEBVTT", ignoreCase = true) == true
}
