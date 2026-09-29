package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

internal enum class TranscriptMatchDialog { NONE, BACK, CANCEL, MESSAGE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscriptMatchScreen(
    textFileName: String,
    audioFileName: String,
    outputDirectory: String,
    pendingFiles: String,
    formats: List<String>,
    selectedFormat: String,
    languages: List<String>,
    selectedLanguage: String,
    modelHint: String,
    startEnabled: Boolean,
    isRunning: Boolean,
    isCancelling: Boolean,
    progress: Int,
    progressText: String,
    dialog: TranscriptMatchDialog,
    dialogTitle: String,
    dialogMessage: String,
    onBack: () -> Unit,
    onSelectText: () -> Unit,
    onSelectAudio: () -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onFormatSelected: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onStartOrCancel: () -> Unit,
    onDialogConfirm: () -> Unit,
    onDialogDismiss: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transcript_match_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
                    }
                }
            )
        }
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.transcript_match_inputs), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onSelectText, enabled = !isRunning) {
                Text(stringResource(R.string.transcript_match_select_text))
            }
            Text(textFileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onSelectAudio, enabled = !isRunning) {
                Text(stringResource(R.string.transcript_match_select_audio))
            }
            Text(audioFileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()

            Text(stringResource(R.string.transcript_match_settings), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.target_format), style = MaterialTheme.typography.bodyMedium)
            OptionSelector(
                value = selectedFormat,
                options = formats,
                enabled = !isRunning,
                onSelected = onFormatSelected
            )
            Text(stringResource(R.string.transcript_match_language), style = MaterialTheme.typography.bodyMedium)
            OptionSelector(
                value = selectedLanguage,
                options = languages,
                enabled = !isRunning,
                onSelected = onLanguageSelected
            )

            HorizontalDivider()

            Text(stringResource(R.string.transcript_match_pending), style = MaterialTheme.typography.titleMedium)
            Text(pendingFiles, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()

            Text(stringResource(R.string.activity_auto_timestamp_text_06), style = MaterialTheme.typography.titleMedium)
            Text(outputDirectory, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onSelectOutputDirectory, enabled = !isRunning) {
                Text(stringResource(R.string.activity_auto_timestamp_text_08))
            }

            if (modelHint.isNotBlank()) {
                Text(
                    modelHint,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (isRunning) {
                Text(progressText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isCancelling) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { (progress.coerceIn(0, 100) / 100f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Button(
                onClick = onStartOrCancel,
                enabled = if (isRunning) !isCancelling else startEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (isRunning) R.string.transcript_match_cancel
                        else R.string.transcript_match_start
                    )
                )
            }
        }
    }

    when (dialog) {
        TranscriptMatchDialog.NONE -> Unit
        TranscriptMatchDialog.BACK -> AlertDialog(
            onDismissRequest = onDialogDismiss,
            title = { Text(dialogTitle) },
            text = { Text(dialogMessage) },
            confirmButton = {
                TextButton(onClick = onDialogConfirm) {
                    Text(stringResource(R.string.transcript_match_back_and_cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = onDialogDismiss) {
                    Text(stringResource(R.string.transcript_match_continue))
                }
            }
        )
        TranscriptMatchDialog.CANCEL -> AlertDialog(
            onDismissRequest = onDialogDismiss,
            title = { Text(dialogTitle) },
            text = { Text(dialogMessage) },
            confirmButton = {
                TextButton(onClick = onDialogConfirm) {
                    Text(stringResource(R.string.transcript_match_cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = onDialogDismiss) {
                    Text(stringResource(R.string.transcript_match_continue))
                }
            }
        )
        TranscriptMatchDialog.MESSAGE -> AlertDialog(
            onDismissRequest = onDialogDismiss,
            title = { Text(dialogTitle) },
            text = { Text(dialogMessage) },
            confirmButton = {
                TextButton(onClick = onDialogDismiss) { Text("确定") }
            }
        )
    }
}

@Composable
private fun OptionSelector(
    value: String,
    options: List<String>,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(value)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}
