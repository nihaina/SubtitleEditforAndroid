package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var backPressedCallback: OnBackPressedCallback

    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.selectFiles(uris) }

    // 输出目录选择器
    private val outputDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
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
                    onLanguageSelected = viewModel::selectLanguage,
                    onFormatSelected = viewModel::selectFormat,
                    onAddToAutoTranslateChange = viewModel::setAddToAutoTranslate,
                    onDisableVadForTxtChange = viewModel::setDisableVadForTxt,
                    onSelectOutputDirectory = { outputDirLauncher.launch(viewModel.outputDirUri) },
                    onStart = viewModel::start,
                    onCancel = viewModel::confirmCancelConversion,
                    onDismissDialog = viewModel::dismissDialog,
                    onOverwriteOutput = { viewModel.resolveOutputConflict(overwriteOutput = true) },
                    onRenameOutput = { viewModel.resolveOutputConflict(overwriteOutput = false) },
                    onConfirmCancel = viewModel::onConfirmCancel,
                    onConfirmBack = {
                        viewModel.confirmBack()
                        leavePage()
                    }
                )
            }
        }

        collectEvents(viewModel.events) { event ->
            when (event) {
                SpeechToSubtitleEvent.OpenAsrSettings -> AsrSettingsNavigation.open(this)
                is SpeechToSubtitleEvent.OpenAutoTranslate -> openAutoTranslate(event)
            }
        }

        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.requestBack()) leavePage()
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun leavePage() {
        backPressedCallback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
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
