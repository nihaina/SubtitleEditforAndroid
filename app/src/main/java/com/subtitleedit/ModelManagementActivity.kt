package com.subtitleedit

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.subtitleedit.feature.ui.ModelManagementScreen
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.OverwritingToast

class ModelManagementActivity : AppComposeActivity() {
    private val viewModel: ModelManagementViewModel by viewModels()

    private val documentPickers = ModelPickTarget.entries.associateWith { target ->
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.onDocumentPicked(target, it) }
        }
    }

    private val tokenizerFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::onTokenizerFolderPicked) }

    private val forcedAlignerPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.onForcedAlignerPicked(uris) }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onStorageAccessResult() }

    private val writeStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.onStorageAccessResult() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onNotificationPermissionResult(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.state.collectAsState()
            val asrImport by viewModel.asrImport.state.collectAsState()
            val asrDialog by viewModel.asrImport.dialog.collectAsState()
            val demucsImport by viewModel.demucsImport.state.collectAsState()
            val demucsDialog by viewModel.demucsImport.dialog.collectAsState()
            val exportDialog by viewModel.exportDialog.collectAsState()
            SubtitleEditComposeTheme {
                ModelManagementScreen(
                    asrImport = asrImport,
                    demucsImport = demucsImport,
                    selectedPage = state.selectedPage,
                    models = state.models,
                    modelsDirectoryLabel = state.modelsDirectoryLabel,
                    isLoading = state.isLoading,
                    errorMessage = state.errorMessage,
                    emptyMessage = state.emptyMessage,
                    isExporting = state.isExporting,
                    deletingModelKey = state.deletingModelKey,
                    asrDialog = asrDialog,
                    demucsDialog = demucsDialog,
                    exportDialog = exportDialog,
                    dialog = state.dialog,
                    onPageSelected = viewModel::onPageSelected,
                    onAsrImportAction = viewModel.asrImport::onAction,
                    onBuiltInVadChanged = viewModel.asrImport::onBuiltInVadChanged,
                    onDemucsImportAction = viewModel.demucsImport::onAction,
                    onNavigateBack = { onBackPressedDispatcher.onBackPressed() },
                    onExport = viewModel::onExportRequested,
                    onDelete = viewModel::onDeleteRequested,
                    onDismissDialog = viewModel::dismissDialog,
                    onConfirmDelete = viewModel::onDeleteConfirmed,
                    onConfirmOverwrite = viewModel::onOverwriteConfirmed
                )
            }
        }

        collectEvents(viewModel.events, ::handleEvent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun handleEvent(event: ModelManagementEvent) {
        when (event) {
            is ModelManagementEvent.PickDocument ->
                documentPickers.getValue(event.target).launch(event.target.mimeTypes)
            ModelManagementEvent.PickQwen3Tokenizer -> tokenizerFolderPicker.launch(null)
            ModelManagementEvent.PickForcedAligner -> forcedAlignerPicker.launch(arrayOf("*/*"))
            ModelManagementEvent.RequestStorageAccess -> requestStorageAccess()
            ModelManagementEvent.RequestNotificationPermission ->
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            is ModelManagementEvent.OpenScreen -> startActivity(Intent(this, event.activity))
            ModelManagementEvent.OpenAsrSettings -> AsrSettingsNavigation.open(this)
            is ModelManagementEvent.OpenUrl -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(event.url))
                val failure = event.failureMessage
                if (failure == null) {
                    startActivity(intent)
                } else {
                    runCatching { startActivity(intent) }.onFailure {
                        OverwritingToast.makeText(this, failure, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            val opened = runCatching { manageStorageLauncher.launch(appIntent) }.isSuccess ||
                runCatching {
                    manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }.isSuccess
            if (!opened) viewModel.onStorageAccessUnavailable()
        } else {
            writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}
