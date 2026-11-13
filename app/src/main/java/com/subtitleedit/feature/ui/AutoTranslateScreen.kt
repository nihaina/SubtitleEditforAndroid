package com.subtitleedit.feature.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppConflictDialog
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.settings.MaterialSettingsTileTheme
import com.subtitleedit.ui.settings.SettingsIconBox
import com.subtitleedit.ui.settings.SettingsTileGroup
import com.subtitleedit.ui.settings.SettingsTileRow
import com.subtitleedit.ui.settings.SettingsTileSection
import com.subtitleedit.ui.settings.SettingsTileSwitchRow
import com.subtitleedit.ui.settings.settingsChipColor
import com.subtitleedit.ui.settings.settingsLightPalette
import com.subtitleedit.ui.settings.settingsPrimaryTextColor
import com.subtitleedit.ui.theme.AppMotion
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
    val canRetry: Boolean,
    val canPause: Boolean
)

data class AutoTranslateUiState(
    val files: List<AutoTranslateFileUi> = emptyList(),
    val outputDirectory: String = "",
    val saveToSourceDirectory: Boolean = false,
    val punctuationPredictionEnabled: Boolean = false,
    val translationEnabled: Boolean = true,
    val queueRunning: Boolean = false,
    val hasWaitingFiles: Boolean = false,
    val canStartQueue: Boolean = true,
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
    onSaveToSourceDirectoryChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onFileClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onConfirmRemove: () -> Unit,
    onDismissRemove: () -> Unit,
    onDismissOutputConflict: () -> Unit,
    onOverwriteOutput: () -> Unit,
    onRenameOutput: () -> Unit,
    onConfirmExit: () -> Unit,
    onDismissExit: () -> Unit
) {
    val pageBackground = MaterialTheme.colorScheme.background
    MaterialSettingsTileTheme {
        AppToolScaffold(
            title = stringResource(R.string.auto_translate),
            onBack = onNavigateBack,
            contentBackground = pageBackground,
            topBarContainerColor = pageBackground,
            topBarScrolledContainerColor = pageBackground,
            actions = {
                IconButton(onClick = onSettings) {
                    Icon(
                        painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.ai_settings_content_description),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            bottomBar = {
                Box(Modifier.background(pageBackground)) {
                    Button(
                        onClick = onStart,
                        enabled = state.canStartQueue && (state.hasWaitingFiles || state.files.isEmpty()),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
                            .height(56.dp),
                        shape = CircleShape
                    ) {
                        Text(
                            stringResource(R.string.activity_auto_translate_text_06),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        ) {
            SettingsTileGroup {
                SettingsTileRow(
                    title = stringResource(R.string.batch_convert_select_subtitle_files),
                    iconRes = R.drawable.ic_file_text,
                    value = if (state.files.isEmpty()) {
                        stringResource(R.string.activity_media_convert_text_03)
                    } else {
                        stringResource(R.string.batch_convert_selected_file_count, state.files.size)
                    },
                    onClick = onSelectFiles
                )
            }

            SettingsTileSection(title = stringResource(R.string.activity_auto_translate_text_01)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                ) {
                    SettingSwitchTile(
                        label = stringResource(R.string.activity_auto_translate_text_08),
                        checked = state.punctuationPredictionEnabled,
                        onCheckedChange = onPunctuationPredictionChange,
                        modifier = Modifier.weight(1f)
                    )
                    SettingSwitchTile(
                        label = stringResource(R.string.activity_auto_translate_text_09),
                        checked = state.translationEnabled,
                        onCheckedChange = onTranslationChange,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SettingsTileSection(title = stringResource(R.string.activity_auto_translate_text_04)) {
                AnimatedVisibility(
                    visible = state.queueRunning,
                    enter = expandVertically(animationSpec = AppMotion.enter()) + fadeIn(animationSpec = AppMotion.enter()),
                    exit = shrinkVertically(animationSpec = AppMotion.exit()) + fadeOut(animationSpec = AppMotion.exit())
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(12.dp)
                    ) {
                        AppTaskProgress(
                            visible = true,
                            progress = null,
                            status = state.progressSummary
                        )
                    }
                }

                if (state.files.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                    ) {
                        items(state.files, key = AutoTranslateFileUi::key) { file ->
                            AutoTranslateFileRow(
                                file = file,
                                onClick = { if (file.canRetry || file.canPause) onFileClick(file.key) },
                                onRemove = { onRemove(file.key) }
                            )
                        }
                    }
                }
            }

            SettingsTileGroup {
                SettingsTileRow(
                    title = stringResource(R.string.activity_tool_output_directory),
                    iconRes = R.drawable.ic_folder,
                    value = state.outputDirectory,
                    valueBelow = true,
                    enabled = !state.queueRunning && !state.showOutputConflict && !state.saveToSourceDirectory,
                    onClick = onSelectOutputDirectory
                )
                SettingsTileSwitchRow(
                    title = stringResource(R.string.save_to_source_folder),
                    checked = state.saveToSourceDirectory,
                    enabled = !state.queueRunning && !state.showOutputConflict,
                    onCheckedChange = onSaveToSourceDirectoryChange
                )
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
private fun SettingSwitchTile(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val light = settingsLightPalette()
    val baseContainer = settingsChipColor()
    val activeContainer = if (light) {
        lerp(baseContainer, colors.primaryContainer, 0.72f)
    } else {
        lerp(baseContainer, colors.primaryContainer, 0.5f)
    }
    val containerColor by animateColorAsState(
        targetValue = if (checked) activeContainer else baseContainer,
        animationSpec = AppMotion.fast(),
        label = "autoTranslateFeatureContainer"
    )
    val textColor by animateColorAsState(
        targetValue = if (checked) colors.onPrimaryContainer else settingsPrimaryTextColor(),
        animationSpec = AppMotion.fast(),
        label = "autoTranslateFeatureText"
    )
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(containerColor)
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = {
                    haptic.performHapticFeedback(
                        if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
                    )
                    onCheckedChange(it)
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            thumbContent = if (checked) {
                {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            } else {
                null
            }
        )
    }
}

@Composable
private fun AutoTranslateFileRow(
    file: AutoTranslateFileUi,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val interactive = file.canRetry || file.canPause
    val isActive = file.canPause || file.showStageProgress
    val isCompleted = !file.canPause && !file.canRetry && !file.showStageProgress &&
        file.totalLines > 0 && file.processedLines >= file.totalLines
    val colors = MaterialTheme.colorScheme
    val statusColor = when {
        isActive -> colors.primary
        isCompleted -> colors.tertiary
        else -> colors.onSurfaceVariant
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.large)
        .background(colors.surfaceContainerHighest)
        .then(
            if (isActive) {
                Modifier.border(
                    width = 1.dp,
                    color = colors.primary.copy(alpha = 0.45f),
                    shape = MaterialTheme.shapes.large
                )
            } else {
                Modifier
            }
        )
        .then(if (interactive) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconBox(R.drawable.ic_file_text)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            Text(
                text = stringResource(
                    R.string.subtitle_file_progress_summary,
                    file.fileSizeLabel,
                    file.totalLines,
                    file.processedLines
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            AnimatedVisibility(visible = file.showStageProgress && file.totalLines > 0) {
                val targetProgress = (file.processedLines.toFloat() / file.totalLines.coerceAtLeast(1).toFloat())
                    .coerceIn(0f, 1f)
                val animatedProgress by animateFloatAsState(
                    targetValue = targetProgress,
                    animationSpec = AppMotion.enter(),
                    label = "autoTranslateFileProgress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                )
            }
            Text(
                text = if (file.statusMessage.isBlank()) file.status else "${file.status}：${file.statusMessage}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isActive || isCompleted) FontWeight.Medium else FontWeight.Normal,
                color = statusColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.delete),
                tint = colors.error
            )
        }
    }
}


