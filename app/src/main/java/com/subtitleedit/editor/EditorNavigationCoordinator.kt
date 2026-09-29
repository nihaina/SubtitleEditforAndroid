package com.subtitleedit.editor

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.subtitleedit.ComposeDialogHost
import com.subtitleedit.EditorDocumentState
import com.subtitleedit.adapter.SubtitleAdapter

/** Owns editor back navigation across fullscreen, selection and unsaved-document states. */
internal class EditorNavigationCoordinator(
    private val activity: AppCompatActivity,
    private val subtitleAdapter: SubtitleAdapter,
    private val isVideoFullscreen: () -> Boolean,
    private val exitVideoFullscreen: () -> Unit,
    private val cancelSelection: () -> Unit,
    private val documentState: EditorDocumentState,
    private val saveAndFinish: () -> Unit,
    private val finishWithoutSaving: () -> Unit
) {
    fun bind() {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBackPressed()
        })
    }

    fun onNavigateUp() {
        if (subtitleAdapter.getSelectedCount() > 0) cancelSelection()
        else onBackPressed()
    }

    fun onBackPressed() {
        when (EditorNavigationPolicy.decide(
            isVideoFullscreen = isVideoFullscreen(),
            selectedCount = subtitleAdapter.getSelectedCount(),
            hasUnsavedChanges = documentState.hasUnsavedChanges
        )) {
            EditorNavigationPolicy.Decision.EXIT_FULLSCREEN -> exitVideoFullscreen()
            EditorNavigationPolicy.Decision.CANCEL_SELECTION -> cancelSelection()
            EditorNavigationPolicy.Decision.CONFIRM_UNSAVED -> showUnsavedChangesDialog()
            EditorNavigationPolicy.Decision.FINISH -> finishWithoutSaving()
        }
    }

    private fun showUnsavedChangesDialog() {
        ComposeDialogHost.show(activity) { dialog ->
            AlertDialog(
                onDismissRequest = dialog::dismiss,
                title = { Text("提示") },
                text = { Text("是否保存更改？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog.dismiss()
                            saveAndFinish()
                        }
                    ) { Text("保存") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = dialog::dismiss) { Text("取消") }
                        TextButton(
                            onClick = {
                                dialog.dismiss()
                                finishWithoutSaving()
                            }
                        ) { Text("不保存") }
                    }
                }
            )
        }
    }
}
