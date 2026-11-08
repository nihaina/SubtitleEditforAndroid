package com.subtitleedit

import java.util.Locale

internal enum class MediaConvertCoverMode { DEFAULT, NONE, CUSTOM }
internal enum class MediaConvertMetadataField { TITLE, AUTHOR, COMMENT, GENRE, ARTIST, ALBUM, YEAR }

internal data class MediaConvertMetadata(
    val cover: MediaConvertCoverMode = MediaConvertCoverMode.DEFAULT,
    val title: String = "",
    val author: String = "",
    val comment: String = "",
    val genre: String = "",
    val artist: String = "",
    val album: String = "",
    val year: String = "",
    val editedFields: Set<MediaConvertMetadataField> = emptySet(),
    /** Task-owned JPEG path, prepared from the picked image URI by the ViewModel. */
    val coverPath: String? = null
) {
    fun value(field: MediaConvertMetadataField): String = when (field) {
        MediaConvertMetadataField.TITLE -> title
        MediaConvertMetadataField.AUTHOR -> author
        MediaConvertMetadataField.COMMENT -> comment
        MediaConvertMetadataField.GENRE -> genre
        MediaConvertMetadataField.ARTIST -> artist
        MediaConvertMetadataField.ALBUM -> album
        MediaConvertMetadataField.YEAR -> year
    }

    fun withValue(field: MediaConvertMetadataField, value: String): MediaConvertMetadata {
        val next = when (field) {
            MediaConvertMetadataField.TITLE -> copy(title = value)
            MediaConvertMetadataField.AUTHOR -> copy(author = value)
            MediaConvertMetadataField.COMMENT -> copy(comment = value)
            MediaConvertMetadataField.GENRE -> copy(genre = value)
            MediaConvertMetadataField.ARTIST -> copy(artist = value)
            MediaConvertMetadataField.ALBUM -> copy(album = value)
            MediaConvertMetadataField.YEAR -> copy(year = value)
        }
        return next.copy(editedFields = editedFields + field)
    }

    /** Empty values intentionally clear a field; untouched fields retain each source file's tags. */
    fun arguments(isAudioOutput: Boolean = false): List<String> = buildList {
        val fields = if (isAudioOutput) audioFields else videoFields
        for (field in fields.filter { it in editedFields }) {
            val keys = when (field) {
                MediaConvertMetadataField.TITLE -> listOf("title")
                MediaConvertMetadataField.AUTHOR -> listOf("author", "artist")
                MediaConvertMetadataField.COMMENT -> listOf("comment", "description")
                MediaConvertMetadataField.GENRE -> listOf("genre")
                MediaConvertMetadataField.ARTIST -> listOf("artist")
                MediaConvertMetadataField.ALBUM -> listOf("album")
                MediaConvertMetadataField.YEAR -> listOf("date", "year")
            }
            keys.forEach { key -> addAll(listOf("-metadata", "$key=${value(field)}")) }
        }
    }

    companion object {
        val videoFields = listOf(MediaConvertMetadataField.TITLE, MediaConvertMetadataField.AUTHOR, MediaConvertMetadataField.COMMENT)
        val audioFields = listOf(MediaConvertMetadataField.GENRE, MediaConvertMetadataField.TITLE,
            MediaConvertMetadataField.ARTIST, MediaConvertMetadataField.ALBUM, MediaConvertMetadataField.YEAR)

        fun fromTags(value: (String) -> String?): MediaConvertMetadata {
            fun tag(key: String): String? = listOf(key, key.uppercase(Locale.ROOT))
                .firstNotNullOfOrNull { candidate -> value(candidate)?.trim()?.takeUnless { it.isEmpty() || it.equals("null", true) } }
            return MediaConvertMetadata(
                title = tag("title").orEmpty(),
                author = tag("author") ?: tag("artist") ?: "",
                comment = tag("comment") ?: tag("description") ?: "",
                genre = tag("genre").orEmpty(),
                artist = tag("artist") ?: tag("author") ?: "",
                album = tag("album").orEmpty(),
                year = tag("date") ?: tag("year") ?: ""
            )
        }
    }
}

internal object MediaConvertMetadataSupport {
    /** These muxers accept JPEG artwork. Other containers still receive supported text tags. */
    fun supportsCover(format: MediaConvertFormatInfo): Boolean = format.formatName in
        setOf("mp3", "mp4", "ipod", "mov", "flac", "matroska")
}
