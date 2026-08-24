package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private const val MAX_SUBTITLES_PER_TRANSLATION_REQUEST = 300
private const val AI_TRANSLATION_LOG_TAG = "AiTranslator"
const val DEFAULT_AI_CONTEXT_WINDOW_TOKENS = 256 * 1024
const val MIN_AI_CONTEXT_WINDOW_TOKENS = 4 * 1024
const val MAX_AI_CONTEXT_WINDOW_TOKENS = 2 * 1024 * 1024
private const val CONTEXT_KEEP_RATIO = 0.5
private const val MIN_COMPLETION_RESERVE_TOKENS = 1_024
private val TIMED_SUBTITLE_LINE = Regex(
    """^[ \t]*(\d{1,3}:\d{2}:\d{2}[,.]\d{3})[ \t]*(?:-->|->|—>|——>)[ \t]*(\d{1,3}:\d{2}:\d{2}[,.]\d{3})[ \t]*\r?$""",
    RegexOption.MULTILINE
)
private val TRANSLATION_TIMESTAMP =
    Regex("""^(\d{1,3}):(\d{2}):(\d{2})[,.](\d{3})$""")
private val SRT_SEQUENCE_BEFORE_CUE = Regex("""\n[ \t]*\d+[ \t]*\n[ \t]*$""")
private val MARKDOWN_FENCE_LINE = Regex("""(?m)^[ \t]*```[^\r\n]*\r?$""")

internal fun buildTranslationSystemPrompt(targetLanguage: String): String =
    "帮我翻译成${targetLanguage}，以原格式输出"

internal fun buildTimedSubtitleContent(
    subtitles: List<SubtitleEntry>,
    startPosition: Int = 1
): String = subtitles.mapIndexed { offset, subtitle ->
    val sequence = subtitle.index.takeIf { it > 0 } ?: (startPosition + offset)
    buildString {
        append(sequence)
        append('\n')
        append(subtitle.getTimeAxisSRT())
        append('\n')
        append(normalizeSubtitleText(subtitle.text))
    }
}.joinToString("\n\n")

internal fun buildTranslationUserContent(
    subtitles: List<SubtitleEntry>,
    targetLanguage: String,
    startPosition: Int = 1
): String = buildString {
    append(buildTimedSubtitleContent(subtitles, startPosition))
    append("\n\n")
    append("翻译成")
    append(targetLanguage)
    append("，以原格式输出")
}

internal fun splitSubtitleTranslationBatches(
    subtitles: List<SubtitleEntry>
): List<List<SubtitleEntry>> = subtitles.chunked(MAX_SUBTITLES_PER_TRANSLATION_REQUEST)

internal fun parseTimedSubtitleTranslation(
    content: String,
    expectedSubtitles: List<SubtitleEntry>
): List<String> {
    val expectedKeys = expectedSubtitles.map(SubtitleEntry::translationTimeRange)
    val expectedCounts = expectedKeys.groupingBy { it }.eachCount()
    val allReturnedBlocks = extractTimedSubtitleBlocks(content)
    val returnedBlocks = allReturnedBlocks
        .filter { it.timeRange in expectedCounts }
    val returnedCounts = returnedBlocks.groupingBy { it.timeRange }.eachCount()

    val duplicateRange = returnedCounts.entries.firstOrNull { (timeRange, count) ->
        count > expectedCounts.getValue(timeRange)
    }
    if (duplicateRange != null) {
        throw IOException("翻译结果包含重复时间轴：${duplicateRange.key.format()}")
    }

    val remainingReturnedCounts = returnedCounts.toMutableMap()
    val missingSubtitles = expectedSubtitles.mapIndexedNotNull { index, subtitle ->
        val timeRange = expectedKeys[index]
        val remaining = remainingReturnedCounts.getOrDefault(timeRange, 0)
        if (remaining > 0) {
            remainingReturnedCounts[timeRange] = remaining - 1
            null
        } else {
            subtitle to timeRange
        }
    }
    if (missingSubtitles.isNotEmpty()) {
        val preview = missingSubtitles.take(10).joinToString("、") { (subtitle, timeRange) ->
            val label = subtitle.index.takeIf { it > 0 }?.let { "原字幕 $it" } ?: "原字幕"
            "$label（${timeRange.format()}）"
        }
        val suffix = if (missingSubtitles.size > 10) "等 ${missingSubtitles.size} 条" else ""
        val unexpectedCount = allReturnedBlocks.size - returnedBlocks.size
        throw IOException(
            "AI 返回 ${returnedBlocks.size}/${expectedSubtitles.size} 个匹配时间轴" +
                (if (unexpectedCount > 0) "，另有 $unexpectedCount 个非本批次时间轴" else "") +
                "；缺少：$preview$suffix。AI 序号仅供显示，匹配以时间轴为准"
        )
    }

    val translationsByTime = returnedBlocks.groupByTo(
        destination = mutableMapOf(),
        keySelector = TimedSubtitleBlock::timeRange,
        valueTransform = TimedSubtitleBlock::text
    ).mapValues { (_, translations) -> ArrayDeque(translations) }

    return expectedKeys.map { timeRange ->
        translationsByTime.getValue(timeRange).removeFirst()
    }
}

