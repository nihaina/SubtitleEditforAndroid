package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppConflictDialog
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.settings.MaterialSettingsTileTheme
import com.subtitleedit.ui.settings.SettingsIconBox
import com.subtitleedit.ui.settings.SettingsTileGroup
import com.subtitleedit.ui.settings.SettingsTileRow
import com.subtitleedit.ui.settings.SettingsTileSection
import com.subtitleedit.ui.settings.SettingsTileSwitchRow
import com.subtitleedit.ui.theme.AppSpacing
import com.subtitleedit.util.SubtitleParser

data class BatchConvertFileUi(
    val key: String,
    val fileName: String,
    val fileSizeLabel: String
)

sealed interface BatchConvertDialogUi {
    data object Conflict : BatchConvertDialogUi
    data class Result(val message: String) : BatchConvertDialogUi
}

@Composable
fun BatchConvertScreen(
    files: List<BatchConvertFileUi>,
    formats: List<AppOption<SubtitleParser.SubtitleFormat>>,
    selectedFormat: SubtitleParser.SubtitleFormat,
    outputDirectoryLabel: String,
    saveToSourceDirectory: Boolean,
    dialog: BatchConvertDialogUi?,
    onNavigateBack: () -> Unit,
    onSelectFiles: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onSelectFormat: (SubtitleParser.SubtitleFormat) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onSaveToSourceDirectoryChange: (Boolean) -> Unit,
    onStartConversion: () -> Unit,
    onDismissDialog: () -> Unit,
    onOverwriteConflicts: () -> Unit,
    onRenameConflicts: () -> Unit
) {
    val pageBackground = MaterialTheme.colorScheme.background
    MaterialSettingsTileTheme {
        AppToolScaffold(
            title = stringResource(R.string.batch_convert),
            onBack = onNavigateBack,
            contentBackground = pageBackground,
            topBarContainerColor = pageBackground,
            topBarScrolledContainerColor = pageBackground,
            bottomBar = {
                Box(Modifier.background(pageBackground)) {
                    Button(
                        onClick = onStartConversion,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
                            .height(56.dp),
                        shape = CircleShape
                    ) {
                        Text(stringResource(R.string.start_convert), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        ) {
            SettingsTileGroup {
                SettingsTileRow(
                    title = stringResource(R.string.batch_convert_select_subtitle_files),
                    iconRes = R.drawable.ic_file_text,
                    value = if (files.isEmpty()) {
                        stringResource(R.string.activity_media_convert_text_03)
                    } else {
                        stringResource(R.string.batch_convert_selected_file_count, files.size)
                    },
                    onClick = onSelectFiles
                )
                BatchConvertFormatRow(
                    label = stringResource(R.string.target_format),
                    value = formats.firstOrNull { it.id == selectedFormat }?.label.orEmpty(),
                    formats = formats,
                    onSelected = onSelectFormat
                )
            }

            SettingsTileSection(title = stringResource(R.string.activity_batch_convert_text_04)) {
                if (files.isEmpty()) {
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
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                    ) {
                        items(files, key = { it.key }) { file ->
                            BatchConvertFileRow(file = file, onRemove = { onRemoveFile(file.key) })
                        }
                    }
                }
            }

            SettingsTileGroup {
                SettingsTileRow(
                    title = stringResource(R.string.activity_tool_output_directory),
                    iconRes = R.drawable.ic_folder,
                    value = outputDirectoryLabel,
                    valueBelow = true,
                    enabled = dialog != BatchConvertDialogUi.Conflict && !saveToSourceDirectory,
                    onClick = onSelectOutputDirectory
                )
                SettingsTileSwitchRow(
                    title = stringResource(R.string.save_to_source_folder),
                    checked = saveToSourceDirectory,
                    enabled = dialog != BatchConvertDialogUi.Conflict,
                    onCheckedChange = onSaveToSourceDirectoryChange
                )
            }
        }
    }

    when (dialog) {
        BatchConvertDialogUi.Conflict -> AppConflictDialog(
            message = stringResource(R.string.output_subtitle_conflict),
            onOverwrite = onOverwriteConflicts,
            onRename = onRenameConflicts,
            onCancel = onDismissDialog
        )
        is BatchConvertDialogUi.Result -> AppAlertDialog(
            title = stringResource(R.string.batch_convert_result_title),
            message = dialog.message,
            onConfirm = onDismissDialog,
            onDismiss = onDismissDialog
        )
        null -> Unit
    }
}

@Composable
private fun BatchConvertFormatRow(
    label: String,
    value: String,
    formats: List<AppOption<SubtitleParser.SubtitleFormat>>,
    onSelected: (SubtitleParser.SubtitleFormat) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsTileRow(
            title = label,
            iconRes = R.drawable.ic_subtitle_format,
            value = value,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            formats.forEach { option ->
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

@Composable
private fun BatchConvertFileRow(file: BatchConvertFileUi, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconBox(R.drawable.ic_file_text)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            Text(
                text = file.fileSizeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}
