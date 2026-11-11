package com.subtitleedit

import java.io.File

/** A directory shortcut keeps its full selection until the destination page is chosen. */
internal data class MediaProcessingSelection(
    val files: List<File>,
    val conversion: MediaConvertFileSelection?,
    val muxFiles: List<File>?
) {
    fun supports(page: MediaProcessingPage): Boolean = when (page) {
        MediaProcessingPage.CONVERT -> conversion != null
        MediaProcessingPage.MUX -> muxFiles != null
    }
}

internal object MediaProcessingSelectionPolicy {
    fun fromFiles(files: List<File>, isMediaFile: (File) -> Boolean): MediaProcessingSelection? {
        val conversion = MediaConvertFileSelectionPolicy.fromFiles(files, isMediaFile)
        val muxFiles = files.takeIf { it.isNotEmpty() && it.all { file -> file.isFile && isMediaFile(file) } }
        if (conversion == null && muxFiles == null) return null
        return MediaProcessingSelection(files.toList(), conversion, muxFiles?.toList())
    }
}
