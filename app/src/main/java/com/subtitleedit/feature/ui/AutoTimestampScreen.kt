package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

internal enum class AutoTimestampDialog { NONE, OUTPUT_CONFLICT, CANCEL_GENERATION, BACK_WHILE_GENERATING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoTimestampScreen(
    audioFilesText: String,
    subtitleFileText: String,
    outputDirectory: String,
    secondaryProcessingEnabled: Boolean,
    secondaryProcessingAvailable: Boolean,
    secondaryProcessingHint: String,
    outputFormat: String,
    isGenerating: Boolean,
    canGenerate: Boolean,
    status: String,
    preview: String,
    dialog: AutoTimestampDialog,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onSelectAudio: () -> Unit,
    onSecondaryProcessingChange: (Boolean) -> Unit,
    onSelectSubtitle: () -> Unit,
    onFormatChange: (String) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onGenerate: () -> Unit,
    onRequestCancel: () -> Unit,
    onConfirmCancel: () -> Unit,
    onOverwrite: () -> Unit,
    onRename: () -> Unit,
    onDismissDialog: () -> Unit
) {
    val previewScrollState = rememberScrollState()
    LaunchedEffect(preview) {
        previewScrollState.scrollTo(previewScrollState.maxValue)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自动打轴") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onSettings, enabled = !isGenerating) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "打轴设置")
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("音频文件", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onSelectAudio, enabled = !isGenerating) {
                Text("选择音频或视频")
            }
            Text(audioFilesText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("二次处理", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = secondaryProcessingEnabled,
                    onCheckedChange = onSecondaryProcessingChange,
                    enabled = secondaryProcessingAvailable && !isGenerating
                )
            }
            Text(secondaryProcessingHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("参考字幕", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = onSelectSubtitle,
                enabled = secondaryProcessingEnabled && !isGenerating
            ) {
                Text("选择 SRT 或 LRC 字幕")
            }
            Text(subtitleFileText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()

            Text("输出格式", style = MaterialTheme.typography.titleMedium)
            FormatSelector(value = outputFormat, enabled = !isGenerating, onSelected = onFormatChange)

            HorizontalDivider()

            Text("输出目录", style = MaterialTheme.typography.titleMedium)
            Text(outputDirectory, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onSelectOutputDirectory, enabled = !isGenerating) {
                Text("选择输出目录")
            }

            Button(
                onClick = onGenerate,
                enabled = canGenerate && !isGenerating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("开始打轴")
            }

            if (isGenerating) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = onRequestCancel, modifier = Modifier.align(Alignment.End)) {
                    Text("取消处理")
                }
            }
            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()

            Text("处理日志与结果", style = MaterialTheme.typography.titleMedium)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 300.dp)
                    .verticalScroll(previewScrollState)
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    when (dialog) {
        AutoTimestampDialog.NONE -> Unit
        AutoTimestampDialog.OUTPUT_CONFLICT -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("文件名冲突") },
            text = { Text("输出目录中已存在同名字幕文件。请选择处理方式。") },
            confirmButton = {
                Row {
                    TextButton(onClick = onOverwrite) { Text("覆盖") }
                    TextButton(onClick = onRename) { Text("自动重命名") }
                    TextButton(onClick = onDismissDialog) { Text("取消") }
                }
            }
        )
        AutoTimestampDialog.CANCEL_GENERATION -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("确认取消") },
            text = { Text("自动打轴正在进行，确定要取消吗？") },
            confirmButton = { TextButton(onClick = onConfirmCancel) { Text("取消处理") } },
            dismissButton = { TextButton(onClick = onDismissDialog) { Text("继续处理") } }
        )
        AutoTimestampDialog.BACK_WHILE_GENERATING -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("正在处理中") },
            text = { Text("自动打轴正在进行，确定要返回吗？返回后处理将被取消。") },
            confirmButton = { TextButton(onClick = onConfirmCancel) { Text("返回并取消") } },
            dismissButton = { TextButton(onClick = onDismissDialog) { Text("继续处理") } }
        )
    }
}

@Composable
private fun FormatSelector(value: String, enabled: Boolean, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(value)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("SRT", "LRC").forEach { format ->
                DropdownMenuItem(
                    text = { Text(format) },
                    onClick = {
                        expanded = false
                        onSelected(format)
                    }
                )
            }
        }
    }
}
