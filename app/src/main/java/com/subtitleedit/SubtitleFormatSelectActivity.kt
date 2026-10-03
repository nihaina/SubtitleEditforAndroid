package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.SubtitleFormatSelectScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

class SubtitleFormatSelectActivity : AppComposeActivity() {
    private val viewModel: SubtitleFormatSelectViewModel by viewModels()

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onFilePicked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                SubtitleFormatSelectScreen(
                    selectedFileName = state.selectedName.takeIf { state.selectedUri != null },
                    onSelectFile = { filePicker.launch(arrayOf("text/*", "application/*")) },
                    onConfirm = ::openEditor,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }

    private fun openEditor() {
        val state = viewModel.state.value
        val uri = state.selectedUri ?: return
        startActivity(Intent(this, SubtitleFormatEditorActivity::class.java).apply {
            putExtra(SubtitleFormatEditorActivity.EXTRA_URI, uri.toString())
            putExtra(SubtitleFormatEditorActivity.EXTRA_FILE_NAME, state.selectedName)
        })
    }
}
