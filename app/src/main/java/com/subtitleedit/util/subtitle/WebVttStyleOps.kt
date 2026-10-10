package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import java.util.Locale

/**
 * Subtitle Edit compatible operations for WebVTT STYLE blocks.
 *
 * VTT styles are CSS rules in the document header.  The cue text refers to a
 * rule with a native `<c.class>` span; converting these rules to HTML/ASS tags
 * would lose properties that are not understood by the generic formatter.
 */
data class WebVttStyleDefinition(
    val name: String,
    val declarations: Map<String, String>,
    val rawDeclarations: String,
    val usageCount: Int = 0
) {
    fun property(name: String): String? = declarations[name.lowercase(Locale.US)]
}

object WebVttStyleOps {
    private val rulePattern = Regex(
        "::cue\\(\\s*\\.([A-Za-z0-9_#-]+)\\s*\\)\\s*\\{([^{}]*)\\}",
        RegexOption.IGNORE_CASE
    )
    private val cueClassPattern = Regex("<c\\.([A-Za-z0-9_#-]+(?:\\.[A-Za-z0-9_#-]+)*)>", RegexOption.IGNORE_CASE)
    private val styleBlockMarker = Regex("(?im)^\\s*STYLE\\s*$")
    private val classToken = Regex("(?<![A-Za-z0-9_#-])\\.([A-Za-z0-9_#-]+)(?![A-Za-z0-9_#-])")
    private val declarationName = Regex("^[A-Za-z][A-Za-z0-9-]*$")
    private val cueTagPattern = Regex("</?c(?:\\.([A-Za-z0-9_#-]+(?:\\.[A-Za-z0-9_#-]+)*))?>", RegexOption.IGNORE_CASE)

    /** Reads standalone `::cue(.name)` rules from STYLE blocks in [header]. */
    fun list(document: SubtitleDocument): List<WebVttStyleDefinition> =
        if (document.format != SubtitleFormat.VTT) emptyList() else {
            val uses = document.entries
                .flatMap { entry -> cueClassPattern.findAll(entry.text).flatMap { classes(it.groupValues[1].split('.')) }.distinct().toList() }
                .groupingBy { it }
                .eachCount()
            rulePattern.findAll(document.header)
                .map { match ->
                    val name = match.groupValues[1]
                    WebVttStyleDefinition(
                        name = name,
                        declarations = parseDeclarations(match.groupValues[2]),
                        rawDeclarations = match.groupValues[2].trim(),
                        usageCount = uses[name] ?: uses[name.lowercase(Locale.US)] ?: 0
                    )
                }
                .distinctBy { it.name.lowercase(Locale.US) }
                .toList()
        }

    /** Applies [styleName] to each selected cue, preserving existing classes and tags. */
    fun apply(document: SubtitleDocument, positions: Set<Int>, styleName: String): SubtitleDocument {
        if (!isVtt(document) || positions.isEmpty() || !isClassName(styleName)) return document
        return document.copy(entries = document.entries.mapIndexed { index, entry ->
            if (index in positions) entry.copy(text = addClass(entry.text, styleName)) else entry
        })
    }

    /** Removes [styleName] from each selected cue without touching other classes. */
    fun remove(document: SubtitleDocument, positions: Set<Int>, styleName: String): SubtitleDocument {
        if (!isVtt(document) || positions.isEmpty() || !isClassName(styleName)) return document
        return document.copy(entries = document.entries.mapIndexed { index, entry ->
            if (index in positions) entry.copy(text = removeClass(entry.text, styleName)) else entry
        })
    }

    /** Renames a style definition and all cue references atomically. */
    fun rename(document: SubtitleDocument, oldName: String, newName: String): SubtitleDocument {
        if (!isVtt(document) || !isClassName(oldName) || !isClassName(newName) ||
            oldName.equals(newName, ignoreCase = true)
        ) return document
        val header = replaceClassToken(document.header, oldName, newName)
        val entries = document.entries.map { entry -> entry.copy(text = replaceCueClassToken(entry.text, oldName, newName)) }
        return document.copy(header = header, entries = entries)
    }

    /** Removes one standalone class rule while retaining all other STYLE CSS. */
    fun delete(document: SubtitleDocument, styleName: String, removeCueReferences: Boolean = false): SubtitleDocument {
        if (!isVtt(document) || !isClassName(styleName)) return document
        val header = removeRule(document.header, styleName)
        val entries = if (removeCueReferences) {
            document.entries.map { entry -> entry.copy(text = removeClass(entry.text, styleName)) }
        } else document.entries
        return document.copy(header = header, entries = entries)
    }

