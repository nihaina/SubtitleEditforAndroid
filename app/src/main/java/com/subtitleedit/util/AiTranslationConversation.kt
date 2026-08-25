package com.subtitleedit.util

import android.content.Context
import com.subtitleedit.chat.ChatBackend
import com.subtitleedit.chat.ChatBackendConfig
import com.subtitleedit.chat.ChatConversation
import com.subtitleedit.chat.ChatHistoryStore
import com.subtitleedit.chat.ChatReasoningLevel
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import kotlinx.coroutines.CancellationException

/**
 * Subtitle translation is a small adapter over the shared chat module: it only prepares a
 * synthetic user message and validates the final assistant output. HTTP, SSE and tool rounds
 * are deliberately owned by [ChatConversation].
 */
class AiTranslationConversation(
    context: Context,
    provider: String,
    apiKey: String,
    model: String,
    private val targetLanguage: String,
    baseUrl: String,
    contextWindowTokens: Int,
    private val subtitleFormat: SubtitleFormat = SubtitleFormat.SRT,
    reasoningLevel: AiProviderConfig.ReasoningLevel = AiProviderConfig.defaultReasoningLevel(provider)
) {
    sealed class TranslationUiEvent {
        data class Request(
            val content: String,
            val startPosition: Int,
            val endPosition: Int
        ) : TranslationUiEvent()

        data class ReasoningDelta(val text: String) : TranslationUiEvent()
        data class AssistantDelta(val text: String) : TranslationUiEvent()
        object ProcessingResponse : TranslationUiEvent()
    }

    data class TranslationRunResult(
        val translations: List<String>,
        val error: Throwable? = null
    ) {
        val isComplete: Boolean
            get() = error == null
    }

    class TranslationCancelledException(
        val translations: List<String>,
        message: String?
    ) : CancellationException(message ?: "翻译已取消")

    private val conversation = ChatConversation(
        config = ChatBackendConfig(
            providerId = provider,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            contextWindowTokens = contextWindowTokens,
            reasoningLevel = ChatReasoningLevel.valueOf(reasoningLevel.name),
            modelSupportsReasoning = AiProviderConfig.modelCapabilities(provider, model).reasoning
        )
    )
    private val historyStore = ChatHistoryStore(context)
    private var historySessionId: String? = null

    fun cancel() = conversation.cancel()

    suspend fun translateSubtitles(
        subtitles: List<SubtitleEntry>,
        startPosition: Int = 1,
        progressCallback: ((Int, Int) -> Unit)? = null,
        conversationCallback: ((TranslationUiEvent) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): TranslationRunResult {
        require(startPosition > 0) { "字幕起始位置必须大于 0" }
        if (subtitles.isEmpty()) return TranslationRunResult(emptyList())

        val translations = mutableListOf<String>()
        var activeBatch = emptyList<SubtitleEntry>()
        var activeBatchStart = startPosition
        val streamedContent = StringBuilder()
        try {
            splitSubtitleTranslationBatches(subtitles).forEach { batch ->
                if (isCancelled()) throw CancellationException("翻译已取消")
                activeBatch = batch
                activeBatchStart = startPosition + translations.size
                streamedContent.setLength(0)
                val userContent = buildTranslationUserContent(
                    subtitles = batch,
                    targetLanguage = targetLanguage,
                    startPosition = activeBatchStart,
                    format = subtitleFormat
                )
                conversationCallback?.invoke(
                    TranslationUiEvent.Request(
                        content = userContent,
                        startPosition = activeBatchStart,
                        endPosition = activeBatchStart + batch.size - 1
                    )
                )
                val result = conversation.sendUserMessage(
                    content = userContent,
                    onEvent = { event ->
                        when (event) {
                            is ChatBackend.Event.ReasoningDelta -> conversationCallback?.invoke(
                                TranslationUiEvent.ReasoningDelta(event.text)
                            )
                            is ChatBackend.Event.TextDelta -> {
                                streamedContent.append(event.text)
                                conversationCallback?.invoke(TranslationUiEvent.AssistantDelta(event.text))
                            }
                            else -> Unit
                        }
                    },
                    isCancelled = isCancelled
                )
                if (streamedContent.isEmpty()) {
                    streamedContent.append(result.text)
                    conversationCallback?.invoke(TranslationUiEvent.AssistantDelta(result.text))
                }
                conversationCallback?.invoke(TranslationUiEvent.ProcessingResponse)
                historySessionId = historyStore.save(
                    id = historySessionId,
                    title = "翻译为$targetLanguage · ${subtitles.size} 条字幕",
                    type = ChatHistoryStore.TYPE_TRANSLATION,
                    messages = conversation.snapshot()
                )
                val batchTranslations = parseSubtitleTranslation(
                    content = result.text,
                    expectedSubtitles = batch,
                    format = subtitleFormat,
                    expectedStartPosition = activeBatchStart
                )
                translations += batchTranslations
                progressCallback?.invoke(translations.size, subtitles.size)
            }
            return TranslationRunResult(translations)
        } catch (error: CancellationException) {
            translations += completedBatchPrefix(
                content = streamedContent.toString(),
                expected = activeBatch,
                startPosition = activeBatchStart
            )
            throw TranslationCancelledException(translations, error.message)
        } catch (error: Exception) {
            return TranslationRunResult(translations, error)
        }
    }

    private fun completedBatchPrefix(
        content: String,
        expected: List<SubtitleEntry>,
        startPosition: Int
    ): List<String> {
        if (content.isBlank() || expected.isEmpty()) return emptyList()
        return runCatching {
            if (subtitleFormat == SubtitleFormat.SRT) {
                parseCompletedTimedTranslationPrefix(content, expected)
            } else {
                parseCompletedIndexedTranslationPrefix(content, expected, subtitleFormat, startPosition)
            }
        }.getOrDefault(emptyList())
    }
}
