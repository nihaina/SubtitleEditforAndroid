package com.subtitleedit

import java.util.Locale

internal enum class MediaConvertCoverMode { DEFAULT, NONE, CUSTOM }
internal enum class MediaConvertMetadataField {
    TITLE, AUTHOR, COMMENT, GENRE, ARTIST, ALBUM, YEAR,
    ALBUM_ARTIST, COMPOSER, TRACK_NUMBER, DISC_NUMBER, LYRICIST, COPYRIGHT, LANGUAGE
}

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
    val coverPath: String? = null,
    val albumArtist: String = "",
    val composer: String = "",
    val trackNumber: String = "",
    val discNumber: String = "",
    val lyricist: String = "",
    val copyright: String = "",
    val language: String = ""
) {
    fun value(field: MediaConvertMetadataField): String = when (field) {
        MediaConvertMetadataField.TITLE -> title
        MediaConvertMetadataField.AUTHOR -> author
        MediaConvertMetadataField.COMMENT -> comment
        MediaConvertMetadataField.GENRE -> genre
        MediaConvertMetadataField.ARTIST -> artist
        MediaConvertMetadataField.ALBUM -> album
        MediaConvertMetadataField.YEAR -> year
        MediaConvertMetadataField.ALBUM_ARTIST -> albumArtist
        MediaConvertMetadataField.COMPOSER -> composer
        MediaConvertMetadataField.TRACK_NUMBER -> trackNumber
        MediaConvertMetadataField.DISC_NUMBER -> discNumber
        MediaConvertMetadataField.LYRICIST -> lyricist
        MediaConvertMetadataField.COPYRIGHT -> copyright
        MediaConvertMetadataField.LANGUAGE -> language
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
            MediaConvertMetadataField.ALBUM_ARTIST -> copy(albumArtist = value)
            MediaConvertMetadataField.COMPOSER -> copy(composer = value)
            MediaConvertMetadataField.TRACK_NUMBER -> copy(trackNumber = value)
            MediaConvertMetadataField.DISC_NUMBER -> copy(discNumber = value)
            MediaConvertMetadataField.LYRICIST -> copy(lyricist = value)
            MediaConvertMetadataField.COPYRIGHT -> copy(copyright = value)
            MediaConvertMetadataField.LANGUAGE -> copy(language = value)
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
                MediaConvertMetadataField.ALBUM_ARTIST -> listOf("album_artist")
                MediaConvertMetadataField.COMPOSER -> listOf("composer")
                MediaConvertMetadataField.TRACK_NUMBER -> listOf("track")
                MediaConvertMetadataField.DISC_NUMBER -> listOf("disc")
                MediaConvertMetadataField.LYRICIST -> listOf("lyricist")
                MediaConvertMetadataField.COPYRIGHT -> listOf("copyright")
                MediaConvertMetadataField.LANGUAGE -> listOf("language")
            }
            keys.forEach { key -> addAll(listOf("-metadata", "$key=${value(field)}")) }
        }
    }

    companion object {
        val videoFields = listOf(MediaConvertMetadataField.TITLE, MediaConvertMetadataField.AUTHOR, MediaConvertMetadataField.COMMENT)
        val audioFields = listOf(MediaConvertMetadataField.GENRE, MediaConvertMetadataField.TITLE,
            MediaConvertMetadataField.ARTIST, MediaConvertMetadataField.ALBUM, MediaConvertMetadataField.YEAR,
            MediaConvertMetadataField.ALBUM_ARTIST, MediaConvertMetadataField.COMPOSER,
            MediaConvertMetadataField.TRACK_NUMBER, MediaConvertMetadataField.DISC_NUMBER, MediaConvertMetadataField.COMMENT,
            MediaConvertMetadataField.LYRICIST, MediaConvertMetadataField.COPYRIGHT, MediaConvertMetadataField.LANGUAGE)

        fun fromTags(value: (String) -> String?): MediaConvertMetadata {
            fun tag(vararg keys: String): String? = keys.flatMap { listOf(it, it.uppercase(Locale.ROOT)) }
                .firstNotNullOfOrNull { candidate -> value(candidate)?.trim()?.takeUnless { it.isEmpty() || it.equals("null", true) } }
            return MediaConvertMetadata(
                title = tag("title").orEmpty(),
                author = tag("author") ?: tag("artist") ?: "",
                comment = tag("comment") ?: tag("description") ?: "",
                genre = tag("genre").orEmpty(),
                artist = tag("artist") ?: tag("author") ?: "",
                album = tag("album").orEmpty(),
                year = tag("date", "year").orEmpty(),
                albumArtist = tag("album_artist", "albumartist", "album artist", "TPE2").orEmpty(),
                composer = tag("composer", "TCOM").orEmpty(),
                trackNumber = tag("track", "tracknumber", "TRCK").orEmpty(),
                discNumber = tag("disc", "discnumber", "TPOS").orEmpty(),
                lyricist = tag("lyricist", "writer", "TEXT").orEmpty(),
                copyright = tag("copyright", "TCOP").orEmpty(),
                language = tag("language", "TLAN").orEmpty()
            )
        }
    }
}

internal object MediaConvertMetadataSupport {
    /** These muxers accept JPEG artwork. Other containers still receive supported text tags. */
    fun supportsCover(format: MediaConvertFormatInfo): Boolean = format.formatName in
        setOf("mp3", "mp4", "ipod", "mov", "flac", "matroska")
}
