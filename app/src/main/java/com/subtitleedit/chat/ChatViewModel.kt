package com.subtitleedit.chat

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.subtitleedit.AppViewModel
import com.subtitleedit.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class ChatUiState(
    val providerSubtitle: String = "",
    val messages: List<ChatUiMessage> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val historySessions: List<ChatHistoryStore.SessionSummary> = emptyList(),
    val showHistoryDialog: Boolean = false,
    val scrollRevision: Int = 0
)

/**
 * Owns the chat conversation, the in-flight send job and the throttled stream
 * rendering so a configuration change does not cancel a running request.
 *
 * [ChatUiMessage.Assistant] keeps its snapshot-state fields; they are only
 * mutated on the main thread (stream flushes run on [Dispatchers.Main]).
 */
internal class ChatViewModel(
    application: Application
) : AppViewModel<ChatUiState, Nothing>(application, ChatUiState()) {
    private companion object {
        const val STREAM_FLUSH_MS = 16L
        const val STREAM_RENDER_CHARS_PER_FRAME = 24
        const val MAX_RETRY_ATTEMPTS = 3
    }

    private data class PendingAssistantCompletion(
        val assistantIndex: Int,
        val finalText: String,
        val onFinished: () -> Unit
    )

    private val historyStore = ChatHistoryStore(application)
    private var configurationId: String? = null
    private var configuration: ChatLaunchConfiguration? = null
    private var conversation: ChatConversation? = null
    private var currentSessionId: String? = null
    private var sendJob: Job? = null

    /** Last list position reported by the screen; streamed text only follows when at the bottom. */
    @Volatile
    private var listAtBottom = true

    private val streamLock = Any()
    private val pendingText = StringBuilder()
    private val pendingReasoning = StringBuilder()
    private var streamFlushScheduled = false
    private var streamFlushJob: Job? = null
    private var pendingAssistantCompletion: PendingAssistantCompletion? = null

    /**
     * Reads the launch configuration once. Returns false when it is no longer
     * available (e.g. after process death) and the page should close.
     */
    fun initialize(configurationId: String?): Boolean {
        if (configuration != null) return true
        val config = ChatLaunchRegistry.get(configurationId) ?: run {
            toast(R.string.chat_configuration_expired, duration = Toast.LENGTH_LONG)
            return false
        }
        this.configurationId = configurationId
        configuration = config
        conversation = newConversation(config)
        setState {
            copy(
                providerSubtitle = string(
                    R.string.chat_provider_subtitle,
                    config.providerName,
                    config.backendConfig.model
                )
            )
        }
        return true
    }

    fun setInputText(text: String) = setState { copy(inputText = text) }

    fun setListAtBottom(atBottom: Boolean) {
        listAtBottom = atBottom
    }

    fun sendOrCancel() {
        val conversation = conversation ?: return
        if (sendJob != null) {
            conversation.cancel()
            sendJob?.cancel()
            return
        }
        if (hasPendingVisualStream()) {
            stopVisualStream()
            return
        }
        val content = currentState.inputText.trim()
        if (content.isBlank()) return
        append(ChatUiMessage.User(content))
        setState { copy(inputText = "") }
        val assistantIndex = currentState.messages.size
        append(ChatUiMessage.Assistant())
        setSending(true)
        sendJob = viewModelScope.launch {
            var waitingForVisualCompletion = false
            try {
                val result = conversation.sendUserMessage(content, onEvent = ::handleBackendEvent)
                finishAssistantResponse(assistantIndex, result.text) { setSending(false) }
                waitingForVisualCompletion = true
                saveCurrentConversation(content, result.messages)
            } catch (_: CancellationException) {
                clearPendingStreamUpdates()
                assistantAt(assistantIndex)?.streaming = false
                setSending(false)
            } catch (error: Exception) {
                clearPendingStreamUpdates()
                setState { copy(messages = messages.filterIndexed { index, _ -> index != assistantIndex }) }
                append(ChatUiMessage.Status(error.message ?: string(R.string.chat_failed_default)))
                setSending(false)
            } finally {
                sendJob = null
                if (!waitingForVisualCompletion) setSending(false)
            }
        }
    }

    fun startNewConversation() {
        val config = configuration ?: return
        sendJob?.cancel()
        clearPendingStreamUpdates()
        conversation?.cancel()
        conversation = newConversation(config)
        currentSessionId = null
        setState { copy(messages = emptyList()) }
        setSending(false)
    }

    fun showHistory() {
        viewModelScope.launch {
            val sessions = historyStore.list()
            if (sessions.isEmpty()) {
                toast(R.string.chat_history_empty)
                return@launch
            }
            setState { copy(historySessions = sessions, showHistoryDialog = true) }
        }
    }

    fun closeHistory() = setState { copy(showHistoryDialog = false) }

    fun openHistory(sessionId: String) {
        closeHistory()
        viewModelScope.launch {
            val session = historyStore.load(sessionId) ?: return@launch
            openLoadedHistory(session)
        }
    }

    fun clearHistory() {
        closeHistory()
        viewModelScope.launch {
            historyStore.clear()
            startNewConversation()
        }
    }

    private fun handleBackendEvent(event: ChatBackend.Event) {
        // Called from the backend's IO thread; setState is thread-safe.
        when (event) {
            is ChatBackend.Event.TextDelta -> enqueueStreamUpdate(text = event.text)
            is ChatBackend.Event.ReasoningDelta -> enqueueStreamUpdate(reasoning = event.text)
            is ChatBackend.Event.ToolCalled ->
                append(ChatUiMessage.Status(string(R.string.chat_status_tool_called, event.toolCall.name)))
            is ChatBackend.Event.ToolResult ->
                append(ChatUiMessage.Status(string(R.string.chat_status_tool_finished, event.toolCall.name)))
            is ChatBackend.Event.Retrying ->
                append(ChatUiMessage.Status(string(R.string.chat_status_retrying, event.attempt, MAX_RETRY_ATTEMPTS)))
            is ChatBackend.Event.Request -> Unit
        }
    }

    private fun enqueueStreamUpdate(text: String = "", reasoning: String = "") {
        synchronized(streamLock) {
            pendingText.append(text)
            pendingReasoning.append(reasoning)
            if (streamFlushScheduled) return
            scheduleFlushLocked(STREAM_FLUSH_MS)
        }
    }

    /** Must hold [streamLock]. Posts a flush to the main thread after [delayMs]. */
    private fun scheduleFlushLocked(delayMs: Long) {
        streamFlushScheduled = true
        streamFlushJob = viewModelScope.launch(Dispatchers.Main) {
            if (delayMs > 0) delay(delayMs)
            flushStreamUpdates()
        }
    }

    private fun flushStreamUpdates() {
        val text: String
        val reasoning: String
        val hasMoreText: Boolean
        synchronized(streamLock) {
            val charCount = minOf(pendingText.length, STREAM_RENDER_CHARS_PER_FRAME)
            text = pendingText.substring(0, charCount)
            pendingText.delete(0, charCount)
            reasoning = pendingReasoning.toString()
            pendingReasoning.setLength(0)
            streamFlushScheduled = false
            hasMoreText = pendingText.isNotEmpty()
            if (hasMoreText) scheduleFlushLocked(STREAM_FLUSH_MS)
        }
        if (text.isNotEmpty() || reasoning.isNotEmpty()) {
            val messages = currentState.messages
            val assistant = messages.getOrNull(messages.indexOfLast { it is ChatUiMessage.Assistant })
                as? ChatUiMessage.Assistant
            if (assistant != null) {
                assistant.text += text
                assistant.reasoning += reasoning
                if (listAtBottom) scrollToBottom()
            }
        }
        if (!hasMoreText) completeAssistantResponseIfReady()
    }

    private fun clearPendingStreamUpdates() {
        val job = synchronized(streamLock) {
            pendingText.setLength(0)
            pendingReasoning.setLength(0)
            streamFlushScheduled = false
            pendingAssistantCompletion = null
            streamFlushJob.also { streamFlushJob = null }
        }
        job?.cancel()
    }

    private fun finishAssistantResponse(
        assistantIndex: Int,
        finalText: String,
        onFinished: () -> Unit
    ) {
        val assistant = assistantAt(assistantIndex) ?: run {
            onFinished()
            return
        }
        synchronized(streamLock) {
            val renderedAndQueued = assistant.text + pendingText
            if (finalText.startsWith(renderedAndQueued)) {
                pendingText.append(finalText.removePrefix(renderedAndQueued))
            } else {
                pendingText.setLength(0)
                assistant.text = ""
                pendingText.append(finalText)
            }
            pendingAssistantCompletion = PendingAssistantCompletion(
                assistantIndex = assistantIndex,
                finalText = finalText,
                onFinished = onFinished
            )
            if (!streamFlushScheduled) scheduleFlushLocked(0L)
        }
    }

    private fun completeAssistantResponseIfReady() {
        val completion = synchronized(streamLock) {
            pendingAssistantCompletion.also { pendingAssistantCompletion = null }
        } ?: return
        assistantAt(completion.assistantIndex)?.let { assistant ->
            assistant.text = completion.finalText
            assistant.streaming = false
        }
        completion.onFinished()
    }

    private fun hasPendingVisualStream(): Boolean = synchronized(streamLock) {
        pendingAssistantCompletion != null
    }

    private fun stopVisualStream() {
        clearPendingStreamUpdates()
        val messages = currentState.messages
        (messages.getOrNull(messages.indexOfLast { it is ChatUiMessage.Assistant }) as? ChatUiMessage.Assistant)
            ?.streaming = false
        setSending(false)
    }

    private fun openLoadedHistory(session: ChatHistoryStore.Session) {
        val config = configuration ?: return
        sendJob?.cancel()
        clearPendingStreamUpdates()
        conversation?.cancel()
        setSending(false)
        currentSessionId = session.id.takeIf { session.type == ChatHistoryStore.TYPE_CHAT }
        val chatMessages = session.messages.filter { message ->
            message.role != "tool" &&
                !(message.role == "assistant" && message.content.isBlank() && message.toolCalls.isNotEmpty())
        }
        conversation = newConversation(
            config,
            initialMessages = if (session.type == ChatHistoryStore.TYPE_CHAT) session.messages else emptyList()
        )
        val uiMessages = chatMessages.mapNotNull { message ->
            when (message.role) {
                "user" -> ChatUiMessage.User(message.content)
                "assistant" -> ChatUiMessage.Assistant(
                    text = message.content,
                    reasoning = message.reasoningContent,
                    streaming = false
                )
                else -> null
            }
        }
        setState { copy(messages = uiMessages) }
        scrollToBottom()
    }

    private suspend fun saveCurrentConversation(
        firstUserText: String,
        newMessages: List<ChatBackend.ChatMessage>
    ) {
        val title = newMessages.firstOrNull { it.role == "user" }
            ?.content
            ?.lineSequence()
            ?.firstOrNull()
            .orEmpty()
        currentSessionId = historyStore.append(
            id = currentSessionId,
            title = title.ifBlank { firstUserText.lineSequence().firstOrNull().orEmpty() },
            type = ChatHistoryStore.TYPE_CHAT,
            messages = newMessages
        )
    }

    private fun assistantAt(index: Int): ChatUiMessage.Assistant? =
        currentState.messages.getOrNull(index) as? ChatUiMessage.Assistant

    private fun append(message: ChatUiMessage) {
        setState { copy(messages = messages + message, scrollRevision = scrollRevision + 1) }
    }

    private fun scrollToBottom() {
        setState { if (messages.isNotEmpty()) copy(scrollRevision = scrollRevision + 1) else this }
    }

    private fun setSending(sending: Boolean) = setState { copy(isSending = sending) }

    private fun newConversation(
        config: ChatLaunchConfiguration,
        initialMessages: List<ChatBackend.ChatMessage> = emptyList()
    ): ChatConversation {
        val isLocal = config.backendConfig.providerId == com.subtitleedit.util.AiProviderConfig.LOCAL
        val safeInitialMessages = if (isLocal) {
            initialMessages
                .filter { it.role == "system" || it.role == "user" || it.role == "assistant" }
                .map { it.copy(toolCalls = emptyList(), toolCallId = "", toolName = "") }
        } else {
            initialMessages
        }
        return ChatConversation(
            config = config.backendConfig,
            tools = if (isLocal) {
                emptyList()
            } else {
                ChatTools.create(app)
            },
            initialMessages = safeInitialMessages,
            context = app
        )
    }

    override fun onCleared() {
        // Page is finishing for real (not a configuration change).
        clearPendingStreamUpdates()
        sendJob?.cancel()
        conversation?.cancel()
        ChatLaunchRegistry.remove(configurationId)
        super.onCleared()
    }
}
