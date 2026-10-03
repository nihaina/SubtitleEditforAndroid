package com.subtitleedit.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import com.subtitleedit.AppComposeActivity
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A standalone chat screen backed entirely by [ChatConversation]. */
class ChatActivity : AppComposeActivity() {
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!viewModel.initialize(intent.getStringExtra(EXTRA_CONFIGURATION_ID))) {
            finish()
            return
        }

        setContent {
            val state by viewModel.state.collectAsState()
            val chatListState = rememberLazyListState()
            LaunchedEffect(chatListState) {
                snapshotFlow { chatListState.isAtBottom() }.collect(viewModel::setListAtBottom)
            }
            SubtitleEditComposeTheme {
                ChatScreen(
                    providerSubtitle = state.providerSubtitle,
                    messages = state.messages,
                    listState = chatListState,
                    inputText = state.inputText,
                    isSending = state.isSending,
                    historySessions = state.historySessions,
                    showHistoryDialog = state.showHistoryDialog,
                    scrollRevision = state.scrollRevision,
                    onInputTextChange = viewModel::setInputText,
                    onSendOrStop = viewModel::sendOrCancel,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() },
                    onShowHistory = viewModel::showHistory,
                    onClearConversation = viewModel::startNewConversation,
                    onCloseHistory = viewModel::closeHistory,
                    onOpenHistory = viewModel::openHistory,
                    onClearHistory = viewModel::clearHistory
                )
            }
        }
    }

    companion object {
        private const val EXTRA_CONFIGURATION_ID = "chat.configuration_id"

        fun createIntent(context: Context, configuration: ChatLaunchConfiguration): Intent {
            val configurationId = ChatLaunchRegistry.put(configuration)
            return Intent(context, ChatActivity::class.java)
                .putExtra(EXTRA_CONFIGURATION_ID, configurationId)
        }
    }
}

private fun LazyListState.isAtBottom(): Boolean {
    val info = layoutInfo
    return info.totalItemsCount == 0 ||
        (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 1
}

data class ChatLaunchConfiguration(
    val providerName: String,
    val backendConfig: ChatBackendConfig
)

/** In-memory hand-off of launch configuration; entries are removed when the chat page finishes. */
internal object ChatLaunchRegistry {
    private val configurations = ConcurrentHashMap<String, ChatLaunchConfiguration>()

    fun put(configuration: ChatLaunchConfiguration): String = UUID.randomUUID().toString().also {
        configurations[it] = configuration
    }

    fun get(id: String?): ChatLaunchConfiguration? = id?.let(configurations::get)

    fun remove(id: String?) {
        if (id != null) configurations.remove(id)
    }
}
