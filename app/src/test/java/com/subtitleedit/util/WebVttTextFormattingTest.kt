package com.subtitleedit.util

import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import com.subtitleedit.util.subtitle.WebVttTextFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVttTextFormattingTest {
    @Test
    fun loadDecodesOnlyTheThreeUpstreamEntitiesInTheSameOrder() {
        assertEquals(
            "<b>A & B</b> &nbsp; &lt; &#65;",
            WebVttTextFormatting.decode("&lt;b&gt;A &amp; B&lt;/b&gt; &nbsp; &amp;lt; &#65;")
        )
    }

    @Test
    fun writingStripsAssTagsAndFoldsBlankLinesWithoutRemovingOrdinaryBraces() {
        assertEquals(
            "First\nsecond third {text}\nlast",
            WebVttTextFormatting.writeText("{\\an7}{\\i1}First\\N\n\nsecond\\hthird {text}\r\n\r\nlast")
        )
    }

    @Test
    fun writingConvertsHtmlColorsUsingSubtitleEditDefaultColorTolerance() {
        assertEquals(
            "<c.red>red</c> <c.red>close</c> <c.color123456>custom</c>",
            WebVttTextFormatting.writeText(
                "<font color=red>red</font> <font color=\"#F01010\">close</font> <font color=\"#123456\">custom</font>"
            )
        )
    }

    @Test
    fun writingAcceptsMixedCaseNamedColorsWithMatchingClosingTags() {
        val converted = WebVttTextFormatting.writeText("<font color=\"Red\">red</font> <font color=BLUE>blue</font>")
        assertEquals("<c.red>red</c> <c.blue>blue</c>", converted)
        assertEquals(
            "<font color=\"red\">red</font> <font color=\"blue\">blue</font>",
            WebVttTextFormatting.removeNativeFormatting(converted, "WEBVTT")
        )
    }

    @Test
    fun writingEscapesPlainTextAndPreservesSupportedTagsAndKnownEntities() {
        assertEquals(
            "<i>A &amp; B</i> &lt; 3 &gt; 2 <v Alice><c.red>voice</c></v> &lt;other&gt; &lrm; &amp;nbsp; &amp;#65;",
            WebVttTextFormatting.writeText("<i>A & B</i> < 3 > 2 <v Alice><c.red>voice</c></v> <other> &lrm; &nbsp; &#65;")
        )
    }

    @Test
    fun nativeConversionRetainsCssItalicBoldAndColorWithNestedClasses() {
        val header = "WEBVTT\n\nSTYLE\n::cue(.emphasis) {font-weight: bold; font-style: italic; color:#AB9216}\n" +
            "::cue(.inner) {color: rgb(255,0,255)}"
        assertEquals(
            "<font color=\"#AB9216\"><b><i>first <font color=\"#FF00FF\">second</font></i></b></font>",
            WebVttTextFormatting.removeNativeFormatting("<c.emphasis>first <c.inner>second</c></c>", header)
        )
    }

    @Test
    fun nativeConversionUsesLastClassColorAndDoesNotUseBackgroundColor() {
        val header = "WEBVTT\n\nSTYLE\n::cue(.background) {background-color:red; font-style:italic}"
        assertEquals(
            "<font color=\"magenta\">one</font> <font color=\"#008000\">two</font> <i>three</i>",
            WebVttTextFormatting.removeNativeFormatting(
                "<c.red.magenta>one</c> <c.color008000>two</c> <c.background>three</c>", header
            )
        )
    }

    @Test
    fun nativeConversionDropsOnlyGlobalNearWhiteColorAndKeepsItalic() {
        val header = "WEBVTT\n\nSTYLE\n::cue(.default) {color:rgba(235,235,235,1)}\n" +
            "::cue(.italic) {font-style:italic}"
        assertEquals(
            listOf("one", "<i>two</i>"),
            WebVttTextFormatting.removeNativeFormatting(
                listOf("<c.default>one</c>", "<c.default><c.italic>two</c></c>"), header
            )
        )
        assertEquals(
            "one",
            WebVttTextFormatting.removeNativeFormatting("<c.named>one</c>", "::cue(.named) {color:gainsboro}")
        )
    }

    @Test
    fun nativeConversionRetainsNearWhiteWhenAnotherSpeakerUsesColorAndSkipsTransparentColor() {
        val header = "::cue(.first) {color:rgba(235,235,235,1)}\n" +
            "::cue(.second) {color:rgb(255,255,0)}\n::cue(.hidden) {color:rgba(255,0,0,0)}"
        assertEquals(
            listOf("<font color=\"#EBEBEB\">one</font>", "<font color=\"#FFFF00\">two</font>", "three"),
            WebVttTextFormatting.removeNativeFormatting(
                listOf("<c.first>one</c>", "<c.second>two</c>", "<c.hidden>three</c>"), header
            )
        )
    }

    @Test
    fun nativeConversionDecodesHtmlEntitiesAndDropsVttVoiceAndWordTimestamps() {
        assertEquals(
            "{\\an7}A\u00A0& B / \u4E2Dreading",
            WebVttTextFormatting.removeNativeFormatting(
                "{\\an7} \n<v Alice><00:00:01.234>A&nbsp;&amp; B&rlm;&lrm; / &#x4E2D;<ruby><rt>reading</rt></ruby></v>", "WEBVTT"
            )
        )
    }

    @Test
    fun conversionToSrtUsesHeaderStylesAndWholeDocumentColorDecision() {
        val content = "WEBVTT\n\nSTYLE\n::cue(.em) {font-style:italic; color:rgb(235,235,235)}\n\n" +
            "00:00:01.000 --> 00:00:02.000\n<c.em>one&nbsp;two</c>\n\n" +
            "00:00:03.000 --> 00:00:04.000\n<c.red>three</c>"
        val converted = SubtitleParser.convertFormat(content, SubtitleFormat.VTT, SubtitleFormat.SRT)
        assertTrue(converted.contains("<font color=\"#EBEBEB\"><i>one\u00A0two</i></font>"))
        assertTrue(converted.contains("<font color=\"red\">three</font>"))
        assertFalse(converted.contains("<c."))
    }

    @Test
    fun convertingVttToItselfRetainsNativeClassesAndHeader() {
        val content = "WEBVTT\n\nSTYLE\n::cue(.em) {font-style:italic}\n\n" +
            "00:00:01.000 --> 00:00:02.000\n<c.em>one</c>"
        val converted = SubtitleParser.convertFormat(content, SubtitleFormat.VTT, SubtitleFormat.VTT)
        assertTrue(converted.contains("::cue(.em)"))
        assertTrue(converted.contains("<c.em>one</c>"))
    }
}
