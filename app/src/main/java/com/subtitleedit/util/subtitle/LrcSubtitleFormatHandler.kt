package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TimeUtils
import kotlin.math.max
import kotlin.math.roundToLong

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

        val sortedTimedTexts = timedTexts.sortedBy { it.timeMs }
        val entries = mutableListOf<SubtitleEntry>()
        sortedTimedTexts.forEachIndexed { index, timedText ->
            if (timedText.text.isEmpty()) return@forEachIndexed

            val next = sortedTimedTexts.getOrNull(index + 1)
            val explicitEndTime = next?.takeIf { it.text.isEmpty() }?.timeMs
            var endTime = when {
                explicitEndTime != null -> explicitEndTime
                next != null -> next.timeMs - MINIMUM_GAP_MS
                else -> timedText.timeMs + optimalFinalDurationMs(timedText.text)
            }
            if (next != null && endTime - timedText.timeMs > MAXIMUM_DISPLAY_DURATION_MS) {
                endTime = timedText.timeMs + MAXIMUM_DISPLAY_DURATION_MS
            }
            if (endTime <= timedText.timeMs) endTime = timedText.timeMs + 1

            entries += SubtitleEntry(
                index = entries.size + 1,
                startTime = timedText.timeMs,
                endTime = endTime,
                text = timedText.text,
                endTimeModified = explicitEndTime == endTime
            )
        }

        return SubtitleDocument(format, entries, header = metadata.joinToString("\n"))
    }

    override fun write(document: SubtitleDocument): String = buildString {
        if (document.header.isNotBlank()) appendLine(document.header.trimEnd())
        document.entries.forEachIndexed { index, entry ->
            appendLine("${TimeUtils.formatLRC(entry.startTime)}${entry.text}")
            val next = document.entries.getOrNull(index + 1)
            if (next == null || entry.endTime != next.startTime) {
                appendLine(TimeUtils.formatLRC(entry.endTime))
            }
        }
        if (document.footer.isNotBlank()) appendLine(document.footer.trimEnd())
    }

    private fun optimalFinalDurationMs(text: String): Long {
        var duration = visibleCharacterCount(text).toDouble() / OPTIMAL_CHARACTERS_PER_SECOND * 1_000
        duration = when {
            duration < 1_400 -> duration * 1.2
            duration < 1_680 -> 1_680.0
            duration > 2_900 -> max(2_900.0, duration * 0.96)
            else -> duration
        }
        val optimalDuration = duration.coerceIn(
            MINIMUM_DISPLAY_DURATION_MS.toDouble(),
            MAXIMUM_DISPLAY_DURATION_MS.toDouble()
        )
        return optimalDuration.roundToLong() + FINAL_CUE_PADDING_MS
    }

    private fun visibleCharacterCount(text: String): Int {
        val plainText = stripFormattingTags(text)
        var count = 0
        var offset = 0
        while (offset < plainText.length) {
            val codePoint = plainText.codePointAt(offset)
            if (!Character.isISOControl(codePoint) && codePoint !in ZERO_WIDTH_CODE_POINTS) count++
            offset += Character.charCount(codePoint)
        }
        return count
    }

    private fun stripFormattingTags(text: String): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val tagEnd = when {
                text[index] == '<' -> text.indexOf('>', index + 1)
                text[index] == '{' && text.getOrNull(index + 1) == '\\' -> {
                    text.indexOf('}', index + 2)
                }
                else -> -1
            }
            if (tagEnd >= 0) {
                index = tagEnd + 1
            } else {
                append(text[index])
                index++
            }
        }
    }

    private val ZERO_WIDTH_CODE_POINTS = setOf(
        0x200B, 0xFEFF, 0x200E, 0x200F,
        0x202A, 0x202B, 0x202C, 0x202D, 0x202E
    )

    private const val MINIMUM_GAP_MS = 24L
    private const val MINIMUM_DISPLAY_DURATION_MS = 1_000L
    private const val MAXIMUM_DISPLAY_DURATION_MS = 8_000L
    private const val FINAL_CUE_PADDING_MS = 1_500L
    private const val OPTIMAL_CHARACTERS_PER_SECOND = 16.0
}
