package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.settings.SettingsTileGroup
import com.subtitleedit.ui.settings.SettingsTileRow
import com.subtitleedit.ui.settings.SettingsTileSection
import com.subtitleedit.ui.settings.SettingsTileSwitchRow
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.theme.AppSpacing

internal enum class TranscriptMatchDialog { NONE, BACK, CANCEL, MESSAGE }

internal enum class TranscriptMatchFileType { TEXT, AUDIO }

internal data class TranscriptMatchFileUi(
    val type: TranscriptMatchFileType,
    val fileName: String,
    val fileSizeLabel: String
)

@Composable
internal fun TranscriptMatchScreen(
    textFileName: String,
    audioFileName: String,
    outputDirectory: String,
    saveToSourceDirectory: Boolean,
    pendingFiles: List<TranscriptMatchFileUi>,
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
    onRemoveFile: (TranscriptMatchFileType) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onSaveToSourceDirectoryChange: (Boolean) -> Unit,
    onFormatSelected: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onStartOrCancel: () -> Unit,
    onDialogConfirm: () -> Unit,
    onDialogDismiss: () -> Unit
) {
    val pageBackground = settingsPageBackground()
    AppToolScaffold(
        title = stringResource(R.string.transcript_match_title),
        onBack = onBack,
        contentBackground = pageBackground,
        topBarContainerColor = pageBackground,
        topBarScrolledContainerColor = pageBackground,
        bottomBar = {
            Box(Modifier.background(pageBackground)) {
                Button(
                    onClick = onStartOrCancel,
                    enabled = if (isRunning) !isCancelling else startEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
                        .height(56.dp),
                    shape = CircleShape
                ) {
                    Text(
                        stringResource(if (isRunning) R.string.transcript_match_cancel else R.string.transcript_match_start),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    ) {
        // ── Input files selection ──
        SettingsTileGroup {
            SettingsTileRow(
                title = stringResource(R.string.transcript_match_select_text),
                iconRes = R.drawable.ic_file_text,
                value = textFileName,
                valueBelow = true,
                enabled = !isRunning,
                onClick = onSelectText
            )
            SettingsTileRow(
                title = stringResource(R.string.transcript_match_select_audio),
                iconRes = R.drawable.ic_file_audio,
                value = audioFileName,
                valueBelow = true,
                enabled = !isRunning,
                onClick = onSelectAudio
            )
        }

        // ── Match settings ──
        SettingsTileGroup {
            TranscriptMatchOptionRow(
                label = stringResource(R.string.target_format),
                iconRes = R.drawable.ic_subtitle_format,
                value = selectedFormat,
                options = formats,
                enabled = !isRunning,
                onSelected = onFormatSelected
            )
            TranscriptMatchOptionRow(
                label = stringResource(R.string.transcript_match_language),
                iconRes = R.drawable.ic_mic,
                value = selectedLanguage,
                options = languages,
                enabled = !isRunning,
                onSelected = onLanguageSelected
            )
        }

        // ── Pending files ──
        SettingsTileSection(
            title = stringResource(R.string.transcript_match_pending),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp)
        ) {
            if (pendingFiles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.transcript_match_pending_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                ) {
                    items(pendingFiles, key = { it.type.name }) { file ->
                        ToolPendingFileRow(
                            fileName = file.fileName,
                            fileSizeLabel = file.fileSizeLabel,
                            onRemove = { onRemoveFile(file.type) },
                            removeEnabled = !isRunning
                        )
                    }
                }
            }
        }

        // ── Output directory ──
        SettingsTileGroup {
            SettingsTileRow(
                title = stringResource(R.string.activity_tool_output_directory),
                iconRes = R.drawable.ic_folder,
                value = outputDirectory.ifEmpty { stringResource(R.string.activity_tool_default_output_directory) },
                valueBelow = true,
                enabled = !isRunning && !saveToSourceDirectory,
                onClick = onSelectOutputDirectory
            )
            SettingsTileSwitchRow(
                title = stringResource(R.string.save_to_source_folder),
                checked = saveToSourceDirectory,
                enabled = !isRunning,
                onCheckedChange = onSaveToSourceDirectoryChange
            )
        }

        // ── Processing progress ──
        if (isRunning) {
            SettingsTileSection(title = stringResource(R.string.activity_speech_to_subtitle_text_06)) {
                AppTaskProgress(
                    visible = true,
                    progress = if (isCancelling) null else progress.coerceIn(0, 100) / 100f,
                    status = progressText
                )
            }
        }

        // ── Model warning / hint ──
        if (modelHint.isNotBlank()) {
            SettingsTileSection {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = modelHint,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    when (dialog) {
        TranscriptMatchDialog.NONE -> Unit
        TranscriptMatchDialog.BACK,
        TranscriptMatchDialog.CANCEL -> AppAlertDialog(
            title = dialogTitle,
            message = dialogMessage,
            confirmText = if (dialog == TranscriptMatchDialog.BACK) stringResource(R.string.transcript_match_back_and_cancel) else stringResource(R.string.transcript_match_cancel),
            dismissText = stringResource(R.string.transcript_match_continue),
            onConfirm = onDialogConfirm,
            onDismiss = onDialogDismiss
        )
        TranscriptMatchDialog.MESSAGE -> AppAlertDialog(
            title = dialogTitle,
            message = dialogMessage,
            onConfirm = onDialogDismiss,
            onDismiss = onDialogDismiss
        )
    }
}

/** A settings-tile-styled dropdown row for selecting a format or language option. */
@Composable
private fun TranscriptMatchOptionRow(
    label: String,
    iconRes: Int,
    value: String,
    options: List<String>,
    enabled: Boolean = true,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsTileRow(
            title = label,
            iconRes = iconRes,
            value = value,
            enabled = enabled,
            onClick = { if (enabled) expanded = true }
        )
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
