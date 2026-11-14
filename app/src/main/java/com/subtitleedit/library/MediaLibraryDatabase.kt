package com.subtitleedit.library

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Version two extends the original tables in place; user collections are never rebuilt. */
internal class MediaLibraryDatabase(context: Context, name: String = NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, 2) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createOriginalTables(db)
        createAccountingTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createAccountingTables(db)
            // Seed membership without rewriting the legacy totals and listening history.
            db.execSQL(
                "INSERT OR IGNORE INTO listening_day_tracks(date,path) SELECT listening_date,media_path FROM listening_sessions"
            )
            db.execSQL(
                """INSERT OR IGNORE INTO legacy_listening_plays(date,path,play_count)
                SELECT listening_date,media_path,SUM(track_count) FROM listening_sessions GROUP BY listening_date,media_path"""
            )
        }
    }

    private fun createOriginalTables(db: SQLiteDatabase) {
        val media =
            "path TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,artist TEXT NOT NULL DEFAULT ''," +
                "album TEXT NOT NULL DEFAULT '',is_video INTEGER NOT NULL DEFAULT 0,duration_ms INTEGER NOT NULL DEFAULT 0,added_at_ms INTEGER NOT NULL"
        db.execSQL("CREATE TABLE favorites($media)")
        db.execSQL("CREATE TABLE library_tracks($media)")
        db.execSQL(
            """CREATE TABLE playlists(
            id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,is_system INTEGER NOT NULL DEFAULT 0,
            created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE playlist_items(
            id INTEGER PRIMARY KEY AUTOINCREMENT,playlist_id INTEGER NOT NULL,path TEXT NOT NULL,title TEXT NOT NULL,
            artist TEXT NOT NULL DEFAULT '',album TEXT NOT NULL DEFAULT '',is_video INTEGER NOT NULL DEFAULT 0,
            duration_ms INTEGER NOT NULL DEFAULT 0,sort_order INTEGER NOT NULL DEFAULT 0,added_at_ms INTEGER NOT NULL,
            UNIQUE(playlist_id,path))"""
        )
        db.execSQL("CREATE INDEX idx_playlist_items_pid ON playlist_items(playlist_id)")
        db.execSQL(
            """CREATE TABLE current_queue(
            sort_order INTEGER PRIMARY KEY,path TEXT NOT NULL,title TEXT NOT NULL,is_video INTEGER NOT NULL DEFAULT 0,
            is_current INTEGER NOT NULL DEFAULT 0,updated_at_ms INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE playback_history(
            id INTEGER PRIMARY KEY AUTOINCREMENT,path TEXT NOT NULL UNIQUE,title TEXT NOT NULL,
            artist TEXT NOT NULL DEFAULT '',album TEXT NOT NULL DEFAULT '',is_video INTEGER NOT NULL DEFAULT 0,
            duration_ms INTEGER NOT NULL DEFAULT 0,position_ms INTEGER NOT NULL DEFAULT 0,
            played_at_ms INTEGER NOT NULL,play_count INTEGER NOT NULL DEFAULT 1)"""
        )
        db.execSQL("CREATE INDEX idx_playback_history_time ON playback_history(played_at_ms DESC)")
        db.execSQL(
            """CREATE TABLE daily_stats(date TEXT PRIMARY KEY NOT NULL,duration_ms INTEGER NOT NULL DEFAULT 0,
            track_count INTEGER NOT NULL DEFAULT 0,play_count INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE listening_sessions(
            id INTEGER PRIMARY KEY AUTOINCREMENT,media_path TEXT NOT NULL,title TEXT NOT NULL,
            artist TEXT NOT NULL DEFAULT '',album TEXT NOT NULL DEFAULT '',is_video INTEGER NOT NULL DEFAULT 0,
            listening_date TEXT NOT NULL,start_at_ms INTEGER NOT NULL,last_active_at_ms INTEGER NOT NULL,
            duration_ms INTEGER NOT NULL DEFAULT 0,track_count INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX idx_listening_sessions_date ON listening_sessions(listening_date)")
    }

    private fun createAccountingTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS playback_runs(playback_id TEXT PRIMARY KEY NOT NULL,path TEXT NOT NULL)"
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS listening_intervals(
            playback_id TEXT NOT NULL,started_at_ms INTEGER NOT NULL,duration_ms INTEGER NOT NULL,
            PRIMARY KEY(playback_id,started_at_ms,duration_ms))"""
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS listening_day_tracks(date TEXT NOT NULL,path TEXT NOT NULL,PRIMARY KEY(date,path))"
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS listening_day_plays(
            date TEXT NOT NULL,playback_id TEXT NOT NULL,path TEXT NOT NULL,PRIMARY KEY(date,playback_id))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS legacy_listening_plays(
            date TEXT NOT NULL,path TEXT NOT NULL,play_count INTEGER NOT NULL,PRIMARY KEY(date,path))"""
        )
    }

    companion object {
        const val NAME = "media_library.db"
    }
}

internal inline fun <T> SQLiteDatabase.libraryTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransactionNonExclusive()
    try {
        val result = block()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}

internal fun SQLiteDatabase.exists(sql: String, vararg args: String): Boolean =
    rawQuery(sql, arrayOf(*args)).use { it.moveToFirst() }

internal fun Cursor.libraryTrack(): MediaLibraryTrack =
    MediaLibraryTrack(
        getString(0),
        getString(1),
        getString(2),
        getString(3),
        getInt(4) != 0,
        getLong(5),
        getLong(6),
    )

internal const val TRACK_COLUMNS = "path,title,artist,album,is_video,duration_ms,added_at_ms"
