package com.subtitleedit.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.concurrent.TimeUnit

private const val MAX_LINES_PER_TRANSLATION_REQUEST = 300
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
    private val baseUrl: String = ""
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
        val assistantContent: String
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
    ): TranslationRunResult = withContext(Dispatchers.IO) {
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
                    currentConversation.messages +
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
        val requestMessages = conversationState.messages + ChatMessage("user", userContent)
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
                onAttemptStarted = progressTracker::reset,
                onDelta = progressTracker::append
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
            assistantContent = responseContent
        )
    }

    private suspend fun executeWithRetry(
        request: Request,
        isCancelled: () -> Boolean,
        onAttemptStarted: () -> Unit,
        onDelta: (String) -> Unit
    ): String {
        var lastError: IOException? = null
        repeat(MAX_RETRY_COUNT) { attempt ->
            if (isCancelled()) throw CancellationException("翻译已取消")
            try {
                onAttemptStarted()
                val call = client.newCall(request)
                activeCall = call
                if (isCancelled()) {
                    call.cancel()
                    throw CancellationException("翻译已取消")
                }
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val responseText = response.body?.string().orEmpty()
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
                        val responseBody = response.body ?: throw IOException("响应为空")
                        return readStreamingContent(responseBody, onDelta)
                            .ifBlank { throw IOException("响应为空") }
                    }
                }
            } catch (error: NonRetryableApiException) {
                throw error
            } catch (error: IOException) {
                if (isCancelled()) throw CancellationException("翻译已取消")
                lastError = error
                if (attempt < MAX_RETRY_COUNT - 1) delay(1_000L shl attempt)
            } finally {
                activeCall = null
            }
        }
        throw lastError ?: IOException("请求失败")
    }

    private fun readStreamingContent(responseBody: ResponseBody, onDelta: (String) -> Unit): String {
        val streamedContent = StringBuilder()
        val plainResponse = StringBuilder()
        var receivedServerEvent = false
        val source = responseBody.source()

        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data:")) {
                receivedServerEvent = true
                val data = line.removePrefix("data:").trimStart()
                if (data.isBlank() || data == "[DONE]") continue
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

        if (receivedServerEvent) return streamedContent.toString()
        val content = extractRegularResponseContent(plainResponse.toString())
        if (content.isNotEmpty()) onDelta(content)
        return content
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
