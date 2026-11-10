package com.subtitleedit

import org.junit.Assert.*
import org.junit.Test

class MediaTagAudioMetadataTest {
    @Test fun nativePropertiesPopulateEveryEditableAudioFieldWithoutMarkingThemEdited() {
        val source = mapOf("TITLE" to listOf("海风 🌊"), "ARTIST" to listOf("歌手", "Guest"),
            "ALBUM" to listOf("Album"), "GENRE" to listOf("Custom"), "DATE" to listOf("2026"),
            "ALBUMARTIST" to listOf("Album artist"), "COMPOSER" to listOf("Composer"),
            "TRACKNUMBER" to listOf("2/12"), "DISCNUMBER" to listOf("1/2"), "COMMENT" to listOf("Comment"),
            "LYRICIST" to listOf("Lyricist"), "COPYRIGHT" to listOf("Copyright"), "LANGUAGE" to listOf("zho"))
        val result = MediaTagAudioMetadata.decode(source)
        assertEquals("海风 🌊", result.title)
        assertEquals("歌手 / Guest", result.artist)
        assertEquals("Album artist", result.albumArtist)
        assertEquals("Composer", result.composer)
        assertEquals("2/12", result.trackNumber)
        assertEquals("1/2", result.discNumber)
        assertEquals("Comment", result.comment)
        assertEquals("Lyricist", result.lyricist)
        assertEquals("Copyright", result.copyright)
        assertEquals("zho", result.language)
        assertTrue(result.editedFields.isEmpty())
    }

    @Test fun editsAndClearsRemoveOnlyReturnedAliasesAndNeverRebuildUneditedLyrics() {
        val changes = MediaConvertMetadata().withValue(MediaConvertMetadataField.GENRE, "Jazz")
            .withValue(MediaConvertMetadataField.ARTIST, "")
        val existing = mapOf("GENRE" to listOf("Old"), "STYLE" to listOf("Old style"),
            "MOOD" to listOf("Old mood"), "ARTIST" to listOf("Singer"), "AUTHOR" to listOf("Singer"),
            "LYRICS" to listOf("[00:00.00]Preserve me"), "PRIVATE" to listOf("Keep"))
        assertEquals(mapOf("GENRE" to "Jazz", "STYLE" to "", "MOOD" to "", "ARTIST" to "", "AUTHOR" to ""),
            MediaTagAudioMetadata.updates(changes, existing))
        assertFalse("TCON" in MediaTagAudioMetadata.updates(changes, existing))
    }

    @Test fun emptyFieldsAreExplicitAndNumericGenreReadbackMayUseTheStandardName() {
        val clear = MediaConvertMetadata().withValue(MediaConvertMetadataField.ALBUM, "")
        assertEquals(mapOf("ALBUM" to ""), MediaTagAudioMetadata.updates(clear, emptyMap()))
        assertTrue(MediaTagAudioMetadata.verifies(clear, MediaConvertMetadata()))
        assertFalse(MediaTagAudioMetadata.verifies(clear, MediaConvertMetadata(album = "Old album")))
        val genre = MediaConvertMetadata().withValue(MediaConvertMetadataField.GENRE, "111")
        assertTrue(MediaTagAudioMetadata.verifies(genre, MediaConvertMetadata(genre = "Slow Jam")))
        assertTrue(MediaTagAudioMetadata.verifies(genre, MediaConvertMetadata(genre = "111")))
        assertFalse(MediaTagAudioMetadata.verifies(genre, MediaConvertMetadata(genre = "Rock")))
        assertFalse(MediaTagAudioMetadata.verifies(genre, MediaConvertMetadata()))
        assertTrue(MediaTagAudioMetadata.matches(MediaConvertMetadataField.GENRE, "17", "Rock"))
        assertFalse(MediaTagAudioMetadata.matches(MediaConvertMetadataField.GENRE, "17", "Slow Jam"))
        assertFalse(MediaTagAudioMetadata.matches(MediaConvertMetadataField.GENRE, "9999", "Rock"))
        assertTrue(MediaTagAudioMetadata.matches(MediaConvertMetadataField.GENRE, "191", "Psybient"))
    }

    @Test fun integerPairReadbackAcceptsOnlyEquivalentTrackAndDiscValues() {
        for (field in listOf(MediaConvertMetadataField.TRACK_NUMBER, MediaConvertMetadataField.DISC_NUMBER)) {
            assertTrue(MediaTagAudioMetadata.matches(field, "01", "1"))
            assertTrue(MediaTagAudioMetadata.matches(field, "01/012", "1/12"))
            assertTrue(MediaTagAudioMetadata.matches(field, "01/0", "1"))
            assertFalse(MediaTagAudioMetadata.matches(field, "01/012", "1/13"))
            assertFalse(MediaTagAudioMetadata.matches(field, "01/012", "1"))
            assertFalse(MediaTagAudioMetadata.matches(field, "1", "2"))
            assertFalse(MediaTagAudioMetadata.matches(field, "1/", "1"))
            assertFalse(MediaTagAudioMetadata.matches(field, "1/2/3", "1/2"))
            assertFalse(MediaTagAudioMetadata.matches(field, "one", "0"))
            assertFalse(MediaTagAudioMetadata.matches(field, "", "0"))
            assertFalse(MediaTagAudioMetadata.matches(field, "4294967297", "1"))
        }
    }

    @Test fun metadataReadbackUsesConsistentWhitespaceAndDateRules() {
        val title = MediaConvertMetadata().withValue(MediaConvertMetadataField.TITLE, " Name ")
        assertTrue(MediaTagAudioMetadata.verifies(title, MediaConvertMetadata(title = " Name ")))
        assertTrue(MediaTagAudioMetadata.matches(MediaConvertMetadataField.TITLE, " Name ", " Name "))
        assertTrue(MediaTagAudioMetadata.matches(MediaConvertMetadataField.TITLE, " Name ", "Name"))
        assertFalse(MediaTagAudioMetadata.matches(MediaConvertMetadataField.TITLE, " Name ", "Different"))
        assertTrue(MediaTagAudioMetadata.matches(MediaConvertMetadataField.YEAR,
            "2026-11-10T18:22:06", "2026-11-10 18:22:06"))
        assertFalse(MediaTagAudioMetadata.matches(MediaConvertMetadataField.YEAR, "2026", "2025"))
    }

    @Test fun unchangedAndNonAudioFieldsCannotProduceAudioMutations() {
        assertTrue(MediaTagAudioMetadata.updates(MediaConvertMetadata(title = "Visible only"), emptyMap()).isEmpty())
        assertTrue(MediaTagAudioMetadata.updates(
            MediaConvertMetadata().withValue(MediaConvertMetadataField.AUTHOR, "Video author"), emptyMap()).isEmpty())
    }
}
