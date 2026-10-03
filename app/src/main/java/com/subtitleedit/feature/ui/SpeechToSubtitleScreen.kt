package com.subtitleedit.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.AppOptionSelector
import com.subtitleedit.ui.components.AppPrimaryButton
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.components.SettingsSwitchRow
import com.subtitleedit.ui.theme.AppMotion
import com.subtitleedit.ui.theme.AppSpacing

sealed interface SpeechToSubtitleDialogUi {
    data object OutputConflict : SpeechToSubtitleDialogUi
    data object CancelConfirmation : SpeechToSubtitleDialogUi
    data object BackConfirmation : SpeechToSubtitleDialogUi
    data class Error(val message: String) : SpeechToSubtitleDialogUi
}

data class SpeechToSubtitleUiState(
    val languageOptions: List<AppOption<String>> = emptyList(),
    val formatOptions: List<AppOption<String>> = emptyList(),
    val selectedFiles: List<String> = emptyList(),
    val outputDirectory: String? = null,
    val selectedLanguage: String = "",
    val selectedFormat: String = "",
    val addToAutoTranslate: Boolean = false,
    val disableVadForTxt: Boolean = false,
    val isTxtSelected: Boolean = false,
    val asrModelReady: Boolean = false,
    val startEnabled: Boolean = false,
    val isConverting: Boolean = false,
    val showProcessingPanel: Boolean = false,
    val progressVisible: Boolean = false,
    val progress: Int = 0,
    val progressStatus: String = "",
    val logText: String = "",
    val dialog: SpeechToSubtitleDialogUi? = null
)

@Composable
fun SpeechToSubtitleScreen(
    state: SpeechToSubtitleUiState,
    onNavigateBack: () -> Unit,
    onSettings: () -> Unit,
    onSelectFiles: () -> Unit,
    onLanguageSelected: (String) -> Unit,
    onFormatSelected: (String) -> Unit,
    onAddToAutoTranslateChange: (Boolean) -> Unit,
    onDisableVadForTxtChange: (Boolean) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismissDialog: () -> Unit,
    onOverwriteOutput: () -> Unit,
    onRenameOutput: () -> Unit,
    onConfirmCancel: () -> Unit,
    onConfirmBack: () -> Unit
) {
    AppToolScaffold(
        title = stringResource(R.string.speech_to_subtitle_title),
        onBack = onNavigateBack,
        actions = {
            IconButton(onClick = onSettings, enabled = !state.isConverting) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.activity_settings_text_03))
            }
        },
        bottomBar = {
            AppPrimaryButton(
                text = stringResource(R.string.start_convert),
                onClick = onStart,
                enabled = state.startEnabled,
                modifier = Modifier.padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
            )
        }
    ) {
        AppSection(
            title = stringResource(R.string.activity_speech_to_subtitle_text_01),
            subtitle = stringResource(R.string.activity_speech_to_subtitle_text_02)
        ) {
            OutlinedButton(onClick = onSelectFiles) {
                Text(stringResource(R.string.activity_auto_timestamp_text_03))
            }
            Text(
                text = if (state.selectedFiles.isEmpty()) {
                    stringResource(R.string.activity_media_convert_text_03)
                } else {
                    buildString {
                        append(stringResource(R.string.selected_file_count_with_colon, state.selectedFiles.size))
                        state.selectedFiles.forEachIndexed { index, fileName -> append("\n${index + 1}. $fileName") }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AppSection(title = stringResource(R.string.activity_speech_to_subtitle_text_03)) {
            AppOptionSelector(
                label = stringResource(R.string.activity_speech_to_subtitle_text_03),
                value = state.languageOptions.firstOrNull { it.id == state.selectedLanguage },
                options = state.languageOptions,
                onSelected = onLanguageSelected
            )
        }

        AppSection(title = stringResource(R.string.activity_auto_timestamp_text_05)) {
            AppOptionSelector(
                label = stringResource(R.string.target_format),
                value = state.formatOptions.firstOrNull { it.id == state.selectedFormat },
                options = state.formatOptions,
                onSelected = onFormatSelected
            )
            SettingsSwitchRow(
                title = stringResource(R.string.activity_speech_to_subtitle_text_10),
                checked = state.addToAutoTranslate,
                onCheckedChange = onAddToAutoTranslateChange
            )
            SettingsSwitchRow(
                title = stringResource(R.string.activity_speech_to_subtitle_text_04),
                description = stringResource(R.string.activity_speech_to_subtitle_text_05),
                checked = state.disableVadForTxt,
                onCheckedChange = onDisableVadForTxtChange,
                enabled = state.isTxtSelected
            )
            Text(
                stringResource(R.string.activity_speech_to_subtitle_text_05),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (state.isTxtSelected) 1f else 0.6f)
            )
        }

        AppSection(title = stringResource(R.string.activity_auto_timestamp_text_06)) {
            Text(
                state.outputDirectory ?: stringResource(R.string.activity_auto_timestamp_text_07),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            OutlinedButton(onClick = onSelectOutputDirectory) {
                Text(stringResource(R.string.activity_auto_timestamp_text_08))
            }
        }

        if (!state.asrModelReady) {
            Text(
                stringResource(R.string.speech_to_subtitle_asr_model_required),
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (state.showProcessingPanel) {
            AppSection(title = stringResource(R.string.activity_speech_to_subtitle_text_06)) {
                AppTaskProgress(
                    visible = state.progressVisible,
                    progress = state.progress.coerceIn(0, 100) / 100f,
                    status = state.progressStatus,
                    onCancel = if (state.progressVisible) onCancel else null
                )
                Text(stringResource(R.string.activity_speech_to_subtitle_text_08), style = MaterialTheme.typography.titleMedium)
                AppLogBox(state.logText)
            }
        }
    }

    when (val dialog = state.dialog) {
        SpeechToSubtitleDialogUi.OutputConflict -> AppConflictDialog(
            message = stringResource(R.string.output_subtitle_conflict),
            onOverwrite = onOverwriteOutput,
            onRename = onRenameOutput,
            onCancel = onDismissDialog
        )
        SpeechToSubtitleDialogUi.CancelConfirmation -> AppAlertDialog(
            title = stringResource(R.string.operation_confirm_cancel),
            message = stringResource(R.string.speech_recognition_cancel_message),
            confirmText = stringResource(R.string.cancel_recognition),
            dismissText = stringResource(R.string.continue_recognition),
            onConfirm = onConfirmCancel,
            onDismiss = onDismissDialog
        )
        SpeechToSubtitleDialogUi.BackConfirmation -> AppAlertDialog(
            title = stringResource(R.string.recognition_in_progress),
            message = stringResource(R.string.speech_recognition_back_message),
            confirmText = stringResource(R.string.back_and_cancel),
            dismissText = stringResource(R.string.continue_recognition),
            onConfirm = onConfirmBack,
            onDismiss = onDismissDialog
        )
        is SpeechToSubtitleDialogUi.Error -> AppAlertDialog(
            title = stringResource(R.string.error),
            message = dialog.message,
            onConfirm = onDismissDialog,
            onDismiss = onDismissDialog
        )
        null -> Unit
    }
}
