package com.subtitleedit.feature.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppConflictDialog
import com.subtitleedit.ui.components.AppLogBox
import com.subtitleedit.ui.components.AppOptionSelector
import com.subtitleedit.ui.components.AppPrimaryButton
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.theme.AppSpacing

internal enum class AutoTimestampDialog { NONE, OUTPUT_CONFLICT, CANCEL_GENERATION, BACK_WHILE_GENERATING }

@Composable
internal fun AutoTimestampScreen(
    audioFilesText: String,
    subtitleFileText: String,
    outputDirectory: String,
    secondaryProcessingEnabled: Boolean,
    secondaryProcessingAvailable: Boolean,
    secondaryProcessingHint: String,
    outputFormat: String,
    isGenerating: Boolean,
    canGenerate: Boolean,
    status: String,
    preview: String,
    dialog: AutoTimestampDialog,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onSelectAudio: () -> Unit,
    onSecondaryProcessingChange: (Boolean) -> Unit,
    onSelectSubtitle: () -> Unit,
    onFormatChange: (String) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onGenerate: () -> Unit,
    onRequestCancel: () -> Unit,
    onConfirmCancel: () -> Unit,
    onOverwrite: () -> Unit,
    onRename: () -> Unit,
    onDismissDialog: () -> Unit
) {
    AppToolScaffold(
        title = stringResource(R.string.auto_timestamp_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = onSettings, enabled = !isGenerating) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.auto_timestamp_settings))
            }
        },
        bottomBar = {
            AppPrimaryButton(
                text = stringResource(R.string.activity_auto_timestamp_text_09),
                onClick = onGenerate,
                enabled = canGenerate && !isGenerating,
                modifier = Modifier.padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
            )
        }
    ) {
        AppSection(title = stringResource(R.string.activity_auto_timestamp_text_02)) {
            OutlinedButton(onClick = onSelectAudio, enabled = !isGenerating) {
                Text(stringResource(R.string.activity_auto_timestamp_text_03))
            }
            Text(audioFilesText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = secondaryProcessingEnabled,
                        enabled = secondaryProcessingAvailable && !isGenerating,
                        role = Role.Switch,
                        onValueChange = onSecondaryProcessingChange
                    )
            ) {
                androidx.compose.foundation.layout.Row {
                    Text(stringResource(R.string.activity_auto_timestamp_text_12), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = secondaryProcessingEnabled, onCheckedChange = null, enabled = secondaryProcessingAvailable && !isGenerating)
                }
            }
            Text(secondaryProcessingHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.activity_auto_timestamp_text_14),
                style = MaterialTheme.typography.bodyMedium,
                color = if (secondaryProcessingEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            OutlinedButton(onClick = onSelectSubtitle, enabled = secondaryProcessingEnabled && !isGenerating) {
                Text(stringResource(R.string.activity_auto_timestamp_text_15))
            }
            Text(
                subtitleFileText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (secondaryProcessingEnabled) 1f else 0.55f)
            )
        }

        AppSection(title = stringResource(R.string.activity_auto_timestamp_text_05)) {
            AppOptionSelector(
                label = stringResource(R.string.target_format),
                value = outputFormat,
                options = listOf("SRT", "LRC"),
                enabled = !isGenerating,
                onSelected = onFormatChange
            )
        }

        AppSection(title = stringResource(R.string.activity_auto_timestamp_text_06)) {
            Text(outputDirectory, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            OutlinedButton(onClick = onSelectOutputDirectory, enabled = !isGenerating) {
                Text(stringResource(R.string.activity_auto_timestamp_text_08))
            }
        }

        AnimatedVisibility(visible = isGenerating, enter = fadeIn(), exit = fadeOut()) {
            AppSection(title = stringResource(R.string.activity_auto_timestamp_text_10)) {
                AppTaskProgress(visible = true, progress = null, status = status, onCancel = onRequestCancel)
            }
        }
        if (!isGenerating && status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AppSection(title = stringResource(R.string.activity_auto_timestamp_text_10)) {
            AppLogBox(preview, height = 200.dp)
        }
    }

    when (dialog) {
        AutoTimestampDialog.NONE -> Unit
        AutoTimestampDialog.OUTPUT_CONFLICT -> AppConflictDialog(
            message = stringResource(R.string.output_subtitle_conflict),
            onOverwrite = onOverwrite,
            onRename = onRename,
            onCancel = onDismissDialog
        )
        AutoTimestampDialog.CANCEL_GENERATION -> AppAlertDialog(
            title = stringResource(R.string.operation_confirm_cancel),
            message = stringResource(R.string.auto_timestamp_cancel_message),
            confirmText = stringResource(R.string.cancel_processing),
            dismissText = stringResource(R.string.continue_processing),
            onConfirm = onConfirmCancel,
            onDismiss = onDismissDialog
        )
        AutoTimestampDialog.BACK_WHILE_GENERATING -> AppAlertDialog(
            title = stringResource(R.string.processing_in_progress),
            message = stringResource(R.string.auto_timestamp_back_message),
            confirmText = stringResource(R.string.back_and_cancel),
            dismissText = stringResource(R.string.continue_processing),
            onConfirm = onConfirmCancel,
            onDismiss = onDismissDialog
        )
    }
}
