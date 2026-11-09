package com.subtitleedit

import android.app.Activity
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.MediaTagEditorScreen
import com.subtitleedit.filepicker.AppFilePickerContracts
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

class MediaTagEditorActivity : AppComposeActivity() {
    companion object {
        const val EXTRA_FILE_PATHS = "media_tag_file_paths"
        const val EXTRA_COVER_PATH = "media_tag_cover_path"
    }

    private val viewModel: MediaTagEditorViewModel by viewModels()
    private val coverPicker = registerForActivityResult(
        AppFilePickerContracts.OpenDocument(MediaTagFileSelectionPolicy.coverExtensions)
    ) { uri -> uri?.let(viewModel::selectCover) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initialize(intent.getStringArrayListExtra(EXTRA_FILE_PATHS).orEmpty(), intent.getStringExtra(EXTRA_COVER_PATH))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                viewModel.cancel()
                finish()
            }
        })
        collectEvents(viewModel.events) {
            setResult(Activity.RESULT_OK)
            finish()
        }
        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                MediaTagEditorScreen(
                    state = state,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onMetadataChanged = viewModel::setMetadata,
                    onPickCover = { coverPicker.launch(arrayOf("image/*")) },
                    onSave = viewModel::save,
                    onCancel = viewModel::cancel
                )
            }
        }
    }
}
