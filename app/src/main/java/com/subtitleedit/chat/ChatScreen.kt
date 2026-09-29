package com.subtitleedit.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(
    providerSubtitle: String,
    messages: List<ChatUiMessage>,
    listState: LazyListState,
    inputText: String,
    isSending: Boolean,
    historySessions: List<ChatHistoryStore.SessionSummary>,
    showHistoryDialog: Boolean,
    scrollRevision: Int,
    onInputTextChange: (String) -> Unit,
    onSendOrStop: () -> Unit,
    onNavigateBack: () -> Unit,
    onShowHistory: () -> Unit,
    onClearConversation: () -> Unit,
    onCloseHistory: () -> Unit,
    onOpenHistory: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    LaunchedEffect(scrollRevision, messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Image(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "返回",
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                        )
                    }
                },
                title = {
                    Column {
                        Text("AI 对话", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            providerSubtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onShowHistory) {
                        Image(
                            painter = painterResource(R.drawable.ic_history),
                            contentDescription = "会话记录",
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                    IconButton(onClick = onClearConversation) {
                        Image(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = "清空对话",
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputTextChange,
                        modifier = Modifier.weight(1f),
                        enabled = !isSending,
                        placeholder = { Text("输入消息") },
                        shape = RoundedCornerShape(8.dp),
                        minLines = 1,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = { onSendOrStop() })
                    )
                    IconButton(onClick = onSendOrStop) {
                        Image(
                            painter = painterResource(if (isSending) R.drawable.ic_stop else R.drawable.ic_send),
                            contentDescription = if (isSending) "停止生成" else "发送消息",
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    ) { contentPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(
                items = messages,
                key = { index, _ -> index }
            ) { _, message ->
                ChatMessageRow(message)
            }
        }
    }

    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = onCloseHistory,
            title = { Text("会话记录") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    items(historySessions, key = { it.id }) { session ->
                        ListItem(
                            headlineContent = {
                                Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    if (session.type == ChatHistoryStore.TYPE_TRANSLATION) "AI 翻译" else "AI 对话",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.clickable(
                                role = Role.Button,
                                onClick = { onOpenHistory(session.id) }
                            )
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onCloseHistory) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = onClearHistory) { Text("清空记录") }
            }
        )
    }
}

@Composable
private fun ChatMessageRow(message: ChatUiMessage) {
    when (message) {
        is ChatUiMessage.User -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    modifier = Modifier.widthIn(max = 440.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
        is ChatUiMessage.Assistant -> AssistantMessage(message)
        is ChatUiMessage.Status -> Text(
            text = message.text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun AssistantMessage(message: ChatUiMessage.Assistant) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "AI",
            modifier = Modifier.padding(bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
        if (message.reasoning.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clickable(
                        role = Role.Button,
                        onClick = { message.reasoningExpanded = !message.reasoningExpanded }
                    ),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        if (message.reasoningExpanded) "收起思考" else "展开思考",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (message.reasoningExpanded) {
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                message.reasoning,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                modifier = Modifier.widthIn(max = 440.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                SelectionContainer {
                    Text(
                        text = message.text.ifBlank { if (message.streaming) "正在生成..." else "" },
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

internal sealed class ChatUiMessage {
    data class User(val text: String, val messageId: String = "") : ChatUiMessage()

    class Assistant(
        text: String = "",
        reasoning: String = "",
        streaming: Boolean = true,
        reasoningExpanded: Boolean = false,
        val messageId: String = ""
    ) : ChatUiMessage() {
        var text by mutableStateOf(text)
        var reasoning by mutableStateOf(reasoning)
        var streaming by mutableStateOf(streaming)
        var reasoningExpanded by mutableStateOf(reasoningExpanded)
    }

    data class Status(val text: String) : ChatUiMessage()
}
