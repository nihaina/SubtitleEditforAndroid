package com.subtitleedit.feature.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppOptionSelector
import com.subtitleedit.ui.components.AppPrimaryButton
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.theme.AppSpacing

internal enum class TranscriptMatchDialog { NONE, BACK, CANCEL, MESSAGE }

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
    AppToolScaffold(
        title = stringResource(R.string.transcript_match_title),
        onBack = onBack,
        bottomBar = {
            AppPrimaryButton(
                text = stringResource(if (isRunning) R.string.transcript_match_cancel else R.string.transcript_match_start),
                onClick = onStartOrCancel,
                enabled = if (isRunning) !isCancelling else startEnabled,
                modifier = Modifier.padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
            )
        }
    ) {
        AppSection(title = stringResource(R.string.transcript_match_inputs)) {
            OutlinedButton(onClick = onSelectText, enabled = !isRunning) {
                Text(stringResource(R.string.transcript_match_select_text))
            }
            Text(textFileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onSelectAudio, enabled = !isRunning) {
                Text(stringResource(R.string.transcript_match_select_audio))
            }
            Text(audioFileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AppSection(title = stringResource(R.string.transcript_match_settings)) {
            AppOptionSelector(
                label = stringResource(R.string.target_format),
                value = selectedFormat,
                options = formats,
                enabled = !isRunning,
                onSelected = onFormatSelected
            )
            AppOptionSelector(
                label = stringResource(R.string.transcript_match_language),
                value = selectedLanguage,
                options = languages,
                enabled = !isRunning,
                onSelected = onLanguageSelected
            )
        }

        AppSection(title = stringResource(R.string.transcript_match_pending)) {
            Text(pendingFiles, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AppSection(title = stringResource(R.string.activity_auto_timestamp_text_06)) {
            Text(outputDirectory, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            OutlinedButton(onClick = onSelectOutputDirectory, enabled = !isRunning) {
                Text(stringResource(R.string.activity_auto_timestamp_text_08))
            }
        }

        if (isRunning) {
            AppSection(title = stringResource(R.string.transcript_match_pending)) {
                AppTaskProgress(
                    visible = true,
                    progress = if (isCancelling) null else progress.coerceIn(0, 100) / 100f,
                    status = progressText
                )
            }
        }
        if (modelHint.isNotBlank()) {
            Text(modelHint, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
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
