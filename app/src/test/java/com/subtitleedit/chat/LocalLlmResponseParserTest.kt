package com.subtitleedit.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLlmResponseParserTest {
    @Test
    fun gemmaThoughtChannelIsKeptOutOfVisibleText() {
        val parsed = LocalLlmResponseParser.parse(
            "<|channel>thought\nHere's a thinking process.\n<channel|>1\n字幕1\n\n2\n字幕2"
        )

        assertEquals("1\n字幕1\n\n2\n字幕2", parsed.visible)
        assertEquals("\nHere's a thinking process.\n", parsed.reasoning)
    }

    @Test
    fun splitGemmaMarkersDoNotLeakIntoStreamingVisibleText() {
        val chunks = listOf(
            "<|channel>tho",
            "ught\nprivate reasoning<channel",
            "|>1\n字幕1"
        )
        val raw = StringBuilder()
        val visible = mutableListOf<String>()

        chunks.forEach {
            raw.append(it)
            visible += LocalLlmResponseParser.parse(raw.toString()).visible
        }

        assertEquals(listOf("", "", "1\n字幕1"), visible)
    }

    @Test
    fun gptStyleFinalChannelIsRemoved() {
        val parsed = LocalLlmResponseParser.parse(
            "<|channel|>analysis<|message|>reasoning<|end|>" +
                "<|channel|>final<|message|>1\n字幕1"
        )

        assertEquals("1\n字幕1", parsed.visible)
        assertEquals("reasoning", parsed.reasoning)
    }
}
