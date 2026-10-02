package com.subtitleedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Plain blocking dialog used while an archive preview/test is running. */
@Composable
internal fun ArchiveBlockingProgressDialog(
    title: String,
    message: String
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {}
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
                modifier = Modifier
                    .padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 8.dp)
                    .heightIn(max = 640.dp)
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
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                        maxLines = 3
                    )
                    ConflictMetadataBlock(
                        title = stringResource(R.string.activity_media_convert_text_01),
                        size = ArchiveConflictDialogFormatter.size(model.source.sizeBytes),
                        modified = ArchiveConflictDialogFormatter.modifiedTime(model.source.modifiedAtMillis),
                        topPadding = 12.dp
                    )
                    ConflictMetadataBlock(
                        title = stringResource(R.string.activity_editor_text_01),
                        size = ArchiveConflictDialogFormatter.size(model.existing.sizeBytes),
                        modified = ArchiveConflictDialogFormatter.modifiedTime(model.existing.modifiedAtMillis),
                        topPadding = 10.dp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { applyToAll = !applyToAll }
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                        Text(stringResource(R.string.dialog_archive_conflict_text_02), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ConflictActionButton(stringResource(R.string.cancel), Modifier.weight(1f)) { onDismiss() }
                    ConflictActionButton(stringResource(R.string.activity_main_contentdescription_03), Modifier.weight(1f)) {
                        onPolicySelected(ArchiveConflictPolicyAction.RENAME, applyToAll)
                    }
                    ConflictActionButton(stringResource(R.string.dialog_archive_conflict_text_03), Modifier.weight(1f)) {
                        onPolicySelected(ArchiveConflictPolicyAction.SKIP, applyToAll)
                    }
                    ConflictActionButton(stringResource(R.string.dialog_archive_conflict_text_04), Modifier.weight(1f)) {
                        onPolicySelected(ArchiveConflictPolicyAction.OVERWRITE, applyToAll)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictMetadataBlock(title: String, size: String, modified: String, topPadding: androidx.compose.ui.unit.Dp) {
    Column(Modifier.fillMaxWidth().padding(top = topPadding)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text("大小：$size", Modifier.padding(start = 16.dp, top = 2.dp), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp))
        Text("最后修改：$modified", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp))
    }
}

@Composable
private fun ConflictActionButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.height(48.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        Text(label, maxLines = 1, fontSize = 14.sp)
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
    onCancelButton: () -> Unit,
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
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = error != null,
                        supportingText = error?.let { message -> { Text(message) } },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    painter = painterResource(
                                        if (passwordVisible) R.drawable.ic_visibility_off
                                        else R.drawable.ic_visibility
                                    ),
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                )
                            }
                        }
                    )
                    IconButton(
                        onClick = onOpenPasswordBook,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
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
            TextButton(onClick = onCancelButton) { Text("取消") }
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
            }
        },
        confirmButton = {
            TextButton(onClick = onSavePassword) { Text("保存当前密码") }
        },
        dismissButton = {
            Row {
                if (passwords.isNotEmpty()) {
                    TextButton(onClick = onClearPasswordBook) { Text("清空密码本") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
internal fun ArchiveProgressDialog(
    state: ArchiveProgressDialogState,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val secondaryTextColor = remember(context) {
        val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        try {
            Color(attributes.getColorStateList(0)?.defaultColor ?: android.graphics.Color.GRAY)
        } finally {
            attributes.recycle()
        }
    }
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
                    color = secondaryTextColor,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.MiddleEllipsis
                )
                state.percentLabel?.let {
                    Text(
                        it,
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryTextColor,
                        fontSize = 14.sp,
                        maxLines = 2
                    )
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
                    Text(
                        it,
                        Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryTextColor,
                        fontSize = 14.sp,
                        maxLines = 2
                    )
                }
                state.processedLabel?.let {
                    Text(
                        it,
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryTextColor,
                        fontSize = 14.sp,
                        maxLines = 2
                    )
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
