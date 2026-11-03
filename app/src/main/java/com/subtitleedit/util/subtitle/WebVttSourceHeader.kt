package com.subtitleedit.util.subtitle

/** Reads only the metadata prefix; source-row edits never require another cue parse tree. */
internal object WebVttSourceHeader {
    private data class Line(val start: Int, val next: Int, val index: Int, val text: String)

    fun forSourceEdit(content: String, editedLine: Int, hasEntries: Boolean): String? {
        val cue = firstCueStart(content)
        if (cue != null && editedLine >= cue.index) return null
        // While the first cue is temporarily malformed, keep its last valid header.
        // A document with no cues can still contain editable STYLE/NOTE metadata.
        if (cue == null && hasEntries) return null
        val prefix = content.substring(0, cue?.start ?: content.length)
        return WebVttSubtitleFormatHandler.parse(prefix.toSubtitleLines()).document.header
    }

    private fun firstCueStart(content: String): Line? {
        var cursor = 0
        var index = 0
        var insideMetadata = false
        while (cursor < content.length) {
            val line = readLine(content, cursor, index)
            val sourceLine = if (index == 0) line.text.removePrefix("\uFEFF") else line.text
            val text = sourceLine.trim()
            when {
                index == 0 && sourceLine.startsWith("WEBVTT", ignoreCase = true) -> Unit
                text.isEmpty() -> insideMetadata = false
                insideMetadata -> Unit
                isMetadataStart(text) -> insideMetadata = true
                WebVttSubtitleFormatHandler.isTimestampMap(text) || text == "WEBVTT" -> Unit
                WebVttSubtitleFormatHandler.parseTimeLine(line.text) != null -> return line
                line.next < content.length && WebVttSubtitleFormatHandler.parseTimeLine(
                    readLine(content, line.next, index + 1).text
                ) != null -> return line
            }
            cursor = line.next
            index++
        }
        return null
    }

    private fun isMetadataStart(text: String): Boolean =
        text == "NOTE" || text.startsWith("NOTE ") || text == "STYLE" || text.startsWith("STYLE ") ||
            text == "REGION" || text.startsWith("REGION ")

    private fun readLine(content: String, start: Int, index: Int): Line {
        var end = start
        while (end < content.length && content[end] != '\r' && content[end] != '\n') end++
        val next = end + when {
            end == content.length -> 0
            content[end] == '\r' && content.getOrNull(end + 1) == '\n' -> 2
            else -> 1
        }
        return Line(start, next, index, content.substring(start, end))
    }
}
