package com.subtitleedit.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private const val MAX_LINES_PER_TRANSLATION_REQUEST = 300
private const val AI_TRANSLATION_LOG_TAG = "AiTranslator"
const val DEFAULT_AI_CONTEXT_WINDOW_TOKENS = 256 * 1024
const val MIN_AI_CONTEXT_WINDOW_TOKENS = 4 * 1024
const val MAX_AI_CONTEXT_WINDOW_TOKENS = 2 * 1024 * 1024
private const val RECENT_FULL_CONTEXT_BATCHES = 2
private const val COMPACTED_LINES_PER_BATCH = 24
private const val CONTEXT_KEEP_RATIO = 0.5
private const val MIN_COMPLETION_RESERVE_TOKENS = 1_024
private val NUMBERED_TRANSLATION_LINE =
    Regex("""^\s*(?:\[(\d+)]|(\d+)\s*[.．、:：])\s*(.*)$""")

internal fun buildTranslationSystemPrompt(targetLanguage: String): String =
    "帮我翻译成${targetLanguage}，以原格式输出"

internal fun buildNumberedSubtitleContent(texts: List<String>, startNumber: Int): String =
    texts.mapIndexed { index, text ->
        "${startNumber + index}.${encodeSubtitleText(text)}"
    }.joinToString("\n")

internal fun splitSubtitleTranslationBatches(texts: List<String>): List<List<String>> =
    texts.chunked(MAX_LINES_PER_TRANSLATION_REQUEST)

/**
 * Extracts only numbered rows. Unnumbered model commentary is intentionally ignored, while a
 * numbered row with no content remains a valid empty translation.
 */
internal fun parseNumberedTranslation(
    content: String,
    expectedStartNumber: Int,
    expectedCount: Int
): List<String> {
    val expectedEndNumber = expectedStartNumber + expectedCount - 1
    val translations = mutableMapOf<Int, String>()

    content.lineSequence().forEach { rawLine ->
        val match = NUMBERED_TRANSLATION_LINE.matchEntire(rawLine.trimEnd('\r'))
            ?: return@forEach
        val number = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull()
            ?: return@forEach
        if (number !in expectedStartNumber..expectedEndNumber) return@forEach
        if (translations.containsKey(number)) {
            throw IOException("翻译结果包含重复编号：$number")
        }
        translations[number] = decodeSubtitleText(match.groupValues[3].trim())
    }

    val missingNumbers = (expectedStartNumber..expectedEndNumber)
        .filterNot(translations::containsKey)
    if (missingNumbers.isNotEmpty()) {
        val preview = missingNumbers.take(10).joinToString("、")
        val suffix = if (missingNumbers.size > 10) "等 ${missingNumbers.size} 条" else ""
        throw IOException("翻译结果缺少编号：$preview$suffix")
    }
    return (expectedStartNumber..expectedEndNumber).map { translations.getValue(it) }
}

/** Returns only the continuous numbered prefix that was fully terminated before cancellation. */
internal fun parseCompletedTranslationPrefix(
    content: String,
    expectedStartNumber: Int,
    expectedCount: Int
): List<String> {
    val expectedEndNumber = expectedStartNumber + expectedCount - 1
    val translations = mutableMapOf<Int, String>()
    content.lineSequence().forEach { rawLine ->
        val match = NUMBERED_TRANSLATION_LINE.matchEntire(rawLine.trimEnd('\r'))
            ?: return@forEach
        val number = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull()
            ?: return@forEach
        if (number in expectedStartNumber..expectedEndNumber &&
            !translations.containsKey(number)
        ) {
            translations[number] = decodeSubtitleText(match.groupValues[3].trim())
        }
    }

    return buildList {
        for (number in expectedStartNumber..expectedEndNumber) {
            val translation = translations[number] ?: break
            add(translation)
        }
    }
}

