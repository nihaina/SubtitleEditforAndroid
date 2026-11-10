package com.subtitleedit

import android.os.ParcelFileDescriptor
import java.io.File

/** Borrowed descriptors stay open until TagLib has finished; native code owns only its duplicate. */
internal object MediaTagAudioRepository {
    data class Snapshot(
        val metadata: MediaConvertMetadata,
        val properties: Map<String, List<String>>,
        val supportsCover: Boolean,
        val pictureCount: Int
    )

    fun read(file: File): Snapshot? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val values = MediaTagNative.read(descriptor.fd) ?: return@use null
            check(values.size % 2 == 0) { "TagLib returned incomplete metadata" }
            val properties = values.toList().chunked(2).groupBy({ it[0] }, { it[1] })
            Snapshot(MediaTagAudioMetadata.decode(properties), properties,
                MediaTagNative.supportsCover(descriptor.fd), MediaTagNative.pictures(descriptor.fd).size)
        }

    fun pictures(file: File): List<MediaTagPicture> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            MediaTagNative.pictures(descriptor.fd).toList()
        }

    /** The caller owns a private staging copy. A failed native write never touches the source. */
    fun write(file: File, metadata: MediaConvertMetadata): Snapshot {
        val original = requireNotNull(read(file)) { "This audio format is not supported by TagLib" }
        val edits = MediaTagAudioMetadata.updates(metadata, original.properties)
        val image = metadata.coverPath?.takeIf { metadata.cover == MediaConvertCoverMode.CUSTOM }?.let(::File)
        val coverMode = when (metadata.cover) {
            MediaConvertCoverMode.DEFAULT -> 0
            MediaConvertCoverMode.NONE -> 1
            MediaConvertCoverMode.CUSTOM -> 2
        }
        check(coverMode == 0 || original.supportsCover) { "The audio format does not support cover images" }
        check(coverMode != 2 || (image != null && image.isFile && image.length() > 0L)) { "The cover image is not ready" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            check(MediaTagNative.write(descriptor.fd, edits.keys.toTypedArray(), edits.values.toTypedArray(),
                coverMode, image?.readBytes(), "image/jpeg")) { "TagLib could not save the audio tags" }
        }
        val saved = requireNotNull(read(file)) { "TagLib could not read the saved audio tags" }
        check(MediaTagAudioMetadata.verifies(metadata, saved.metadata)) { "Saved audio tags do not match the requested values" }
        when (metadata.cover) {
            MediaConvertCoverMode.DEFAULT -> check(saved.pictureCount == original.pictureCount) { "Existing cover images were lost" }
            MediaConvertCoverMode.NONE -> check(saved.pictureCount == 0) { "Cover images could not be removed" }
            MediaConvertCoverMode.CUSTOM -> check(saved.pictureCount > 0) { "The cover image was not saved" }
        }
        return saved
    }

    fun extension(picture: MediaTagPicture): String = when (picture.mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/tiff" -> "tiff"
        // APEv2 artwork has a filename and image bytes, but no MIME type.
        else -> with(picture.data) {
            fun starts(vararg bytes: Int) = size >= bytes.size && bytes.indices.all { (this[it].toInt() and 255) == bytes[it] }
            when {
                starts(0xff, 0xd8, 0xff) -> "jpg"
                starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "png"
                starts(0x47, 0x49, 0x46, 0x38) -> "gif"
                starts(0x42, 0x4d) -> "bmp"
                size >= 12 && String(this, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(this, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
                else -> "img"
            }
        }
    }
}
