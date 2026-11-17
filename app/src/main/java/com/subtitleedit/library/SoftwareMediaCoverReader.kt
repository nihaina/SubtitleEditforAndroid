package com.subtitleedit.library

import com.subtitleedit.MediaTagAudioRepository
import java.io.File
import java.util.Locale

/** Embedded pictures use the same software readers on every Android version. */
internal object SoftwareMediaCoverReader {
    private const val MAX_COVER_BYTES = 16 * 1024 * 1024
    private val matroska = setOf("mkv", "mka", "mks", "webm")
    private val ogg = setOf("ogg", "oga", "ogv", "opus", "spx")

    fun read(file: File): ByteArray? {
        val extension = file.extension.lowercase(Locale.ROOT)
        // TagLib understands both audio tags and MP4/ASF movie cover tags. Do not decide
        // whether it may read a picture based on the filename's audio/video classification.
        val native = runCatching { MediaTagAudioRepository.pictures(file) }.getOrNull()
            ?.filter { it.data.size in 1..MAX_COVER_BYTES }
            ?.let { pictures -> pictures.firstOrNull { it.pictureType == "Front Cover" } ?: pictures.firstOrNull() }
            ?.data
        if (native != null) return native
        return when {
            extension in matroska -> MatroskaCoverReader.read(file)
            extension in ogg -> OggCoverReader.read(file)
            extension in setOf("wav", "rf64", "bw64", "aac", "mp3", "mp2") -> Id3CoverReader.read(file)
            else -> null
        }
    }
}
