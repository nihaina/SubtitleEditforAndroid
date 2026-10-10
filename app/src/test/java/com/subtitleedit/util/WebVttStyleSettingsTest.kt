package com.subtitleedit.util

import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.util.subtitle.WebVttStyleDefinition
import com.subtitleedit.util.subtitle.WebVttStyleOps
import com.subtitleedit.util.subtitle.WebVttStyleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVttStyleSettingsTest {
    private fun definition(vararg declarations: Pair<String, String>): WebVttStyleDefinition =
        WebVttStyleDefinition("example", declarations.toMap(), declarations.joinToString(";") { "${it.first}:${it.second}" })

    @Test
    fun newStyleDoesNotInventOptionalProperties() {
        val settings = WebVttStyleSettings.fromDefinition(null)
        assertTrue(settings.toDeclarations().isEmpty())
        val document = SubtitleDocument(format = SubtitleParser.SubtitleFormat.VTT, entries = emptyList(), header = "WEBVTT")
        val saved = WebVttStyleOps.upsert(document, "new", settings.toDeclarations())
        assertEquals("new", WebVttStyleOps.list(saved).single().name)
        assertTrue(WebVttStyleOps.list(saved).single().declarations.isEmpty())
    }

    @Test
    fun subtitleEditStylePropertiesReadIntoControls() {
        val style = definition(
            "font-family" to "'Courier New'", "font-size" to "24.5px",
            "font-weight" to "bold", "font-style" to "italic",
            "text-decoration" to "underline line-through", "color" to "rgb(1,2,3)",
            "background-color" to "rgba(4,5,6,0.5)", "text-shadow" to "#ff3c00ff 7.5px"
        )
        val settings = WebVttStyleSettings.fromDefinition(style)
        assertEquals("Courier New", settings.fontName)
        assertEquals(24.5, settings.fontSize!!, 0.0)
        assertTrue(settings.bold && settings.italic && settings.underline && settings.strikeout)
        assertTrue(settings.useColor && settings.useBackgroundColor && settings.useShadow)
        assertEquals(0xFF010203.toInt(), settings.color)
        assertEquals(0xFFff3c00.toInt(), settings.shadowColor)
        assertEquals(7.5, settings.shadowWidth, 0.0)
        assertEquals(style.declarations, settings.toDeclarations(style))
    }

    @Test
    fun uneditedUnsupportedPropertiesRetainTheirOriginalCss() {
        val style = definition(
            "font-size" to "120%", "font-weight" to "normal", "font-style" to "normal",
            "text-shadow" to "0px 2px 4px red, 1px 1px blue", "letter-spacing" to "0.2em",
            "background-image" to "linear-gradient(red, blue)", "color" to "var(--accent)"
        )
        val initial = WebVttStyleSettings.fromDefinition(style)
        assertNull(initial.fontSize)
        assertEquals(style.declarations, initial.toDeclarations(style))
        val changed = initial.copy(italic = true).toDeclarations(style)
        assertEquals("italic", changed["font-style"])
        style.declarations.filterKeys { it != "font-style" }.forEach { (key, value) -> assertEquals(value, changed[key]) }
    }

    @Test
    fun settingAllControlsWritesNativeWebVttCss() {
        val css = WebVttStyleSettings(
            fontName = "Noto Sans", fontSize = 26.5, bold = true, italic = true,
            underline = true, strikeout = true,
            useColor = true, color = 0xFF123456.toInt(),
            useBackgroundColor = true, backgroundColor = 0x80112233.toInt(),
            useShadow = true, shadowColor = 0x80ff3c00.toInt(), shadowWidth = 7.5
        ).toDeclarations()
        assertEquals("Noto Sans", css["font-family"])
        assertEquals("26.5px", css["font-size"])
        assertEquals("bold", css["font-weight"])
        assertEquals("italic", css["font-style"])
        assertEquals("underline line-through", css["text-decoration"])
        assertEquals("rgb(18,52,86)", css["color"])
        assertEquals("rgba(17,34,51,${128 / 255.0})", css["background-color"])
        assertEquals("#ff3c0080 7.5px", css["text-shadow"])
    }

    @Test
    fun uncheckedControlsRemoveOnlyTheirProperties() {
        val style = definition(
            "font-weight" to "bold", "font-style" to "italic",
            "text-decoration" to "underline line-through overline",
            "color" to "red", "background-color" to "black", "text-shadow" to "#000000ff 2px",
            "font-size" to "20px", "font-family" to "Arial", "letter-spacing" to "2px"
        )
        val initial = WebVttStyleSettings.fromDefinition(style)
        val changed = initial.copy(
            bold = false, italic = false, underline = false, strikeout = false,
            useColor = false, useBackgroundColor = false, useShadow = false, fontSize = null, fontName = ""
        ).toDeclarations(style)
        assertEquals(mapOf("text-decoration" to "overline", "letter-spacing" to "2px"), changed)
    }

    @Test
    fun zeroFontSizeAndShadowWidthRemainUnset() {
        val css = WebVttStyleSettings(fontSize = 0.0, useShadow = true, shadowWidth = 0.0).toDeclarations()
        assertFalse(css.containsKey("font-size"))
        assertFalse(css.containsKey("text-shadow"))
    }

    @Test
    fun quotedFontNameContainingSemicolonDoesNotSplitDeclarations() {
        val source = SubtitleDocument(
            format = SubtitleParser.SubtitleFormat.VTT,
            entries = emptyList(),
            header = "WEBVTT\n\nSTYLE\n::cue(.example) { font-family:\"My; Font\"; color:red; }"
        )
        val style = WebVttStyleOps.list(source).single()
        assertEquals("\"My; Font\"", style.property("font-family"))
        val initial = WebVttStyleSettings.fromDefinition(style)
        assertEquals("My; Font", initial.fontName)
        val css = initial.copy(fontName = "New; Font").toDeclarations(style)
        val saved = WebVttStyleOps.upsert(source, style.name, css)
        assertEquals("\"New; Font\"", WebVttStyleOps.list(saved).single().property("font-family"))
        assertEquals("red", WebVttStyleOps.list(saved).single().property("color"))
    }

    @Test
    fun duplicateSubtitleEditDecorationDeclarationsExposeBothControlsAndStayRawWhenUnchanged() {
        val source = SubtitleDocument(
            format = SubtitleParser.SubtitleFormat.VTT,
            entries = emptyList(),
            header = "WEBVTT\n\nSTYLE\n" +
                "::cue(.example) { text-decoration:underline; text-decoration:line-through; color:red; }"
        )
        val style = WebVttStyleOps.list(source).single()
        assertEquals("underline line-through", style.property("text-decoration"))
        val settings = WebVttStyleSettings.fromDefinition(style)
        assertTrue(settings.underline)
        assertTrue(settings.strikeout)

        // Since no editor control changed, upsert must leave Subtitle Edit's
        // two declarations byte-for-byte intact instead of collapsing them.
        val saved = WebVttStyleOps.upsert(source, style.name, settings.toDeclarations(style))
        assertEquals(source.header, saved.header)

        // Changing only underline rewrites the exposed property while retaining
        // the rest of the style.
        val changed = settings.copy(underline = false).toDeclarations(style)
        assertEquals("line-through", changed["text-decoration"])
        assertEquals("red", changed["color"])
    }
}
