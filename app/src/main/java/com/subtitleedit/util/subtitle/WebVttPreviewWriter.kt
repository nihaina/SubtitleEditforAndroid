package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import java.util.Locale
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/** Temporary libmpv track; never used for saving or converting the user's document. */
internal object WebVttPreviewWriter {
    private val cssRule = Regex("""([^{}]+)\{([^{}]*)\}""")
    private val cueSelector = Regex("""::cue(?:\(\s*\.([A-Za-z0-9_#-]+)\s*\))?""")
    private val classTag = Regex("""<(/?)c(?:\.([A-Za-z0-9_.#-]+))?>""")
    private val voiceTag = Regex("""<(/?)(?:v|lang)(?=[\s.>])[^>]*>""")
    private val wordTimestamp = Regex("""<\d+:\d+(?::\d+)?\.\d+>""")
    private val alignmentPrefix = Regex(
        """^((?:\s*<(?:i|b|u|c(?:\.[^>]*)?|font(?:\s[^>]*)?)>)*\s*)\{\\an([1-9])\}""",
        RegexOption.IGNORE_CASE
    )

    fun write(document: SubtitleDocument): String {
        val styles = parseStyles(document.header)
        return buildString {
            appendHeader()
            document.entries.forEach { entry ->
                append("Dialogue: 0,")
                append(formatTime(entry.startTime)).append(',').append(formatTime(entry.endTime))
                append(",Default,,0,0,0,,").append(renderCue(entry, styles)).append('\n')
            }
        }
    }

    private fun StringBuilder.appendHeader() {
        // Match mpv's default subtitle style at its 384 x 288 ASS coordinate size.
        append("[Script Info]\nScriptType: v4.00+\nPlayResX: 384\nPlayResY: 288\n")
        append("ScaledBorderAndShadow: yes\nYCbCr Matrix: None\n\n[V4+ Styles]\n")
        append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, ")
        append("Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, ")
        append("Alignment, MarginL, MarginR, MarginV, Encoding\n")
        append("Style: Default,sans-serif,15.2,&H00FFFFFF,&H00FFFFFF,&H00000000,&H50000000,")
        append("0,0,0,0,100,100,0,0,1,0.66,0,2,7,7,13,1\n\n[Events]\n")
        append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
    }

    private fun renderCue(entry: SubtitleEntry, styles: Styles): String = buildString {
        var text = entry.text
        alignmentPrefix.find(text)?.let { prefix ->
            append("{\\an").append(prefix.groupValues[2]).append('}')
            text = text.replaceRange(prefix.range, prefix.groupValues[1])
        }
        val initial = State().apply(styles.global)
        appendChange(State(), initial)
        // HTML parsers see <c.foo> and </c> as different tag names. Normalize
        // the native wrappers first so nested classes keep their actual scope.
        text = classTag.replace(text) { match ->
            if (match.groupValues[1].isNotEmpty()) "</span>"
            else "<span data-vtt-classes=\"${match.groupValues[2]}\">"
        }
        text = voiceTag.replace(text) { if (it.groupValues[1].isNotEmpty()) "</span>" else "<span>" }
        text = wordTimestamp.replace(text, "")
        Parser.parseFragment(text, Element("body"), "").forEach { renderNode(it, initial, styles) }
    }

    private fun StringBuilder.renderNode(node: Node, parent: State, styles: Styles) {
        when (node) {
            is TextNode -> appendEscaped(node.wholeText)
            is Element -> {
                if (node.normalName() == "br") {
                    append("\\N")
                    return
                }
                var next = parent
                if (node.hasAttr("data-vtt-classes")) {
                    node.attr("data-vtt-classes").split('.').filter { it.isNotEmpty() }.forEach { name ->
                        next = next.apply(styles.classes[name] ?: defaultColor(name)?.let { Delta(color = it) } ?: Delta())
                    }
                }
                next = when (node.normalName()) {
                    "i" -> next.copy(italic = true)
                    "b" -> next.copy(bold = true)
                    "u" -> next.copy(underline = true)
                    "font" -> WebVttTextFormatting.parseHtmlColor(node.attr("color"))
                        ?.let { next.copy(color = it) } ?: next
                    else -> next
                }
                appendChange(parent, next)
                node.childNodes().forEach { renderNode(it, next, styles) }
                appendChange(next, parent)
            }
        }
    }

