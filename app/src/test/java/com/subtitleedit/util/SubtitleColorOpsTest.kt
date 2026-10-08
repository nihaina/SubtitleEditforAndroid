package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.subtitle.SubtitleDocument
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleColorOpsTest {
    @Test
    fun parsesAndFormatsArgbValues() {
        assertEquals(0xFFFF0080.toInt(), SubtitleColorOps.parseArgb("#FF0080"))
        assertEquals(0x8044CC11.toInt(), SubtitleColorOps.parseArgb("8044CC11"))
        assertEquals("8044CC11", SubtitleColorOps.toArgbHex(0x8044CC11.toInt()))
        assertEquals("#44CC11", SubtitleColorOps.toRgbHex(0x8044CC11.toInt()))
        assertEquals(null, SubtitleColorOps.parseArgb("#12345"))
        assertEquals(null, SubtitleColorOps.parseArgb("GG44CC11"))
        assertEquals(0, SubtitleColorOps.parseArgb("00000000"))
    }

    @Test
    fun htmlColorPreservesFontFaceAssPrefixAndEmptyCues() {
        val document = SubtitleDocument(
            SubtitleFormat.SRT,
            listOf(
                SubtitleEntry(text = "{\\pos(10,20)}<font face=Arial>Hello</font>"),
                SubtitleEntry(text = "  "),
                SubtitleEntry(text = "<b>World</b>")
            )
        )
        val result = SubtitleColorOps.apply(document, setOf(0, 1, 2), 0xFF00AA11.toInt())
        assertEquals(
            "{\\pos(10,20)}<font face=Arial color=\"#00AA11\">Hello</font>",
            result.entries[0].text
        )
        assertEquals("  ", result.entries[1].text)
        assertEquals("<font color=\"#00AA11\"><b>World</b></font>", result.entries[2].text)
    }

    @Test
    fun htmlBatchOnlyChangesSelectedPositions() {
        val document = SubtitleDocument(SubtitleFormat.LRC, listOf(SubtitleEntry(text = "One"), SubtitleEntry(text = "Two")))
        val result = SubtitleColorOps.apply(document, setOf(1), 0xFFFF0000.toInt())
        assertEquals("One", result.entries[0].text)
        assertTrue(result.entries[1].text.contains("#FF0000"))
    }

    @Test
    fun htmlReplacesColorAndKeepsOtherFontAttributesWithoutTrimmingText() {
        val document = SubtitleDocument(
            SubtitleFormat.SRT,
            listOf(SubtitleEntry(text = "<font color=red face=Arial>  Hi\nthere  </font>"))
        )
        val result = SubtitleColorOps.apply(document, setOf(0), 0x80FFAA00.toInt())
        assertEquals(
            "<font face=Arial color=\"#FFAA00\">  Hi\nthere  </font>",
            result.entries.single().text
        )
        assertEquals(result, SubtitleColorOps.apply(result, setOf(0), 0x80FFAA00.toInt()))
    }

    @Test
    fun htmlColorPreservesNonColorSpanCss() {
        val document = SubtitleDocument(
            SubtitleFormat.SRT,
            listOf(SubtitleEntry(text = "<span style='color:red;font-weight:bold;background-color:black'>Hi</span>"))
        )
        val result = SubtitleColorOps.apply(document, setOf(0), 0xFF00AA11.toInt())
        assertEquals(
            "<font color=\"#00AA11\"><span style='font-weight:bold;background-color:black'>Hi</span></font>",
            result.entries.single().text
        )
    }

    @Test
    fun webVttAddsAlphaRuleAndPreservesOtherHeaderAndStyle() {
        val header = "WEBVTT\nNOTE keep this\nSTYLE\n::cue(.bold) { font-weight:bold; }"
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(text = "<c.bold>hello</c>"), SubtitleEntry(text = "other")),
            header = header,
            footer = "NOTE footer"
        )
        val result = SubtitleColorOps.apply(document, setOf(0), 0x8044CC11.toInt())
        assertEquals("NOTE footer", result.footer)
        assertTrue(result.header.contains("NOTE keep this"))
        assertTrue(result.header.contains("rgba(68,204,17,"))
        assertEquals("<c.bold.44cc1180>hello</c>", result.entries[0].text)
        assertEquals("other", result.entries[1].text)
    }

    @Test
    fun webVttRemovesOldColorOnlyAndDefaultClassButKeepsMixedStyle() {
        val header = "WEBVTT\nSTYLE\n" +
            "::cue(.old) { color:rgb(255,0,0); }\n" +
            "::cue(.mixed) { color:rgb(0,0,0); font-weight:bold; }"
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(text = "<c.old.mixed.red>text</c>")),
            header = header
        )
        val result = SubtitleColorOps.apply(document, setOf(0), 0xFF0000FF.toInt())
        assertEquals("<c.mixed.0000ffff>text</c>", result.entries.single().text)
        assertNotNull(result.header)
    }

    @Test
    fun webVttUnwrapsAColorOnlyWrapperBeforeApplyingReplacement() {
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(text = "<c.old>text\nnext</c>")),
            header = "WEBVTT\n\nSTYLE\n::cue(.old) { color:red; }"
        )
        val result = SubtitleColorOps.apply(document, setOf(0), 0xFF0000FF.toInt())
        assertEquals("<c.0000ffff>text\nnext</c>", result.entries.single().text)
        assertTrue(result.header.startsWith(document.header))
    }

    @Test
    fun webVttRoundTripKeepsStylesCueMetadataAndAlpha() {
        val original = SubtitleParser.parseDocument(
            "WEBVTT\n\nSTYLE\n::cue(.loud) { font-weight:bold; }\n\n" +
                "cue-id\n00:00:01.000 --> 00:00:02.000 line:85%\n<c.loud>Hello</c>\n\nNOTE footer\nKeep me\n",
            "sample.vtt"
        )
        val result = SubtitleColorOps.apply(original, setOf(0), 0x40CC2299)
        val reopened = SubtitleParser.parseDocument(SubtitleParser.serialize(result), "saved.vtt")
        assertEquals(result.header, reopened.header)
        assertEquals(result.entries.single().text, reopened.entries.single().text)
        assertEquals("cue-id", reopened.entries.single().cueIdentifier)
        assertEquals("line:85%", reopened.entries.single().cueSettings)
        assertEquals(original.footer, reopened.footer)
        assertEquals(original.entries.single().stableId, result.entries.single().stableId)
    }

    @Test
    fun webVttRepeatedApplyReusesStyleAndDoesNotNestTags() {
        val document = SubtitleDocument(SubtitleFormat.VTT, listOf(SubtitleEntry(text = "text")), header = "WEBVTT")
        val first = SubtitleColorOps.apply(document, setOf(0), 0xFFFF0000.toInt())
        val second = SubtitleColorOps.apply(first, setOf(0), 0xFFFF0000.toInt())
        assertEquals(first, second)
        assertEquals(1, Regex("::cue").findAll(second.header).count())
        assertEquals("<c.ff0000ff>text</c>", second.entries.single().text)
    }

    @Test
    fun webVttReusesExistingCustomColorClassAndPreservesUnusedStyles() {
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(text = "<c.loud>text</c>")),
            header = "WEBVTT\n\nSTYLE\n::cue(.sea) { color:rgb(0,255,255); }\n::cue(.loud) { font-weight:bold; }"
        )
        val result = SubtitleColorOps.apply(document, setOf(0), 0xFF00FFFF.toInt())
        assertEquals(document.header, result.header)
        assertEquals("<c.loud.sea>text</c>", result.entries.single().text)
    }

    @Test
    fun webVttGeneratedNameCollisionGetsANewNameWithoutChangingExistingRule() {
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(text = "<c.ff0000ff>text</c>")),
            header = "WEBVTT\n\nSTYLE\n::cue(.ff0000ff) { font-weight:bold; }"
        )
        val result = SubtitleColorOps.apply(document, setOf(0), 0xFFFF0000.toInt())
        assertTrue(result.header.startsWith(document.header))
        assertTrue(result.header.contains("::cue(.ff0000ff-2) { color:rgb(255,0,0); }"))
        assertEquals("<c.ff0000ff.ff0000ff-2>text</c>", result.entries.single().text)
        assertEquals(result, SubtitleColorOps.apply(result, setOf(0), 0xFFFF0000.toInt()))
    }

    @Test
    fun webVttClassNamesAreCaseSensitiveAndCompoundSelectorsArePreserved() {
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(text = "<c.RED.red.one.two>text</c>")),
            header = "WEBVTT\n\nSTYLE\n::cue(.RED) { font-weight:bold; }\n" +
                "::cue(.one.two) { color:red; font-weight:bold; }"
        )
        val result = SubtitleColorOps.apply(document, setOf(0), 0xFF0000FF.toInt())
        assertEquals("<c.RED.one.two.0000ffff>text</c>", result.entries.single().text)
    }

    @Test
    fun webVttBlankOrInvalidTargetsDoNotAddAStyle() {
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(text = "  "), SubtitleEntry(text = "text")),
            header = "WEBVTT"
        )
        assertSame(document, SubtitleColorOps.apply(document, setOf(0, 9), 0xFFFF0000.toInt()))
    }

    @Test
    fun webVttTransparentColorDoesNotReuseAnOpaqueHexRule() {
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(text = "text")),
            header = "WEBVTT\n\nSTYLE\n::cue(.opaque) { color:#FF0000; }"
        )
        val result = SubtitleColorOps.apply(document, setOf(0), 0x80FF0000.toInt())
        assertEquals("<c.ff000080>text</c>", result.entries.single().text)
        assertTrue(result.header.contains("rgba(255,0,0,"))
    }

    @Test
    fun webVttReusesEquivalentCssAlphaAndHexColorValues() {
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(text = "text")),
            header = "WEBVTT\n\nSTYLE\n" +
                "::cue(.alpha) { color: rgba(255, 0, 0, 0.5); }\n" +
                "::cue(.hex) { color: #FF000080; }"
        )
        val alphaResult = SubtitleColorOps.apply(document, setOf(0), 0x80FF0000.toInt())
        assertEquals("<c.alpha>text</c>", alphaResult.entries.single().text)
        assertEquals(document.header, alphaResult.header)

        val hexDocument = document.copy(header = "WEBVTT\n\nSTYLE\n::cue(.hex) { color: #FF000080; }")
        val hexResult = SubtitleColorOps.apply(hexDocument, setOf(0), 0x80FF0000.toInt())
        assertEquals("<c.hex>text</c>", hexResult.entries.single().text)
        assertEquals(hexDocument.header, hexResult.header)
    }

    @Test
    fun webVttDefaultColorClassesAreRemovedWhenReplacingColor() {
        val document = SubtitleDocument(
            SubtitleFormat.VTT,
            listOf(SubtitleEntry(text = "<c.green.bg_red.bold>text</c>")),
            header = "WEBVTT\n\nSTYLE\n::cue(.bold) { font-weight:bold; }"
        )
        val result = SubtitleColorOps.apply(document, setOf(0), 0xFF0000FF.toInt())
        assertEquals("<c.bold.0000ffff>text</c>", result.entries.single().text)
    }

    @Test
    fun syncVttHeaderPreservesRawMetadataBomCrLfAndCueIdentifier() {
        val source = "\uFEFFWEBVTT\r\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000\r\n\r\n" +
            "NOTE untouched\r\nKeep me\r\n\r\ncue-id\r\n00:00:01.000 --> 00:00:02.000\r\nHello\r\n"
        val oldHeader = "WEBVTT\n\nNOTE untouched\nKeep me"
        val newHeader = oldHeader + "\n\nSTYLE\n::cue(.ff0000ff) { color:rgb(255,0,0); }"
        val result = SubtitleColorOps.syncVttHeader(source, oldHeader, newHeader)
        assertTrue(result.startsWith(source.substringBefore("cue-id")))
        assertTrue(result.contains("STYLE\r\n::cue(.ff0000ff) { color:rgb(255,0,0); }\r\n\r\ncue-id\r\n"))
        assertTrue(result.endsWith(source.substringAfter("cue-id")))
        assertEquals(source, SubtitleColorOps.syncVttHeader(source, oldHeader, oldHeader))
    }

    @Test
    fun syncVttHeaderSupportsHeaderlessContentWithoutAddingASignature() {
        val source = "id\n00:00:01.000 --> 00:00:02.000\nHello\n"
        val result = SubtitleColorOps.syncVttHeader(source, "", "WEBVTT\n\nSTYLE\n::cue(.red) { color:red; }")
        assertEquals("STYLE\n::cue(.red) { color:red; }\n\nid\n00:00:01.000 --> 00:00:02.000\nHello\n", result)
    }

    @Test
    fun syncVttHeaderInsertsBeforeTheWholeCueBlockWithoutBlankSeparator() {
        val source = "WEBVTT\n\nid\n00:00:01.000 --> 00:00:02.000\nHello\n"
        val result = SubtitleColorOps.syncVttHeader(
            source,
            "WEBVTT",
            "WEBVTT\n\nSTYLE\n::cue(.red) { color:red; }"
        )
        assertTrue(result.contains("STYLE\n::cue(.red) { color:red; }\n\nid\n00:00:01.000"))
    }

    @Test
    fun syncVttHeaderSkipsMetadataTimelinesAndAcceptsShortTimestamps() {
        val source = "WEBVTT\n\nNOTE example\n00:00:01.000 --> 00:00:02.000\nComment\n\n" +
            "STYLE\n::cue(.bold) { font-weight:bold; }\n\n" +
            "REGION\nid:region\n\ncue-id\n0:1.000 --> 0:2.000\nHello\n"
        val oldHeader = SubtitleParser.parseDocument(source, "test.vtt").header
        val addition = "STYLE\n::cue(.ff0000ff) { color:rgb(255,0,0); }"

        val result = SubtitleColorOps.syncVttHeader(source, oldHeader, oldHeader + "\n\n" + addition)

        assertEquals(source.replace("cue-id\n", addition + "\n\ncue-id\n"), result)
        assertEquals(1, SubtitleParser.parseDocument(result, "test.vtt").entries.size)
    }
}
