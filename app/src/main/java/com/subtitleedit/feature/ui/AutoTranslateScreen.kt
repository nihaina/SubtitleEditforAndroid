package com.subtitleedit.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppConflictDialog
import com.subtitleedit.ui.components.AppPrimaryButton
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.theme.AppSpacing

data class AutoTranslateFileUi(
    val key: String,
    val fileName: String,
    val fileSizeLabel: String,
    val status: String,
    val statusMessage: String,
    val totalLines: Int,
    val processedLines: Int,
    val progressLabel: String?,
    val showStageProgress: Boolean,
    val canRetry: Boolean
)

data class AutoTranslateUiState(
    val files: List<AutoTranslateFileUi> = emptyList(),
    val outputDirectory: String? = null,
    val punctuationPredictionEnabled: Boolean = false,
    val translationEnabled: Boolean = true,
    val queueRunning: Boolean = false,
    val progressSummary: String = "",
    val showOutputConflict: Boolean = false,
    val showExitConfirmation: Boolean = false,
    val removeFileName: String? = null
)

@Composable
fun AutoTranslateScreen(
    state: AutoTranslateUiState,
    onNavigateBack: () -> Unit,
    onSettings: () -> Unit,
    onSelectFiles: () -> Unit,
    onPunctuationPredictionChange: (Boolean) -> Unit,
    onTranslationChange: (Boolean) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onStart: () -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onConfirmRemove: () -> Unit,
    onDismissRemove: () -> Unit,
    onDismissOutputConflict: () -> Unit,
    onOverwriteOutput: () -> Unit,
    onRenameOutput: () -> Unit,
    onConfirmExit: () -> Unit,
    onDismissExit: () -> Unit
) {
    AppToolScaffold(
        title = stringResource(R.string.auto_translate),
        onBack = onNavigateBack,
        actions = {
            IconButton(onClick = onSettings) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.ai_settings_content_description))
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
            ) {
                AppPrimaryButton(
                    text = stringResource(R.string.activity_auto_translate_text_06),
                    onClick = onStart,
                    enabled = !state.queueRunning
                )
            }
        }
    ) {
        AppSection(title = stringResource(R.string.batch_convert_select_subtitle_files)) {
            OutlinedButton(onClick = onSelectFiles) {
                Text(stringResource(R.string.activity_auto_timestamp_text_03))
            }
            Text(
                if (state.files.isEmpty()) stringResource(R.string.activity_media_convert_text_03)
                else stringResource(R.string.batch_convert_selected_file_count, state.files.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AppSection(title = stringResource(R.string.activity_auto_translate_text_01)) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                SettingSwitch(
                    label = stringResource(R.string.activity_auto_translate_text_08),
                    checked = state.punctuationPredictionEnabled,
                    onCheckedChange = onPunctuationPredictionChange,
                    modifier = Modifier.weight(1f)
                )
                SettingSwitch(
                    label = stringResource(R.string.activity_auto_translate_text_09),
                    checked = state.translationEnabled,
                    onCheckedChange = onTranslationChange,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        AppSection(title = stringResource(R.string.activity_auto_translate_text_04)) {
            AppTaskProgress(
                visible = state.queueRunning,
                progress = null,
                status = state.progressSummary
            )
            if (state.files.isEmpty()) {
                Text(
                    stringResource(R.string.activity_media_convert_text_03),
                    modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.Inner),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                ) {
                    items(state.files, key = AutoTranslateFileUi::key) { file ->
                        AutoTranslateFileRow(
                            file = file,
                            onClick = { if (file.canRetry) onRetry(file.key) },
                            onRemove = { onRemove(file.key) }
                        )
                    }
                }
            }
        }

        AppSection(title = stringResource(R.string.activity_auto_timestamp_text_06)) {
            Text(
                state.outputDirectory ?: stringResource(R.string.auto_translate_default_output_directory),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            OutlinedButton(onClick = onSelectOutputDirectory) {
                Text(stringResource(R.string.activity_auto_timestamp_text_08))
            }
        }
    }

    if (state.showOutputConflict) {
        AppConflictDialog(
            message = stringResource(R.string.output_subtitle_conflict),
            onOverwrite = onOverwriteOutput,
            onRename = onRenameOutput,
            onCancel = onDismissOutputConflict
        )
    }
    if (state.removeFileName != null) {
        AppAlertDialog(
            title = stringResource(R.string.auto_translate_remove_title),
            message = stringResource(R.string.auto_translate_remove_message),
            confirmText = stringResource(R.string.remove),
            dismissText = stringResource(R.string.cancel),
            onConfirm = onConfirmRemove,
            onDismiss = onDismissRemove
        )
    }
    if (state.showExitConfirmation) {
        AppAlertDialog(
            title = stringResource(R.string.auto_translate_processing_title),
            message = stringResource(R.string.auto_translate_exit_message),
            confirmText = stringResource(R.string.stop_and_exit),
            dismissText = stringResource(R.string.continue_processing),
            onConfirm = onConfirmExit,
            onDismiss = onDismissExit
        )
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun AutoTranslateFileRow(
    file: AutoTranslateFileUi,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (file.canRetry) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = AppSpacing.Inner),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_file),
            contentDescription = stringResource(R.string.file_name),
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Column(Modifier.weight(1f).padding(start = AppSpacing.Inner)) {
            Text(file.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                stringResource(
                    R.string.subtitle_file_progress_summary,
                    file.fileSizeLabel,
                    file.totalLines,
                    file.processedLines
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (file.statusMessage.isBlank()) file.status else "${file.status}：${file.statusMessage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRemove) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
        }
    }
}
