package com.subtitleedit.util

import android.content.Context
import com.subtitleedit.chat.ChatBackend
import com.subtitleedit.chat.ChatBackendConfig
import com.subtitleedit.chat.ChatConversation
import com.subtitleedit.chat.ChatHistoryStore
import com.subtitleedit.chat.ChatReasoningLevel
import com.subtitleedit.chat.ChatTools
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser.SubtitleFormat
import kotlinx.coroutines.CancellationException
import java.util.UUID

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
    private val customPrompt: String = "",
    baseUrl: String,
    private val subtitleFormat: SubtitleFormat = SubtitleFormat.SRT,
    reasoningLevel: AiProviderConfig.ReasoningLevel = AiProviderConfig.defaultReasoningLevel(provider),
    private val thinkingEnabled: Boolean = reasoningLevel != AiProviderConfig.ReasoningLevel.OFF,
    /** Stable ID for one editor translation operation, including all split batches and retries. */
    private val historySessionId: String = UUID.randomUUID().toString(),
    private val historyTitle: String? = null
) {
    private val localProvider = provider == AiProviderConfig.LOCAL

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
            reasoningLevel = ChatReasoningLevel.valueOf(reasoningLevel.name),
            modelSupportsReasoning = AiProviderConfig.modelCapabilities(provider, model).reasoning,
            thinkingEnabled = thinkingEnabled,
            localModelPath = if (provider == AiProviderConfig.LOCAL) {
                SettingsManager.getInstance(context).getLlmModelPath()
            } else {
                ""
            },
            localRepackEnabled = SettingsManager.getInstance(context).isLlmRepackEnabled(),
            localContextSize = SettingsManager.getInstance(context).getLlmContextSize()
        ),
        tools = if (provider == AiProviderConfig.LOCAL) emptyList() else ChatTools.create(context.applicationContext),
        context = context.applicationContext
    )
    private val historyStore = ChatHistoryStore(context)

    fun cancel() = conversation.cancel()

    suspend fun predictPunctuation(
        text: String,
        streamCallback: ((String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): Result<String> = processSubtitleText(
        text = text,
        instruction = PUNCTUATION_PREDICTION_PROMPT,
        defaultHistoryTitle = "标点预测",
        streamCallback = streamCallback,
        isCancelled = isCancelled
    )

    private suspend fun processSubtitleText(
        text: String,
        instruction: String,
        defaultHistoryTitle: String,
        streamCallback: ((String) -> Unit)?,
        isCancelled: () -> Boolean
    ): Result<String> {
        if (text.isBlank()) return Result.success(text)
        return runCatching {
            val prompt = buildString {
                append(instruction)
                customPrompt.trim().takeIf { it.isNotEmpty() }?.let {
                    append('\n')
                    append(it)
                }
                append("\n\n")
                append(text)
            }
            // The caller provides all context needed for this batch. Earlier batches
            // stay in the history archive, but must not enter this AI request.
            conversation.clear()
            val streamedContent = StringBuilder()
            val result = conversation.sendUserMessage(
                content = prompt,
                onEvent = { event ->
                    if (event is ChatBackend.Event.TextDelta) {
                        streamedContent.append(event.text)
                        runCatching { streamCallback?.invoke(streamedContent.toString()) }
                    }
                },
                isCancelled = isCancelled
            )
            if (streamedContent.isEmpty()) {
                runCatching { streamCallback?.invoke(result.text) }
            }
            historyStore.append(
                id = historySessionId,
                title = historyTitle ?: defaultHistoryTitle,
                type = ChatHistoryStore.TYPE_TRANSLATION,
                messages = result.messages
            )
            extractSubtitleAiResponse(result.text)
        }
    }

    suspend fun translateSubtitles(
        subtitles: List<SubtitleEntry>,
        startPosition: Int = 1,
        progressCallback: ((Int, Int) -> Unit)? = null,
        streamCallback: ((String) -> Unit)? = null,
        streamProgressCallback: ((Int, Int) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): TranslationRunResult {
        require(startPosition > 0) { "字幕起始位置必须大于 0" }
        if (subtitles.isEmpty()) return TranslationRunResult(emptyList())

        val translations = mutableListOf<String>()
        var activeBatch = emptyList<SubtitleEntry>()
        var activeBatchStart = startPosition
        val streamedContent = StringBuilder()
        try {
            splitSubtitleTranslationBatches(
                subtitles,
                maxSubtitlesPerBatch = if (localProvider) {
                    LOCAL_AI_SUBTITLES_PER_REQUEST
                } else {
                    DEFAULT_AI_SUBTITLES_PER_REQUEST
                }
            ).forEach { batch ->
                if (isCancelled()) throw CancellationException("翻译已取消")
                if (localProvider) {
                    // Each batch contains its complete subtitle context. Keeping
                    // prior batches would eventually exceed the native context.
                    conversation.clear()
                }
                activeBatch = batch
                activeBatchStart = startPosition + translations.size
                streamedContent.setLength(0)
                var streamedBatchCount = 0
                val userContent = buildTranslationUserContent(
                    subtitles = batch,
                    targetLanguage = targetLanguage,
                    customPrompt = customPrompt,
                    startPosition = activeBatchStart,
                    format = subtitleFormat,
                    sequenceOnly = localProvider
                )
                val result = conversation.sendUserMessage(
                    content = userContent,
                    onEvent = { event ->
                        if (event is ChatBackend.Event.TextDelta) {
                            streamedContent.append(event.text)
                            runCatching { streamCallback?.invoke(streamedContent.toString()) }
                            val completedPrefix = completedBatchPrefix(
                                content = streamedContent.toString(),
                                expected = batch,
                                startPosition = activeBatchStart
                            ).size
                            if (completedPrefix > streamedBatchCount) {
                                streamedBatchCount = completedPrefix
                                runCatching {
                                    streamProgressCallback?.invoke(
                                        translations.size + streamedBatchCount,
                                        subtitles.size
                                    )
                                }
                            }
                        }
                    },
                    isCancelled = isCancelled
                )
                if (streamedContent.isEmpty()) {
                    streamedContent.append(result.text)
                    runCatching { streamCallback?.invoke(streamedContent.toString()) }
                    val completedPrefix = completedBatchPrefix(
                        content = streamedContent.toString(),
                        expected = batch,
                        startPosition = activeBatchStart
                    ).size
                    if (completedPrefix > streamedBatchCount) {
                        streamedBatchCount = completedPrefix
                        runCatching {
                            streamProgressCallback?.invoke(
                                translations.size + streamedBatchCount,
                                subtitles.size
                            )
                        }
                    }
                }
                historyStore.append(
                    id = historySessionId,
                    title = historyTitle ?: "翻译为$targetLanguage · ${subtitles.size} 条字幕",
                    type = ChatHistoryStore.TYPE_TRANSLATION,
                    messages = result.messages
                )
                val batchTranslations = parseSubtitleTranslation(
                    content = result.text,
                    expectedSubtitles = batch,
                    format = subtitleFormat,
                    expectedStartPosition = activeBatchStart,
                    sequenceOnly = localProvider
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
            if (localProvider) {
                parseCompletedSequenceTranslationPrefix(content, expected, startPosition)
            } else if (subtitleFormat == SubtitleFormat.SRT) {
                parseCompletedTimedTranslationPrefix(content, expected)
            } else {
                parseCompletedIndexedTranslationPrefix(content, expected, subtitleFormat, startPosition)
            }
        }.getOrDefault(emptyList())
    }
}
