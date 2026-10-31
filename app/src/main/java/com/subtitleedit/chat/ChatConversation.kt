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
}
