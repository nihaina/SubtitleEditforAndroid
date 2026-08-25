package com.subtitleedit.chat

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBackendTest {
    private fun backend(
        baseUrl: String = "https://example.com/v1",
        contextWindowTokens: Int = 256 * 1024
    ) = ChatBackend(
        ChatBackendConfig(
            providerId = "custom",
            apiKey = "test-key",
            model = "test-model",
            baseUrl = baseUrl,
            contextWindowTokens = contextWindowTokens
        )
    )

    @Test
    fun requestBody_preservesReasoningToolCallsAndToolResults() {
        val messages = listOf(
            ChatBackend.ChatMessage("user", "调用工具"),
            ChatBackend.ChatMessage(
                role = "assistant",
                content = "",
                reasoningContent = "先计算",
                toolCalls = listOf(ChatBackend.ToolCall("call-1", "calculate", "{\"a\":1}"))
            ),
            ChatBackend.ChatMessage(
                role = "tool",
                content = "1",
                toolCallId = "call-1",
                toolName = "calculate"
            )
        )

        val serialized = backend().buildRequestBody(messages).getJSONArray("messages")

        assertEquals("先计算", serialized.getJSONObject(1).getString("reasoning_content"))
        assertEquals("call-1", serialized.getJSONObject(1).getJSONArray("tool_calls")
            .getJSONObject(0).getString("id"))
        assertEquals("tool", serialized.getJSONObject(2).getString("role"))
        assertEquals("call-1", serialized.getJSONObject(2).getString("tool_call_id"))
    }

    @Test
    fun conversation_addsSystemPromptToLegacyHistoryOnce() = runBlocking {
        val conversation = ChatConversation(
            config = ChatBackendConfig(
                providerId = "custom",
                apiKey = "test-key",
                model = "test-model",
                baseUrl = "https://example.com/v1",
                contextWindowTokens = 256 * 1024
            ),
            systemPrompt = "Be precise.",
            initialMessages = listOf(ChatBackend.ChatMessage("user", "已有消息"))
        )

        val snapshot = conversation.snapshot()

        assertEquals(2, snapshot.size)
        assertEquals("system", snapshot.first().role)
        assertEquals("Be precise.", snapshot.first().content)
    }

    @Test
    fun toolCall_isExecutedAndContinuedInsideBackend() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{" +
                        "\"index\":0,\"id\":\"call-1\",\"function\":{" +
                        "\"name\":\"calculate\",\"arguments\":\"{\\\"a\\\":2,\\\"b\\\":3}\"}}]}}]}\n\n" +
                        "data: [DONE]\n\n"
                ))
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"结果是 5\"}}]}\n\n" +
                    "data: [DONE]\n\n"))
            val tool = ChatBackend.ChatTool(
                name = "calculate",
                description = "加法",
                parameters = JSONObject().put("type", "object"),
                execute = { args -> (args.getInt("a") + args.getInt("b")).toString() }
            )

            val result = backend(server.url("/v1").toString()).send(
                conversation = emptyList(),
                userContent = "2 + 3 等于多少？",
                tools = listOf(tool)
            )

            assertEquals("结果是 5", result.text)
            assertEquals(4, result.messages.size)
            assertEquals("tool", result.messages[2].role)
            assertEquals("5", result.messages[2].content)
            assertEquals(2, server.requestCount)
            val firstRequest = server.takeRequest()
            assertEquals("identity", firstRequest.getHeader("Accept-Encoding"))
            assertEquals("no-cache", firstRequest.getHeader("Cache-Control"))
            val firstRequestBody = JSONObject(firstRequest.body.readUtf8())
            assertEquals("2 + 3 等于多少？", firstRequestBody.getJSONArray("messages")
                .getJSONObject(0).getString("content"))
            val toolSchema = firstRequestBody.getJSONArray("tools").getJSONObject(0)
                .getJSONObject("function")
            assertEquals("calculate", toolSchema.getString("name"))
            assertEquals("object", toolSchema.getJSONObject("parameters").getString("type"))
            val secondRequest = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("tool", secondRequest.getJSONArray("messages")
                .getJSONObject(2).getString("role"))
            assertEquals("call-1", secondRequest.getJSONArray("messages")
                .getJSONObject(2).getString("tool_call_id"))
        }
    }

    @Test
    fun unknownTool_isReturnedToModelBeforeFinalAnswer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{" +
                        "\"index\":0,\"id\":\"call-missing\",\"function\":{" +
                        "\"name\":\"missing_tool\",\"arguments\":\"{}\"}}]}}]}\n\n" +
                        "data: [DONE]\n\n"
                ))
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"我无法使用该工具\"}}]}\n\n" +
                    "data: [DONE]\n\n"))

            val result = backend(server.url("/v1").toString()).send(emptyList(), "测试工具")

            assertEquals("我无法使用该工具", result.text)
            assertEquals(2, server.requestCount)
            server.takeRequest()
            val continuation = JSONObject(server.takeRequest().body.readUtf8())
            val toolMessage = continuation.getJSONArray("messages").getJSONObject(2)
            assertEquals("call-missing", toolMessage.getString("tool_call_id"))
            assertTrue(toolMessage.getString("content").contains("未注册工具：missing_tool"))
        }
    }

    @Test
    fun invalidToolArguments_areReturnedToModelBeforeFinalAnswer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{" +
                        "\"index\":0,\"id\":\"call-invalid\",\"function\":{" +
                        "\"name\":\"calculate\",\"arguments\":\"not-json\"}}]}}]}\n\n" +
                        "data: [DONE]\n\n"
                ))
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"请提供有效参数\"}}]}\n\n" +
                    "data: [DONE]\n\n"))
            val tool = ChatBackend.ChatTool(
                name = "calculate",
                description = "加法",
                parameters = JSONObject().put("type", "object")
            )

            val result = backend(server.url("/v1").toString())
                .send(emptyList(), "测试参数", tools = listOf(tool))

            assertEquals("请提供有效参数", result.text)
            server.takeRequest()
            val continuation = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(continuation.getJSONArray("messages").getJSONObject(2)
                .getString("content").contains("工具参数不是有效 JSON"))
        }
    }

    @Test
    fun contextCompaction_keepsCompleteToolTurn() {
        val oldTurn = listOf(
            ChatBackend.ChatMessage("user", "旧请求".repeat(3_000)),
            ChatBackend.ChatMessage(
                "assistant",
                "",
                toolCalls = listOf(ChatBackend.ToolCall("call-1", "calculate", "{}"))
            ),
            ChatBackend.ChatMessage("tool", "旧结果", toolCallId = "call-1", toolName = "calculate")
        )
        val recentTurn = listOf(
            ChatBackend.ChatMessage("user", "较新请求"),
            ChatBackend.ChatMessage("assistant", "较新结果")
        )

        val compacted = backend(contextWindowTokens = 8 * 1024)
            .compactConversationForRequest(oldTurn + recentTurn, "当前请求")

        assertFalse(compacted.any { it.toolCallId == "call-1" })
        assertEquals(recentTurn, compacted)
    }

    @Test
    fun oversizedCurrentMessage_isRejected() {
        assertThrows(IOException::class.java) {
            backend(contextWindowTokens = 4 * 1024)
                .compactConversationForRequest(emptyList(), "超长消息".repeat(4_000))
        }
    }

    @Test
    fun streamDoneEvent_stopsBeforeTrailingTransportContent() {
        val response = (
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n" +
                "data: [DONE]\n\n" +
                "data: invalid trailing content\n\n"
            ).toResponseBody("text/event-stream".toMediaType())
        var captured: Pair<String, Boolean>? = null

        val content = response.use {
            backend().readStreamingContent(
                responseBody = it,
                onCaptured = { text, complete -> captured = text to complete }
            ) {}
        }

        assertEquals("你好", content)
        assertEquals("你好" to true, captured)
    }

    @Test
    fun streamedEofWithoutDone_isMarkedIncomplete() {
        val response = "data: {\"choices\":[{\"delta\":{\"content\":\"未完成\"}}]}\n\n"
            .toResponseBody("text/event-stream".toMediaType())
        var complete = true

        response.use {
            backend().readStreamingContent(
                responseBody = it,
                onCaptured = { _, value -> complete = value }
            ) {}
        }

        assertFalse(complete)
    }
}
