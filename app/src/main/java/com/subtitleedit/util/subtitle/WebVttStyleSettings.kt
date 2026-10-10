package com.subtitleedit.util.subtitle

import java.math.BigDecimal
import java.util.Locale

/** Editable WebVTT CSS properties exposed by Subtitle Edit's style editor. */
data class WebVttStyleSettings(
    val fontName: String = "",
    val fontSize: Double? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeout: Boolean = false,
    val useColor: Boolean = false,
    val color: Int = -1,
    val useBackgroundColor: Boolean = false,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val useShadow: Boolean = false,
    val shadowColor: Int = 0xFF000000.toInt(),
    val shadowWidth: Double = 0.0
) {
    /**
     * Only edited controls replace their CSS declarations. Opening and saving an
     * existing rule must retain relative font sizes, explicit normal values,
     * complex shadows, and properties that the editor does not expose.
     */
    fun toDeclarations(
        existing: WebVttStyleDefinition? = null,
        initial: WebVttStyleSettings = fromDefinition(existing)
    ): Map<String, String> = existing?.declarations.orEmpty().toMutableMap().apply {
        if (fontName != initial.fontName) {
            val name = fontName.trim()
            if (name.isEmpty()) remove("font-family") else put("font-family", fontFamily(name))
        }
        if (fontSize != initial.fontSize) {
            if (fontSize == null || !fontSize.isFinite() || fontSize <= 0) remove("font-size")
            else put("font-size", "${number(fontSize)}px")
        }
        updateFlag("font-weight", initial.bold, bold, "bold")
        updateFlag("font-style", initial.italic, italic, "italic")
        if (underline != initial.underline || strikeout != initial.strikeout) {
            val decorations = this["text-decoration"].orEmpty().split(Regex("\\s+"))
                .filter { it.isNotBlank() && it.lowercase(Locale.US) !in setOf("none", "underline", "line-through") }
                .toMutableList()
            if (underline) decorations += "underline"
            if (strikeout) decorations += "line-through"
            if (decorations.isEmpty()) remove("text-decoration")
            else put("text-decoration", decorations.joinToString(" "))
        }
        updateColor("color", initial.useColor, useColor, initial.color, color)
        updateColor("background-color", initial.useBackgroundColor, useBackgroundColor, initial.backgroundColor, backgroundColor)
        if (useShadow != initial.useShadow || shadowColor != initial.shadowColor || shadowWidth != initial.shadowWidth) {
            if (!useShadow || !shadowWidth.isFinite() || shadowWidth <= 0) remove("text-shadow")
            else put("text-shadow", "${hexColor(shadowColor)} ${number(shadowWidth)}px")
        }
    }

    companion object {
        private val pixelSize = Regex("^([+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*(?:px)?$", RegexOption.IGNORE_CASE)
        private val simpleShadow = Regex("^(.+?)\\s+([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))px$", RegexOption.IGNORE_CASE)

        fun fromDefinition(existing: WebVttStyleDefinition?): WebVttStyleSettings {
            if (existing == null) return WebVttStyleSettings()
            val decorations = existing.property("text-decoration").orEmpty().lowercase(Locale.US).split(Regex("\\s+"))
            val shadow = existing.property("text-shadow")?.trim()?.let(simpleShadow::matchEntire)
            val color = existing.property("color")
            val background = existing.property("background-color")
            val shadowDeclaration = existing.property("text-shadow")
            return WebVttStyleSettings(
                fontName = existing.property("font-family").orEmpty().let(::unquoteFamily),
                fontSize = existing.property("font-size")?.trim()?.let(pixelSize::matchEntire)
                    ?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 },
                bold = existing.property("font-weight")?.lowercase(Locale.US).let { it == "bold" || it == "bolder" || (it?.toIntOrNull() ?: 0) >= 600 },
                italic = existing.property("font-style")?.lowercase(Locale.US).let { it == "italic" || it?.startsWith("oblique") == true },
                underline = "underline" in decorations,
                strikeout = "line-through" in decorations,
                useColor = color != null,
                color = color?.let(WebVttTextFormatting::parseHtmlColor) ?: -1,
                useBackgroundColor = background != null,
                backgroundColor = background?.let(WebVttTextFormatting::parseHtmlColor) ?: 0xFF000000.toInt(),
                useShadow = shadowDeclaration != null && !shadowDeclaration.equals("none", true),
                shadowColor = shadow?.groupValues?.get(1)?.let(WebVttTextFormatting::parseHtmlColor) ?: 0xFF000000.toInt(),
                shadowWidth = shadow?.groupValues?.get(2)?.toDoubleOrNull() ?: 0.0
            )
        }

        private fun MutableMap<String, String>.updateFlag(key: String, before: Boolean, after: Boolean, value: String) {
            if (before == after) return
            if (after) put(key, value) else remove(key)
        }

        private fun MutableMap<String, String>.updateColor(key: String, usedBefore: Boolean, usedAfter: Boolean, before: Int, after: Int) {
            if (usedBefore == usedAfter && before == after) return
            if (usedAfter) put(key, cssColor(after)) else remove(key)
        }

        private fun cssColor(argb: Int): String {
            val red = argb ushr 16 and 255
            val green = argb ushr 8 and 255
            val blue = argb and 255
            val alpha = argb ushr 24 and 255
            return if (alpha == 255) "rgb($red,$green,$blue)" else "rgba($red,$green,$blue,${alpha / 255.0})"
        }

        private fun hexColor(argb: Int): String = "#%02x%02x%02x%02x".format(
            Locale.US, argb ushr 16 and 255, argb ushr 8 and 255, argb and 255, argb ushr 24 and 255
        )

        private fun number(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

        private fun fontFamily(value: String): String =
            if (Regex("^[\\p{L}\\p{N}_ ,.-]+$").matches(value)) value
            else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\d ").replace("\n", "\\a ") + "\""

        private fun unquoteFamily(value: String): String {
            val family = value.trim()
            if (family.length < 2 || family.first() !in "'\"" || family.last() != family.first()) return family
            val quote = family.first()
            // A list such as 'Arial', 'sans-serif' is not a single quoted name.
            if (Regex("(?<!\\\\)${Regex.escape(quote.toString())}").findAll(family).count() != 2) return family
            return family.substring(1, family.lastIndex).replace("\\$quote", quote.toString()).replace("\\\\", "\\")
        }
    }
}
