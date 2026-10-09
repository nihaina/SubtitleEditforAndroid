package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TimeUtils
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal data class SrtTimeLine(
    val startTime: Long,
    val endTime: Long,
    val startToken: String,
    val endToken: String,
    val suffix: String,
    val isFrameCandidate: Boolean,
    val startRange: IntRange? = null,
    val endRange: IntRange? = null
)

internal data class SrtCueSourceLocation(
    val numberLineIndex: Int?,
    val timeLineIndex: Int,
    val endExclusive: Int
)

internal data class SrtParseResult(
    val document: SubtitleDocument,
    val errorCount: Int,
    val usesFrameTiming: Boolean,
    val cues: List<SrtCueSourceLocation>
)

object SrtSubtitleFormatHandler : SubtitleFormatHandler {
    override val format = SubtitleParser.SubtitleFormat.SRT
    override val extensions = setOf("srt", "wsrt")

    const val DEFAULT_FRAME_RATE: Double = 23.976

    private enum class Expecting { NUMBER, TIME_CODES, TEXT }

    private data class ParsedTimestamp(
        val value: Long,
        val frameCandidate: Boolean
    )

    private data class CueBuilder(
        var number: Int = 0,
        var numberLineIndex: Int? = null,
        var timeLineIndex: Int = -1,
        var endExclusive: Int = -1,
        var startTime: Long = 0,
        var endTime: Long = 0,
        var text: String = ""
    )

    private val timestampPattern = Regex(
        """^(-?\d+):(-?\d+):(-?\d+)[:,](\d+)$"""
    )
    private val normalizedTimeLinePattern = Regex("""^(-?\d+:-?\d+:-?\d+[:,]\d+)\s*-->\s*(-?\d+:-?\d+:\d+[:,]\d+)$""")
    private val rawTimestamp = """-?\d+\s*[:.]\s*-?\d+(?:\s*[:.]\s*-?\d+)?\s*[,.;:\u060C\uF7C8\u00A1]\s*\d+"""
    private val rawTimeLinePattern = Regex("""^(\s*)($rawTimestamp)(\s*[-\u2014\s]*>+\s*)($rawTimestamp)(.*)$""")

    override fun isMine(lines: List<String>, fileName: String?): Boolean {
        if (lines.firstOrNull()?.trimStart { it.isWhitespace() || it == '\uFEFF' }
                ?.startsWith("WEBVTT", ignoreCase = true) == true
        ) return false
        val parsed = parse(lines, fileName)
        return parsed.document.entries.size > parsed.errorCount
    }

    override fun load(lines: List<String>, fileName: String?): SubtitleDocument = parse(lines, fileName).document

    fun loadWithFrameRate(
        lines: List<String>,
        fileName: String? = null,
        frameRate: Double = DEFAULT_FRAME_RATE
    ): SubtitleDocument = parse(lines, fileName, frameRate).document

