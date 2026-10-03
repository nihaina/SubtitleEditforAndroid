package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppAlertDialog
import com.subtitleedit.ui.components.AppChoiceTile
import com.subtitleedit.ui.components.AppConflictDialog
import com.subtitleedit.ui.components.AppOptionSelector
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.AppPrimaryButton
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppToolScaffold
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
    outputDirectoryLabel: String?,
    dialog: BatchConvertDialogUi?,
    onNavigateBack: () -> Unit,
    onSelectFiles: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onSelectFormat: (SubtitleParser.SubtitleFormat) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onStartConversion: () -> Unit,
    onDismissDialog: () -> Unit,
    onOverwriteConflicts: () -> Unit,
    onRenameConflicts: () -> Unit
) {
    AppToolScaffold(
        title = stringResource(R.string.batch_convert),
        onBack = onNavigateBack,
        bottomBar = {
            AppPrimaryButton(
                text = stringResource(R.string.start_convert),
                onClick = onStartConversion,
                modifier = Modifier.padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner)
            )
        }
    ) {
        AppSection(title = stringResource(R.string.batch_convert_select_subtitle_files)) {
            OutlinedButton(onClick = onSelectFiles) {
                Text(stringResource(R.string.activity_auto_timestamp_text_03))
            }
            Text(
                text = if (files.isEmpty()) {
                    stringResource(R.string.activity_media_convert_text_03)
                } else {
                    stringResource(R.string.batch_convert_selected_file_count, files.size)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AppSection(title = stringResource(R.string.activity_batch_convert_text_01)) {
            AppOptionSelector(
                label = stringResource(R.string.target_format),
                value = formats.firstOrNull { it.id == selectedFormat },
                options = formats,
                onSelected = onSelectFormat
            )
        }

        AppSection(title = stringResource(R.string.activity_batch_convert_text_04)) {
            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(vertical = AppSpacing.Inner),
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
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                ) {
                    items(files, key = { it.key }) { file ->
                        BatchConvertFileRow(file = file, onRemove = { onRemoveFile(file.key) })
                    }
                }
            }
        }

        AppSection(title = stringResource(R.string.activity_auto_timestamp_text_06)) {
            Text(
                text = outputDirectoryLabel
                    ?: stringResource(R.string.activity_auto_timestamp_text_07),
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
private fun BatchConvertFileRow(file: BatchConvertFileUi, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_file),
                contentDescription = stringResource(R.string.file_name),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = AppSpacing.Inner)) {
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
