package com.subtitleedit.util

/**
 * Operations used by the editor's "remove formatting" menu.
 *
 * Subtitle text can contain HTML/WebVTT tags or ASS/SSA override blocks.  The
 * operation deliberately leaves unknown tags and ordinary brace text alone;
 * only tags which carry the selected formatting are removed.
 */
object SubtitleFormattingTagOps {
    enum class Kind {
        ALL,
        BOLD,
        ITALIC,
        UNDERLINE,
        COLOR,
        FONT_NAME,
        ALIGNMENT
    }

    fun remove(text: String, kind: Kind): String {
        if (text.isEmpty()) return text
        return buildString(text.length) {
            val htmlOpenTags = mutableListOf<Pair<String, Boolean>>()
            var index = 0
            while (index < text.length) {
                when {
                    text[index] == '<' -> {
                        val end = text.indexOf('>', index + 1)
                        if (end < 0) {
                            append(text[index++])
                        } else {
                            val tag = text.substring(index, end + 1)
                            appendHtmlTag(tag, kind, this, htmlOpenTags)
                            index = end + 1
                        }
                    }
                    text[index] == '{' && text.getOrNull(index + 1) == '\\' -> {
                        val end = text.indexOf('}', index + 2)
                        if (end < 0) {
                            append(text[index++])
                        } else {
                            val block = text.substring(index, end + 1)
                            appendAssBlock(block, kind, this)
                            index = end + 1
                        }
                    }
                    else -> append(text[index++])
                }
            }
        }
    }

    private fun shouldRemoveHtmlTag(tag: String, kind: Kind): Boolean {
        if (kind == Kind.ALL) return true
        val body = tag.substring(1, tag.length - 1).trim().lowercase()
        val name = body.substringAfter('/').substringBefore(' ').substringBefore('>').trim()
        return when (kind) {
            Kind.BOLD -> name == "b" || name == "strong"
            Kind.ITALIC -> name == "i" || name == "em"
            Kind.UNDERLINE -> name == "u" || name == "ins"
            Kind.COLOR -> name == "c" || name.startsWith("c.") ||
                name == "font" && hasAttribute(body, "color") ||
                name == "span" && hasStyle(body, "color")
            Kind.FONT_NAME -> name == "font" && (hasAttribute(body, "face") || hasAttribute(body, "font-family")) ||
                name == "span" && hasStyle(body, "font-family")
            Kind.ALIGNMENT -> name == "align" || name == "p" && hasAttribute(body, "align") ||
                name == "span" && hasStyle(body, "text-align")
            Kind.ALL -> false
        }
    }

    private fun hasAttribute(body: String, attribute: String): Boolean =
        Regex("(?:^|\\s)$attribute\\s*=|(?:^|\\s)$attribute\\s+", RegexOption.IGNORE_CASE)
            .containsMatchIn(body)

    private fun hasStyle(body: String, property: String): Boolean =
        Regex("style\\s*=\\s*['\"][^'\"]*\\b$property\\s*:", RegexOption.IGNORE_CASE)
            .containsMatchIn(body)

    private fun appendHtmlTag(
        tag: String,
        kind: Kind,
        out: StringBuilder,
        openTags: MutableList<Pair<String, Boolean>>
    ) {
        if (kind == Kind.ALL) return
        val body = tag.substring(1, tag.length - 1).trim()
        val closing = body.startsWith('/')
        val name = body.removePrefix("/").substringBefore(' ').substringBefore('/').lowercase()
        if (closing) {
            val openIndex = openTags.indexOfLast { it.first == name }
            if (openIndex >= 0) {
                val retainClose = openTags.removeAt(openIndex).second
                if (retainClose) out.append(tag)
            } else if (!shouldRemoveHtmlTag(tag, kind)) {
                out.append(tag)
            }
            return
        }

        if (name == "font" && (kind == Kind.COLOR || kind == Kind.FONT_NAME)) {
            val attribute = if (kind == Kind.COLOR) "color" else "(?:face|font-family)"
            val rewritten = removeHtmlAttribute(tag, attribute)
            val retained = rewritten != null
            openTags += name to retained
            if (rewritten != null) out.append(rewritten)
            return
        }
        val removesTag = shouldRemoveHtmlTag(tag, kind)
        if (name.isNotEmpty() && !body.endsWith('/')) openTags += name to !removesTag
        if (!removesTag) out.append(tag)
    }

    private fun removeHtmlAttribute(tag: String, attributePattern: String): String? {
        val rewritten = tag.replace(
            Regex("\\s+$attributePattern\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE),
            ""
        )
        if (rewritten == tag) return tag
        val tagNameEnd = Regex("^<[^\\s/>]+").find(rewritten)?.range?.last?.plus(1) ?: 1
        val remainingAttributes = rewritten.substring(tagNameEnd, rewritten.lastIndex)
            .trim()
            .trimEnd('/')
        return rewritten.takeIf { remainingAttributes.isNotEmpty() }
    }

    private fun appendAssBlock(block: String, kind: Kind, out: StringBuilder) {
        if (kind == Kind.ALL) return
        val body = block.substring(1, block.length - 1)
        val filtered = filterAssCommands(body, kind)
        if (filtered.isNotEmpty() && filtered != "\\") {
            out.append('{').append(filtered).append('}')
        }
    }

    private fun filterAssCommands(body: String, kind: Kind): String {
        val out = StringBuilder(body.length)
        var index = 0
        while (index < body.length) {
            val slash = body.indexOf('\\', index)
            if (slash < 0) {
                out.append(body.substring(index))
                break
            }
            // Preserve text preceding a command (normally empty in override blocks).
            if (slash > index) out.append(body.substring(index, slash))
            val next = body.indexOf('\\', slash + 1).let { if (it < 0) body.length else it }
            val command = body.substring(slash, next)
            if (!shouldRemoveAssCommand(command, kind)) out.append(command)
            index = next
        }
        return out.toString()
    }

    private fun shouldRemoveAssCommand(command: String, kind: Kind): Boolean {
        val token = command.drop(1).lowercase()
        return when (kind) {
            Kind.BOLD -> token.matches(Regex("b-?\\d*"))
            Kind.ITALIC -> token.matches(Regex("i-?\\d*"))
            Kind.UNDERLINE -> token.matches(Regex("u-?\\d*"))
            Kind.COLOR -> token.matches(Regex("(?:[1-4]?c|alpha|[1-4]a)(?:&h[0-9a-f]+&?)?"))
            Kind.FONT_NAME -> token.startsWith("fn")
            Kind.ALIGNMENT -> token.matches(Regex("(?:an|a)-?\\d*"))
            Kind.ALL -> true
        }
    }
}
