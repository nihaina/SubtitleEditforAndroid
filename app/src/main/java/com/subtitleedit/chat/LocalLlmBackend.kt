package com.subtitleedit.chat

import android.content.Context
import com.subtitleedit.localllm.LocalChatMessage
import com.subtitleedit.localllm.LocalLlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.io.IOException

/** Chat adapter over the LMPlayground-style local llama.cpp service. */
class LocalLlmBackend(
    private val context: Context,
    private val config: ChatBackendConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeRequest: kotlinx.coroutines.Deferred<LocalLlmEngine.GenerationResult>? = null

    suspend fun send(
        conversation: List<ChatBackend.ChatMessage>,
        userContent: String,
        onEvent: (ChatBackend.Event) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ChatBackend.SendResult {
        require(userContent.isNotBlank()) { "消息不能为空" }
        if (config.localModelPath.isBlank()) throw IOException("未选择本地 LLM 模型")
        if (!LocalLlmEngine.isModelLoaded(context, config.localModelPath, config.localRepackEnabled)) {
            throw IOException("本地模型未加载，请先在 AI 设置中点击“加载”")
        }

        val requestMessages = buildList {
            conversation.forEach { add(LocalChatMessage(it.role, it.content)) }
            add(LocalChatMessage("user", userContent))
        }
        onEvent(
            ChatBackend.Event.Request(
                conversation + ChatBackend.ChatMessage("user", userContent),
                1
            )
        )
        val streamedRaw = StringBuilder()
        val emittedReasoning = StringBuilder()
        val emittedVisible = StringBuilder()
        val deferred = scope.async {
            LocalLlmEngine.generate(
                context = context,
                modelPath = config.localModelPath,
                repackEnabled = config.localRepackEnabled,
                contextSize = config.localContextSize,
                thinkingEnabled = config.thinkingEnabled,
                conversation = requestMessages,
                onDelta = { delta ->
                    streamedRaw.append(delta)
                    val parsed = parseThinking(streamedRaw.toString())
                    if (parsed.reasoning.length > emittedReasoning.length) {
                        val suffix = parsed.reasoning.substring(emittedReasoning.length)
                        emittedReasoning.append(suffix)
                        onEvent(ChatBackend.Event.ReasoningDelta(suffix))
                    }
                    if (parsed.visible.length > emittedVisible.length) {
                        val suffix = parsed.visible.substring(emittedVisible.length)
                        emittedVisible.append(suffix)
                        onEvent(ChatBackend.Event.TextDelta(suffix))
                    }
                },
                isCancelled = isCancelled,
            )
        }
        activeRequest = deferred
        return try {
            val generation = deferred.await()
            if (isCancelled()) throw CancellationException("对话已取消")
            val parsed = parseThinking(generation.text)
            ChatBackend.SendResult(
                text = parsed.visible,
                messages = listOf(
                    ChatBackend.ChatMessage("user", userContent),
                    ChatBackend.ChatMessage(
                        role = "assistant",
                        content = parsed.visible,
                        reasoningContent = parsed.reasoning,
                        outputTokens = generation.outputTokens,
                        generationMs = generation.generationMs
                    ),
                ),
                isComplete = true,
                outputTokens = generation.outputTokens,
                generationMs = generation.generationMs,
            )
        } finally {
            activeRequest = null
        }
    }

    /** Cancels the child request, which triggers LocalLlmEngine's binder cancel path. */
    fun cancel() {
        activeRequest?.cancel()
    }

    fun close() = scope.cancel()

    private fun parseThinking(text: String): LocalLlmResponseParser.Parsed =
        LocalLlmResponseParser.parse(text)
}
