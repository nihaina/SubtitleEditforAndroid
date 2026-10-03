package com.subtitleedit.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppToolScaffold

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
    val compactActions = LocalConfiguration.current.screenWidthDp < 360
    var overflowExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(scrollRevision, messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    AppToolScaffold(
        title = stringResource(R.string.chat_title),
        onBack = onNavigateBack,
        scrollable = false,
        imePadding = true,
        titleContent = {
            Column {
                Text(stringResource(R.string.chat_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            contentDescription = stringResource(R.string.chat_history),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                    if (compactActions) {
                        Box {
                            IconButton(onClick = { overflowExpanded = true }) {
                                Image(
                                    painter = painterResource(R.drawable.ic_more_vertical),
                                    contentDescription = stringResource(R.string.chat_more_options),
                                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_clear_conversation)) },
                                    onClick = {
                                        overflowExpanded = false
                                        onClearConversation()
                                    }
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = onClearConversation) {
                            Image(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.chat_clear_conversation),
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.error)
                            )
                        }
                    }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputTextChange,
                        modifier = Modifier.weight(1f),
                        enabled = !isSending,
                        label = { Text(stringResource(R.string.chat_input_hint)) },
                        shape = MaterialTheme.shapes.small,
                        minLines = 1,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = { onSendOrStop() })
                    )
                    IconButton(onClick = onSendOrStop, modifier = Modifier.width(48.dp)) {
                        Image(
                            painter = painterResource(if (isSending) R.drawable.ic_stop else R.drawable.ic_send),
                            contentDescription = stringResource(
                                if (isSending) R.string.chat_stop_generation else R.string.chat_send_message
                            ),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Bottom
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
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.chat_history)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    items(historySessions, key = { it.id }) { session ->
                        val type = stringResource(
                            if (session.type == ChatHistoryStore.TYPE_TRANSLATION) {
                                R.string.chat_ai_translation
                            } else {
                                R.string.chat_ai_conversation
                            }
                        )
                        ListItem(
                            headlineContent = {
                                Text("$type · ${session.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            modifier = Modifier.clickable(
                                role = Role.Button,
                                onClick = { onOpenHistory(session.id) }
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onCloseHistory) { Text(stringResource(R.string.cancel)) }
            },
            dismissButton = {
                TextButton(onClick = onClearHistory) { Text(stringResource(R.string.chat_clear_history)) }
            }
        )
    }
}

@Composable
private fun ChatMessageRow(message: ChatUiMessage) {
    when (message) {
        is ChatUiMessage.User -> {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    modifier = Modifier.widthIn(min = 64.dp, max = 420.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
        is ChatUiMessage.Assistant -> AssistantMessage(message)
        is ChatUiMessage.Status -> Text(
            text = message.text,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun AssistantMessage(message: ChatUiMessage.Assistant) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.chat_ai_conversation),
            modifier = Modifier.padding(bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
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
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Column(
                    Modifier.heightIn(min = 42.dp).padding(10.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        stringResource(
                            if (message.reasoningExpanded) R.string.chat_collapse_reasoning
                            else R.string.chat_expand_reasoning
                        ),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (message.reasoningExpanded) {
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                message.reasoning,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
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
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                SelectionContainer {
                    Text(
                            text = message.text.ifBlank {
                                if (message.streaming) stringResource(R.string.chat_generating) else ""
                            },
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