/** Returns only complete consecutive blocks; the trailing block may still be streaming. */
internal fun parseCompletedTimedTranslationPrefix(
    content: String,
    expectedSubtitles: List<SubtitleEntry>
): List<String> {
    val expectedKeys = expectedSubtitles.map(SubtitleEntry::translationTimeRange)
    val expectedKeySet = expectedKeys.toSet()
    val completedBlocks = extractTimedSubtitleBlocks(content)
        .dropLast(1)
        .filter { it.timeRange in expectedKeySet }
    val translationsByTime = completedBlocks.groupByTo(
        destination = mutableMapOf(),
        keySelector = TimedSubtitleBlock::timeRange,
        valueTransform = TimedSubtitleBlock::text
    ).mapValues { (_, translations) -> ArrayDeque(translations) }

    return buildList {
        for (timeRange in expectedKeys) {
            val translations = translationsByTime[timeRange]
            if (translations == null || translations.isEmpty()) break
            add(translations.removeFirst())
        }
    }
}

private data class TranslationTimeRange(val startTimeMs: Long, val endTimeMs: Long) {
    fun format(): String =
        "${TimeUtils.formatSRT(startTimeMs)} --> ${TimeUtils.formatSRT(endTimeMs)}"
}

private data class TimedSubtitleBlock(
    val timeRange: TranslationTimeRange,
    val text: String
)

private fun SubtitleEntry.translationTimeRange() = TranslationTimeRange(startTime, endTime)

private fun extractTimedSubtitleBlocks(content: String): List<TimedSubtitleBlock> {
    val normalizedContent = limitToSrtCodeBlock(normalizeSubtitleText(content))
    val matches = TIMED_SUBTITLE_LINE.findAll(normalizedContent).toList()
    return matches.mapIndexedNotNull { index, match ->
        val startTime = parseTranslationTimestamp(match.groupValues[1])
            ?: return@mapIndexedNotNull null
        val endTime = parseTranslationTimestamp(match.groupValues[2])
            ?: return@mapIndexedNotNull null
        val blockEnd = matches.getOrNull(index + 1)?.range?.first ?: normalizedContent.length
        val blockText = normalizedContent.substring(match.range.last + 1, blockEnd)
            .removePrefix("\n")
            .replace(SRT_SEQUENCE_BEFORE_CUE, "\n")
            .trim('\n')
            .removeTrailingMarkdownFence()
        TimedSubtitleBlock(TranslationTimeRange(startTime, endTime), blockText)
    }
}

private fun limitToSrtCodeBlock(content: String): String {
    val timeStart = TIMED_SUBTITLE_LINE.find(content)?.range?.first ?: return content
    val fences = MARKDOWN_FENCE_LINE.findAll(content).toList()
    val openingFence = fences.lastOrNull { it.range.first < timeStart }
    val closingFence = fences.firstOrNull { it.range.first > timeStart }
    return if (openingFence != null && closingFence != null) {
        content.substring(0, closingFence.range.first)
    } else {
        content
    }
}

private fun timedSubtitleLineRange(line: String): TranslationTimeRange? {
    val match = TIMED_SUBTITLE_LINE.matchEntire(line.trimEnd('\r')) ?: return null
    val startTime = parseTranslationTimestamp(match.groupValues[1]) ?: return null
    val endTime = parseTranslationTimestamp(match.groupValues[2]) ?: return null
    return TranslationTimeRange(startTime, endTime)
}

private fun parseTranslationTimestamp(value: String): Long? {
    val match = TRANSLATION_TIMESTAMP.matchEntire(value) ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: return null
    val minutes = match.groupValues[2].toLongOrNull()?.takeIf { it < 60 } ?: return null
    val seconds = match.groupValues[3].toLongOrNull()?.takeIf { it < 60 } ?: return null
    val millis = match.groupValues[4].toLongOrNull() ?: return null
    return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
}

private fun String.removeTrailingMarkdownFence(): String {
    val lines = lines().toMutableList()
    while (lines.lastOrNull()?.trim() == "```") {
        lines.removeLast()
    }
    return lines.joinToString("\n").trimEnd('\n')
}

