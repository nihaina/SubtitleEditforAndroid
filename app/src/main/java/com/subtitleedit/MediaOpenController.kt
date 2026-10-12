package com.subtitleedit

import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.util.FileUtils
import java.io.File

/** Coordinates same-directory subtitle selection when opening media. */
internal class MediaOpenController(
    private val activity: AppCompatActivity,
    private val openEditor: (File, EditorMediaType, File?) -> Unit
) {
    fun open(mediaFile: File, mediaType: EditorMediaType) {
        val subtitles = FileUtils.getPossibleSubtitleFiles(mediaFile)
        if (subtitles.size > 1) showSubtitlePicker(mediaFile, mediaType, subtitles)
        else openEditor(mediaFile, mediaType, subtitles.firstOrNull())
    }

    private fun showSubtitlePicker(
        mediaFile: File,
        mediaType: EditorMediaType,
        subtitleFiles: List<File>
    ) {
        val fileNames = subtitleFiles.map { "${it.name}  (${FileUtils.formatFileSize(it.length())})" }
        val typeLabel = if (mediaType == EditorMediaType.VIDEO) "视频" else "音频"
        ComposeDialogHost.show(activity) { dialog ->
            SubtitleFilePickerDialog(
                mediaLabel = typeLabel,
                mediaFileName = mediaFile.name,
                fileNames = fileNames,
                onDismiss = dialog::dismiss,
                onSelect = { which ->
                    dialog.dismiss()
                    openEditor(mediaFile, mediaType, subtitleFiles[which])
                }
            )
        }
    }
}
