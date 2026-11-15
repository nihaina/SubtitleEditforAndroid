package com.subtitleedit.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.subtitleedit.model.BrowserFileMetadata

/** Persisted text tags are reusable while both the file size and modification time match. */
internal data class IndexedAlbumMetadata(
    val path: String,
    val length: Long,
    val modifiedAtMs: Long,
    val metadata: MediaAlbumMetadata,
) {
    fun matches(file: BrowserFileMetadata): Boolean =
        path == file.path && length == file.size && modifiedAtMs == file.modifiedTimeMillis

    companion object {
        fun of(file: BrowserFileMetadata, metadata: MediaAlbumMetadata): IndexedAlbumMetadata =
            IndexedAlbumMetadata(file.path, file.size, file.modifiedTimeMillis, metadata)
    }
}

/** The caller owns transactions so scan batches can store base and classification tags together. */
internal object MediaAlbumMetadataStore {
    fun createTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS album_metadata(
            path TEXT PRIMARY KEY NOT NULL,file_length INTEGER NOT NULL,modified_at_ms INTEGER NOT NULL,
            album_artist TEXT NOT NULL DEFAULT '',composer TEXT NOT NULL DEFAULT '',year TEXT NOT NULL DEFAULT '',
            track_number TEXT NOT NULL DEFAULT '',disc_number TEXT NOT NULL DEFAULT '',genre TEXT NOT NULL DEFAULT '')"""
        )
    }

    fun read(db: SQLiteDatabase): Map<String, IndexedAlbumMetadata> {
        val entries = LinkedHashMap<String, IndexedAlbumMetadata>()
        db.rawQuery(
            "SELECT path,file_length,modified_at_ms,album_artist,composer,year,track_number,disc_number,genre FROM album_metadata",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0)
                entries[path] = IndexedAlbumMetadata(
                    path = path,
                    length = cursor.getLong(1),
                    modifiedAtMs = cursor.getLong(2),
                    metadata = MediaAlbumMetadata(
                        albumArtist = cursor.getString(3),
                        composer = cursor.getString(4),
                        year = cursor.getString(5),
                        trackNumber = cursor.getString(6),
                        discNumber = cursor.getString(7),
                        genre = cursor.getString(8),
                    ),
                )
            }
        }
        return entries
    }

    fun put(db: SQLiteDatabase, entry: IndexedAlbumMetadata) {
        db.insertWithOnConflict(
            "album_metadata",
            null,
            ContentValues().apply {
                put("path", entry.path)
                put("file_length", entry.length)
                put("modified_at_ms", entry.modifiedAtMs)
                put("album_artist", entry.metadata.albumArtist)
                put("composer", entry.metadata.composer)
                put("year", entry.metadata.year)
                put("track_number", entry.metadata.trackNumber)
                put("disc_number", entry.metadata.discNumber)
                put("genre", entry.metadata.genre)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}
