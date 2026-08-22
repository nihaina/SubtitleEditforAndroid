package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TimeUtils

object LrcSubtitleFormatHandler : SubtitleFormatHandler {
    override val format = SubtitleParser.SubtitleFormat.LRC
    override val extensions = setOf("lrc")

    private val timeTagPattern = Regex("""\[(-?\d{1,4}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val metadataPattern = Regex("""^\[([A-Za-z][A-Za-z0-9_-]*):(.*)]\s*$""")
    private val offsetPattern = Regex("""^\[offset:([+-]?\d+)]\s*$""", RegexOption.IGNORE_CASE)

    private data class TimedText(val timeMs: Long, val text: String)

    override fun isMine(lines: List<String>, fileName: String?): Boolean =
        lines.any { timeTagPattern.containsMatchIn(it) }

    override fun load(lines: List<String>, fileName: String?): SubtitleDocument {
        val metadata = mutableListOf<String>()
        val timedTexts = mutableListOf<TimedText>()
        val offsetMs = lines.asSequence()
            .mapNotNull { offsetPattern.matchEntire(it.trim()) }
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .lastOrNull() ?: 0L

        lines.forEach { rawLine ->
            val offsetMatch = offsetPattern.matchEntire(rawLine.trim())
            if (offsetMatch != null) {
                // 时间已经应用 offset；写回时不保留该标签，避免再次打开后重复偏移。
                return@forEach
            }

            val matches = timeTagPattern.findAll(rawLine).toList()
            if (matches.isEmpty()) {
                if (metadataPattern.matches(rawLine.trim()) && rawLine.isNotBlank()) {
                    metadata += rawLine
                }
                return@forEach
            }

            val text = rawLine.substring(matches.last().range.last + 1).trim()
            matches.forEach matchLoop@ { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@matchLoop
                val seconds = match.groupValues[2].toLongOrNull() ?: return@matchLoop
                val fraction = match.groupValues[3]
                val millis = if (fraction.isEmpty()) 0L else {
                    fraction.take(3).padEnd(3, '0').toLong()
                }
                timedTexts += TimedText(
                    (minutes * 60_000 + seconds * 1_000 + millis + offsetMs).coerceAtLeast(0L),
                    text
                )
            }
        }

        val entries = mutableListOf<SubtitleEntry>()
        timedTexts.forEachIndexed { index, timedText ->
            if (timedText.text.isEmpty()) return@forEachIndexed

            var endTime = timedText.timeMs + DEFAULT_DURATION_MS
            var explicitEnd = false
            val next = timedTexts.getOrNull(index + 1)
            if (next != null) {
                if (next.text.isEmpty()) {
                    endTime = next.timeMs
                    explicitEnd = true
                } else if (next.timeMs < endTime) {
                    endTime = next.timeMs
                }
            }
            if (endTime <= timedText.timeMs) endTime = timedText.timeMs + 1

            entries += SubtitleEntry(
                index = entries.size + 1,
                startTime = timedText.timeMs,
                endTime = endTime,
                text = timedText.text,
                endTimeModified = explicitEnd
            )
        }

        return SubtitleDocument(format, entries, header = metadata.joinToString("\n"))
    }

    override fun write(document: SubtitleDocument): String = buildString {
        if (document.header.isNotBlank()) appendLine(document.header.trimEnd())
        document.entries.forEachIndexed { index, entry ->
            appendLine("${TimeUtils.formatLRC(entry.startTime)}${entry.text}")
            val next = document.entries.getOrNull(index + 1)
            if (next == null || entry.endTime < next.startTime) {
                appendLine(TimeUtils.formatLRC(entry.endTime))
            }
        }
        if (document.footer.isNotBlank()) appendLine(document.footer.trimEnd())
    }

    private const val DEFAULT_DURATION_MS = 6_000L
}
