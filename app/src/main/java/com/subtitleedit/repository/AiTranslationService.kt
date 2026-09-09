package com.subtitleedit.repository

import android.content.Context
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.AiTranslationConversation
import com.subtitleedit.util.SubtitleParser

internal interface AiTranslationService {
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String>

    fun createConversation(
        context: Context,
        provider: String,
        apiKey: String,
        model: String,
        targetLanguage: String,
        customPrompt: String,
        baseUrl: String,
        contextWindowTokens: Int,
        subtitleFormat: SubtitleParser.SubtitleFormat,
        reasoningLevel: AiProviderConfig.ReasoningLevel,
        historySessionId: String,
        historyTitle: String? = null
    ): AiTranslationConversation
}
