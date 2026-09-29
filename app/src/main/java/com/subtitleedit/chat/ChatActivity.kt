package com.subtitleedit.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A standalone chat screen backed entirely by [ChatConversation]. */
class ChatActivity : AppCompatActivity() {
    private lateinit var conversation: ChatConversation
    private val messages = mutableStateListOf<ChatUiMessage>()
    private val chatListState = LazyListState()
    private var messageText by mutableStateOf("")
    private var isSendingState by mutableStateOf(false)
    private var historySessions by mutableStateOf(emptyList<ChatHistoryStore.SessionSummary>())
    private var showHistoryDialog by mutableStateOf(false)
    private var scrollRevision by mutableIntStateOf(0)
    private var sendJob: Job? = null
    private lateinit var configuration: ChatLaunchConfiguration
    private lateinit var historyStore: ChatHistoryStore
    private var currentSessionId: String? = null
    private val streamHandler = Handler(Looper.getMainLooper())
    private val streamLock = Any()
    private val pendingText = StringBuilder()
    private val pendingReasoning = StringBuilder()
    private var streamFlushScheduled = false
    private var pendingAssistantCompletion: PendingAssistantCompletion? = null

    private data class PendingAssistantCompletion(
        val assistantIndex: Int,
        val finalText: String,
        val onFinished: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configuration = launchConfiguration(intent) ?: run {
            Toast.makeText(this, "对话配置已失效，请从 AI 设置重新打开", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        historyStore = ChatHistoryStore(this)
        conversation = newConversation()

        setContent {
            SubtitleEditComposeTheme {
                ChatScreen(
                    providerSubtitle = "${configuration.providerName} · ${configuration.backendConfig.model}",
                    messages = messages,
                    listState = chatListState,
                    inputText = messageText,
                    isSending = isSendingState,
                    historySessions = historySessions,
                    showHistoryDialog = showHistoryDialog,
                    scrollRevision = scrollRevision,
                    onInputTextChange = { messageText = it },
                    onSendOrStop = ::sendOrCancel,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() },
                    onShowHistory = ::showHistory,
                    onClearConversation = ::startNewConversation,
                    onCloseHistory = { showHistoryDialog = false },
                    onOpenHistory = { sessionId ->
                        showHistoryDialog = false
                        openHistory(sessionId)
                    },
                    onClearHistory = {
                        lifecycleScope.launch {
                            historyStore.clear()
                            showHistoryDialog = false
                            startNewConversation()
                        }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        streamHandler.removeCallbacksAndMessages(null)
        if (::conversation.isInitialized) conversation.cancel()
        if (isFinishing) ChatLaunchRegistry.remove(intent.getStringExtra(EXTRA_CONFIGURATION_ID))
        super.onDestroy()
    }

    private fun sendOrCancel() {
        if (sendJob != null) {
            conversation.cancel()
            sendJob?.cancel()
            return
        }
        if (hasPendingVisualStream()) {
            stopVisualStream()
            return
        }
        val content = messageText.trim()
        if (content.isBlank()) return
        append(ChatUiMessage.User(content))
        messageText = ""
        val assistantIndex = messages.size
        append(ChatUiMessage.Assistant())
        setSending(true)
        sendJob = lifecycleScope.launch {
            var waitingForVisualCompletion = false
            try {
                val result = conversation.sendUserMessage(content, onEvent = ::handleBackendEvent)
                finishAssistantResponse(assistantIndex, result.text) { setSending(false) }
                waitingForVisualCompletion = true
                saveCurrentConversation(content, result.messages)
            } catch (_: CancellationException) {
                clearPendingStreamUpdates()
                val assistant = messages.getOrNull(assistantIndex) as? ChatUiMessage.Assistant
                if (assistant != null) {
                    assistant.streaming = false
                }
            } catch (error: Exception) {
                clearPendingStreamUpdates()
                messages.removeAt(assistantIndex)
                append(ChatUiMessage.Status(error.message ?: "对话失败"))
            } finally {
                sendJob = null
                if (!waitingForVisualCompletion) setSending(false)
            }
        }
    }

    private fun handleBackendEvent(event: ChatBackend.Event) {
        when (event) {
            is ChatBackend.Event.TextDelta -> enqueueStreamUpdate(text = event.text)
            is ChatBackend.Event.ReasoningDelta -> enqueueStreamUpdate(reasoning = event.text)
            is ChatBackend.Event.ToolCalled -> runOnUiThread {
                append(ChatUiMessage.Status("调用工具：${event.toolCall.name}"))
            }
            is ChatBackend.Event.ToolResult -> runOnUiThread {
                append(ChatUiMessage.Status("工具已完成：${event.toolCall.name}"))
            }
            is ChatBackend.Event.Retrying -> runOnUiThread {
                append(ChatUiMessage.Status("连接重试 ${event.attempt}/3"))
            }
            is ChatBackend.Event.Request -> Unit
        }
    }

    private fun enqueueStreamUpdate(text: String = "", reasoning: String = "") {
        synchronized(streamLock) {
            pendingText.append(text)
            pendingReasoning.append(reasoning)
            if (streamFlushScheduled) return
            streamFlushScheduled = true
            streamHandler.postDelayed(::flushStreamUpdates, STREAM_FLUSH_MS)
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
            if (hasMoreText) {
                streamFlushScheduled = true
                streamHandler.postDelayed(::flushStreamUpdates, STREAM_FLUSH_MS)
            }
        }
        if (text.isNotEmpty() || reasoning.isNotEmpty()) {
            val index = messages.indexOfLast { it is ChatUiMessage.Assistant }
            val assistant = messages.getOrNull(index) as? ChatUiMessage.Assistant
            if (assistant != null) {
                assistant.text += text
                assistant.reasoning += reasoning
                if (isAtBottom()) scrollToBottom()
            }
        }
        if (!hasMoreText) completeAssistantResponseIfReady()
    }

    private fun clearPendingStreamUpdates() {
        synchronized(streamLock) {
            pendingText.setLength(0)
            pendingReasoning.setLength(0)
            streamFlushScheduled = false
            pendingAssistantCompletion = null
        }
        streamHandler.removeCallbacksAndMessages(null)
    }

    private fun finishAssistantResponse(
        assistantIndex: Int,
        finalText: String,
        onFinished: () -> Unit
    ) {
        val assistant = messages.getOrNull(assistantIndex) as? ChatUiMessage.Assistant ?: run {
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
            if (!streamFlushScheduled) {
                streamFlushScheduled = true
                streamHandler.post(::flushStreamUpdates)
            }
        }
    }

    private fun completeAssistantResponseIfReady() {
        val completion = synchronized(streamLock) {
            pendingAssistantCompletion.also { pendingAssistantCompletion = null }
        } ?: return
        val assistant = messages.getOrNull(completion.assistantIndex) as? ChatUiMessage.Assistant
        if (assistant != null) {
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
        val index = messages.indexOfLast { it is ChatUiMessage.Assistant }
        (messages.getOrNull(index) as? ChatUiMessage.Assistant)?.let { assistant ->
            assistant.streaming = false
        }
        setSending(false)
    }

    private fun startNewConversation() {
        sendJob?.cancel()
        clearPendingStreamUpdates()
        conversation.cancel()
        conversation = newConversation()
        currentSessionId = null
        messages.clear()
    }

    private fun showHistory() {
        lifecycleScope.launch {
            val sessions = historyStore.list()
            if (sessions.isEmpty()) {
                Toast.makeText(this@ChatActivity, "暂无会话记录", Toast.LENGTH_SHORT).show()
                return@launch
            }
            historySessions = sessions
            showHistoryDialog = true
        }
    }

    private fun openHistory(sessionId: String) {
        lifecycleScope.launch {
            val session = historyStore.load(sessionId) ?: return@launch
            openLoadedHistory(session)
        }
    }

    private fun openLoadedHistory(session: ChatHistoryStore.Session) {
        sendJob?.cancel()
        clearPendingStreamUpdates()
        conversation.cancel()
        currentSessionId = session.id.takeIf { session.type == ChatHistoryStore.TYPE_CHAT }
        val chatMessages = session.messages.filter { message ->
            message.role != "tool" &&
                !(message.role == "assistant" && message.content.isBlank() && message.toolCalls.isNotEmpty())
        }
        conversation = newConversation(
            initialMessages = if (session.type == ChatHistoryStore.TYPE_CHAT) session.messages else emptyList()
        )
        messages.clear()
        chatMessages.forEach { message ->
            when (message.role) {
                "user" -> messages += ChatUiMessage.User(message.content)
                "assistant" -> messages += ChatUiMessage.Assistant(
                    text = message.content,
                    reasoning = message.reasoningContent,
                    streaming = false
                )
            }
        }
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

    private fun append(message: ChatUiMessage) {
        messages += message
        scrollToBottom()
    }

    private fun scrollToBottom() {
        if (messages.isNotEmpty()) scrollRevision++
    }

    private fun isAtBottom(): Boolean {
        val layoutInfo = chatListState.layoutInfo
        return layoutInfo.totalItemsCount == 0 ||
            (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layoutInfo.totalItemsCount - 1
    }

    private fun setSending(sending: Boolean) {
        isSendingState = sending
    }

    companion object {
        private const val EXTRA_CONFIGURATION_ID = "chat.configuration_id"
        private const val STREAM_FLUSH_MS = 16L
        private const val STREAM_RENDER_CHARS_PER_FRAME = 24
        fun createIntent(context: Context, configuration: ChatLaunchConfiguration): Intent {
            val configurationId = ChatLaunchRegistry.put(configuration)
            return Intent(context, ChatActivity::class.java)
                .putExtra(EXTRA_CONFIGURATION_ID, configurationId)
        }

        private fun launchConfiguration(intent: Intent): ChatLaunchConfiguration? =
            ChatLaunchRegistry.get(intent.getStringExtra(EXTRA_CONFIGURATION_ID))
    }

    private fun newConversation(
        initialMessages: List<ChatBackend.ChatMessage> = emptyList()
    ) = ChatConversation(
        config = configuration.backendConfig,
        tools = ChatTools.create(applicationContext),
        initialMessages = initialMessages
    )
}

data class ChatLaunchConfiguration(
    val providerName: String,
    val backendConfig: ChatBackendConfig
)

private object ChatLaunchRegistry {
    private val configurations = ConcurrentHashMap<String, ChatLaunchConfiguration>()

    fun put(configuration: ChatLaunchConfiguration): String = UUID.randomUUID().toString().also {
        configurations[it] = configuration
    }

    fun get(id: String?): ChatLaunchConfiguration? = id?.let(configurations::get)

    fun remove(id: String?) {
        if (id != null) configurations.remove(id)
    }
}
