package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import com.subtitleedit.filepicker.AppFilePickerContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.SpeechToSubtitleScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

/**
 * 语音转字幕功能页面
 * 支持音频/视频文件转字幕，多种语言识别
 * 使用 sherpa-onnx + Whisper 进行离线语音识别
 */
class SpeechToSubtitleActivity : AppComposeActivity() {
    private val viewModel: SpeechToSubtitleViewModel by viewModels()

    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        AppFilePickerContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.selectFiles(uris) }

    // 输出目录选择器
    private val outputDirLauncher = registerForActivityResult(
        AppFilePickerContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::selectOutputDirectory) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                SpeechToSubtitleScreen(
                    state = state,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() },
                    onSettings = viewModel::openSettings,
                    onSelectFiles = { filePickerLauncher.launch(arrayOf("audio/*", "video/*")) },
                    onRemoveFile = viewModel::removeFile,
                    onLanguageSelected = viewModel::selectLanguage,
                    onFormatSelected = viewModel::selectFormat,
                    onAddToAutoTranslateChange = viewModel::setAddToAutoTranslate,
                    onDisableVadForTxtChange = viewModel::setDisableVadForTxt,
                    onSelectOutputDirectory = { outputDirLauncher.launch(viewModel.outputDirUri) },
                    onSaveToSourceDirectoryChange = viewModel::setSaveToSourceDirectory,
                    onStart = viewModel::start,
                    onCancel = viewModel::confirmCancelConversion,
                    onDismissDialog = viewModel::dismissDialog,
                    onOverwriteOutput = { viewModel.resolveOutputConflict(overwriteOutput = true) },
                    onRenameOutput = { viewModel.resolveOutputConflict(overwriteOutput = false) },
                    onConfirmCancel = viewModel::onConfirmCancel,
                )
            }
        }

        collectEvents(viewModel.events) { event ->
            when (event) {
                SpeechToSubtitleEvent.OpenAsrSettings -> AsrSettingsNavigation.open(this)
                is SpeechToSubtitleEvent.OpenAutoTranslate -> openAutoTranslate(event)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onPause() {
        viewModel.onPause()
        super.onPause()
    }

    private fun openAutoTranslate(event: SpeechToSubtitleEvent.OpenAutoTranslate) {
        val intent = Intent(this, AutoTranslateActivity::class.java)
            .putParcelableArrayListExtra(
                AutoTranslateActivity.EXTRA_INITIAL_FILE_URIS,
                ArrayList(event.files)
            )
        startActivity(intent)
        finish()
    }
}
