package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.selectFiles(uris) }

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
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
            val videoFormats = viewModel.formatList.filterNot { it.isAudioOnly }.map {
                MediaFormatOption(it.extension, it.displayName, it.isAudioOnly)
            }
            val audioFormats = viewModel.formatList.filter { it.isAudioOnly }.map {
                MediaFormatOption(it.extension, it.displayName, it.isAudioOnly)
            }
            SubtitleEditComposeTheme {
                MediaConvertScreen(
                    sourceSummary = if (state.selectedFiles.isEmpty()) "" else getString(R.string.files_selected_header, state.selectedFiles.size),
                    sourceInfo = state.selectedFiles.mapIndexed { index, file -> "${index + 1}. ${file.fileName}\n${file.mediaInfo}" }.joinToString("\n\n"),
                    outputDirectory = state.outputDirectory,
                    videoFormats = videoFormats,
                    audioFormats = audioFormats,
                    selectedFormat = state.selectedFormat,
                    videoCodecs = viewModel.formatList.firstOrNull { it.extension == state.selectedFormat }?.videoCodecs.orEmpty(),
                    selectedVideoCodec = state.selectedVideoCodec,
                    audioCodecs = viewModel.formatList.firstOrNull { it.extension == state.selectedFormat }?.audioCodecs.orEmpty(),
                    selectedAudioCodec = state.selectedAudioCodec,
                    resolutions = viewModel.resolutions.mapIndexed { index, label -> AppOption(index, label) },
                    resolutionIndex = state.resolutionIndex,
                    videoBitrate = state.videoBitrate,
                    qualityOptions = viewModel.qualityValues.mapIndexed { index, id -> AppOption(id, viewModel.qualityLabels[index]) },
                    selectedQualityId = state.selectedQualityId,
                    customQuality = state.customQuality,
                    audioBitrate = state.audioBitrate,
                    sampleRates = viewModel.sampleRates.mapIndexed { index, label -> AppOption(index, label) },
                    sampleRateIndex = state.sampleRateIndex,
                    channels = viewModel.channels.mapIndexed { index, label -> AppOption(index, label) },
                    channelIndex = state.channelIndex,
                    advancedExpanded = state.advancedExpanded,
                    isConverting = state.isConverting,
                    canConvert = state.selectedFiles.isNotEmpty() && state.selectedFormat != null,
                    canShare = state.outputUris.isNotEmpty(),
                    progress = state.progress,
                    log = state.log,
                    dialog = state.dialog,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onPickFiles = { pickFileLauncher.launch(arrayOf("video/*", "audio/*")) },
                    onSelectOutputDirectory = { directoryPickerLauncher.launch(viewModel.currentOutputDirectoryUri) },
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
                    onDismissDialog = viewModel::dismissDialog
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
