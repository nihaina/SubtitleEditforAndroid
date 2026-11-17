package com.subtitleedit.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.library.buildMediaAlbums
import com.subtitleedit.util.FfprobeMediaInformation
import com.subtitleedit.util.MediaFilePropertiesReader
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaCategoryRevealMetadataTest {
    @Test fun matroskaNavigationKeepsSoftwareTagsWhenReplacingTheCatalogRow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "category-metadata-${System.nanoTime()}").apply { check(mkdirs()) }
        val file = File(root, "tagged.mkv")
        try {
            val session = FFmpegKit.executeWithArguments(arrayOf(
                "-y", "-f", "lavfi", "-i", "sine=sample_rate=8000", "-t", "0.1", "-c:a", "pcm_s16le",
                "-metadata", "title=Current title", "-metadata", "artist=Current singer",
                "-metadata", "album=Current album", "-metadata", "album_artist=Current artist",
                "-metadata", "composer=Current composer", "-metadata", "genre=Current genre",
                "-metadata", "date=2026", "-metadata", "track=3/9", "-metadata", "disc=2/2", file.path
            ))
            assertTrue(session.getFailStackTrace() ?: session.getAllLogsAsString(), ReturnCode.isSuccess(session.getReturnCode()))
            val probe = FfprobeMediaInformation.read(file.path, context.cacheDir)
            assertEquals("Current title", probe.getTags()?.optString("title"))
            val target = requireNotNull(loadMediaCategoryRevealTrack(file.path, context.cacheDir))
            assertEquals("Current title", target.track.title)
            assertEquals("Current singer", target.track.artist)
            assertEquals("Current album", target.track.album)
            assertEquals("Current artist", target.albumArtist)
            assertEquals("Current composer", target.composer)
            assertEquals("Current genre", target.genre)
            assertEquals(2026, target.year)
            assertEquals(3, target.trackNumber)
            assertEquals(2, target.discNumber)
            val properties = MediaFilePropertiesReader.read(file, readTags = true, cacheDirectory = context.cacheDir)
            assertTrue(properties.mediaDetails.any { it.label == "歌曲名" && it.value == "Current title" })
            assertTrue(properties.mediaDetails.any { it.label == "艺术家" && it.value == "Current artist" })

            val old = MediaAlbumTrack(MediaLibraryTrack(file.path, "Stale title", addedAtMs = 123L),
                genre = "Stale genre", playCount = 7, lastPlayedAtMs = 456L)
            val current = mediaAlbumsWithRevealTrack(buildMediaAlbums(listOf(old)), target)
                .flatMap { it.tracks }.single()
            assertEquals("Current genre", current.genre)
            assertEquals("Current title", current.track.title)
            assertEquals(123L, current.track.addedAtMs)
            assertEquals(7, current.playCount)
            assertEquals(456L, current.lastPlayedAtMs)
            assertTrue("An information lookup cannot add a file outside the catalog",
                mediaAlbumsWithRevealTrack(emptyList(), target).isEmpty())
        } finally {
            PlayerTrackMetadataReader.invalidate(file.path)
            root.deleteRecursively()
        }
    }
}
