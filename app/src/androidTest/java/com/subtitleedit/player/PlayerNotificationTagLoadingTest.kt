package com.subtitleedit.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.MediaAlbumMetadata
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlayerNotificationTagLoadingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun missingExternalFileUsesTheFilenameAndFallbackArtworkWithoutReadingTextTags() {
        val database = "notification-external-tags-${System.nanoTime()}.db"
        val repository = MediaLibraryRepository.open(context, database,
            albumMetadataReader = { error("Notification must not parse album tags") }) { _, _ ->
            error("Notification must not parse external tags")
        }
        try {
            val track = PlayerTrack("/ordinary-folder/External file.wav", "External file.wav", false)
            val loaded = PlayerMediaNotificationMetadata.load(context, track, 12_000L, repository)

            assertEquals("External file", loaded.title)
            assertEquals("", loaded.artist)
            assertEquals("", loaded.album)
            assertEquals(12_000L, loaded.durationMs)
            assertNotNull("A missing source file still has fallback artwork", loaded.artwork)
            assertTrue(repository.getIndexedTracks().isEmpty())
        } finally {
            repository.close()
            context.deleteDatabase(database)
        }
    }

    @Test fun notificationReusesStoredCollectionTagsWithoutReadingTheSourceAgain() {
        val database = "notification-stored-tags-${System.nanoTime()}.db"
        val reads = AtomicInteger()
        val repository = MediaLibraryRepository.open(context, database,
            albumMetadataReader = { MediaAlbumMetadata() }) { metadata, _ ->
            reads.incrementAndGet()
            MediaLibraryTrack(metadata.path, "Library title", "Library artist", "Library album")
        }
        try {
            val track = PlayerTrack("/ordinary-folder/Collection file.wav", "Collection file.wav", false)
            repository.toggleFavorite(track.path)
            val loaded = PlayerMediaNotificationMetadata.load(context, track, 12_000L, repository)

            assertEquals(1, reads.get())
            assertEquals("Library title", loaded.title)
            assertEquals("Library artist", loaded.artist)
            assertEquals("Library album", loaded.album)
        } finally {
            repository.close()
            context.deleteDatabase(database)
        }
    }
}
