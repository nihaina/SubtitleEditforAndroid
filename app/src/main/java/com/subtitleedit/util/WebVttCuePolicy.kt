package com.subtitleedit.util

internal object WebVttCuePolicy {
    fun validate(identifier: String, settings: String): String? {
        if (identifier.contains('\n') || identifier.contains("-->")) {
            return "Cue identifier 不能包含换行或 -->"
        }
        if (settings.contains('\n') || settings.contains("-->")) {
            return "Cue settings 不能包含换行或 -->"
        }
        val allowedKeys = setOf("vertical", "line", "position", "size", "align", "region")
        val invalid = settings.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .firstOrNull { token ->
                val separator = token.indexOf(':')
                separator <= 0 || token.substring(0, separator).lowercase() !in allowedKeys
            }
        return invalid?.let { "无法识别的 Cue setting：$it" }
    }
}
