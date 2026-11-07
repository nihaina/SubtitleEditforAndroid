package com.subtitleedit

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.subtitleedit.filepicker.AppFilePickerContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.ui.components.AppToolScaffold

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
    AppToolScaffold(
        title = if (currentFolder.isEmpty()) stringResource(R.string.drafts) else currentFolder,
        onBack = onBack,
        showTopBar = showTopBar,
        scrollable = false,
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(
                    items = drafts,
                    key = { "${it.folderName}/${it.fileName}" }
                ) { draft ->
                    DraftRow(
                        draft = draft,
                        onClick = { onItemClick(draft) },
                        onLongClick = { onLongPress(draft) },
                        onDeleteClick = { onDeleteClick(draft) }
                    )
                }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DraftRow(
    draft: DraftsActivity.DraftItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), MaterialTheme.shapes.large)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (!draft.isFolder) onLongClick()
                }
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(if (draft.isFolder) R.drawable.ic_folder else R.drawable.ic_file_text),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.padding(end = 12.dp).size(32.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = draft.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (draft.isFolder) stringResource(R.string.draft_folder) else draft.formattedDate,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_delete),
                    contentDescription = stringResource(R.string.delete),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
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
