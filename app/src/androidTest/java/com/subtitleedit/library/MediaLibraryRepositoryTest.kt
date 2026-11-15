package com.subtitleedit.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.Closeable
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite queries and migrations against a disposable database; user data is untouched. */
@RunWith(AndroidJUnit4::class)
class MediaLibraryRepositoryTest {
    private val context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private class Fixture(context: Context) : Closeable {
        val name = "library-test-${System.nanoTime()}.db"
        val repo =
            MediaLibraryRepository.open(context, name) { file, duration ->
                MediaLibraryTrack(
                    file.absolutePath,
                    "Title ${file.nameWithoutExtension}",
                    "Artist",
                    "Album",
                    durationMs = duration,
                )
            }
        private val app = context

        override fun close() {
            repo.close()
            app.deleteDatabase(name)
        }
    }

    @Test
    fun emptyAndPopulatedSearchCoverEverySourceWithHistoryTimestampMapping() =
        Fixture(context).use { f ->
            val r = f.repo
            assertTrue(r.searchLibrary("").isEmpty())
            r.toggleFavorite("/favorite.wav", 12_000L)
            val id = requireNotNull(r.createPlaylist("Favorites 2"))
            assertTrue(r.addTrackToPlaylist(id, "/playlist.wav"))
            r.recordPlaybackProgress("/history.wav", "run", 30_000L, 3_000L)
            assertEquals(
                setOf("/favorite.wav", "/playlist.wav", "/history.wav"),
                r.searchLibrary("").map { it.path }.toSet(),
            )
            assertEquals("/history.wav", r.searchLibrary("HISTORY").single().path)
            assertEquals(3, r.searchLibrary("artist").size)
        }

    @Test
    fun neverPlayedMergesPathsBeforeApplyingLimitAndKeepsPreferredMetadata() {
        val name = "library-never-played-${System.nanoTime()}.db"
        val file = File.createTempFile("library-indexed-", ".wav", context.cacheDir)
        var label = "Indexed"
        var addedAt = 100L
        val r = MediaLibraryRepository.open(context, name) { media, _ ->
            MediaLibraryTrack(media.path, "$label ${media.name}", addedAtMs = addedAt++)
        }
        try {
            val first = requireNotNull(r.createPlaylist("First"))
            val second = requireNotNull(r.createPlaylist("Second"))
            r.indexTrack(file.path)
            label = "Favorite"
            r.toggleFavorite(file.path)
            r.toggleFavorite("/favorite-only.wav")
            label = "Playlist"
            listOf(file.path, "/favorite-only.wav", "/playlist-only.wav").forEach { path ->
                r.addTrackToPlaylist(first, path)
                r.addTrackToPlaylist(second, path)
            }
            r.recordPlaybackProgress("/played.wav", "played", 1_000L, 500L)
            r.addTrackToPlaylist(first, "/played.wav")
            r.addTrackToPlaylist(first, "/listened.wav")
            r.recordListeningInterval("/listened.wav", "listened", System.currentTimeMillis(), 100L)

            val tracks = r.getNeverPlayedTracks(limit = 3)
            assertEquals(setOf(file.path, "/favorite-only.wav", "/playlist-only.wav"), tracks.map { it.path }.toSet())
            assertEquals(3, tracks.size)
            assertEquals("Indexed ${file.name}", tracks.first { it.path == file.path }.title)
            assertEquals("Favorite favorite-only.wav", tracks.first { it.path == "/favorite-only.wav" }.title)
            assertEquals("Playlist playlist-only.wav", tracks.first { it.path == "/playlist-only.wav" }.title)
            assertTrue(r.getNeverPlayedTracks(limit = 0).isEmpty())
            assertEquals(2, r.getNeverPlayedTracks(limit = 2).size)
        } finally {
            r.close()
            context.deleteDatabase(name)
            file.delete()
        }
    }

    @Test
    fun collectionsRejectOrphansAndKeepDuplicateOperationsIdempotent() =
        Fixture(context).use { f ->
            val r = f.repo
            val id = requireNotNull(r.createPlaylist(" Mix "))
            assertEquals(id, r.createPlaylist("Mix"))
            val second = requireNotNull(r.createPlaylist("Other"))
            assertFalse(r.renamePlaylist(second, "Mix"))
            assertFalse(r.addTrackToPlaylist(999_999L, "/a.wav"))
            assertEquals(1, r.addTracksToPlaylists(listOf(id, id, 999_999L), "/a.wav"))
            assertFalse(r.addTrackToPlaylist(id, "/a.wav"))
            assertEquals(setOf(id), r.getPlaylistIdsContainingTrack("/a.wav"))
            assertEquals(1, r.getPlaylists().first { it.id == id }.itemCount)
            r.saveCurrentQueue(listOf("/a.wav", "/b.wav", "/a.wav"), 2)
            assertEquals(listOf("/a.wav", "/b.wav"), r.getCurrentQueue().first.map { it.path })
            assertEquals(0, r.getCurrentQueue().second)
            r.deletePlaylist(id)
            assertTrue(r.getPlaylistTracks(id).isEmpty())
            assertTrue(r.getPlaylistIdsContainingTrack("/a.wav").isEmpty())
            assertFalse(r.addTrackToPlaylist(id, "/a.wav"))
        }

