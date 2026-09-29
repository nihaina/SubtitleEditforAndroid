package com.subtitleedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.model.ArchiveConflictDialogFormatter
import com.subtitleedit.model.ArchiveConflictDialogModel

@Composable
internal fun ArchiveActionsDialog(
    archiveName: String,
    actionLabels: List<String>,
    onDismiss: () -> Unit,
    onAction: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(archiveName) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                actionLabels.forEachIndexed { index, label ->
                    TextButton(
                        onClick = { onAction(index) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ArchiveConflictDialog(
    model: ArchiveConflictDialogModel,
    archiveInternal: Boolean,
    onDismiss: () -> Unit,
    onPolicySelected: (ArchiveConflictPolicyAction, Boolean) -> Unit
) {
    var applyToAll by remember { mutableStateOf(false) }
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp).heightIn(max = 640.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(stringResource(R.string.dialog_archive_conflict_text_01), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (archiveInternal) "压缩包内重复条目：${model.entryName}"
                        else "（${model.entryName}）已存在",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    ConflictMetadataBlock(
                        title = stringResource(R.string.activity_media_convert_text_01),
                        size = ArchiveConflictDialogFormatter.size(model.source.sizeBytes),
                        modified = ArchiveConflictDialogFormatter.modifiedTime(model.source.modifiedAtMillis)
                    )
                    ConflictMetadataBlock(
                        title = stringResource(R.string.activity_editor_text_01),
                        size = ArchiveConflictDialogFormatter.size(model.existing.sizeBytes),
                        modified = ArchiveConflictDialogFormatter.modifiedTime(model.existing.modifiedAtMillis)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { applyToAll = !applyToAll }
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                        Text(stringResource(R.string.dialog_archive_conflict_text_02), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ConflictActionButton(stringResource(R.string.cancel)) { onDismiss() }
                    ConflictActionButton(stringResource(R.string.activity_main_contentdescription_03)) {
                        onPolicySelected(ArchiveConflictPolicyAction.RENAME, applyToAll)
                    }
                    ConflictActionButton(stringResource(R.string.dialog_archive_conflict_text_03)) {
                        onPolicySelected(ArchiveConflictPolicyAction.SKIP, applyToAll)
                    }
                    ConflictActionButton(stringResource(R.string.dialog_archive_conflict_text_04)) {
                        onPolicySelected(ArchiveConflictPolicyAction.OVERWRITE, applyToAll)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictMetadataBlock(title: String, size: String, modified: String) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text("大小：$size", Modifier.padding(start = 16.dp, top = 2.dp), style = MaterialTheme.typography.bodyMedium)
        Text("最后修改：$modified", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConflictActionButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(label, maxLines = 1)
    }
}

internal enum class ArchiveConflictPolicyAction { RENAME, SKIP, OVERWRITE }

@Composable
internal fun ArchivePasswordDialog(
    archiveName: String,
    password: String,
    error: String?,
    passwords: List<String>?,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    onOpenPasswordBook: () -> Unit,
    onSelectPassword: (String) -> Unit,
    onSavePassword: () -> Unit,
    onClearPasswordBook: () -> Unit,
    onClosePasswordBook: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入压缩包密码") },
        text = {
            Column {
                Text(archiveName, style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.dialog_archive_password_hint_01)) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        isError = error != null,
                        supportingText = error?.let { message -> { Text(message) } },
                        trailingIcon = {
                            TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(if (passwordVisible) "隐藏" else "显示")
                            }
                        }
                    )
                    IconButton(onClick = onOpenPasswordBook) {
                        Icon(
                            painter = painterResource(R.drawable.ic_password_book),
                            contentDescription = stringResource(R.string.dialog_archive_password_contentdescription_01)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    if (passwords != null) {
        ArchivePasswordBookDialog(
            passwords = passwords,
            onSelectPassword = onSelectPassword,
            onSavePassword = onSavePassword,
            onClearPasswordBook = onClearPasswordBook,
            onDismiss = onClosePasswordBook
        )
    }
}

@Composable
internal fun ArchivePasswordBookDialog(
    passwords: List<String>,
    onSelectPassword: (String) -> Unit,
    onSavePassword: () -> Unit,
    onClearPasswordBook: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("密码本") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (passwords.isEmpty()) {
                    Text("密码本为空，可保存当前输入的密码。")
                } else {
                    passwords.forEachIndexed { index, password ->
                        TextButton(
                            onClick = { onSelectPassword(password) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "密码 ${index + 1}（${"•".repeat(password.length.coerceIn(1, 8))}）",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                TextButton(onClick = onSavePassword) {
                    Text("保存当前密码")
                }
                if (passwords.isNotEmpty()) {
                    TextButton(onClick = onClearPasswordBook) {
                        Text("清空密码本")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
internal fun ArchiveProgressDialog(
    state: ArchiveProgressDialogState,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = { Text(state.title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                state.percentLabel?.let {
                    Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (state.indeterminate) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                } else {
                    LinearProgressIndicator(
                        progress = { state.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                    )
                }
                state.leadingLabel?.let {
                    Text(it, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall)
                }
                state.processedLabel?.let {
                    Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = if (state.showCancel) {
            {
                TextButton(onClick = onCancel, enabled = state.cancelEnabled) {
                    Text("取消")
                }
            }
        } else {
            {}
        }
    )
}
