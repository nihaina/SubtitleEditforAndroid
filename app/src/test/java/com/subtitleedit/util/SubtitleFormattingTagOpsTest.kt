package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleFormattingTagOpsTest {
    @Test
    fun removesAllHtmlAndAssFormattingTags() {
        val source = "<b>Hello</b> {\\i1}world{\\i0}<font color=red>! </font>"
        assertEquals("Hello world! ", SubtitleFormattingTagOps.remove(source, SubtitleFormattingTagOps.Kind.ALL))
    }

    @Test
    fun removeAllStripsKnownHtmlAndAssOverrideBlocks() {
        val source = "<b>Hi</b>{\\pos(10,20)}"
        assertEquals("Hi", SubtitleFormattingTagOps.remove(source, SubtitleFormattingTagOps.Kind.ALL))
    }

    @Test
    fun removesOnlySelectedHtmlFormatting() {
        val source = "<b><i><u>Hello</u></i></b>"
        assertEquals("<i><u>Hello</u></i>", SubtitleFormattingTagOps.remove(source, SubtitleFormattingTagOps.Kind.BOLD))
        assertEquals("<b><i>Hello</i></b>", SubtitleFormattingTagOps.remove(source, SubtitleFormattingTagOps.Kind.UNDERLINE))
    }

    @Test
    fun removesSelectedAssCommandsAndKeepsOthers() {
        val source = "{\\b1\\i1\\c&HFFFFFF&\\fnArial\\fs24\\an8\\blur1}Hello"
        assertEquals("{\\i1\\c&HFFFFFF&\\fnArial\\fs24\\an8\\blur1}Hello", SubtitleFormattingTagOps.remove(source, SubtitleFormattingTagOps.Kind.BOLD))
        assertEquals("{\\b1\\i1\\fnArial\\fs24\\an8\\blur1}Hello", SubtitleFormattingTagOps.remove(source, SubtitleFormattingTagOps.Kind.COLOR))
        assertEquals("{\\b1\\i1\\c&HFFFFFF&\\fs24\\an8\\blur1}Hello", SubtitleFormattingTagOps.remove(source, SubtitleFormattingTagOps.Kind.FONT_NAME))
    }

    @Test
    fun namedFormattingRemovalKeepsPositionTagsLikeSubtitleEdit() {
        val source = "{\\pos(10,20)}<i>Hi</i>"
        val namedKinds = listOf(
            SubtitleFormattingTagOps.Kind.ITALIC,
            SubtitleFormattingTagOps.Kind.BOLD,
            SubtitleFormattingTagOps.Kind.UNDERLINE,
            SubtitleFormattingTagOps.Kind.FONT_NAME,
            SubtitleFormattingTagOps.Kind.ALIGNMENT,
            SubtitleFormattingTagOps.Kind.COLOR
        )

        val withoutNamedFormatting = namedKinds.fold(source) { text, kind ->
            SubtitleFormattingTagOps.remove(text, kind)
        }

        assertEquals("{\\pos(10,20)}Hi", withoutNamedFormatting)
        assertEquals("Hi", SubtitleFormattingTagOps.remove(source, SubtitleFormattingTagOps.Kind.ALL))
    }

    @Test
    fun removesHtmlColorFontNameAndAlignmentIndependently() {
        assertEquals("Hi{\\an8}", SubtitleFormattingTagOps.remove("<font color=red>Hi</font>{\\an8}", SubtitleFormattingTagOps.Kind.COLOR))
        assertEquals("Hi{\\an8}", SubtitleFormattingTagOps.remove("<font face=Arial>Hi</font>{\\an8}", SubtitleFormattingTagOps.Kind.FONT_NAME))
        assertEquals(
            "<font face=Arial>Hi</font>",
            SubtitleFormattingTagOps.remove("<font color=red face=Arial>Hi</font>", SubtitleFormattingTagOps.Kind.COLOR)
        )
        assertEquals(
            "<font color=red>Hi</font>",
            SubtitleFormattingTagOps.remove("<font color=red face=Arial>Hi</font>", SubtitleFormattingTagOps.Kind.FONT_NAME)
        )
        assertEquals("<font color=red>Hi</font>", SubtitleFormattingTagOps.remove("<font color=red>Hi</font>{\\an8}", SubtitleFormattingTagOps.Kind.ALIGNMENT))
    }

    @Test
    fun malformedTagsAndPlainBracesRemainUntouched() {
        val source = "<b>open {literal} and <broken"
        assertEquals("<b>open {literal} and <broken", SubtitleFormattingTagOps.remove(source, SubtitleFormattingTagOps.Kind.COLOR))
    }
}
