package com.subtitleedit.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppConflictDialog
import com.subtitleedit.ui.components.AppLogBox
import com.subtitleedit.ui.components.AppPrimaryButton
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.theme.AppMotion
import com.subtitleedit.ui.theme.AppSpacing

sealed interface SpeechToSubtitleDialogUi {
    data object OutputConflict : SpeechToSubtitleDialogUi
    data object CancelConfirmation : SpeechToSubtitleDialogUi
    data object BackConfirmation : SpeechToSubtitleDialogUi
    data class Error(val message: String) : SpeechToSubtitleDialogUi
}

data class SpeechToSubtitleUiState(
    val languageOptions: List<String> = emptyList(),
    val formatOptions: List<String> = emptyList(),
    val selectedFiles: List<String> = emptyList(),
    val outputDirectory: String? = null,
    val selectedLanguageIndex: Int = 0,
    val selectedFormatIndex: Int = 0,
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
    onLanguageSelected: (Int) -> Unit,
    onFormatSelected: (Int) -> Unit,
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
        title = "语音转字幕",
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
                        append("已选择 ${state.selectedFiles.size} 个文件：")
                        state.selectedFiles.forEachIndexed { index, fileName -> append("\n${index + 1}. $fileName") }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AppSection(title = stringResource(R.string.activity_speech_to_subtitle_text_03)) {
            SpeechSelector(
                options = state.languageOptions,
                selectedIndex = state.selectedLanguageIndex,
                onSelected = onLanguageSelected
            )
        }

        AppSection(title = stringResource(R.string.activity_auto_timestamp_text_05)) {
            SpeechSelector(
                options = state.formatOptions,
                selectedIndex = state.selectedFormatIndex,
                onSelected = onFormatSelected
            )
            SpeechToggle(
                label = stringResource(R.string.activity_speech_to_subtitle_text_10),
                checked = state.addToAutoTranslate,
                onCheckedChange = onAddToAutoTranslateChange
            )
            SpeechToggle(
                label = stringResource(R.string.activity_speech_to_subtitle_text_04),
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
            message = "输出目录中已存在同名字幕文件。请选择处理方式。",
            onOverwrite = onOverwriteOutput,
            onRename = onRenameOutput,
            onCancel = onDismissDialog
        )
        SpeechToSubtitleDialogUi.CancelConfirmation -> AppAlertDialog(
            title = "确认取消",
            message = "语音识别正在进行，确定要取消吗？",
            confirmText = "取消识别",
            dismissText = "继续识别",
            onConfirm = onConfirmCancel,
            onDismiss = onDismissDialog
        )
        SpeechToSubtitleDialogUi.BackConfirmation -> AppAlertDialog(
            title = "正在识别中",
            message = "语音识别正在进行，确定要返回吗？返回后识别将被取消。",
            confirmText = "返回并取消",
            dismissText = "继续识别",
            onConfirm = onConfirmBack,
            onDismiss = onDismissDialog
        )
        is SpeechToSubtitleDialogUi.Error -> AppAlertDialog(
            title = "错误",
            message = dialog.message,
            onConfirm = onDismissDialog,
            onDismiss = onDismissDialog
        )
        null -> Unit
    }
}

@Composable
private fun SpeechSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = AppMotion.fast(),
        label = "speech-selector-chevron"
    )
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    options.getOrNull(selectedIndex).orEmpty(),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).graphicsLayer(rotationZ = rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelected(index)
                    }
                )
            }
        }
    }
}

@Composable
private fun SpeechToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