    private fun StringBuilder.appendChange(from: State, to: State) {
        if (from == to) return
        append('{')
        if (from.fontFamily != to.fontFamily) {
            append("\\fn").append(assFontName(to.fontFamily))
        }
        if (from.fontSize != to.fontSize) {
            append("\\fs").append(formatAssNumber(to.fontSize))
        }
        if (from.color != to.color) {
            append("\\1c&H").append("%02X%02X%02X".format(
                Locale.US, to.color and 0xFF, to.color ushr 8 and 0xFF, to.color ushr 16 and 0xFF
            )).append('&')
            append("\\1a&H").append("%02X".format(Locale.US, 255 - (to.color ushr 24 and 0xFF))).append('&')
        }
        if (from.bold != to.bold) append("\\b").append(if (to.bold) 1 else 0)
        if (from.italic != to.italic) append("\\i").append(if (to.italic) 1 else 0)
        if (from.underline != to.underline) append("\\u").append(if (to.underline) 1 else 0)
        if (from.strikeout != to.strikeout) append("\\s").append(if (to.strikeout) 1 else 0)
        if (from.backgroundColor != to.backgroundColor) {
            val background = to.backgroundColor ?: 0x00000000
            append("\\3c&H").append(assColor(background)).append('&')
            append("\\3a&H").append(assAlpha(background)).append('&')
        }
        if (from.borderWidth != to.borderWidth) {
            // ASS has no cue-box background override. A wide outline gives the same
            // readable approximation around a single-line cue while preserving the
            // native WebVTT CSS in the saved document.
            append("\\bord").append(formatAssNumber(to.borderWidth))
        }
        if (from.shadowColor != to.shadowColor) {
            append("\\4c&H").append(assColor(to.shadowColor)).append('&')
            append("\\4a&H").append(assAlpha(to.shadowColor)).append('&')
        }
        if (from.shadowWidth != to.shadowWidth) {
            append("\\shad").append(formatAssNumber(to.shadowWidth))
        }
        append('}')
    }

    private fun StringBuilder.appendEscaped(text: String) {
        text.forEach { character ->
            when (character) {
                '\n' -> append("\\N")
                '\r' -> Unit
                '\u00A0' -> append("\\h")
                '{', '}' -> append('\\').append(character)
                // A plain backslash is not an ASS escape. WORD JOINER breaks
                // control sequences such as literal \N, just as FFmpeg does.
                '\\' -> append('\\').append('\u2060')
                else -> append(character)
            }
        }
    }

    private fun parseStyles(header: String): Styles {
        var global = Delta()
        val classes = mutableMapOf<String, Delta>()
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        var insideStyle = false
        fun finish() {
            if (insideStyle) blocks += current.toString()
            current.clear()
        }
        header.lineSequence().forEach { line ->
            when {
                line.trim() == "STYLE" -> { finish(); insideStyle = true }
                line.isBlank() -> { finish(); insideStyle = false }
                insideStyle -> current.appendLine(line)
            }
        }
        finish()
        blocks.forEach { block ->
            val css = Regex("""/\*[\s\S]*?\*/""").replace(block, "")
            cssRule.findAll(css).forEach rule@{ match ->
                val selector = cueSelector.matchEntire(match.groupValues[1].trim()) ?: return@rule
                val delta = parseDeclarations(match.groupValues[2])
                val name = selector.groupValues[1]
                if (name.isEmpty()) global = global.merge(delta)
                else classes[name] = (classes[name] ?: Delta()).merge(delta)
            }
        }
        return Styles(global, classes)
    }

    private fun parseDeclarations(body: String): Delta {
        var delta = Delta()
        declarationParts(body).forEach { declaration ->
            if (':' !in declaration) return@forEach
            val name = declaration.substringBefore(':').trim().lowercase(Locale.US)
            val rawValue = declaration.substringAfter(':').substringBefore('!').trim().trimEnd(';').trim()
            val value = rawValue.lowercase(Locale.US)
            delta = when (name) {
                "color" -> delta.copy(color = WebVttTextFormatting.parseHtmlColor(value) ?: delta.color)
                "background-color" -> delta.copy(backgroundColor = parseColor(value) ?: delta.backgroundColor)
                "font-family" -> delta.copy(fontFamily = rawValue.split(',').firstOrNull()
                    ?.trim()?.trim('"', '\'')?.takeIf(String::isNotBlank) ?: delta.fontFamily)
                "font-size" -> delta.copy(fontSize = parseCssPixels(value) ?: delta.fontSize)
                "font-weight" -> when {
                    value == "bold" -> delta.copy(bold = true)
                    value == "normal" -> delta.copy(bold = false)
                    value.toIntOrNull() != null -> delta.copy(bold = value.toInt() >= 600)
                    else -> delta
                }
                "font-style" -> when (value) {
                    "italic", "oblique" -> delta.copy(italic = true)
                    "normal" -> delta.copy(italic = false)
                    else -> delta
                }
                "text-decoration", "text-decoration-line" -> {
                    val tokens = value.split(Regex("\\s+"))
                    when {
                        "none" in tokens -> delta.copy(underline = false, strikeout = false)
                        else -> delta.copy(
                            underline = if ("underline" in tokens) true else delta.underline ?: false,
                            strikeout = if ("line-through" in tokens) true else delta.strikeout ?: false
                        )
                    }
                }
                "text-shadow" -> parseShadow(value)?.let { (color, width) ->
                    delta.copy(shadowColor = color, shadowWidth = width)
                } ?: if (value == "none") delta.copy(shadowWidth = 0f) else delta
                else -> delta
            }
        }
        return delta
    }

