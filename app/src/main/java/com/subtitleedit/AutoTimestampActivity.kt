package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.AutoTimestampScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager

/**
 * 自动打轴页面 - 自动检测语音段并生成时间轴
 */
class AutoTimestampActivity : AppComposeActivity() {
    private val viewModel: AutoTimestampViewModel by viewModels()

    // 音频文件选择器
    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.selectAudios(uris) }

    private val subtitlePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::selectSubtitle) }

    // 输出目录选择器
    private val outputDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::selectOutputDirectory) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                AutoTimestampScreen(
                    audioFilesText = state.audioFilesText,
                    subtitleFileText = state.subtitleFileText,
                    outputDirectory = state.outputDirectoryText,
                    secondaryProcessingEnabled = state.secondaryProcessingEnabled,
                    secondaryProcessingAvailable = state.secondaryProcessingAvailable,
                    secondaryProcessingHint = state.secondaryProcessingHint,
                    outputFormat = state.outputFormat,
                    isGenerating = state.isGenerating,
                    canGenerate = state.canGenerate,
                    status = state.statusText,
                    preview = state.previewText,
                    dialog = state.dialog,
                    onBack = ::requestBack,
                    onSettings = ::openTimestampSettings,
                    onSelectAudio = { audioPickerLauncher.launch(arrayOf("audio/*", "video/*")) },
                    onSecondaryProcessingChange = viewModel::setSecondaryProcessingEnabled,
                    onSelectSubtitle = { subtitlePickerLauncher.launch(arrayOf("*/*")) },
                    onFormatChange = viewModel::setOutputFormat,
                    onSelectOutputDirectory = { outputDirLauncher.launch(viewModel.outputDirUri) },
                    onGenerate = viewModel::generate,
                    onRequestCancel = viewModel::requestCancel,
                    onConfirmCancel = { if (viewModel.confirmCancel()) finish() },
                    onOverwrite = { viewModel.resolveOutputConflict(overwrite = true) },
                    onRename = { viewModel.resolveOutputConflict(overwrite = false) },
                    onDismissDialog = viewModel::dismissDialog
                )
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestBack()
        })
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun requestBack() {
        if (viewModel.requestBack()) finish()
    }

    private fun openTimestampSettings() {
        if (viewModel.opensVadSettings) {
            startActivity(Intent(this, VadModelSettingsActivity::class.java))
        } else {
            AsrSettingsNavigation.open(this, SettingsManager.getInstance(this))
        }
    }
}
