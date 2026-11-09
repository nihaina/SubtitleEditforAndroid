package com.subtitleedit

import java.io.File
import java.util.Locale

internal data class MediaTagFileSelection(
    val mediaFiles: List<File>,
    val coverFile: File?
)

/** A tag-editing selection may contain one cover image alongside its media files. */
internal object MediaTagFileSelectionPolicy {
    val coverExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

    fun fromFiles(files: List<File>, isMediaFile: (File) -> Boolean): MediaTagFileSelection? {
        val mediaFiles = mutableListOf<File>()
        var coverFile: File? = null
        for (file in files) {
            if (!file.isFile) return null
            when {
                isMediaFile(file) -> mediaFiles += file
                file.extension.lowercase(Locale.ROOT) in coverExtensions -> {
                    if (coverFile != null) return null
                    coverFile = file
                }
                else -> return null
            }
        }
        return if (mediaFiles.isEmpty()) null else MediaTagFileSelection(mediaFiles, coverFile)
    }
}
