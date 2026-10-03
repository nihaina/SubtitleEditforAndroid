package com.subtitleedit

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.SubtitleFormatEditorScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

class SubtitleFormatEditorActivity : AppComposeActivity() {
    private val viewModel: SubtitleFormatEditorViewModel by viewModels()

    companion object {
        const val EXTRA_URI = "subtitle_format_uri"
        const val EXTRA_FILE_NAME = "subtitle_format_file_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialized = viewModel.initialize(
            uriText = intent.getStringExtra(EXTRA_URI),
            fileNameExtra = intent.getStringExtra(EXTRA_FILE_NAME)
        )
        if (!initialized) {
            finish()
            return
        }

        collectEvents(viewModel.events) { event ->
            when (event) {
                SubtitleFormatEditorEvent.Finish -> finish()
            }
        }
        setupBackHandling()
        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                SubtitleFormatEditorScreen(
                    fileName = state.fileName,
                    fileInfo = state.fileInfo,
                    items = state.items,
                    isLoading = state.isLoading,
                    isApplying = state.isApplying,
                    showSaveConfirmation = state.showSaveConfirmation,
                    showDiscardConfirmation = state.showDiscardConfirmation,
                    onBack = ::handleBack,
                    onSaveRequest = viewModel::requestSave,
                    onConfirmSave = viewModel::confirmSave,
                    onDismissSaveConfirmation = viewModel::dismissSaveConfirmation,
                    onConfirmDiscard = {
                        viewModel.dismissDiscardConfirmation()
                        finish()
                    },
                    onDismissDiscardConfirmation = viewModel::dismissDiscardConfirmation,
                    onSelectAll = viewModel::selectAll,
                    onSelectRange = viewModel::selectRange,
                    onSelectionChanged = viewModel::setSelection,
                    onEditItem = viewModel::editItem,
                    onInvalidRange = viewModel::onInvalidRange,
                    onApply = viewModel::applyFormatting
                )
            }
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
    }

    private fun handleBack() {
        if (viewModel.requestBack()) finish()
    }
}
