package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class Qwen3AsrTextNormalizerTest {

    @Test
    fun stripsLeadingLanguagePromptAndAsrTextMarker() {
        assertEquals(
            "催眠の世界で聞く。",
            Qwen3AsrTextNormalizer.normalize("language Japanese<asr_text>催眠の世界で聞く。")
        )
    }

    @Test
    fun removesAsrMarkerWithoutRemovingOrdinaryText() {
        assertEquals("language Japanese", Qwen3AsrTextNormalizer.normalize("language Japanese"))
        assertEquals(
            "Text remains",
            Qwen3AsrTextNormalizer.normalize("Text <asr_text> remains")
        )
        assertEquals("Transcript", Qwen3AsrTextNormalizer.normalize("<asr_text>Transcript"))
    }

    @Test
    fun returnsEmptyForPromptOnlyResult() {
        assertEquals("", Qwen3AsrTextNormalizer.normalize(" language Japanese<asr_text> "))
    }

    @Test
    fun mapsSelectedLanguageToQwenLanguageAndLeavesAutoDetectionUnset() {
        assertEquals("Japanese", Qwen3AsrLanguageMapper.toModelLanguage("日语"))
        assertEquals("Chinese", Qwen3AsrLanguageMapper.toModelLanguage("中文"))
        assertEquals(null, Qwen3AsrLanguageMapper.toModelLanguage("自动检测"))
        assertEquals(null, Qwen3AsrLanguageMapper.toModelLanguage("auto"))
    }
}