    /** Adds or replaces a standalone class rule in the first STYLE block. */
    fun upsert(
        document: SubtitleDocument,
        styleName: String,
        declarations: Map<String, String>
    ): SubtitleDocument {
        if (!isVtt(document) || !isClassName(styleName)) return document
        val properties = declarations.entries
            .filter { declarationName.matches(it.key.trim()) && it.value.isNotBlank() }
            .associate { it.key.trim().lowercase(Locale.US) to it.value.trim() }
        val existing = rulePattern.findAll(document.header).firstOrNull { it.groupValues[1].equals(styleName, true) }
        if (existing != null && parseDeclarations(existing.groupValues[2]) == properties) return document
        val body = if (existing == null) properties.entries.joinToString(" ") { "${it.key}:${it.value};" }
        else updateDeclarations(existing.groupValues[2], properties)
        val rule = "::cue(.$styleName) { $body }"
        val header = if (existing != null) {
            document.header.replaceRange(existing.range, rule)
        } else {
            appendRule(document.header, rule)
        }
        return document.copy(header = header)
    }

    /** Applies an already parsed definition, retaining its declaration order. */
    fun upsert(document: SubtitleDocument, style: WebVttStyleDefinition): SubtitleDocument =
        upsertRaw(document, style.name, style.rawDeclarations)

    private fun upsertRaw(document: SubtitleDocument, styleName: String, body: String): SubtitleDocument {
        if (!isVtt(document) || !isClassName(styleName)) return document
        val declarations = body.trim().trimEnd(';')
        val rule = "::cue(.$styleName) { ${if (declarations.isEmpty()) "" else "$declarations;"} }"
        val existing = rulePattern.findAll(document.header).firstOrNull { it.groupValues[1].equals(styleName, true) }
        val header = if (existing != null) document.header.replaceRange(existing.range, rule) else appendRule(document.header, rule)
        return document.copy(header = header)
    }

    private fun classes(value: List<String>): Sequence<String> = value.asSequence().filter { it.isNotBlank() }

    private fun addClass(text: String, name: String): String {
        if (text.isBlank()) return text
        var inserted = false
        val updated = cueClassPattern.replace(text) { match ->
            if (inserted) return@replace match.value
            inserted = true
            val classes = match.groupValues[1].split('.').filter { it.isNotBlank() }
            if (classes.any { it.equals(name, true) }) match.value
            else "<c.${(classes + name).joinToString(".")}>"
        }
        if (inserted) return updated
        val opening = Regex("^<((?:v|lang)(?:\\s+[^>]*|\\.[^>]*)?)>", RegexOption.IGNORE_CASE).find(text)
        return if (opening != null) {
            val contentStart = opening.range.last + 1
            val closing = Regex("</(?:v|lang)>", RegexOption.IGNORE_CASE).find(text, contentStart)
            if (closing == null) {
                text.substring(0, contentStart) + "<c.$name>" + text.substring(contentStart) + "</c>"
            } else {
                text.substring(0, contentStart) + "<c.$name>" +
                    text.substring(contentStart, closing.range.first) + "</c>" + text.substring(closing.range.first)
            }
        } else "<c.$name>$text</c>"
    }

    private fun removeClass(text: String, name: String): String = buildString(text.length) {
        val keptScopes = ArrayDeque<Boolean>()
        var offset = 0
        cueTagPattern.findAll(text).forEach { match ->
            append(text, offset, match.range.first)
            if (match.value.startsWith("</")) {
                if (keptScopes.removeLastOrNull() == true) append("</c>")
            } else {
                val kept = match.groupValues[1].split('.').filter { it.isNotBlank() && !it.equals(name, true) }
                keptScopes.addLast(kept.isNotEmpty())
                if (kept.isNotEmpty()) append("<c.${kept.joinToString(".")}>")
            }
            offset = match.range.last + 1
        }
        append(text, offset, text.length)
    }

    private fun replaceCueClassToken(text: String, oldName: String, newName: String): String =
        cueClassPattern.replace(text) { match ->
            val classes = match.groupValues[1].split('.').map { if (it.equals(oldName, true)) newName else it }
            "<c.${classes.joinToString(".")}>"
        }

    private fun replaceClassToken(text: String, oldName: String, newName: String): String =
        classToken.replace(text) { match -> if (match.groupValues[1].equals(oldName, true)) ".${newName}" else match.value }

    private fun removeRule(header: String, name: String): String {
        val rule = rulePattern.findAll(header).firstOrNull { it.groupValues[1].equals(name, true) } ?: return header
        var result = header.removeRange(rule.range)
        result = result.replace(Regex("\\n{3,}"), "\n\n").trimEnd()
        return result
    }

    private fun appendRule(header: String, rule: String): String {
        val base = header.ifBlank { "WEBVTT" }.trimEnd()
        val marker = styleBlockMarker.find(base)
        if (marker == null) return "$base\n\nSTYLE\n$rule"
        val blockEnd = Regex("\\r?\\n[ \\t]*\\r?\\n").find(base, marker.range.last + 1)
        if (blockEnd == null) return "$base\n$rule"
        return base.substring(0, blockEnd.range.first) + "\n$rule" + base.substring(blockEnd.range.first)
    }

