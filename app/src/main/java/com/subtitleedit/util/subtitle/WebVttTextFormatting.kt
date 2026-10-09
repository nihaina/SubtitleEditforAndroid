package com.subtitleedit.util.subtitle

import com.subtitleedit.util.SubtitleColorOps
import org.jsoup.parser.Parser
import java.util.Locale
import kotlin.math.abs

/** WebVTT text handling shared by writing, source synchronization, and format conversion. */
internal object WebVttTextFormatting {
    private val classTag = Regex("</?c(?:[A-Za-z._\\-\\d%#]*)>")
    private val cueRule = Regex("::cue\\(([A-Za-z._\\-\\d%#]*)\\)\\s*\\{([^{}]*)\\}")
    private val nativeTag = Regex("</?(?:v|rt|ruby|span)(?=[\\s>.])[^>]*>")
    private val wordTimestamp = Regex("<\\d+:\\d+:\\d+\\.\\d+>")
    private val alignmentWhitespace = Regex("(\\{\\\\an\\d\\})[\\s\\r\\n]+")
    private val foregroundCss = Regex("(?:^|[^A-Za-z-])color:([^;]+)", RegexOption.IGNORE_CASE)
    private val preservedShortTags = listOf("<i>", "<b>", "<u>", "<c>", "<v>")
    private val preservedLongTags = listOf("<ruby", "<font", "<v.", "<v ", "<c.", "<c ", "<lang.", "<lang ")
    private val preservedEntities = listOf("&lrm;", "&amp;", "&lt;", "&gt;")
    private val defaultColors = linkedMapOf(
        "white" to 0xFFFFFF,
        "lime" to 0x00FF00,
        "cyan" to 0x00FFFF,
        "red" to 0xFF0000,
        "yellow" to 0xFFFF00,
        "green" to 0x00FF00,
        "magenta" to 0xFF00FF,
        "blue" to 0x0000FF,
        "black" to 0x000000
    )

    fun decode(text: String): String = text
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&")

    fun writeText(text: String): String = encode(
        colorHtmlToWebVtt(removeAssTags(text).replace(Regex("[\\r\\n]+"), "\n"))
    )

    fun removeNativeFormatting(text: String, header: String): String =
        removeNativeFormatting(listOf(text), header).single()

    fun removeNativeFormatting(texts: List<String>, header: String): List<String> {
        val styles = parseCueStyles(header)
        val colors = texts.asSequence().flatMap { classTag.findAll(it) }
            .mapNotNull { bestColor(classes(it.value), styles) }
            .map { it.lowercase(Locale.US) }.toSet()
        val skipColor = colors.size == 1 && isNearWhite(colors.single())
        return texts.map { removeNativeFormatting(it, styles, skipColor) }
    }

    private fun removeNativeFormatting(text: String, styles: Map<String, String>, skipColor: Boolean): String {
        if ('<' !in text && '&' !in text) return text
        val decoded = Parser.unescapeEntities(text.replace("&rlm;", "").replace("&lrm;", ""), false)
        val endings = mutableListOf<String>()
        val result = buildString(decoded.length) {
            var offset = 0
            classTag.findAll(decoded).forEach { match ->
                append(decoded, offset, match.range.first)
                if (match.value.startsWith("</")) {
                    if (endings.isNotEmpty()) append(endings.removeAt(endings.lastIndex))
                } else {
                    val classes = classes(match.value)
                    val italic = classes.any { styles[it]?.contains("font-style:italic;", true) == true }
                    val bold = classes.any { styles[it]?.contains("font-weight:bold;", true) == true }
                    val color = if (skipColor) null else bestColor(classes, styles)
                    if (color != null) append("<font color=\"").append(color).append("\">")
                    if (bold) append("<b>")
                    if (italic) append("<i>")
                    endings += buildString {
                        if (italic) append("</i>")
                        if (bold) append("</b>")
                        if (color != null) append("</font>")
                    }
                }
                offset = match.range.last + 1
            }
            append(decoded, offset, decoded.length)
            endings.asReversed().forEach { append(it) }
        }
        return alignmentWhitespace.replace(
            wordTimestamp.replace(nativeTag.replace(result, ""), "").trim(), "$1"
        )
    }

    private fun parseCueStyles(header: String): Map<String, String> = buildMap {
        cueRule.findAll(header).forEach { match ->
            val name = match.groupValues[1].trimStart('.')
            val body = match.groupValues[2].trim().replace(" ", "")
            put(name, if (body.isNotEmpty() && !body.endsWith(';')) "$body;" else body)
        }
    }

    private fun classes(tag: String): List<String> =
        tag.removePrefix("<c.").trimEnd('>').split('.')

