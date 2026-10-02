package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AnimatedProgress
import com.subtitleedit.demix.VocalSeparationEngine

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

@OptIn(ExperimentalMaterial3Api::class)
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
    val logScrollState = rememberScrollState()
    LaunchedEffect(state.log) { logScrollState.scrollTo(logScrollState.maxValue) }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.height(56.dp),
                title = { Text("人声分离") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onSettings, enabled = !state.isRunning) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "人声分离设置")
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
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        stringResource(R.string.activity_speech_to_subtitle_text_01),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = onSelectFiles,
                        enabled = !state.isRunning,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text(stringResource(R.string.select_file))
                    }
                    SelectionContainer {
                        Text(
                            state.selectedFilesText,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        stringResource(R.string.activity_vocal_separation_text_02),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    VocalSeparationEngine.Stem.entries.chunked(2).forEachIndexed { index, rowStems ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = if (index == 0) 6.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowStems.forEach { stem ->
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .toggleable(
                                        value = stem in state.selectedStems,
                                        enabled = stem in state.enabledStems && !state.isRunning,
                                        role = Role.Checkbox,
                                        onValueChange = { onStemChange(stem, it) }
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = stem in state.selectedStems,
                                    enabled = stem in state.enabledStems && !state.isRunning,
                                    onCheckedChange = null
                                )
                                Text(
                                    text = stem.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (stem in state.enabledStems) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.activity_auto_timestamp_text_06),
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                SelectionContainer {
                    Text(
                        state.outputDirectory,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onSelectOutputDirectory,
                    enabled = !state.isRunning,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Text(stringResource(R.string.activity_auto_timestamp_text_08))
                }
            }
            }

            Button(
                onClick = onStart,
                enabled = state.canStart,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Text(stringResource(R.string.activity_vocal_separation_text_08))
            }

            if (state.progressVisible) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(
                        state.progressStatus,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AnimatedProgress(
                        visible = true,
                        progress = state.progress.coerceIn(0, 100) / 100f,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }

            Text(
                stringResource(R.string.activity_settings_text_06),
                modifier = Modifier.padding(top = 16.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(240.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(logScrollState)
                        .padding(12.dp)
                ) {
                    Text(
                        state.log,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Text(
                stringResource(R.string.activity_vocal_separation_text_10),
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    when (state.dialog) {
        VocalSeparationDialog.NONE -> Unit
        VocalSeparationDialog.CANCEL -> ConfirmDialog(
            title = "确认取消",
            message = "人声分离正在进行，确定要取消吗？",
            positive = "取消分离",
            onPositive = onCancel,
            negative = "继续分离",
            onDismiss = onDismissDialog
        )
        VocalSeparationDialog.BACK -> ConfirmDialog(
            title = "正在分离中",
            message = "人声分离正在进行，确定要返回吗？返回后任务将被取消。",
            positive = "返回并取消",
            onPositive = onConfirmBack,
            negative = "继续分离",
            onDismiss = onDismissDialog
        )
        VocalSeparationDialog.OUTPUT_CONFLICT -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("文件名冲突") },
            text = { Text("输出目录中已有同名音频文件。请选择处理方式。") },
            confirmButton = {
                Row {
                    TextButton(onClick = onOverwrite) { Text("覆盖") }
                    TextButton(onClick = onAutoRename) { Text("自动重命名") }
                    TextButton(onClick = onDismissDialog) { Text("取消") }
                }
            }
        )
        VocalSeparationDialog.ERROR -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("人声分离失败") },
            text = { Text(state.errorMessage) },
            confirmButton = { TextButton(onClick = onDismissDialog) { Text("确定") } }
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    positive: String,
    onPositive: () -> Unit,
    negative: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onPositive) { Text(positive) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(negative) } }
    )
}
