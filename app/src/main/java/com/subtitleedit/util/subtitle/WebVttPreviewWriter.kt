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
        if (from.color != to.color) {
            append("\\1c&H").append("%02X%02X%02X".format(
                Locale.US, to.color and 0xFF, to.color ushr 8 and 0xFF, to.color ushr 16 and 0xFF
            )).append('&')
            append("\\1a&H").append("%02X".format(Locale.US, 255 - (to.color ushr 24 and 0xFF))).append('&')
        }
        if (from.bold != to.bold) append("\\b").append(if (to.bold) 1 else 0)
        if (from.italic != to.italic) append("\\i").append(if (to.italic) 1 else 0)
        if (from.underline != to.underline) append("\\u").append(if (to.underline) 1 else 0)
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
        body.split(';').forEach { declaration ->
            if (':' !in declaration) return@forEach
            val name = declaration.substringBefore(':').trim().lowercase(Locale.US)
            val value = declaration.substringAfter(':').substringBefore('!').trim().lowercase(Locale.US)
            delta = when (name) {
                "color" -> delta.copy(color = WebVttTextFormatting.parseHtmlColor(value) ?: delta.color)
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
                "text-decoration", "text-decoration-line" -> delta.copy(underline = "underline" in value)
                else -> delta
            }
        }
        return delta
    }

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
        val color: Int? = null, val bold: Boolean? = null,
        val italic: Boolean? = null, val underline: Boolean? = null
    ) {
        fun merge(other: Delta): Delta = Delta(
            other.color ?: color, other.bold ?: bold, other.italic ?: italic, other.underline ?: underline
        )
    }

    private data class State(
        val color: Int = 0xFFFFFFFF.toInt(), val bold: Boolean = false,
        val italic: Boolean = false, val underline: Boolean = false
    ) {
        fun apply(delta: Delta): State = State(
            delta.color ?: color, delta.bold ?: bold, delta.italic ?: italic, delta.underline ?: underline
        )
    }
}
