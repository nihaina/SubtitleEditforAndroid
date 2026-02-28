package com.subtitleedit.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 翻译器 - 使用硅基流动平台进行翻译
 */
class AiTranslator(
    private val apiKey: String,
    private val model: String,
    private val sourceLanguage: String,
    private val targetLanguage: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val apiUrl = "https://api.siliconflow.cn/v1/chat/completions"

    /**
     * 翻译字幕文本 - 批量翻译优化版本
     * @param texts 要翻译的文本列表
     * @param progressCallback 进度回调 (当前进度，总进度)
     * @param isCancelled 取消检查回调
     * @return 翻译后的文本列表
     */
    suspend fun translateTexts(
        texts: List<String>,
        progressCallback: ((Int, Int) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            // 批量翻译：将所有文本合并成一个大请求
            if (texts.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            if (isCancelled()) {
                return@withContext Result.failure(CancellationException("翻译已取消"))
            }

            // 使用批量翻译
            val translatedTexts = translateBatch(texts)
            progressCallback?.invoke(texts.size, texts.size)

            Result.success(translatedTexts)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 批量翻译文本
     * 将所有字幕文本合并成一个大请求，提高效率和节省 Token
     */
    private fun translateBatch(texts: List<String>): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (texts.size == 1) return listOf(translateSingleText(texts[0]))

        // 构建带索引的文本内容，方便后续解析
        val indexedContent = texts.mapIndexed { index, text ->
            "[${index + 1}] ${text}"
        }.joinToString("\n")

        val jsonBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", buildSystemPrompt(texts.size))
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", indexedContent)
                })
            })
            put("stream", false)
            put("temperature", 0.3)
            put("max_tokens", 4096)
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
                val content = message.getString("content")
                // 解析返回的翻译结果
                parseTranslationResult(content, texts.size)
            } else {
                throw IOException("没有翻译结果")
            }
        }
    }

    /**
     * 构建系统提示词
     */
    private fun buildSystemPrompt(count: Int): String {
        val sourceLangText = if (sourceLanguage == "自动检测" || sourceLanguage.isEmpty()) {
            "自动检测源语言"
        } else {
            "源语言为$sourceLanguage"
        }
        
        return """
你是一个专业的字幕翻译助手。$sourceLangText，请将用户提供的字幕文本翻译成$targetLanguage。

用户会提供 $count 条带编号的字幕文本，格式为：[编号] 内容

请按以下要求处理：
1. 只返回翻译结果，不要添加任何解释或其他内容
2. 保持原有的编号格式，每条翻译结果占一行
3. 格式为：[编号] 翻译后的内容
4. 确保返回 $count 条翻译结果，顺序与输入一致

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
        val result = mutableListOf<String>()
        val lines = content.split("\n").filter { it.isNotBlank() }

        for (line in lines) {
            // 匹配 [数字] 格式
            val match = Regex("""^\[(\d+)\]\s*(.*)$""").find(line)
            if (match != null) {
                val index = match.groupValues[1].toIntOrNull()
                val text = match.groupValues[2].trim()
                if (index != null && index in 1..expectedCount) {
                    // 确保列表足够大
                    while (result.size < index) {
                        result.add("")
                    }
                    result[index - 1] = text
                }
            } else if (result.size < expectedCount) {
                // 如果没有匹配到编号格式，但还需要结果，直接添加（可能是 AI 格式略有偏差）
                result.add(line.trim())
            }
        }

        // 如果结果数量不足，用空字符串填充
        while (result.size < expectedCount) {
            result.add("")
        }

        return result
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
            put("temperature", 0.3)
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

    /**
     * 取消标记
     */
    class CancellationException(message: String) : Exception(message)
}
