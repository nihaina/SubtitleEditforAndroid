package com.subtitleedit.util

import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
/** Applies the same non-ASS font tag rules as Subtitle Edit's FontNameService. */
object SubtitleFontOps {
    fun setFontName(texts: List<String>, fontName: String): List<String> =
        texts.map { setFontName(it, fontName) }

    fun setFontName(input: String, fontName: String): String {
        return setFontName(input, fontName, isAss = false)
    }

    fun setFontName(input: String, fontName: String, format: SubtitleFormat): String =
        setFontName(
            input,
            fontName,
            isAss = format == SubtitleFormat.ASS || format == SubtitleFormat.SSA
        )

    private fun setFontName(input: String, fontName: String, isAss: Boolean): String {
        val name = fontName.trim()
        if (input.isBlank() || name.isEmpty()) return input

        if (isAss) return setAssFontName(input, name)

        var text = input
        var prefix = ""
        if (text.startsWith("{\\") && text.indexOf('}') >= 0) {
            val end = text.indexOf('}') + 1
            prefix = text.substring(0, end)
            text = text.substring(end)
        }

        if (text.startsWith("<font ", ignoreCase = true)) {
            val end = text.indexOf('>')
            if (end > 0) {
                val opening = text.substring(0, end)
                val face = Regex("\\s+face\\s*=\\s*(['\"])(.*?)\\1", RegexOption.IGNORE_CASE)
                val faceMatch = face.find(opening)
                if (faceMatch != null) {
                    val rewritten = opening.replaceRange(
                        faceMatch.range,
                        " face=\"${escapeAttribute(name)}\""
                    )
                    return prefix + rewritten + text.substring(end)
                }
                if (Regex("\\s+color\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(opening)) {
                    // Subtitle Edit inserts the new face attribute immediately before
                    // the existing color attribute, preserving the rest of the tag.
                    val colorStart = Regex("\\s+color\\s*=", RegexOption.IGNORE_CASE)
                        .find(opening)?.range?.first ?: opening.length
                    return prefix + text.substring(0, colorStart) +
                        " face=\"${escapeAttribute(name)}\"" + text.substring(colorStart)
                }
            }
        }
        return prefix + "<font face=\"${escapeAttribute(name)}\">$text</font>"
    }

    private fun setAssFontName(input: String, name: String): String {
        val withoutExisting = Regex("\\{([^}]*)}").replace(input) { match ->
            val body = match.groupValues[1]
            val cleaned = body.replace(Regex("\\\\fn[^\\\\}]*", RegexOption.IGNORE_CASE), "")
            if (cleaned.isBlank()) "" else "{$cleaned}"
        }
        return "{\\fn${name.replace("}", "").replace("\\", "")}}$withoutExisting"
    }

    /** Applies font tags to a parsed document without changing headers or timing metadata. */
    fun apply(document: SubtitleDocument, positions: Set<Int>, fontName: String): SubtitleDocument {
        if (positions.isEmpty() || fontName.isBlank()) return document
        return document.copy(entries = document.entries.mapIndexed { index, entry ->
            if (index in positions) entry.copy(text = setFontName(entry.text, fontName, document.format)) else entry
        })
    }

    private fun escapeAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
