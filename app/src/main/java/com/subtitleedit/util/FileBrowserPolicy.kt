package com.subtitleedit.util

import java.io.File

internal object FileBrowserPolicy {
    fun shouldDisplayFile(
        file: File,
        includeAllFileTypes: Boolean,
        videoExtensions: Set<String>,
        isRecognizedArchive: (File) -> Boolean
    ): Boolean {
        if (!file.isFile) return false
        return includeAllFileTypes ||
            FileUtils.isSubtitleFile(file) ||
            FileUtils.isAudioFile(file) ||
            file.extension.lowercase() in videoExtensions ||
            isRecognizedArchive(file)
    }
}
