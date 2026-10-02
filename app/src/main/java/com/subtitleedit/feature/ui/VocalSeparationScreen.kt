package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.subtitleedit.ui.components.AppPrimaryButton
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.theme.AppSpacing

internal data class VocalSeparationUiState(
    val selectedFilesText: String = "未选择文件",
    val hasSelectedFiles: Boolean = false,
    val outputDirectory: String = "",
    val selectedStems: Set<VocalSeparationEngine.Stem> = setOf(VocalSeparationEngine.Stem.VOCALS),
    val enabledStems: Set<VocalSeparationEngine.Stem> = emptySet(),
    val isRunning: Boolean = false,
    val progressVisible: Boolean = false,
    val progressStatus: String = "正在准备...",
    val progress: Int = 0,
    val log: String = "",
    val dialog: VocalSeparationDialog = VocalSeparationDialog.NONE,
    val errorMessage: String = ""
) {
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
    onSelectOutputDirectory: () -> Unit,
    onStemChange: (VocalSeparationEngine.Stem, Boolean) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOverwrite: () -> Unit,
    onAutoRename: () -> Unit,
    onDismissDialog: () -> Unit
) {
    AppToolScaffold(
        title = "人声分离",
        onBack = onBack,
        actions = {
            IconButton(onClick = onSettings, enabled = !state.isRunning) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "人声分离设置")
            }
        },
        bottomBar = {
            AppPrimaryButton(
                text = stringResource(R.string.activity_vocal_separation_text_08),
                onClick = onStart,
                enabled = state.canStart,
                modifier = Modifier.padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
            )
        }
    ) {
        AppSection(title = stringResource(R.string.activity_speech_to_subtitle_text_01)) {
            OutlinedButton(onClick = onSelectFiles, enabled = !state.isRunning) {
                Text(stringResource(R.string.select_file))
            }
            Text(state.selectedFilesText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AppSection(title = stringResource(R.string.activity_vocal_separation_text_02)) {
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
            Text(
                stringResource(R.string.activity_vocal_separation_text_10),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AppSection(title = stringResource(R.string.activity_auto_timestamp_text_06)) {
            Text(state.outputDirectory, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onSelectOutputDirectory, enabled = !state.isRunning) {
                Text(stringResource(R.string.activity_auto_timestamp_text_08))
            }
        }

        if (state.progressVisible) {
            AppSection(title = stringResource(R.string.activity_speech_to_subtitle_text_06)) {
                AppTaskProgress(
                    visible = true,
                    progress = state.progress.coerceIn(0, 100) / 100f,
                    status = state.progressStatus,
                    onCancel = onCancel
                )
            }
        }

        AppSection(title = stringResource(R.string.activity_settings_text_06)) {
            AppLogBox(state.log)
        }
    }

    when (state.dialog) {
        VocalSeparationDialog.NONE -> Unit
        VocalSeparationDialog.CANCEL -> AppAlertDialog(
            title = "确认取消",
            message = "人声分离正在进行，确定要取消吗？",
            confirmText = "取消分离",
            dismissText = "继续分离",
            onConfirm = onCancel,
            onDismiss = onDismissDialog
        )
        VocalSeparationDialog.BACK -> AppAlertDialog(
            title = "正在分离中",
            message = "人声分离正在进行，确定要返回吗？返回后任务将被取消。",
            confirmText = "返回并取消",
            dismissText = "继续分离",
            onConfirm = onConfirmBack,
            onDismiss = onDismissDialog
        )
        VocalSeparationDialog.OUTPUT_CONFLICT -> AppConflictDialog(
            message = "输出目录中已有同名音频文件。请选择处理方式。",
            onOverwrite = onOverwrite,
            onRename = onAutoRename,
            onCancel = onDismissDialog
        )
        VocalSeparationDialog.ERROR -> AppAlertDialog(
            title = "人声分离失败",
            message = state.errorMessage,
            onConfirm = onDismissDialog,
            onDismiss = onDismissDialog
        )
    }
}
