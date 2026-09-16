package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AiSemanticMergeProtocolTest {
    @Test
    fun readsAllFencedSubtitleBlocks() {
        val response = "合并结果：\n```text\n123 4\n```\n\n```text\n56\n78\n```"

        assertEquals("123 4\n56\n78", extractSemanticMergeResponse(response))
    }

    @Test
    fun readsAllMarkedSubtitleBlocks() {
        val response = "[[PUNCTUATED_TEXT]]123 4[[/PUNCTUATED_TEXT]]\n" +
            "[[PUNCTUATED_TEXT]]56\n78[[/PUNCTUATED_TEXT]]"

        assertEquals("123 4\n56\n78", extractSemanticMergeResponse(response))
    }

    @Test
    fun plainResponsePreservesLineBreaksAndInternalSpaces() {
        assertEquals("123 4\n56", extractSemanticMergeResponse("\n 123 4\n56\n"))
    }
}
