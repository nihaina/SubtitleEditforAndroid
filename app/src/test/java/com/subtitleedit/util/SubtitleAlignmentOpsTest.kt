package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAlignmentOpsTest {
    @Test
    fun pickerOrderMatchesSubtitleEditNumpadMapping() {
        assertEquals(listOf(7, 8, 9, 4, 5, 6, 1, 2, 3),
            SubtitleAlignmentOps.alignments.map { it.number })
    }

    @Test
    fun setReplacesAnTagAndKeepsOtherAssaCommands() {
        assertEquals(
            "{\\an8\\i1}Hello",
            SubtitleAlignmentOps.set("{\\an7\\i1}Hello", 8)
        )
        assertEquals(
            "{\\an9\\i1}Hello",
            SubtitleAlignmentOps.set("{\\i1}Hello", 9)
        )
    }

    @Test
    fun defaultBottomCenterRemovesAlignmentTag() {
        assertEquals("Hello", SubtitleAlignmentOps.set("{\\an7}Hello", 2))
        assertEquals("{\\an2}Hello", SubtitleAlignmentOps.set("Hello", 2, writeAn2Tag = true))
    }

    @Test
    fun settingAlignmentReplacesExplicitCoordinate() {
        assertEquals(
            "{\\an8\\i1}Hello",
            SubtitleAlignmentOps.set("{\\pos(96,216)\\an7\\i1}Hello", 8)
        )
        assertEquals("Hello", SubtitleAlignmentOps.set("{\\pos(96,216)}Hello", 2))
    }

    @Test
    fun removesStandaloneAndInlineTags() {
        val result = SubtitleAlignmentOps.remove("{\\an8}Hello \\an9")
        assertEquals("Hello ", result)
        assertTrue(!SubtitleAlignmentOps.has(result))
    }
}
