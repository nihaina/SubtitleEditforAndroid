package com.subtitleedit.util

import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.util.subtitle.WebVttSubtitleFormatHandler
import com.subtitleedit.util.subtitle.toSubtitleLines
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Applies Subtitle Edit-style foreground colors without owning editor/history state. */
object SubtitleColorOps {
    private val cueClassPattern = Regex("<c\\.([A-Za-z0-9_.#-]+)>")
    // Android's ICU regex requires the closing brace to be escaped as well.
    private val cssRulePattern = Regex("([^{}]+)\\{([^{}]*)\\}")
    private val singleClassSelectorPattern = Regex("::cue\\(\\.([A-Za-z0-9_#-]+)\\)")
    private val cssClassPattern = Regex("\\.([A-Za-z0-9_#-]+)")
    private val cueTagPattern = Regex("<c(?:\\.([A-Za-z0-9_.#-]+))?>|</c>")

    /** Applies [argb] to the selected cues and preserves the rest of the document metadata. */
    fun apply(document: SubtitleDocument, positions: Set<Int>, argb: Int): SubtitleDocument {
        if (positions.isEmpty() || document.entries.isEmpty()) return document
        return if (document.format == SubtitleFormat.VTT) {
            applyWebVtt(document, positions, argb)
        } else {
            document.copy(
                entries = document.entries.mapIndexed { index, entry ->
                    if (index in positions) entry.copy(text = applyHtmlColor(entry.text, argb)) else entry
                }
            )
        }
    }

    /** Parses RGB (opaque) or AARRGGBB, with an optional leading #. */
    fun parseArgb(input: String): Int? {
        val value = input.trim().removePrefix("#")
        if (value.length != 6 && value.length != 8 || !value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return null
        }
        return value.toLongOrNull(16)?.let { parsed ->
            if (value.length == 6) (0xFF000000L or parsed).toInt() else parsed.toInt()
        }
    }

    fun toArgbHex(argb: Int): String = "%08X".format(Locale.US, argb.toLong() and 0xFFFFFFFFL)

    fun toRgbHex(argb: Int): String = "#%02X%02X%02X".format(
        Locale.US,
        argb ushr 16 and 0xFF,
        argb ushr 8 and 0xFF,
        argb and 0xFF
    )

    /** Adds newly appended STYLE blocks before the first raw cue without rewriting its header. */
    fun syncVttHeader(content: String, oldHeader: String, newHeader: String): String {
        if (oldHeader == newHeader || !newHeader.startsWith(oldHeader)) return content
        val addition = newHeader.removePrefix(oldHeader).removePrefix("WEBVTT").trim()
        if (!addition.startsWith("STYLE")) return content
        val lines = Regex("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").findAll(content).filter { it.value.isNotEmpty() }.toList()
        val cue = WebVttSubtitleFormatHandler.parse(content.toSubtitleLines()).rawCues.firstOrNull() ?: return content
        val cueStart = cue.identifierLineIndex ?: cue.timeLineIndex
        var insertAt = lines.getOrNull(cueStart)?.range?.first ?: return content
        if (insertAt == 0 && content.startsWith('\uFEFF')) insertAt = 1
        val newline = if ("\r\n" in content) "\r\n" else if ('\r' in content && '\n' !in content) "\r" else "\n"
        val prefix = content.substring(0, insertAt)
        val separator = when {
            prefix.isBlank() || prefix == "\uFEFF" || prefix.endsWith(newline + newline) -> ""
            prefix.endsWith(newline) -> newline
            else -> newline + newline
        }
        return prefix + separator + addition.replace("\n", newline) + newline + newline + content.substring(insertAt)
    }

    private fun applyHtmlColor(text: String, argb: Int): String {
        if (text.isBlank()) return text
        val withoutColor = removeHtmlColor(text)
        val color = toRgbHex(argb)
        val prefixEnd = leadingAssPrefixEnd(withoutColor)
        val prefix = withoutColor.substring(0, prefixEnd)
        val body = withoutColor.substring(prefixEnd)
        if (body.isEmpty() || body.isBlank()) return text

        val fontMatch = Regex("^<font(?=[\\s>])([^>]*)>", RegexOption.IGNORE_CASE).find(body)
        if (fontMatch != null) {
            val opening = fontMatch.value
            val attrs = fontMatch.groupValues[1]
            val rewritten = "<font$attrs color=\"$color\">"
            return prefix + rewritten + body.removePrefix(opening)
        }
        return prefix + "<font color=\"$color\">" + body + "</font>"
    }

