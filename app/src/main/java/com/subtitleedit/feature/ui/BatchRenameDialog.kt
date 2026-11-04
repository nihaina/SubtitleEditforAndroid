package com.subtitleedit.feature.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.subtitleedit.R
import com.subtitleedit.util.BatchRename
import com.subtitleedit.util.BatchRenameHistory
import java.io.File

internal data class BatchRenameDialogUi(
    val sources: List<File>,
    val running: Boolean = false,
    val error: String? = null,
    val history: List<BatchRenameHistory.Entry> = emptyList()
)

@Composable
internal fun BatchRenameDialog(
    state: BatchRenameDialogUi,
    onDismiss: () -> Unit,
    onConfirm: (BatchRename.Options) -> Unit,
    onOrderChanged: (List<File>) -> Unit = {},
    onDeleteHistory: (String) -> Unit = {}
) {
    val sources = state.sources
    val initialPattern = BatchRename.FILE_NAME_TOKEN + "(" + BatchRename.NUMBER_TOKEN + ")"
    var pattern by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialPattern, selection = TextRange(initialPattern.length)))
    }
    var patternFocused by remember { mutableStateOf(false) }
    // A mixed selection retains each file's suffix until the user edits this field.
    val extensions = remember(sources) { sources.filter { it.isFile }.map(BatchRename::sourceExtension).distinct() }
    var extension by rememberSaveable { mutableStateOf(extensions.singleOrNull().orEmpty()) }
    var extensionEdited by rememberSaveable { mutableStateOf(false) }
    var numberWidth by rememberSaveable { mutableStateOf(BatchRename.Options().numberWidth.toString()) }
    var startNumber by rememberSaveable { mutableStateOf("1") }
    var find by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var regexEnabled by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showOrder by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val openDialog: (() -> Unit) -> Unit = { open ->
        focusManager.clearFocus()
        keyboardController?.hide()
        open()
    }
    val optionsResult = remember(pattern.text, extension, extensionEdited, numberWidth, startNumber, find, replacement, regexEnabled) {
        runCatching {
            BatchRename.Options(
                pattern = pattern.text,
                extension = if (extensionEdited) extension else null,
                numberWidth = numberWidth.toIntOrNull()
                    ?: throw IllegalArgumentException("请输入有效的编号位数"),
                startNumber = startNumber.toLongOrNull()
                    ?: throw IllegalArgumentException("请输入有效的开始序号"),
                find = find,
                replacement = replacement,
                regexEnabled = regexEnabled
            )
        }
    }
    val previewResult = remember(sources, optionsResult) {
        optionsResult.mapCatching { BatchRename.preview(sources, it) }
    }
    val blue = Color(0xFF5364F5)
    val paleBlue = if (isSystemInDarkTheme()) Color(0xFF33446B) else Color(0xFFD8E6FF)
    val failedOptions = remember(state.error) { optionsResult.getOrNull() }
    val error = previewResult.exceptionOrNull()?.message ?: state.error.takeIf { optionsResult.getOrNull() == failedOptions }
    val controlsEnabled = !state.running

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = blue)) {
        AlertDialog(
            onDismissRequest = { if (controlsEnabled) onDismiss() },
            properties = DialogProperties(dismissOnBackPress = controlsEnabled, dismissOnClickOutside = controlsEnabled),
            containerColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainerHigh else Color(0xFFEDEDED),
            title = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.batch_rename_title), modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { openDialog { showHistory = true } },
                        enabled = controlsEnabled,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_history),
                            contentDescription = stringResource(R.string.batch_rename_history_title)
                        )
                    }
                    IconButton(
                        onClick = { openDialog { showOrder = true } },
                        enabled = controlsEnabled,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_batch_rename_sort),
                            contentDescription = stringResource(R.string.batch_rename_sort_title)
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        contentPadding = PaddingValues(bottom = 4.dp)
                    ) {
                        itemsIndexed(sources, key = { _, source -> source.absolutePath }) { index, source ->
                            val preview = previewResult.getOrNull()?.getOrNull(index)
                            RenameFileRow(file = source, displayName = preview?.target?.name ?: source.name)
                            HorizontalDivider()
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PatternButton(
                            label = stringResource(R.string.batch_rename_file_name),
                            modifier = Modifier.weight(1f),
                            enabled = controlsEnabled,
                            containerColor = blue,
                            contentColor = Color.White,
                            onClick = {
                                pattern = insertRenameToken(pattern, BatchRename.FILE_NAME_TOKEN, patternFocused)
                            }
                        ) {
                            Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                        PatternButton(
                            label = stringResource(R.string.batch_rename_number),
                            modifier = Modifier.weight(1f),
                            enabled = controlsEnabled,
                            containerColor = blue,
                            contentColor = Color.White,
                            onClick = {
                                pattern = insertRenameToken(pattern, BatchRename.NUMBER_TOKEN, patternFocused)
                            }
                        ) { Text("1≡", style = MaterialTheme.typography.titleMedium) }
                        val regexState = stringResource(if (regexEnabled) R.string.batch_rename_regex_enabled else R.string.batch_rename_regex_disabled)
                        PatternButton(
                            label = stringResource(R.string.batch_rename_regex),
                            modifier = Modifier.weight(1f).semantics { stateDescription = regexState },
                            enabled = controlsEnabled,
                            containerColor = if (regexEnabled) blue else paleBlue,
                            contentColor = if (regexEnabled) Color.White else if (isSystemInDarkTheme()) Color(0xFFBED2FF) else blue,
                            onClick = { regexEnabled = !regexEnabled }
                        ) { Text(".*", style = MaterialTheme.typography.titleMedium) }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // Keep token editing isolated from extension editing, just like the reference.
                        Box(Modifier.weight(1f)) {
                            RenamePatternField(
                                value = pattern,
                                onValueChange = { if (controlsEnabled) pattern = it },
                                onFocusChanged = { patternFocused = it },
                                enabled = controlsEnabled,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(".", modifier = Modifier.padding(horizontal = 4.dp))
                        OutlinedTextField(
                            value = extension,
                            onValueChange = { extension = it; extensionEdited = true },
                            modifier = Modifier.weight(0.38f),
                            enabled = controlsEnabled && extensions.isNotEmpty(),
                            singleLine = true,
                            label = {
                                Text(
                                    text = stringResource(R.string.batch_rename_extension),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            placeholder = {
                                if (extensions.size > 1 && !extensionEdited) Text(stringResource(R.string.batch_rename_keep_extension))
                            }
                        )
                    }
                    Text(stringResource(R.string.batch_rename_numbering), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = numberWidth,
                            onValueChange = { numberWidth = it.filter(Char::isDigit).take(2) },
                            modifier = Modifier.weight(1f),
                            enabled = controlsEnabled,
                            label = { Text(stringResource(R.string.batch_rename_digits)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = startNumber,
                            onValueChange = { startNumber = it.filter(Char::isDigit).take(19) },
                            modifier = Modifier.weight(1f),
                            enabled = controlsEnabled,
                            label = { Text(stringResource(R.string.batch_rename_start)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    Text(stringResource(R.string.batch_rename_replace_section), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = find,
                            onValueChange = { find = it },
                            modifier = Modifier.weight(1f),
                            enabled = controlsEnabled,
                            label = { Text(stringResource(R.string.batch_rename_find)) },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = replacement,
                            onValueChange = { replacement = it },
                            modifier = Modifier.weight(1f),
                            enabled = controlsEnabled,
                            label = { Text(stringResource(R.string.batch_rename_replace)) },
                            singleLine = true
                        )
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { optionsResult.getOrNull()?.let(onConfirm) },
                    enabled = controlsEnabled && previewResult.isSuccess,
                    colors = ButtonDefaults.textButtonColors(contentColor = blue)
                ) {
                    if (state.running) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    enabled = controlsEnabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = blue)
                ) { Text(stringResource(R.string.cancel)) }
            }
        )
        if (showHistory) {
            BatchRenameHistoryDialog(
                entries = state.history,
                onDismiss = { showHistory = false },
                onDelete = onDeleteHistory,
                onSelect = { saved ->
                    pattern = TextFieldValue(saved.pattern, selection = TextRange(saved.pattern.length))
                    extension = saved.extension ?: extensions.singleOrNull().orEmpty()
                    extensionEdited = saved.extension != null
                    numberWidth = saved.numberWidth.toString()
                    startNumber = saved.startNumber.toString()
                    find = saved.find
                    replacement = saved.replacement
                    regexEnabled = saved.regexEnabled
                    showHistory = false
                }
            )
        }
        if (showOrder) {
            BatchRenameOrderDialog(
                sources = sources,
                onDismiss = { showOrder = false },
                onConfirm = { ordered ->
                    onOrderChanged(ordered)
                    showOrder = false
                }
            )
        }
    }
}

@Composable
private fun PatternButton(
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).focusProperties { canFocus = false },
        enabled = enabled,
        shape = RoundedCornerShape(5.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content()
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
