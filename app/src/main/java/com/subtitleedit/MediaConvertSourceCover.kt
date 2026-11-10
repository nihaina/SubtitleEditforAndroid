package com.subtitleedit

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale

/** Read the source picture once so a sparse source track cannot hold back subtitle input. */
internal object MediaConvertSourceCover {
    fun prepare(input: String, cacheDirectory: File, source: MediaConvertSource,
                execute: (Array<String>) -> Boolean = { arguments ->
                    ReturnCode.isSuccess(FFmpegKit.executeWithArguments(arguments).getReturnCode())
                }): MediaConvertSource {
        val index = source.attachedPictureStreamIndex ?: return source
        if (source.attachedPicturePath != null) return source
        val cover = requireNotNull(MediaTagCoverExport.cover(
            MediaTagStream(index, "video", attachedPicture = true), source.attachedPictureCodecName, null))
        val image = File.createTempFile("source_cover_", ".${cover.extension}", cacheDirectory)
        try {
            check(execute(MediaTagCoverExport.arguments(input, image.absolutePath, cover).toTypedArray()) &&
                image.isFile && image.length() > 0L) { "Cannot read the source cover image" }
            return source.copy(attachedPicturePath = image.absolutePath)
        } catch (error: Throwable) {
            image.delete()
            throw error
        }
    }

    fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "gif" -> "image/gif"
        "tif", "tiff" -> "image/tiff"
        "jp2" -> "image/jp2"
        "avif" -> "image/avif"
        else -> "application/octet-stream"
    }
}