    private fun removeHtmlColor(text: String): String {
        // The existing remover removes whole colored spans; shield spans to retain other CSS.
        var marker = "\u0000subtitle-color-span-"
        while (marker in text) marker += "-"
        val spans = mutableListOf<String>()
        val protected = Regex("</?span(?=[\\s>])[^>]*>", RegexOption.IGNORE_CASE).replace(text) { match ->
            val rewritten = Regex("\\s+style\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE).replace(match.value) { attribute ->
                val declarations = attribute.groupValues[2].split(';').filter {
                    it.substringBefore(':').trim().lowercase(Locale.US) != "color"
                }.joinToString(";")
                if (declarations.isBlank()) "" else " style=${attribute.groupValues[1]}$declarations${attribute.groupValues[1]}"
            }
            spans += rewritten
            "$marker${spans.lastIndex}\u0000"
        }
        var result = SubtitleFormattingTagOps.remove(protected, SubtitleFormattingTagOps.Kind.COLOR)
        spans.forEachIndexed { index, tag -> result = result.replace("$marker$index\u0000", tag) }
        return result
    }

    private fun leadingAssPrefixEnd(text: String): Int {
        var cursor = 0
        while (text.startsWith("{\\", cursor)) {
            val end = text.indexOf('}', cursor + 2)
            if (end < 0) break
            cursor = end + 1
        }
        return cursor
    }

    private fun applyWebVtt(
        document: SubtitleDocument,
        positions: Set<Int>,
        argb: Int
    ): SubtitleDocument {
        if (positions.none { document.entries.getOrNull(it)?.text?.isNotBlank() == true }) return document
        val styles = parseColorRules(document.header)
        val className = chooseColorClass(styles, document, argb)
        val newHeader = if (styles.any { it.name == className && it.colorOnly && cssColorMatches(it.colorValue, argb) }) {
            document.header
        } else {
            appendColorRule(document.header, className, argb)
        }
        val updatedEntries = document.entries.mapIndexed { index, entry ->
            if (index in positions) entry.copy(text = applyWebVttClass(entry.text, className, styles)) else entry
        }
        return document.copy(header = newHeader, entries = updatedEntries)
    }

    private fun applyWebVttClass(
        text: String,
        className: String,
        styles: List<CssRule>
    ): String {
        if (text.isBlank()) return text
        val colorClasses = styles.groupBy { it.name }.filterValues { it.all(CssRule::colorOnly) }.keys
        val cleaned = removeColorClasses(text, colorClasses)
        val match = cueClassPattern.find(cleaned)
        if (match == null) return "<c.$className>$cleaned</c>"
        val classes = match.groupValues[1].split('.').filter { it.isNotBlank() }.toMutableList()
        if (className !in classes) classes += className
        val merged = "<c.${classes.joinToString(".")}>"
        return cleaned.replaceRange(match.range, merged)
    }

    private fun removeColorClasses(text: String, colorClasses: Set<String>): String {
        val retainClosings = mutableListOf<Boolean>()
        return cueTagPattern.replace(text) { match ->
            if (match.value == "</c>") {
                if (retainClosings.isEmpty() || retainClosings.removeAt(retainClosings.lastIndex)) match.value else ""
            } else {
                val kept = match.groupValues[1].split('.').filter {
                it.isNotBlank() && it !in colorClasses && it !in DEFAULT_COLOR_CLASSES
                }
                val retain = kept.isNotEmpty()
                retainClosings += retain
                if (retain) "<c.${kept.joinToString(".")}>" else ""
            }
        }
    }

    private fun parseColorRules(header: String): List<CssRule> = styleBlocks(header).flatMap { block ->
        cssRulePattern.findAll(block).flatMap { match ->
            val selector = match.groupValues[1].trim()
            val singleSelector = singleClassSelectorPattern.matchEntire(selector)
            if (singleSelector == null) {
                // Compound/grouped selectors may carry unrelated formatting.
                return@flatMap cssClassPattern.findAll(selector).map { CssRule(it.groupValues[1], false, null) }
            }
            val declarations = match.groupValues[2].split(';').filter { it.isNotBlank() }
            val colorOnly = declarations.isNotEmpty() && declarations.all {
                it.substringBefore(':').trim().equals("color", ignoreCase = true) && ':' in it
            }
            val colorValue = declarations.lastOrNull {
                it.substringBefore(':').trim().equals("color", ignoreCase = true)
            }?.substringAfter(':')?.trim()
            sequenceOf(CssRule(singleSelector.groupValues[1], colorOnly, colorValue))
        }.toList()
    }

