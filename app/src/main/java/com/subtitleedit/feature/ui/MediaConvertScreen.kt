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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.MediaConvertResolution
import com.subtitleedit.MediaConvertResolutionSettings
import com.subtitleedit.MediaConvertVideoSize
import com.subtitleedit.MediaConvertBitrateMode
import com.subtitleedit.MediaConvertVideoBitrate
import com.subtitleedit.MediaConvertVideoBitrateSettings
import com.subtitleedit.MediaConvertAudioBitrate
import com.subtitleedit.ui.components.AppChoiceTile
import com.subtitleedit.ui.components.AppConflictDialog
import com.subtitleedit.ui.components.AppLogBox
import com.subtitleedit.ui.components.AppOption
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
    saveToSourceDirectory: Boolean,
    videoFormats: List<MediaFormatOption>,
    audioFormats: List<MediaFormatOption>,
    selectedFormat: String?,
    videoCodecs: List<AppOption<String>>,
    selectedVideoCodec: String,
    audioCodecs: List<AppOption<String>>,
    selectedAudioCodec: String,
    resolution: MediaConvertResolutionSettings,
    videoBitrate: MediaConvertVideoBitrateSettings,
    qualityOptions: List<AppOption<String>>,
    selectedQualityId: String,
    customQuality: String,
    audioBitrate: String,
    sampleRates: List<AppOption<Int>>,
    sampleRateIndex: Int,
    channels: List<AppOption<Int>>,
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
    onSaveToSourceDirectoryChange: (Boolean) -> Unit,
    onFormatSelected: (String) -> Unit,
    onVideoCodecSelected: (String) -> Unit,
    onAudioCodecSelected: (String) -> Unit,
    onResolutionSelected: (MediaConvertResolutionSettings) -> Unit,
    onVideoBitrateChange: (MediaConvertVideoBitrateSettings) -> Unit,
    onQualitySelected: (String) -> Unit,
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
    onDismissDialog: () -> Unit,
    videoFormatsExpanded: Boolean = false,
    audioFormatsExpanded: Boolean = false,
    onToggleVideoFormats: () -> Unit = {},
    onToggleAudioFormats: () -> Unit = {},
    qualityHint: String = "",
    sourceVideoSize: MediaConvertVideoSize? = null,
    multipleSources: Boolean = false,
    sourceVideoBitrateBps: Long? = null,
    canUseOriginalVideoBitrate: Boolean = false,
    audioBitrateEditable: Boolean = true,
    audioCodecHint: String = "",
    sourceAudioBitrateBps: Long? = null,
    canUseOriginalAudioBitrate: Boolean = false,
    effectiveAudioCodec: String = selectedAudioCodec,
    audioMuxer: String = "",
    sourceAudioSampleRate: Int? = null,
    sourceAudioChannelLabel: String = ""
) {
    var showResolutionDialog by rememberSaveable { mutableStateOf(false) }
    var showBitrateDialog by rememberSaveable { mutableStateOf(false) }
    var showAudioBitrateDialog by rememberSaveable { mutableStateOf(false) }
    var showSampleRateDialog by rememberSaveable { mutableStateOf(false) }
    var showChannelDialog by rememberSaveable { mutableStateOf(false) }
    AppToolScaffold(
        title = stringResource(R.string.media_convert_title),
        onBack = onBack,
        imePadding = true,
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
            ) {
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
            OutlinedButton(onClick = onPickFiles, enabled = !isConverting) {
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

        if (sourceSummary.isNotBlank()) {
            AppSection(title = stringResource(R.string.activity_tool_output_format)) {
                FormatExpander(
                    title = stringResource(R.string.activity_media_convert_text_05),
                    formats = videoFormats,
                    selectedFormat = selectedFormat,
                    expanded = videoFormatsExpanded,
                    enabled = !isConverting,
                    onToggle = onToggleVideoFormats,
                    onSelected = onFormatSelected
                )
                HorizontalDivider()
                FormatExpander(
                    title = stringResource(R.string.media_convert_audio_formats),
                    formats = audioFormats,
                    selectedFormat = selectedFormat,
                    expanded = audioFormatsExpanded,
                    enabled = !isConverting,
                    onToggle = onToggleAudioFormats,
                    onSelected = onFormatSelected
                )
            }
        }

        if (selectedFormat != null) AppSection {
            val rotation by animateFloatAsState(
                targetValue = if (advancedExpanded) 90f else 0f,
                animationSpec = AppMotion.fast(),
                label = "advanced-chevron"
            )
            TextButton(onClick = onToggleAdvanced, enabled = !isConverting, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (advancedExpanded) stringResource(R.string.media_convert_advanced_options)
                        else stringResource(R.string.activity_media_convert_text_07),
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
                        AppOptionSelector(stringResource(R.string.activity_media_convert_text_08), videoCodecs.firstOrNull { it.id == selectedVideoCodec }, videoCodecs, !isConverting, onVideoCodecSelected)
                        val videoEnabled = !isConverting && selectedVideoCodec != "copy"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                        ) {
                            Text(stringResource(R.string.activity_media_convert_text_09), modifier = Modifier.width(AppSpacing.FormLabelWidth), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { showResolutionDialog = true }, enabled = videoEnabled, modifier = Modifier.weight(1f)) {
                                Text(
                                    if (resolution.presetIndex == 0) stringResource(R.string.media_convert_resolution_original)
                                    else resolution.size?.label.orEmpty() + if (resolution.presetIndex == MediaConvertResolution.CUSTOM) stringResource(R.string.media_convert_resolution_custom_suffix) else "",
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                        ) {
                            Text(stringResource(R.string.activity_media_convert_text_10), modifier = Modifier.width(AppSpacing.FormLabelWidth), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { showBitrateDialog = true }, enabled = videoEnabled, modifier = Modifier.weight(1f)) {
                                val label = when (videoBitrate.mode) {
                                    MediaConvertBitrateMode.AUTO -> stringResource(R.string.media_convert_bitrate_auto)
                                    MediaConvertBitrateMode.ORIGINAL -> stringResource(R.string.media_convert_bitrate_original) +
                                        (sourceVideoBitrateBps?.let { " (${MediaConvertVideoBitrate.label(it)})" } ?: "")
                                    MediaConvertBitrateMode.CUSTOM -> "${videoBitrate.customKbps} kbps"
                                    MediaConvertBitrateMode.PRESET -> "${videoBitrate.presetKbps} kbps"
                                }
                                Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                        val qualityEnabled = videoEnabled && videoBitrate.mode == MediaConvertBitrateMode.AUTO
                        if (videoBitrate.mode != MediaConvertBitrateMode.AUTO) Text(
                            stringResource(R.string.media_convert_bitrate_quality_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppOptionSelector(
                            stringResource(R.string.activity_media_convert_text_11),
                            qualityOptions.firstOrNull { it.id == selectedQualityId },
                            qualityOptions,
                            qualityEnabled,
                            onSelected = onQualitySelected
                        )
                        if (selectedQualityId == "custom") {
                            OutlinedTextField(
                                value = customQuality,
                                onValueChange = onCustomQualityChange,
                                placeholder = { Text(qualityHint) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                enabled = qualityEnabled,
                                modifier = Modifier.fillMaxWidth().padding(start = AppSpacing.FormLabelWidth)
                            )
                        }
                        HorizontalDivider()
                    }
                    AppOptionSelector(stringResource(R.string.activity_media_convert_text_12), audioCodecs.firstOrNull { it.id == selectedAudioCodec }, audioCodecs, !isConverting, onAudioCodecSelected)
                    if (audioCodecHint.isNotBlank()) Text(audioCodecHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val audioEnabled = !isConverting && selectedAudioCodec != "copy"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                    ) {
                        Text(stringResource(R.string.activity_media_convert_text_13), modifier = Modifier.width(AppSpacing.FormLabelWidth), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { showAudioBitrateDialog = true }, enabled = audioEnabled && audioBitrateEditable, modifier = Modifier.weight(1f)) {
                            val label = when {
                                !audioBitrateEditable -> stringResource(R.string.media_convert_audio_bitrate_derived)
                                audioBitrate == MediaConvertAudioBitrate.ORIGINAL -> stringResource(R.string.media_convert_bitrate_original) +
                                    (sourceAudioBitrateBps?.let { " (${MediaConvertAudioBitrate.label(it)})" } ?: "")
                                audioBitrate.isBlank() -> stringResource(R.string.media_convert_bitrate_auto)
                                else -> "$audioBitrate kbps"
                            }
                            Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                    ) {
                        Text(stringResource(R.string.activity_media_convert_text_14), modifier = Modifier.width(AppSpacing.FormLabelWidth), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { showSampleRateDialog = true }, enabled = audioEnabled, modifier = Modifier.weight(1f)) {
                            val label = if (sampleRateIndex == 0) {
                                stringResource(R.string.media_convert_sample_rate_original) + (sourceAudioSampleRate?.let { " ($it Hz)" } ?: "")
                            } else sampleRates.firstOrNull { it.id == sampleRateIndex }?.label ?: stringResource(R.string.media_convert_audio_auto_adapt)
                            Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner)
                    ) {
                        Text(stringResource(R.string.activity_media_convert_text_15), modifier = Modifier.width(AppSpacing.FormLabelWidth), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { showChannelDialog = true }, enabled = audioEnabled, modifier = Modifier.weight(1f)) {
                            val label = if (channelIndex == 0) {
                                stringResource(R.string.media_convert_channel_original) + sourceAudioChannelLabel.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                            } else channels.firstOrNull { it.id == channelIndex }?.label ?: stringResource(R.string.media_convert_audio_auto_adapt)
                            Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        ToolOutputDirectorySection(
            outputDirectory = outputDirectory,
            saveToSourceDirectory = saveToSourceDirectory,
            enabled = !isConverting && dialog != MediaConvertDialog.OUTPUT_CONFLICT,
            onSelectOutputDirectory = onSelectOutputDirectory,
            onSaveToSourceDirectoryChange = onSaveToSourceDirectoryChange
        )

        AppTaskProgress(
            visible = isConverting,
            progress = progress.coerceIn(0, 100) / 100f,
            onCancel = onCancel
        )
        AppSection(title = stringResource(R.string.activity_settings_text_06)) {
            AppLogBox(log)
        }
    }

    if (showChannelDialog && !isConverting && selectedAudioCodec != "copy") {
        MediaConvertChannelDialog(
            initialIndex = channelIndex,
            options = channels,
            sourceLabel = sourceAudioChannelLabel,
            multipleSources = multipleSources,
            onDismiss = { showChannelDialog = false },
            onConfirm = { onChannelSelected(it); showChannelDialog = false }
        )
    }

    if (showSampleRateDialog && !isConverting && selectedAudioCodec != "copy") {
        MediaConvertSampleRateDialog(
            initialIndex = sampleRateIndex,
            options = sampleRates,
            sourceSampleRate = sourceAudioSampleRate,
            multipleSources = multipleSources,
            onDismiss = { showSampleRateDialog = false },
            onConfirm = { onSampleRateSelected(it); showSampleRateDialog = false }
        )
    }

    if (showAudioBitrateDialog && !isConverting && selectedAudioCodec != "copy" && audioBitrateEditable) {
        MediaConvertAudioBitrateDialog(
            initial = audioBitrate,
            codec = effectiveAudioCodec,
            muxer = audioMuxer,
            sourceBitsPerSecond = sourceAudioBitrateBps,
            canUseOriginal = canUseOriginalAudioBitrate,
            multipleSources = multipleSources,
            onDismiss = { showAudioBitrateDialog = false },
            onConfirm = { onAudioBitrateChange(it); showAudioBitrateDialog = false }
        )
    }

    if (showBitrateDialog && !isConverting && videoCodecs.isNotEmpty() && selectedVideoCodec != "copy") {
        MediaConvertBitrateDialog(
            initial = videoBitrate,
            sourceBitsPerSecond = sourceVideoBitrateBps,
            canUseOriginal = canUseOriginalVideoBitrate,
            multipleSources = multipleSources,
            onDismiss = { showBitrateDialog = false },
            onConfirm = { onVideoBitrateChange(it); showBitrateDialog = false }
        )
    }

    if (showResolutionDialog && !isConverting && videoCodecs.isNotEmpty() && selectedVideoCodec != "copy") {
        MediaConvertResolutionDialog(
            initial = resolution,
            sourceSize = sourceVideoSize,
            multipleSources = multipleSources,
            onDismiss = { showResolutionDialog = false },
            onConfirm = { onResolutionSelected(it); showResolutionDialog = false }
        )
    }

    if (dialog == MediaConvertDialog.OUTPUT_CONFLICT) {
        AppConflictDialog(
            message = stringResource(R.string.media_convert_output_conflict),
            onOverwrite = onOverwrite,
            onRename = onAutoRename,
            onCancel = onDismissDialog
        )
    }
}

@Composable
private fun FormatExpander(
    title: String,
    formats: List<MediaFormatOption>,
    selectedFormat: String?,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onSelected: (String) -> Unit
) {
    val selection = formats.firstOrNull { it.extension == selectedFormat }?.displayName
    val description = stringResource(if (expanded) R.string.media_convert_expanded else R.string.media_convert_collapsed)
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, AppMotion.fast(), label = "format-chevron")
    TextButton(
        onClick = onToggle,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = description }
    ) {
        Text(title, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        if (selection != null) Text(selection, modifier = Modifier.padding(end = AppSpacing.Inner))
        Icon(
            painter = painterResource(R.drawable.ic_arrow_right),
            contentDescription = null,
            modifier = Modifier.size(18.dp).graphicsLayer(rotationZ = rotation)
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(AppMotion.enter()) + fadeIn(AppMotion.enter()),
        exit = shrinkVertically(AppMotion.exit()) + fadeOut(AppMotion.exit())
    ) {
        FormatGrid(formats, selectedFormat, enabled, onSelected)
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