private fun numberedTranslationLineNumber(line: String): Int? {
    val match = NUMBERED_TRANSLATION_LINE.matchEntire(line.trimEnd('\r')) ?: return null
    return (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull()
}

private fun encodeSubtitleText(text: String): String =
    text.replace("\\", "\\\\")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\n", "\\n")

private fun decodeSubtitleText(text: String): String = buildString {
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '\\' && index + 1 < text.length) {
            when (text[index + 1]) {
                'n' -> append('\n')
                '\\' -> append('\\')
                else -> {
                    append(character)
                    append(text[index + 1])
                }
            }
            index += 2
        } else {
            append(character)
            index++
        }
    }
}

/** Uses an OpenAI-compatible Chat Completions endpoint for streamed subtitle translation. */
class AiTranslator(
    private val provider: String,
    private val apiKey: String,
    private val model: String,
    private val targetLanguage: String,
    private val baseUrl: String = "",
    contextWindowTokens: Int = DEFAULT_AI_CONTEXT_WINDOW_TOKENS
) {
    companion object {
        private const val MAX_RETRY_COUNT = 3
    }

    data class ChatMessage(val role: String, val content: String)

    data class ConversationState(val messages: List<ChatMessage> = emptyList())

    data class TranslationRunResult(
        val translations: List<String>,
        val conversationState: ConversationState,
        val error: Throwable? = null
    ) {
        val isComplete: Boolean
            get() = error == null
    }

    class TranslationCancelledException(
        val translations: List<String>,
        val conversationState: ConversationState,
        message: String?
    ) : CancellationException(message ?: "翻译已取消")

    private data class BatchResult(
        val translations: List<String>,
        val userContent: String,
        val assistantContent: String,
        val conversationBeforeBatch: ConversationState
    )

    private data class TranslationLogContext(
        val startNumber: Int,
        val endNumber: Int,
        val messages: List<ChatMessage>,
        val estimatedInputTokens: Int
    )

    private class NonRetryableApiException(message: String) : IOException(message)

    private class BatchTranslationCancelledException(
        val translations: List<String>,
        message: String?
    ) : CancellationException(message ?: "翻译已取消")

    private val client = OkHttpClient.Builder()
        .connectTimeout(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()
    private val translationMutex = Mutex()
    private val contextWindowTokens = contextWindowTokens.coerceIn(
        MIN_AI_CONTEXT_WINDOW_TOKENS,
        MAX_AI_CONTEXT_WINDOW_TOKENS
    )

    private val providerConfig = AiProviderConfig.getProvider(provider)
    private val apiUrl = AiProviderConfig.chatCompletionsUrl(
        baseUrl.ifBlank { providerConfig.baseUrl }
    )

    @Volatile
    private var activeCall: Call? = null

    fun cancel() {
        activeCall?.cancel()
    }

    /**
     * Sends batches one at a time. A successful user/assistant pair is appended to the state
     * before the next batch, matching a normal multi-turn chat conversation.
     */
    suspend fun translateTexts(
        texts: List<String>,
        startNumber: Int = 1,
        progressCallback: ((Int, Int) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        conversationState: ConversationState = ConversationState()
    ): TranslationRunResult = translationMutex.withLock {
        withContext(Dispatchers.IO) {
            require(startNumber > 0) { "字幕起始编号必须大于 0" }
            val translatedTexts = mutableListOf<String>()
            var currentConversation = ensureSystemMessage(conversationState)

            try {
                if (texts.isEmpty()) {
                    return@withContext TranslationRunResult(emptyList(), currentConversation)
                }
                if (isCancelled()) throw CancellationException("翻译已取消")

                splitSubtitleTranslationBatches(texts).forEach { batch ->
                    if (isCancelled()) throw CancellationException("翻译已取消")
                    val completedBeforeBatch = translatedTexts.size
                    val batchStartNumber = startNumber + completedBeforeBatch
                    val batchResult = translateBatch(
                        texts = batch,
                        conversationState = currentConversation,
                        startNumber = batchStartNumber,
                        isCancelled = isCancelled,
                        streamingProgress = { receivedCount ->
                            progressCallback?.invoke(completedBeforeBatch + receivedCount, texts.size)
                        }
                    )
                    translatedTexts.addAll(batchResult.translations)
                    currentConversation = ConversationState(
                        batchResult.conversationBeforeBatch.messages +
                            ChatMessage("user", batchResult.userContent) +
                            ChatMessage("assistant", batchResult.assistantContent)
                    )
                    progressCallback?.invoke(translatedTexts.size, texts.size)
                }

                TranslationRunResult(translatedTexts, currentConversation)
            } catch (error: BatchTranslationCancelledException) {
                translatedTexts.addAll(error.translations)
                throw TranslationCancelledException(
                    translatedTexts.toList(),
                    currentConversation,
                    error.message
                )
            } catch (error: CancellationException) {
                throw TranslationCancelledException(
                    translatedTexts.toList(),
                    currentConversation,
                    error.message
                )
            } catch (error: Exception) {
                TranslationRunResult(translatedTexts, currentConversation, error)
            }
        }
    }

    private fun ensureSystemMessage(conversationState: ConversationState): ConversationState {
        if (conversationState.messages.isNotEmpty()) return conversationState
        return ConversationState(
            listOf(ChatMessage("system", buildTranslationSystemPrompt(targetLanguage)))
        )
    }

    private suspend fun translateBatch(
        texts: List<String>,
        conversationState: ConversationState,
        startNumber: Int,
        isCancelled: () -> Boolean,
        streamingProgress: (Int) -> Unit
    ): BatchResult {
        val userContent = buildNumberedSubtitleContent(texts, startNumber)
        val boundedConversation = compactConversationForRequest(conversationState, userContent)
        val requestMessages = boundedConversation.messages + ChatMessage("user", userContent)
        val logContext = TranslationLogContext(
            startNumber = startNumber,
            endNumber = startNumber + texts.size - 1,
            messages = requestMessages,
            estimatedInputTokens = estimateMessagesTokens(requestMessages)
        )
        val jsonBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                requestMessages.forEach { message ->
                    put(JSONObject().put("role", message.role).put("content", message.content))
                }
            })
            put("stream", true)
            if (provider == AiProviderConfig.DEEPSEEK) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
        }
        val request = Request.Builder()
            .url(apiUrl)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .build()

        val progressTracker = NumberedStreamProgress(
            expectedStartNumber = startNumber,
            expectedCount = texts.size,
            onProgress = streamingProgress
        )
        val responseContent = try {
            executeWithRetry(
                request = request,
                isCancelled = isCancelled,
                onAttemptStarted = { progressTracker.reset() },
                onDelta = progressTracker::append,
                logContext = logContext
            )
        } catch (error: CancellationException) {
            throw BatchTranslationCancelledException(
                progressTracker.completedTranslations(),
                error.message
            )
        }
        progressTracker.finish()
        return BatchResult(
            translations = parseNumberedTranslation(responseContent, startNumber, texts.size),
            userContent = userContent,
            assistantContent = responseContent,
            conversationBeforeBatch = boundedConversation
        )
    }

    /**
     * Keeps the current request inside the configured model context window. Recent batches remain
     * complete; older pairs keep only a small numbered tail before the oldest pairs are discarded.
     */
    internal fun compactConversationForRequest(
        conversationState: ConversationState,
        pendingUserContent: String
    ): ConversationState {
        val conversation = ensureSystemMessage(conversationState)
        val pendingMessage = ChatMessage("user", pendingUserContent)
        val completionReserve = maxOf(
            MIN_COMPLETION_RESERVE_TOKENS.toLong(),
            estimateTextTokens(pendingUserContent).toLong() * 3L / 2L
        ).coerceAtMost(Int.MAX_VALUE.toLong())
        val inputLimit = contextWindowTokens.toLong() - completionReserve
        val systemMessages = conversation.messages.takeWhile { it.role == "system" }
            .ifEmpty { listOf(ChatMessage("system", buildTranslationSystemPrompt(targetLanguage))) }
        val baseMessages = systemMessages + pendingMessage
        val baseTokens = estimateMessagesTokens(baseMessages)
        if (baseTokens.toLong() > inputLimit) {
            throw IOException(
                "当前 300 行字幕预计需要 ${baseTokens + completionReserve} tokens，" +
                    "超过上下文上限 $contextWindowTokens，请提高上下文上限或缩短单行字幕"
            )
        }

        if (estimateMessagesTokens(conversation.messages + pendingMessage).toLong() <= inputLimit) {
            return conversation
        }

        val history = conversation.messages.drop(systemMessages.size)
        val pairs = history.chunked(2).mapNotNull { messages ->
            if (messages.size == 2 && messages[0].role == "user" &&
                messages[1].role == "assistant"
            ) {
                messages
            } else {
                null
            }
        }
        val targetInputTokens = baseTokens.toLong() +
            ((inputLimit - baseTokens) * CONTEXT_KEEP_RATIO).toLong()
        val selectedPairs = ArrayDeque<List<ChatMessage>>()
        val recentFullStart = (pairs.size - RECENT_FULL_CONTEXT_BATCHES).coerceAtLeast(0)

        for (index in pairs.indices.reversed()) {
            val pair = pairs[index]
            val compactedPair = compactPair(pair)
            val candidates = if (index >= recentFullStart && compactedPair != pair) {
                listOf(pair, compactedPair)
            } else {
                listOf(compactedPair)
            }
            val accepted = candidates.firstOrNull { candidate ->
                val messages = systemMessages + candidate + selectedPairs.flatten() + pendingMessage
                estimateMessagesTokens(messages).toLong() <= targetInputTokens
            }
            if (accepted != null) {
                selectedPairs.addFirst(accepted)
                continue
            }

            if (selectedPairs.isEmpty()) {
                val fallback = candidates.firstOrNull { candidate ->
                    estimateMessagesTokens(systemMessages + candidate + pendingMessage).toLong() <= inputLimit
                }
                if (fallback != null) selectedPairs.addFirst(fallback)
            }
            break
        }

        return ConversationState(systemMessages + selectedPairs.flatten())
    }

    internal fun estimateConversationTokens(messages: List<ChatMessage>): Int =
        estimateMessagesTokens(messages)

    private fun compactPair(pair: List<ChatMessage>): List<ChatMessage> = pair.map { message ->
        message.copy(content = compactNumberedContent(message.content))
    }

    private fun compactNumberedContent(content: String): String {
        val numberedLines = content.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { NUMBERED_TRANSLATION_LINE.matches(it) }
            .toList()
        if (numberedLines.isNotEmpty()) {
            return numberedLines.takeLast(COMPACTED_LINES_PER_BATCH).joinToString("\n")
        }
        return content.takeLast(2_048)
    }

    private fun estimateMessagesTokens(messages: List<ChatMessage>): Int {
        val estimate = 3L + messages.sumOf { message ->
            6L + estimateTextTokens(message.role) + estimateTextTokens(message.content)
        }
        return estimate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun estimateTextTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val utf8Bytes = text.toByteArray(StandardCharsets.UTF_8).size.toLong()
        return ((utf8Bytes + 1L) / 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private suspend fun executeWithRetry(
        request: Request,
        isCancelled: () -> Boolean,
        onAttemptStarted: (Int) -> Unit,
        onDelta: (String) -> Unit,
        logContext: TranslationLogContext
    ): String {
        var lastError: IOException? = null
        repeat(MAX_RETRY_COUNT) { attempt ->
            if (isCancelled()) throw CancellationException("翻译已取消")
            val attemptNumber = attempt + 1
            try {
                onAttemptStarted(attemptNumber)
                logTranslationRequest(logContext, attemptNumber)
                val call = client.newCall(request)
                activeCall = call
                if (isCancelled()) {
                    call.cancel()
                    throw CancellationException("翻译已取消")
                }
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val responseText = response.body?.string().orEmpty()
                        logTranslationResponse(
                            context = logContext,
                            attempt = attemptNumber,
                            content = responseText,
                            complete = false,
                            status = "HTTP ${response.code}"
                        )
                        val error = IOException(formatApiError(response.code, responseText))
                        if (response.code !in listOf(408, 429) && response.code !in 500..599) {
                            throw NonRetryableApiException(error.message ?: "API 请求失败")
                        }
                        lastError = error
                        val retryAfterMs = response.header("Retry-After")
                            ?.toLongOrNull()
                            ?.times(1_000L)
                        if (attempt < MAX_RETRY_COUNT - 1) {
                            delay(retryAfterMs ?: (1_000L shl attempt))
                        }
                    } else {
                        val responseBody = response.body
                        if (responseBody == null) {
                            logTranslationResponse(
                                context = logContext,
                                attempt = attemptNumber,
                                content = "",
                                complete = false,
                                status = "HTTP ${response.code}，响应体为空"
                            )
                            throw IOException("响应为空")
                        }
                        return readStreamingContent(
                            responseBody = responseBody,
                            onDelta = onDelta,
                            onCaptured = { content, complete ->
                                logTranslationResponse(
                                    context = logContext,
                                    attempt = attemptNumber,
                                    content = content,
                                    complete = complete,
                                    status = "HTTP ${response.code}"
                                )
                            }
                        )
                            .ifBlank { throw IOException("响应为空") }
                    }
                }
            } catch (error: NonRetryableApiException) {
                throw error
            } catch (error: IOException) {
                if (isCancelled()) {
                    RuntimeLogManager.i(
                        AI_TRANSLATION_LOG_TAG,
                        "AI_TRANSLATION_ATTEMPT_CANCELLED batch=${logContext.startNumber}-${logContext.endNumber} " +
                            "attempt=$attemptNumber"
                    )
                    throw CancellationException("翻译已取消")
                }
                RuntimeLogManager.w(
                    AI_TRANSLATION_LOG_TAG,
                    "AI_TRANSLATION_ATTEMPT_ERROR batch=${logContext.startNumber}-${logContext.endNumber} " +
                        "attempt=$attemptNumber message=${error.message}",
                    error
                )
                lastError = error
                if (attempt < MAX_RETRY_COUNT - 1) delay(1_000L shl attempt)
            } finally {
                activeCall = null
            }
        }
        throw lastError ?: IOException("请求失败")
    }

    internal fun readStreamingContent(
        responseBody: ResponseBody,
        onCaptured: (String, Boolean) -> Unit = { _, _ -> },
        onDelta: (String) -> Unit
    ): String {
        val streamedContent = StringBuilder()
        val plainResponse = StringBuilder()
        var receivedServerEvent = false
        var capturedContent = ""
        var completed = false
        val source = responseBody.source()

        try {
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data:")) {
                    receivedServerEvent = true
                    val data = line.removePrefix("data:").trimStart()
                    if (data == "[DONE]") break
                    if (data.isBlank()) continue
                    val delta = extractStreamDelta(data)
                    if (delta.isNotEmpty()) {
                        streamedContent.append(delta)
                        onDelta(delta)
                    }
                } else if (!receivedServerEvent && line.isNotBlank()) {
                    if (plainResponse.isNotEmpty()) plainResponse.append('\n')
                    plainResponse.append(line)
                }
            }

            capturedContent = if (receivedServerEvent) {
                streamedContent.toString()
            } else {
                extractRegularResponseContent(plainResponse.toString()).also { content ->
                    if (content.isNotEmpty()) onDelta(content)
                }
            }
            completed = true
            return capturedContent
        } finally {
            if (!completed) {
                capturedContent = if (receivedServerEvent) {
                    streamedContent.toString()
                } else {
                    plainResponse.toString()
                }
            }
            runCatching { onCaptured(capturedContent, completed) }
        }
    }

    private fun logTranslationRequest(context: TranslationLogContext, attempt: Int) {
        RuntimeLogManager.i(
            AI_TRANSLATION_LOG_TAG,
            buildString {
                appendLine("AI_TRANSLATION_REQUEST_BEGIN")
                appendLine(
                    "batch=${context.startNumber}-${context.endNumber} attempt=$attempt/$MAX_RETRY_COUNT " +
                        "provider=$provider model=$model contextWindowTokens=$contextWindowTokens " +
                        "estimatedInputTokens=${context.estimatedInputTokens}"
                )
                context.messages.forEachIndexed { index, message ->
                    appendLine("--- message[$index] role=${message.role} ---")
                    append(message.content)
                    if (!message.content.endsWith('\n')) appendLine()
                }
                append("AI_TRANSLATION_REQUEST_END")
            }
        )
    }

    private fun logTranslationResponse(
        context: TranslationLogContext,
        attempt: Int,
        content: String,
        complete: Boolean,
        status: String
    ) {
        val message = buildString {
            appendLine("AI_TRANSLATION_RESPONSE_BEGIN")
            appendLine(
                "batch=${context.startNumber}-${context.endNumber} attempt=$attempt/$MAX_RETRY_COUNT " +
                    "complete=$complete status=$status chars=${content.length}"
            )
            append(content)
            if (!content.endsWith('\n')) appendLine()
            append("AI_TRANSLATION_RESPONSE_END")
        }
        if (complete) {
            RuntimeLogManager.i(AI_TRANSLATION_LOG_TAG, message)
        } else {
            RuntimeLogManager.w(AI_TRANSLATION_LOG_TAG, message)
        }
    }

    private fun extractStreamDelta(data: String): String {
        val event = try {
            JSONObject(data)
        } catch (error: Exception) {
            throw IOException("流式响应格式无效", error)
        }
        event.optJSONObject("error")?.let { error ->
            throw IOException(error.optString("message").ifBlank { "AI 服务返回错误" })
        }
        val choices = event.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val choice = choices.optJSONObject(0) ?: return ""
        if (choice.optString("finish_reason") == "length") {
            throw NonRetryableApiException("AI 输出达到长度上限，当前批次未完整返回")
        }
        val delta = choice.optJSONObject("delta")
        val content = delta?.opt("content")
            ?: choice.optJSONObject("message")?.opt("content")
        return jsonContentToText(content)
    }

    private fun extractRegularResponseContent(responseText: String): String {
        val response = try {
            JSONObject(responseText)
        } catch (error: Exception) {
            throw IOException("响应格式无效", error)
        }
        response.optJSONObject("error")?.let { error ->
            throw IOException(error.optString("message").ifBlank { "AI 服务返回错误" })
        }
        val choices = response.optJSONArray("choices")
            ?: throw IOException("响应中没有翻译结果")
        if (choices.length() == 0) throw IOException("响应中没有翻译结果")
        val content = choices.optJSONObject(0)
            ?.optJSONObject("message")
            ?.opt("content")
        return jsonContentToText(content)
    }

    private fun jsonContentToText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index)
                append(part?.optString("text").orEmpty())
            }
        }
        else -> ""
    }

    private fun formatApiError(code: Int, body: String): String {
        val providerMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val detail = providerMessage ?: body.take(2_000).ifBlank { "未知错误" }
        return "API 请求失败：$code - $detail"
    }

    private class NumberedStreamProgress(
        private val expectedStartNumber: Int,
        expectedCount: Int,
        private val onProgress: (Int) -> Unit
    ) {
        private val expectedEndNumber = expectedStartNumber + expectedCount - 1
        private val currentLine = StringBuilder()
        private val completedContent = StringBuilder()
        private val receivedNumbers = mutableSetOf<Int>()

        fun reset() {
            currentLine.setLength(0)
            completedContent.setLength(0)
            receivedNumbers.clear()
        }

        fun append(delta: String) {
            delta.forEach { character ->
                if (character == '\n') {
                    consumeCurrentLine()
                } else {
                    currentLine.append(character)
                }
            }
        }

        fun finish() {
            consumeCurrentLine()
        }

        fun completedTranslations(): List<String> = parseCompletedTranslationPrefix(
            completedContent.toString(),
            expectedStartNumber,
            expectedEndNumber - expectedStartNumber + 1
        )

        private fun consumeCurrentLine() {
            val line = currentLine.toString()
            currentLine.setLength(0)
            completedContent.append(line).append('\n')
            val number = numberedTranslationLineNumber(line)
            if (number != null && number in expectedStartNumber..expectedEndNumber &&
                receivedNumbers.add(number)
            ) {
                onProgress(receivedNumbers.size)
            }
        }
    }
}
