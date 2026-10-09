package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import kotlin.math.abs

internal data class WebVttParsedCue(
    val entry: SubtitleEntry,
    val locations: MutableList<WebVttCueLocation>,
    var textTransformed: Boolean = false
)

internal object WebVttCueProcessing {
    private val wordTimestamp = Regex("""<\d+:\d+:\d+\.\d+>""")
    private val classTag = Regex("""</?c[^>]*>""")
    private val positionTag = Regex("""\{\\an([1-9])\}""")
    private val leadingPositionTag = Regex("""^\{\\an[1-9]\}""")
    private val styleWrappers = Regex(
        """^(?:\s*<(?:i|b|u|c(?:\.[^>]*)?|font(?:\s[^>]*)?)>)*\s*$""", RegexOption.IGNORE_CASE
    )
    private val unsignedDecimal = Regex("""\d*(?:\.\d*)?""")
    private val defaultSettings = listOf(
        "", "position:20%", "", "position:80%", "position:20% line:50%", "line:50%",
        "position:80% line:50%", "position:20% line:20%", "line:20%", "position:80% line:20%"
    )

    fun process(cues: MutableList<WebVttParsedCue>): List<WebVttParsedCue> {
        val timestampCount = cues.count { wordTimestamp.containsMatchIn(it.entry.text) }
        val bridges = cues.count {
            !wordTimestamp.containsMatchIn(it.entry.text) && it.entry.endTime - it.entry.startTime <= 100
        }
        if (cues.size >= 4 && timestampCount >= 3 && bridges >= 2 && timestampCount * 4 >= cues.size) {
            var previousLines = emptyList<String>()
            val iterator = cues.iterator()
            while (iterator.hasNext()) {
                val cue = iterator.next()
                val plain = classTag.replace(wordTimestamp.replace(cue.entry.text, ""), "")
                val lines = plain.split('\n').map { leadingPositionTag.replace(it, "").trim() }.filter { it.isNotEmpty() }
                val newLines = lines.filter { it !in previousLines }
                previousLines = lines
                if (newLines.isEmpty()) iterator.remove()
                else {
                    cue.entry.text = newLines.joinToString("\n")
                    // Clear the layout style while keeping the region, as the upstream cleaner does.
                    cue.entry.cueSettings = cue.entry.cueSettings.split(Regex("\\s+"))
                        .filter { it.startsWith("region:") }.joinToString(" ")
                    cue.textTransformed = true
                }
            }
        }

        for (index in cues.size - 2 downTo 0) {
            val current = cues[index]
            val next = cues[index + 1]
            val first = current.entry
            val second = next.entry
            if (first.startTime == second.startTime && first.endTime == second.endTime &&
                tag(first.cueSettings, "region") == tag(second.cueSettings, "region") &&
                sameVerticalPlacement(first.cueSettings, second.cueSettings)
            ) {
                if (first.text != second.text) first.text += "\n" + second.text
                current.locations += next.locations
                current.textTransformed = true
                cues.removeAt(index + 1)
            }
        }
        return cues
    }

    fun positionInfo(settings: String): String {
        val horizontal = tag(settings, "position").takeIf { it.endsWith('%') }
            ?.dropLast(1)?.let(::unsignedNumber)
        val line = tag(settings, "line")
        val vertical = if (line.endsWith('%')) {
            unsignedNumber(line.dropLast(1))?.let { if (it < 25) 2 else if (it < 75) 1 else 0 }
        } else {
            unsignedNumber(line)?.let { if (it in 0.0..7.0) 2 else if (it > 7 && it < 11) 1 else 0 }
        } ?: 0
        val column = if (horizontal != null && horizontal < 25) 1 else if (horizontal != null && horizontal > 75) 3 else 2
        val alignment = vertical * 3 + column
        return if (alignment == 2) "" else "{\\an$alignment}"
    }

    fun settingsFor(entry: SubtitleEntry): String {
        val current = alignmentTag(entry.text)?.value.orEmpty()
        val raw = entry.cueSettings.trim()
        if (current == positionInfo(raw)) return raw
        val alignment = alignmentTag(entry.text)?.groupValues?.get(1)?.toInt() ?: 2
        val remaining = raw.split(Regex("\\s+")).filter {
            it.isNotEmpty() && !it.startsWith("position:") && !it.startsWith("line:")
        }.joinToString(" ")
        return listOf(remaining, defaultSettings[alignment]).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun updateSettings(entry: SubtitleEntry, settings: String) {
        val alignment = alignmentTag(entry.text)
        val body = if (alignment == null) entry.text else entry.text.removeRange(alignment.range)
        entry.text = positionInfo(settings) + body
        entry.cueSettings = settings
    }

    private fun alignmentTag(text: String): MatchResult? = positionTag.find(text)?.takeIf { match ->
        // Position tags emitted by this parser occur at the cue-text prefix. Allow
        // existing editor style wrappers (<i>, <b>, <c...>) before the tag.
        styleWrappers.matches(text.substring(0, match.range.first))
    }

    private fun sameVerticalPlacement(first: String, second: String): Boolean {
        val a = verticalPosition(first) ?: return true
        val b = verticalPosition(second) ?: return true
        return abs(a - b) <= 15
    }

    private fun verticalPosition(settings: String): Double? {
        val line = tag(settings, "line").substringBefore(',')
        if (line.endsWith('%')) return unsignedNumber(line.dropLast(1))
        val number = line.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return (if (number < 0) 16 + number else number) * 100 / 16
    }

    private fun unsignedNumber(value: String): Double? = value.takeIf { unsignedDecimal.matches(it) }?.toDoubleOrNull()

    private fun tag(settings: String, name: String): String {
        val start = settings.indexOf("$name:")
        if (start < 0) return ""
        val value = settings.substring(start + name.length + 1).trim().substringBefore(' ')
        return if ("%," in value) value.substringBefore("%,") + "%" else value
    }

    fun removeRepeatingHeader(input: String): String {
        val text = input.replace(Regex(" {2,}"), " ")
        val signature = text.indexOf("\nWEBVTT")
        if (signature >= 0 && text.trimEnd().endsWith('}') && "STYLE" in text) return text.take(signature).trim()
        if (text.trimEnd().endsWith("\nWEBVTT")) return text.substringBeforeLast("\nWEBVTT").trim()
        val style = text.indexOf("\nSTYLE\n")
        return if (style >= 0 && text.trimEnd().endsWith('}')) text.take(style).trim() else text
    }
}
