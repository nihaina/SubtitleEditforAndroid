package com.subtitleedit

import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.util.ArchiveActionUiPolicy
import com.subtitleedit.util.ArchiveActionUiPolicy.ArchiveAction
import java.io.File

/** Owns archive action selection and simple archive-operation progress dialogs. */
internal class ArchiveActionDialogController(
    private val activityProvider: () -> AppCompatActivity?
) {
    fun showActions(
        archive: File,
        onAction: (ArchiveAction) -> Unit,
        onExtractToDestination: () -> Unit
    ) {
        val activity = activityProvider() ?: return
        ComposeDialogHost.show(activity) { dialog ->
            ArchiveActionsDialog(
                archiveName = archive.name,
                actionLabels = ArchiveActionUiPolicy.actionLabels.toList(),
                onDismiss = dialog::dismiss,
                onAction = { which ->
                    dialog.dismiss()
                    when (which) {
                        0 -> onAction(ArchiveAction.PREVIEW)
                        1 -> onAction(ArchiveAction.EXTRACT_CURRENT)
                        2 -> onExtractToDestination()
                        3 -> onAction(ArchiveAction.TEST)
                    }
                }
            )
        }
    }

    fun showBlockingProgress(title: String, message: String): ComposeDialogHandle {
        val activity = activityProvider()
            ?: error("Archive action UI is not attached to an Activity")
        return ComposeDialogHost.show(activity) { _ ->
            // The archive preview/test operation used a plain, non-cancelable
            // AlertDialog in the XML implementation. Keep that presentation
            // separate from the detailed progress dialog used by extraction
            // and copy operations.
            ArchiveBlockingProgressDialog(title, message)
        }
    }
}
