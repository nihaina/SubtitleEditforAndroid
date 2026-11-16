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
    // The image shortcut needs an artwork-capable target. Media-only selections can still edit text tags.
    private val coverMediaExtensions = setOf(
        "mp3", "flac", "aac", "ogg", "oga", "opus", "m4a", "m4b", "wma", "ape", "wv", "aif", "aiff",
        "mp4", "m4v", "mov", "mkv"
    )

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
        if (mediaFiles.isEmpty()) return null
        if (coverFile != null && mediaFiles.any { it.extension.lowercase(Locale.ROOT) !in coverMediaExtensions }) return null
        return MediaTagFileSelection(mediaFiles, coverFile)
    }
}