    internal fun parse(
        lines: List<String>,
        fileName: String? = null,
        frameRate: Double = DEFAULT_FRAME_RATE
    ): SrtParseResult {
        val builders = mutableListOf<CueBuilder>()
        val locations = mutableListOf<SrtCueSourceLocation>()
        var current = CueBuilder()
        var last: CueBuilder? = null
        var state = Expecting.NUMBER
        var errors = 0
        var renumber = false
        var frameMode = true
        var sawTimeline = false
        fun register(line: SrtTimeLine, at: Int, cue: CueBuilder) {
            cue.timeLineIndex = at
            cue.startTime = line.startTime
            cue.endTime = line.endTime
            sawTimeline = true
        }

        fun finish(at: Int) {
            val cue = current
            if (cue.timeLineIndex < 0) return
            cue.endExclusive = maxOf(at, cue.timeLineIndex + 1)
            builders += cue
            locations += SrtCueSourceLocation(cue.numberLineIndex, cue.timeLineIndex, cue.endExclusive)
            last = cue
            current = CueBuilder()
        }

        fun probe(input: String): SrtTimeLine? {
            val timeline = parseTimeLine(input)
            if (timeline != null) frameMode = frameMode && timeline.isFrameCandidate
            return timeline
        }

        fun isText(input: String): Boolean =
            !(input.isBlank() || input.trim().toIntOrNull() != null || probe(input.trim()) != null)

        val prepared = lines.mapIndexed { index, value ->
            val clean = value.trimEnd().trim('\u007F')
            if (index == 0) clean.removePrefix("\uFEFF") else clean
        }
        for (lineIndex in prepared.indices) {
            val line = prepared[lineIndex]
            val trimmed = line.trim()
            val next = prepared.getOrNull(lineIndex + 1).orEmpty()
            val nextNext = prepared.getOrNull(lineIndex + 2).orEmpty()
            val nextNextNext = prepared.getOrNull(lineIndex + 3).orEmpty()

            if (state == Expecting.NUMBER && probe(trimmed) != null) {
                state = Expecting.TIME_CODES
                renumber = true
            } else if (current.text.isNotEmpty() && state == Expecting.TEXT && probe(trimmed) != null) {
                finish(lineIndex)
                state = Expecting.TIME_CODES
                renumber = true
            }

            when (state) {
                Expecting.NUMBER -> {
                    val number = trimmed.toIntOrNull()
                    if (number != null) {
                        current.number = number
                        current.numberLineIndex = lineIndex
                        state = Expecting.TIME_CODES
                    } else if (line.isNotBlank()) {
                        val previous = last
                        if (previous != null && (previous.number + 1).toString() == nextNext) {
                            previous.text = (previous.text + "\n" + line.trim()).trim()
                        } else errors++
                    }
                }
                Expecting.TIME_CODES -> {
                    val timeline = probe(line)
                    if (timeline != null) {
                        register(timeline, lineIndex, current)
                        current.text = ""
                        state = Expecting.TEXT
                    } else if (line.isNotBlank()) {
                        errors++
                        state = Expecting.NUMBER
                    }
                }
                Expecting.TEXT -> {
                    if (trimmed.toIntOrNull() != null &&
                        (probe(next) != null || next.isEmpty() && probe(nextNext) != null)
                    ) {
                        finish(lineIndex)
                        current.number = trimmed.toInt()
                        current.numberLineIndex = lineIndex
                        state = Expecting.NUMBER
                    } else if (probe(line) != null) {
                        if (current.endTime > 0 || current.text.isNotEmpty()) finish(lineIndex)
                        else current = CueBuilder()
                        register(probe(line)!!, lineIndex, current)
                    } else if (line.isNotBlank() || isText(next) || isText(nextNext) ||
                        nextNextNext == (current.number + 1).toString()
                    ) {
                        val text = line.replace('\u0000', ' ').trimEnd()
                        current.text = if (current.text.isEmpty()) text else current.text + "\n" + text
                        if (line.isBlank() && next.trim().toIntOrNull() != null && probe(nextNext) != null) {
                            finish(lineIndex + 1)
                            state = Expecting.NUMBER
                        }
                    } else if (line.isEmpty() && current.text.isEmpty()) {
                        if (next.isNotEmpty() && (next.trim().toIntOrNull() != null || probe(next) != null)) {
                            finish(lineIndex + 1)
                            state = Expecting.NUMBER
                        }
                    } else if (line.isEmpty() && next.isEmpty()) {
                        current.text += "\n" + line.replace('\u0000', ' ').trimEnd()
                    } else {
                        finish(lineIndex + 1)
                        state = Expecting.NUMBER
                    }
                }
            }
        }
        finish(lines.size)

        val usesFrames = sawTimeline && frameMode
        val effectiveRate = frameRate.takeIf { it.isFinite() && it > 0 } ?: DEFAULT_FRAME_RATE
        val entries = builders.map { cue ->
            val start = if (usesFrames) convertFrameTime(cue.startTime, effectiveRate) else cue.startTime
            val end = if (usesFrames) convertFrameTime(cue.endTime, effectiveRate) else cue.endTime
            val text = cue.text.trimEnd()
            SubtitleEntry(
                index = cue.number,
                startTime = start,
                endTime = end,
                text = if (fileName?.endsWith(".wsrt", ignoreCase = true) == true) {
                    text.replace(Regex("<3\\d>"), "<i>").replace(Regex("</3\\d>"), "</i>")
                } else text
            )
        }.let { parsed -> if (renumber) parsed.mapIndexed { i, e -> e.copy(index = i + 1) } else parsed }

        return SrtParseResult(SubtitleDocument(format, entries), errors, usesFrames, locations)
    }

    override fun write(document: SubtitleDocument): String {
        val body = buildString {
            document.entries.forEach { entry ->
                appendLine(entry.index)
                append(TimeUtils.formatSRT(entry.startTime))
                append(" --> ")
                appendLine(TimeUtils.formatSRT(entry.endTime))
                appendLine(entry.text)
                appendLine()
            }
        }
        return body.trim() + "\n\n"
    }

