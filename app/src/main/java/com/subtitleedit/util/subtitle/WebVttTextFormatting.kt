package com.subtitleedit.util.subtitle

import com.subtitleedit.util.SubtitleColorOps
import org.jsoup.nodes.Element
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
    private val fontTag = Regex("""<font(?=[\s>])(?:"[^"]*"|'[^']*'|[^'">])*?>|</font\s*>""", RegexOption.IGNORE_CASE)
    private val colorClass = Regex("""<c\.([A-Za-z0-9_.#-]+)>""")
    private val cssClass = Regex("""\.([A-Za-z0-9_#-]+)""")
    private val singleCueSelector = Regex("""::cue\(\.([A-Za-z0-9_#-]+)\)""")
    private val cssRule = Regex("""([^{}]+)\{([^{}]*)\}""")
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

    /**
     * A text-only color conversion cannot add the STYLE rule needed by a custom cue class.
     * Prepare all cues together so writing uses the same class/CSS representation as
     * Subtitle Edit's WebVttHelper color tools, without recoloring other text fragments.
     */
    fun prepareDocument(document: SubtitleDocument): SubtitleDocument {
        if (document.entries.none { fontTag.containsMatchIn(it.text) }) return document
        val styles = colorRules(document.header)
        val occupied = cssClass.findAll(document.header + "\n" + document.footer).map { it.groupValues[1] }.toMutableSet()
        document.entries.forEach { entry ->
            colorClass.findAll(entry.text).forEach { occupied += it.groupValues[1].split('.') }
        }
        val chosen = mutableMapOf<Int, String>()
        val additions = mutableListOf<String>()
        fun classFor(color: Int): String = chosen.getOrPut(color) {
            styles.entries.firstOrNull { (_, rules) ->
                rules.all { it.colorOnly && it.color == color }
            }?.key ?: run {
                val base = "%02x%02x%02x%02x".format(
                    Locale.US, color ushr 16 and 0xFF, color ushr 8 and 0xFF,
                    color and 0xFF, color ushr 24 and 0xFF
                )
                var name = base
                var suffix = 2
                while (name in occupied) name = "$base-${suffix++}"
                occupied += name
                val r = color ushr 16 and 0xFF
                val g = color ushr 8 and 0xFF
                val b = color and 0xFF
                val alpha = color ushr 24 and 0xFF
                val css = if (alpha == 255) "rgb($r,$g,$b)" else "rgba($r,$g,$b,${alpha / 255.0})"
                additions += "::cue(.$name) { color:$css; }"
                name
            }
        }
        val entries = document.entries.map { entry ->
            val text = rewriteFontColors(entry.text) { value -> parseHtmlColor(value)?.let(::classFor) }
            if (text == entry.text) entry else entry.copy(text = text)
        }
        val header = if (additions.isEmpty()) document.header else
            document.header.ifBlank { "WEBVTT" }.trimEnd() + "\n\nSTYLE\n" + additions.joinToString("\n")
        return document.copy(entries = entries, header = header)
    }

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
        return parseHtmlColor(color)?.and(0xFFFFFF)
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
        return rewriteFontColors(text) { color ->
            if (color.all { it.isLetter() } && parseHtmlColor(color) != null) {
                return@rewriteFontColors color.lowercase(Locale.US)
            }
            val rgb = parseHtmlColor(color) ?: return@rewriteFontColors null
            val close = defaultColors.entries.firstOrNull { (_, candidate) ->
                listOf(0, 8, 16).all { shift -> abs((candidate shr shift and 0xFF) - (rgb shr shift and 0xFF)) <= 25 }
            }?.key
            close ?: "color${color.removePrefix("#")}".takeIf { color.startsWith('#') }
        }
    }

    private fun rewriteFontColors(text: String, classFor: (String) -> String?): String = buildString(text.length) {
        val endings = mutableListOf<Boolean>()
        var offset = 0
        fontTag.findAll(text).forEach { match ->
            append(text, offset, match.range.first)
            if (match.value.startsWith("</")) {
                val converted = if (endings.isNotEmpty()) endings.removeAt(endings.lastIndex) else false
                append(if (converted) "</c>" else match.value)
            } else {
                val element = Parser.parseFragment(match.value, Element("body"), "").filterIsInstance<Element>()
                    .firstOrNull { it.normalName() == "font" }
                val name = element?.attr("color")?.takeIf { it.isNotEmpty() }?.let(classFor)
                endings += name != null
                append(if (name == null) match.value else "<c.$name>")
            }
            offset = match.range.last + 1
        }
        append(text, offset, text.length)
    }

    private data class ColorRule(val colorOnly: Boolean, val color: Int?)

    private fun colorRules(header: String): Map<String, List<ColorRule>> {
        val rules = mutableListOf<Pair<String, ColorRule>>()
        var insideStyle = false
        val css = StringBuilder()
        fun finish() {
            cssRule.findAll(css.toString()).forEach { rule ->
                val selector = rule.groupValues[1].trim()
                val name = singleCueSelector.matchEntire(selector)?.groupValues?.get(1)
                if (name == null) {
                    // A class used by a grouped/compound selector may carry formatting
                    // beyond its standalone color rule, so it is not safe to reuse.
                    cssClass.findAll(selector).map { it.groupValues[1] }.distinct().forEach {
                        rules += it to ColorRule(colorOnly = false, color = null)
                    }
                    return@forEach
                }
                val declarations = rule.groupValues[2].split(';').filter { it.isNotBlank() }
                val colorOnly = declarations.isNotEmpty() && declarations.all {
                    it.substringBefore(':').trim().equals("color", true) && ':' in it
                }
                val color = declarations.lastOrNull {
                    it.substringBefore(':').trim().equals("color", true)
                }?.substringAfter(':')?.let(::parseHtmlColor)
                rules += name to ColorRule(colorOnly, color)
            }
            css.clear()
        }
        header.lineSequence().forEach { line ->
            when {
                line.trim() == "STYLE" -> { if (insideStyle) finish(); insideStyle = true }
                line.isBlank() -> { if (insideStyle) finish(); insideStyle = false }
                insideStyle -> css.appendLine(line)
            }
        }
        if (insideStyle) finish()
        return rules.groupBy({ it.first }, { it.second })
    }

    internal fun parseHtmlColor(value: String): Int? {
        val color = value.trim().lowercase(Locale.US)
        if (color == "transparent") return 0
        htmlNamedColors[color]?.let { return (0xFF000000L or it.toLong()).toInt() }
        if (color.startsWith('#') && color.length in 4..5) {
            return SubtitleColorOps.parseCssColor("#" + color.drop(1).flatMap { listOf(it, it) }.joinToString(""))
        }
        return SubtitleColorOps.parseCssColor(color)
    }

    // Standard CSS named colors, kept independent of Android Color for JVM conversion/tests.
    private val htmlNamedColors = """
        aliceblue:F0F8FF antiquewhite:FAEBD7 aqua:00FFFF aquamarine:7FFFD4 azure:F0FFFF beige:F5F5DC bisque:FFE4C4
        black:000000 blanchedalmond:FFEBCD blue:0000FF blueviolet:8A2BE2 brown:A52A2A burlywood:DEB887 cadetblue:5F9EA0
        chartreuse:7FFF00 chocolate:D2691E coral:FF7F50 cornflowerblue:6495ED cornsilk:FFF8DC crimson:DC143C cyan:00FFFF
        darkblue:00008B darkcyan:008B8B darkgoldenrod:B8860B darkgray:A9A9A9 darkgrey:A9A9A9 darkgreen:006400
        darkkhaki:BDB76B darkmagenta:8B008B darkolivegreen:556B2F darkorange:FF8C00 darkorchid:9932CC darkred:8B0000
        darksalmon:E9967A darkseagreen:8FBC8F darkslateblue:483D8B darkslategray:2F4F4F darkslategrey:2F4F4F
        darkturquoise:00CED1 darkviolet:9400D3 deeppink:FF1493 deepskyblue:00BFFF dimgray:696969 dimgrey:696969
        dodgerblue:1E90FF firebrick:B22222 floralwhite:FFFAF0 forestgreen:228B22 fuchsia:FF00FF gainsboro:DCDCDC
        ghostwhite:F8F8FF gold:FFD700 goldenrod:DAA520 gray:808080 grey:808080 green:008000 greenyellow:ADFF2F
        honeydew:F0FFF0 hotpink:FF69B4 indianred:CD5C5C indigo:4B0082 ivory:FFFFF0 khaki:F0E68C lavender:E6E6FA
        lavenderblush:FFF0F5 lawngreen:7CFC00 lemonchiffon:FFFACD lightblue:ADD8E6 lightcoral:F08080 lightcyan:E0FFFF
        lightgoldenrodyellow:FAFAD2 lightgray:D3D3D3 lightgrey:D3D3D3 lightgreen:90EE90 lightpink:FFB6C1
        lightsalmon:FFA07A lightseagreen:20B2AA lightskyblue:87CEFA lightslategray:778899 lightslategrey:778899
        lightsteelblue:B0C4DE lightyellow:FFFFE0 lime:00FF00 limegreen:32CD32 linen:FAF0E6 magenta:FF00FF
        maroon:800000 mediumaquamarine:66CDAA mediumblue:0000CD mediumorchid:BA55D3 mediumpurple:9370DB
        mediumseagreen:3CB371 mediumslateblue:7B68EE mediumspringgreen:00FA9A mediumturquoise:48D1CC
        mediumvioletred:C71585 midnightblue:191970 mintcream:F5FFFA mistyrose:FFE4E1 moccasin:FFE4B5 navajowhite:FFDEAD
        navy:000080 oldlace:FDF5E6 olive:808000 olivedrab:6B8E23 orange:FFA500 orangered:FF4500 orchid:DA70D6
        palegoldenrod:EEE8AA palegreen:98FB98 paleturquoise:AFEEEE palevioletred:DB7093 papayawhip:FFEFD5
        peachpuff:FFDAB9 peru:CD853F pink:FFC0CB plum:DDA0DD powderblue:B0E0E6 purple:800080 rebeccapurple:663399
        red:FF0000 rosybrown:BC8F8F royalblue:4169E1 saddlebrown:8B4513 salmon:FA8072 sandybrown:F4A460 seagreen:2E8B57
        seashell:FFF5EE sienna:A0522D silver:C0C0C0 skyblue:87CEEB slateblue:6A5ACD slategray:708090 slategrey:708090
        snow:FFFAFA springgreen:00FF7F steelblue:4682B4 tan:D2B48C teal:008080 thistle:D8BFD8 tomato:FF6347
        turquoise:40E0D0 violet:EE82EE wheat:F5DEB3 white:FFFFFF whitesmoke:F5F5F5 yellow:FFFF00 yellowgreen:9ACD32
    """.trim().split(Regex("\\s+")).associate { name ->
        name.substringBefore(':') to name.substringAfter(':').toInt(16)
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
