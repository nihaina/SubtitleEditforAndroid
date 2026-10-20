package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class TxtTranslationSessionTest {
    @Test
    fun predictedLinesKeepOriginalBlankLinesAndIndentation() {
        val source = "  第一行  \r\n\r\n \t\n！！\r第二行\n"
        assertEquals(
            "  第一行。  \r\n\r\n \t\n！！\r第二行！\n",
            restoreTxtLineLayout(source, mapOf(1 to "第一行。", 3 to "第二行！"))
        )
    }

    @Test
    fun preservesLeadingAndTrailingBlankLinesAfterResponseExtraction() {
        val session = TxtTranslationSession("\r\n\r\n  原文\r\n\r\n", wordStart = { _, _ -> -1 })
        session.appendTranslation("译文")
        assertEquals("\r\n\r\n  译文\r\n\r\n", session.translatedText)
    }

    @Test
    fun resumesAtNextChunkAndKeepsCompletedTranslation() {
        val session = TxtTranslationSession("甲".repeat(800) + "乙", wordStart = { _, _ -> -1 })
        assertEquals(2, session.totalCount)
        session.appendTranslation("first")
        assertEquals(1, session.completedCount)
        assertEquals("乙", session.nextText)
        session.appendTranslation("second")
        assertEquals(2, session.completedCount)
        assertEquals("first second", session.translatedText)
    }

    @Test
    fun emptyTranslationDoesNotAdvanceProgress() {
        val session = TxtTranslationSession("原文", wordStart = { _, _ -> -1 })
        assertThrows(IOException::class.java) { session.appendTranslation(" ") }
        assertEquals(0, session.completedCount)
        assertEquals("原文", session.nextText)
        session.appendTranslation("译文")
        assertEquals("译文", session.translatedText)
    }

    @Test
    fun consecutiveChunksDoNotRepeatOrReplacePreviouslyTranslatedText() {
        val source = "甲".repeat(770) + "。" + "乙".repeat(30) + "。" + "丙".repeat(100)
        val session = TxtTranslationSession(source, wordStart = { _, _ -> -1 })
        session.appendTranslation("第一段。")
        assertEquals("丙".repeat(100), session.nextText)
        session.appendTranslation("第二段。")
        assertEquals(2, session.completedCount)
        assertEquals("第一段。第二段。", session.translatedText)
    }
}
