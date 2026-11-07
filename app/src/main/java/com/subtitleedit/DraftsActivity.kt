package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.subtitleedit.filepicker.AppFilePickerContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.feature.ui.BrowserFileLayout
import com.subtitleedit.feature.ui.BrowserFileRow
import com.subtitleedit.feature.ui.browserFileContentPadding
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.settings.settingsPageBackground
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.DraftManager
import java.io.File

/** Draft browser host. Page state and file access live in [DraftsViewModel]. */
class DraftsActivity : AppComposeActivity() {
    private val viewModel: DraftsViewModel by viewModels()

    private val exportFileLauncher = registerForActivityResult(
        AppFilePickerContracts.CreateDocument("*/*")
    ) { uri -> viewModel.onExportTargetSelected(uri) }

    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK, result.data)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Whether the editor launched this page to load a saved draft.
        viewModel.initialize(fromEditor = intent.getBooleanExtra(EXTRA_FROM_EDITOR, false))
        collectEvents(viewModel.events) { event ->
            when (event) {
                is DraftsEvent.PickExportFile -> exportFileLauncher.launch(event.fileName)
                is DraftsEvent.PreviewDraft -> previewLauncher.launch(
                    DraftPreviewActivity.createIntent(
                        this, event.draft.folderName, event.draft.fileName, event.fromEditor
                    )
                )
            }
        }

        setContent {
            val state by viewModel.state.collectAsState()
            BackHandler(enabled = state.currentFolder.isNotEmpty()) { viewModel.goToRoot() }
            SubtitleEditComposeTheme {
                DraftsPage(
                    currentFolder = state.currentFolder,
                    drafts = state.drafts,
                    dialogState = state.dialogState,
                    onBack = ::handleBack,
                    onBackToRoot = viewModel::goToRoot,
                    onItemClick = viewModel::openDraft,
                    onLongPress = viewModel::showActions,
                    onDeleteClick = viewModel::requestDelete,
                    onRequestDelete = viewModel::requestDelete,
                    onDismissDialog = viewModel::dismissDialog,
                    onCopyDraft = viewModel::copyDraft,
                    onExportDraft = viewModel::exportDraft,
                    onDeleteDraft = viewModel::deleteDraft,
                    onDeleteFolder = viewModel::deleteFolder
                )
            }
        }
    }

    private fun handleBack() {
        if (viewModel.navigateUp()) onBackPressedDispatcher.onBackPressed()
    }

    companion object {
        const val EXTRA_FROM_EDITOR = "extra_from_editor"
        const val EXTRA_DRAFT_CONTENT = "extra_draft_content"
        const val EXTRA_DRAFT_FILE_NAME = "extra_draft_file_name"
        const val EXTRA_DRAFT_FOLDER_NAME = "extra_draft_folder_name"
    }

    data class DraftItem(
        val folderName: String,
        val fileName: String,
        val displayName: String,
        val formattedDate: String,
        val isFolder: Boolean
    )
}

