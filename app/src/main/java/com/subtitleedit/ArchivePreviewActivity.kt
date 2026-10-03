package com.subtitleedit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.ArchivePreviewScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File

class ArchivePreviewActivity : AppComposeActivity() {
    private val viewModel: ArchivePreviewViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initialize(
            archiveName = intent.getStringExtra(EXTRA_ARCHIVE_NAME).orEmpty(),
            previewPath = intent.getStringExtra(EXTRA_PREVIEW_PATH).orEmpty()
        )

        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                ArchivePreviewScreen(
                    archiveName = state.archiveName,
                    currentDirectory = state.currentDirectory,
                    entryCount = state.entryCount,
                    items = state.items,
                    isLoading = state.isLoading,
                    errorMessage = state.errorMessage,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenDirectory = viewModel::showDirectory
                )
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.navigateUp()) finish()
            }
        })
    }

    companion object {
        private const val EXTRA_ARCHIVE_NAME = "extra_archive_name"
        private const val EXTRA_PREVIEW_PATH = "extra_preview_path"

        fun createIntent(context: Context, archiveName: String, previewFile: File): Intent =
            Intent(context, ArchivePreviewActivity::class.java).apply {
                putExtra(EXTRA_ARCHIVE_NAME, archiveName)
                putExtra(EXTRA_PREVIEW_PATH, previewFile.absolutePath)
            }
    }
}
