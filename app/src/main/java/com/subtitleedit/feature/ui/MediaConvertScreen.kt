package com.subtitleedit.feature.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppChoiceTile
import com.subtitleedit.ui.components.AppConflictDialog
import com.subtitleedit.ui.components.AppLogBox
import com.subtitleedit.ui.components.AppOptionSelector
import com.subtitleedit.ui.components.AppPrimaryButton
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.theme.AppMotion
import com.subtitleedit.ui.theme.AppSpacing

internal data class MediaFormatOption(
    val extension: String,
    val displayName: String,
    val isAudioOnly: Boolean
)

internal enum class MediaConvertDialog { NONE, OUTPUT_CONFLICT }

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
    AppToolScaffold(
        title = "格式转换",
        onBack = onBack,
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
            ) {
                AppTaskProgress(
                    visible = isConverting,
                    progress = progress.coerceIn(0, 100) / 100f,
                    onCancel = onCancel
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                    AppPrimaryButton(
                        text = stringResource(R.string.start_convert),
                        onClick = onConvert,
                        enabled = canConvert && !isConverting,
                        modifier = Modifier.weight(1f)
                    )
                    if (canShare && !isConverting) {
                        FilledTonalButton(onClick = onShare, modifier = Modifier.height(52.dp)) {
                            Text(stringResource(R.string.activity_media_convert_text_16))
                        }
                    }
                }
            }
        }
    ) {
        AppSection(title = stringResource(R.string.activity_media_convert_text_01)) {
            OutlinedButton(onClick = onPickFiles, enabled = !isConverting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.activity_media_convert_text_02), maxLines = 2)
            }
            if (sourceSummary.isBlank()) {
                Text(
                    stringResource(R.string.activity_media_convert_text_03),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(sourceSummary, style = MaterialTheme.typography.titleSmall, maxLines = 12, overflow = TextOverflow.Ellipsis)
                Text(
                    sourceInfo,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 40,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        AppSection(title = stringResource(R.string.activity_auto_timestamp_text_06)) {
            OutlinedButton(onClick = onSelectOutputDirectory, enabled = !isConverting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.activity_batch_convert_text_02))
            }
            Text(outputDirectory, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (sourceSummary.isNotBlank()) {
            AppSection(title = stringResource(R.string.activity_auto_timestamp_text_05)) {
                Text(stringResource(R.string.activity_media_convert_text_05), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FormatGrid(videoFormats, selectedFormat, !isConverting, onFormatSelected)
                HorizontalDivider()
                Text(stringResource(R.string.activity_media_convert_text_06), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FormatGrid(audioFormats, selectedFormat, !isConverting, onFormatSelected)
            }
        }

        AppSection {
            val rotation by animateFloatAsState(
                targetValue = if (advancedExpanded) 90f else 0f,
                animationSpec = AppMotion.fast(),
                label = "advanced-chevron"
            )
            TextButton(onClick = onToggleAdvanced, enabled = !isConverting, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (advancedExpanded) "高级选项" else stringResource(R.string.activity_media_convert_text_07),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Start
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_right),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).graphicsLayer(rotationZ = rotation)
                    )
                }
            }
            AnimatedVisibility(
                visible = advancedExpanded,
                enter = expandVertically(animationSpec = AppMotion.enter()) + fadeIn(animationSpec = AppMotion.enter()),
                exit = shrinkVertically(animationSpec = AppMotion.exit()) + fadeOut(animationSpec = AppMotion.exit())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                    if (videoCodecs.isNotEmpty()) {
                        AppOptionSelector(stringResource(R.string.activity_media_convert_text_08), selectedVideoCodec, videoCodecs, !isConverting, onVideoCodecSelected)
                        AppOptionSelector(
                            stringResource(R.string.activity_media_convert_text_09),
                            resolutions.getOrElse(resolutionIndex) { resolutions.firstOrNull().orEmpty() },
                            resolutions,
                            !isConverting,
                            onSelected = { onResolutionSelected(resolutions.indexOf(it).coerceAtLeast(0)) }
                        )
                        MediaConvertNumberField(stringResource(R.string.activity_media_convert_text_10), videoBitrate, stringResource(R.string.activity_media_convert_hint_01), !isConverting, onVideoBitrateChange)
                        AppOptionSelector(
                            stringResource(R.string.activity_media_convert_text_11),
                            qualityLabels.getOrElse(qualityIndex) { qualityLabels.firstOrNull().orEmpty() },
                            qualityLabels,
                            !isConverting,
                            onSelected = { onQualitySelected(qualityLabels.indexOf(it).coerceAtLeast(0)) }
                        )
                        if (qualityLabels.getOrNull(qualityIndex) == "自定义") {
                            OutlinedTextField(
                                value = customQuality,
                                onValueChange = onCustomQualityChange,
                                placeholder = { Text(stringResource(R.string.activity_media_convert_hint_02)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                enabled = !isConverting,
                                modifier = Modifier.fillMaxWidth().padding(start = AppSpacing.FormLabelWidth)
                            )
                        }
                        HorizontalDivider()
                    }
                    AppOptionSelector(stringResource(R.string.activity_media_convert_text_12), selectedAudioCodec, audioCodecs, !isConverting, onAudioCodecSelected)
                    MediaConvertNumberField(stringResource(R.string.activity_media_convert_text_13), audioBitrate, stringResource(R.string.activity_media_convert_hint_01), !isConverting, onAudioBitrateChange)
                    AppOptionSelector(
                        stringResource(R.string.activity_media_convert_text_14),
                        sampleRates.getOrElse(sampleRateIndex) { sampleRates.firstOrNull().orEmpty() },
                        sampleRates,
                        !isConverting,
                        onSelected = { onSampleRateSelected(sampleRates.indexOf(it).coerceAtLeast(0)) }
                    )
                    AppOptionSelector(
                        stringResource(R.string.activity_media_convert_text_15),
                        channels.getOrElse(channelIndex) { channels.firstOrNull().orEmpty() },
                        channels,
                        !isConverting,
                        onSelected = { onChannelSelected(channels.indexOf(it).coerceAtLeast(0)) }
                    )
                }
            }
        }

        AppSection(title = stringResource(R.string.activity_settings_text_06)) {
            AppLogBox(log)
        }
    }

    if (dialog == MediaConvertDialog.OUTPUT_CONFLICT) {
        AppConflictDialog(
            message = "输出目录中已有同名文件，或所选文件会生成同名输出。请选择处理方式。",
            onOverwrite = onOverwrite,
            onRename = onAutoRename,
            onCancel = onDismissDialog
        )
    }
}

@Composable
private fun MediaConvertNumberField(
    label: String,
    value: String,
    hint: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(AppSpacing.FormLabelWidth), style = MaterialTheme.typography.bodyMedium)
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
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
        formats.chunked(5).forEach { rowFormats ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                rowFormats.forEach { format ->
                    AppChoiceTile(
                        text = format.displayName,
                        selected = selectedFormat == format.extension,
                        enabled = enabled,
                        onClick = { onSelected(format.extension) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)
                    )
                }
                repeat(5 - rowFormats.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
