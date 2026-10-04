package com.subtitleedit

import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.model.ArchiveConflictDialogModel
import com.subtitleedit.model.ArchiveConflictFileMetadata
import com.subtitleedit.util.ArchiveManager

/** Owns the destination-conflict decision dialog used during extraction. */
internal class ArchiveConflictDialogController(
    private val activityProvider: () -> AppCompatActivity?
) {
    fun show(
        conflict: ArchiveManager.DestinationConflict,
        onPolicySelected: (ArchiveManager.ConflictPolicy, Boolean) -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        val activity = activityProvider() ?: run {
            onCancelled()
            return
        }
        val model = ArchiveConflictDialogModel(
            entryName = conflict.entryName,
            source = ArchiveConflictFileMetadata(
                sizeBytes = conflict.sourceSize.takeIf { it >= 0L },
                modifiedAtMillis = conflict.sourceModifiedTimeMillis.takeIf { it > 0L }
            ),
            existing = ArchiveConflictFileMetadata(
                sizeBytes = conflict.existingSize.takeIf { it >= 0L },
                modifiedAtMillis = conflict.existingModifiedTimeMillis.takeIf { it > 0L }
            )
        )
        ComposeDialogHost.show(activity) { dialog ->
            ArchiveConflictDialog(
                model = model,
                archiveInternal = conflict.archiveInternal,
                onDismiss = {
                    dialog.dismiss()
                    onCancelled()
                },
                onPolicySelected = { action, applyToAll ->
                    dialog.dismiss()
                    val policy = when (action) {
                        ArchiveConflictPolicyAction.RENAME -> ArchiveManager.ConflictPolicy.RENAME
                        ArchiveConflictPolicyAction.SKIP -> ArchiveManager.ConflictPolicy.SKIP
                        ArchiveConflictPolicyAction.OVERWRITE -> ArchiveManager.ConflictPolicy.OVERWRITE
                    }
                    onPolicySelected(policy, applyToAll)
                }
            )
        }
    }
}
