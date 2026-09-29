package com.subtitleedit.editor

import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.subtitleedit.ComposeDialogHost

/** Centralizes confirmation dialogs used by the editor's destructive or replacing actions. */
internal class EditorConfirmationDialogController(
    private val activity: ComponentActivity,
    private val hasUnsavedChanges: () -> Boolean
) {
    fun show(
        title: String,
        message: String,
        positiveText: String = "确定",
        negativeText: String = "取消",
        onConfirm: () -> Unit
    ) {
        ComposeDialogHost.show(activity) { dialog ->
            AlertDialog(
                onDismissRequest = dialog::dismiss,
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog.dismiss()
                            onConfirm()
                        }
                    ) { Text(positiveText) }
                },
                dismissButton = {
                    TextButton(onClick = dialog::dismiss) { Text(negativeText) }
                }
            )
        }
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
