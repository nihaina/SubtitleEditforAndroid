package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import com.subtitleedit.filepicker.AppFilePickerContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import com.subtitleedit.feature.ui.MediaConvertScreen
import com.subtitleedit.feature.ui.MediaFormatOption
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File

/** 音视频格式转换页面；状态和 FFmpeg 任务由 [MediaConvertViewModel] 持有。 */
class MediaConvertActivity : AppComposeActivity() {
    private val viewModel: MediaConvertViewModel by viewModels()

    private val pickFileLauncher = registerForActivityResult(
        AppFilePickerContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.selectFiles(uris) }

    private val directoryPickerLauncher = registerForActivityResult(
        AppFilePickerContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::selectOutputDirectory) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.isRunning) viewModel.cancelConversion()
                isEnabled = false
                finish()
            }
        })
        collectEvents(viewModel.events) { event ->
            when (event) {
                is MediaConvertEvent.ShareOutputs -> shareOutputs(event.uris)
            }
        }
        setContent {
            val state by viewModel.state.collectAsState()
            val formats = remember(state.availableEncoders, state.availableAudioEncoders) { viewModel.formatList }
            val videoFormats = formats.filterNot { it.isAudioOnly }.map {
                MediaFormatOption(it.extension, it.displayName, it.isAudioOnly)
            }
            val audioFormats = formats.filter { it.isAudioOnly }.map {
                MediaFormatOption(it.extension, it.displayName, it.isAudioOnly)
            }
            val format = formats.firstOrNull { it.extension == state.selectedFormat }
            fun codecOption(id: String) = AppOption(id, when (id) {
                "auto" -> getString(R.string.media_convert_codec_auto)
                "copy" -> getString(R.string.media_convert_codec_copy)
                else -> MediaConvertFormats.codecNames[id] ?: id
            })
            val effectiveVideoCodec = format?.takeUnless { it.isAudioOnly }?.videoEncoder(state.options.selectedVideoCodec).orEmpty()
            val qualityRange = MediaConvertCommand.qualityRange(effectiveVideoCodec)
            val effectiveAudioCodec = format?.audioEncoder(state.options.selectedAudioCodec).orEmpty()
            val audioProfile = MediaConvertAudioCodecs.profile(effectiveAudioCodec, format?.formatName.orEmpty())
            val restrictedAudio = format?.isAudioOnly == false && effectiveAudioCodec != "copy"
            val sampleRateIndices = if (format?.isAudioOnly == false) {
                MediaConvertAudioCodecs.sampleRateIndices(effectiveAudioCodec, format.formatName)
            } else (0..5).toList()
            val sampleRateOptions = sampleRateIndices.map { index -> AppOption(index, viewModel.sampleRates[index]) }
            val channelOptions = viewModel.channels.mapIndexed { index, label -> AppOption(index, label) }.filter {
                !restrictedAudio || audioProfile.channels.isEmpty() || it.id == 0 || (if (it.id == 1) 2 else 1) in audioProfile.channels
            }.map { if (restrictedAudio && it.id == 0 && audioProfile.channels.isNotEmpty()) AppOption(0, getString(R.string.media_convert_audio_auto_adapt)) else it }
            val audioHints = buildList {
                if (restrictedAudio && (audioProfile.sampleRates.isNotEmpty() || audioProfile.channels.isNotEmpty())) add(getString(R.string.media_convert_audio_constraints_hint))
                if (restrictedAudio && (audioProfile.bitrateValues.isNotEmpty() || effectiveAudioCodec in setOf("libspeex", "dca", "mp2"))) add(getString(R.string.media_convert_audio_bitrate_adapt_hint))
                if (restrictedAudio && !audioProfile.adjustableBitrate) add(getString(R.string.media_convert_audio_derived_bitrate_hint))
                if (restrictedAudio && audioProfile.experimental) add(getString(R.string.media_convert_audio_experimental_hint))
            }.joinToString("\n")
            SubtitleEditComposeTheme {
                MediaConvertScreen(
                    sourceSummary = if (state.selectedFiles.isEmpty()) "" else getString(R.string.files_selected_header, state.selectedFiles.size),
                    sourceInfo = state.selectedFiles.mapIndexed { index, file -> "${index + 1}. ${file.fileName}\n${file.mediaInfo}" }.joinToString("\n\n"),
                    outputDirectory = state.outputDirectory,
                    saveToSourceDirectory = state.saveToSourceDirectory,
                    videoFormats = videoFormats,
                    audioFormats = audioFormats,
                    selectedFormat = state.selectedFormat,
                    videoCodecs = format?.videoCodecs.orEmpty().map(::codecOption),
                    selectedVideoCodec = state.options.selectedVideoCodec,
                    audioCodecs = format?.audioCodecs.orEmpty().map(::codecOption),
                    selectedAudioCodec = state.options.selectedAudioCodec,
                    resolution = state.options.resolution,
                    videoBitrate = state.options.videoBitrate,
                    qualityOptions = viewModel.qualityValues.mapIndexed { index, id -> AppOption(id, viewModel.qualityLabels[index]) },
                    selectedQualityId = state.options.selectedQualityId,
                    customQuality = state.options.customQuality,
                    audioBitrate = state.options.audioBitrate,
                    sampleRates = sampleRateOptions,
                    sampleRateIndex = state.options.sampleRateIndex,
                    channels = channelOptions,
                    channelIndex = state.options.channelIndex,
                    advancedExpanded = state.options.advancedExpanded,
                    isConverting = state.isConverting,
                    canConvert = state.selectedFiles.isNotEmpty() && state.selectedFormat != null,
                    canShare = state.outputUris.isNotEmpty(),
                    progress = state.progress,
                    log = state.log,
                    dialog = state.dialog,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onPickFiles = { pickFileLauncher.launch(arrayOf("video/*", "audio/*")) },
                    onSelectOutputDirectory = { directoryPickerLauncher.launch(viewModel.currentOutputDirectoryUri) },
                    onSaveToSourceDirectoryChange = viewModel::setSaveToSourceDirectory,
                    onFormatSelected = viewModel::selectFormat,
                    onVideoCodecSelected = viewModel::setVideoCodec,
                    onAudioCodecSelected = viewModel::setAudioCodec,
                    onResolutionSelected = viewModel::setResolution,
                    onVideoBitrateChange = viewModel::setVideoBitrate,
                    onQualitySelected = viewModel::setQuality,
                    onCustomQualityChange = viewModel::setCustomQuality,
                    onAudioBitrateChange = viewModel::setAudioBitrate,
                    onSampleRateSelected = viewModel::setSampleRate,
                    onChannelSelected = viewModel::setChannel,
                    onToggleAdvanced = viewModel::toggleAdvanced,
                    onConvert = viewModel::startConversion,
                    onCancel = viewModel::cancelConversion,
                    onShare = viewModel::share,
                    onOverwrite = viewModel::overwrite,
                    onAutoRename = viewModel::rename,
                    onDismissDialog = viewModel::dismissDialog,
                    videoFormatsExpanded = state.videoFormatsExpanded,
                    audioFormatsExpanded = state.audioFormatsExpanded,
                    onToggleVideoFormats = viewModel::toggleVideoFormats,
                    onToggleAudioFormats = viewModel::toggleAudioFormats,
                    audioBitrateEditable = !restrictedAudio || audioProfile.adjustableBitrate,
                    audioCodecHint = audioHints,
                    sourceVideoSize = state.selectedFiles.firstNotNullOfOrNull { it.videoSize },
                    multipleSources = state.selectedFiles.size > 1,
                    sourceVideoBitrateBps = state.selectedFiles.firstOrNull()?.videoBitrateBps,
                    canUseOriginalVideoBitrate = state.selectedFiles.isNotEmpty() && state.selectedFiles.all { (it.videoBitrateBps ?: 0) > 0 },
                    sourceAudioBitrateBps = state.selectedFiles.firstOrNull()?.audioBitrateBps,
                    canUseOriginalAudioBitrate = state.selectedFiles.isNotEmpty() && state.selectedFiles.all { (it.audioBitrateBps ?: 0) > 0 },
                    effectiveAudioCodec = effectiveAudioCodec,
                    audioMuxer = format?.formatName.orEmpty(),
                    sourceAudioSampleRate = state.selectedFiles.firstOrNull()?.audioSampleRate,
                    qualityHint = getString(R.string.media_convert_quality_range, qualityRange.first, qualityRange.last, if (effectiveVideoCodec == "libtheora") getString(R.string.media_convert_quality_larger) else getString(R.string.media_convert_quality_smaller))
                )
            }
        }
    }

    private fun shareOutputs(uris: List<Uri>) {
        val shareUris = uris.mapNotNull { uri ->
            if (uri.scheme == "file") uri.path?.let { path -> FileProvider.getUriForFile(this, "${packageName}.provider", File(path)) }
            else uri
        }
        if (shareUris.isEmpty()) return
        val intent = if (shareUris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { type = "*/*"; putExtra(Intent.EXTRA_STREAM, shareUris.first()) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(shareUris)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.activity_media_convert_text_16)))
    }
}
