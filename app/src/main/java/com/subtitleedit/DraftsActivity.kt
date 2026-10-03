package com.subtitleedit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.util.DraftManager

/** Draft browser host. File access and platform results remain in the Activity. */
class DraftsActivity : AppComposeActivity() {

    private var currentFolder by mutableStateOf("")
    private var drafts by mutableStateOf(emptyList<DraftItem>())
    private var dialogState by mutableStateOf<DraftsDialogState?>(null)

    // Whether the editor launched this page to load a saved draft.
    private var fromEditor = false

    private var draftToExport: DraftItem? = null

    private val exportFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            exportToUri(uri)
        } else {
            draftToExport = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromEditor = intent.getBooleanExtra(EXTRA_FROM_EDITOR, false)
        loadDrafts()

        setContent {
            SubtitleEditComposeTheme {
                DraftsPage(
                    currentFolder = currentFolder,
                    drafts = drafts,
                    fromEditor = fromEditor,
                    dialogState = dialogState,
                    onBack = ::handleBack,
                    onBackToRoot = ::goToRoot,
                    onItemClick = ::openDraft,
                    onLongPress = ::showActions,
                    onDeleteClick = ::requestDelete,
                    onRequestDelete = ::requestDelete,
                    onDismissDialog = { dialogState = null },
                    onCopyDraft = { draft ->
                        copyToClipboard(DraftManager.readDraft(this, draft.folderName, draft.fileName))
                    },
                    onExportDraft = ::exportDraft,
                    onLoadDraft = ::returnDraftToEditor,
                    onDeleteDraft = ::deleteDraft,
                    onDeleteFolder = ::deleteFolder
                )
            }
        }
    }

    private fun handleBack() {
        if (currentFolder.isNotEmpty()) {
            goToRoot()
        } else {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun goToRoot() {
        currentFolder = ""
        dialogState = null
        loadDrafts()
    }

    private fun openDraft(draft: DraftItem) {
        if (draft.isFolder) {
            currentFolder = draft.folderName
            loadDrafts()
        } else {
            dialogState = DraftsDialogState.Preview(
                draft = draft,
                content = DraftManager.readDraft(this, draft.folderName, draft.fileName)
            )
        }
    }

    private fun showActions(draft: DraftItem) {
        if (!draft.isFolder) dialogState = DraftsDialogState.Actions(draft)
    }

    private fun requestDelete(draft: DraftItem) {
        dialogState = if (draft.isFolder) {
            DraftsDialogState.DeleteFolder(draft)
        } else {
            DraftsDialogState.DeleteDraft(draft)
        }
    }

    private fun copyToClipboard(content: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("draft", content))
        com.subtitleedit.util.OverwritingToast.makeText(
            this,
            "已复制到剪贴板",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun exportDraft(draft: DraftItem) {
        draftToExport = draft
        exportFileLauncher.launch(draft.fileName)
    }

    private fun exportToUri(uri: android.net.Uri) {
        draftToExport?.let { draft ->
            try {
                val content = DraftManager.readDraft(this, draft.folderName, draft.fileName)
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
                com.subtitleedit.util.OverwritingToast.makeText(
                    this,
                    "导出成功",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this,
                    "导出失败：${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        draftToExport = null
    }

    private fun returnDraftToEditor(draft: DraftItem) {
        val content = DraftManager.readDraft(this, draft.folderName, draft.fileName)
        val resultIntent = Intent()
        resultIntent.putExtra(EXTRA_DRAFT_CONTENT, content)
        resultIntent.putExtra(EXTRA_DRAFT_FILE_NAME, draft.fileName)
        resultIntent.putExtra(EXTRA_DRAFT_FOLDER_NAME, draft.folderName)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun deleteDraft(draft: DraftItem) {
        dialogState = null
        val success = DraftManager.deleteDraft(this, draft.folderName, draft.fileName)
        if (success) {
            com.subtitleedit.util.OverwritingToast.makeText(
                this,
                R.string.draft_deleted,
                Toast.LENGTH_SHORT
            ).show()
            loadDrafts()
        }
    }

    private fun deleteFolder(draft: DraftItem) {
        dialogState = null
        val success = DraftManager.deleteDraftFolder(this, draft.folderName)
        if (success) {
            com.subtitleedit.util.OverwritingToast.makeText(
                this,
                R.string.draft_deleted,
                Toast.LENGTH_SHORT
            ).show()
            if (currentFolder == draft.folderName) {
                currentFolder = ""
            }
            loadDrafts()
        }
    }

    private fun loadDrafts() {
        drafts = if (currentFolder.isEmpty()) {
            DraftManager.getAllDraftFolders(this).map { folder ->
                DraftItem(folder.name, "", folder.name, "", true)
            }
        } else {
            DraftManager.getDraftsInFolder(this, currentFolder).map { file ->
                DraftItem(
                    currentFolder,
                    file.name,
                    file.name,
                    DraftManager.getFormattedDate(file),
                    false
                )
            }
        }
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
    data class Preview(
        val draft: DraftsActivity.DraftItem,
        val content: String
    ) : DraftsDialogState

    data class Actions(val draft: DraftsActivity.DraftItem) : DraftsDialogState
    data class DeleteDraft(val draft: DraftsActivity.DraftItem) : DraftsDialogState
    data class DeleteFolder(val draft: DraftsActivity.DraftItem) : DraftsDialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DraftsPage(
    currentFolder: String,
    drafts: List<DraftsActivity.DraftItem>,
    fromEditor: Boolean,
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
    onLoadDraft: (DraftsActivity.DraftItem) -> Unit,
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
        fromEditor = fromEditor,
        onDismiss = onDismissDialog,
        onCopyDraft = onCopyDraft,
        onExportDraft = onExportDraft,
        onRequestDelete = onRequestDelete,
        onLoadDraft = onLoadDraft,
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
    fromEditor: Boolean,
    onDismiss: () -> Unit,
    onCopyDraft: (DraftsActivity.DraftItem) -> Unit,
    onExportDraft: (DraftsActivity.DraftItem) -> Unit,
    onRequestDelete: (DraftsActivity.DraftItem) -> Unit,
    onLoadDraft: (DraftsActivity.DraftItem) -> Unit,
    onDeleteDraft: (DraftsActivity.DraftItem) -> Unit,
    onDeleteFolder: (DraftsActivity.DraftItem) -> Unit
) {
    when (dialogState) {
        is DraftsDialogState.Preview -> {
            val density = LocalDensity.current
            val previewHorizontalPadding = with(density) { 50.toDp() }
            val previewVerticalPadding = with(density) { 40.toDp() }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(stringResource(R.string.draft_preview_title, dialogState.draft.displayName))
                },
                text = {
                    Box(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(
                                horizontal = previewHorizontalPadding,
                                vertical = previewVerticalPadding
                            )
                    ) {
                        Text(
                            text = dialogState.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            onCopyDraft(dialogState.draft)
                            onDismiss()
                        }) { Text(stringResource(R.string.draft_copy_full_text)) }
                        Spacer(Modifier.weight(1f))
                        if (fromEditor) {
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                        TextButton(onClick = {
                            if (fromEditor) onLoadDraft(dialogState.draft)
                            else onDismiss()
                        }) {
                            Text(
                                if (fromEditor) stringResource(R.string.draft_load)
                                else stringResource(R.string.confirm)
                            )
                        }
                    }
                },
                dismissButton = {}
            )
        }

        is DraftsDialogState.Actions -> {
            AlertDialog(
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
            AlertDialog(
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
            AlertDialog(
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
