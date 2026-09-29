package com.subtitleedit.editor

import android.app.Activity
import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.subtitleedit.ComposeDialogHost
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TimeUtils
import com.subtitleedit.util.WebVttCuePolicy

/** Owns the small dialogs used to edit one subtitle row. */
internal class EditorSubtitleDialogController(
    context: Context,
    private val ensureListMode: () -> Boolean,
    private val currentFormat: () -> SubtitleParser.SubtitleFormat,
    private val entryAt: (Int) -> SubtitleEntry?,
    private val updateTime: (Int, Boolean, Long) -> Boolean,
    private val updateText: (Int, String) -> Boolean,
    private val updateCue: (Int, String, String) -> Unit,
    private val onUpdated: (Int, String) -> Unit,
    private val showMessage: (String) -> Unit
) {
    private val activity = context as? Activity

    fun showTime(position: Int, start: Boolean) {
        if (!ensureListMode()) return
        val entry = entryAt(position) ?: return
        val current = if (start) entry.startTime else entry.endTime
        val input = mutableStateOf(TimeUtils.formatForInput(current))
        val hostActivity = activity ?: return
        ComposeDialogHost.show(hostActivity) { dialog ->
            AlertDialog(
                onDismissRequest = dialog::dismiss,
                title = { Text(if (start) "编辑开始时间" else "编辑结束时间") },
                text = {
                    OutlinedTextField(
                        value = input.value,
                        onValueChange = { input.value = it },
                        singleLine = true,
                        label = { Text("格式：00:00:01.500") }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog.dismiss()
                            val value = TimeUtils.parseFromInput(input.value)
                            if (value == null) showMessage("时间格式无效")
                            else if (updateTime(position, start, value)) onUpdated(position, "已更新")
                        }
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = dialog::dismiss) { Text("取消") }
                }
            )
        }
    }

    fun showText(position: Int) {
        if (!ensureListMode()) return
        val entry = entryAt(position) ?: return
        val input = mutableStateOf(entry.text)
        val hostActivity = activity ?: return
        ComposeDialogHost.show(hostActivity) { dialog ->
            AlertDialog(
                onDismissRequest = dialog::dismiss,
                title = { Text("编辑字幕文本") },
                text = {
                    OutlinedTextField(
                        value = input.value,
                        onValueChange = { input.value = it },
                        minLines = 3
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog.dismiss()
                            if (updateText(position, input.value)) onUpdated(position, "已更新")
                        }
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = dialog::dismiss) { Text("取消") }
                }
            )
        }
    }

    fun showWebVttCue(position: Int) {
        if (!ensureListMode() || currentFormat() != SubtitleParser.SubtitleFormat.VTT) return
        val entry = entryAt(position) ?: return
        val identifier = mutableStateOf(entry.cueIdentifier)
        val settings = mutableStateOf(entry.cueSettings)
        val hostActivity = activity ?: return
        ComposeDialogHost.show(hostActivity) { dialog ->
            AlertDialog(
                onDismissRequest = dialog::dismiss,
                title = { Text("WebVTT Cue 属性") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        OutlinedTextField(
                            value = identifier.value,
                            onValueChange = { identifier.value = it },
                            singleLine = true,
                            label = { Text("Cue identifier（可选）") }
                        )
                        OutlinedTextField(
                            value = settings.value,
                            onValueChange = { settings.value = it },
                            singleLine = true,
                            label = { Text("Cue settings") },
                            placeholder = { Text("line:90% position:50% align:start") }
                        )
                        Text("line / position / size / align / vertical / region")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val id = identifier.value.trim()
                            val options = settings.value.trim()
                            val error = WebVttCuePolicy.validate(id, options)
                            if (error != null) {
                                showMessage(error)
                                return@TextButton
                            }
                            updateCue(position, id, options)
                            onUpdated(position, "WebVTT Cue 属性已更新")
                            dialog.dismiss()
                        }
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = dialog::dismiss) { Text("取消") }
                }
            )
        }
    }
}
