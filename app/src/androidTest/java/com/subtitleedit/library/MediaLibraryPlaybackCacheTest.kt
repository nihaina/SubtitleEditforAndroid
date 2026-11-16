package com.subtitleedit.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.util.SharedFileCache
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryPlaybackCacheTest {
    @Test fun playbackIndexKeepsUnchangedFileVersionWhileExplicitEditRefreshesIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = "playback-cache-${System.nanoTime()}.db"
        val file = File.createTempFile("playback-cache-", ".wav", context.cacheDir)
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        var title = "Original title"
        val repository = MediaLibraryRepository.open(context, database,
            albumMetadataReader = { MediaAlbumMetadata() }) { metadata, duration ->
            MediaLibraryTrack(metadata.path, title, durationMs = duration)
        }
        try {
            val before = requireNotNull(SharedFileCache.attributes.get(file))
            val invalidations = SharedFileCache.attributes.invalidationRevision.value
            repository.indexTrack(file.path, 10_000L, forceFileRevision = false)
            repository.indexTrack(file.path, 10_000L, forceFileRevision = false)
            assertSame("Playing an unchanged file must retain its row preview key", before,
                SharedFileCache.attributes.get(file))
            assertEquals(invalidations, SharedFileCache.attributes.invalidationRevision.value)
            assertEquals(10_000L, repository.getIndexedTracks().single().durationMs)

            // Tag writers can preserve both size and mtime. Explicit indexing still forces refresh.
            val modified = file.lastModified()
            file.writeBytes(byteArrayOf(4, 3, 2, 1))
            check(file.setLastModified(modified))
            title = "Edited title"
            repository.indexTrack(file.path, 10_000L)
            assertNotEquals(before.cacheKey, SharedFileCache.attributes.get(file)?.cacheKey)
            assertEquals("Edited title", repository.getIndexedTracks().single().title)
        } finally {
            repository.close()
            context.deleteDatabase(database)
            SharedFileCache.attributes.invalidate(file.path)
            file.delete()
        }
    }
}
