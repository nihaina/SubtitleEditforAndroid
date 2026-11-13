package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppConflictDialog
import com.subtitleedit.ui.components.AppLogBox
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.settings.SettingsTileGroup
import com.subtitleedit.ui.settings.SettingsTileRow
import com.subtitleedit.ui.settings.SettingsTileSection
import com.subtitleedit.ui.settings.SettingsTileSwitchRow
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.settings.settingsPrimaryTextColor
import com.subtitleedit.ui.theme.AppSpacing

sealed interface SpeechToSubtitleDialogUi {
    data object OutputConflict : SpeechToSubtitleDialogUi
    data object CancelConfirmation : SpeechToSubtitleDialogUi
    data object BackConfirmation : SpeechToSubtitleDialogUi
    data class Error(val message: String) : SpeechToSubtitleDialogUi
}

data class SpeechToSubtitleFileUi(
    val key: String,
    val fileName: String,
    val fileSizeLabel: String
)

data class SpeechToSubtitleUiState(
    val languageOptions: List<AppOption<String>> = emptyList(),
    val formatOptions: List<AppOption<String>> = emptyList(),
    val selectedFiles: List<SpeechToSubtitleFileUi> = emptyList(),
    val outputDirectory: String? = null,
    val saveToSourceDirectory: Boolean = false,
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
    onRemoveFile: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onFormatSelected: (String) -> Unit,
    onAddToAutoTranslateChange: (Boolean) -> Unit,
    onDisableVadForTxtChange: (Boolean) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onSaveToSourceDirectoryChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismissDialog: () -> Unit,
    onOverwriteOutput: () -> Unit,
    onRenameOutput: () -> Unit,
    onConfirmCancel: () -> Unit,
    onConfirmBack: () -> Unit
) {
    val pageBackground = settingsPageBackground()
    val canModifyFiles = !state.isConverting && state.dialog != SpeechToSubtitleDialogUi.OutputConflict
    AppToolScaffold(
        title = stringResource(R.string.speech_to_subtitle_title),
        onBack = onNavigateBack,
        contentBackground = pageBackground,
        topBarContainerColor = pageBackground,
        topBarScrolledContainerColor = pageBackground,
        actions = {
            IconButton(onClick = onSettings, enabled = !state.isConverting) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.activity_settings_text_03),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        bottomBar = {
            Box(Modifier.background(pageBackground)) {
                Button(
                    onClick = onStart,
                    enabled = state.startEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
                        .height(56.dp),
                    shape = CircleShape
                ) {
                    Text(
                        stringResource(R.string.start_convert),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        },
    ) {
        // ── File selection & source language ──
        SettingsTileGroup {
            SettingsTileRow(
                title = stringResource(R.string.activity_speech_to_subtitle_text_01),
                iconRes = R.drawable.ic_file_audio,
                value = if (state.selectedFiles.isEmpty()) {
                    stringResource(R.string.activity_media_convert_text_03)
                } else {
                    stringResource(R.string.selected_file_count_with_colon, state.selectedFiles.size)
                },
                enabled = canModifyFiles,
                onClick = onSelectFiles
            )
            SpeechToSubtitleOptionRow(
                label = stringResource(R.string.activity_speech_to_subtitle_text_03),
                iconRes = R.drawable.ic_mic,
                value = state.languageOptions.firstOrNull { it.id == state.selectedLanguage }?.label.orEmpty(),
                options = state.languageOptions,
                onSelected = onLanguageSelected
            )
        }

        SettingsTileSection(
            title = stringResource(R.string.speech_to_subtitle_pending_files),
            subtitle = stringResource(R.string.activity_speech_to_subtitle_text_02),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp)
        ) {
            if (state.selectedFiles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.activity_media_convert_text_03),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                ) {
                    items(state.selectedFiles, key = { it.key }) { file ->
                        ToolPendingFileRow(
                            fileName = file.fileName,
                            fileSizeLabel = file.fileSizeLabel,
                            onRemove = { onRemoveFile(file.key) },
                            removeEnabled = canModifyFiles
                        )
                    }
                }
            }
        }

        // ── Output format & options ──
        SettingsTileGroup {
            SpeechToSubtitleOptionRow(
                label = stringResource(R.string.target_format),
                iconRes = R.drawable.ic_subtitle_format,
                value = state.formatOptions.firstOrNull { it.id == state.selectedFormat }?.label.orEmpty(),
                options = state.formatOptions,
                onSelected = onFormatSelected
            )
            SettingsTileSwitchRow(
                title = stringResource(R.string.activity_speech_to_subtitle_text_10),
                checked = state.addToAutoTranslate,
                onCheckedChange = onAddToAutoTranslateChange
            )
            SettingsTileSwitchRow(
                title = stringResource(R.string.activity_speech_to_subtitle_text_04),
                description = stringResource(R.string.activity_speech_to_subtitle_text_05),
                checked = state.disableVadForTxt,
                onCheckedChange = onDisableVadForTxtChange,
                enabled = state.isTxtSelected
            )
        }

        // ── Output directory ──
        SettingsTileGroup {
            SettingsTileRow(
                title = stringResource(R.string.activity_tool_output_directory),
                iconRes = R.drawable.ic_folder,
                value = state.outputDirectory ?: stringResource(R.string.activity_tool_default_output_directory),
                valueBelow = true,
                enabled = !state.isConverting && state.dialog != SpeechToSubtitleDialogUi.OutputConflict && !state.saveToSourceDirectory,
                onClick = onSelectOutputDirectory
            )
            SettingsTileSwitchRow(
                title = stringResource(R.string.save_to_source_folder),
                checked = state.saveToSourceDirectory,
                enabled = !state.isConverting && state.dialog != SpeechToSubtitleDialogUi.OutputConflict,
                onCheckedChange = onSaveToSourceDirectoryChange
            )
        }

        // ── ASR model warning ──
        if (!state.asrModelReady) {
            SettingsTileSection {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        stringResource(R.string.speech_to_subtitle_asr_model_required),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // ── Processing panel ──
        if (state.showProcessingPanel) {
            SettingsTileSection(title = stringResource(R.string.activity_speech_to_subtitle_text_06)) {
                AppTaskProgress(
                    visible = state.progressVisible,
                    progress = state.progress.coerceIn(0, 100) / 100f,
                    status = state.progressStatus,
                    onCancel = if (state.progressVisible) onCancel else null
                )
                Text(
                    stringResource(R.string.activity_speech_to_subtitle_text_08),
                    style = MaterialTheme.typography.titleMedium,
                    color = settingsPrimaryTextColor()
                )
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

/** A settings-tile-styled dropdown row for selecting a language or format option. */
@Composable
private fun <T> SpeechToSubtitleOptionRow(
    label: String,
    iconRes: Int,
    value: String,
    options: List<AppOption<T>>,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsTileRow(
            title = label,
            iconRes = iconRes,
            value = value,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelected(option.id)
                    }
                )
            }
        }
    }
}
