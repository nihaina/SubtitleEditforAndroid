package com.subtitleedit

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.subtitleedit.model.ArchiveConflictDialogModel
import com.subtitleedit.model.ArchiveConflictFileMetadata
import com.subtitleedit.util.ArchiveManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Owns the destination-conflict dialog shared by extraction and file copying. */
internal class ArchiveConflictDialogController(
    private val activityProvider: () -> AppCompatActivity?
) {
    fun show(
        conflict: ArchiveManager.DestinationConflict,
        onPolicySelected: (ArchiveManager.ConflictPolicy, Boolean) -> Unit,
        onCancelled: () -> Unit = {}
    ): ComposeDialogHandle? {
        val activity = activityProvider() ?: run {
            onCancelled()
            return null
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
        return ComposeDialogHost.show(activity) { dialog ->
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

    suspend fun awaitResolution(conflict: ArchiveManager.DestinationConflict): ArchiveManager.ConflictResolution =
        withContext(Dispatchers.Main.immediate) {
            val activity = activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed }
                ?: throw CancellationException("文件操作页面已关闭")
            suspendCancellableCoroutine { continuation ->
                if (!continuation.isActive) return@suspendCancellableCoroutine
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY) continuation.cancel()
                }
                activity.lifecycle.addObserver(observer)
                val dialog = show(
                    conflict,
                    onPolicySelected = { policy, applyToAll ->
                        if (continuation.isActive) continuation.resume(ArchiveManager.ConflictResolution(policy, applyToAll))
                    },
                    onCancelled = { continuation.cancel() }
                )
                fun cleanup() {
                    activity.lifecycle.removeObserver(observer)
                    dialog?.dismiss()
                }
                continuation.invokeOnCancellation { activity.runOnUiThread { cleanup() } }
                dialog?.addOnDismissListener {
                    activity.lifecycle.removeObserver(observer)
                }
                if (!continuation.isActive) cleanup()
            }
        }
}