    private fun bestColor(classes: List<String>, styles: Map<String, String>): String? {
        classes.asReversed().forEach { name ->
            val lower = name.lowercase(Locale.US)
            if (lower in defaultColors) return lower
            if (lower.startsWith("color") && lower.length > 6 && lower.drop(5).all { it in "0123456789abcdef" }) {
                return "#${lower.drop(5)}"
            }
            styles[name]?.let { css ->
                foregroundCss.find(css)
                    ?.groupValues?.get(1)?.let { toFontColor(it) }?.let { return it }
            }
        }
        return null
    }

    private fun toFontColor(color: String): String? {
        if (!color.startsWith("rgb", ignoreCase = true)) return color
        val match = Regex("^rgba?\\(([^()]*)\\)$", RegexOption.IGNORE_CASE).matchEntire(color) ?: return null
        val components = match.groupValues[1].split(',')
        if (components.size !in 3..4) return null
        val rgb = components.take(3).map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: return null }
        if (components.size == 4 && (components[3].toDoubleOrNull() ?: 1.0) <= 0) return null
        return "#%02X%02X%02X".format(Locale.US, rgb[0], rgb[1], rgb[2])
    }

    private fun isNearWhite(color: String): Boolean {
        val rgb = rgbColor(color) ?: return false
        val components = listOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
        return components.min() >= 200 && components.max() - components.min() <= 16
    }

    private fun rgbColor(color: String): Int? {
        defaultColors[color.lowercase(Locale.US)]?.let { return it }
        // Named near-white colors accepted by Subtitle Edit's ColorTranslator.
        val named = when (color.lowercase(Locale.US)) {
            "gainsboro" -> 0xDCDCDC
            "aliceblue" -> 0xF0F8FF
            "lightgrey", "lightgray" -> 0xD3D3D3
            "whitesmoke" -> 0xF5F5F5
            "snow" -> 0xFFFAFA
            "ghostwhite" -> 0xF8F8FF
            "ivory" -> 0xFFFFF0
            "honeydew" -> 0xF0FFF0
            "mintcream" -> 0xF5FFFA
            "azure" -> 0xF0FFFF
            "lavenderblush" -> 0xFFF0F5
            "floralwhite" -> 0xFFFAF0
            "seashell" -> 0xFFF5EE
            else -> null
        }
        if (named != null) return named
        if (color.startsWith('#') && color.length == 4) {
            return color.drop(1).flatMap { listOf(it, it) }.joinToString("").toIntOrNull(16)
        }
        return SubtitleColorOps.parseArgb(color)?.and(0xFFFFFF)
    }

    private fun removeAssTags(text: String): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            if (text[index] == '{' && (text.startsWith("{\\", index) || text.startsWith("{Kara Effector", index))) {
                val end = text.indexOf('}', index + 1)
                if (end >= 0) {
                    index = end + 1
                    continue
                }
            }
            if (text[index] == '\\' && index + 1 < text.length) {
                when (text[index + 1]) {
                    'N', 'n' -> { append('\n'); index += 2; continue }
                    'h' -> { append(' '); index += 2; continue }
                }
            }
            append(text[index++])
        }
    }

    private fun colorHtmlToWebVtt(text: String): String {
        var result = text.replace("</font>", "</c>")
        result = Regex("<font color=\"([A-Za-z]*)\">").replace(result) {
            "<c.${it.groupValues[1].lowercase(Locale.US)}>"
        }
        result = Regex("<font color=([A-Za-z]*)>").replace(result) {
            "<c.${it.groupValues[1].lowercase(Locale.US)}>"
        }
        result = Regex("<font color=\"#([ABCDEFabcdef\\d]*)\">").replace(result) { match ->
            val hex = match.groupValues[1]
            val close = rgbColor("#$hex")?.let { rgb ->
                defaultColors.entries.firstOrNull { (_, candidate) ->
                    listOf(0, 8, 16).all { shift -> abs((candidate shr shift and 0xFF) - (rgb shr shift and 0xFF)) <= 25 }
                }?.key
            }
            "<c.${close ?: "color$hex"}>"
        }
        return result
    }

    private fun encode(text: String): String = buildString(text.length) {
        var index = 0
        var tagOn = false
        while (index < text.length) {
            when (text[index]) {
                '<' -> when {
                    preservedShortTags.any { text.startsWith(it, index, true) } -> {
                        append(text, index, index + 3)
                        index += 3
                        continue
                    }
                    text.startsWith("</", index) -> {
                        append("</")
                        index += 2
                        tagOn = true
                        continue
                    }
                    preservedLongTags.any { text.startsWith(it, index, true) } -> {
                        append('<')
                        tagOn = true
                    }
                    else -> append("&lt;")
                }
                '>' -> {
                    append(if (tagOn) ">" else "&gt;")
                    tagOn = false
                }
                '&' -> {
                    val known = preservedEntities.any { text.startsWith(it, index, true) }
                    val shortEntity = (2..3).any { length ->
                        text.getOrNull(index + length + 1) == ';' &&
                            (1..length).all { text[index + it].isLetter() }
                    }
                    append(if (known || shortEntity) "&" else "&amp;")
                }
                else -> append(text[index])
            }
            index++
        }
    }
}
