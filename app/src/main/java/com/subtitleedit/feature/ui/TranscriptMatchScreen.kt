package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
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
                modifier = Modifier.height(56.dp),
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
                .padding(16.dp)
        ) {
            TranscriptCard {
                SectionHeading(stringResource(R.string.transcript_match_inputs))
                OutlinedButton(
                    onClick = onSelectText,
                    enabled = !isRunning,
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text(stringResource(R.string.transcript_match_select_text)) }
                Text(
                    textFileName,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onSelectAudio,
                    enabled = !isRunning,
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text(stringResource(R.string.transcript_match_select_audio)) }
                Text(
                    audioFileName,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            TranscriptCard {
                SectionHeading(stringResource(R.string.transcript_match_settings))
                Text(
                    stringResource(R.string.target_format),
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OptionSelector(selectedFormat, formats, !isRunning, onFormatSelected)
                Text(
                    stringResource(R.string.transcript_match_language),
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OptionSelector(selectedLanguage, languages, !isRunning, onLanguageSelected)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            TranscriptCard {
                SectionHeading(stringResource(R.string.transcript_match_pending))
                Text(
                    pendingFiles,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            TranscriptCard {
                SectionHeading(stringResource(R.string.activity_auto_timestamp_text_06))
                Text(
                    outputDirectory,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                OutlinedButton(
                    onClick = onSelectOutputDirectory,
                    enabled = !isRunning,
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text(stringResource(R.string.activity_auto_timestamp_text_08)) }
            }
            if (isRunning) {
                Text(
                    progressText,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isCancelling) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (modelHint.isNotBlank()) {
                Text(
                    modelHint,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = onStartOrCancel,
                enabled = if (isRunning) !isCancelling else startEnabled,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Text(stringResource(if (isRunning) R.string.transcript_match_cancel else R.string.transcript_match_start))
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
private fun TranscriptCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun OptionSelector(
    value: String,
    options: List<String>,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(value, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Text("▼", fontSize = 12.sp)
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
