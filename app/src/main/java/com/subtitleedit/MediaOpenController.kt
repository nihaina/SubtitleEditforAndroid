package com.subtitleedit

import android.content.Intent
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.util.FileUtils
import java.io.File

/** Coordinates media open mode and same-directory subtitle selection dialogs. */
internal class MediaOpenController(
    private val activity: AppCompatActivity,
    private val openEditor: (File, EditorMediaType, File?, Boolean) -> Unit
) {
    fun open(mediaFile: File, mediaType: EditorMediaType, audioOnlyFromVideo: Boolean = false) {
        val subtitles = FileUtils.getPossibleSubtitleFiles(mediaFile)
        if (subtitles.size > 1) showSubtitlePicker(mediaFile, mediaType, subtitles, audioOnlyFromVideo)
        else openEditor(mediaFile, mediaType, subtitles.firstOrNull(), audioOnlyFromVideo)
    }

    fun showVideoModePicker(videoFile: File) {
        AlertDialog.Builder(activity)
            .setTitle("打开视频文件")
            .setItems(arrayOf("加载视频", "仅加载音频")) { _, which ->
                if (which == 0) open(videoFile, EditorMediaType.VIDEO)
                else open(videoFile, EditorMediaType.AUDIO, audioOnlyFromVideo = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSubtitlePicker(
        mediaFile: File,
        mediaType: EditorMediaType,
        subtitleFiles: List<File>,
        audioOnlyFromVideo: Boolean
    ) {
        val fileNames = subtitleFiles.map { "${it.name}  (${FileUtils.formatFileSize(it.length())})" }.toTypedArray()
        val customTitle = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 0)
            addView(TextView(context).apply {
                text = "选择字幕文件"
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
                setPadding(4, 0, 4, 6)
            })
            addView(TextView(context).apply {
                val typeLabel = if (mediaType == EditorMediaType.VIDEO) "视频" else "音频"
                text = "$typeLabel「${mediaFile.name}」同目录下存在多个字幕文件，请选择要打开的文件："
                textSize = 14f
                setPadding(4, 0, 4, 0)
            })
        }
        AlertDialog.Builder(activity)
            .setCustomTitle(customTitle)
            .setItems(fileNames) { _, which ->
                openEditor(mediaFile, mediaType, subtitleFiles[which], audioOnlyFromVideo)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
