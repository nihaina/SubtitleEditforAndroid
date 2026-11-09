package com.subtitleedit

import java.io.File
import java.util.Locale

/** A cover is either an attached image stream or an image attachment, never a movie frame. */
internal data class MediaTagCover(val streamIndex: Long, val attachment: Boolean, val extension: String)

internal object MediaTagCoverExport {
    fun cover(stream: MediaTagStream, codec: String?, fileName: String?): MediaTagCover? {
        if (!stream.attachedPicture && !stream.coverAttachment) return null
        val extension = when (codec?.lowercase(Locale.ROOT)) {
            "mjpeg", "jpeg", "ljpeg" -> "jpg"
            "png" -> "png"
            "webp" -> "webp"
            "bmp" -> "bmp"
            "gif" -> "gif"
            "tiff" -> "tiff"
            "jpeg2000" -> "jp2"
            else -> fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
                ?.takeIf { it in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "tif", "tiff", "jp2", "avif") }
                ?: "img"
        }
        return MediaTagCover(stream.index, stream.type == "attachment", extension)
    }

    /** The image packet/attachment is copied unchanged, with no image resizing or re-encoding. */
    fun arguments(input: String, output: String, cover: MediaTagCover): List<String> {
        require(cover.streamIndex >= 0)
        return if (cover.attachment) listOf("-y", "-hide_banner", "-dump_attachment:${cover.streamIndex}", output,
            "-i", input, "-map", "0:a:0?", "-map", "0:V:0?", "-c", "copy", "-t", "0", "-f", "null", "-")
        else listOf("-y", "-hide_banner", "-i", input, "-map", "0:${cover.streamIndex}",
            "-c:v", "copy", "-frames:v", "1", "-f", "image2", "-update", "1", output)
    }

    /** Reserves an unused name first so exporting into the source folder cannot replace a file. */
    fun copyUnique(source: File, directory: File, suggestedName: String): File {
        require(source.isFile && source.length() > 0) { "The exported cover is empty" }
        require(directory.isDirectory) { "The export directory does not exist" }
        require(File(suggestedName).name == suggestedName && suggestedName.isNotBlank()) { "Invalid cover filename" }
        val extension = suggestedName.substringAfterLast('.', "")
        val base = suggestedName.substringBeforeLast('.', suggestedName)
        var counter = 1
        while (true) {
            val name = if (counter == 1) suggestedName else "$base ($counter)${if (extension.isNotEmpty()) ".$extension" else ""}"
            val output = File(directory, name)
            if (!output.createNewFile()) { counter++; continue }
            try {
                source.inputStream().use { input -> output.outputStream().use { destination -> input.copyTo(destination) } }
                return output
            } catch (error: Throwable) {
                output.delete()
                throw error
            }
        }
    }
}