    /** Retains every occurrence, including Subtitle Edit's separate decoration declarations. */
    internal fun propertyValues(body: String, name: String): List<String> = declarationParts(body)
        .mapNotNull(::parseDeclaration)
        .filter { it.first.equals(name, true) }
        .map { it.second }

    private fun parseDeclarations(body: String): Map<String, String> = buildMap {
        declarationParts(body).mapNotNull(::parseDeclaration).forEach { (key, value) ->
            // Subtitle Edit serializes underline and strike-through as two
            // text-decoration declarations. A Map cannot hold duplicates, so
            // retain both values as one valid CSS shorthand while preserving
            // the original raw block for an unchanged upsert.
            if (key == "text-decoration" && containsKey(key)) {
                val previous = getValue(key).split(Regex("\\s+"))
                val additions = value.split(Regex("\\s+"))
                put(key, (previous + additions).distinct().joinToString(" "))
            } else {
                put(key, value)
            }
        }
    }

    private fun parseDeclaration(declaration: String): Pair<String, String>? {
        val separator = declaration.indexOf(':')
        if (separator <= 0) return null
        val key = declaration.substring(0, separator).trim().lowercase(Locale.US)
        val value = declaration.substring(separator + 1).trim().trimEnd(';').trimEnd()
        return if (key.isNotBlank() && value.isNotBlank()) key to value else null
    }

    private fun updateDeclarations(body: String, updated: Map<String, String>): String {
        val original = parseDeclarations(body)
        val written = mutableSetOf<String>()
        return buildString {
            declarationParts(body).forEach { part ->
                val (key, _) = parseDeclaration(part) ?: run { append(part); return@forEach }
                if (original[key] == updated[key]) {
                    // Keep the raw text of unchanged properties, including duplicate
                    // decorations generated by Subtitle Edit and unsupported CSS.
                    append(part)
                    written += key
                } else if (key in updated && written.add(key)) {
                    append(part.takeWhile { it.isWhitespace() })
                    append(key).append(':').append(updated.getValue(key)).append(';')
                }
            }
            updated.forEach { (key, value) ->
                if (written.add(key)) {
                    if (isNotEmpty() && !last().isWhitespace()) append(' ')
                    append(key).append(':').append(value).append(';')
                }
            }
        }.trim()
    }

    private fun declarationParts(body: String): List<String> {
        // Font names and CSS functions can contain semicolons. They do not end
        // a declaration while inside a quoted string or parentheses.
        val parts = mutableListOf<String>()
        var start = 0
        var quote: Char? = null
        var escaped = false
        var depth = 0
        body.forEachIndexed { index, char ->
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                quote != null -> if (char == quote) quote = null
                char == '\'' || char == '"' -> quote = char
                char == '(' -> depth++
                char == ')' -> depth = (depth - 1).coerceAtLeast(0)
                char == ';' && depth == 0 -> {
                    parts += body.substring(start, index + 1)
                    start = index + 1
                }
            }
        }
        parts += body.substring(start)
        return parts
    }

    private fun isVtt(document: SubtitleDocument): Boolean = document.format == SubtitleFormat.VTT

    fun isValidName(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_#-]+"))

    private fun isClassName(value: String): Boolean = isValidName(value)
}

/** Subtitle Edit compatible `<v Speaker>...</v>` helpers. */
object WebVttVoiceOps {
    private val opening = Regex("<v(?:\\s+([^>]*))?>", RegexOption.IGNORE_CASE)
    private val closing = Regex("</v>", RegexOption.IGNORE_CASE)

    fun list(document: SubtitleDocument): List<String> =
        if (document.format != SubtitleFormat.VTT) emptyList() else document.entries
            .asSequence()
            .flatMap { entry -> opening.findAll(entry.text).map { it.groupValues[1].trim() } }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    fun get(entry: SubtitleEntry): String? = opening.find(entry.text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    /** Replaces every existing voice wrapper, preventing nested `<v>` tags. */
    fun set(entry: SubtitleEntry, voice: String?): SubtitleEntry = entry.copy(text = setText(entry.text, voice))

    fun set(document: SubtitleDocument, positions: Set<Int>, voice: String?): SubtitleDocument {
        if (document.format != SubtitleFormat.VTT || positions.isEmpty()) return document
        return document.copy(entries = document.entries.mapIndexed { index, entry ->
            if (index in positions) set(entry, voice) else entry
        })
    }

    fun remove(document: SubtitleDocument, positions: Set<Int>): SubtitleDocument = set(document, positions, null)

    fun setText(text: String, voice: String?): String {
        val body = closing.replace(opening.replace(text, ""), "")
        val name = voice?.replace(Regex("[\\r\\n>]+"), " ")?.trim().orEmpty()
        return if (name.isBlank() || body.isBlank()) body else "<v $name>$body</v>"
    }
}
