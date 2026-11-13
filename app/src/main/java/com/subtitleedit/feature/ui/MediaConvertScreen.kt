package com.subtitleedit.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subtitleedit.MediaConvertAudioBitrate
import com.subtitleedit.MediaConvertAudioVbr
import com.subtitleedit.MediaConvertBitrateMode
import com.subtitleedit.MediaConvertEmbeddedLyric
import com.subtitleedit.MediaConvertResolution
import com.subtitleedit.MediaConvertResolutionSettings
import com.subtitleedit.MediaConvertSourceSubtitle
import com.subtitleedit.MediaConvertSubtitleType
import com.subtitleedit.MediaConvertVideoBitrate
import com.subtitleedit.MediaConvertVideoBitrateSettings
import com.subtitleedit.MediaConvertVideoSettings
import com.subtitleedit.MediaConvertVideoSettingsSupport
import com.subtitleedit.MediaConvertVideoSize
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppConflictDialog
import com.subtitleedit.ui.components.AppLogBox
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.components.AppTaskProgress
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.settings.SettingsTileGroup
import com.subtitleedit.ui.settings.SettingsTileRow
import com.subtitleedit.ui.settings.SettingsTileSection
import com.subtitleedit.ui.settings.SettingsTileSwitchRow
import com.subtitleedit.ui.settings.settingsOutlinedTextFieldColors
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.settings.settingsPrimaryTextColor
import com.subtitleedit.ui.settings.settingsSecondaryTextColor
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
    onConvert: () -> Unit,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onOverwrite: () -> Unit,
    onAutoRename: () -> Unit,
    onDismissDialog: () -> Unit,
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
    sourceAudioChannelLabel: String = "",
    audioVolumePercent: Int = 100,
    onAudioVolumeChange: (Int) -> Unit = {},
    videoSettings: MediaConvertVideoSettings = MediaConvertVideoSettings(),
    effectiveVideoCodec: String = selectedVideoCodec,
    detectedPixelFormats: List<String>? = null,
    sourcePixelFormat: String? = null,
    onVideoSettingsChange: (MediaConvertVideoSettings) -> Unit = {},
    sourceFileSizeBytes: Long? = null,
    audioVbrQuality: Int = MediaConvertAudioVbr.OFF,
    onAudioVbrQualityChange: (Int) -> Unit = {},
    selectedSourceCount: Int = 0,
    subtitleFileNames: List<String> = emptyList(),
    maxSubtitleFiles: Int = Int.MAX_VALUE,
    subtitleType: MediaConvertSubtitleType = MediaConvertSubtitleType.SOFT,
    allowedSubtitleExtensions: Set<String> = emptySet(),
    subtitleBurnInAvailable: Boolean = false,
    onPickSubtitle: () -> Unit = {},
    onRemoveSubtitle: (Int) -> Unit = {},
    onSubtitleTypeChange: (MediaConvertSubtitleType) -> Unit = {},
    sourceSubtitleTracks: List<MediaConvertSourceSubtitle> = emptyList(),
    sourceSubtitleSupport: List<Boolean> = emptyList(),
    sourceSubtitleProbeFailed: Boolean = false,
    sourceSubtitleProbeInProgress: Boolean = false,
    sourceEmbeddedLyrics: List<MediaConvertEmbeddedLyric> = emptyList(),
    sourceLyricsProbeFailed: Boolean = false,
    sourceIsAudio: Boolean = false,
    onViewEmbeddedLyric: (Int) -> Unit = {},
    showTopBar: Boolean = true
) {
    var showResolutionDialog by rememberSaveable { mutableStateOf(false) }
    var showBitrateDialog by rememberSaveable { mutableStateOf(false) }
    var showAudioBitrateDialog by rememberSaveable { mutableStateOf(false) }
    var showSampleRateDialog by rememberSaveable { mutableStateOf(false) }
    var showChannelDialog by rememberSaveable { mutableStateOf(false) }
    var showVolumeDialog by rememberSaveable { mutableStateOf(false) }
    var showAudioVbrDialog by rememberSaveable { mutableStateOf(false) }
    val isAudioOutput = audioFormats.any { it.extension == selectedFormat }
    val vbrSupported = isAudioOutput && MediaConvertAudioVbr.supports(effectiveAudioCodec)
    val vbrActive = vbrSupported && audioVbrQuality in MediaConvertAudioVbr.qualities
    val pageBackground = settingsPageBackground()

    AppToolScaffold(
        title = stringResource(R.string.media_convert_title),
        onBack = onBack,
        showTopBar = showTopBar,
        imePadding = true,
        contentBackground = pageBackground,
        topBarContainerColor = pageBackground,
        topBarScrolledContainerColor = pageBackground,
        bottomBar = {
            Box(Modifier.background(pageBackground)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.Page, vertical = AppSpacing.Inner),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onConvert,
                        enabled = canConvert && !isConverting,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = CircleShape
                    ) {
                        Text(
                            stringResource(R.string.start_convert),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    if (canShare && !isConverting) {
                        FilledTonalButton(
                            onClick = onShare,
                            modifier = Modifier.height(56.dp),
                            shape = CircleShape
                        ) {
                            Text(stringResource(R.string.activity_media_convert_text_16))
                        }
                    }
                }
            }
        }
    ) {
        // ── Source file selection ──
        SettingsTileGroup {
            SettingsTileRow(
                title = stringResource(R.string.activity_media_convert_text_02),
                iconRes = R.drawable.ic_file_video,
                value = if (sourceSummary.isBlank()) {
                    stringResource(R.string.activity_media_convert_text_03)
                } else if (selectedSourceCount > 0) {
                    stringResource(R.string.selected_file_count, selectedSourceCount)
                } else {
                    sourceSummary
                },
                enabled = !isConverting,
                onClick = onPickFiles
            )
        }

        if (sourceSummary.isNotBlank() && sourceInfo.isNotBlank()) {
            SettingsTileSection(title = stringResource(R.string.activity_media_convert_text_01)) {
                Text(
                    sourceInfo,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = settingsSecondaryTextColor(),
                    maxLines = 40,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // ── Output format ──
        if (sourceSummary.isNotBlank()) {
            SettingsTileGroup {
                MediaConvertFormatPicker(
                    title = stringResource(R.string.activity_media_convert_text_05),
                    formats = videoFormats,
                    selectedFormat = selectedFormat,
                    enabled = !isConverting,
                    onSelected = onFormatSelected
                )
                MediaConvertFormatPicker(
                    title = stringResource(R.string.media_convert_audio_formats),
                    formats = audioFormats,
                    selectedFormat = selectedFormat,
                    enabled = !isConverting,
                    onSelected = onFormatSelected
                )
            }
        }

        // ── Settings pager (More, Advanced, Subtitles) ──
        if (sourceSummary.isNotBlank()) {
            MediaConvertSettingsPager(
                enabled = !isConverting,
                isAudioOutput = isAudioOutput,
                showSubtitlePage = selectedSourceCount > 0,
                subtitleContent = {
                    MediaConvertSubtitlePage(
                        singleSource = selectedSourceCount == 1,
                        enabled = !isConverting,
                        fileNames = subtitleFileNames,
                        type = subtitleType,
                        allowedExtensions = allowedSubtitleExtensions,
                        copyingVideo = effectiveVideoCodec == "copy",
                        burnInAvailable = subtitleBurnInAvailable,
                        onPick = onPickSubtitle,
                        onRemove = onRemoveSubtitle,
                        onTypeChanged = onSubtitleTypeChange,
                        isAudioOutput = isAudioOutput,
                        maxSubtitleFiles = maxSubtitleFiles,
                        audioLyricsPlainText = isAudioOutput && audioMuxer == "ipod",
                        sourceSubtitleTracks = sourceSubtitleTracks,
                        sourceSubtitleSupport = sourceSubtitleSupport,
                        sourceSubtitleProbeFailed = sourceSubtitleProbeFailed,
                        sourceSubtitleProbeInProgress = sourceSubtitleProbeInProgress,
                        sourceEmbeddedLyrics = sourceEmbeddedLyrics,
                        sourceLyricsProbeFailed = sourceLyricsProbeFailed,
                        sourceIsAudio = sourceIsAudio,
                        onViewEmbeddedLyric = onViewEmbeddedLyric,
                        outputFormatSelected = selectedFormat != null
                    )
                },
                moreContent = {
                    when {
                        selectedFormat == null -> Text(
                            stringResource(R.string.media_convert_select_format_required),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        videoCodecs.isNotEmpty() -> MediaConvertVideoSettingsContent(
                            settings = videoSettings,
                            codec = effectiveVideoCodec,
                            bitrateMode = videoBitrate.mode,
                            detectedPixelFormats = detectedPixelFormats,
                            sourcePixelFormat = sourcePixelFormat,
                            multipleSources = multipleSources,
                            isConverting = isConverting,
                            onChange = onVideoSettingsChange,
                            sourceFileSizeBytes = sourceFileSizeBytes
                        )
                        else -> Text(
                            stringResource(R.string.media_convert_audio_more_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                advancedContent = {
                    if (selectedFormat != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                            if (videoCodecs.isNotEmpty()) {
                                SettingsTileGroup {
                                    MediaConvertOptionRow(
                                        label = stringResource(R.string.activity_media_convert_text_08),
                                        iconRes = R.drawable.ic_file_video,
                                        value = videoCodecs.firstOrNull { it.id == selectedVideoCodec }?.label.orEmpty(),
                                        options = videoCodecs,
                                        enabled = !isConverting,
                                        onSelected = onVideoCodecSelected
                                    )
                                    val videoEnabled = !isConverting && selectedVideoCodec != "copy"
                                    SettingsTileRow(
                                        title = stringResource(R.string.activity_media_convert_text_09),
                                        iconRes = R.drawable.ic_video_fullscreen,
                                        valueBelow = true,
                                        value = if (resolution.presetIndex == 0) stringResource(R.string.media_convert_resolution_original)
                                        else resolution.size?.label.orEmpty() + if (resolution.presetIndex == MediaConvertResolution.CUSTOM) stringResource(R.string.media_convert_resolution_custom_suffix) else "",
                                        enabled = videoEnabled,
                                        onClick = { showResolutionDialog = true }
                                    )
                                    SettingsTileRow(
                                        title = stringResource(R.string.activity_media_convert_text_10),
                                        iconRes = R.drawable.ic_settings,
                                        valueBelow = true,
                                        value = when (videoBitrate.mode) {
                                            MediaConvertBitrateMode.AUTO -> stringResource(R.string.media_convert_bitrate_auto)
                                            MediaConvertBitrateMode.ORIGINAL -> stringResource(R.string.media_convert_bitrate_original) +
                                                (sourceVideoBitrateBps?.let { " (${MediaConvertVideoBitrate.label(it)})" } ?: "")
                                            MediaConvertBitrateMode.CUSTOM -> "${videoBitrate.customKbps} kbps"
                                            MediaConvertBitrateMode.PRESET -> "${videoBitrate.presetKbps} kbps"
                                        },
                                        enabled = videoEnabled,
                                        onClick = { showBitrateDialog = true }
                                    )
                                    val qualityEnabled = videoEnabled && videoBitrate.mode == MediaConvertBitrateMode.AUTO &&
                                        videoSettings.sizeLimitPercent == null &&
                                        !(videoSettings.crf != null && effectiveVideoCodec in MediaConvertVideoSettingsSupport.crfCodecs)
                                    MediaConvertOptionRow(
                                        label = stringResource(R.string.activity_media_convert_text_11),
                                        iconRes = R.drawable.ic_settings,
                                        value = qualityOptions.firstOrNull { it.id == selectedQualityId }?.label.orEmpty(),
                                        options = qualityOptions,
                                        enabled = qualityEnabled,
                                        onSelected = onQualitySelected
                                    )
                                }
                                val videoEnabled = !isConverting && selectedVideoCodec != "copy"
                                val qualityEnabled = videoEnabled && videoBitrate.mode == MediaConvertBitrateMode.AUTO &&
                                    videoSettings.sizeLimitPercent == null &&
                                    !(videoSettings.crf != null && effectiveVideoCodec in MediaConvertVideoSettingsSupport.crfCodecs)
                                if (videoBitrate.mode != MediaConvertBitrateMode.AUTO) {
                                    Text(
                                        stringResource(R.string.media_convert_bitrate_quality_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                if (selectedQualityId == "custom") {
                                    OutlinedTextField(
                                        value = customQuality,
                                        onValueChange = onCustomQualityChange,
                                        placeholder = { Text(qualityHint) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        enabled = qualityEnabled,
                                        colors = settingsOutlinedTextFieldColors(),
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                    )
                                }
                                MediaConvertVideoSettingsContent(
                                    settings = videoSettings,
                                    codec = effectiveVideoCodec,
                                    bitrateMode = videoBitrate.mode,
                                    detectedPixelFormats = detectedPixelFormats,
                                    sourcePixelFormat = sourcePixelFormat,
                                    multipleSources = multipleSources,
                                    isConverting = isConverting,
                                    onChange = onVideoSettingsChange,
                                    group = MediaConvertVideoSettingsGroup.ADVANCED
                                )
                            }
                            SettingsTileGroup {
                                MediaConvertOptionRow(
                                    label = stringResource(R.string.activity_media_convert_text_12),
                                    iconRes = R.drawable.ic_file_audio,
                                    value = audioCodecs.firstOrNull { it.id == selectedAudioCodec }?.label.orEmpty(),
                                    options = audioCodecs,
                                    enabled = !isConverting,
                                    onSelected = onAudioCodecSelected
                                )
                                val audioEnabled = !isConverting && selectedAudioCodec != "copy"
                                SettingsTileRow(
                                    title = stringResource(R.string.activity_media_convert_text_13),
                                    iconRes = R.drawable.ic_file_audio,
                                    valueBelow = true,
                                    value = when {
                                        vbrActive -> stringResource(R.string.media_convert_audio_vbr_active)
                                        !audioBitrateEditable -> stringResource(R.string.media_convert_audio_bitrate_derived)
                                        audioBitrate == MediaConvertAudioBitrate.ORIGINAL -> stringResource(R.string.media_convert_bitrate_original) +
                                            (sourceAudioBitrateBps?.let { " (${MediaConvertAudioBitrate.label(it)})" } ?: "")
                                        audioBitrate.isBlank() -> stringResource(R.string.media_convert_bitrate_auto)
                                        else -> "$audioBitrate kbps"
                                    },
                                    enabled = audioEnabled && audioBitrateEditable && !vbrActive,
                                    onClick = { showAudioBitrateDialog = true }
                                )
                                SettingsTileRow(
                                    title = stringResource(R.string.activity_media_convert_text_14),
                                    iconRes = R.drawable.ic_mic,
                                    valueBelow = true,
                                    value = if (sampleRateIndex == 0) {
                                        stringResource(R.string.media_convert_sample_rate_original) + (sourceAudioSampleRate?.let { " ($it Hz)" } ?: "")
                                    } else sampleRates.firstOrNull { it.id == sampleRateIndex }?.label ?: stringResource(R.string.media_convert_audio_auto_adapt),
                                    enabled = audioEnabled,
                                    onClick = { showSampleRateDialog = true }
                                )
                                SettingsTileRow(
                                    title = stringResource(R.string.activity_media_convert_text_15),
                                    iconRes = R.drawable.ic_file_audio,
                                    valueBelow = true,
                                    value = if (channelIndex == 0) {
                                        stringResource(R.string.media_convert_channel_original) + sourceAudioChannelLabel.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                                    } else channels.firstOrNull { it.id == channelIndex }?.label ?: stringResource(R.string.media_convert_audio_auto_adapt),
                                    enabled = audioEnabled,
                                    onClick = { showChannelDialog = true }
                                )
                                SettingsTileRow(
                                    title = stringResource(R.string.media_convert_volume),
                                    iconRes = R.drawable.ic_media_convert,
                                    valueBelow = true,
                                    value = "$audioVolumePercent%",
                                    enabled = audioEnabled,
                                    onClick = { showVolumeDialog = true }
                                )
                                if (isAudioOutput) {
                                    SettingsTileRow(
                                        title = stringResource(R.string.media_convert_audio_vbr),
                                        iconRes = R.drawable.ic_file_audio,
                                        valueBelow = true,
                                        value = when {
                                            !vbrSupported -> stringResource(R.string.media_convert_audio_vbr_unavailable)
                                            vbrActive -> "${MediaConvertAudioVbr.bitrateBand(audioVbrQuality)} kbps"
                                            else -> stringResource(R.string.media_convert_audio_vbr_off)
                                        },
                                        enabled = audioEnabled && vbrSupported,
                                        modifier = Modifier.testTag("audio-vbr-button"),
                                        onClick = { showAudioVbrDialog = true }
                                    )
                                }
                            }
                            if (audioCodecHint.isNotBlank()) {
                                Text(
                                    audioCodecHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                            if (isAudioOutput && !vbrSupported) {
                                Text(
                                    stringResource(R.string.media_convert_audio_vbr_codec_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            stringResource(R.string.media_convert_select_format_required),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }

        // ── Output directory ──
        SettingsTileGroup {
            SettingsTileRow(
                title = stringResource(R.string.activity_tool_output_directory),
                iconRes = R.drawable.ic_folder,
                value = outputDirectory.ifBlank { stringResource(R.string.activity_tool_default_output_directory) },
                valueBelow = true,
                enabled = !isConverting && dialog != MediaConvertDialog.OUTPUT_CONFLICT && !saveToSourceDirectory,
                onClick = onSelectOutputDirectory
            )
            SettingsTileSwitchRow(
                title = stringResource(R.string.save_to_source_folder),
                checked = saveToSourceDirectory,
                enabled = !isConverting && dialog != MediaConvertDialog.OUTPUT_CONFLICT,
                onCheckedChange = onSaveToSourceDirectoryChange
            )
        }

        // ── Progress & Log ──
        if (isConverting || log.isNotBlank()) {
            SettingsTileSection(
                title = stringResource(
                    if (isConverting) R.string.activity_speech_to_subtitle_text_06
                    else R.string.activity_settings_text_06
                )
            ) {
                if (isConverting) {
                    AppTaskProgress(
                        visible = true,
                        progress = progress.coerceIn(0, 100) / 100f,
                        onCancel = onCancel
                    )
                }
                if (log.isNotBlank()) {
                    if (isConverting) {
                        Text(
                            stringResource(R.string.activity_settings_text_06),
                            style = MaterialTheme.typography.titleMedium,
                            color = settingsPrimaryTextColor()
                        )
                    }
                    AppLogBox(log)
                }
            }
        }
    }

    if (showAudioVbrDialog && !isConverting && vbrSupported && selectedAudioCodec != "copy") {
        MediaConvertAudioVbrDialog(
            initialQuality = audioVbrQuality,
            onDismiss = { showAudioVbrDialog = false },
            onConfirm = { onAudioVbrQualityChange(it); showAudioVbrDialog = false }
        )
    }

    if (showVolumeDialog && !isConverting && selectedAudioCodec != "copy") {
        MediaConvertVolumeDialog(
            initialPercent = audioVolumePercent,
            onDismiss = { showVolumeDialog = false },
            onConfirm = { onAudioVolumeChange(it); showVolumeDialog = false }
        )
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

    if (showAudioBitrateDialog && !isConverting && selectedAudioCodec != "copy" && audioBitrateEditable && !vbrActive) {
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

/** Settings-tile dropdown row for selecting an option. */
@Composable
private fun <T> MediaConvertOptionRow(
    label: String,
    value: String,
    options: List<AppOption<T>>,
    enabled: Boolean = true,
    iconRes: Int? = null,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsTileRow(
            title = label,
            iconRes = iconRes,
            value = value,
            valueBelow = true,
            enabled = enabled && options.isNotEmpty(),
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelected(option.id)
                    }
                )
            }
        }
    }
}
