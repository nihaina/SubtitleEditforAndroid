package com.subtitleedit.util.subtitle

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleColorOps
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVttPreviewWriterTest {
    @Test
    fun previewUsesColorMenuCssWithoutChangingTheDocument() {
        val original = document("<i>one</i>")
        val colored = SubtitleColorOps.apply(original, setOf(0), 0xFF123456.toInt())
        val savedHeader = colored.header
        val savedText = colored.entries.single().text

        val preview = WebVttPreviewWriter.write(colored)

        assertTrue(preview.contains("{\\1c&H563412&\\1a&H00&}{\\i1}one{\\i0}"))
        assertEquals(savedHeader, colored.header)
        assertEquals(savedText, colored.entries.single().text)
        assertEquals("<i>one</i>", original.entries.single().text)
        assertFalse(preview.contains("<c."))
        assertFalse(preview.contains("STYLE"))
    }

    @Test
    fun nestedColorsRestoreOuterThenDefaultColorAndAlpha() {
        val preview = text(document(
            "<c.outer>one <c.inner>two</c> three</c> four",
            "STYLE\n::cue(.outer) { color:rgba(255,0,0,0.5); }\n::cue(.inner) { color:#0000FF40; }"
        ))

        assertEquals(
            "{\\1c&H0000FF&\\1a&H7F&}one {\\1c&HFF0000&\\1a&HBF&}two" +
                "{\\1c&H0000FF&\\1a&H7F&} three{\\1c&HFFFFFF&\\1a&H00&} four",
            preview
        )
    }

    @Test
    fun nearWhiteAndFullyTransparentColorsAreKeptForPreview() {
        assertEquals(
            "{\\1c&HEBEBEB&\\1a&H00&}one{\\1c&HFFFFFF&\\1a&H00&}",
            text(document("<c.whiteish>one</c>", "STYLE\n::cue(.whiteish) { color:rgb(235,235,235) }"))
        )
        assertEquals(
            "{\\1c&H0000FF&\\1a&HFF&}hidden{\\1c&HFFFFFF&\\1a&H00&}",
            text(document("<c.hidden>hidden</c>", "STYLE\n::cue(.hidden) { color:rgba(255,0,0,0) }"))
        )
    }

    @Test
    fun normalizedNativeClassTagsHaveMatchingScopeInTheHtmlParser() {
        assertEquals(
            "{\\1c&H0000FF&\\1a&H00&}red{\\1c&HFFFFFF&\\1a&H00&}" +
                " gap {\\1c&HFF0000&\\1a&H00&}blue{\\1c&HFFFFFF&\\1a&H00&} tail",
            text(document("<c.red>red</c> gap <c.blue>blue</c> tail"))
        )
    }

    @Test
    fun globalStyleAndNestedNativeTagsRestoreInheritedState() {
        assertEquals(
            "{\\1c&H563412&\\1a&H00&\\b1}<" +
                "{\\i1}a{\\u1}b{\\u0}c{\\i0}>",
            text(document(
                "&lt;<i>a<u>b</u>c</i>&gt;",
                "STYLE\n::cue { color:#123456; font-weight:bold; }"
            ))
        )
        assertEquals("{\\b1}a b c{\\b0} end", text(document("<b>a <b>b</b> c</b> end")))
    }

    @Test
    fun classStylesSupportMultipleClassesRepeatedDeclarationsAndNativeColorOverride() {
        assertEquals(
            "{\\1c&H563412&\\1a&H00&\\i1\\u1}one{\\1c&HFFFFFF&\\1a&H00&\\i0\\u0}",
            text(document(
                "<c.red.emphasis>one</c>",
                "STYLE\n::cue(.red) { color:yellow; color:#123456; }\n" +
                    "::cue(.emphasis) { font-style:italic; text-decoration:underline; }"
            ))
        )
    }

    @Test
    fun ordinaryAssOverridesAndLiteralControlSequencesNeverExecute() {
        val output = text(document("{\\pos(0,0)} literal\\N &lt;i&gt; &nbsp; <br>next"))

        assertEquals("\\{\\\u2060pos(0,0)\\} literal\\\u2060N <i> \\h \\Nnext", output)
        assertFalse(output.contains("{\\pos"))
        assertFalse(output.contains("literal\\N"))
        assertFalse(output.contains("{\\i1}"))
    }

    @Test
    fun parserAlignmentPrefixWorksInsideEditorWrappersButOtherBracesAreLiteral() {
        assertEquals(
            "{\\an7}{\\1c&H0000FF&\\1a&H00&}{\\i1}one \\{\\\u2060an9\\}" +
                "{\\i0}{\\1c&HFFFFFF&\\1a&H00&}",
            text(document("<c.red><i>{\\an7}one {\\an9}</i></c>"))
        )
    }

    @Test
    fun voiceMetadataWordTimestampsEntitiesAndRubyKeepReadableText() {
        assertEquals(
            "{\\1c&H0000FF&\\1a&H00&}A\\h& B{\\1c&HFFFFFF&\\1a&H00&} / 中reading\\Nnext",
            text(document("<v Alice><c.red><00:01.234>A&nbsp;&amp; B</c></v> / <ruby>中<rt>reading</rt></ruby>\nnext"))
        )
    }

    @Test
    fun invalidColorsDoNotCrashOrLoseOtherFormattingAndOnlyStyleBlocksApply() {
        assertEquals(
            "{\\b1}one{\\b0}",
            text(document(
                "<c.bad>one</c>",
                "NOTE\n::cue(.bad) { color:red; }\n\nSTYLE\n" +
                    "::cue(.bad) {color:rgb(999999999999999999999,0,0); font-weight:bold}"
            ))
        )
    }

    @Test
    fun timestampsAndDefaultAppearanceUseMpvSubtitleConventions() {
        val preview = WebVttPreviewWriter.write(document("one").copy(entries = listOf(
            SubtitleEntry(startTime = 3_010, endTime = 3_000_020, text = "one")
        )))

        assertTrue(preview.contains("PlayResX: 384\nPlayResY: 288"))
        assertTrue(preview.contains("Style: Default,sans-serif,15.2,"))
        assertTrue(preview.contains("0,0,0,0,100,100,0,0,1,0.66,0,2,7,7,13,1"))
        assertTrue(preview.contains("Dialogue: 0,0:00:03.01,0:50:00.02,Default,,0,0,0,,one"))
    }

    private fun document(text: String, headerTail: String = ""): SubtitleDocument = SubtitleDocument(
        SubtitleFormat.VTT,
        listOf(SubtitleEntry(startTime = 1_000, endTime = 2_000, text = text)),
        header = "WEBVTT" + if (headerTail.isBlank()) "" else "\n\n$headerTail"
    )

    private fun text(document: SubtitleDocument): String = WebVttPreviewWriter.write(document)
        .lineSequence().single { it.startsWith("Dialogue: ") }.substringAfter(",Default,,0,0,0,,")
}
