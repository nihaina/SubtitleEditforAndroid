package com.subtitleedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaConvertMetadataTest {
    @Test
    fun readsTitleAuthorAndCommentFromCommonContainerTagNames() {
        val metadata = MediaConvertMetadata.fromTags { key ->
            mapOf("title" to "Film", "artist" to "Director", "description" to "A note")[key]
        }

        assertEquals("Film", metadata.title)
        assertEquals("Director", metadata.author)
        assertEquals("A note", metadata.comment)
        assertEquals(MediaConvertCoverMode.DEFAULT, metadata.cover)
    }

    @Test
    fun prefersExplicitAuthorAndCommentTags() {
        val metadata = MediaConvertMetadata.fromTags { key ->
            mapOf("author" to "Author", "artist" to "Artist", "comment" to "Comment", "description" to "Description")[key]
        }

        assertEquals("Author", metadata.author)
        assertEquals("Comment", metadata.comment)
    }

    @Test
    fun audioFieldsReadFromTagsWithoutBecomingOverrides() {
        val metadata = MediaConvertMetadata.fromTags { key ->
            mapOf("genre" to "Rock", "artist" to "Singer", "album" to "Record", "date" to "2025")[key]
        }
        assertEquals("Rock", metadata.genre)
        assertEquals("Singer", metadata.artist)
        assertEquals("Record", metadata.album)
        assertEquals("2025", metadata.year)
        assertTrue(metadata.arguments(true).isEmpty())
        assertTrue(metadata.editedFields.isEmpty())
    }

    @Test
    fun uppercaseMatroskaTagsAndBlankAliasesAreRecognized() {
        val metadata = MediaConvertMetadata.fromTags { key ->
            mapOf("TITLE" to "Film", "author" to "", "ARTIST" to "Singer", "year" to "2024")[key]
        }
        assertEquals("Film", metadata.title)
        assertEquals("Singer", metadata.author)
        assertEquals("2024", metadata.year)
    }

    @Test
    fun onlyEditedFieldsAreAppliedAndAnExplicitEmptyStringClearsAValue() {
        val metadata = MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, "Batch title")
            .withValue(MediaConvertMetadataField.ARTIST, "")
        val arguments = metadata.arguments(true)
        assertEquals(listOf("-metadata", "title=Batch title", "-metadata", "artist="), arguments)
        assertFalse(arguments.any { it.startsWith("album=") })
        assertEquals("Batch title", metadata.value(MediaConvertMetadataField.TITLE))
    }

    @Test
    fun audioAndVideoPagesWriteTheirOwnFields() {
        val metadata = MediaConvertMetadata().withValue(MediaConvertMetadataField.AUTHOR, "Writer")
            .withValue(MediaConvertMetadataField.ARTIST, "Singer")
            .withValue(MediaConvertMetadataField.YEAR, "2026")
        assertTrue("author=Writer" in metadata.arguments())
        assertFalse("artist=Singer" in metadata.arguments())
        assertTrue("artist=Singer" in metadata.arguments(true))
        assertTrue("date=2026" in metadata.arguments(true))
        assertFalse("author=Writer" in metadata.arguments(true))
    }

    @Test
    fun readsTagLibAndContainerAliasesWithoutLosingTrackTotalsOrDates() {
        val values = mapOf(
            "ALBUMARTIST" to "专辑艺术家", "COMPOSER" to "作曲者", "TRACKNUMBER" to "3/12",
            "DISCNUMBER" to "2/3", "COMMENT" to "第一行\n第二行", "LYRICIST" to "作词者",
            "COPYRIGHT" to "© 2026", "LANGUAGE" to "jpn", "DATE" to "2026-08-14"
        )
        val metadata = MediaConvertMetadata.fromTags(values::get)
        assertEquals("专辑艺术家", metadata.albumArtist)
        assertEquals("作曲者", metadata.composer)
        assertEquals("3/12", metadata.trackNumber)
        assertEquals("2/3", metadata.discNumber)
        assertEquals("第一行\n第二行", metadata.comment)
        assertEquals("作词者", metadata.lyricist)
        assertEquals("© 2026", metadata.copyright)
        assertEquals("jpn", metadata.language)
        assertEquals("2026-08-14", metadata.year)
        assertTrue(metadata.editedFields.isEmpty())
        assertTrue(metadata.arguments(true).isEmpty())
    }

    @Test
    fun newAudioEditsLeaveUntouchedTagsAndVideoArgumentsAlone() {
        val metadata = MediaConvertMetadata(title = "Original title", composer = "Untouched composer")
            .withValue(MediaConvertMetadataField.ALBUM_ARTIST, "New album artist")
            .withValue(MediaConvertMetadataField.TRACK_NUMBER, "4/12")
            .withValue(MediaConvertMetadataField.LANGUAGE, "")
        assertEquals("Original title", metadata.title)
        assertEquals("Untouched composer", metadata.composer)
        assertEquals(setOf(MediaConvertMetadataField.ALBUM_ARTIST, MediaConvertMetadataField.TRACK_NUMBER,
            MediaConvertMetadataField.LANGUAGE), metadata.editedFields)
        assertEquals(listOf("-metadata", "album_artist=New album artist", "-metadata", "track=4/12",
            "-metadata", "language="), metadata.arguments(true))
        assertTrue(metadata.arguments().isEmpty())
    }

    @Test
    fun commonAudioFieldsCanBeIndependentlyEditedOrCleared() {
        val values = linkedMapOf(
            MediaConvertMetadataField.ALBUM_ARTIST to "Album artist",
            MediaConvertMetadataField.COMPOSER to "Composer",
            MediaConvertMetadataField.TRACK_NUMBER to "3/12",
            MediaConvertMetadataField.DISC_NUMBER to "2/3",
            MediaConvertMetadataField.COMMENT to "A comment",
            MediaConvertMetadataField.LYRICIST to "Lyricist",
            MediaConvertMetadataField.COPYRIGHT to "Copyright",
            MediaConvertMetadataField.LANGUAGE to "eng"
        )
        val metadata = values.entries.fold(MediaConvertMetadata()) { current, (field, value) -> current.withValue(field, value) }
        for ((field, value) in values) assertEquals(field.name, value, metadata.value(field))
        val cleared = metadata.withValue(MediaConvertMetadataField.COMPOSER, "")
        assertEquals("", cleared.composer)
        assertEquals("Album artist", cleared.albumArtist)
        assertEquals("3/12", cleared.trackNumber)
        assertTrue("composer=" in cleared.arguments(true))
    }
}
