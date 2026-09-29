package com.subtitleedit

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import com.subtitleedit.util.ArchiveActionUiPolicy
import com.subtitleedit.util.ArchiveActionUiPolicy.ArchiveAction
import java.io.File

/** Owns archive action selection and simple archive-operation progress dialogs. */
internal class ArchiveActionDialogController(
    private val activity: AppCompatActivity
) {
    fun showActions(
        archive: File,
        onAction: (ArchiveAction) -> Unit,
        onExtractToDestination: () -> Unit
    ) {
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
        val state = mutableStateOf(
            ArchiveProgressDialogState(title = title, message = message)
        )
        return ComposeDialogHost.show(activity) { dialog ->
            ArchiveProgressDialog(state.value, onCancel = dialog::dismiss)
        }
    }
}