    private fun declarationParts(body: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var quote: Char? = null
        var escaped = false
        var depth = 0
        body.forEachIndexed { index, character ->
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                quote != null -> if (character == quote) quote = null
                character == '\'' || character == '"' -> quote = character
                character == '(' -> depth++
                character == ')' -> depth = (depth - 1).coerceAtLeast(0)
                character == ';' && depth == 0 -> {
                    parts += body.substring(start, index + 1)
                    start = index + 1
                }
            }
        }
        parts += body.substring(start)
        return parts
    }

    private fun parseColor(value: String): Int? = when {
        value == "transparent" -> 0
        else -> WebVttTextFormatting.parseHtmlColor(value)
    }

    private fun parseCssPixels(value: String): Float? =
        Regex("^([+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))px$").matchEntire(value)
            ?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }

    private fun parseShadow(value: String): Pair<Int, Float>? {
        val parts = value.trim().split(Regex("\\s+"))
        val width = parts.lastOrNull()?.removeSuffix("px")?.toFloatOrNull()
            ?.takeIf { it.isFinite() && it > 0f } ?: return null
        val color = parseColor(parts.dropLast(1).joinToString(" ")) ?: return null
        return color to width
    }

    private fun assFontName(value: String): String = value
        .replace("\\", "")
        .replace("{", "")
        .replace("}", "")
        .trim()
        .ifBlank { "sans-serif" }

    private fun assColor(argb: Int): String = "%02X%02X%02X".format(
        Locale.US, argb and 0xFF, argb ushr 8 and 0xFF, argb ushr 16 and 0xFF
    )

    private fun assAlpha(argb: Int): String = "%02X".format(Locale.US, 255 - (argb ushr 24 and 0xFF))

    private fun formatAssNumber(value: Float): String =
        "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')

    private fun formatTime(milliseconds: Long): String {
        val centiseconds = milliseconds.coerceAtLeast(0L) / 10
        return "%d:%02d:%02d.%02d".format(
            Locale.US, centiseconds / 360_000, centiseconds / 6_000 % 60,
            centiseconds / 100 % 60, centiseconds % 100
        )
    }

    private fun defaultColor(name: String): Int? = when (name) {
        "white" -> 0xFFFFFFFF.toInt()
        "lime", "green" -> 0xFF00FF00.toInt()
        "cyan" -> 0xFF00FFFF.toInt()
        "red" -> 0xFFFF0000.toInt()
        "yellow" -> 0xFFFFFF00.toInt()
        "magenta" -> 0xFFFF00FF.toInt()
        "blue" -> 0xFF0000FF.toInt()
        "black" -> 0xFF000000.toInt()
        else -> null
    }

    private data class Styles(val global: Delta, val classes: Map<String, Delta>)

    private data class Delta(
        val fontFamily: String? = null, val fontSize: Float? = null,
        val color: Int? = null, val bold: Boolean? = null,
        val italic: Boolean? = null, val underline: Boolean? = null,
        val strikeout: Boolean? = null, val backgroundColor: Int? = null,
        val shadowColor: Int? = null, val shadowWidth: Float? = null
    ) {
        fun merge(other: Delta): Delta = Delta(
            other.fontFamily ?: fontFamily,
            other.fontSize ?: fontSize,
            other.color ?: color,
            other.bold ?: bold,
            other.italic ?: italic,
            other.underline ?: underline,
            other.strikeout ?: strikeout,
            other.backgroundColor ?: backgroundColor,
            other.shadowColor ?: shadowColor,
            other.shadowWidth ?: shadowWidth
        )
    }

    private data class State(
        val fontFamily: String = "sans-serif", val fontSize: Float = 15.2f,
        val color: Int = 0xFFFFFFFF.toInt(), val bold: Boolean = false,
        val italic: Boolean = false, val underline: Boolean = false,
        val strikeout: Boolean = false, val backgroundColor: Int? = null,
        val borderWidth: Float = 0.66f,
        val shadowColor: Int = 0xFF000000.toInt(), val shadowWidth: Float = 0f
    ) {
        fun apply(delta: Delta): State = State(
            fontFamily = delta.fontFamily ?: fontFamily,
            fontSize = delta.fontSize ?: fontSize,
            color = delta.color ?: color,
            bold = delta.bold ?: bold,
            italic = delta.italic ?: italic,
            underline = delta.underline ?: underline,
            strikeout = delta.strikeout ?: strikeout,
            backgroundColor = delta.backgroundColor ?: backgroundColor,
            borderWidth = if (delta.backgroundColor != null) {
                maxOf(borderWidth, 4f)
            } else {
                borderWidth
            },
            shadowColor = delta.shadowColor ?: shadowColor,
            shadowWidth = delta.shadowWidth ?: shadowWidth
        )
    }
}
