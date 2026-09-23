package com.subtitleedit.util

internal object Qwen3AsrTextNormalizer {
    private val languagePromptPrefix = Regex("""^language\s+[^<]*<asr_text>\s*""", RegexOption.IGNORE_CASE)
    private val asrTextMarker = Regex("\\s*<asr_text>\\s*", RegexOption.IGNORE_CASE)

    fun normalize(text: String): String {
        val withoutLanguagePrompt = languagePromptPrefix.replaceFirst(text.trim(), "")
        return asrTextMarker.replace(withoutLanguagePrompt, " ").trim()
    }
}

internal object Qwen3AsrLanguageMapper {
    private val modelLanguages = mapOf(
        "中文" to "Chinese",
        "英语" to "English",
        "日语" to "Japanese",
        "韩语" to "Korean",
        "法语" to "French",
        "德语" to "German",
        "西班牙语" to "Spanish",
        "俄语" to "Russian",
        "葡萄牙语" to "Portuguese",
        "意大利语" to "Italian",
        "土耳其语" to "Turkish"
    )

    fun toModelLanguage(language: String): String? = modelLanguages[language]
}
