package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.VocalSeparationScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

class VocalSeparationActivity : AppComposeActivity() {
    private val viewModel: VocalSeparationViewModel by viewModels()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.selectFiles(uris) }

    private val outputDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::selectOutputDirectory) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                VocalSeparationScreen(
                    state = state,
                    onBack = ::requestBack,
                    onConfirmBack = {
                        viewModel.confirmBack()
                        finish()
                    },
                    onSettings = { startActivity(Intent(this, VocalSeparationSettingsActivity::class.java)) },
                    onSelectFiles = { filePickerLauncher.launch(arrayOf("audio/*", "video/*")) },
                    onSelectOutputDirectory = { outputDirLauncher.launch(viewModel.outputDirUri) },
                    onStemChange = viewModel::setStemSelected,
                    onStart = viewModel::start,
                    onCancel = viewModel::onCancelClicked,
                    onOverwrite = { viewModel.resolveOutputConflict(overwrite = true) },
                    onAutoRename = { viewModel.resolveOutputConflict(overwrite = false) },
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
}
