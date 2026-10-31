package com.subtitleedit.chat

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stateful facade used by screens and feature adapters to share one chat history safely. */
class ChatConversation(
    config: ChatBackendConfig,
    systemPrompt: String = "",
    private val tools: List<ChatBackend.ChatTool> = emptyList(),
    initialMessages: List<ChatBackend.ChatMessage> = emptyList(),
    context: Context? = null
) {
    private val cloudBackend = if (config.providerId == com.subtitleedit.util.AiProviderConfig.LOCAL) {
        null
    } else {
        ChatBackend(config)
    }
    private val localBackend = if (config.providerId == com.subtitleedit.util.AiProviderConfig.LOCAL) {
        requireNotNull(context) { "本地 LLM 会话需要 Android Context" }
            .let { LocalLlmBackend(it.applicationContext, config) }
    } else {
        null
    }
    private val historyMutex = Mutex()
    private val history = initialMessages.toMutableList().also { messages ->
        if (systemPrompt.isNotBlank() && messages.none { it.role == "system" }) {
            messages.add(0, ChatBackend.ChatMessage("system", systemPrompt))
        }
    }

    suspend fun sendUserMessage(
        content: String,
        onEvent: (ChatBackend.Event) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): ChatBackend.SendResult = historyMutex.withLock {
        val result = if (localBackend != null) {
            check(tools.isEmpty()) { "本地 LLM 暂不支持工具调用" }
            localBackend.send(
                conversation = history.toList(),
                userContent = content,
                onEvent = onEvent,
                isCancelled = isCancelled
            )
        } else {
            compactApiContextIfNeeded(
                userContent = content,
                isCancelled = isCancelled
            )
            cloudBackend!!.send(
                conversation = history.toList(),
                userContent = content,
                tools = tools,
                onEvent = onEvent,
                isCancelled = isCancelled
            )
        }
        history += result.messages
        result
    }

    /**
     * API providers do not have the local runtime's bounded context. Once the serialized request
     * would exceed the safety threshold, summarize the old conversation in a hidden request and
     * replace the in-memory request context with that summary plus the system prompt. The chat
     * history store is owned by the caller and is therefore unaffected by this compaction.
     */
    private suspend fun compactApiContextIfNeeded(
        userContent: String,
        isCancelled: () -> Boolean
    ) {
        val backend = cloudBackend ?: return
        val requestMessages = history + ChatBackend.ChatMessage(role = ROLE_USER, content = userContent)
        if (requestMessages.isEmpty() || estimateRequestSize(requestMessages) <= API_CONTEXT_COMPACTION_THRESHOLD) {
            return
        }
        if (history.none { it.role != ROLE_SYSTEM }) return

        val summaryResult = backend.send(
            conversation = history.toList(),
            userContent = API_CONTEXT_SUMMARY_PROMPT,
            // Summary generation is an internal maintenance request. Tool calls would add
            // protocol messages to the compacted context and make the summary non-deterministic.
            tools = emptyList(),
            onEvent = {},
            isCancelled = isCancelled
        )
        val summary = summaryResult.text
            .replace(Regex("\\s+"), " ")
            .trim()
        require(summary.isNotBlank()) { "API 上下文摘要为空" }

        val systemMessages = history.filter { it.role == ROLE_SYSTEM }
        history.clear()
        history += systemMessages
        history += ChatBackend.ChatMessage(
            role = ROLE_ASSISTANT,
            content = "此前对话上下文摘要：$summary"
        )
    }

    private fun estimateRequestSize(messages: List<ChatBackend.ChatMessage>): Int =
        cloudBackend?.buildRequestBody(messages, tools)?.toString()?.length ?: 0

    suspend fun snapshot(): List<ChatBackend.ChatMessage> = historyMutex.withLock { history.toList() }

    /** Adds a user turn and its partial assistant reply when generation was interrupted. */
    suspend fun appendInterruptedTurn(
        userContent: String,
        partialAssistantContent: String,
        partialReasoningContent: String = ""
    ) = historyMutex.withLock {
        history += ChatBackend.ChatMessage(role = "user", content = userContent)
        history += ChatBackend.ChatMessage(
            role = "assistant",
            content = partialAssistantContent,
            reasoningContent = partialReasoningContent
        )
    }

    suspend fun clear() = historyMutex.withLock {
        history.removeAll { it.role != "system" }
    }

    /** Keeps the system prompt and only the most recent user exchange for local context. */
    suspend fun retainLastExchange() = historyMutex.withLock {
        retainLastExchangeLocked(history)
    }

    private fun retainLastExchangeLocked(messages: MutableList<ChatBackend.ChatMessage>) {
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        if (lastUserIndex < 0) {
            messages.removeAll { it.role != "system" }
            return
        }
        val retained = buildList {
            addAll(messages.filter { it.role == "system" })
            addAll(messages.subList(lastUserIndex, messages.size).filter { it.role != "system" })
        }
        messages.clear()
        messages += retained
    }

    fun cancel() {
        cloudBackend?.cancel()
        localBackend?.cancel()
    }

    companion object {
        /** Serialized request size at which cloud conversation context is compacted. */
        internal const val API_CONTEXT_COMPACTION_THRESHOLD = 900_000
        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val API_CONTEXT_SUMMARY_PROMPT =
            "请将此前完整对话上下文压缩为一段话，保留与当前任务相关的事实、约束、已完成内容、待处理内容和术语。只输出一段纯文本摘要，不要列标题，不要解释。"
    }
}