    internal fun parseTimeLine(input: String): SrtTimeLine? {
        if (input.length < 10 || '>' !in input) return null
        val beginning = input.removePrefix("\uFEFF").trimStart('-', ' ')
        if (beginning.length < 10 || !beginning.first().isDigit()) return null

        val rawLayout = rawTimeLinePattern.matchEntire(input.replace('\u200B', ' ').replace('\uFEFF', ' '))
        val rawSuffix = rawLayout?.groupValues?.get(5).orEmpty()
        val separator = " --> "
        var line = input.replace('\u060C', ',').replace('\uF7C8', ',').replace('\u00A1', ',')
            .replace('\u200B', ' ').replace('\uFEFF', ' ')
            .replace(" -> ", separator)
            .replace(" \u2014> ", separator)
            .replace(" \u2014\u2014> ", separator)
            .replace(" - > ", separator)
            .replace(" ->> ", separator)
            .replace(" -- > ", separator)
            .replace(" - -> ", separator)
            .replace(" -->> ", separator)
            .replace(" ---> ", separator)
            .replace("  ", " ").replace(": ", ":").replace(" :", ":").trim()

        // Position data is outside the time-code syntax.  Keep it in the raw
        // layout for source rewrites, while matching SubRip's loading rules.
        if (line.length > 30) {
            val cut = when {
                line[29] == ' ' -> 29
                line[28] == ' ' -> 28
                line[27] == ' ' -> 27
                else -> -1
            }
            if (cut >= 0) line = line.substring(0, cut)
        }
        line = line.replace(" ", "").replace("-->", separator).trim()
        if (!line.contains(separator)) line = line.replace(">", separator)
        line = line.replace('.', ':')
        if (line.length >= 29 && (line[8] == ':' || line[8] == ';')) {
            line = line.substring(0, 8) + ',' + line.substring(9)
        }
        if (line.length in 29..30 && (line[25] == ':' || line[25] == ';')) {
            line = line.substring(0, 25) + ',' + line.substring(26)
        }
        if (line.length == 23 && line[2] == ':' && line[5] == ',' && line[9] == ' ' &&
            line[12] == '>' && line[13] == ' ' && line[16] == ':' && line[19] == ','
        ) {
            line = "00:" + line.substring(0, 14) + "00:" + line.substring(14)
        }

        val matched = normalizedTimeLinePattern.matchEntire(line) ?: return null
        val startToken = matched.groupValues[1]
        val endToken = matched.groupValues[2]
        val start = parseTimestamp(startToken) ?: return null
        val end = parseTimestamp(endToken) ?: return null
        return SrtTimeLine(
            start.value,
            end.value,
            startToken,
            endToken,
            rawSuffix,
            start.frameCandidate && end.frameCandidate,
            rawLayout?.groups?.get(2)?.range,
            rawLayout?.groups?.get(4)?.range
        )
    }

    internal fun readTimeLine(input: String): Pair<Long, Long>? = parseTimeLine(input)?.let { it.startTime to it.endTime }

    internal fun rewriteTimeLine(input: String, startTime: Long, endTime: Long): String? {
        val parsed = parseTimeLine(input) ?: return null
        val start = parsed.startRange ?: return null
        val end = parsed.endRange ?: return null
        return input.substring(0, start.first) + TimeUtils.formatSRT(startTime) +
            input.substring(start.last + 1, end.first) + TimeUtils.formatSRT(endTime) +
            input.substring(end.last + 1)
    }

    private fun parseTimestamp(value: String): ParsedTimestamp? {
        val match = timestampPattern.matchEntire(value.trim()) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: return null
        val minutes = match.groupValues[2].toIntOrNull() ?: return null
        val seconds = match.groupValues[3].toIntOrNull() ?: return null
        val fractionText = match.groupValues[4]
        val fractionValue = fractionText.toIntOrNull() ?: return null
        // SubRip's TimeCode constructor receives the signed hour component.  A
        // negative zero-hour token is then negated after construction, which is
        // why -01:00:01 is -3,599,000 ms while -00:00:01 is -1,000 ms.
        val total = hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + fractionValue
        val valueMs = if (match.groupValues[1].startsWith('-') && total > 0) -total else total
        return ParsedTimestamp(
            valueMs,
            fractionText.length == 2 && fractionValue <= 30
        )
    }

    private fun convertFrameTime(raw: Long, frameRate: Double): Long {
        val frame = raw % 1000
        val effectiveRate = when {
            abs(frameRate - 23.976) < 0.001 -> 24000.0 / 1001.0
            abs(frameRate - 29.97) < 0.001 -> 30000.0 / 1001.0
            abs(frameRate - 59.94) < 0.001 -> 60000.0 / 1001.0
            else -> frameRate
        }
        val value = frame * (1000.0 / effectiveRate)
        val frameMs = (if (value < 0) ceil(value - 0.5) else floor(value + 0.5)).toLong().coerceAtMost(999L)
        return raw - frame + frameMs
    }
}
