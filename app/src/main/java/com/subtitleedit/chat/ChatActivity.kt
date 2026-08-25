package com.subtitleedit.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.subtitleedit.R
import com.subtitleedit.databinding.ActivityChatBinding
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A standalone chat screen backed entirely by [ChatConversation]. */
class ChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatBinding
    private lateinit var conversation: ChatConversation
    private val messages = mutableListOf<ChatUiMessage>()
    private val adapter = ChatMessageAdapter(messages)
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
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configuration = launchConfiguration(intent) ?: run {
            Toast.makeText(this, "对话配置已失效，请从 AI 翻译设置重新打开", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        historyStore = ChatHistoryStore(this)
        conversation = newConversation()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AI 对话"
        binding.toolbar.subtitle = "${configuration.providerName} · ${configuration.backendConfig.model}"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatList.itemAnimator = null
        binding.chatList.adapter = adapter
        binding.btnSend.setOnClickListener { sendOrCancel() }
        binding.etMessage.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendOrCancel()
                true
            } else {
                false
            }
        }
    }

    override fun onDestroy() {
        streamHandler.removeCallbacksAndMessages(null)
        if (::conversation.isInitialized) conversation.cancel()
        if (isFinishing) ChatLaunchRegistry.remove(intent.getStringExtra(EXTRA_CONFIGURATION_ID))
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chat, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_chat_history -> {
            showHistory()
            true
        }
        R.id.action_clear_chat -> {
            startNewConversation()
            true
        }
        else -> super.onOptionsItemSelected(item)
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
        val content = binding.etMessage.text?.toString()?.trim().orEmpty()
        if (content.isBlank()) return
        append(ChatUiMessage.User(content))
        binding.etMessage.setText("")
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
                    adapter.notifyItemChanged(assistantIndex)
                }
            } catch (error: Exception) {
                clearPendingStreamUpdates()
                messages.removeAt(assistantIndex)
                adapter.notifyItemRemoved(assistantIndex)
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
                adapter.notifyItemChanged(index)
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
            val changed = assistant.text != completion.finalText || assistant.streaming
            assistant.text = completion.finalText
            assistant.streaming = false
            if (changed) adapter.notifyItemChanged(completion.assistantIndex)
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
            adapter.notifyItemChanged(index)
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
        adapter.notifyDataSetChanged()
    }

    private fun showHistory() {
        lifecycleScope.launch {
            val sessions = historyStore.list()
            if (sessions.isEmpty()) {
                Toast.makeText(this@ChatActivity, "暂无会话记录", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = sessions.map { session ->
                val type = if (session.type == ChatHistoryStore.TYPE_TRANSLATION) "AI 翻译" else "AI 对话"
                "$type · ${session.title}"
            }.toTypedArray()
            AlertDialog.Builder(this@ChatActivity)
                .setTitle("会话记录")
                .setItems(labels) { _, which -> openHistory(sessions[which].id) }
                .setNegativeButton("取消", null)
                .setNeutralButton("清空记录") { _, _ ->
                    lifecycleScope.launch {
                        historyStore.clear()
                        startNewConversation()
                    }
                }
                .show()
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
        adapter.notifyDataSetChanged()
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
        adapter.notifyItemInserted(messages.lastIndex)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        if (adapter.itemCount <= 0) return
        binding.chatList.post { binding.chatList.scrollToPosition(adapter.itemCount - 1) }
    }

    private fun isAtBottom(): Boolean = !binding.chatList.canScrollVertically(1)

    private fun setSending(sending: Boolean) {
        binding.btnSend.setImageResource(if (sending) R.drawable.ic_stop else R.drawable.ic_send)
        binding.btnSend.contentDescription = if (sending) "停止生成" else "发送消息"
        binding.etMessage.isEnabled = !sending
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
