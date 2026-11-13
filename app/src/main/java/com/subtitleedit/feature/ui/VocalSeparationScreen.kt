package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.demix.VocalSeparationEngine
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppChoiceTile
import com.subtitleedit.ui.components.AppConflictDialog
import com.subtitleedit.ui.components.AppLogBox
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.settings.SettingsTileGroup
import com.subtitleedit.ui.settings.SettingsTileRow
import com.subtitleedit.ui.settings.SettingsTileSection
import com.subtitleedit.ui.settings.SettingsTileSwitchRow
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.theme.AppSpacing

internal data class VocalSeparationFileUi(
    val key: String,
    val fileName: String,
    val fileSizeLabel: String
)

internal data class VocalSeparationUiState(
    val files: List<VocalSeparationFileUi> = emptyList(),
    val outputDirectory: String = "",
    val saveToSourceDirectory: Boolean = false,
    val selectedStems: Set<VocalSeparationEngine.Stem> = setOf(VocalSeparationEngine.Stem.VOCALS),
    val enabledStems: Set<VocalSeparationEngine.Stem> = emptySet(),
    val isRunning: Boolean = false,
    val progressVisible: Boolean = false,
    val progressStatus: String? = null,
    val progress: Int = 0,
    val log: String = "",
    val dialog: VocalSeparationDialog = VocalSeparationDialog.NONE,
    val errorMessage: String = ""
) {
    val hasSelectedFiles: Boolean
        get() = files.isNotEmpty()

    val canStart: Boolean
        get() = !isRunning && hasSelectedFiles && selectedStems.isNotEmpty()
}

internal enum class VocalSeparationDialog { NONE, CANCEL, BACK, OUTPUT_CONFLICT, ERROR }

@Composable
internal fun VocalSeparationScreen(
    state: VocalSeparationUiState,
    onBack: () -> Unit,
    onConfirmBack: () -> Unit,
    onSettings: () -> Unit,
    onSelectFiles: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onSaveToSourceDirectoryChange: (Boolean) -> Unit,
    onStemChange: (VocalSeparationEngine.Stem, Boolean) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOverwrite: () -> Unit,
    onAutoRename: () -> Unit,
    onDismissDialog: () -> Unit
) {
    val pageBackground = settingsPageBackground()
    AppToolScaffold(
        title = stringResource(R.string.activity_vocal_separation_title),
        onBack = onBack,
        contentBackground = pageBackground,
        topBarContainerColor = pageBackground,
        topBarScrolledContainerColor = pageBackground,
        actions = {
            IconButton(onClick = onSettings, enabled = !state.isRunning) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.activity_vocal_separation_settings_title),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        bottomBar = {
            Box(Modifier.background(pageBackground)) {
                Button(
                    onClick = onStart,
                    enabled = state.canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
                        .height(56.dp),
                    shape = CircleShape
                ) {
                    Text(
                        stringResource(R.string.activity_vocal_separation_text_08),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    ) {
        SettingsTileGroup {
            SettingsTileRow(
                title = stringResource(R.string.activity_speech_to_subtitle_text_01),
                iconRes = R.drawable.ic_file_audio,
                value = if (state.files.isEmpty()) {
                    stringResource(R.string.vocal_separation_no_files)
                } else {
                    stringResource(R.string.vocal_separation_selected_file_count, state.files.size)
                },
                enabled = !state.isRunning,
                onClick = onSelectFiles
            )
        }

        SettingsTileSection(
            title = stringResource(R.string.activity_batch_convert_text_04),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp)
        ) {
            if (state.files.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.vocal_separation_no_files),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                ) {
                    items(state.files, key = { it.key }) { file ->
                        ToolPendingFileRow(
                            fileName = file.fileName,
                            fileSizeLabel = file.fileSizeLabel,
                            onRemove = { onRemoveFile(file.key) },
                            removeEnabled = !state.isRunning && state.dialog != VocalSeparationDialog.OUTPUT_CONFLICT
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsTileSection(
            title = stringResource(R.string.activity_vocal_separation_text_02),
            subtitle = stringResource(R.string.activity_vocal_separation_text_10)
        ) {
            VocalSeparationEngine.Stem.entries.chunked(2).forEach { stems ->
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                    stems.forEach { stem ->
                        val enabled = stem in state.enabledStems && !state.isRunning
                        AppChoiceTile(
                            text = stem.displayName,
                            selected = stem in state.selectedStems,
                            enabled = enabled,
                            onClick = { onStemChange(stem, stem !in state.selectedStems) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (stems.size == 1) {
                        Column(Modifier.weight(1f)) {}
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsTileGroup {
            SettingsTileRow(
                title = stringResource(R.string.activity_tool_output_directory),
                iconRes = R.drawable.ic_folder,
                value = state.outputDirectory.ifBlank { stringResource(R.string.activity_settings_software_directory_default) },
                valueBelow = true,
                enabled = !state.isRunning && state.dialog != VocalSeparationDialog.OUTPUT_CONFLICT &&
                    !state.saveToSourceDirectory,
                onClick = onSelectOutputDirectory
            )
            SettingsTileSwitchRow(
                title = stringResource(R.string.save_to_source_folder),
                checked = state.saveToSourceDirectory,
                enabled = !state.isRunning && state.dialog != VocalSeparationDialog.OUTPUT_CONFLICT,
                onCheckedChange = onSaveToSourceDirectoryChange
            )
        }

        if (state.progressVisible) {
            Spacer(Modifier.height(12.dp))
            SettingsTileSection(title = stringResource(R.string.activity_speech_to_subtitle_text_06)) {
                AppTaskProgress(
                    visible = true,
                    progress = state.progress.coerceIn(0, 100) / 100f,
                    status = state.progressStatus.orEmpty(),
                    onCancel = onCancel
                )
            }
        }

        if (state.log.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            SettingsTileSection(title = stringResource(R.string.activity_settings_text_06)) {
                AppLogBox(state.log)
            }
        }
    }

    when (state.dialog) {
        VocalSeparationDialog.NONE -> Unit
        VocalSeparationDialog.CANCEL -> AppAlertDialog(
            title = stringResource(R.string.vocal_separation_cancel_title),
            message = stringResource(R.string.vocal_separation_cancel_message),
            confirmText = stringResource(R.string.vocal_separation_cancel_confirm),
            dismissText = stringResource(R.string.vocal_separation_cancel_dismiss),
            onConfirm = onCancel,
            onDismiss = onDismissDialog
        )
        VocalSeparationDialog.BACK -> AppAlertDialog(
            title = stringResource(R.string.vocal_separation_back_title),
            message = stringResource(R.string.vocal_separation_back_message),
            confirmText = stringResource(R.string.vocal_separation_back_confirm),
            dismissText = stringResource(R.string.vocal_separation_back_dismiss),
            onConfirm = onConfirmBack,
            onDismiss = onDismissDialog
        )
        VocalSeparationDialog.OUTPUT_CONFLICT -> AppConflictDialog(
            message = stringResource(R.string.vocal_separation_output_conflict),
            onOverwrite = onOverwrite,
            onRename = onAutoRename,
            onCancel = onDismissDialog
        )
        VocalSeparationDialog.ERROR -> AppAlertDialog(
            title = stringResource(R.string.vocal_separation_error_title),
            message = state.errorMessage,
            onConfirm = onDismissDialog,
            onDismiss = onDismissDialog
        )
    }
}
