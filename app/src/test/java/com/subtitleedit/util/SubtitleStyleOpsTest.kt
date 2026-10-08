package com.subtitleedit.util

import com.subtitleedit.util.SubtitleStyleOps.Style
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleStyleOpsTest {
    @Test
    fun italicCanBeAppliedAndRemovedWithoutChangingText() {
        val original = listOf("Hello", "World")

        val styled = SubtitleStyleOps.toggle(original, Style.ITALIC)

        assertEquals(listOf("<i>Hello</i>", "<i>World</i>"), styled)
        assertEquals(original, SubtitleStyleOps.toggle(styled, Style.ITALIC))
    }

    @Test
    fun boldCanBeAppliedAndRemovedWithoutChangingText() {
        val original = listOf("Hello", "World")

        val styled = SubtitleStyleOps.toggle(original, Style.BOLD)

        assertEquals(listOf("<b>Hello</b>", "<b>World</b>"), styled)
        assertEquals(original, SubtitleStyleOps.toggle(styled, Style.BOLD))
    }

    @Test
    fun styledFirstCueRemovesStyleFromTheWholeMixedSelection() {
        assertEquals(
            listOf("First", "Second", "Third"),
            SubtitleStyleOps.toggle(listOf("<i>First</i>", "Second", "<I>Third</I>"), Style.ITALIC)
        )
        assertEquals(
            listOf("First", "Second", "Third"),
            SubtitleStyleOps.toggle(listOf("<b>First</b>", "Second", "<B>Third</B>"), Style.BOLD)
        )
    }

    @Test
    fun unstyledFirstCueAppliesStyleToTheWholeMixedSelectionWithoutDuplicates() {
        assertEquals(
            listOf("<i>First</i>", "<i>Second</i>", "<i>Third</i>"),
            SubtitleStyleOps.toggle(listOf("First", "<i>Second</i>", "<I>Third</I>"), Style.ITALIC)
        )
        assertEquals(
            listOf("<b>First</b>", "<b>Second</b>"),
            SubtitleStyleOps.toggle(listOf("First", "<b>Second</b>"), Style.BOLD)
        )
    }

    @Test
    fun partiallyStyledFirstCueTriggersRemoval() {
        assertEquals(
            listOf("Hello world and again"),
            SubtitleStyleOps.toggle(listOf("Hello <i>world</i> and <I>again</I>"), Style.ITALIC)
        )
        assertEquals(
            listOf("Hello world and again"),
            SubtitleStyleOps.toggle(listOf("Hello <b>world</b> and <B>again</B>"), Style.BOLD)
        )
    }

    @Test
    fun uppercaseFirstCueIsNormalizedToLowercaseInsteadOfRemoved() {
        assertEquals(
            listOf("<i>Hello</i>"),
            SubtitleStyleOps.toggle(listOf("<I>Hello</I>"), Style.ITALIC)
        )
        assertEquals(
            listOf("<b>Hello</b>"),
            SubtitleStyleOps.toggle(listOf("<B>Hello</B>"), Style.BOLD)
        )
    }

    @Test
    fun multilineCueIsWrappedAsOneBlockWithoutTrimming() {
        assertEquals(
            listOf("<i>  First\n\nSecond  </i>"),
            SubtitleStyleOps.toggle(listOf("  First\n\nSecond  "), Style.ITALIC)
        )
        assertEquals(
            listOf("<b> First\r\nSecond </b>"),
            SubtitleStyleOps.toggle(listOf(" First\r\nSecond "), Style.BOLD)
        )
    }

    @Test
    fun emptyCuesStayEmptyAndWhitespaceIsPreserved() {
        assertEquals(
            listOf("", "<i> </i>", "<i>Text</i>", ""),
            SubtitleStyleOps.toggle(listOf("", " ", "Text", "<i></i>"), Style.ITALIC)
        )
        assertEquals(
            listOf("", "", ""),
            SubtitleStyleOps.toggle(listOf("<b></b>", "", "<B></B>"), Style.BOLD)
        )
        assertEquals(emptyList<String>(), SubtitleStyleOps.toggle(emptyList(), Style.ITALIC))
        assertEquals(emptyList<String>(), SubtitleStyleOps.toggle(emptyList(), Style.BOLD))
    }

    @Test
    fun otherFormattingAndPositionTagsAreRetained() {
        val source = "{\\an8}<font color=red><b><u>Hello</u></b></font>{\\pos(10,20)}"

        val styled = SubtitleStyleOps.toggle(listOf(source), Style.ITALIC)

        assertEquals(listOf("<i>$source</i>"), styled)
        assertEquals(listOf(source), SubtitleStyleOps.toggle(styled, Style.ITALIC))
    }

    @Test
    fun attributeOpeningsAndAlternativeStyleTagsAreLeftIntact() {
        assertEquals(
            listOf("<i><i class=accent><em>Hello</em></i>"),
            SubtitleStyleOps.toggle(listOf("<i class=accent><em>Hello</em></i>"), Style.ITALIC)
        )
        assertEquals(
            listOf("<b><strong>Hello</strong></b>"),
            SubtitleStyleOps.toggle(listOf("<strong>Hello</strong>"), Style.BOLD)
        )
    }

    @Test
    fun italicAndBoldSurviveSrtSaveAndReload() {
        val source = "1\n00:00:01,000 --> 00:00:02,000\nHello\nWorld\n\n"
        val document = SubtitleParser.parseDocument(source, format = SubtitleParser.SubtitleFormat.SRT)
        val styled = SubtitleStyleOps.toggle(
            SubtitleStyleOps.toggle(document.entries.map { it.text }, Style.ITALIC), Style.BOLD
        )
        val updated = document.copy(entries = document.entries.zip(styled) { entry, text -> entry.copy(text = text) })

        val reloaded = SubtitleParser.parseDocument(SubtitleParser.serialize(updated), format = document.format)

        assertEquals("<b><i>Hello\nWorld</i></b>", reloaded.entries.single().text)
        assertEquals(document.entries.single().startTime, reloaded.entries.single().startTime)
        assertEquals(document.entries.single().endTime, reloaded.entries.single().endTime)
    }

    @Test
    fun italicAndBoldSurviveVttSaveAndReloadWithoutChangingCueMetadata() {
        val source = "WEBVTT\n\ncue-1\n00:01.000 --> 00:02.000 align:start\n<c.red>Hello</c>\n\n"
        val document = SubtitleParser.parseDocument(source, format = SubtitleParser.SubtitleFormat.VTT)
        val styled = SubtitleStyleOps.toggle(
            SubtitleStyleOps.toggle(document.entries.map { it.text }, Style.BOLD), Style.ITALIC
        )
        val updated = document.copy(entries = document.entries.zip(styled) { entry, text -> entry.copy(text = text) })

        val reloaded = SubtitleParser.parseDocument(SubtitleParser.serialize(updated), format = document.format)

        assertEquals("<i><b><c.red>Hello</c></b></i>", reloaded.entries.single().text)
        assertEquals("cue-1", reloaded.entries.single().cueIdentifier)
        assertEquals("align:start", reloaded.entries.single().cueSettings)
        assertEquals(document.entries.single().startTime, reloaded.entries.single().startTime)
        assertEquals(document.entries.single().endTime, reloaded.entries.single().endTime)
    }
}
