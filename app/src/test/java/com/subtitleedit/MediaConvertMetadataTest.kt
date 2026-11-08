package com.subtitleedit

import org.junit.Assert.assertEquals
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
}
