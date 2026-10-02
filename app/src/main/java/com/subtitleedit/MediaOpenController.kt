package com.subtitleedit

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
        ComposeDialogHost.show(activity) { dialog ->
            VideoModeDialog(
                onDismiss = dialog::dismiss,
                onOpenVideo = {
                    dialog.dismiss()
                    open(videoFile, EditorMediaType.VIDEO)
                },
                onOpenAudioOnly = {
                    dialog.dismiss()
                    open(videoFile, EditorMediaType.AUDIO, audioOnlyFromVideo = true)
                }
            )
        }
    }

    private fun showSubtitlePicker(
        mediaFile: File,
        mediaType: EditorMediaType,
        subtitleFiles: List<File>,
        audioOnlyFromVideo: Boolean
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
                    openEditor(mediaFile, mediaType, subtitleFiles[which], audioOnlyFromVideo)
                }
            )
        }
    }
}
