package com.subtitleedit.chat

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * An OpenAI-compatible conversation backend. It owns request generation, stream decoding,
 * context trimming and the assistant -> tool -> assistant execution loop.
 */
class ChatBackend(
    private val config: ChatBackendConfig
) {
    data class ChatMessage(
        val role: String,
        val content: String,
        val reasoningContent: String = "",
        val toolCalls: List<ToolCall> = emptyList(),
        val toolCallId: String = "",
        val toolName: String = ""
    )

    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    data class ChatTool(
        val name: String,
        val description: String,
        val parameters: JSONObject,
        val execute: suspend (JSONObject) -> String = { "" }
    )

    sealed class Event {
        data class Request(val messages: List<ChatMessage>, val attempt: Int) : Event()
        data class ReasoningDelta(val text: String) : Event()
        data class TextDelta(val text: String) : Event()
        data class ToolCalled(val toolCall: ToolCall) : Event()
        data class ToolResult(val toolCall: ToolCall, val result: String) : Event()
        data class Retrying(val attempt: Int, val reason: String) : Event()
    }

    data class SendResult(
        val text: String,
        val messages: List<ChatMessage>,
        val isComplete: Boolean
    )

    private data class StreamingResponse(
        val visibleContent: String,
        val assistantContent: String,
        val reasoningContent: String,
        val toolCalls: List<ToolCall>,
        val isComplete: Boolean
    )

    private data class StreamDelta(
        val content: String = "",
        val reasoning: String = "",
        val toolCalls: List<StreamToolCallDelta> = emptyList()
    )

    private data class StreamToolCallDelta(
        val index: Int,
        val id: String = "",
        val name: String = "",
        val arguments: String = ""
    )

    private data class MutableToolCall(
        var id: String,
        var name: String,
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun toToolCall() = ToolCall(id, name, arguments.toString())
    }

    /**
     * Keeps the protocol definition and the local executor together. A tool result is always
     * returned to the model with its original tool_call_id; tools never produce a final answer.
     */
    private class ToolRegistry(tools: List<ChatTool>) {
        val definitions: List<ChatTool> = tools.toList()
        private val toolsByName = definitions.associateBy(ChatTool::name)

        init {
            require(definitions.none { it.name.isBlank() }) { "工具名称不能为空" }
            require(toolsByName.size == definitions.size) { "工具名称不能重复" }
        }

        suspend fun execute(call: ToolCall): String {
            val tool = toolsByName[call.name]
                ?: return toolError("未注册工具：${call.name}")
            val arguments = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }
                .getOrElse { return toolError("工具参数不是有效 JSON：${it.message}") }
            return try {
                tool.execute(arguments)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                toolError(error.message ?: "工具执行失败")
            }
        }
    }

    private class NonRetryableApiException(message: String) : IOException(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val sendMutex = Mutex()
    private val contextWindowTokens = config.contextWindowTokens.coerceIn(
        MIN_CONTEXT_WINDOW_TOKENS,
        MAX_CONTEXT_WINDOW_TOKENS
    )
    private val apiUrl = chatCompletionsUrl(config.baseUrl)

    @Volatile
    private var activeCall: Call? = null

    fun cancel() {
        activeCall?.cancel()
    }

    /** Sends a user turn and follows every requested local-tool continuation in this backend. */
    suspend fun send(
        conversation: List<ChatMessage>,
        userContent: String,
        tools: List<ChatTool> = emptyList(),
        onEvent: (Event) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): SendResult = sendMutex.withLock {
        withContext(Dispatchers.IO) {
            require(userContent.isNotBlank()) { "消息不能为空" }
            val toolRegistry = ToolRegistry(tools)
            var requestMessages = compactConversationForRequest(conversation, userContent) +
                ChatMessage(role = ROLE_USER, content = userContent)
            val appended = mutableListOf<ChatMessage>()
            appended += ChatMessage(role = ROLE_USER, content = userContent)

            repeat(MAX_TOOL_ROUNDS) { toolRound ->
                ensureNotCancelled(isCancelled)
                val response = requestWithRetry(
                    requestMessages = requestMessages,
                    tools = toolRegistry.definitions,
                    onEvent = onEvent,
                    isCancelled = isCancelled
                )
                val assistant = ChatMessage(
                    role = ROLE_ASSISTANT,
                    content = response.assistantContent,
                    reasoningContent = response.reasoningContent,
                    toolCalls = response.toolCalls
                )
                requestMessages = requestMessages + assistant
                appended += assistant

                if (response.toolCalls.isEmpty()) {
                    return@withContext SendResult(
                        text = response.visibleContent,
                        messages = appended,
                        isComplete = response.isComplete
                    )
                }

                response.toolCalls.forEach { onEvent(Event.ToolCalled(it)) }
                val toolMessages = response.toolCalls.map { call ->
                    val result = toolRegistry.execute(call)
                    onEvent(Event.ToolResult(call, result))
                    ChatMessage(
                        role = ROLE_TOOL,
                        content = result,
                        toolCallId = call.id,
                        toolName = call.name
                    )
                }
                requestMessages = requestMessages + toolMessages
                appended += toolMessages

                if (toolRound == MAX_TOOL_ROUNDS - 1) {
                    throw IOException("工具调用轮次超过上限 $MAX_TOOL_ROUNDS")
                }
            }
            error("工具调用流程未能结束")
        }
    }

    internal fun buildRequestBody(
        messages: List<ChatMessage>,
        tools: List<ChatTool> = emptyList()
    ): JSONObject = JSONObject().apply {
        put("model", config.model)
        put("messages", JSONArray().apply {
            messages.forEach { message -> put(message.toJson()) }
        })
        put("stream", true)
        if (apiUrl.toHttpUrlOrNull()?.host != "api.mistral.ai") {
            put("stream_options", JSONObject().put("include_usage", true))
        }
        addReasoningParameters(this)
        if (tools.isNotEmpty()) {
            put("tools", JSONArray().apply {
                tools.forEach { tool ->
                    put(JSONObject()
                        .put("type", "function")
                        .put("function", JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", tool.parameters)
                        )
                    )
                }
            })
        }
    }

    /** Keeps whole user turns and their tool results when a request must be shortened. */
    internal fun compactConversationForRequest(
        conversation: List<ChatMessage>,
        pendingUserContent: String
    ): List<ChatMessage> {
        val pending = ChatMessage(ROLE_USER, pendingUserContent)
        val completionReserve = maxOf(
            MIN_COMPLETION_RESERVE_TOKENS.toLong(),
            estimateTextTokens(pendingUserContent).toLong() * 3L / 2L
        ).coerceAtMost(Int.MAX_VALUE.toLong())
        val inputLimit = contextWindowTokens.toLong() - completionReserve
        val systemMessages = conversation.takeWhile { it.role == ROLE_SYSTEM }
        val baseTokens = estimateConversationTokens(systemMessages + pending)
        if (baseTokens.toLong() > inputLimit) {
            throw IOException(
                "当前消息预计需要 ${baseTokens + completionReserve} tokens，" +
                    "超过上下文上限 $contextWindowTokens"
            )
        }
        if (estimateConversationTokens(conversation + pending).toLong() <= inputLimit) {
            return conversation
        }

        val turns = mutableListOf<List<ChatMessage>>()
        var currentTurn = mutableListOf<ChatMessage>()
        conversation.drop(systemMessages.size).forEach { message ->
            if (message.role == ROLE_USER && currentTurn.isNotEmpty()) {
                turns += currentTurn
                currentTurn = mutableListOf()
            }
            currentTurn += message
        }
        if (currentTurn.isNotEmpty()) turns += currentTurn

        val keepTarget = baseTokens.toLong() + ((inputLimit - baseTokens) * CONTEXT_KEEP_RATIO)
        val selected = ArrayDeque<List<ChatMessage>>()
        for (index in turns.indices.reversed()) {
            val turn = turns[index]
            val candidate = systemMessages + turn + selected.flatten() + pending
            if (estimateConversationTokens(candidate).toLong() <= keepTarget) {
                selected.addFirst(turn)
            } else {
                if (selected.isEmpty() &&
                    estimateConversationTokens(systemMessages + turn + pending).toLong() <= inputLimit
                ) {
                    selected.addFirst(turn)
                }
                break
            }
        }
        return systemMessages + selected.flatten()
    }

    internal fun estimateConversationTokens(messages: List<ChatMessage>): Int {
        val estimate = 3L + messages.sumOf { message ->
            6L + estimateTextTokens(message.role) +
                estimateTextTokens(message.content) +
                estimateTextTokens(message.reasoningContent) +
                estimateTextTokens(message.toolCallId) +
                estimateTextTokens(message.toolName) +
                message.toolCalls.sumOf { call ->
                    estimateTextTokens(call.id) +
                        estimateTextTokens(call.name) +
                        estimateTextTokens(call.arguments)
                }
        }
        return estimate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    internal fun readStreamingContent(
        responseBody: ResponseBody,
        onCaptured: (String, Boolean) -> Unit = { _, _ -> },
        onReasoning: (String) -> Unit = {},
        onDelta: (String) -> Unit
    ): String = readStreamingResponse(responseBody, onCaptured, onReasoning, onDelta).visibleContent

    private suspend fun requestWithRetry(
        requestMessages: List<ChatMessage>,
        tools: List<ChatTool>,
        onEvent: (Event) -> Unit,
        isCancelled: () -> Boolean
    ): StreamingResponse {
        var lastError: IOException? = null
        repeat(MAX_RETRY_COUNT) { index ->
            ensureNotCancelled(isCancelled)
            val attempt = index + 1
            val request = Request.Builder()
                .url(apiUrl)
                .post(buildRequestBody(requestMessages, tools).toString()
                    .toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                // Avoid proxy compression/buffering that can turn an SSE response into one final chunk.
                .addHeader("Accept-Encoding", "identity")
                .addHeader("Cache-Control", "no-cache")
                .build()
            try {
                onEvent(Event.Request(requestMessages, attempt))
                val call = client.newCall(request)
                activeCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val error = IOException(formatApiError(response.code, response.body?.string().orEmpty()))
                        if (response.code !in RETRYABLE_HTTP_CODES && response.code !in 500..599) {
                            throw NonRetryableApiException(error.message ?: "API 请求失败")
                        }
                        lastError = error
                        if (index < MAX_RETRY_COUNT - 1) {
                            onEvent(Event.Retrying(attempt + 1, error.message.orEmpty()))
                            delay(retryAfterMs(response.header("Retry-After")) ?: (1_000L shl index))
                        }
                    } else {
                        val body = response.body ?: throw IOException("响应为空")
                        val streamed = readStreamingResponse(
                            responseBody = body,
                            onReasoning = { onEvent(Event.ReasoningDelta(it)) },
                            onDelta = { onEvent(Event.TextDelta(it)) }
                        )
                        if (!streamed.isComplete) throw IOException("流式响应在完成前断开")
                        if (streamed.visibleContent.isBlank() && streamed.toolCalls.isEmpty()) {
                            throw IOException("响应为空")
                        }
                        return streamed
                    }
                }
            } catch (error: NonRetryableApiException) {
                throw error
            } catch (error: IOException) {
                if (isCancelled()) throw CancellationException("对话已取消")
                lastError = error
                if (index < MAX_RETRY_COUNT - 1) {
                    onEvent(Event.Retrying(attempt + 1, error.message.orEmpty()))
                    delay(1_000L shl index)
                }
            } finally {
                activeCall = null
            }
        }
        throw lastError ?: IOException("请求失败")
    }

    private fun readStreamingResponse(
        responseBody: ResponseBody,
        onCaptured: (String, Boolean) -> Unit = { _, _ -> },
        onReasoning: (String) -> Unit = {},
        onDelta: (String) -> Unit = {}
    ): StreamingResponse {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val rawResponse = StringBuilder()
        val callsByIndex = linkedMapOf<Int, MutableToolCall>()
        var isSse = false
        var isDone = false
        var completed = false
        val pendingData = StringBuilder()

        fun consumeEvent() {
            if (pendingData.isEmpty()) return
            val data = pendingData.toString()
            pendingData.setLength(0)
            if (data.trim() == "[DONE]") {
                isDone = true
                return
            }
            val delta = extractStreamDelta(data)
            if (delta.reasoning.isNotEmpty()) {
                reasoning.append(delta.reasoning)
                onReasoning(delta.reasoning)
            }
            if (delta.content.isNotEmpty()) {
                text.append(delta.content)
                onDelta(delta.content)
            }
            delta.toolCalls.forEach { deltaCall ->
                val call = callsByIndex.getOrPut(deltaCall.index) {
                    MutableToolCall("stream-tool-${deltaCall.index}", deltaCall.name)
                }
                if (deltaCall.id.isNotBlank()) call.id = deltaCall.id
                if (deltaCall.name.isNotBlank()) call.name = deltaCall.name
                call.arguments.append(deltaCall.arguments)
            }
        }

        var captured = ""
        try {
            responseBody.source().use { source ->
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) {
                        consumeEvent()
                        if (isDone) break
                    } else if (line.startsWith("data:")) {
                        isSse = true
                        val lineData = line.removePrefix("data:").trimStart()
                        if (pendingData.isNotEmpty()) pendingData.append('\n')
                        pendingData.append(lineData)
                    } else if (!isSse) {
                        if (rawResponse.isNotEmpty()) rawResponse.append('\n')
                        rawResponse.append(line)
                    }
                }
            }
            if (!isDone) consumeEvent()
            val toolCalls = callsByIndex.values.map(MutableToolCall::toToolCall)
            val response = if (isSse) {
                val assistantContent = text.toString()
                StreamingResponse(
                    visibleContent = assistantContent,
                    assistantContent = assistantContent,
                    reasoningContent = reasoning.toString(),
                    toolCalls = toolCalls,
                    isComplete = isDone
                )
            } else {
                parseRegularResponse(rawResponse.toString())
            }
            captured = response.visibleContent
            completed = response.isComplete
            return response
        } finally {
            onCaptured(captured, completed)
        }
    }

    private fun parseRegularResponse(raw: String): StreamingResponse {
        val response = runCatching { JSONObject(raw) }
            .getOrElse { throw IOException("响应格式无效", it) }
        response.optJSONObject("error")?.let { error ->
            throw IOException(error.optString("message").ifBlank { "AI 服务返回错误" })
        }
        val message = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: throw IOException("响应中没有消息内容")
        val calls = buildList {
            message.optJSONArray("tool_calls")?.let { array ->
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val function = item.optJSONObject("function")
                    add(ToolCall(
                        id = item.optString("id").ifBlank { "response-tool-$index" },
                        name = function?.optString("name").orEmpty(),
                        arguments = function?.optString("arguments").orEmpty()
                    ))
                }
            }
        }
        val content = jsonContentToText(message.opt("content"))
        return StreamingResponse(
            visibleContent = content,
            assistantContent = content,
            reasoningContent = jsonContentToText(message.opt("reasoning_content"))
                .ifBlank { jsonContentToText(message.opt("reasoning")) },
            toolCalls = calls,
            isComplete = true
        )
    }

    private fun ChatMessage.toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        when (role) {
            ROLE_ASSISTANT -> {
                if (reasoningContent.isNotBlank()) put("reasoning_content", reasoningContent)
                put("content", content)
                if (toolCalls.isNotEmpty()) {
                    put("tool_calls", JSONArray().apply {
                        toolCalls.forEach { call ->
                            put(JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put("function", JSONObject()
                                    .put("name", call.name)
                                    .put("arguments", normalizeToolArguments(call.arguments))
                                )
                            )
                        }
                    })
                }
            }
            ROLE_TOOL -> {
                if (toolCallId.isNotBlank()) put("tool_call_id", toolCallId)
                if (toolName.isNotBlank()) put("name", toolName)
                put("content", content)
            }
            else -> put("content", content)
        }
    }

    private fun addReasoningParameters(body: JSONObject) {
        val level = config.reasoningLevel
        if (level == ChatReasoningLevel.AUTO && !config.modelSupportsReasoning) return
        when (apiUrl.toHttpUrlOrNull()?.host.orEmpty().lowercase()) {
            "openrouter.ai" -> body.put("reasoning", JSONObject().apply {
                if (level == ChatReasoningLevel.AUTO) put("enabled", true)
                else put("effort", if (level == ChatReasoningLevel.OFF) "none" else level.effort)
            })
            "dashscope.aliyuncs.com", "api.siliconflow.cn", "aiping.cn" ->
                body.put("enable_thinking", level != ChatReasoningLevel.OFF)
            "api.deepseek.com" -> body.put("thinking", JSONObject().put(
                "type", if (level == ChatReasoningLevel.OFF) "disabled" else "enabled"
            ))
            "api.mistral.ai" -> Unit
            else -> if (level != ChatReasoningLevel.AUTO) {
                body.put("reasoning_effort", if (level == ChatReasoningLevel.OFF) "low" else level.effort)
            }
        }
    }

    private fun extractStreamDelta(data: String): StreamDelta {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<StreamToolCallDelta>()
        data.trim().split('\n').map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val event = runCatching { JSONObject(line) }
                .getOrElse { throw IOException("流式响应格式无效", it) }
            event.optJSONObject("error")?.let { error ->
                throw IOException(error.optString("message").ifBlank { "AI 服务返回错误" })
            }
            val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: return@forEach
            val delta = choice.optJSONObject("delta")
            val message = choice.optJSONObject("message")
            val rawContent = delta?.opt("content") ?: message?.opt("content")
            content.append(jsonContentToText(rawContent))
            val rawReasoning = delta?.opt("reasoning_content") ?: delta?.opt("reasoning")
                ?: message?.opt("reasoning_content") ?: message?.opt("reasoning")
            reasoning.append(jsonContentToText(rawReasoning).ifBlank { jsonContentToReasoning(rawContent) })
            (delta?.optJSONArray("tool_calls") ?: message?.optJSONArray("tool_calls"))?.let { calls ->
                for (index in 0 until calls.length()) {
                    val call = calls.optJSONObject(index) ?: continue
                    val function = call.optJSONObject("function")
                    toolCalls += StreamToolCallDelta(
                        index = call.optInt("index", index),
                        id = call.optString("id"),
                        name = function?.optString("name").orEmpty(),
                        arguments = function?.optString("arguments").orEmpty()
                    )
                }
            }
        }
        return StreamDelta(content.toString(), reasoning.toString(), toolCalls)
    }

    private fun jsonContentToText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index) ?: continue
                if (part.optString("type") !in setOf("thinking", "reasoning")) append(part.optString("text"))
            }
        }
        else -> ""
    }

    private fun jsonContentToReasoning(content: Any?): String = when (content) {
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index) ?: continue
                if (part.optString("type") in setOf("thinking", "reasoning")) {
                    append(reasoningPartText(part.opt("thinking")))
                    append(reasoningPartText(part.opt("reasoning")))
                    append(part.optString("text"))
                }
            }
        }
        else -> ""
    }

    private fun reasoningPartText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString { for (index in 0 until value.length()) append(reasoningPartText(value.opt(index))) }
        is JSONObject -> value.optString("text") + reasoningPartText(value.opt("thinking")) +
            reasoningPartText(value.opt("reasoning"))
        else -> ""
    }

    private fun normalizeToolArguments(arguments: String): String =
        runCatching { JSONObject(arguments.trim().ifEmpty { "{}" }).toString() }.getOrDefault("{}")

    private fun formatApiError(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: body.take(2_000).ifBlank { "未知错误" }
        return "API 请求失败：$code - $detail"
    }

    private fun estimateTextTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return ((text.toByteArray(StandardCharsets.UTF_8).size.toLong() + 1L) / 2L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun chatCompletionsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotEmpty()) { "请先填写 API 请求地址" }
        return if (normalized.endsWith("/chat/completions", ignoreCase = true)) normalized
        else "$normalized/chat/completions"
    }

    private fun ensureNotCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("对话已取消")
    }

    private fun retryAfterMs(value: String?): Long? = value?.toLongOrNull()?.times(1_000L)

    companion object {
        private fun toolError(message: String) = JSONObject()
            .put("error", message.take(4_000))
            .toString()

        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val ROLE_TOOL = "tool"
        private const val MAX_RETRY_COUNT = 3
        private const val MAX_TOOL_ROUNDS = 8
        private const val MIN_CONTEXT_WINDOW_TOKENS = 4 * 1024
        private const val MAX_CONTEXT_WINDOW_TOKENS = 2 * 1024 * 1024
        private const val MIN_COMPLETION_RESERVE_TOKENS = 1_024
        private const val CONTEXT_KEEP_RATIO = 0.9
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val RETRYABLE_HTTP_CODES = setOf(408, 429)
    }
}

data class ChatBackendConfig(
    val providerId: String,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val contextWindowTokens: Int,
    val reasoningLevel: ChatReasoningLevel = ChatReasoningLevel.AUTO,
    val modelSupportsReasoning: Boolean = false
)

enum class ChatReasoningLevel(val effort: String) {
    OFF("none"),
    AUTO("auto"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max")
}