    private fun styleBlocks(header: String): List<String> {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        var inside = false
        header.lineSequence().forEach { line ->
            if (line.trim() == "STYLE") {
                inside = true
            } else if (line.isBlank()) {
                if (inside) blocks += current.toString()
                current.clear()
                inside = false
            } else if (inside) {
                current.appendLine(line)
            }
        }
        if (inside) blocks += current.toString()
        return blocks
    }

    private fun chooseColorClass(styles: List<CssRule>, document: SubtitleDocument, argb: Int): String {
        val grouped = styles.groupBy { it.name }
        grouped.entries.firstOrNull { (_, rules) ->
            rules.all { it.colorOnly && cssColorMatches(it.colorValue, argb) }
        }?.let { return it.key }
        val base = webVttColorClass(argb)
        val occupied = styleBlocks(document.header).flatMap { block ->
            cssClassPattern.findAll(block).map { it.groupValues[1] }.toList()
        }.toSet() + document.entries.flatMap { entry ->
            cueClassPattern.findAll(entry.text).flatMap { it.groupValues[1].split('.').asSequence() }.toList()
        }
        if (base !in occupied) return base
        var suffix = 2
        while ("$base-$suffix" in occupied) suffix++
        return "$base-$suffix"
    }

    private fun appendColorRule(header: String, className: String, argb: Int): String {
        val rule = "::cue(.$className) { color:${cssColor(argb)}; }"
        return (if (header.isEmpty()) "WEBVTT" else header) + "\n\nSTYLE\n" + rule
    }

    private fun cssColor(argb: Int): String {
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        val alpha = argb ushr 24 and 0xFF
        return if (alpha == 0xFF) "rgb($r,$g,$b)" else "rgba($r,$g,$b,${alpha / 255.0})"
    }

    private fun cssColorMatches(value: String?, argb: Int): Boolean {
        return value?.let { parseCssColor(it) } == argb
    }

    /** Parses CSS colors into Android ARGB, accepting the forms emitted by Subtitle Edit. */
    private fun parseCssColor(value: String): Int? {
        val normalized = value.trim().lowercase(Locale.US).replace(" ", "")
        if (normalized.startsWith("#")) {
            val hex = normalized.substring(1)
            return when (hex.length) {
                6 -> hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
                // CSS #RRGGBBAA stores alpha last; convert to Android AARRGGBB.
                8 -> hex.toLongOrNull(16)?.let { raw ->
                    val rgb = raw ushr 8
                    val alpha = raw and 0xFF
                    ((alpha shl 24) or rgb).toInt()
                }
                else -> null
            }
        }
        val rgb = Regex("^rgb\\((\\d+),(\\d+),(\\d+)\\)$").matchEntire(normalized)
        if (rgb != null) {
            val (r, g, b) = rgb.destructured
            return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
        val rgba = Regex("^rgba\\((\\d+),(\\d+),(\\d+),([0-9.]+)\\)$").matchEntire(normalized)
        if (rgba != null) {
            val (r, g, b, alphaText) = rgba.destructured
            val alpha = alphaText.toDoubleOrNull()?.let { (it * 255.0).roundToInt() } ?: return null
            if (alpha !in 0..255) return null
            return (alpha shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
        return null
    }

    private fun webVttColorClass(argb: Int): String = "%02x%02x%02x%02x".format(
        Locale.US,
        argb ushr 16 and 0xFF,
        argb ushr 8 and 0xFF,
        argb and 0xFF,
        argb ushr 24 and 0xFF
    )

    private data class CssRule(val name: String, val colorOnly: Boolean, val colorValue: String?)

    private val DEFAULT_COLOR_CLASSES = setOf(
        "white", "lime", "cyan", "red", "yellow", "magenta", "blue", "black", "green",
        "bg_white", "bg_lime", "bg_cyan", "bg_red", "bg_yellow", "bg_magenta", "bg_blue",
        "bg_black", "bg_green"
    )
}
