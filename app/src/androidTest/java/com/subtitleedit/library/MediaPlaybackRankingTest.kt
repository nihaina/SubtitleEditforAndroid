package com.subtitleedit.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPlaybackRankingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun upgradesV4AndPreservesRankingsWithFiftyThousandIndexedTracks() {
        val name = "ranked-library-${System.nanoTime()}.db"
        try {
            val (baselineNever, baselineMost) = MediaLibraryDatabase(context, name).use { helper ->
                val db = helper.writableDatabase
                // v5 only adds indexes: remove them before seeding a disposable v4 database.
                db.libraryTransaction {
                    RANK_INDEXES.forEach { execSQL("DROP INDEX $it") }
                    version = 4
                    seedLargeLibrary()
                }
                val neverPlayed = db.rankedTracks(V4_NEVER_PLAYED)
                val mostPlayed = db.mostPlayedPaths()
                val oldPlan = db.plan(V4_NEVER_PLAYED, 100)
                assertTrue(oldPlan.toString(), oldPlan.any { it.contains("TEMP B-TREE") })
                Log.i(TAG, "v4 top100 never=${medianMs { db.rankedTracks(V4_NEVER_PLAYED) }} ms, top20 most=${medianMs { db.mostPlayedPaths() }} ms")
                neverPlayed to mostPlayed
            }

            val upgradeStart = SystemClock.elapsedRealtime()
            MediaLibraryDatabase(context, name).use { helper ->
                val db = helper.writableDatabase
                Log.i(TAG, "opened v4 with 50,000 indexed tracks as v${db.version} in ${SystemClock.elapsedRealtime() - upgradeStart} ms")
                assertEquals(5, db.version)
                assertEquals(baselineNever, db.rankedTracks(MediaPlaybackQueries.NEVER_PLAYED))
                assertEquals(baselineMost, db.mostPlayedPaths())
                val neverPlan = db.plan(MediaPlaybackQueries.NEVER_PLAYED, 100)
                val mostPlan = db.plan(MediaPlaybackQueries.MOST_PLAYED, 20)
                Log.i(TAG, "v5 never plan: $neverPlan; most plan: $mostPlan")
                assertFalse(neverPlan.toString(), neverPlan.any { it.contains("TEMP B-TREE") })
                assertFalse(mostPlan.toString(), mostPlan.any { it.contains("TEMP B-TREE") })
                for (index in RANK_INDEXES) {
                    assertTrue(index, (neverPlan + mostPlan).any { it.contains(index) })
                }
                Log.i(TAG, "v5 top100 never=${medianMs { db.rankedTracks(MediaPlaybackQueries.NEVER_PLAYED) }} ms, top20 most=${medianMs { db.mostPlayedPaths() }} ms")
            }
            MediaLibraryRepository.open(context, name).use { repository ->
                assertEquals(50_000, repository.getIndexedTracks().size)
                val never = repository.getNeverPlayedTracks(limit = 100)
                assertEquals(baselineNever, never)
                assertEquals(listOf("/Zplaylist.wav", "/afavorite.wav", "/indexed/3.wav"), never.take(3).map { it.path })
                assertEquals(listOf("a-track", "A-track", "Track 00003"), never.take(3).map { it.title })
                assertEquals(baselineMost, repository.getMostPlayed(limit = 20).map { it.path })
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun topWorksKeepsTieOrderAndCountsDistinctPlaysOnlyInsideTheRange() {
        val name = "ranked-works-${System.nanoTime()}.db"
        try {
            MediaLibraryDatabase(context, name).use { helper ->
                val db = helper.writableDatabase
                fun session(path: String, date: String, duration: Long) = db.execSQL(
                    "INSERT INTO listening_sessions(media_path,title,listening_date,start_at_ms,last_active_at_ms,duration_ms) VALUES(?,?,?,0,1,?)",
                    arrayOf<Any>(path, path, date, duration)
                )
                session("/a.wav", "2026-11-09", 2_000)
                session("/a.wav", "2026-11-10", 3_000)
                session("/b.wav", "2026-11-09", 5_000)
                session("/outside.wav", "2026-11-08", 9_000)
                session("/empty.wav", "2026-11-09", 0)
                for ((date, run) in listOf("08" to "old", "09" to "one", "10" to "one", "10" to "two")) {
                    db.execSQL("INSERT INTO listening_day_plays(date,playback_id,path) VALUES(?,?,'/a.wav')", arrayOf("2026-11-$date", run))
                }
                db.execSQL("INSERT INTO legacy_listening_plays(date,path,play_count) VALUES('2026-11-09','/a.wav',3),('2026-11-08','/a.wav',99)")
                val ranked = ListeningStatsStore.top(db, "2026-11-09", "2026-11-10", 2)
                assertEquals(listOf("/a.wav", "/b.wav"), ranked.map { it.mediaPath })
                assertEquals(listOf(5_000L, 5_000L), ranked.map { it.totalDurationMs })
                assertEquals(listOf(5, 0), ranked.map { it.playCount })
                assertEquals(ranked.take(1), ListeningStatsStore.top(db, "2026-11-09", "2026-11-10", 1))
                assertTrue(ListeningStatsStore.top(db, "2026-11-09", "2026-11-10", 0).isEmpty())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun SQLiteDatabase.seedLargeLibrary() {
        compileStatement("INSERT INTO library_tracks(path,title,added_at_ms) VALUES(?,?,0)").use { indexed ->
            compileStatement("INSERT INTO playback_history(path,title,played_at_ms,play_count) VALUES(?,?,?,?)").use { played ->
                repeat(50_000) { i ->
                    val path = "/indexed/$i.wav"
                    val title = "Track ${i.toString().padStart(5, '0')}"
                    indexed.bindString(1, path)
                    indexed.bindString(2, title)
                    indexed.executeInsert()
                    if (i % 4 == 0) {
                        played.bindString(1, path)
                        played.bindString(2, title)
                        played.bindLong(3, (i % 168).toLong())
                        played.bindLong(4, (i % 71).toLong())
                        played.executeInsert()
                    }
                }
            }
        }
        // Check case-insensitive title ties, case-sensitive path ties, and source precedence.
        execSQL("""INSERT INTO favorites(path,title,added_at_ms) VALUES
            ('/afavorite.wav','A-track',0),('/indexed/3.wav','0-hidden-by-indexed',0)""")
        execSQL("""INSERT INTO playlist_items(playlist_id,path,title,added_at_ms) VALUES
            (1,'/Zplaylist.wav','a-track',0),(2,'/Zplaylist.wav','0-hidden-by-older-entry',0),
            (1,'/afavorite.wav','0-hidden-by-favorite',0)""")
        execSQL("""INSERT INTO listening_sessions(media_path,title,listening_date,start_at_ms,last_active_at_ms,duration_ms)
            VALUES('/indexed/1.wav','session only','2026-11-09',0,1,1),('/indexed/2.wav','session only','2026-11-09',0,1,1)""")
    }

    private fun SQLiteDatabase.rankedTracks(sql: String): List<MediaLibraryTrack> =
        rawQuery(sql, arrayOf("100")).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.libraryTrack()) } }

    private fun SQLiteDatabase.mostPlayedPaths(): List<String> =
        rawQuery(MediaPlaybackQueries.MOST_PLAYED, arrayOf("20")).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    private fun SQLiteDatabase.plan(sql: String, limit: Int): List<String> =
        rawQuery("EXPLAIN QUERY PLAN $sql", arrayOf(limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
        }

    private fun medianMs(query: () -> Any): Double {
        repeat(5) { query() }
        val nanos = List(15) {
            val start = SystemClock.elapsedRealtimeNanos()
            query()
            SystemClock.elapsedRealtimeNanos() - start
        }.sorted()
        return nanos[nanos.size / 2] / 1e6
    }

    private companion object {
        const val TAG = "MediaPlaybackRankingTest"
        val RANK_INDEXES = listOf(
            "idx_playback_history_rank", "idx_library_tracks_title_path", "idx_favorites_title_path",
            "idx_playlist_items_title_path", "idx_playlist_items_path"
        )
        const val V4_NEVER_PLAYED = """SELECT $TRACK_COLUMNS FROM (
            SELECT $TRACK_COLUMNS FROM library_tracks
            UNION ALL
            SELECT $TRACK_COLUMNS FROM favorites f
            WHERE NOT EXISTS (SELECT 1 FROM library_tracks l WHERE l.path=f.path)
            UNION ALL
            SELECT $TRACK_COLUMNS FROM playlist_items p
            WHERE p.id IN (SELECT MIN(id) FROM playlist_items GROUP BY path)
            AND NOT EXISTS (SELECT 1 FROM library_tracks l WHERE l.path=p.path)
            AND NOT EXISTS (SELECT 1 FROM favorites f WHERE f.path=p.path)) candidates
            WHERE NOT EXISTS (SELECT 1 FROM playback_history h WHERE h.path=candidates.path)
            AND NOT EXISTS (SELECT 1 FROM listening_sessions s WHERE s.media_path=candidates.path)
            ORDER BY title COLLATE NOCASE,path LIMIT ?"""
    }
}
