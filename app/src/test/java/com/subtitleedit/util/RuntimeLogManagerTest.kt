package com.subtitleedit.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLogManagerTest {

    @Test
    fun previewCollector_keepsTheCompleteAiResponseSection() {
        val collector = RuntimeLogManager.PreviewCollector(maxLines = 3, maxChars = 30)
        collector.add("old log line")
        collector.add("AI_TRANSLATION_RESPONSE_BEGIN")
        repeat(10) { index -> collector.add("response line $index") }
        collector.add("AI_TRANSLATION_RESPONSE_END")
        collector.add("new log line")

        val content = collector.content()
        assertTrue(content.contains("AI_TRANSLATION_RESPONSE_BEGIN"))
        assertTrue(content.contains("response line 0"))
        assertTrue(content.contains("response line 9"))
        assertTrue(content.contains("AI_TRANSLATION_RESPONSE_END"))
        assertFalse(content.contains("old log line"))
        assertTrue(collector.isTruncated)
    }
}
