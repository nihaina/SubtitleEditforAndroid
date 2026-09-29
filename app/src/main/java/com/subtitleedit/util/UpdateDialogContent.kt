package com.subtitleedit.util

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun UpdateAvailableDialog(
    update: UpdateChecker.UpdateInfo,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = !update.forceUpdate,
            dismissOnClickOutside = !update.forceUpdate
        ),
        title = { Text("发现新版本 ${update.versionName}") },
        text = { Text(update.releaseNotes) },
        confirmButton = {
            TextButton(onClick = onDownload) { Text("前往下载") }
        },
        dismissButton = if (update.forceUpdate) null else {
            { TextButton(onClick = onDismiss) { Text("稍后") } }
        }
    )
}
