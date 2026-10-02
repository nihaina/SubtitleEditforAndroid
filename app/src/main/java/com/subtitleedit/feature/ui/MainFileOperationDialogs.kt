package com.subtitleedit.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import com.subtitleedit.util.ArchiveManager

internal sealed interface MainActivityDialogUi {
    data class Message(val title: String, val message: String) : MainActivityDialogUi
    data object PermissionDenied : MainActivityDialogUi
    data class Rename(val initialName: String) : MainActivityDialogUi
    data class DeleteConfirmation(val selectedCount: Int) : MainActivityDialogUi
}

internal data class SubtitleConversionDialogUi(
    val id: Int,
    val sourceFileText: String,
    val sourceFormatText: String,
    val targetFormats: List<String>,
    val initialTargetIndex: Int
)

internal data class SubtitleConversionResultUi(
    val title: String,
    val message: String
)

internal data class ArchiveFormatOptionUi(
    val format: ArchiveManager.CreateFormat,
    val compressionMethods: List<ArchiveManager.CompressionMethod>,
    val encryptionMethods: List<ArchiveManager.EncryptionMethod>
)

internal data class ArchiveSplitOptionUi(
    val label: String,
    val bytes: Long?
)

internal data class ArchiveCreationDialogUi(
    val initialName: String,
    val formats: List<ArchiveFormatOptionUi>,
    val splitOptions: List<ArchiveSplitOptionUi>
)

internal data class ArchiveCreateSubmission(
    val name: String,
    val format: ArchiveManager.CreateFormat,
    val method: ArchiveManager.CompressionMethod,
    val password: String,
    val encryptionMethod: ArchiveManager.EncryptionMethod?,
    val splitSizeBytes: Long?,
    val deleteSources: Boolean
)

