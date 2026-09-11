package com.subtitleedit.editor

import android.app.AlertDialog
import android.content.Context

/** Centralizes confirmation dialogs used by the editor's destructive or replacing actions. */
internal class EditorConfirmationDialogController(
    private val context: Context,
    private val hasUnsavedChanges: () -> Boolean
) {
    fun show(title: String, message: String, positiveText: String = "确定", negativeText: String = "取消", onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onConfirm() }
            .setNegativeButton(negativeText, null)
            .show()
    }

    fun runAfterUnsavedChangesConfirmed(message: String, action: () -> Unit) {
        if (!hasUnsavedChanges()) action()
        else show("提示", message, onConfirm = action)
    }

    fun showReplaceAll(count: Int, onConfirm: () -> Unit) {
        show("确认替换", "确定要全部替换吗？共找到 $count 处匹配项。", onConfirm = onConfirm)
    }

    fun showDelete(message: String, onConfirm: () -> Unit) {
        show("删除", message, onConfirm = onConfirm)
    }
}
