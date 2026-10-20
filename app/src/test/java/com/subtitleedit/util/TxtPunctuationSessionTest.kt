package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class TxtPunctuationSessionTest {
    @Test
    fun usesTheSameChunkBoundariesAndAppendsInOrder() {
        val source = "甲".repeat(799) + "。" + "乙".repeat(800)
        val session = TxtPunctuationSession(source) { _, _ -> -1 }
        assertEquals(2, session.totalCount)
        assertEquals(source.take(800), session.nextText)
        session.appendPrediction("甲".repeat(799) + "！")
        assertEquals(source.drop(800), session.nextText)
        session.appendPrediction("乙".repeat(800) + "！")
        assertEquals("甲".repeat(799) + "！" + "乙".repeat(800) + "！", session.predictedText)
    }

    @Test
    fun blankPredictionDoesNotAdvance() {
        val session = TxtPunctuationSession("原文", wordStart = { _, _ -> -1 })
        assertThrows(IOException::class.java) { session.appendPrediction(" ") }
        assertEquals(0, session.completedCount)
        assertEquals("原文", session.nextText)
    }

    @Test
    fun preservesSourceSpacingAndDoesNotInsertEnglishWordSeparators() {
        val source = "a".repeat(801)
        val session = TxtPunctuationSession(source) { _, _ -> -1 }
        session.appendPrediction("a".repeat(800) + ".")
        session.appendPrediction("a")
        assertEquals("a".repeat(800) + ".a", session.predictedText)
    }

    @Test
    fun rejectsChangedTextOrInternalLineBreaksWithoutAdvancing() {
        val source = "第一行\r\n\r\n第二行"
        val session = TxtPunctuationSession(source) { _, _ -> -1 }
        assertThrows(IOException::class.java) { session.appendPrediction("第一行。\n\r\n第二行") }
        assertEquals(0, session.completedCount)
        assertThrows(IOException::class.java) { session.appendPrediction("另一个句子。") }
        assertEquals(0, session.completedCount)
        assertEquals(source, session.nextText)
    }

    @Test
    fun keepsIndentationBlankLinesAndOuterLineEndings() {
        val source = "\r\n  第一行  \r\n\r\n  第二行  \r\n\n"
        val session = TxtPunctuationSession(source) { _, _ -> -1 }
        session.appendPrediction("  第一行。  \r\n\r\n  第二行。  ")
        assertEquals("\r\n  第一行。  \r\n\r\n  第二行。  \r\n\n", session.predictedText)
    }
}
