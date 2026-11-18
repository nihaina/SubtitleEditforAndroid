package com.subtitleedit.util

import android.os.ParcelFileDescriptor
import com.subtitleedit.MediaTagAudioMetadata
import com.subtitleedit.MediaTagNative
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaConvertMetadataField
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
        return fromMetadata(metadata)
    }

    internal fun fromMetadata(metadata: MediaConvertMetadata, isVideo: Boolean = false): List<PropertyDetail> {
        return MediaConvertMetadata.audioFields.mapNotNull { field ->
            val isVideoAuthor = isVideo && field == MediaConvertMetadataField.ARTIST
            val displayField = if (isVideoAuthor) MediaConvertMetadataField.AUTHOR else field
            val value = if (isVideoAuthor) metadata.author.ifBlank { metadata.artist } else metadata.value(field)
            value.takeIf { it.isNotBlank() }?.let { PropertyDetail(displayField.labelResource, it) }
        }
    }
}
