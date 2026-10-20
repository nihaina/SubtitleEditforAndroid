package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TxtTranslationPromptTest {
    @Test
    fun reusesOneClickInstructionAndCustomPromptWithPlainText() {
        val text = "第一行。\n\n第二行。"
        assertEquals(
            "帮我翻译成英语，以原格式输出\n保留人名\n\n$text",
            buildTextTranslationUserContent(text, "英语", " 保留人名 ")
        )
    }

    @Test
    fun indexTranslate2bUsesExistingShortInstruction() {
        assertEquals(
            "帮我翻译成中文\n\nHello world.",
            buildTextTranslationUserContent("Hello world.", "中文", indexTranslate = true)
        )
    }
}
