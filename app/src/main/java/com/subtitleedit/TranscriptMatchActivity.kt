package com.subtitleedit

import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.TranscriptMatchScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

class TranscriptMatchActivity : AppComposeActivity() {
    private val viewModel: TranscriptMatchViewModel by viewModels()

    private val textPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::selectText)
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::selectAudio)
    }
    private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel::selectOutputDirectory)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                TranscriptMatchScreen(
                    textFileName = state.textFileName,
                    audioFileName = state.audioFileName,
                    outputDirectory = state.outputDirectory,
                    pendingFiles = state.pendingFiles,
                    formats = viewModel.formats,
                    selectedFormat = state.selectedFormat,
                    languages = viewModel.languages,
                    selectedLanguage = state.selectedLanguage,
                    modelHint = state.modelHint,
                    startEnabled = state.startEnabled,
                    isRunning = state.isRunning,
                    isCancelling = state.isCancelling,
                    progress = state.progress,
                    progressText = state.progressText,
                    dialog = state.dialog,
                    dialogTitle = state.dialogTitle,
                    dialogMessage = state.dialogMessage,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onSelectText = { textPicker.launch(arrayOf("text/*", "application/octet-stream")) },
                    onSelectAudio = { audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    onSelectOutputDirectory = { directoryPicker.launch(viewModel.outputDirectoryUri) },
                    onFormatSelected = viewModel::setFormat,
                    onLanguageSelected = viewModel::setLanguage,
                    onStartOrCancel = {
                        if (state.isRunning) viewModel.requestCancel() else viewModel.start()
                    },
                    onDialogConfirm = {
                        if (viewModel.confirmDialog()) finish()
                    },
                    onDialogDismiss = viewModel::dismissDialog
                )
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.requestBack()) {
                    isEnabled = false
                    finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshModelState()
    }
}
