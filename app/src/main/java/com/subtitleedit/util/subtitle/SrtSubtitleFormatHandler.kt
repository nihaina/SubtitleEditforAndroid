package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TimeUtils

object SrtSubtitleFormatHandler : SubtitleFormatHandler {
    override val format = SubtitleParser.SubtitleFormat.SRT
    override val extensions = setOf("srt", "wsrt")

    private val timeLinePattern = Regex(
        """^\s*(-?\d{1,3}[:.]\d{1,2}[:.]\d{1,2}(?:[,.;:]\d{1,4})?)\s*(?:-->|->|—>|——>|-+\s*>)\s*(-?\d{1,3}[:.]\d{1,2}[:.]\d{1,2}(?:[,.;:]\d{1,4})?)(?:\s+.*)?$"""
    )
    private val timestampPattern = Regex(
        """^(-?\d{1,3})[:.](\d{1,2})[:.](\d{1,2})(?:[,.;:](\d{1,4}))?$"""
    )

    override fun isMine(lines: List<String>, fileName: String?): Boolean {
        if (lines.firstOrNull()?.trimStart()?.startsWith("WEBVTT", ignoreCase = true) == true) {
            return false
        }
        return load(lines, fileName).entries.isNotEmpty()
    }

    override fun load(lines: List<String>, fileName: String?): SubtitleDocument {
        val entries = mutableListOf<SubtitleEntry>()
        var index = 0

        while (index < lines.size) {
            val timeCodes = readTimeLine(lines[index])
            if (timeCodes == null) {
                index++
                continue
            }

            index++
            val textLines = mutableListOf<String>()
            while (index < lines.size) {
                val line = lines[index]
                val next = lines.getOrNull(index + 1)
                val nextNext = lines.getOrNull(index + 2)
                val startsNextCue = readTimeLine(line) != null ||
                    (line.trim().toIntOrNull() != null && readTimeLine(next.orEmpty()) != null) ||
                    (line.isBlank() && next?.trim()?.toIntOrNull() != null &&
                        readTimeLine(nextNext.orEmpty()) != null) ||
                    (line.isBlank() && readTimeLine(next.orEmpty()) != null)

                if (startsNextCue) break
                textLines += line.replace('\u0000', ' ').trimEnd()
                index++
            }

            val text = textLines.dropLastWhile { it.isBlank() }.joinToString("\n")
            if (text.isNotEmpty()) {
                entries += SubtitleEntry(
                    index = entries.size + 1,
                    startTime = timeCodes.first,
                    endTime = timeCodes.second,
                    text = if (fileName?.endsWith(".wsrt", ignoreCase = true) == true) {
                        text.replace(Regex("<3\\d>"), "<i>")
                            .replace(Regex("</3\\d>"), "</i>")
                    } else {
                        text
                    }
                )
            }

            while (index < lines.size && lines[index].isBlank()) index++
            if (index < lines.size && lines[index].trim().toIntOrNull() != null &&
                readTimeLine(lines.getOrNull(index + 1).orEmpty()) != null
            ) {
                index++
            }
        }

        return SubtitleDocument(format, entries)
    }

    override fun write(document: SubtitleDocument): String = buildString {
        document.entries.forEachIndexed { index, entry ->
            appendLine(index + 1)
            append(TimeUtils.formatSRT(entry.startTime))
            append(" --> ")
            appendLine(TimeUtils.formatSRT(entry.endTime))
            appendLine(entry.text)
            appendLine()
        }
    }

    private fun readTimeLine(input: String): Pair<Long, Long>? {
        if ('>' !in input) return null
        val normalized = input
            .replace('،', ',')
            .replace('\u200B', ' ')
            .replace('\uFEFF', ' ')
            .trim()
        val match = timeLinePattern.matchEntire(normalized) ?: return null
        val start = parseTimestamp(match.groupValues[1]) ?: return null
        val end = parseTimestamp(match.groupValues[2]) ?: return null
        return start to end
    }

    private fun parseTimestamp(value: String): Long? {
        val match = timestampPattern.matchEntire(value.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val fraction = match.groupValues[4]
        val millis = if (fraction.isEmpty()) 0L else fraction.take(3).padEnd(3, '0').toLong()
        return (hours * 3_600_000 + minutes * 60_000 + seconds * 1_000 + millis)
            .coerceAtLeast(0L)
    }
}