internal sealed interface DraftsDialogState {
    data class Actions(val draft: DraftsActivity.DraftItem) : DraftsDialogState
    data class DeleteDraft(val draft: DraftsActivity.DraftItem) : DraftsDialogState
    data class DeleteFolder(val draft: DraftsActivity.DraftItem) : DraftsDialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DraftsPage(
    currentFolder: String,
    drafts: List<DraftsActivity.DraftItem>,
    dialogState: DraftsDialogState?,
    showTopBar: Boolean = true,
    viewMode: FileBrowserViewMode = FileBrowserViewMode.DETAILED,
    onBack: () -> Unit,
    onBackToRoot: () -> Unit,
    onItemClick: (DraftsActivity.DraftItem) -> Unit,
    onLongPress: (DraftsActivity.DraftItem) -> Unit,
    onDeleteClick: (DraftsActivity.DraftItem) -> Unit,
    onRequestDelete: (DraftsActivity.DraftItem) -> Unit,
    onDismissDialog: () -> Unit,
    onCopyDraft: (DraftsActivity.DraftItem) -> Unit,
    onExportDraft: (DraftsActivity.DraftItem) -> Unit,
    onDeleteDraft: (DraftsActivity.DraftItem) -> Unit,
    onDeleteFolder: (DraftsActivity.DraftItem) -> Unit
) {
    val pageBackground = settingsPageBackground()
    AppToolScaffold(
        title = if (currentFolder.isEmpty()) stringResource(R.string.drafts) else currentFolder,
        onBack = onBack,
        showTopBar = showTopBar,
        scrollable = false,
        contentPadding = PaddingValues(0.dp),
        contentBackground = pageBackground,
        topBarContainerColor = pageBackground,
        topBarScrolledContainerColor = pageBackground,
        actions = {
            if (currentFolder.isNotEmpty()) {
                IconButton(onClick = onBackToRoot) {
                    Icon(
                        painter = painterResource(android.R.drawable.ic_menu_revert),
                        contentDescription = stringResource(R.string.menu_drafts_title_01),
                        tint = Color.Unspecified
                    )
                }
            }
        }
    ) {
        if (drafts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_drafts),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            BrowserFileLayout(
                items = drafts,
                key = { "${it.folderName}/${it.fileName}" },
                viewMode = viewMode,
                modifier = Modifier.fillMaxSize(),
                contentPadding = browserFileContentPadding(viewMode),
                adaptiveGrid = true
            ) { draft ->
                DraftRow(
                    draft = draft,
                    viewMode = viewMode,
                    onClick = { onItemClick(draft) },
                    // Folders have no action menu, so long-press goes straight to delete.
                    onLongClick = { if (draft.isFolder) onDeleteClick(draft) else onLongPress(draft) }
                )
            }
        }
    }

    DraftsDialogHost(
        dialogState = dialogState,
        onDismiss = onDismissDialog,
        onCopyDraft = onCopyDraft,
        onExportDraft = onExportDraft,
        onRequestDelete = onRequestDelete,
        onDeleteDraft = onDeleteDraft,
        onDeleteFolder = onDeleteFolder
    )
}

/** Same row as the directory and favorites pages, backed by the draft's on-disk file. */
@Composable
private fun DraftRow(
    draft: DraftsActivity.DraftItem,
    viewMode: FileBrowserViewMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val file = remember(draft.folderName, draft.fileName) {
        val draftsDir = DraftManager.getDraftsDir(context)
        if (draft.isFolder) File(draftsDir, draft.folderName)
        else DraftManager.getDraftFile(context, draft.folderName, draft.fileName)
    }
    BrowserFileRow(
        file = file,
        relativePathRoot = null,
        isSelected = false,
        isSelectionMode = false,
        onClick = onClick,
        onLongClick = onLongClick,
        restrictedOverride = false,
        viewMode = viewMode,
        directoryPageStyle = true
    )
}

@Composable
private fun DraftsDialogHost(
    dialogState: DraftsDialogState?,
    onDismiss: () -> Unit,
    onCopyDraft: (DraftsActivity.DraftItem) -> Unit,
    onExportDraft: (DraftsActivity.DraftItem) -> Unit,
    onRequestDelete: (DraftsActivity.DraftItem) -> Unit,
    onDeleteDraft: (DraftsActivity.DraftItem) -> Unit,
    onDeleteFolder: (DraftsActivity.DraftItem) -> Unit
) {
    when (dialogState) {
        is DraftsDialogState.Actions -> {
            AppDialog(
                onDismissRequest = onDismiss,
                title = { Text(dialogState.draft.displayName) },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                val draft = dialogState.draft
                                onDismiss()
                                onExportDraft(draft)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.draft_export))
                        }
                        TextButton(
                            onClick = {
                                val draft = dialogState.draft
                                onDismiss()
                                onCopyDraft(draft)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.draft_copy_full_text))
                        }
                        TextButton(
                            onClick = {
                                onRequestDelete(dialogState.draft)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.draft_delete))
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        is DraftsDialogState.DeleteDraft -> {
            AppDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.delete)) },
                text = { Text(stringResource(R.string.delete_draft_confirm)) },
                confirmButton = {
                    TextButton(onClick = { onDeleteDraft(dialogState.draft) }) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        is DraftsDialogState.DeleteFolder -> {
            AppDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.delete)) },
                text = { Text(stringResource(R.string.draft_delete_folder_confirm)) },
                confirmButton = {
                    TextButton(onClick = { onDeleteFolder(dialogState.draft) }) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        null -> Unit
    }
}
