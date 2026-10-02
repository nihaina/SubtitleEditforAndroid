package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
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
                modifier = Modifier.height(56.dp),
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AutoTimestampCard {
                AutoTimestampTitle(stringResource(R.string.activity_auto_timestamp_text_02))
                OutlinedButton(
                    onClick = onSelectAudio,
                    enabled = !isGenerating,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.activity_auto_timestamp_text_03))
                }
                Text(
                    text = audioFilesText,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 40.dp)
                        .alpha(if (secondaryProcessingAvailable) 1f else 0.55f)
                        .toggleable(
                            value = secondaryProcessingEnabled,
                            enabled = secondaryProcessingAvailable && !isGenerating,
                            role = Role.Switch,
                            onValueChange = onSecondaryProcessingChange
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.activity_auto_timestamp_text_12),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp
                    )
                    Switch(
                        checked = secondaryProcessingEnabled,
                        onCheckedChange = null,
                            enabled = secondaryProcessingAvailable && !isGenerating
                    )
                }
                Text(
                    text = secondaryProcessingHint,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.activity_auto_timestamp_text_14),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        .alpha(if (secondaryProcessingEnabled) 1f else 0.55f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp
                )
                OutlinedButton(
                    onClick = onSelectSubtitle,
                    enabled = secondaryProcessingEnabled && !isGenerating,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.activity_auto_timestamp_text_15))
                }
                Text(
                    text = subtitleFileText,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .alpha(if (secondaryProcessingEnabled) 1f else 0.55f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AutoTimestampCard {
                AutoTimestampTitle(stringResource(R.string.activity_auto_timestamp_text_05))
                FormatSelector(value = outputFormat, enabled = !isGenerating, onSelected = onFormatChange)
            }

            AutoTimestampCard {
                AutoTimestampTitle(stringResource(R.string.activity_auto_timestamp_text_06))
                Text(
                    text = outputDirectory,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                OutlinedButton(
                    onClick = onSelectOutputDirectory,
                    enabled = !isGenerating,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.activity_auto_timestamp_text_08))
                }
            }

            Button(
                onClick = onGenerate,
                enabled = canGenerate && !isGenerating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.activity_auto_timestamp_text_09))
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isGenerating) {
                    CircularProgressIndicator()
                }
                if (status.isNotBlank()) {
                    Text(
                        text = status,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                if (isGenerating) {
                    TextButton(onClick = onRequestCancel, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }

            AutoTimestampCard {
                AutoTimestampTitle(stringResource(R.string.activity_auto_timestamp_text_10))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(300.dp)
                        .verticalScroll(previewScrollState)
                ) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
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
private fun AutoTimestampCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun AutoTimestampTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun FormatSelector(value: String, enabled: Boolean, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
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