private fun normalizeSubtitleText(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n')

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
        val startPosition: Int,
        val endPosition: Int,
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
    suspend fun translateSubtitles(
        subtitles: List<SubtitleEntry>,
        startPosition: Int = 1,
        progressCallback: ((Int, Int) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        conversationState: ConversationState = ConversationState()
    ): TranslationRunResult = translationMutex.withLock {
        withContext(Dispatchers.IO) {
            require(startPosition > 0) { "字幕起始位置必须大于 0" }
            val translatedTexts = mutableListOf<String>()
            var currentConversation = normalizeConversation(conversationState)

            try {
                if (subtitles.isEmpty()) {
                    return@withContext TranslationRunResult(emptyList(), currentConversation)
                }
                if (isCancelled()) throw CancellationException("翻译已取消")

                splitSubtitleTranslationBatches(subtitles).forEach { batch ->
                    if (isCancelled()) throw CancellationException("翻译已取消")
                    val completedBeforeBatch = translatedTexts.size
                    val batchResult = translateBatch(
                        subtitles = batch,
                        conversationState = currentConversation,
                        batchStartPosition = startPosition + completedBeforeBatch,
                        isCancelled = isCancelled,
                        streamingProgress = { receivedCount ->
                            progressCallback?.invoke(
                                completedBeforeBatch + receivedCount,
                                subtitles.size
                            )
                        }
                    )
                    translatedTexts.addAll(batchResult.translations)
                    currentConversation = ConversationState(
                        batchResult.conversationBeforeBatch.messages +
                            ChatMessage("user", batchResult.userContent) +
                            ChatMessage("assistant", batchResult.assistantContent)
                    )
                    progressCallback?.invoke(translatedTexts.size, subtitles.size)
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

    private fun normalizeConversation(conversationState: ConversationState): ConversationState =
        conversationState

    private suspend fun translateBatch(
        subtitles: List<SubtitleEntry>,
        conversationState: ConversationState,
        batchStartPosition: Int,
        isCancelled: () -> Boolean,
        streamingProgress: (Int) -> Unit
    ): BatchResult {
        val userContent = buildTranslationUserContent(
            subtitles = subtitles,
            targetLanguage = targetLanguage,
            startPosition = batchStartPosition
        )
        val boundedConversation = compactConversationForRequest(conversationState, userContent)
        val requestMessages = boundedConversation.messages + ChatMessage("user", userContent)
        val logContext = TranslationLogContext(
            startPosition = batchStartPosition,
            endPosition = batchStartPosition + subtitles.size - 1,
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
            if (apiUrl.toHttpUrlOrNull()?.host != "api.mistral.ai") {
                put("stream_options", JSONObject().put("include_usage", true))
            }
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

        val progressTracker = TimedStreamProgress(
            expectedSubtitles = subtitles,
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
        val translations = try {
            parseTimedSubtitleTranslation(responseContent, subtitles)
        } catch (error: IOException) {
            RuntimeLogManager.e(
                AI_TRANSLATION_LOG_TAG,
                "AI_TRANSLATION_PARSE_ERROR batch=${logContext.startPosition}-${logContext.endPosition} " +
                    "responseChars=${responseContent.length} message=${error.message}",
                error
            )
            throw error
        }
        return BatchResult(
            translations = translations,
            userContent = userContent,
            assistantContent = responseContent,
            conversationBeforeBatch = boundedConversation
        )
    }

    /** Keeps complete user/assistant turns inside the configured context window. */
    internal fun compactConversationForRequest(
        conversationState: ConversationState,
        pendingUserContent: String
    ): ConversationState {
        val conversation = normalizeConversation(conversationState)
        val pendingMessage = ChatMessage("user", pendingUserContent)
        val completionReserve = maxOf(
            MIN_COMPLETION_RESERVE_TOKENS.toLong(),
            estimateTextTokens(pendingUserContent).toLong() * 3L / 2L
        ).coerceAtMost(Int.MAX_VALUE.toLong())
        val inputLimit = contextWindowTokens.toLong() - completionReserve
        val systemMessages = conversation.messages.takeWhile { it.role == "system" }
        val baseMessages = systemMessages + pendingMessage
        val baseTokens = estimateMessagesTokens(baseMessages)
        if (baseTokens.toLong() > inputLimit) {
            throw IOException(
                "当前 300 条字幕预计需要 ${baseTokens + completionReserve} tokens，" +
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

        for (index in pairs.indices.reversed()) {
            val pair = pairs[index]
            val messages = systemMessages + pair + selectedPairs.flatten() + pendingMessage
            val accepted = if (estimateMessagesTokens(messages).toLong() <= targetInputTokens) {
                pair
            } else {
                null
            }
            if (accepted != null) {
                selectedPairs.addFirst(accepted)
                continue
            }

            if (selectedPairs.isEmpty()) {
                val pairTokens = estimateMessagesTokens(systemMessages + pair + pendingMessage)
                if (pairTokens.toLong() <= inputLimit) {
                    selectedPairs.addFirst(pair)
                }
            }
            break
        }

        return ConversationState(systemMessages + selectedPairs.flatten())
    }

    internal fun estimateConversationTokens(messages: List<ChatMessage>): Int =
        estimateMessagesTokens(messages)

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

    /** Retries only transient transport/API failures; subtitle format is validated once afterward. */
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
                        val content = readStreamingContent(
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
                        return content
                    }
                }
            } catch (error: NonRetryableApiException) {
                throw error
            } catch (error: IOException) {
                if (isCancelled()) {
                    RuntimeLogManager.i(
                        AI_TRANSLATION_LOG_TAG,
                        "AI_TRANSLATION_ATTEMPT_CANCELLED batch=${logContext.startPosition}-${logContext.endPosition} " +
                            "attempt=$attemptNumber"
                    )
                    throw CancellationException("翻译已取消")
                }
                RuntimeLogManager.w(
                    AI_TRANSLATION_LOG_TAG,
                    "AI_TRANSLATION_ATTEMPT_ERROR batch=${logContext.startPosition}-${logContext.endPosition} " +
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
        var receivedDoneEvent = false
        var capturedContent = ""
        var completed = false
        val source = responseBody.source()

        try {
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data:")) {
                    receivedServerEvent = true
                    val data = line.removePrefix("data:").trimStart()
                    if (data == "[DONE]") {
                        receivedDoneEvent = true
                        break
                    }
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
            // A streamed response is complete only after the provider's explicit DONE event.
            // EOF without DONE is retained for diagnostics and final parsing, but must not be
            // reported as a successful transport completion.
            completed = !receivedServerEvent || receivedDoneEvent
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
                    "batch=${context.startPosition}-${context.endPosition} attempt=$attempt/$MAX_RETRY_COUNT " +
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
                "batch=${context.startPosition}-${context.endPosition} attempt=$attempt/$MAX_RETRY_COUNT " +
                    "complete=$complete status=$status chars=${content.length} " +
                    "payload=unfiltered_assistant_content_before_parsing"
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
        return buildString {
            data.trim()
                .split('\n')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { jsonLine ->
                    val event = try {
                        JSONObject(jsonLine)
                    } catch (error: Exception) {
                        throw IOException("流式响应格式无效", error)
                    }
                    event.optJSONObject("error")?.let { error ->
                        throw IOException(error.optString("message").ifBlank { "AI 服务返回错误" })
                    }
                    val choices = event.optJSONArray("choices") ?: return@forEach
                    if (choices.length() == 0) return@forEach
                    val choice = choices.optJSONObject(0) ?: return@forEach
                    val delta = choice.optJSONObject("delta")
                    val content = delta?.opt("content")
                        ?: choice.optJSONObject("message")?.opt("content")
                    append(jsonContentToText(content))
                }
        }
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

    private class TimedStreamProgress(
        private val expectedSubtitles: List<SubtitleEntry>,
        private val onProgress: (Int) -> Unit
    ) {
        private val expectedTimeRanges = expectedSubtitles
            .map(SubtitleEntry::translationTimeRange)
            .toSet()
        private val currentLine = StringBuilder()
        private val completedContent = StringBuilder()
        private var reportedCount = 0

        fun reset() {
            currentLine.setLength(0)
            completedContent.setLength(0)
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
            reportProgress(countCompletedTimedTranslations(completedContent.toString(), false))
        }

        fun completedTranslations(): List<String> = parseCompletedTimedTranslationPrefix(
            completedContent.toString(),
            expectedSubtitles
        )

        private fun consumeCurrentLine() {
            val line = currentLine.toString()
            currentLine.setLength(0)
            completedContent.append(line).append('\n')
            val timeRange = timedSubtitleLineRange(line)
            if (timeRange in expectedTimeRanges) {
                // Progress is diagnostic only. Count every completed time-matched block so one
                // missing or out-of-order cue cannot make the UI appear frozen forever. Final
                // validation still requires every expected time range and preserves source order.
                val completedCount = countCompletedTimedTranslations(completedContent.toString())
                reportProgress(completedCount)
            }
        }

        private fun reportProgress(completedCount: Int) {
            if (completedCount > reportedCount) {
                reportedCount = completedCount
                onProgress(completedCount)
            }
        }

        private fun countCompletedTimedTranslations(
            content: String,
            excludeTrailingBlock: Boolean = true
        ): Int {
            val expectedCounts = expectedSubtitles
                .map(SubtitleEntry::translationTimeRange)
                .groupingBy { it }
                .eachCount()
                .toMutableMap()
            val blocks = extractTimedSubtitleBlocks(content)
            return (if (excludeTrailingBlock) blocks.dropLast(1) else blocks)
                .count { block ->
                    val remaining = expectedCounts[block.timeRange] ?: return@count false
                    if (remaining <= 0) return@count false
                    expectedCounts[block.timeRange] = remaining - 1
                    true
                }
        }
    }
}
