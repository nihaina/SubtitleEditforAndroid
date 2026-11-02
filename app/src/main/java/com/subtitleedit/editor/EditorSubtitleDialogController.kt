package com.subtitleedit.editor

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.subtitleedit.EditorEntryEdit
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.TimeUtils
import com.subtitleedit.util.WebVttCuePolicy
import com.subtitleedit.util.subtitle.WebVttSubtitleFormatHandler

/** Owns the small dialogs used to edit one subtitle row. */
internal class EditorSubtitleDialogController(
    private val context: Context,
    private val ensureListMode: () -> Boolean,
    private val currentFormat: () -> SubtitleParser.SubtitleFormat,
    private val entryAt: (Int) -> SubtitleEntry?,
    private val commitEdit: (EditorEntryEdit) -> Boolean,
    private val onUpdated: (String) -> Unit,
    private val showMessage: (String) -> Unit
) {
    fun showTime(position: Int, start: Boolean) {
        if (!ensureListMode()) return
        val entry = entryAt(position) ?: return
        val current = if (start) entry.startTime else entry.endTime
        val input = EditText(context).apply {
            setText(TimeUtils.formatForInput(current))
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "格式：00:00:01.500"
        }
        AlertDialog.Builder(context)
            .setTitle(if (start) "编辑开始时间" else "编辑结束时间")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val value = TimeUtils.parseFromInput(input.text.toString())
                if (value == null) showMessage("时间格式无效")
                else if (commitEdit(EditorEntryEdit.Time(
                    entry.stableId,
                    startTime = value.takeIf { start },
                    endTime = value.takeUnless { start }
                ))) {
                    onUpdated("已更新")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showText(position: Int) {
        if (!ensureListMode()) return
        val entry = entryAt(position) ?: return
        val input = EditText(context).apply {
            setText(entry.text)
            setLines(3)
        }
        AlertDialog.Builder(context)
            .setTitle("编辑字幕文本")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                if (commitEdit(EditorEntryEdit.Text(entry.stableId, input.text.toString()))) {
                    onUpdated("已更新")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showWebVttCue(position: Int) {
        if (!ensureListMode() || currentFormat() != SubtitleParser.SubtitleFormat.VTT) return
        val entry = entryAt(position) ?: return
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 0, 32, 0)
        }
        val identifier = EditText(context).apply {
            hint = "Cue identifier（可选）"
            setText(entry.cueIdentifier)
            isSingleLine = true
        }
        val settings = EditText(context).apply {
            hint = "例如：line:90% position:50% align:start"
            setText(WebVttSubtitleFormatHandler.getCueSettings(entry))
            isSingleLine = true
        }
        layout.addView(TextView(context).apply { text = "Cue identifier" })
        layout.addView(identifier)
        layout.addView(TextView(context).apply {
            text = "Cue settings（line / position / size / align / vertical / region）"
        })
        layout.addView(settings)
        val dialog = AlertDialog.Builder(context)
            .setTitle("WebVTT Cue 属性")
            .setView(layout)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = identifier.text.toString().trim()
                val options = settings.text.toString().trim()
                val error = WebVttCuePolicy.validate(id, options)
                if (error != null) {
                    showMessage(error)
                    return@setOnClickListener
                }
                if (commitEdit(EditorEntryEdit.Cue(entry.stableId, id, options))) {
                    onUpdated("WebVTT Cue 属性已更新")
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
