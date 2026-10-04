package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.AutoTranslateScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

/** 多文件 AI 字幕处理页面；队列和任务由 [AutoTranslateViewModel] 持有。 */
class AutoTranslateActivity : AppComposeActivity() {
    companion object {
        const val EXTRA_INITIAL_FILE_URIS = "auto_translate_initial_file_uris"
    }

    private val viewModel: AutoTranslateViewModel by viewModels()
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.addFiles(uris.toList()) }
    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::selectOutputDirectory) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectEvents(viewModel.events) { event ->
            when (event) {
                AutoTranslateEvent.OpenSettings -> startActivity(Intent(this, AiSettingsActivity::class.java))
                AutoTranslateEvent.Exit -> finish()
            }
        }
        viewModel.initialize(initialFileUris())
        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                AutoTranslateScreen(
                    state = state,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() },
                    onSettings = viewModel::openSettings,
                    onSelectFiles = { filePickerLauncher.launch(arrayOf("text/*", "application/*")) },
                    onPunctuationPredictionChange = viewModel::setPunctuationPrediction,
                    onTranslationChange = viewModel::setTranslation,
                    onSelectOutputDirectory = { directoryPickerLauncher.launch(viewModel.currentOutputDirectoryUri) },
                    onStart = viewModel::startQueuedFiles,
                    onRetry = viewModel::retry,
                    onRemove = viewModel::requestRemove,
                    onConfirmRemove = viewModel::confirmRemove,
                    onDismissRemove = viewModel::dismissRemove,
                    onDismissOutputConflict = viewModel::dismissOutputConflict,
                    onOverwriteOutput = { viewModel.resolveOutputConflict(true) },
                    onRenameOutput = { viewModel.resolveOutputConflict(false) },
                    onConfirmExit = viewModel::stopAndExit,
                    onDismissExit = viewModel::dismissExit
                )
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.requestBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun initialFileUris(): List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableArrayListExtra(EXTRA_INITIAL_FILE_URIS, Uri::class.java).orEmpty()
    } else {
        intent.getParcelableArrayListExtra<Uri>(EXTRA_INITIAL_FILE_URIS).orEmpty()
    }
}
