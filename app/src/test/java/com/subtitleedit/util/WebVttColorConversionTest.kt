package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.util.subtitle.WebVttTextFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVttColorConversionTest {
    private fun convertSrt(text: String): String = SubtitleParser.convertFormat(
        "1\n00:00:01,000 --> 00:00:02,000\n$text\n", SubtitleFormat.SRT, SubtitleFormat.VTT
    )

    @Test
    fun srtConversionDeclaresEveryCustomColorAndPreservesTheOriginalTextFragments() {
        val converted = convertSrt(
            "<i><font color=\"#123456\">First</font></i> plain <b><font color=\"#654321\">Second</font></b>"
        )
        assertTrue(converted.contains("::cue(.123456ff) { color:rgb(18,52,86); }"))
        assertTrue(converted.contains("::cue(.654321ff) { color:rgb(101,67,33); }"))
        val cue = SubtitleParser.parseVTT(converted).single()
        assertEquals("<i><c.123456ff>First</c></i> plain <b><c.654321ff>Second</c></b>", cue.text)
        assertEquals(1000L, cue.startTime)
        assertEquals(2000L, cue.endTime)
        assertFalse(converted.contains("<font"))
    }

    @Test
    fun conversionSupportsMixedCaseNamedColorsAndShortHexWithoutApproximatingTheirRgbValues() {
        val converted = convertSrt(
            "<font color=LightSeaGreen>Sea</font> <font color='green'>Green</font> <font color='#AbC'>Short</font>"
        )
        assertTrue(converted.contains("::cue(.20b2aaff) { color:rgb(32,178,170); }"))
        assertTrue(converted.contains("::cue(.008000ff) { color:rgb(0,128,0); }"))
        assertTrue(converted.contains("::cue(.aabbccff) { color:rgb(170,187,204); }"))
        assertEquals(
            "<c.20b2aaff>Sea</c> <c.008000ff>Green</c> <c.aabbccff>Short</c>",
            SubtitleParser.parseVTT(converted).single().text
        )
    }

    @Test
    fun nestedAndMultiAttributeFontTagsKeepBalancedPerFragmentColorTags() {
        val converted = convertSrt(
            "<font face='Arial' color='#123456'>Outer <b><font color='#654321'>Inner</font></b> Outer</font>"
        )
        assertEquals(
            "<c.123456ff>Outer <b><c.654321ff>Inner</c></b> Outer</c>",
            SubtitleParser.parseVTT(converted).single().text
        )
        assertFalse(converted.contains("</font>"))
    }

    @Test
    fun writerReusesAnEquivalentColorOnlyStyleAndPreservesExistingStylesAndClasses() {
        val header = "WEBVTT\n\nSTYLE\n::cue(.sea) { color:LightSeaGreen; }\n::cue(.bold) { font-weight:bold; }"
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(startTime = 1000, endTime = 2000, text = "<c.bold><font color='#20B2AA'>Sea</font></c>")),
            header
        )
        val prepared = WebVttTextFormatting.prepareDocument(document)
        assertEquals(header, prepared.header)
        assertEquals("<c.bold><c.sea>Sea</c></c>", prepared.entries.single().text)
        assertEquals("<c.bold><font color='#20B2AA'>Sea</font></c>", document.entries.single().text)
        val written = SubtitleParser.serialize(document)
        assertEquals(2, Regex("::cue").findAll(written).count())
        assertEquals("<c.bold><c.sea>Sea</c></c>", SubtitleParser.parseVTT(written).single().text)
    }

    @Test
    fun writerDoesNotReplaceMixedStyleRulesOrOccupiedClassNames() {
        val header = "WEBVTT\n\nSTYLE\n::cue(.123456ff) { color:#123456; font-weight:bold; }"
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(startTime = 1000, endTime = 2000, text = "<c.123456ff>Keep</c> <font color='#123456'>New</font>")),
            header
        )
        val prepared = WebVttTextFormatting.prepareDocument(document)
        assertTrue(prepared.header.startsWith(header))
        assertTrue(prepared.header.contains("::cue(.123456ff-2) { color:rgb(18,52,86); }"))
        assertEquals("<c.123456ff>Keep</c> <c.123456ff-2>New</c>", prepared.entries.single().text)
    }

    @Test
    fun writerDoesNotReuseColorClassesAlsoReferencedByGroupedOrCompoundSelectors() {
        listOf(
            "::cue(.reuse), ::cue(.other) { font-weight:bold; }",
            "::cue(.reuse.other) { font-style:italic; }"
        ).forEach { extraRule ->
            val header = "WEBVTT\n\nSTYLE\n::cue(.reuse) { color:#123456; }\n$extraRule"
            val document = SubtitleDocument(
                SubtitleFormat.VTT,
                listOf(SubtitleEntry(startTime = 1000, endTime = 2000, text = "<font color='#123456'>Plain</font>")),
                header
            )
            val prepared = WebVttTextFormatting.prepareDocument(document)

            assertTrue(prepared.header.startsWith(header))
            assertTrue(prepared.header.contains("::cue(.123456ff) { color:rgb(18,52,86); }"))
            assertEquals("<c.123456ff>Plain</c>", prepared.entries.single().text)
            assertFalse(SubtitleParser.serialize(document).contains("<c.reuse>"))
        }
    }

    @Test
    fun colorsSharedBySeveralCuesCreateOneRuleAndRepeatedConversionIsStable() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n<font color='#123456'>One</font>\n\n" +
            "2\n00:00:03,000 --> 00:00:04,000\n<font color='#123456'>Two</font>"
        val converted = SubtitleParser.convertFormat(srt, SubtitleFormat.SRT, SubtitleFormat.VTT)
        assertEquals(1, Regex("::cue").findAll(converted).count())
        assertEquals(converted, SubtitleParser.convertFormat(srt, SubtitleFormat.SRT, SubtitleFormat.VTT))
        assertEquals(converted, SubtitleParser.convertFormat(converted, SubtitleFormat.VTT, SubtitleFormat.VTT))
        val reloaded = SubtitleParser.parseDocument(converted, format = SubtitleFormat.VTT)
        assertEquals(reloaded, WebVttTextFormatting.prepareDocument(reloaded))
    }

    @Test
    fun cssRgbaAlphaUsesTheSubtitleEditClassAndStyleRepresentation() {
        val converted = convertSrt("<font color='#12345680'>Alpha</font>")
        assertTrue(converted.contains("::cue(.12345680) { color:rgba(18,52,86,${128 / 255.0}); }"))
        assertEquals("<c.12345680>Alpha</c>", SubtitleParser.parseVTT(converted).single().text)
    }

    @Test
    fun textOnlyWritingPreservesUnconvertedFontTagsAndTheirMatchingClosingTags() {
        assertEquals(
            "<font face='Arial'>One</font> <font color='unknown-color'>Two</font>",
            WebVttTextFormatting.writeText("<font face='Arial'>One</font> <font color='unknown-color'>Two</font>")
        )
    }
}
