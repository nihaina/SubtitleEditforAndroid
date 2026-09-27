package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptWindowAllocatorTest {
    @Test fun preservesOriginalTranscriptInOrder() {
        val source = "你好，世界！ Hello world. 再见。"
        val parts = TranscriptWindowAllocator.assign(source, listOf(10L, 10L, 5L))
        assertEquals(listOf("你好，世界！", "Hello world.", "再见。"), parts)
    }

    @Test fun givesMoreTextToLongerSpeech() {
        val parts = TranscriptWindowAllocator.assign("一二三四五六七八九十", listOf(1L, 3L))
        assertEquals("一二三", parts[0])
        assertEquals("四五六七八九十", parts[1])
    }

    @Test fun rejectsMoreWindowsThanCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptWindowAllocator.assign("你好", listOf(1L, 1L, 1L))
        }
    }
}
