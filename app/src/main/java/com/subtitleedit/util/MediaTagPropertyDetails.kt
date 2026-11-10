package com.subtitleedit.util

import android.os.ParcelFileDescriptor
import com.subtitleedit.MediaTagAudioMetadata
import com.subtitleedit.MediaTagNative
import java.io.File

/** The properties dialog uses the same file-tag parser as the audio tag editor. */
internal object MediaTagPropertyDetails {
    fun read(file: File): List<PropertyDetail>? = ParcelFileDescriptor.open(file,
        ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        MediaTagNative.read(descriptor.fd)?.let(::fromProperties)
    }

    internal fun fromProperties(pairs: Array<String>): List<PropertyDetail> {
        require(pairs.size % 2 == 0)
        val tags = pairs.toList().chunked(2).groupBy({ it[0] }, { it[1] })
        val metadata = MediaTagAudioMetadata.decode(tags)
        return listOf(
            "歌曲名" to metadata.title, "艺术家" to metadata.artist, "专辑" to metadata.album,
            "专辑艺术家" to metadata.albumArtist, "作曲" to metadata.composer,
            "流派" to metadata.genre, "年份" to metadata.year, "音轨号" to metadata.trackNumber,
            "碟号" to metadata.discNumber, "注释" to metadata.comment,
            "作词" to metadata.lyricist, "版权" to metadata.copyright, "语言" to metadata.language
        ).mapNotNull { (label, text) -> text.takeIf { it.isNotBlank() }?.let { PropertyDetail(label, it) } }
    }
}
