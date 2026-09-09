package com.subtitleedit.repository

import android.content.Context
import com.subtitleedit.util.AiModelClient
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.AiTranslationConversation
import com.subtitleedit.util.SubtitleParser

internal class DefaultAiTranslationService : AiTranslationService {
    override suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> =
        AiModelClient.fetchModels(baseUrl, apiKey)

    override fun createConversation(
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
        historyTitle: String?
    ) = AiTranslationConversation(
        context = context,
        provider = provider,
        apiKey = apiKey,
        model = model,
        targetLanguage = targetLanguage,
        customPrompt = customPrompt,
        baseUrl = baseUrl,
        contextWindowTokens = contextWindowTokens,
        subtitleFormat = subtitleFormat,
        reasoningLevel = reasoningLevel,
        historySessionId = historySessionId,
        historyTitle = historyTitle
    )
}
