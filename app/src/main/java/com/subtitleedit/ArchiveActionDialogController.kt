package com.subtitleedit

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
        AlertDialog.Builder(activity)
            .setTitle(archive.name)
            .setItems(ArchiveActionUiPolicy.actionLabels) { _, which ->
                when (which) {
                    0 -> onAction(ArchiveAction.PREVIEW)
                    1 -> onAction(ArchiveAction.EXTRACT_CURRENT)
                    2 -> onExtractToDestination()
                    3 -> onAction(ArchiveAction.TEST)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showBlockingProgress(title: String, message: String): AlertDialog =
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .create()
            .also(AlertDialog::show)
}
