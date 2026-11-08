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
}
