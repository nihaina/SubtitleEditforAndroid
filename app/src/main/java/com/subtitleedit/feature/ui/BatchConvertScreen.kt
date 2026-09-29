package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

data class BatchConvertFileUi(
    val key: String,
    val fileName: String,
    val fileSizeLabel: String
)

sealed interface BatchConvertDialogUi {
    data object Conflict : BatchConvertDialogUi
    data class Result(val message: String) : BatchConvertDialogUi
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchConvertScreen(
    files: List<BatchConvertFileUi>,
    formats: List<String>,
    selectedFormatIndex: Int,
    outputDirectoryLabel: String?,
    dialog: BatchConvertDialogUi?,
    onNavigateBack: () -> Unit,
    onSelectFiles: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onSelectFormat: (Int) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onStartConversion: () -> Unit,
    onDismissDialog: () -> Unit,
    onOverwriteConflicts: () -> Unit,
    onRenameConflicts: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.batch_convert)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.tools_navigate_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle(stringResource(R.string.batch_convert_select_subtitle_files))
                    OutlinedButton(
                        onClick = onSelectFiles,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_file),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.activity_auto_timestamp_text_03),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Text(
                        text = if (files.isEmpty()) {
                            stringResource(R.string.activity_media_convert_text_03)
                        } else {
                            stringResource(R.string.batch_convert_selected_file_count, files.size)
                        },
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle(stringResource(R.string.activity_batch_convert_text_01))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.target_format),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FormatSelector(
                            formats = formats,
                            selectedIndex = selectedFormatIndex,
                            onSelected = onSelectFormat,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle(stringResource(R.string.activity_batch_convert_text_04))
                    if (files.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(files, key = { it.key }) { file ->
                                BatchConvertFileRow(file = file, onRemove = { onRemoveFile(file.key) })
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle(stringResource(R.string.activity_auto_timestamp_text_06))
                    Text(
                        text = outputDirectoryLabel
                            ?: stringResource(R.string.activity_auto_timestamp_text_07),
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    OutlinedButton(
                        onClick = onSelectOutputDirectory,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.activity_auto_timestamp_text_08))
                    }
                }
            }

            Button(
                onClick = onStartConversion,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.start_convert))
            }
        }
    }

    when (dialog) {
        BatchConvertDialogUi.Conflict -> ConflictDialog(
            onDismiss = onDismissDialog,
            onOverwrite = onOverwriteConflicts,
            onRename = onRenameConflicts
        )
        is BatchConvertDialogUi.Result -> ResultDialog(
            message = dialog.message,
            onDismiss = onDismissDialog
        )
        null -> Unit
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun FormatSelector(
    formats: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = formats.getOrNull(selectedIndex).orEmpty(),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer(rotationZ = 90f)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            formats.forEachIndexed { index, format ->
                DropdownMenuItem(
                    text = { Text(format) },
                    onClick = {
                        onSelected(index)
                        expanded = false
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
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_file),
            contentDescription = stringResource(R.string.file_name),
            modifier = Modifier
                .padding(4.dp)
                .size(32.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = file.fileSizeLabel,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
private fun ConflictDialog(
    onDismiss: () -> Unit,
    onOverwrite: () -> Unit,
    onRename: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文件名冲突") },
        text = { Text("输出目录中已存在同名字幕文件。请选择处理方式。") },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onRename) { Text("自动重命名") }
                TextButton(onClick = onOverwrite) { Text("覆盖") }
            }
        }
    )
}

@Composable
private fun ResultDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量转换结果") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
        }
    )
}
