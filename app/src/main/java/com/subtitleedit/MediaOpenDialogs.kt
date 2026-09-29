package com.subtitleedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed

@Composable
internal fun VideoModeDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onOpenVideo: () -> Unit,
    onOpenAudioOnly: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开视频文件") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(fileName, style = MaterialTheme.typography.bodyMedium)
                ListItem(
                    headlineContent = { Text("加载视频") },
                    modifier = Modifier.clickable(onClick = onOpenVideo)
                )
                ListItem(
                    headlineContent = { Text("仅加载音频") },
                    modifier = Modifier.clickable(onClick = onOpenAudioOnly)
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
internal fun SubtitleFilePickerDialog(
    mediaLabel: String,
    mediaFileName: String,
    fileNames: List<String>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择字幕文件") },
        text = {
            Column {
                Text(
                    "$mediaLabel「$mediaFileName」同目录下存在多个字幕文件，请选择要打开的文件：",
                    style = MaterialTheme.typography.bodyMedium
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                ) {
                    itemsIndexed(fileNames) { index, name ->
                        ListItem(
                            headlineContent = { Text(name) },
                            modifier = Modifier.clickable { onSelect(index) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
