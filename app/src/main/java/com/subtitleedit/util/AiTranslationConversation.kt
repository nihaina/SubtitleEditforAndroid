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
import java.io.IOException
import java.util.UUID

/**
 * Subtitle translation is a small adapter over the shared chat module: it only prepares a
 * synthetic user message and validates the final assistant output. HTTP, SSE and tool rounds
 * are deliberately owned by [ChatConversation].
 */
class AiTranslationConversation(
    context: Context,
    private val provider: String,
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
    private val settingsManager = SettingsManager.getInstance(context)
    private val indexTranslate2bModel = localProvider &&
        settingsManager.getLlmModelFamily() == SettingsManager.LLM_MODEL_INDEX_TRANSLATE &&
        isIndexTranslate2b(settingsManager)

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
                settingsManager.getLlmModelPath()
            } else {
                ""
            },
            localRepackEnabled = settingsManager.isLlmRepackEnabled(),
            localContextSize = settingsManager.getLlmContextSize(),
            localThinkingBudget = settingsManager.getLlmThinkingBudget(),
            localTemperature = settingsManager.getLlmTemperature(),
            localTopP = settingsManager.getLlmTopP(),
            localRepetitionPenalty = settingsManager.getLlmRepetitionPenalty()
        ),
        tools = if (provider == AiProviderConfig.LOCAL) emptyList() else ChatTools.create(context.applicationContext),
        context = context.applicationContext
    )
    private val historyStore = ChatHistoryStore(context)

    private fun isIndexTranslate2b(settings: SettingsManager): Boolean {
        // Downloaded and manually selected Index GGUF files expose their size in the
        // display name. Only an explicit 2B match opts into the short prompt.
        val model2bPattern = Regex("(?i)(^|[^a-z0-9])2b([^a-z0-9]|$)")
        return model2bPattern.containsMatchIn(settings.getLlmModelDisplayName()) ||
            model2bPattern.containsMatchIn(settings.getLlmModelPath())
    }

    fun cancel() = conversation.cancel()

    /** TXT chunks use the same translation instruction without the cue/time-axis protocol. */
    suspend fun translateText(
        text: String,
        streamCallback: ((String) -> Unit)? = null,
        retryOnNoOutput: Boolean = false,
        onNoOutputRetry: ((Int) -> Unit)? = null,
        preserveConversation: Boolean = false,
        isPaused: () -> Boolean = { false },
        isCancelled: () -> Boolean = { false }
    ): Result<String> {
        if (text.isBlank()) return Result.success(text)
        return runCatching {
            if (isPaused() || isCancelled()) throw CancellationException("翻译已取消")
            val streamed = StringBuilder()
            val prompt = buildTextTranslationUserContent(text, targetLanguage, customPrompt, indexTranslate2bModel)
            val reasoning = StringBuilder()
            val result = try {
                sendUserPrompt(
                    content = prompt,
                    clearConversation = localProvider && !preserveConversation,
                    streamCallback = streamCallback,
                    isCancelled = { isPaused() || isCancelled() },
                    cancellationMessage = "翻译已取消",
                    streamedContent = streamed,
                    streamedReasoning = reasoning,
                    retryOnNoOutput = retryOnNoOutput,
                    onNoOutputRetry = onNoOutputRetry,
                    isPaused = isPaused
                )
            } catch (error: Throwable) {
                if (isPaused()) {
                    val partial = streamed.toString()
                    val partialReasoning = reasoning.toString()
                    conversation.appendInterruptedTurn(prompt, partial, partialReasoning)
                    historyStore.append(
                        id = historySessionId,
                        title = historyTitle ?: "翻译为$targetLanguage",
                        type = ChatHistoryStore.TYPE_TRANSLATION,
                        messages = listOf(
                            ChatBackend.ChatMessage("user", prompt),
                            ChatBackend.ChatMessage("assistant", partial, reasoningContent = partialReasoning)
                        )
                    )
                }
                throw error
            }
            historyStore.append(
                id = historySessionId,
                title = historyTitle ?: "翻译为$targetLanguage",
                type = ChatHistoryStore.TYPE_TRANSLATION,
                messages = result.messages
            )
            val translation = try {
                extractTaggedTxtAiResponse(result.text)
            } catch (error: InvalidTxtAiResponseException) {
                // A failed cloud reply is already in ChatConversation history. Remove it
                // before AutoTranslateViewModel retries this TXT chunk.
                if (!localProvider) conversation.clear()
                throw error
            }
            translation.also {
                if (it.isBlank()) throw IOException("AI 未返回文本翻译结果")
            }
        }
    }

    suspend fun predictPunctuation(
        text: String,
        streamCallback: ((String) -> Unit)? = null,
        clearConversation: Boolean = true,
        recordInterruptedTurn: () -> Boolean = { false },
        isPaused: () -> Boolean = { false },
        isCancelled: () -> Boolean = { false }
    ): Result<String> = processSubtitleText(
        text = text,
        instruction = PUNCTUATION_PREDICTION_PROMPT,
        defaultHistoryTitle = "标点预测",
        streamCallback = streamCallback,
        clearConversation = clearConversation,
        recordInterruptedTurn = recordInterruptedTurn,
        isPaused = isPaused,
        isCancelled = isCancelled
    )

    private suspend fun processSubtitleText(
        text: String,
        instruction: String,
        defaultHistoryTitle: String,
        streamCallback: ((String) -> Unit)?,
        clearConversation: Boolean,
        recordInterruptedTurn: () -> Boolean,
        isPaused: () -> Boolean,
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
                if (subtitleFormat == SubtitleFormat.TXT) append("\n\n") else append('\n')
                if (subtitleFormat == SubtitleFormat.TXT) append("<start>\n")
                append(text)
                if (subtitleFormat == SubtitleFormat.TXT) append("\n<end>")
            }
            val streamedContent = StringBuilder()
            val streamedReasoning = StringBuilder()
            val result = try {
                sendUserPrompt(
                    content = prompt,
                    clearConversation = clearConversation,
                    streamCallback = streamCallback,
                    isCancelled = { isPaused() || isCancelled() },
                    cancellationMessage = "标点预测已取消",
                    streamedContent = streamedContent,
                    streamedReasoning = streamedReasoning,
                    isPaused = isPaused
                )
            } catch (error: Throwable) {
                if (recordInterruptedTurn() || isPaused()) {
                    val partial = streamedContent.toString()
                    conversation.appendInterruptedTurn(
                        userContent = prompt,
                        partialAssistantContent = partial,
                        partialReasoningContent = streamedReasoning.toString()
                    )
                    historyStore.append(
                        id = historySessionId,
                        title = historyTitle ?: defaultHistoryTitle,
                        type = ChatHistoryStore.TYPE_TRANSLATION,
                        messages = listOf(
                            ChatBackend.ChatMessage(role = "user", content = prompt),
                            ChatBackend.ChatMessage(
                                role = "assistant",
                                content = partial,
                                reasoningContent = streamedReasoning.toString()
                            )
                        )
                    )
                }
                throw error
            }
            if (recordInterruptedTurn() || isPaused()) {
                historyStore.append(
                    id = historySessionId,
                    title = historyTitle ?: defaultHistoryTitle,
                    type = ChatHistoryStore.TYPE_TRANSLATION,
                    messages = result.messages
                )
                throw CancellationException("标点预测响应格式错误")
            }
            if (streamedContent.isEmpty()) {
                runCatching { streamCallback?.invoke(result.text) }
            }
            historyStore.append(
                id = historySessionId,
                title = historyTitle ?: defaultHistoryTitle,
                type = ChatHistoryStore.TYPE_TRANSLATION,
                messages = result.messages
            )
            if (subtitleFormat == SubtitleFormat.TXT) {
                try {
                    extractTaggedTxtAiResponse(result.text)
                } catch (error: InvalidTxtAiResponseException) {
                    if (!localProvider) conversation.clear()
                    throw error
                }
            } else {
                extractSubtitleAiResponse(result.text)
            }
        }
    }

    suspend fun translateSubtitles(
        subtitles: List<SubtitleEntry>,
        startPosition: Int = 1,
        progressCallback: ((Int, Int) -> Unit)? = null,
        streamCallback: ((String) -> Unit)? = null,
        streamProgressCallback: ((Int, Int) -> Unit)? = null,
        retryOnNoOutput: Boolean = false,
        onNoOutputRetry: ((Int) -> Unit)? = null,
        preserveConversationOnFirstBatch: Boolean = false,
        maxSubtitlesPerBatch: Int? = null,
        isPaused: () -> Boolean = { false },
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
                maxSubtitlesPerBatch = maxSubtitlesPerBatch ?: settingsManager.getAiSubtitlesPerRequest(provider)
            ).forEachIndexed { batchIndex, batch ->
                if (isPaused() || isCancelled()) throw CancellationException("翻译已暂停或取消")
                if (localProvider && !(preserveConversationOnFirstBatch && batchIndex == 0)) {
                    // Each batch contains its complete subtitle context. Keeping
                    // prior batches would eventually exceed the native context.
                    conversation.clear()
                }
                activeBatch = batch
                activeBatchStart = startPosition + translations.size
                streamedContent.setLength(0)
                val batchTranslations = translateBatchWithRecovery(
                    batch = batch,
                    batchStart = activeBatchStart,
                    totalSubtitles = subtitles.size,
                    completedBeforeBatch = translations.size,
                    streamCallback = streamCallback,
                    streamProgressCallback = streamProgressCallback,
                    retryOnNoOutput = retryOnNoOutput,
                    onNoOutputRetry = onNoOutputRetry,
                    isPaused = isPaused,
                    isCancelled = isCancelled,
                    streamedContent = streamedContent
                )
                translations += batchTranslations
                activeBatch = emptyList()
                streamedContent.setLength(0)
                progressCallback?.invoke(translations.size, subtitles.size)
            }
            return TranslationRunResult(translations)
        } catch (error: BatchTranslationCancelled) {
            translations += error.completedTranslations
            throw TranslationCancelledException(translations, error.message)
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

    private sealed class BatchAttempt {
        data class Success(val translations: List<String>) : BatchAttempt()
        data class Failure(
            val issue: TranslationProtocolIssue,
            val completedPrefix: List<String>
        ) : BatchAttempt()
    }

    private class BatchTranslationCancelled(
        val completedTranslations: List<String>,
        message: String?
    ) : CancellationException(message ?: "翻译已取消")

    /**
     * Finishes one batch before returning anything to the caller. A protocol error is recovered
     * in three increasingly narrow passes: retry from the preceding cue, retry a three-cue
     * window, then retry the preceding cue/current cue separately before continuing with the tail.
     */
    private suspend fun translateBatchWithRecovery(
        batch: List<SubtitleEntry>,
        batchStart: Int,
        totalSubtitles: Int,
        completedBeforeBatch: Int,
        streamCallback: ((String) -> Unit)?,
        streamProgressCallback: ((Int, Int) -> Unit)?,
        retryOnNoOutput: Boolean,
        onNoOutputRetry: ((Int) -> Unit)?,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        streamedContent: StringBuilder
    ): List<String> {
        val translated = arrayOfNulls<String>(batch.size)
        val pending = ArrayDeque<List<Int>>()
        pending.add((batch.indices).toList())
        var lastIssueIndex: Int? = null
        var retryLevel = 0
        var activeRequestIndices: List<Int>? = null

        fun reportProgress() {
            val completed = translated.count { it != null }
            runCatching {
                streamProgressCallback?.invoke(
                    completedBeforeBatch + completed,
                    totalSubtitles
                )
            }
        }

        fun enqueueMissingRanges() {
            var rangeStart = -1
            for (index in 0..translated.size) {
                val missing = index < translated.size && translated[index] == null
                if (missing && rangeStart < 0) rangeStart = index
                if (!missing && rangeStart >= 0) {
                    pending.add((rangeStart until index).toList())
                    rangeStart = -1
                }
            }
        }

        try {
            while (pending.isNotEmpty()) {
                if (isPaused() || isCancelled()) throw CancellationException("翻译已暂停或取消")
                val requestIndices = pending.removeFirst()
                if (requestIndices.isEmpty()) continue
                activeRequestIndices = requestIndices
                val attempt = requestBatchRange(
                    batch = batch,
                    batchStart = batchStart,
                    requestIndices = requestIndices,
                    streamCallback = streamCallback,
                    retryOnNoOutput = retryOnNoOutput,
                    onNoOutputRetry = onNoOutputRetry,
                    isPaused = isPaused,
                    isCancelled = isCancelled,
                    streamedContent = streamedContent
                )
                activeRequestIndices = null
                when (attempt) {
                    is BatchAttempt.Success -> {
                        requestIndices.zip(attempt.translations).forEach { (index, text) ->
                            translated[index] = text
                        }
                        reportProgress()
                        // A successful three-cue recovery window only covers the local
                        // window. Continue with the untouched tail before declaring the
                        // batch complete. Existing stage-three queues already contain
                        // their remaining requests, so do not duplicate them.
                        if (pending.isEmpty()) {
                            enqueueMissingRanges()
                        }
                    }

                    is BatchAttempt.Failure -> {
                        val issueOffset = attempt.issue.index.coerceIn(0, requestIndices.lastIndex)
                        requestIndices.take(attempt.completedPrefix.size.coerceAtMost(issueOffset))
                            .zip(attempt.completedPrefix)
                            .forEach { (index, text) -> translated[index] = text }
                        reportProgress()

                        val issueIndex = requestIndices[issueOffset]
                        if (lastIssueIndex != issueIndex) {
                            lastIssueIndex = issueIndex
                            retryLevel = 1
                        } else {
                            retryLevel++
                        }
                        if (retryLevel > 3) {
                            throw IOException(
                                "字幕第 ${batchStart + issueIndex} 条连续三次返回格式错误：${attempt.issue.message}"
                            )
                        }

                        pending.clear()
                        when (retryLevel) {
                            1 -> {
                                val retryStart = (issueIndex - 1).coerceAtLeast(0)
                                pending.add((retryStart until batch.size).toList())
                            }

                            2 -> {
                                val windowStart = (issueIndex - 1).coerceAtLeast(0)
                                val windowEnd = (issueIndex + 2).coerceAtMost(batch.size)
                                pending.add((windowStart until windowEnd).toList())
                            }

                            else -> {
                                val previous = (issueIndex - 1).takeIf { it >= 0 }
                                previous?.let { pending.add(listOf(it)) }
                                pending.add(listOf(issueIndex))
                                if (issueIndex + 1 < batch.size) {
                                    pending.add((issueIndex + 1 until batch.size).toList())
                                }
                            }
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            if (isPaused() || isCancelled()) {
                activeRequestIndices?.let { requestIndices ->
                    val requestSubtitles = requestIndices.map(batch::get)
                    val completedPrefix = completedBatchPrefix(
                        streamedContent.toString(),
                        requestSubtitles,
                        batchStart + requestIndices.first()
                    )
                    requestIndices.take(completedPrefix.size)
                        .zip(completedPrefix)
                        .forEach { (index, text) -> translated[index] = text }
                }
                // A retry can have completed later cues while earlier ones are still pending.
                // Returning only the contiguous prefix keeps subtitle/result alignment intact.
                throw BatchTranslationCancelled(
                    translated.takeWhile { it != null }.filterNotNull(),
                    error.message
                )
            }
            throw error
        }

        if (translated.any { it == null }) {
            throw IOException("AI 未完成当前批次翻译")
        }
        @Suppress("UNCHECKED_CAST")
        return translated.toList() as List<String>
    }

    private suspend fun requestBatchRange(
        batch: List<SubtitleEntry>,
        batchStart: Int,
        requestIndices: List<Int>,
        streamCallback: ((String) -> Unit)?,
        retryOnNoOutput: Boolean,
        onNoOutputRetry: ((Int) -> Unit)?,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        streamedContent: StringBuilder
    ): BatchAttempt {
        val requestSubtitles = requestIndices.map(batch::get)
        val requestStart = batchStart + requestIndices.first()
        val userContent = buildTranslationUserContent(
            subtitles = requestSubtitles,
            targetLanguage = targetLanguage,
            customPrompt = customPrompt,
            startPosition = requestStart,
            format = subtitleFormat,
            sequenceOnly = localProvider,
            includeBlockMarkers = true,
            indexTranslate = indexTranslate2bModel
        )
        streamedContent.setLength(0)
        val streamedReasoning = StringBuilder()
        var protocolIssue: TranslationProtocolIssue? = null

        suspend fun recordInterruptedAttempt() {
            val messages = listOf(
                ChatBackend.ChatMessage(role = "user", content = userContent),
                ChatBackend.ChatMessage(
                    role = "assistant",
                    content = streamedContent.toString(),
                    reasoningContent = streamedReasoning.toString()
                )
            )
            conversation.appendInterruptedTurn(
                userContent = userContent,
                partialAssistantContent = streamedContent.toString(),
                partialReasoningContent = streamedReasoning.toString()
            )
            historyStore.append(
                id = historySessionId,
                title = historyTitle ?: "翻译为$targetLanguage · ${batch.size} 条字幕",
                type = ChatHistoryStore.TYPE_TRANSLATION,
                messages = messages
            )
        }

        val result = try {
            sendUserPrompt(
                content = userContent,
                clearConversation = false,
                streamCallback = streamCallback,
                isCancelled = { isPaused() || isCancelled() || protocolIssue != null },
                isPaused = isPaused,
                cancellationMessage = "翻译已取消",
                streamedContent = streamedContent,
                streamedReasoning = streamedReasoning,
                retryOnNoOutput = retryOnNoOutput,
                onNoOutputRetry = onNoOutputRetry,
                onTextDelta = {
                    if (protocolIssue == null) {
                        findTranslationProtocolIssue(
                            content = streamedContent.toString(),
                            expectedSubtitles = requestSubtitles,
                            format = subtitleFormat,
                            expectedStartPosition = requestStart,
                            sequenceOnly = localProvider,
                            finalResponse = false
                        )?.let { issue ->
                            protocolIssue = issue
                            // Causes both the HTTP SSE reader and the local generation loop
                            // to stop at the first identifiable bad cue.
                            conversation.cancel()
                        }
                    }
                }
            )
        } catch (error: CancellationException) {
            if (isPaused()) {
                recordInterruptedAttempt()
                throw error
            }
            if (protocolIssue != null && !isCancelled()) {
                recordInterruptedAttempt()
                return BatchAttempt.Failure(
                    protocolIssue!!,
                    completedBatchPrefix(streamedContent.toString(), requestSubtitles, requestStart)
                )
            }
            throw error
        } catch (error: Exception) {
            if (isPaused()) {
                recordInterruptedAttempt()
                throw error
            }
            if (protocolIssue != null && !isCancelled()) {
                recordInterruptedAttempt()
                return BatchAttempt.Failure(
                    protocolIssue!!,
                    completedBatchPrefix(streamedContent.toString(), requestSubtitles, requestStart)
                )
            }
            throw error
        }

        if (protocolIssue != null) {
            return BatchAttempt.Failure(
                protocolIssue!!,
                completedBatchPrefix(streamedContent.toString(), requestSubtitles, requestStart)
            )
        }
        val finalIssue = findTranslationProtocolIssue(
            content = result.text,
            expectedSubtitles = requestSubtitles,
            format = subtitleFormat,
            expectedStartPosition = requestStart,
            sequenceOnly = localProvider,
            finalResponse = true
        )
        if (finalIssue != null) {
            historyStore.append(
                id = historySessionId,
                title = historyTitle ?: "翻译为$targetLanguage",
                type = ChatHistoryStore.TYPE_TRANSLATION,
                messages = result.messages
            )
            return BatchAttempt.Failure(
                finalIssue,
                completedBatchPrefix(result.text, requestSubtitles, requestStart)
            )
        }
        historyStore.append(
            id = historySessionId,
            title = historyTitle ?: "翻译为$targetLanguage · ${batch.size} 条字幕",
            type = ChatHistoryStore.TYPE_TRANSLATION,
            messages = result.messages
        )
        val parsed = try {
            parseSubtitleTranslation(
                content = result.text,
                expectedSubtitles = requestSubtitles,
                format = subtitleFormat,
                expectedStartPosition = requestStart,
                sequenceOnly = localProvider
            )
        } catch (error: Exception) {
            return BatchAttempt.Failure(
                TranslationProtocolIssue(0, error.message ?: "AI 返回字幕格式无效"),
                completedBatchPrefix(result.text, requestSubtitles, requestStart)
            )
        }
        return BatchAttempt.Success(parsed)
    }

    /**
     * Sends one user turn through the same local/API path used by one-click translation.
     * Callers keep protocol-specific history and parsing, while this method owns the common
     * input simulation, reasoning-channel capture, visible streaming and cancellation checks.
     */
    private suspend fun sendUserPrompt(
        content: String,
        clearConversation: Boolean,
        streamCallback: ((String) -> Unit)?,
        isCancelled: () -> Boolean,
        cancellationMessage: String,
        streamedContent: StringBuilder,
        streamedReasoning: StringBuilder,
        retryOnNoOutput: Boolean = false,
        onNoOutputRetry: ((Int) -> Unit)? = null,
        isPaused: () -> Boolean = { false },
        onTextDelta: (() -> Unit)? = null
    ): ChatBackend.SendResult {
        if (retryOnNoOutput) {
            return retryAiRequestWhenNoOutput(
                isCancelled = { isCancelled() || isPaused() },
                onTimeout = conversation::cancel,
                onRetry = { retries ->
                    streamedContent.setLength(0)
                    streamedReasoning.setLength(0)
                    onNoOutputRetry?.invoke(retries)
                }
            ) { onOutput, isTimedOut ->
                sendUserPromptAttempt(
                    content = content,
                    clearConversation = clearConversation,
                    streamCallback = streamCallback,
                    isCancelled = { isCancelled() || isTimedOut() },
                    cancellationMessage = cancellationMessage,
                    streamedContent = streamedContent,
                    streamedReasoning = streamedReasoning,
                    onTextDelta = onTextDelta,
                    onOutput = onOutput,
                    isPaused = isPaused
                )
            }
        }
        return sendUserPromptAttempt(
            content = content,
            clearConversation = clearConversation,
            streamCallback = streamCallback,
            isCancelled = isCancelled,
            cancellationMessage = cancellationMessage,
            streamedContent = streamedContent,
            streamedReasoning = streamedReasoning,
            onTextDelta = onTextDelta,
            onOutput = {},
            isPaused = isPaused
        )
    }

    private suspend fun sendUserPromptAttempt(
        content: String,
        clearConversation: Boolean,
        streamCallback: ((String) -> Unit)?,
        isCancelled: () -> Boolean,
        cancellationMessage: String,
        streamedContent: StringBuilder,
        streamedReasoning: StringBuilder,
        onTextDelta: (() -> Unit)?,
        onOutput: () -> Unit,
        isPaused: () -> Boolean
    ): ChatBackend.SendResult {
        if (isCancelled() && !isPaused()) throw CancellationException(cancellationMessage)
        if (clearConversation) conversation.clear()
        val result = conversation.sendUserMessage(
            content = content,
            onEvent = { event ->
                when (event) {
                    is ChatBackend.Event.ReasoningDelta -> {
                        if (event.text.isNotEmpty()) onOutput()
                        streamedReasoning.append(event.text)
                    }
                    is ChatBackend.Event.TextDelta -> {
                        if (event.text.isNotEmpty()) onOutput()
                        streamedContent.append(event.text)
                        runCatching { streamCallback?.invoke(streamedContent.toString()) }
                        onTextDelta?.invoke()
                    }
                    else -> Unit
                }
            },
            isCancelled = isCancelled
        )
        if (isCancelled() && !isPaused()) throw CancellationException(cancellationMessage)
        if (streamedContent.isEmpty()) {
            if (result.text.isNotEmpty()) onOutput()
            streamedContent.append(result.text)
            runCatching { streamCallback?.invoke(streamedContent.toString()) }
            onTextDelta?.invoke()
        }
        return result
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
                parseCompletedTimestampedTranslationPrefix(content, expected, subtitleFormat)
            }
        }.getOrDefault(emptyList())
    }
}
