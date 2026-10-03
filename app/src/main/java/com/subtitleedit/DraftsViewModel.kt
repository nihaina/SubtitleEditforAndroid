package com.subtitleedit

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.subtitleedit.util.DraftManager

internal data class DraftsUiState(
    val currentFolder: String = "",
    val drafts: List<DraftsActivity.DraftItem> = emptyList(),
    val fromEditor: Boolean = false,
    val dialogState: DraftsDialogState? = null
)

internal sealed interface DraftsEvent {
    /** Launch the "create document" picker for exporting a draft. */
    data class PickExportFile(val fileName: String) : DraftsEvent

    /** Hand the chosen draft back to the editor and close the page. */
    data class ReturnDraft(val content: String, val fileName: String, val folderName: String) : DraftsEvent
}

internal class DraftsViewModel(
    application: Application
) : AppViewModel<DraftsUiState, DraftsEvent>(application, DraftsUiState()) {
    private var initialized = false
    private var draftToExport: DraftsActivity.DraftItem? = null

    private val currentFolder: String get() = currentState.currentFolder

    /** Reads the intent extras once; later calls (after recreation) are ignored. */
    fun initialize(fromEditor: Boolean) {
        if (initialized) return
        initialized = true
        setState { copy(fromEditor = fromEditor) }
        loadDrafts()
    }

    /** Returns true when the back press should leave the page (already at the root). */
    fun navigateUp(): Boolean {
        if (currentFolder.isEmpty()) return true
        goToRoot()
        return false
    }

    fun goToRoot() {
        setState { copy(currentFolder = "", dialogState = null) }
        loadDrafts()
    }

    fun openDraft(draft: DraftsActivity.DraftItem) {
        if (draft.isFolder) {
            setState { copy(currentFolder = draft.folderName) }
            loadDrafts()
        } else {
            val content = DraftManager.readDraft(app, draft.folderName, draft.fileName)
            setState { copy(dialogState = DraftsDialogState.Preview(draft = draft, content = content)) }
        }
    }

    fun showActions(draft: DraftsActivity.DraftItem) {
        if (!draft.isFolder) setState { copy(dialogState = DraftsDialogState.Actions(draft)) }
    }

    fun requestDelete(draft: DraftsActivity.DraftItem) {
        val dialog = if (draft.isFolder) {
            DraftsDialogState.DeleteFolder(draft)
        } else {
            DraftsDialogState.DeleteDraft(draft)
        }
        setState { copy(dialogState = dialog) }
    }

    fun dismissDialog() = setState { copy(dialogState = null) }

    fun copyDraft(draft: DraftsActivity.DraftItem) {
        val content = DraftManager.readDraft(app, draft.folderName, draft.fileName)
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("draft", content))
        toast(R.string.drafts_copied_to_clipboard)
    }

    fun exportDraft(draft: DraftsActivity.DraftItem) {
        draftToExport = draft
        sendEvent(DraftsEvent.PickExportFile(draft.fileName))
    }

    /** Result of the export picker; null when the user cancelled. */
    fun onExportTargetSelected(uri: Uri?) {
        val draft = draftToExport
        draftToExport = null
        if (uri == null || draft == null) return
        try {
            val content = DraftManager.readDraft(app, draft.folderName, draft.fileName)
            app.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray())
            }
            toast(R.string.drafts_export_success)
        } catch (e: Exception) {
            toast(R.string.drafts_export_failed, e.message.toString())
        }
    }

    fun loadDraftIntoEditor(draft: DraftsActivity.DraftItem) {
        val content = DraftManager.readDraft(app, draft.folderName, draft.fileName)
        sendEvent(DraftsEvent.ReturnDraft(content, draft.fileName, draft.folderName))
    }

    fun deleteDraft(draft: DraftsActivity.DraftItem) {
        dismissDialog()
        if (DraftManager.deleteDraft(app, draft.folderName, draft.fileName)) {
            toast(R.string.draft_deleted)
            loadDrafts()
        }
    }

    fun deleteFolder(draft: DraftsActivity.DraftItem) {
        dismissDialog()
        if (DraftManager.deleteDraftFolder(app, draft.folderName)) {
            toast(R.string.draft_deleted)
            if (currentFolder == draft.folderName) setState { copy(currentFolder = "") }
            loadDrafts()
        }
    }

    private fun loadDrafts() {
        val folder = currentFolder
        val drafts = if (folder.isEmpty()) {
            DraftManager.getAllDraftFolders(app).map { entry ->
                DraftsActivity.DraftItem(entry.name, "", entry.name, "", true)
            }
        } else {
            DraftManager.getDraftsInFolder(app, folder).map { file ->
                DraftsActivity.DraftItem(
                    folder,
                    file.name,
                    file.name,
                    DraftManager.getFormattedDate(file),
                    false
                )
            }
        }
        setState { copy(drafts = drafts) }
    }
}
