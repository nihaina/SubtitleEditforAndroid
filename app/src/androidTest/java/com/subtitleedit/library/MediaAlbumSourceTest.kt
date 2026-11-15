package com.subtitleedit.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaConvertMetadataField
import com.subtitleedit.MediaTagAudioRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAlbumSourceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun albumSourceOnlyContainsIndexedFilesDespiteOtherCollections() {
        val name = "album-source-${System.nanoTime()}.db"
        val file = File.createTempFile("album-indexed-", ".wav", context.cacheDir)
        val repository = MediaLibraryRepository.open(context, name) { media, duration ->
            MediaLibraryTrack(media.absolutePath, media.name, album = "Album", durationMs = duration)
        }
        try {
            repository.toggleFavorite("/favorite-only.wav")
            val playlist = requireNotNull(repository.createPlaylist("Album source fixture"))
            repository.addTrackToPlaylist(playlist, "/playlist-only.wav")
            repository.recordPlaybackProgress("/history-only.wav", "album-source-run", 1_000L, 500L)
            assertEquals(3, repository.searchLibrary("").size)
            assertTrue(repository.getIndexedTracks().isEmpty())

            repository.indexTrack(file.path, 2_000L)
            repository.toggleFavorite(file.path)
            repository.addTrackToPlaylist(playlist, file.path)
            val indexed = repository.getIndexedTracks()
            assertEquals(listOf(file.absolutePath), indexed.map { it.path })
            assertEquals(2_000L, indexed.single().durationMs)
        } finally {
            repository.close()
            context.deleteDatabase(name)
            file.delete()
        }
    }

    @Test fun catalogLoadsRealAlbumFieldsAndRefreshesAfterTagInvalidation() {
        val file = createWave()
        try {
            writeAlbumTags(file, "Album artist A", "Composer A", "2024", "2/12", "1/2")
            val track = PlayerTrackMetadataReader.readLibraryTrack(file)
            val initial = AlbumCatalogLoader.load(listOf(track, track)).single()
            assertEquals("Fixture album", initial.track.album)
            assertEquals("Album artist A", initial.albumArtist)
            assertEquals("Composer A", initial.composer)
            assertEquals(2024, initial.year)
            assertEquals(2, initial.trackNumber)
            assertEquals(1, initial.discNumber)
            assertTrue(initial.track.durationMs in 990L..1010L)

            writeAlbumTags(file, "Album artist B", "Composer B", "2025", "3/12", "2/2")
            PlayerTrackMetadataReader.invalidate(file.path)
            val refreshed = AlbumCatalogLoader.load(listOf(PlayerTrackMetadataReader.readLibraryTrack(file))).single()
            assertEquals("Album artist B", refreshed.albumArtist)
            assertEquals("Composer B", refreshed.composer)
            assertEquals(2025, refreshed.year)
            assertEquals(3, refreshed.trackNumber)
            assertEquals(2, refreshed.discNumber)
        } finally {
            PlayerTrackMetadataReader.invalidate(file.path)
            file.delete()
        }
    }

    @Test fun catalogChecksCancellationBeforeReadingMedia() {
        try {
            AlbumCatalogLoader.load(listOf(MediaLibraryTrack("/not-read.wav", "Unused"))) { true }
            fail("A cancelled load must not publish a partial catalog")
        } catch (_: CancellationException) {
            // Expected before the file or metadata is read.
        }
    }

    private fun createWave(): File {
        val file = File.createTempFile("album-metadata-", ".wav", context.cacheDir)
        val count = 8_000
        file.writeBytes(ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(count); putInt(count * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
        }.array())
        return file
    }

    private fun writeAlbumTags(file: File, albumArtist: String, composer: String, year: String, track: String, disc: String) {
        MediaTagAudioRepository.write(file, MediaConvertMetadata(
            title = "Fixture song", artist = "Fixture singer", album = "Fixture album",
            albumArtist = albumArtist, composer = composer, year = year,
            trackNumber = track, discNumber = disc,
            editedFields = setOf(
                MediaConvertMetadataField.TITLE, MediaConvertMetadataField.ARTIST, MediaConvertMetadataField.ALBUM,
                MediaConvertMetadataField.ALBUM_ARTIST, MediaConvertMetadataField.COMPOSER, MediaConvertMetadataField.YEAR,
                MediaConvertMetadataField.TRACK_NUMBER, MediaConvertMetadataField.DISC_NUMBER
            )
        ))
    }
}