@Composable
internal fun MainFileOperationDialogs(
    subtitleConversion: SubtitleConversionDialogUi?,
    subtitleConversionResult: SubtitleConversionResultUi?,
    archiveCreation: ArchiveCreationDialogUi?,
    onDismissSubtitleConversion: (Int) -> Unit,
    onConvertSubtitle: (Int, Int, Boolean) -> Boolean,
    onDismissConversionResult: () -> Unit,
    onDismissArchiveCreation: () -> Unit,
    onCreateArchive: (ArchiveCreateSubmission) -> String?,
    onLoadArchivePasswords: () -> List<String>?,
    onSaveArchivePassword: (String) -> Unit,
    onClearArchivePasswordBook: () -> Unit,
    mainDialog: MainActivityDialogUi?,
    onDismissMainDialog: () -> Unit,
    onPermissionDialogConfirm: () -> Unit,
    onRenameConfirm: (String) -> Unit,
    onDeleteConfirm: () -> Unit
) {
    subtitleConversion?.let { dialog ->
        SubtitleConversionDialog(
            dialog = dialog,
            onDismiss = { onDismissSubtitleConversion(dialog.id) },
            onConvert = { targetIndex, keepOriginal ->
                onConvertSubtitle(dialog.id, targetIndex, keepOriginal)
            }
        )
    }

    subtitleConversionResult?.let { result ->
        AlertDialog(
            onDismissRequest = onDismissConversionResult,
            title = { Text(result.title) },
            text = {
                Text(
                    result.message,
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissConversionResult) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    archiveCreation?.let { dialog ->
        ArchiveCreationDialog(
            dialog = dialog,
            onDismiss = onDismissArchiveCreation,
            onCreate = onCreateArchive,
            onLoadPasswords = onLoadArchivePasswords,
            onSavePassword = onSaveArchivePassword,
            onClearPasswordBook = onClearArchivePasswordBook
        )
    }

    mainDialog?.let { dialog ->
        MainActivityDialog(
            dialog = dialog,
            onDismiss = onDismissMainDialog,
            onPermissionConfirm = onPermissionDialogConfirm,
            onRenameConfirm = onRenameConfirm,
            onDeleteConfirm = onDeleteConfirm
        )
    }
}

@Composable
private fun MainActivityDialog(
    dialog: MainActivityDialogUi,
    onDismiss: () -> Unit,
    onPermissionConfirm: () -> Unit,
    onRenameConfirm: (String) -> Unit,
    onDeleteConfirm: () -> Unit
) {
    when (dialog) {
        is MainActivityDialogUi.Message -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(dialog.title) },
            text = { Text(dialog.message) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
            }
        )
        MainActivityDialogUi.PermissionDenied -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.error)) },
            text = { Text("需要存储权限才能访问字幕文件。请在设置中授予权限。") },
            confirmButton = {
                TextButton(onClick = onPermissionConfirm) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        )
        is MainActivityDialogUi.Rename -> RenameDialog(
            initialName = dialog.initialName,
            onDismiss = onDismiss,
            onConfirm = onRenameConfirm
        )
        is MainActivityDialogUi.DeleteConfirmation -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("删除") },
            text = { Text("确定要删除选中的 ${dialog.selectedCount} 项吗？此操作无法撤销。") },
            confirmButton = {
                Button(onClick = onDeleteConfirm) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun RenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) {
        mutableStateOf(TextFieldValue(initialName, selection = TextRange(initialName.length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.text) }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun SubtitleConversionDialog(
    dialog: SubtitleConversionDialogUi,
    onDismiss: () -> Unit,
    onConvert: (Int, Boolean) -> Boolean
) {
    var targetIndex by remember(dialog) { mutableStateOf(dialog.initialTargetIndex) }
    var keepOriginal by remember(dialog) { mutableStateOf(false) }
    var converting by remember(dialog) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_subtitle_convert_title)) },
        text = {
            Column {
                Text(dialog.sourceFileText, style = MaterialTheme.typography.bodyMedium)
                Text(
                    dialog.sourceFormatText,
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                )
                ChoiceField(
                    modifier = Modifier.padding(top = 12.dp),
                    label = stringResource(R.string.target_format),
                    selected = dialog.targetFormats[targetIndex],
                    options = dialog.targetFormats,
                    horizontal = true,
                    onSelect = { targetIndex = dialog.targetFormats.indexOf(it) }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable { keepOriginal = !keepOriginal },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = keepOriginal,
                        onCheckedChange = { keepOriginal = it }
                    )
                    Text(
                        stringResource(R.string.dialog_subtitle_convert_keep_original),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !converting,
                onClick = {
                    converting = onConvert(targetIndex, keepOriginal)
                }
            ) {
                Text(stringResource(R.string.start_convert))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ArchiveCreationDialog(
    dialog: ArchiveCreationDialogUi,
    onDismiss: () -> Unit,
    onCreate: (ArchiveCreateSubmission) -> String?,
    onLoadPasswords: () -> List<String>?,
    onSavePassword: (String) -> Unit,
    onClearPasswordBook: () -> Unit
) {
    val initialFormat = remember(dialog) { dialog.formats.first() }
    var name by remember(dialog) {
        mutableStateOf(
            TextFieldValue(dialog.initialName, selection = TextRange(dialog.initialName.length))
        )
    }
    var selectedFormat by remember(dialog) { mutableStateOf(initialFormat) }
    var selectedMethod by remember(dialog) { mutableStateOf(initialFormat.compressionMethods.first()) }
    var selectedEncryption by remember(dialog) { mutableStateOf(initialFormat.encryptionMethods.firstOrNull()) }
    var password by remember(dialog) { mutableStateOf("") }
    var showPassword by remember(dialog) { mutableStateOf(false) }
    var splitSize by remember(dialog) { mutableStateOf(dialog.splitOptions.first().bytes) }
    var deleteSources by remember(dialog) { mutableStateOf(false) }
    var nameError by remember(dialog) { mutableStateOf<String?>(null) }
    var passwordBook by remember(dialog) { mutableStateOf<List<String>?>(null) }

    val selectedFormatOption = dialog.formats.first { it.format == selectedFormat.format }
    val supportsSplit = selectedFormat.format == ArchiveManager.CreateFormat.ZIP ||
        selectedFormat.format == ArchiveManager.CreateFormat.SEVEN_Z
    val canSetPassword = selectedFormatOption.encryptionMethods.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建压缩文件") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dialog_create_archive_hint_01)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = { nameError?.let { Text(it) } }
                )
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    ChoiceField(
                        label = stringResource(R.string.dialog_create_archive_text_01),
                        selected = selectedFormat.format.displayName,
                        options = dialog.formats.map { it.format.displayName },
                        onSelect = { label ->
                            val option = dialog.formats.first { it.format.displayName == label }
                            selectedFormat = option
                            selectedMethod = option.compressionMethods.first()
                            selectedEncryption = option.encryptionMethods.firstOrNull()
                            if (option.format == ArchiveManager.CreateFormat.TAR) {
                                splitSize = dialog.splitOptions.first().bytes
                            }
                        }
                    )
                    ChoiceField(
                        modifier = Modifier.padding(top = 8.dp),
                        label = stringResource(R.string.dialog_create_archive_text_02),
                        selected = selectedMethod.displayName,
                        options = selectedFormatOption.compressionMethods.map { it.displayName },
                        onSelect = { label ->
                            selectedMethod = selectedFormatOption.compressionMethods.first { it.displayName == label }
                        }
                    )
                }
                if (selectedFormat.format == ArchiveManager.CreateFormat.ZIP) {
                    ChoiceField(
                        modifier = Modifier.padding(top = 8.dp),
                        label = stringResource(R.string.dialog_create_archive_text_03),
                        selected = selectedEncryption?.displayName.orEmpty(),
                        options = selectedFormatOption.encryptionMethods.map { it.displayName },
                        onSelect = { label ->
                            selectedEncryption = selectedFormatOption.encryptionMethods.first { it.displayName == label }
                        }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.weight(1f),
                        enabled = canSetPassword,
                        label = { Text(stringResource(R.string.dialog_create_archive_hint_02)) },
                        singleLine = true,
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    painter = painterResource(
                                        if (showPassword) R.drawable.ic_visibility_off
                                        else R.drawable.ic_visibility
                                    ),
                                    contentDescription = if (showPassword) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    IconButton(
                        modifier = Modifier.padding(start = 8.dp),
                        onClick = {
                            passwordBook = onLoadPasswords()
                        },
                        enabled = canSetPassword
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_password_book),
                            contentDescription = stringResource(R.string.dialog_archive_password_contentdescription_01)
                        )
                    }
                }
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = when (selectedFormat.format) {
                        ArchiveManager.CreateFormat.ZIP -> "留空则不加密；ZipCrypto 兼容性更好，AES-256 更安全"
                        ArchiveManager.CreateFormat.SEVEN_Z -> "使用 7Z AES-256 加密；留空则不加密"
                        ArchiveManager.CreateFormat.TAR -> "密码仅适用于 ZIP 和 7Z 格式"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                ChoiceField(
                    modifier = Modifier.padding(top = 12.dp),
                    label = stringResource(R.string.dialog_create_archive_text_05),
                    selected = dialog.splitOptions.first { it.bytes == splitSize }.label,
                    options = dialog.splitOptions.map { it.label },
                    enabled = supportsSplit,
                    onSelect = { label ->
                        splitSize = dialog.splitOptions.first { it.label == label }.bytes
                    }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clickable { deleteSources = !deleteSources },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = deleteSources, onCheckedChange = { deleteSources = it })
                    Text(stringResource(R.string.dialog_create_archive_text_06))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val error = onCreate(
                    ArchiveCreateSubmission(
                        name = name.text,
                        format = selectedFormat.format,
                        method = selectedMethod,
                        password = if (canSetPassword) password else "",
                        encryptionMethod = when (selectedFormat.format) {
                            ArchiveManager.CreateFormat.ZIP -> selectedEncryption
                            ArchiveManager.CreateFormat.SEVEN_Z -> selectedFormatOption.encryptionMethods.firstOrNull()
                            ArchiveManager.CreateFormat.TAR -> null
                        },
                        splitSizeBytes = if (supportsSplit) splitSize else null,
                        deleteSources = deleteSources
                    )
                )
                if (error != null) nameError = error
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )

    passwordBook?.let { passwords ->
        AlertDialog(
            onDismissRequest = { passwordBook = null },
            title = { Text("密码本") },
            text = {
                if (passwords.isEmpty()) {
                    Text("密码本为空，可保存当前输入的密码。")
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        passwords.forEachIndexed { index, savedPassword ->
                            TextButton(
                                onClick = {
                                    password = savedPassword
                                    passwordBook = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("密码 ${index + 1}（${"•".repeat(savedPassword.length.coerceIn(1, 8))}）")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSavePassword(password)
                    passwordBook = null
                }) { Text("保存当前密码") }
            },
            dismissButton = {
                Row {
                    if (passwords.isNotEmpty()) {
                        TextButton(onClick = {
                            onClearPasswordBook()
                            passwordBook = null
                        }) { Text("清空密码本") }
                    }
                    TextButton(onClick = { passwordBook = null }) { Text("关闭") }
                }
            }
        )
    }
}

@Composable
private fun ChoiceField(
    modifier: Modifier = Modifier,
    label: String,
    selected: String,
    options: List<String>,
    enabled: Boolean = true,
    horizontal: Boolean = false,
    onSelect: (String) -> Unit
) {
    var expanded by remember(label, options) { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        val labelContent: @Composable () -> Unit = {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (horizontal) 14.sp else 13.sp,
                modifier = Modifier.alpha(if (enabled) 1f else 0.38f)
            )
        }
        val selectedContent: @Composable (Modifier) -> Unit = { selectionModifier ->
            Box(modifier = selectionModifier) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable(enabled = enabled) { expanded = true }
                        .alpha(if (enabled) 1f else 0.38f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selected,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 16.sp,
                                letterSpacing = 0.sp
                            )
                        )
                        Text(
                            text = "▾",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 18.sp,
                                letterSpacing = 0.sp
                            )
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                expanded = false
                                onSelect(option)
                            }
                        )
                    }
                }
            }
        }
        if (horizontal) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                labelContent()
                Spacer(Modifier.width(12.dp))
                selectedContent(Modifier.weight(1f))
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                labelContent()
                selectedContent(Modifier.fillMaxWidth())
            }
        }
    }
}
