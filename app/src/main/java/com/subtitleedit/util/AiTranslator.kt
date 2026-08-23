package com.subtitleedit.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 翻译器 - 使用 OpenAI 兼容的 Chat Completions API 进行翻译
 */
class AiTranslator(
    private val provider: String,
    private val apiKey: String,
    private val model: String,
    private val sourceLanguage: String,
    private val targetLanguage: String,
    private val customPrompt: String = "",
    private val baseUrl: String = ""
) {
    companion object {
        private const val MAX_LINES_PER_REQUEST = 300
        private const val MAX_CHARS_PER_REQUEST = 12_000
        private const val MAX_CONTEXT_CHARS = 2_000
        private const val MAX_RETRY_COUNT = 3
    }
    private val client = OkHttpClient.Builder()
        // 0 表示无限制；用户可通过取消按钮主动中断当前请求。
        .connectTimeout(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()

    private val providerConfig = AiProviderConfig.getProvider(provider)
    private val apiUrl = AiProviderConfig.chatCompletionsUrl(
        baseUrl.ifBlank { providerConfig.baseUrl }
    )
    @Volatile private var activeCall: Call? = null

    fun cancel() {
        activeCall?.cancel()
    }

    /**
     * 翻译字幕文本 - 批量翻译优化版本
     * @param texts 要翻译的文本列表
     * @param progressCallback 进度回调 (当前进度，总进度)
     * @param isCancelled 取消检查回调
     * @param continuationContext 重试时沿用的上一成功批次上下文
     * @return 本次已完成的译文、续传上下文与可选失败信息
     */
    data class TranslationRunResult(
        val translations: List<String>,
        val continuationContext: String,
        val error: Throwable? = null
    ) {
        val isComplete: Boolean
            get() = error == null
    }

    class TranslationCancelledException(
        val translations: List<String>,
        val continuationContext: String,
        message: String?
    ) : CancellationException(message ?: "翻译已取消")

    suspend fun translateTexts(
        texts: List<String>,
        progressCallback: ((Int, Int) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        continuationContext: String = ""
    ): TranslationRunResult = withContext(Dispatchers.IO) {
        val translatedTexts = mutableListOf<String>()
        var previousContext = continuationContext
        try {
            // 批量翻译：将所有文本合并成一个大请求
            if (texts.isEmpty()) {
                return@withContext TranslationRunResult(emptyList(), previousContext)
            }

            if (isCancelled()) {
                throw CancellationException("翻译已取消")
            }

            buildBatches(texts).forEachIndexed { batchIndex, batch ->
                if (isCancelled()) throw CancellationException("翻译已取消")
                val batchResult = translateBatch(batch, previousContext, batchIndex, isCancelled)
                translatedTexts.addAll(batchResult.translations)
                previousContext = batchResult.rawResponse.takeLast(MAX_CONTEXT_CHARS)
                progressCallback?.invoke(translatedTexts.size, texts.size)
            }

            TranslationRunResult(translatedTexts, previousContext)
        } catch (e: CancellationException) {
            throw TranslationCancelledException(
                translatedTexts.toList(),
                previousContext,
                e.message
            )
        } catch (e: Exception) {
            TranslationRunResult(translatedTexts, previousContext, e)
        }
    }

    /**
     * 批量翻译文本
     * 将所有字幕文本合并成一个大请求，提高效率和节省 Token
     */
    private data class BatchResult(
        val translations: List<String>,
        val rawResponse: String
    )

    private class NonRetryableApiException(message: String) : IOException(message)

    private fun buildBatches(texts: List<String>): List<List<String>> {
        val batches = mutableListOf<MutableList<String>>()
        var currentBatch = mutableListOf<String>()
        var currentChars = 0
        texts.forEach { text ->
            val encodedText = encodeSubtitleText(text)
            if (encodedText.length > MAX_CHARS_PER_REQUEST) {
                throw IOException("单条字幕超过 ${MAX_CHARS_PER_REQUEST} 个字符，无法稳定翻译，请先拆分该字幕")
            }
            val indexedLength = encodedText.length + 16
            if (currentBatch.isNotEmpty() &&
                (currentBatch.size >= MAX_LINES_PER_REQUEST || currentChars + indexedLength > MAX_CHARS_PER_REQUEST)
            ) {
                batches.add(currentBatch)
                currentBatch = mutableListOf()
                currentChars = 0
            }
            currentBatch.add(text)
            currentChars += indexedLength
        }
        if (currentBatch.isNotEmpty()) batches.add(currentBatch)
        return batches
    }

    private suspend fun translateBatch(
        texts: List<String>,
        previousContext: String,
        batchIndex: Int,
        isCancelled: () -> Boolean
    ): BatchResult {
        if (texts.isEmpty()) return BatchResult(emptyList(), "")

        // 构建带索引的文本内容，方便后续解析
        val indexedContent = texts.mapIndexed { index, text ->
            "[${index + 1}] ${encodeSubtitleText(text)}"
        }.joinToString("\n")

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", buildSystemPrompt())
            })
            if (previousContext.isNotBlank()) {
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", "上一批翻译结果摘要，请延续术语与语气：\n$previousContext")
                })
            }
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", indexedContent)
        })

        val jsonBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", false)
            // DeepSeek's thinking-mode API does not support temperature. Thinking is
            // explicitly disabled below for deterministic subtitle output.
            if (provider != AiProviderConfig.DEEPSEEK) {
                put("temperature", 0.3)
            }
            put("max_tokens", (texts.sumOf { it.length } * 2).coerceIn(1024, 8192))
            if (provider == AiProviderConfig.DEEPSEEK) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
        }

        val mediaType = "application/json".toMediaType()
        val body = jsonBody.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        val responseContent = executeWithRetry(request, isCancelled)
        val jsonResponse = JSONObject(responseContent)
        val choices = jsonResponse.getJSONArray("choices")
        if (choices.length() == 0) throw IOException("第 ${batchIndex + 1} 批没有返回翻译结果")

        val content = choices.getJSONObject(0).getJSONObject("message").getString("content")
        return BatchResult(parseTranslationResult(content, texts.size), content)
    }

    private suspend fun executeWithRetry(request: Request, isCancelled: () -> Boolean): String {
        var lastError: IOException? = null
        repeat(MAX_RETRY_COUNT) { attempt ->
            if (isCancelled()) throw CancellationException("翻译已取消")
            try {
                val call = client.newCall(request)
                activeCall = call
                call.execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) return body.ifBlank { throw IOException("响应为空") }
                    val error = IOException(formatApiError(response.code, body))
                    if (response.code !in listOf(408, 429) && response.code !in 500..599) {
                        throw NonRetryableApiException(error.message ?: "API 请求失败")
                    }
                    lastError = error
                    val retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.times(1_000L)
                    if (attempt < MAX_RETRY_COUNT - 1) delay(retryAfterMs ?: (1_000L shl attempt))
                }
            } catch (e: NonRetryableApiException) {
                throw e
            } catch (e: IOException) {
                if (isCancelled()) throw CancellationException("翻译已取消")
                lastError = e
                if (attempt < MAX_RETRY_COUNT - 1) delay(1_000L shl attempt)
            } finally {
                activeCall = null
            }
        }
        throw lastError ?: IOException("请求失败")
    }

    private fun formatApiError(code: Int, body: String): String {
        val providerMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val detail = providerMessage ?: body.take(2_000).ifBlank { "未知错误" }
        return "API 请求失败：$code - $detail"
    }

    /**
     * 构建系统提示词
     */
    private fun buildSystemPrompt(): String {
        val sourceLangText = if (sourceLanguage == "自动检测" || sourceLanguage.isEmpty()) {
            "自动检测源语言"
        } else {
            "源语言为$sourceLanguage"
        }
        
        val additionalInstructions = customPrompt.trim().takeIf { it.isNotEmpty() }?.let {
            "\n额外翻译要求：\n$it\n"
        }.orEmpty()

        return """
你是一个专业的字幕翻译助手。$sourceLangText，请将用户提供的字幕文本翻译成$targetLanguage。
$additionalInstructions

用户会提供最多 300 个字幕时间段，格式为：[编号] 内容。一个编号对应一个字幕时间段，在请求中只占一行；即使该时间段的字幕内容原本包含多行，也必须视为同一条字幕。
原字幕内容中的换行会编码为字面量 \n；请将其视为同一条字幕内容的一部分，并在译文中保留为 \n。若有后续消息，它们属于同一份字幕，必须延续此前上下文。

请按以下要求处理：
1. 只返回翻译结果，不要添加任何解释或其他内容
2. 保持原有的编号格式，每个字幕时间段的翻译结果占一行
3. 格式为：[编号] 翻译后的内容
4. 确保返回与当前请求数量一致的翻译结果，顺序与输入一致

示例输入：
[1] Hello
[2] How are you?

示例输出：
[1] 你好
[2] 你好吗？
""".trimIndent()
    }

    /**
     * 解析翻译结果
     * 从 AI 返回的文本中提取翻译结果，保持原有顺序
     */
    private fun parseTranslationResult(content: String, expectedCount: Int): List<String> {
        val result = MutableList<String?>(expectedCount) { null }
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .toList()

        var currentIndex: Int? = null
        for (line in lines) {
            // 匹配 [数字] 格式
            val match = Regex("""^\[(\d+)\]\s*(.*)$""").find(line)
            if (match != null) {
                val index = match.groupValues[1].toIntOrNull()
                val text = match.groupValues[2].trim()
                if (index == null || index !in 1..expectedCount || text.isBlank()) {
                    throw IOException("翻译结果编号或内容无效：$line")
                }
                if (result[index - 1] != null) throw IOException("翻译结果包含重复编号：[$index]")
                result[index - 1] = decodeSubtitleText(text)
                currentIndex = index - 1
            } else {
                val continuationIndex = currentIndex
                    ?: throw IOException("翻译结果格式无效，缺少编号：$line")
                result[continuationIndex] = "${result[continuationIndex]}\n${decodeSubtitleText(line)}"
            }
        }
        if (result.any { it == null }) throw IOException("翻译结果不完整，请重试")
        return result.map { it!! }
    }

    /** Encodes line breaks inside one subtitle time range without creating extra numbered rows. */
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

    /**
     * 翻译单个文本（备用方法）
     */
    private fun translateSingleText(text: String): String {
        val sourceLangText = if (sourceLanguage == "自动检测" || sourceLanguage.isEmpty()) {
            "自动检测源语言"
        } else {
            "源语言为$sourceLanguage"
        }
        
        val jsonBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一个专业的字幕翻译助手。$sourceLangText，请将用户提供的字幕文本翻译成$targetLanguage。只返回翻译结果，不要添加任何解释或其他内容。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "请翻译以下字幕文本：\n$text")
                })
            })
            put("stream", false)
            if (provider != AiProviderConfig.DEEPSEEK) {
                put("temperature", 0.3)
            }
            put("max_tokens", 2048)
        }

        val mediaType = "application/json".toMediaType()
        val body = jsonBody.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "未知错误"
                throw IOException("API 请求失败：${response.code} - $errorBody")
            }

            val responseBody = response.body?.string() ?: throw IOException("响应为空")
            val jsonResponse = JSONObject(responseBody)
            val choices = jsonResponse.getJSONArray("choices")

            if (choices.length() > 0) {
                val choice = choices.getJSONObject(0)
                val message = choice.getJSONObject("message")
                message.getString("content")
            } else {
                throw IOException("没有翻译结果")
            }
        }
    }
}
