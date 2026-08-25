package com.subtitleedit.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stateful facade used by screens and feature adapters to share one chat history safely. */
class ChatConversation(
    config: ChatBackendConfig,
    systemPrompt: String = "",
    private val tools: List<ChatBackend.ChatTool> = emptyList(),
    initialMessages: List<ChatBackend.ChatMessage> = emptyList()
) {
    private val backend = ChatBackend(config)
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
        backend.send(
            conversation = history.toList(),
            userContent = content,
            tools = tools,
            onEvent = onEvent,
            isCancelled = isCancelled
        ).also { result -> history += result.messages }
    }

    suspend fun snapshot(): List<ChatBackend.ChatMessage> = historyMutex.withLock { history.toList() }

    suspend fun clear() = historyMutex.withLock {
        history.removeAll { it.role != "system" }
    }

    fun cancel() = backend.cancel()
}
