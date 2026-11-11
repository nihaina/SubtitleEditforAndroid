package com.subtitleedit

import java.io.File

internal data class MediaConvertFileSelection(
    val mediaFiles: List<File>,
    val subtitleFiles: List<File>
)

/** Separates directory selections into processing inputs without treating subtitles as media. */
internal object MediaConvertFileSelectionPolicy {
    fun fromFiles(files: List<File>, isMediaFile: (File) -> Boolean): MediaConvertFileSelection? {
        val mediaFiles = mutableListOf<File>()
        val subtitleFiles = mutableListOf<File>()
        for (file in files) {
            if (!file.isFile) return null
            when {
                isMediaFile(file) -> mediaFiles += file
                MediaConvertSubtitles.extension(file.name) in MediaConvertSubtitles.importExtensions -> subtitleFiles += file
                else -> return null
            }
        }
        if (mediaFiles.isEmpty() || (subtitleFiles.isNotEmpty() && mediaFiles.size != 1)) return null
        return MediaConvertFileSelection(mediaFiles, subtitleFiles)
    }
}
