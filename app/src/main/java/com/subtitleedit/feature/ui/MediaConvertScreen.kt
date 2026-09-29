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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

internal data class MediaFormatOption(
    val extension: String,
    val displayName: String,
    val isAudioOnly: Boolean
)

internal enum class MediaConvertDialog { NONE, OUTPUT_CONFLICT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaConvertScreen(
    sourceSummary: String,
    sourceInfo: String,
    outputDirectory: String,
    videoFormats: List<MediaFormatOption>,
    audioFormats: List<MediaFormatOption>,
    selectedFormat: String?,
    videoCodecs: List<String>,
    selectedVideoCodec: String,
    audioCodecs: List<String>,
    selectedAudioCodec: String,
    resolutions: List<String>,
    resolutionIndex: Int,
    videoBitrate: String,
    qualityLabels: List<String>,
    qualityIndex: Int,
    customQuality: String,
    audioBitrate: String,
    sampleRates: List<String>,
    sampleRateIndex: Int,
    channels: List<String>,
    channelIndex: Int,
    advancedExpanded: Boolean,
    isConverting: Boolean,
    canConvert: Boolean,
    canShare: Boolean,
    progress: Int,
    log: String,
    dialog: MediaConvertDialog,
    onBack: () -> Unit,
    onPickFiles: () -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onFormatSelected: (String) -> Unit,
    onVideoCodecSelected: (String) -> Unit,
    onAudioCodecSelected: (String) -> Unit,
    onResolutionSelected: (Int) -> Unit,
    onVideoBitrateChange: (String) -> Unit,
    onQualitySelected: (Int) -> Unit,
    onCustomQualityChange: (String) -> Unit,
    onAudioBitrateChange: (String) -> Unit,
    onSampleRateSelected: (Int) -> Unit,
    onChannelSelected: (Int) -> Unit,
    onToggleAdvanced: () -> Unit,
    onConvert: () -> Unit,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onOverwrite: () -> Unit,
    onAutoRename: () -> Unit,
    onDismissDialog: () -> Unit
) {
    val logScrollState = rememberScrollState()
    LaunchedEffect(log) { logScrollState.scrollTo(logScrollState.maxValue) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("格式转换") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("选择媒体", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onPickFiles, enabled = !isConverting, modifier = Modifier.fillMaxWidth()) {
                Text("选择音频或视频文件")
            }
            Text(
                sourceSummary.ifBlank { "未选择文件" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (sourceInfo.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(sourceInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider()

            Text("输出目录", style = MaterialTheme.typography.titleMedium)
            Text(outputDirectory, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onSelectOutputDirectory, enabled = !isConverting) {
                Text("选择输出目录")
            }

            if (sourceSummary.isNotBlank() && videoFormats.isNotEmpty()) {
                HorizontalDivider()
                Text("输出格式", style = MaterialTheme.typography.titleMedium)
                Text("视频", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FormatGrid(videoFormats, selectedFormat, !isConverting, onFormatSelected)
                HorizontalDivider()
                Text("音频", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FormatGrid(audioFormats, selectedFormat, !isConverting, onFormatSelected)
            }

            TextButton(onClick = onToggleAdvanced, enabled = !isConverting) {
                Text(if (advancedExpanded) "隐藏高级选项" else "高级选项")
            }
            if (advancedExpanded && selectedFormat != null) {
                if (videoCodecs.isNotEmpty()) {
                    OptionSelector("视频编码器", selectedVideoCodec, videoCodecs, !isConverting, onVideoCodecSelected)
                    OptionSelector("分辨率", resolutions.getOrElse(resolutionIndex) { resolutions.first() }, resolutions, !isConverting) {
                        onResolutionSelected(resolutions.indexOf(it).coerceAtLeast(0))
                    }
                    OutlinedTextField(
                        value = videoBitrate,
                        onValueChange = onVideoBitrateChange,
                        label = { Text("视频码率 (kb/s)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !isConverting,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OptionSelector("质量", qualityLabels.getOrElse(qualityIndex) { qualityLabels.first() }, qualityLabels, !isConverting) {
                        onQualitySelected(qualityLabels.indexOf(it).coerceAtLeast(0))
                    }
                    if (qualityLabels.getOrNull(qualityIndex) == "自定义") {
                        OutlinedTextField(
                            value = customQuality,
                            onValueChange = onCustomQualityChange,
                            label = { Text("CRF (1-31)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            enabled = !isConverting,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                OptionSelector("音频编码器", selectedAudioCodec, audioCodecs, !isConverting, onAudioCodecSelected)
                OutlinedTextField(
                    value = audioBitrate,
                    onValueChange = onAudioBitrateChange,
                    label = { Text("音频码率 (kb/s)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !isConverting,
                    modifier = Modifier.fillMaxWidth()
                )
                OptionSelector("采样率", sampleRates.getOrElse(sampleRateIndex) { sampleRates.first() }, sampleRates, !isConverting) {
                    onSampleRateSelected(sampleRates.indexOf(it).coerceAtLeast(0))
                }
                OptionSelector("声道", channels.getOrElse(channelIndex) { channels.first() }, channels, !isConverting) {
                    onChannelSelected(channels.indexOf(it).coerceAtLeast(0))
                }
            }

            if (isConverting || progress > 0) {
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(0, 100) / 100f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConvert, enabled = canConvert && !isConverting, modifier = Modifier.weight(1f)) {
                    Text("开始转换")
                }
                if (isConverting) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
                if (canShare && !isConverting) {
                    TextButton(onClick = onShare) { Text("分享结果") }
                }
            }

            if (log.isNotBlank()) {
                HorizontalDivider()
                Text("转换日志", style = MaterialTheme.typography.titleMedium)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .verticalScroll(logScrollState)
                        .padding(8.dp)
                ) {
                    Text(
                        log,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (dialog == MediaConvertDialog.OUTPUT_CONFLICT) {
        AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("文件名冲突") },
            text = { Text("输出目录中已有同名文件，或所选文件会生成同名输出。请选择处理方式。") },
            confirmButton = {
                Row {
                    TextButton(onClick = onOverwrite) { Text("覆盖") }
                    TextButton(onClick = onAutoRename) { Text("自动重命名") }
                    TextButton(onClick = onDismissDialog) { Text("取消") }
                }
            }
        )
    }
}

@Composable
private fun FormatGrid(
    formats: List<MediaFormatOption>,
    selectedFormat: String?,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        formats.chunked(5).forEach { rowFormats ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowFormats.forEach { format ->
                    FilterChip(
                        selected = selectedFormat == format.extension,
                        onClick = { onSelected(format.extension) },
                        enabled = enabled,
                        label = { Text(format.displayName, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(5 - rowFormats.size) { Spacer(Modifier.weight(1f).width(1.dp)) }
            }
        }
    }
}

@Composable
private fun OptionSelector(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
                Text(value)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            onSelected(option)
                        }
                    )
                }
            }
        }
    }
}
