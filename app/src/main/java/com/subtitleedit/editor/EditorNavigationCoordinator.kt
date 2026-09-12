package com.subtitleedit.editor

import android.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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
        AlertDialog.Builder(activity)
            .setTitle("提示")
            .setMessage("是否保存更改？")
            .setPositiveButton("保存") { _, _ -> saveAndFinish() }
            .setNegativeButton("不保存") { _, _ -> finishWithoutSaving() }
            .setNeutralButton("取消", null)
            .show()
    }
}
