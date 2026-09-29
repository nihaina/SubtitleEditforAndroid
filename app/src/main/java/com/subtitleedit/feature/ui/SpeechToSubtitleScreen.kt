package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

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

@OptIn(ExperimentalMaterial3Api::class)
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
    val logScrollState = rememberScrollState()
    LaunchedEffect(state.logText) {
        withFrameNanos { }
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语音转字幕") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.tools_navigate_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings, enabled = !state.isConverting) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.activity_settings_text_03)
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_text_01),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_text_02),
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = onSelectFiles,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Text(stringResource(R.string.activity_auto_timestamp_text_03))
                }
                Text(
                    text = if (state.selectedFiles.isEmpty()) {
                        stringResource(R.string.activity_media_convert_text_03)
                    } else {
                        buildString {
                            append("已选择 ${state.selectedFiles.size} 个文件：")
                            state.selectedFiles.forEachIndexed { index, fileName ->
                                append("\n${index + 1}. $fileName")
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            HorizontalDivider()

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_text_03),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                SpeechSelector(
                    label = stringResource(R.string.activity_speech_to_subtitle_text_03),
                    options = state.languageOptions,
                    selectedIndex = state.selectedLanguageIndex,
                    onSelected = onLanguageSelected,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            HorizontalDivider()

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.activity_auto_timestamp_text_05),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                SpeechSelector(
                    label = stringResource(R.string.activity_auto_timestamp_text_05),
                    options = state.formatOptions,
                    selectedIndex = state.selectedFormatIndex,
                    onSelected = onFormatSelected,
                    modifier = Modifier.padding(top = 8.dp)
                )
                SpeechToggle(
                    label = stringResource(R.string.activity_speech_to_subtitle_text_10),
                    checked = state.addToAutoTranslate,
                    onCheckedChange = onAddToAutoTranslateChange,
                    modifier = Modifier.padding(top = 8.dp)
                )
                SpeechToggle(
                    label = stringResource(R.string.activity_speech_to_subtitle_text_04),
                    checked = state.disableVadForTxt,
                    onCheckedChange = onDisableVadForTxtChange,
                    enabled = state.isTxtSelected,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = stringResource(R.string.activity_speech_to_subtitle_text_05),
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (state.isTxtSelected) 1f else 0.6f
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            HorizontalDivider()

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.activity_auto_timestamp_text_06),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = state.outputDirectory
                        ?: stringResource(R.string.activity_auto_timestamp_text_07),
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

            HorizontalDivider()

            if (!state.asrModelReady) {
                Text(
                    text = stringResource(R.string.speech_to_subtitle_asr_model_required),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = state.startEnabled
            ) {
                Text(stringResource(R.string.start_convert))
            }

            if (state.showProcessingPanel) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(
                        text = stringResource(R.string.activity_speech_to_subtitle_text_06),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.progressVisible) {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0, 100) / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    if (state.progressStatus.isNotBlank()) {
                        Text(
                            text = state.progressStatus,
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        text = stringResource(R.string.activity_speech_to_subtitle_text_08),
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .height(200.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        SelectionContainer {
                            Text(
                                text = state.logText,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(logScrollState)
                                    .padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                            )
                        }
                    }
                    if (state.progressVisible) {
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        SpeechToSubtitleDialogUi.OutputConflict -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("文件名冲突") },
            text = { Text("输出目录中已存在同名字幕文件。请选择处理方式。") },
            confirmButton = { TextButton(onClick = onOverwriteOutput) { Text("覆盖") } },
            dismissButton = {
                Row {
                    TextButton(onClick = onRenameOutput) { Text("自动重命名") }
                    TextButton(onClick = onDismissDialog) { Text("取消") }
                }
            }
        )
        SpeechToSubtitleDialogUi.CancelConfirmation -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("确认取消") },
            text = { Text("语音识别正在进行，确定要取消吗？") },
            confirmButton = { TextButton(onClick = onConfirmCancel) { Text("取消识别") } },
            dismissButton = { TextButton(onClick = onDismissDialog) { Text("继续识别") } }
        )
        SpeechToSubtitleDialogUi.BackConfirmation -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("正在识别中") },
            text = { Text("语音识别正在进行，确定要返回吗？返回后识别将被取消。") },
            confirmButton = { TextButton(onClick = onConfirmBack) { Text("返回并取消") } },
            dismissButton = { TextButton(onClick = onDismissDialog) { Text("继续识别") } }
        )
        is SpeechToSubtitleDialogUi.Error -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("错误") },
            text = { Text(dialog.message) },
            confirmButton = { TextButton(onClick = onDismissDialog) { Text("确定") } }
        )
        null -> Unit
    }
}

@Composable
private fun SpeechSelector(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$label：${options.getOrNull(selectedIndex).orEmpty()}",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer(rotationZ = 90f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
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
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) },
            color = if (enabled) MaterialTheme.colorScheme.onSurface else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