    @Test
    fun concurrentFavoriteTogglesAreAtomic() =
        Fixture(context).use { f ->
            val executor = Executors.newFixedThreadPool(4)
            try {
                val futures =
                    (0 until 8).map {
                        executor.submit<Boolean> { f.repo.toggleFavorite("/favorite.wav") }
                    }
                val results = futures.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(4, results.count { it })
                assertEquals(4, results.count { !it })
                assertFalse(f.repo.isFavorite("/favorite.wav"))
            } finally {
                executor.shutdownNow()
            }
        }

    @Test
    fun replayCountsAreStableAcrossProgressUpdatesAndConcurrentWriters() =
        Fixture(context).use { f ->
            val r = f.repo
            r.recordPlaybackProgress("/a.wav", "one", 30_000L, 1_000L, 1_000L)
            r.recordPlaybackProgress("/a.wav", "one", 30_000L, 5_000L, 5_000L)
            r.recordPlaybackProgress("/a.wav", "two", 30_000L, 0L, 6_000L)
            r.recordPlaybackProgress("/a.wav", "one", 30_000L, 2_000L, 2_000L)
            var history = r.getPlaybackHistory().single()
            assertEquals(2, history.playCount)
            assertEquals(0L, history.positionMs)
            assertEquals(6_000L, history.playedAtMs)
            val ready = CountDownLatch(6)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(6)
            try {
                val futures =
                    (0 until 6).map { index ->
                        executor.submit {
                            ready.countDown()
                            assertTrue(start.await(5, TimeUnit.SECONDS))
                            r.recordPlaybackProgress(
                                "/a.wav",
                                "concurrent-$index",
                                30_000L,
                                index * 1_000L,
                                10_000L + index,
                            )
                        }
                    }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
            history = r.getPlaybackHistory().single()
            assertEquals(8, history.playCount)
            assertEquals(5_000L, history.positionMs)
            r.clearPlaybackHistory()
            assertTrue(r.getPlaybackHistory().isEmpty())
        }

    @Test
    fun listeningIntervalsSplitAtFiveAmAndCountEachTrackAndRunOnce() =
        Fixture(context).use { f ->
            val r = f.repo
            val start =
                LocalDateTime.of(2026, 11, 14, 4, 59, 58)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            r.recordListeningInterval("/a.wav", "one", start, 4_000L)
            r.recordListeningInterval("/a.wav", "one", start, 4_000L) // retry must be harmless
            r.recordListeningInterval("/a.wav", "one", start + 4_000L, 1_000L)
            r.recordListeningInterval("/a.wav", "two", start + 5_000L, 1_000L)
            val days = r.getAllDailyStats()
            assertEquals(listOf("2026-11-13", "2026-11-14"), days.map { it.date })
            assertEquals(listOf(2_000L, 4_000L), days.map { it.durationMs })
            assertEquals(listOf(1, 1), days.map { it.trackCount })
            assertEquals(listOf(1, 2), days.map { it.playCount })
            val session = r.getSessionsForDate("2026-11-14").single()
            assertEquals(start + 2_000L, session.startAtMs)
            assertEquals(start + 6_000L, session.lastActiveAtMs)
            assertEquals(4_000L, session.durationMs)
            val top = r.getTopListeningItems("2026-11-13", "2026-11-14").single()
            assertEquals(6_000L, top.totalDurationMs)
            assertEquals(2, top.playCount)
        }

    @Test
    fun listeningRejectsAPlaybackIdReusedForAnotherTrack() =
        Fixture(context).use { f ->
            f.repo.recordListeningInterval("/a.wav", "same-id", 10_000L, 1_000L)
            assertThrows(IllegalArgumentException::class.java) {
                f.repo.recordListeningInterval("/b.wav", "same-id", 11_000L, 1_000L)
            }
            assertEquals(1_000L, f.repo.getAllDailyStats().single().durationMs)
            assertEquals(
                "/a.wav",
                f.repo.getSessionsForDate(ListeningDay.dateOf(10_000L)).single().mediaPath,
            )
        }

    @Test
    fun scanDoesNotHoldADatabaseTransactionWhileReadingMetadata() {
        val name = "library-scan-${System.nanoTime()}.db"
        val directory = File(context.cacheDir, "scan-${System.nanoTime()}").apply { mkdirs() }
        File(directory, "a.wav").writeBytes(byteArrayOf(0))
        val metadataStarted = CountDownLatch(1)
        val releaseMetadata = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val r =
            MediaLibraryRepository.open(context, name) { file, _ ->
                metadataStarted.countDown()
                check(releaseMetadata.await(5, TimeUnit.SECONDS))
                MediaLibraryTrack(file.path, file.name)
            }
        try {
            r.createPlaylist("Warm database")
            val scan = executor.submit<Int> { r.scanDirectoriesToLibrary(listOf(directory)) }
            assertTrue(metadataStarted.await(5, TimeUnit.SECONDS))
            assertNotNull(r.createPlaylist("Writable during metadata"))
            assertFalse(r.isFavorite("/a.wav"))
            releaseMetadata.countDown()
            assertEquals(1, scan.get(10, TimeUnit.SECONDS).toInt())
            assertEquals(1, r.searchLibrary("").size)
        } finally {
            releaseMetadata.countDown()
            executor.shutdownNow()
            r.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    @Test
    fun versionOneUpgradeKeepsCollectionsHistoryAndListeningTotals() {
        val name = "library-migration-${System.nanoTime()}.db"
        val helper = MediaLibraryDatabase(context, name)
        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO favorites(path,title,added_at_ms) VALUES ('/old.wav','Old favorite',1)"
            )
            db.execSQL(
                "INSERT INTO playlists(id,name,created_at_ms,updated_at_ms) VALUES (7,'Old playlist',1,2)"
            )
            db.execSQL(
                "INSERT INTO playlist_items(playlist_id,path,title,sort_order,added_at_ms) VALUES (7,'/old.wav','Old item',0,1)"
            )
            db.execSQL(
                "INSERT INTO current_queue(sort_order,path,title,is_current,updated_at_ms) VALUES (0,'/old.wav','Old queue',1,2)"
            )
            db.execSQL(
                "INSERT INTO playback_history(path,title,played_at_ms,play_count) VALUES ('/old.wav','Old history',2,4)"
            )
            db.execSQL(
                "INSERT INTO daily_stats(date,duration_ms,track_count,play_count) VALUES ('2026-11-13',9000,1,4)"
            )
            db.execSQL(
                "INSERT INTO listening_sessions(media_path,title,listening_date,start_at_ms,last_active_at_ms,duration_ms,track_count) VALUES ('/old.wav','Old session','2026-11-13',1,9001,9000,4)"
            )
            for (table in
                listOf(
                    "playback_runs",
                    "listening_intervals",
                    "listening_day_tracks",
                    "listening_day_plays",
                    "legacy_listening_plays",
                )) db.execSQL("DROP TABLE $table")
            db.version = 1
            helper.close()
            MediaLibraryRepository.open(context, name) { file, _ ->
                    MediaLibraryTrack(file.path, file.name)
                }
                .use { r ->
                    assertEquals("Old favorite", r.getFavorites().single().title)
                    assertEquals("Old playlist", r.getPlaylists().filterNot { it.isSystem }.single().name)
                    assertEquals("Old item", r.getPlaylistTracks(7).single().title)
                    assertEquals("Old queue", r.getCurrentQueue().first.single().title)
                    assertEquals(0, r.getCurrentQueue().second)
                    assertEquals(4, r.getPlaybackHistory().single().playCount)
                    assertEquals(9000L, r.getAllDailyStats().single().durationMs)
                    assertEquals(
                        4,
                        r.getTopListeningItems("2026-11-13", "2026-11-14").single().playCount,
                    )
                    assertEquals(1, r.searchLibrary("").size)
                }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun cancelledScanKeepsCompletedBatchesWithoutPublishingAPartialBatch() {
        val name = "library-cancel-${System.nanoTime()}.db"
        val directory =
            File(context.cacheDir, "scan-cancel-${System.nanoTime()}").apply { mkdirs() }
        repeat(30) { File(directory, "$it.wav").writeBytes(byteArrayOf(0)) }
        val reads = AtomicInteger()
        val r =
            MediaLibraryRepository.open(context, name) { file, _ ->
                reads.incrementAndGet()
                MediaLibraryTrack(file.path, file.name)
            }
        try {
            assertThrows(CancellationException::class.java) {
                r.scanDirectoriesToLibrary(listOf(directory)) { reads.get() >= 26 }
            }
            assertEquals(24, r.searchLibrary("").size)
        } finally {
            r.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    @Test
    fun refreshingIndexUpdatesMetadataInExistingCollectionsWithoutResettingHistory() {
        val name = "library-refresh-${System.nanoTime()}.db"
        val file = File.createTempFile("library-refresh-", ".wav", context.cacheDir)
        var title = "Before"
        val r =
            MediaLibraryRepository.open(context, name) { media, duration ->
                MediaLibraryTrack(media.path, title, "Artist", "Album", durationMs = duration)
            }
        try {
            val id = requireNotNull(r.createPlaylist("A playlist"))
            r.toggleFavorite(file.path)
            r.addTrackToPlaylist(id, file.path)
            r.recordPlaybackProgress(file.path, "run", 30_000L, 8_000L, 10_000L)
            title = "After"
            r.indexTrack(file.path, 40_000L)
            assertEquals("After", r.getFavorites().single().title)
            assertEquals("After", r.getPlaylistTracks(id).single().title)
            val history = r.getPlaybackHistory().single()
            assertEquals("After", history.title)
            assertEquals(8_000L, history.positionMs)
            assertEquals(1, history.playCount)
            assertEquals(40_000L, history.durationMs)
            assertEquals("After", r.searchLibrary("After").single().title)
        } finally {
            r.close()
            context.deleteDatabase(name)
            file.delete()
        }
    }
}
