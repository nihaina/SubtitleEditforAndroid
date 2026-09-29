package com.subtitleedit.feature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

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
    val canRetry: Boolean
)

data class AutoTranslateUiState(
    val files: List<AutoTranslateFileUi> = emptyList(),
    val outputDirectory: String? = null,
    val punctuationPredictionEnabled: Boolean = false,
    val translationEnabled: Boolean = true,
    val queueRunning: Boolean = false,
    val progressSummary: String = "",
    val showOutputConflict: Boolean = false,
    val showExitConfirmation: Boolean = false,
    val removeFileName: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoTranslateScreen(
    state: AutoTranslateUiState,
    onNavigateBack: () -> Unit,
    onSettings: () -> Unit,
    onSelectFiles: () -> Unit,
    onPunctuationPredictionChange: (Boolean) -> Unit,
    onTranslationChange: (Boolean) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onStart: () -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onConfirmRemove: () -> Unit,
    onDismissRemove: () -> Unit,
    onDismissOutputConflict: () -> Unit,
    onOverwriteOutput: () -> Unit,
    onRenameOutput: () -> Unit,
    onConfirmExit: () -> Unit,
    onDismissExit: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auto_translate)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.tools_navigate_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = "AI 设置"
                        )
                    }
                }
            )
        }
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AutoTranslateSection {
                Text(
                    text = stringResource(R.string.batch_convert_select_subtitle_files),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(
                    onClick = onSelectFiles,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.activity_auto_translate_text_05),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Text(
                    text = if (state.files.isEmpty()) {
                        stringResource(R.string.activity_media_convert_text_03)
                    } else {
                        stringResource(R.string.batch_convert_selected_file_count, state.files.size)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            AutoTranslateSection {
                Text(
                    text = stringResource(R.string.activity_auto_translate_text_01),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SettingSwitch(
                        label = stringResource(R.string.activity_auto_translate_text_08),
                        checked = state.punctuationPredictionEnabled,
                        onCheckedChange = onPunctuationPredictionChange,
                        enabled = true,
                        modifier = Modifier.weight(1f)
                    )
                    SettingSwitch(
                        label = stringResource(R.string.activity_auto_translate_text_09),
                        checked = state.translationEnabled,
                        onCheckedChange = onTranslationChange,
                        enabled = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            AutoTranslateSection {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.activity_auto_translate_text_04),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${state.files.size}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                if (state.files.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.files, key = AutoTranslateFileUi::key) { file ->
                            AutoTranslateFileRow(
                                file = file,
                                onClick = { if (file.canRetry) onRetry(file.key) },
                                onRemove = { onRemove(file.key) }
                            )
                        }
                    }
                }
            }

            AutoTranslateSection {
                Text(
                    text = stringResource(R.string.activity_auto_timestamp_text_06),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = state.outputDirectory
                        ?: stringResource(R.string.activity_auto_translate_text_03),
                    modifier = Modifier.padding(top = 6.dp),
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

            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.queueRunning
            ) {
                Text(stringResource(R.string.activity_auto_translate_text_06))
            }
            if (state.queueRunning && state.progressSummary.isNotBlank()) {
                Text(
                    text = state.progressSummary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (state.showOutputConflict) {
        AlertDialog(
            onDismissRequest = onDismissOutputConflict,
            title = { Text("文件名冲突") },
            text = { Text("输出目录中已存在同名字幕文件。请选择处理方式。") },
            confirmButton = {
                TextButton(onClick = onOverwriteOutput) { Text("覆盖") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onRenameOutput) { Text("自动重命名") }
                    TextButton(onClick = onDismissOutputConflict) { Text("取消") }
                }
            }
        )
    }

    if (state.removeFileName != null) {
        AlertDialog(
            onDismissRequest = onDismissRemove,
            title = { Text("移除文件") },
            text = { Text("${state.removeFileName} 正在处理，是否移除？") },
            confirmButton = {
                TextButton(onClick = onConfirmRemove) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = onDismissRemove) { Text("取消") }
            }
        )
    }

    if (state.showExitConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissExit,
            title = { Text("处理进行中") },
            text = { Text("退出将停止正在进行的处理，已完成的文件会保留。确定退出吗？") },
            confirmButton = {
                TextButton(onClick = onConfirmExit) { Text("停止并退出") }
            },
            dismissButton = {
                TextButton(onClick = onDismissExit) { Text("继续处理") }
            }
        )
    }
}

@Composable
private fun AutoTranslateSection(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun AutoTranslateFileRow(
    file: AutoTranslateFileUi,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = file.canRetry, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = file.fileSizeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (file.progressLabel == null) {
                            "字幕 ${file.totalLines}"
                        } else {
                            "字幕 ${file.totalLines} · 已处理 ${file.processedLines}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (file.statusMessage.isBlank()) file.status else "${file.status}：${file.statusMessage}",
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        file.canRetry -> MaterialTheme.colorScheme.error
                        file.status == "已完成" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (file.showStageProgress && file.progressLabel != null && file.totalLines > 0) {
                    LinearProgressIndicator(
                        progress = { (file.processedLines.toFloat() / file.totalLines).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                }
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
}
