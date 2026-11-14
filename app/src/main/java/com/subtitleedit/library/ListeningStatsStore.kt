package com.subtitleedit.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Runs inside the repository's short write transaction. No media I/O occurs here. */
internal object ListeningStatsStore {
    private const val MERGE_GAP_MS = 20 * 60 * 1000L

    fun record(
        db: SQLiteDatabase,
        track: MediaLibraryTrack,
        playbackId: String,
        start: Long,
        duration: Long,
        slices: List<ListeningDay.Slice>,
    ): Boolean {
        require(playbackId.isNotBlank())
        require(
            !db.exists(
                "SELECT 1 FROM listening_day_plays WHERE playback_id=? AND path!=?",
                playbackId,
                track.path,
            )
        ) {
            "A playback id cannot belong to multiple tracks"
        }
        val inserted =
            db.insertWithOnConflict(
                "listening_intervals",
                null,
                ContentValues().apply {
                    put("playback_id", playbackId)
                    put("started_at_ms", start)
                    put("duration_ms", duration)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        if (inserted < 0L) return false
        slices.forEach { slice ->
            val newTrack =
                db.insertWithOnConflict(
                    "listening_day_tracks",
                    null,
                    ContentValues().apply {
                        put("date", slice.date)
                        put("path", track.path)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                ) >= 0L
            val newPlay =
                db.insertWithOnConflict(
                    "listening_day_plays",
                    null,
                    ContentValues().apply {
                        put("date", slice.date)
                        put("playback_id", playbackId)
                        put("path", track.path)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                ) >= 0L
            db.execSQL(
                "INSERT OR IGNORE INTO daily_stats(date,duration_ms,track_count,play_count) VALUES (?,0,0,0)",
                arrayOf(slice.date),
            )
            db.execSQL(
                "UPDATE daily_stats SET duration_ms=duration_ms+?,track_count=track_count+?,play_count=play_count+? WHERE date=?",
                arrayOf<Any>(slice.durationMs, if (newTrack) 1 else 0, if (newPlay) 1 else 0, slice.date),
            )
            saveSession(db, track, slice, if (newPlay) 1 else 0)
        }
        return true
    }

    private fun saveSession(
        db: SQLiteDatabase,
        track: MediaLibraryTrack,
        slice: ListeningDay.Slice,
        plays: Int,
    ) {
        var mergeId: Long? = null
        db.rawQuery(
                """SELECT id,media_path,start_at_ms,last_active_at_ms FROM listening_sessions
            WHERE listening_date=? ORDER BY last_active_at_ms DESC,id DESC LIMIT 1""",
                arrayOf(slice.date),
            )
            .use { c ->
                if (
                    c.moveToFirst() &&
                        c.getString(1) == track.path &&
                        slice.startedAtMs >= c.getLong(2) &&
                        slice.startedAtMs - c.getLong(3) <= MERGE_GAP_MS
                )
                    mergeId = c.getLong(0)
            }
        val end = slice.startedAtMs + slice.durationMs
        val id = mergeId
        if (id != null) {
            db.execSQL(
                "UPDATE listening_sessions SET last_active_at_ms=MAX(last_active_at_ms,?),duration_ms=duration_ms+?,track_count=track_count+? WHERE id=?",
                arrayOf<Any>(end, slice.durationMs, plays, id),
            )
        } else {
            db.insertOrThrow(
                "listening_sessions",
                null,
                ContentValues().apply {
                    put("media_path", track.path)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("is_video", if (track.isVideo) 1 else 0)
                    put("listening_date", slice.date)
                    put("start_at_ms", slice.startedAtMs)
                    put("last_active_at_ms", end)
                    put("duration_ms", slice.durationMs)
                    put("track_count", plays)
                },
            )
        }
    }

    fun daily(
        db: SQLiteDatabase,
        startDate: String? = null,
        endDate: String? = null,
    ): List<ListeningDayStat> {
        val result = ArrayList<ListeningDayStat>()
        val where = if (startDate != null && endDate != null) " WHERE date>=? AND date<=?" else ""
        db.rawQuery(
                "SELECT date,duration_ms,track_count,play_count FROM daily_stats$where ORDER BY date",
                if (where.isEmpty()) null else arrayOf(startDate!!, endDate!!),
            )
            .use { c ->
                while (c.moveToNext()) result +=
                    ListeningDayStat(c.getString(0), c.getLong(1), c.getInt(2), c.getInt(3))
            }
        return result
    }

    fun sessions(db: SQLiteDatabase, date: String): List<ListeningSessionRecord> {
        val result = ArrayList<ListeningSessionRecord>()
        db.rawQuery(
                """SELECT id,media_path,title,artist,album,is_video,listening_date,start_at_ms,last_active_at_ms,duration_ms,track_count
            FROM listening_sessions WHERE listening_date=? ORDER BY start_at_ms DESC,id DESC""",
                arrayOf(date),
            )
            .use { c ->
                while (c.moveToNext()) result +=
                    ListeningSessionRecord(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getInt(5) != 0,
                        c.getString(6),
                        c.getLong(7),
                        c.getLong(8),
                        c.getLong(9),
                        c.getInt(10),
                    )
            }
        return result
    }

    fun top(
        db: SQLiteDatabase,
        startDate: String,
        endDate: String,
        limit: Int,
    ): List<TopListeningItem> {
        val result = ArrayList<TopListeningItem>()
        db.rawQuery(
                """SELECT s.media_path,MAX(s.title),MAX(s.artist),MAX(s.album),SUM(s.duration_ms) AS total_dur,
            (SELECT COUNT(DISTINCT playback_id) FROM listening_day_plays p WHERE p.path=s.media_path AND p.date>=? AND p.date<=?)+
            COALESCE((SELECT SUM(play_count) FROM legacy_listening_plays p WHERE p.path=s.media_path AND p.date>=? AND p.date<=?),0)
            FROM listening_sessions s WHERE s.listening_date>=? AND s.listening_date<=?
            GROUP BY s.media_path HAVING total_dur>0 ORDER BY total_dur DESC,s.media_path LIMIT ?""",
                arrayOf(
                    startDate,
                    endDate,
                    startDate,
                    endDate,
                    startDate,
                    endDate,
                    limit.coerceAtLeast(0).toString(),
                ),
            )
            .use { c ->
                while (c.moveToNext()) result +=
                    TopListeningItem(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getLong(4),
                        c.getInt(5),
                    )
            }
        return result
    }
}
