package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppCard
import com.subtitleedit.ui.theme.AppMotion

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
                modifier = Modifier.height(56.dp),
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MediaConvertCard {
                MediaConvertTitle(stringResource(R.string.activity_media_convert_text_01))
                Button(
                    onClick = onPickFiles,
                    enabled = !isConverting,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Text(stringResource(R.string.activity_media_convert_text_02), maxLines = 2)
                }
                if (sourceSummary.isBlank()) {
                    Text(
                        text = stringResource(R.string.activity_media_convert_text_03),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = sourceSummary,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = sourceInfo,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 40,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            MediaConvertCard {
                MediaConvertTitle(stringResource(R.string.activity_auto_timestamp_text_06))
                Button(
                    onClick = onSelectOutputDirectory,
                    enabled = !isConverting,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Text(stringResource(R.string.activity_batch_convert_text_02))
                }
                Text(
                    text = outputDirectory,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (sourceSummary.isNotBlank()) {
                MediaConvertCard {
                    MediaConvertTitle(stringResource(R.string.activity_auto_timestamp_text_05))
                    Text(
                        text = stringResource(R.string.activity_media_convert_text_05),
                        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FormatGrid(videoFormats, selectedFormat, !isConverting, onFormatSelected)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = stringResource(R.string.activity_media_convert_text_06),
                        modifier = Modifier.padding(bottom = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FormatGrid(audioFormats, selectedFormat, !isConverting, onFormatSelected)
                }
            }

            MediaConvertCard {
                TextButton(
                    onClick = onToggleAdvanced,
                    enabled = !isConverting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (advancedExpanded) "▼ 高级选项"
                        else stringResource(R.string.activity_media_convert_text_07),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Start
                    )
                }
                if (advancedExpanded) {
                    if (videoCodecs.isNotEmpty()) {
                        OptionSelector(
                            stringResource(R.string.activity_media_convert_text_08),
                            selectedVideoCodec, videoCodecs, !isConverting, onVideoCodecSelected
                        )
                        OptionSelector(
                            stringResource(R.string.activity_media_convert_text_09),
                            resolutions.getOrElse(resolutionIndex) { resolutions.first() },
                            resolutions, !isConverting
                        ) { onResolutionSelected(resolutions.indexOf(it).coerceAtLeast(0)) }
                        MediaConvertNumberField(
                            label = stringResource(R.string.activity_media_convert_text_10),
                            value = videoBitrate,
                            hint = stringResource(R.string.activity_media_convert_hint_01),
                            enabled = !isConverting,
                            onValueChange = onVideoBitrateChange
                        )
                        OptionSelector(
                            stringResource(R.string.activity_media_convert_text_11),
                            qualityLabels.getOrElse(qualityIndex) { qualityLabels.first() },
                            qualityLabels, !isConverting
                        ) { onQualitySelected(qualityLabels.indexOf(it).coerceAtLeast(0)) }
                        if (qualityLabels.getOrNull(qualityIndex) == "自定义") {
                            OutlinedTextField(
                                value = customQuality,
                                onValueChange = onCustomQualityChange,
                                placeholder = { Text(stringResource(R.string.activity_media_convert_hint_02)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                enabled = !isConverting,
                                modifier = Modifier.fillMaxWidth().padding(start = 100.dp)
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    }
                    OptionSelector(
                        stringResource(R.string.activity_media_convert_text_12),
                        selectedAudioCodec, audioCodecs, !isConverting, onAudioCodecSelected
                    )
                    MediaConvertNumberField(
                        label = stringResource(R.string.activity_media_convert_text_13),
                        value = audioBitrate,
                        hint = stringResource(R.string.activity_media_convert_hint_01),
                        enabled = !isConverting,
                        onValueChange = onAudioBitrateChange
                    )
                    OptionSelector(
                        stringResource(R.string.activity_media_convert_text_14),
                        sampleRates.getOrElse(sampleRateIndex) { sampleRates.first() },
                        sampleRates, !isConverting
                    ) { onSampleRateSelected(sampleRates.indexOf(it).coerceAtLeast(0)) }
                    OptionSelector(
                        stringResource(R.string.activity_media_convert_text_15),
                        channels.getOrElse(channelIndex) { channels.first() },
                        channels, !isConverting
                    ) { onChannelSelected(channels.indexOf(it).coerceAtLeast(0)) }
                }
            }

            MediaConvertCard {
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(0, 100) / 100f) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onConvert,
                        enabled = canConvert && !isConverting,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.start_convert)) }
                    if (isConverting) {
                        FilledTonalButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    }
                    if (canShare && !isConverting) {
                        FilledTonalButton(onClick = onShare) {
                            Text(stringResource(R.string.activity_media_convert_text_16))
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(180.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .verticalScroll(logScrollState)
                        .padding(8.dp)
                ) {
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    if (dialog == MediaConvertDialog.OUTPUT_CONFLICT) {
        AlertDialog(
            onDismissRequest = onDismissDialog,
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
private fun MediaConvertCard(content: @Composable () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) { content() }
    }
}

@Composable
private fun MediaConvertTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun MediaConvertNumberField(
    label: String,
    value: String,
    hint: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.width(100.dp), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f)
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
    val density = LocalDensity.current
    val buttonMargin = with(density) { 3.toDp() }
    val buttonVerticalPadding = with(density) { 14.toDp() }
    val buttonCornerRadius = MaterialTheme.shapes.medium
    Column {
        formats.chunked(5).forEach { rowFormats ->
            Row {
                rowFormats.forEach { format ->
                    val selected = selectedFormat == format.extension
                    val containerColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                        animationSpec = AppMotion.fast(),
                        label = "format-container"
                    )
                    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(buttonMargin)
                            .clip(buttonCornerRadius)
                            .background(containerColor)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, buttonCornerRadius)
                            .clickable(enabled = enabled) { onSelected(format.extension) }
                            .padding(vertical = buttonVerticalPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = format.displayName,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor
                        )
                    }
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.width(100.dp), style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
