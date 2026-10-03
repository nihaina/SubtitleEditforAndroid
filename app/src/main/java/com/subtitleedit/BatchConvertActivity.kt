package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.BatchConvertScreen
import com.subtitleedit.ui.components.AppOption
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SubtitleFormatConverter

/** 批量转换界面 */
class BatchConvertActivity : AppComposeActivity() {
    private val viewModel: BatchConvertViewModel by viewModels()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.addFiles(uris) }

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::selectOutputDirectory) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val formats = SubtitleFormatConverter.supportedTargetFormats.map { format ->
            AppOption(format, SubtitleFormatConverter.displayName(format))
        }

        setContent {
            val state by viewModel.state.collectAsState()
            SubtitleEditComposeTheme {
                BatchConvertScreen(
                    files = state.files,
                    formats = formats,
                    selectedFormat = state.targetFormat,
                    outputDirectoryLabel = state.outputDirectoryLabel,
                    dialog = state.dialog,
                    onNavigateBack = { finish() },
                    onSelectFiles = {
                        filePickerLauncher.launch(arrayOf("text/*", "application/*"))
                    },
                    onRemoveFile = viewModel::removeFile,
                    onSelectFormat = viewModel::selectFormat,
                    onSelectOutputDirectory = {
                        directoryPickerLauncher.launch(viewModel.outputDirectoryUri)
                    },
                    onStartConversion = viewModel::startConversion,
                    onDismissDialog = viewModel::dismissDialog,
                    onOverwriteConflicts = viewModel::overwriteConflicts,
                    onRenameConflicts = viewModel::renameConflicts
                )
            }
        }
    }
}
