package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TimeUtils
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/** Subtitle Edit compatible LRC reader/writer. */
object LrcSubtitleFormatHandler : SubtitleFormatHandler {
    override val format = SubtitleParser.SubtitleFormat.LRC
    override val extensions = setOf("lrc")

    // The reader remains tolerant of common LRC variants. Detection is deliberately stricter
    // and only accepts time tags at the beginning of a line, like Subtitle Edit.
    private val timeTagPattern = Regex("""\[(-?\d{1,4}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val detectableTimeTagPattern = Regex("""^\[\d+:\d{2}\.\d{2,3}\].*$""")
    private val metadataPattern = Regex("""^\[([A-Za-z][A-Za-z0-9_-]*):(.*)]\s*$""")
    private val offsetPattern = Regex(
        """^\[offset:\s*([+-]?\d+(?:\.\d+)?)\]\s*$""",
        RegexOption.IGNORE_CASE
    )

    private data class TimedText(val timeMs: Long, val text: String)
    private data class ParsedLine(val tags: List<ParsedTag>, val text: String, val textStart: Int)
    private data class ParsedTag(val timeMs: Long, val fractionDigits: Int)

    override fun isMine(lines: List<String>, fileName: String?): Boolean {
        if (lines.any {
                it.trimStart('\uFEFF').trim()
                    .equals(NO_END_TIME_HEADER, ignoreCase = true)
            }
        ) return true
        return lines.any {
            val line = it.trimStart('\uFEFF')
            detectableTimeTagPattern.matches(line) &&
                line.substringAfter(']', missingDelimiterValue = "").trim().isNotEmpty()
        }
    }

    override fun load(lines: List<String>, fileName: String?): SubtitleDocument {
        val metadata = mutableListOf<String>()
        val timedTexts = mutableListOf<TimedText>()
        var sawThreeDigitFraction = false
        var hasCue = false
        var offsetMs = 0L

        lines.forEach { rawLine ->
            val line = rawLine.trimStart('\uFEFF')
            val offsetMatch = offsetPattern.matchEntire(line.trim())
            if (offsetMatch != null) {
                // Subtitle Edit consumes offset and applies it after end times are inferred.
                offsetMs = offsetMatch.groupValues[1].toDoubleOrNull()?.roundToLong() ?: offsetMs
                return@forEach
            }

            val parsed = parseLeadingTags(line)
            if (parsed == null || parsed.tags.isEmpty()) {
                // LRC headers are only read before the first cue. This mirrors Subtitle Edit's
                // handling and prevents metadata-looking subtitle text from becoming a header.
                if (!hasCue && metadataPattern.matches(line.trim()) && line.isNotBlank()) {
                    metadata += line.trim()
                }
                return@forEach
            }

            hasCue = true
            parsed.tags.forEach { tag ->
                if (tag.fractionDigits == 3) sawThreeDigitFraction = true
                timedTexts += TimedText(tag.timeMs, parsed.text)
            }
        }

        val sortedTimedTexts = timedTexts.sortedBy { it.timeMs }
        val entries = mutableListOf<SubtitleEntry>()
        val noEndTime = isNoEndTimeHeader(metadata)
        sortedTimedTexts.forEachIndexed { index, timedText ->
            if (timedText.text.isEmpty()) return@forEachIndexed

            val next = sortedTimedTexts.getOrNull(index + 1)
            val explicitEndTime = next?.takeIf { it.text.isEmpty() }?.timeMs
            var endTime = when {
                explicitEndTime != null -> explicitEndTime
                next != null -> next.timeMs - MINIMUM_GAP_MS
                else -> timedText.timeMs + optimalFinalDurationMs(timedText.text, noEndTime)
            }
            if (next != null && endTime - timedText.timeMs > MAXIMUM_DISPLAY_DURATION_MS) {
                endTime = timedText.timeMs + if (noEndTime) {
                    optimalFinalDurationMs(timedText.text, noEndTime = true)
                } else {
                    MAXIMUM_DISPLAY_DURATION_MS
                }
            }
            if (endTime <= timedText.timeMs) endTime = timedText.timeMs + 1

            entries += SubtitleEntry(
                index = entries.size + 1,
                startTime = timedText.timeMs,
                endTime = endTime,
                text = timedText.text,
                endTimeModified = explicitEndTime != null
            )
        }

        // Apply offset after end-time inference, as Subtitle Edit does. In particular, a
        // negative offset shifts both boundaries together instead of changing the inferred gap.
        if (offsetMs != 0L) {
            entries.forEach { entry ->
                entry.startTime = (entry.startTime + offsetMs).coerceAtLeast(0L)
                entry.endTime = (entry.endTime + offsetMs).coerceAtLeast(0L)
            }
        }

        val variant = when {
            noEndTime -> LrcVariant.NO_END_TIME
            sawThreeDigitFraction -> LrcVariant.MILLISECONDS
            else -> LrcVariant.CENTISECONDS
        }
        return SubtitleDocument(
            format = format,
            entries = entries,
            header = metadata.joinToString("\n"),
            lrcVariant = variant
        )
    }

    override fun write(document: SubtitleDocument): String = buildString {
        val headerLines = document.header.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !offsetPattern.matches(it.trim()) }
            .toList()
        // Subtitle Edit identifies this variant through its generated header. Keep the marker
        // even when callers provide additional metadata so a subsequent open/save is stable.
        val normalizedHeaderLines = if (
            document.lrcVariant == LrcVariant.NO_END_TIME &&
            headerLines.none { it.equals(NO_END_TIME_HEADER, ignoreCase = true) }
        ) {
            listOf(NO_END_TIME_HEADER) + headerLines
        } else {
            headerLines
        }
        if (normalizedHeaderLines.isNotEmpty()) {
            appendLine(normalizedHeaderLines.joinToString("\n"))
        }

        val variant = document.lrcVariant
        document.entries.forEachIndexed { index, entry ->
            appendLine(formatLrcTag(entry.startTime, variant) + normalizeLrcText(entry.text))
            if (variant == LrcVariant.NO_END_TIME) return@forEachIndexed

            val next = document.entries.getOrNull(index + 1)
            // Subtitle Edit does not emit an explicit end marker when the next cue is within
            // 100 ms of this cue's end. The final cue always gets a marker in these variants.
            if (next == null || next.startTime - entry.endTime > EXPLICIT_END_THRESHOLD_MS) {
                appendLine(formatLrcTag(entry.endTime, variant))
            }
        }
        if (document.footer.isNotBlank()) appendLine(document.footer.trimEnd())
    }

    private fun parseLeadingTags(line: String): ParsedLine? {
        if (!line.startsWith('[')) return null
        val tags = mutableListOf<ParsedTag>()
        var cursor = 0
        while (true) {
            val match = timeTagPattern.find(line, cursor) ?: break
            if (match.range.first != cursor) break
            val minutes = match.groupValues[1].toLongOrNull() ?: break
            val seconds = match.groupValues[2].toLongOrNull() ?: break
            val fractionText = match.groupValues[3]
            val fraction = if (fractionText.isEmpty()) 0L
            else fractionText.take(3).padEnd(3, '0').toLong()
            tags += ParsedTag(
                timeMs = minutes * 60_000 + seconds * 1_000 + fraction,
                fractionDigits = fractionText.length
            )
            cursor = match.range.last + 1
            while (cursor < line.length && line[cursor].isWhitespace()) cursor++
            if (cursor >= line.length || line[cursor] != '[') break
        }
        if (tags.isEmpty()) return null
        return ParsedLine(tags, line.substring(cursor).trim(), cursor)
    }

    private fun isNoEndTimeHeader(metadata: List<String>): Boolean = metadata.any {
        it.equals("[re: Subtitle Edit - LRC No End Time]", ignoreCase = true)
    }

    /** The same lyric text is written by list serialization and raw source synchronization. */
    internal fun normalizeLrcText(text: String): String =
        stripHtmlTags(text)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\n', ' ')

    /** Clean timed lyric text without rebuilding timestamps, metadata, or physical line endings. */
    internal fun normalizeSourceForWrite(content: String): String {
        if ('<' !in content) return content
        return buildString(content.length) {
            val newlineChars = charArrayOf('\r', '\n')
            var cursor = 0
            while (cursor < content.length) {
                val newline = content.indexOfAny(newlineChars, cursor)
                val end = if (newline < 0) content.length else newline
                val line = content.substring(cursor, end)
                val bomLength = line.takeWhile { it == '\uFEFF' }.length
                val parsed = if ('<' in line) parseLeadingTags(line.substring(bomLength)) else null
                if (parsed == null) {
                    append(line)
                } else {
                    val textStart = bomLength + parsed.textStart
                    append(line, 0, textStart)
                    append(normalizeLrcText(line.substring(textStart)))
                }
                if (newline < 0) break
                val endingLength = if (content[newline] == '\r' && content.getOrNull(newline + 1) == '\n') 2 else 1
                append(content, newline, newline + endingLength)
                cursor = newline + endingLength
            }
        }
    }

    private fun stripHtmlTags(text: String): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            if (text[index] == '<') {
                val end = text.indexOf('>', index + 1)
                if (end >= 0) {
                    index = end + 1
                    continue
                }
            }
            append(text[index])
            index++
        }
    }

    private fun formatLrcTag(timeMs: Long, variant: LrcVariant): String {
        val safe = timeMs.coerceAtLeast(0L)
        if (variant != LrcVariant.MILLISECONDS) return TimeUtils.formatLRC(safe)
        return String.format(
            Locale.US,
            "[%02d:%02d.%03d]",
            safe / 60_000,
            safe % 60_000 / 1_000,
            safe % 1_000
        )
    }

    private fun optimalFinalDurationMs(text: String, noEndTime: Boolean): Long {
        val durationText = if (noEndTime) "$text!" else text
        var duration = visibleCharacterCount(durationText).toDouble() / OPTIMAL_CHARACTERS_PER_SECOND * 1_000
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
        return optimalDuration.roundToLong() + if (noEndTime) 0L else FINAL_CUE_PADDING_MS
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
            if (tagEnd >= 0) index = tagEnd + 1
            else {
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
    private const val EXPLICIT_END_THRESHOLD_MS = 100L
    private const val NO_END_TIME_HEADER = "[re: Subtitle Edit - LRC No End Time]"
    private const val MINIMUM_DISPLAY_DURATION_MS = 1_000L
    private const val MAXIMUM_DISPLAY_DURATION_MS = 8_000L
    private const val FINAL_CUE_PADDING_MS = 1_500L
    private const val OPTIMAL_CHARACTERS_PER_SECOND = 16.0
}
