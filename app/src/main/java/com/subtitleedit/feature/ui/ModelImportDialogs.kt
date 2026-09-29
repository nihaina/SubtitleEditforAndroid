package com.subtitleedit.feature.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

sealed interface ModelImportDialogUi {
    data class Message(
        val title: String,
        val message: String,
        val confirmLabel: String? = "确定",
        val dismissLabel: String? = null,
        val auxiliaryLabel: String? = null,
        val destructiveConfirm: Boolean = false,
        val onConfirm: () -> Unit = {},
        val onDismiss: () -> Unit,
        val onAuxiliary: () -> Unit = {}
    ) : ModelImportDialogUi

    data class Options(
        val title: String,
        val options: List<String>,
        val selectedIndex: Int? = null,
        val onSelect: (Int) -> Unit,
        val onDismiss: () -> Unit
    ) : ModelImportDialogUi

    data class Progress(
        val token: Long,
        val title: String,
        val message: String,
        val progress: Float? = null,
        val onCancel: () -> Unit
    ) : ModelImportDialogUi
}

@Composable
fun ModelImportDialogs(
    asrDialog: ModelImportDialogUi?,
    demucsDialog: ModelImportDialogUi?,
    exportDialog: ModelImportDialogUi?
) {
    asrDialog?.let { ModelImportDialog(it) }
    demucsDialog?.let { ModelImportDialog(it) }
    exportDialog?.let { ModelImportDialog(it) }
}

@Composable
private fun ModelImportDialog(dialog: ModelImportDialogUi) {
    when (dialog) {
        is ModelImportDialogUi.Message -> MessageDialog(dialog)
        is ModelImportDialogUi.Options -> OptionsDialog(dialog)
        is ModelImportDialogUi.Progress -> ProgressDialog(dialog)
    }
}

@Composable
private fun MessageDialog(dialog: ModelImportDialogUi.Message) {
    AlertDialog(
        onDismissRequest = dialog.onDismiss,
        title = { Text(dialog.title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                LinkedDialogText(dialog.message)
            }
        },
        confirmButton = {
            dialog.confirmLabel?.let { label ->
                TextButton(onClick = dialog.onConfirm) {
                    Text(
                        label,
                        color = if (dialog.destructiveConfirm) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dialog.auxiliaryLabel?.let { label ->
                    TextButton(onClick = dialog.onAuxiliary) { Text(label) }
                }
                dialog.dismissLabel?.let { label ->
                    TextButton(onClick = dialog.onDismiss) { Text(label) }
                }
            }
        },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    )
}

@Composable
private fun OptionsDialog(dialog: ModelImportDialogUi.Options) {
    AlertDialog(
        onDismissRequest = dialog.onDismiss,
        title = { Text(dialog.title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                dialog.options.forEachIndexed { index, label ->
                    if (dialog.selectedIndex == null) {
                        TextButton(
                            onClick = { dialog.onSelect(index) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label, modifier = Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dialog.onSelect(index) }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = dialog.selectedIndex == index,
                                onClick = { dialog.onSelect(index) }
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dialog.onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ProgressDialog(dialog: ModelImportDialogUi.Progress) {
    AlertDialog(
        onDismissRequest = dialog.onCancel,
        title = { Text(dialog.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(dialog.message, style = MaterialTheme.typography.bodyMedium)
                if (dialog.progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { dialog.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = dialog.onCancel) { Text("取消") }
        },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkedDialogText(message: String) {
    val uriHandler = LocalUriHandler.current
    val urlRegex = Regex("https?://[^\\s]+")
    val annotated = buildAnnotatedString {
        var start = 0
        urlRegex.findAll(message).forEach { match ->
            append(message.substring(start, match.range.first))
            pushStringAnnotation(tag = "url", annotation = match.value)
            withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline
                )
            ) { append(match.value) }
            pop()
            start = match.range.last + 1
        }
        append(message.substring(start))
    }
    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        onClick = { offset ->
            annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let { annotation ->
                runCatching { uriHandler.openUri(annotation.item) }
            }
        }
    )
}
