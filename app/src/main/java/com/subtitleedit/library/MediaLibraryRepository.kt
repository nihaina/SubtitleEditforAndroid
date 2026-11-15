package com.subtitleedit.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Blocking storage API. Callers execute it on IO; disk metadata is never read inside a transaction.
 */
@WorkerThread
internal class MediaLibraryRepository
private constructor(
    private val helper: MediaLibraryDatabase,
    private val readMetadata: (File, Long) -> MediaLibraryTrack,
) : Closeable {
    private val writeLock = Any()
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes.asStateFlow()
    private val db
        get() = helper.writableDatabase

    private fun changed() {
        changes.update { it + 1L }
    }

    private fun <T> write(block: SQLiteDatabase.() -> T): T =
        synchronized(writeLock) { db.libraryTransaction(block) }

    private fun metadata(path: String, durationMs: Long = 0L): MediaLibraryTrack =
        readMetadata(File(path), durationMs)

    fun isFavorite(path: String): Boolean = db.exists("SELECT 1 FROM favorites WHERE path=?", path)

    fun getFavorites(): List<MediaLibraryTrack> =
        tracks("SELECT $TRACK_COLUMNS FROM favorites ORDER BY added_at_ms DESC,path")

    fun toggleFavorite(path: String, durationMs: Long = 0L): Boolean {
        val track = metadata(path, durationMs)
        val favorite = write {
            if (delete("favorites", "path=?", arrayOf(path)) > 0) false
            else {
                insertOrThrow("favorites", null, track.values())
                true
            }
        }
        changed()
        return favorite
    }

    fun removeFavorite(path: String) {
        if (write { delete("favorites", "path=?", arrayOf(path)) } > 0) changed()
    }

    fun getPlaylists(): List<MediaPlaylist> {
        val result = ArrayList<MediaPlaylist>()
        db.rawQuery(
                """SELECT p.id,p.name,p.is_system,p.created_at_ms,p.updated_at_ms,
            (SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id=p.id),
            (SELECT path FROM playlist_items i WHERE i.playlist_id=p.id ORDER BY sort_order,id LIMIT 1)
            FROM playlists p ORDER BY p.is_system DESC,p.updated_at_ms DESC,p.id DESC""",
                null,
            )
            .use { c ->
                while (c.moveToNext()) result +=
                    MediaPlaylist(
                        c.getLong(0),
                        c.getString(1),
                        c.getInt(2) != 0,
                        c.getLong(3),
                        c.getLong(4),
                        c.getInt(5),
                        c.getString(6),
                    )
            }
        return result
    }

    fun getPlaylistById(playlistId: Long): MediaPlaylist? =
        getPlaylists().firstOrNull { it.id == playlistId }

    fun getPlaylistTracks(playlistId: Long): List<MediaLibraryTrack> =
        tracks(
            "SELECT $TRACK_COLUMNS FROM playlist_items WHERE playlist_id=? ORDER BY sort_order,id",
            playlistId.toString(),
        )

    fun getPlaylistIdsContainingTrack(path: String): Set<Long> {
        val result = linkedSetOf<Long>()
        db.rawQuery(
                "SELECT i.playlist_id FROM playlist_items i JOIN playlists p ON p.id=i.playlist_id WHERE i.path=?",
                arrayOf(path),
            )
            .use { c -> while (c.moveToNext()) result += c.getLong(0) }
        return result
    }

    fun createPlaylist(name: String): Long? {
        val normalized = name.trim().takeIf { it.isNotEmpty() } ?: return null
        val now = System.currentTimeMillis()
        val id = write {
            insertWithOnConflict(
                "playlists",
                null,
                ContentValues().apply {
                    put("name", normalized)
                    put("is_system", 0)
                    put("created_at_ms", now)
                    put("updated_at_ms", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            rawQuery("SELECT id FROM playlists WHERE name=? AND is_system=0", arrayOf(normalized))
                .use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }
        if (id != null) changed()
        return id
    }

    fun renamePlaylist(playlistId: Long, newName: String): Boolean {
        val normalized = newName.trim().takeIf { it.isNotEmpty() } ?: return false
        val success = write {
            if (
                exists(
                    "SELECT 1 FROM playlists WHERE name=? AND id!=?",
                    normalized,
                    playlistId.toString(),
                )
            )
                false
            else
                update(
                    "playlists",
                    ContentValues().apply {
                        put("name", normalized)
                        put("updated_at_ms", System.currentTimeMillis())
                    },
                    "id=? AND is_system=0",
                    arrayOf(playlistId.toString()),
                ) > 0
        }
        if (success) changed()
        return success
    }

    fun deletePlaylist(playlistId: Long) {
        val deleted = write {
            if (
                !exists("SELECT 1 FROM playlists WHERE id=? AND is_system=0", playlistId.toString())
            )
                false
            else {
                delete("playlist_items", "playlist_id=?", arrayOf(playlistId.toString()))
                delete("playlists", "id=?", arrayOf(playlistId.toString()))
                true
            }
        }
        if (deleted) changed()
    }

    fun addTrackToPlaylist(playlistId: Long, path: String, durationMs: Long = 0L): Boolean =
        addTracksToPlaylists(listOf(playlistId), path, durationMs) > 0

    fun addTracksToPlaylists(
        playlistIds: Collection<Long>,
        path: String,
        durationMs: Long = 0L,
    ): Int {
        if (playlistIds.isEmpty()) return 0
        val track = metadata(path, durationMs)
        val added = write {
            var count = 0
            for (id in playlistIds.distinct()) {
                if (!exists("SELECT 1 FROM playlists WHERE id=? AND is_system=0", id.toString()))
                    continue
                val order =
                    rawQuery(
                            "SELECT COALESCE(MAX(sort_order)+1,0) FROM playlist_items WHERE playlist_id=?",
                            arrayOf(id.toString()),
                        )
                        .use {
                            it.moveToFirst()
                            it.getInt(0)
                        }
                val row =
                    insertWithOnConflict(
                        "playlist_items",
                        null,
                        track.values().apply {
                            put("playlist_id", id)
                            put("sort_order", order)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                if (row >= 0L) {
                    count++
                    execSQL(
                        "UPDATE playlists SET updated_at_ms=? WHERE id=?",
                        arrayOf(System.currentTimeMillis(), id),
                    )
                }
            }
            count
        }
        if (added > 0) changed()
        return added
    }

    fun removeTrackFromPlaylist(playlistId: Long, path: String) {
        val removed = write {
            val count =
                delete(
                    "playlist_items",
                    "playlist_id=? AND path=?",
                    arrayOf(playlistId.toString(), path),
                )
            if (count > 0)
                execSQL(
                    "UPDATE playlists SET updated_at_ms=? WHERE id=?",
                    arrayOf(System.currentTimeMillis(), playlistId),
                )
            count
        }
        if (removed > 0) changed()
    }

    fun saveCurrentQueue(paths: List<String>, currentIndex: Int) {
        val selected = paths.getOrNull(currentIndex)
        val unique = paths.distinct()
        val now = System.currentTimeMillis()
        write {
            delete("current_queue", null, null)
            unique.forEachIndexed { index, path ->
                val file = File(path)
                insertOrThrow(
                    "current_queue",
                    null,
                    ContentValues().apply {
                        put("sort_order", index)
                        put("path", path)
                        put("title", file.name)
                        put("is_video", if (FileTypePolicy.isVideo(file)) 1 else 0)
                        put(
                            "is_current",
                            if (path == selected || selected == null && index == 0) 1 else 0,
                        )
                        put("updated_at_ms", now)
                    },
                )
            }
        }
        changed()
    }

    fun getCurrentQueue(): Pair<List<MediaLibraryTrack>, Int> {
        val result = ArrayList<MediaLibraryTrack>()
        var currentIndex = 0
        db.rawQuery(
                "SELECT path,title,is_video,is_current,updated_at_ms FROM current_queue ORDER BY sort_order",
                null,
            )
            .use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(3) != 0) currentIndex = result.size
                    result +=
                        MediaLibraryTrack(
                            c.getString(0),
                            c.getString(1),
                            isVideo = c.getInt(2) != 0,
                            addedAtMs = c.getLong(4),
                        )
                }
            }
        return result to currentIndex
    }

    /** The playback id belongs to one actual play-through, including pauses and view recreation. */
    fun recordPlaybackProgress(
        path: String,
        playbackId: String,
        durationMs: Long,
        positionMs: Long,
        playedAtMs: Long = System.currentTimeMillis(),
    ) {
        require(playbackId.isNotBlank())
        val track = cachedMetadata(path, durationMs)
        write {
            val insertedRun =
                insertWithOnConflict(
                    "playback_runs",
                    null,
                    ContentValues().apply {
                        put("playback_id", playbackId)
                        put("path", path)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                ) >= 0L
            if (
                !exists(
                    "SELECT 1 FROM playback_runs WHERE playback_id=? AND path=?",
                    playbackId,
                    path,
                )
            )
                return@write
            saveHistory(track, positionMs, playedAtMs, if (insertedRun) 1 else 0)
        }
        changed()
    }

    /** Compatibility for existing consumers; new player code uses stable playback ids. */
    fun recordPlaybackHistory(
        path: String,
        durationMs: Long,
        positionMs: Long,
        incrementPlayCount: Boolean = false,
    ) {
        val track = cachedMetadata(path, durationMs)
        write {
            saveHistory(
                track,
                positionMs,
                System.currentTimeMillis(),
                if (incrementPlayCount) 1 else 0,
            )
        }
        changed()
    }

    private fun SQLiteDatabase.saveHistory(
        track: MediaLibraryTrack,
        positionMs: Long,
        now: Long,
        increment: Int,
    ) {
        insertWithOnConflict(
            "playback_history",
            null,
            track.values().apply {
                remove("added_at_ms")
                put("position_ms", 0L)
                put("played_at_ms", 0L)
                put("play_count", 0)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        execSQL(
            """UPDATE playback_history SET title=?,artist=?,album=?,is_video=?,duration_ms=MAX(duration_ms,?),
            position_ms=CASE WHEN played_at_ms<=? THEN ? ELSE position_ms END,
            played_at_ms=MAX(played_at_ms,?),play_count=MAX(1,play_count+?) WHERE path=?""",
            arrayOf<Any>(
                track.title,
                track.artist,
                track.album,
                if (track.isVideo) 1 else 0,
                track.durationMs,
                now,
                positionMs.coerceAtLeast(0L),
                now,
                increment,
                track.path,
            ),
        )
    }

    fun getPlaybackHistory(limit: Int = 200): List<PlaybackHistoryEntry> {
        val result = ArrayList<PlaybackHistoryEntry>()
        db.rawQuery(
                "SELECT id,path,title,artist,album,is_video,duration_ms,position_ms,played_at_ms,play_count FROM playback_history ORDER BY played_at_ms DESC,id DESC LIMIT ?",
                arrayOf(limit.coerceAtLeast(0).toString()),
            )
            .use { c ->
                while (c.moveToNext()) result +=
                    PlaybackHistoryEntry(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getInt(5) != 0,
                        c.getLong(6),
                        c.getLong(7),
                        c.getLong(8),
                        c.getInt(9),
                    )
            }
        return result
    }

    fun getMostPlayed(limit: Int = 100): List<PlaybackHistoryEntry> {
        val result = ArrayList<PlaybackHistoryEntry>()
        db.rawQuery(
                "SELECT id,path,title,artist,album,is_video,duration_ms,position_ms,played_at_ms,play_count FROM playback_history ORDER BY play_count DESC,played_at_ms DESC,id DESC LIMIT ?",
                arrayOf(limit.coerceAtLeast(0).toString()),
            )
            .use { c ->
                while (c.moveToNext()) result +=
                    PlaybackHistoryEntry(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getInt(5) != 0,
                        c.getLong(6),
                        c.getLong(7),
                        c.getLong(8),
                        c.getInt(9),
                    )
            }
        return result
    }

    /** Prefer indexed metadata, then favorites, then the first playlist entry for each path. */
    fun getNeverPlayedTracks(limit: Int = 200): List<MediaLibraryTrack> =
        tracks(
            """SELECT path,title,artist,album,is_video,duration_ms,added_at_ms FROM (
            SELECT path,title,artist,album,is_video,duration_ms,added_at_ms FROM library_tracks
            UNION ALL
            SELECT f.path,f.title,f.artist,f.album,f.is_video,f.duration_ms,f.added_at_ms FROM favorites f
            WHERE NOT EXISTS (SELECT 1 FROM library_tracks l WHERE l.path=f.path)
            UNION ALL
            SELECT p.path,p.title,p.artist,p.album,p.is_video,p.duration_ms,p.added_at_ms FROM playlist_items p
            WHERE p.id IN (SELECT MIN(id) FROM playlist_items GROUP BY path)
            AND NOT EXISTS (SELECT 1 FROM library_tracks l WHERE l.path=p.path)
            AND NOT EXISTS (SELECT 1 FROM favorites f WHERE f.path=p.path)) candidates
            WHERE NOT EXISTS (SELECT 1 FROM playback_history h WHERE h.path=candidates.path)
            AND NOT EXISTS (SELECT 1 FROM listening_sessions s WHERE s.media_path=candidates.path)
            ORDER BY title COLLATE NOCASE,path LIMIT ?""",
            limit.coerceAtLeast(0).toString(),
        )

    fun removePlaybackHistory(path: String) {
        write {
            delete("playback_history", "path=?", arrayOf(path))
            delete("playback_runs", "path=?", arrayOf(path))
        }
        changed()
    }

    fun clearPlaybackHistory() {
        write {
            delete("playback_history", null, null)
            delete("playback_runs", null, null)
        }
        changed()
    }

    /**
     * Iterative traversal avoids symlink cycles; metadata and cancellation remain outside write
     * locks.
     */
    fun scanDirectoriesToLibrary(
        directories: List<File>,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): Int {
        val extensions =
            FileUtils.AUDIO_EXTENSIONS +
                setOf("opus", "ac3", "amr") +
                FileTypePolicy.videoExtensions
        val visitedDirs = HashSet<String>()
        val visitedFiles = HashSet<String>()
        val pending = ArrayDeque<File>()
        directories.forEach { pending.addLast(it) }
        val batch = ArrayList<MediaLibraryTrack>(24)
        var count = 0
        fun checkCancelled() {
            if (isCancelled()) throw CancellationException("Media scan cancelled")
        }
        fun commitBatch() {
            if (batch.isEmpty()) return
            checkCancelled()
            write { batch.forEach { storeIndexedTrack(it) } }
            batch.clear()
            changed()
        }
        while (pending.isNotEmpty()) {
            checkCancelled()
            val directory = pending.removeFirst()
            if (!directory.isDirectory || !directory.canRead()) continue
            val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: continue
            if (!visitedDirs.add(canonical)) continue
            directory.listFiles()?.forEach { file ->
                checkCancelled()
                if (file.name.startsWith('.')) return@forEach
                if (file.isDirectory) pending.addLast(file)
                else if (file.isFile && file.extension.lowercase(Locale.ROOT) in extensions) {
                    val path = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
                    if (!visitedFiles.add(path)) return@forEach
                    batch += metadata(file.absolutePath)
                    count++
                    if (batch.size >= 24) commitBatch()
                }
            }
        }
        commitBatch()
        return count
    }

    fun indexTrack(path: String, durationMs: Long = 0L) {
        if (!File(path).isFile) return
        val track = metadata(path, durationMs)
        write { storeIndexedTrack(track) }
        changed()
    }

    private fun SQLiteDatabase.storeIndexedTrack(track: MediaLibraryTrack) {
        val added =
            rawQuery("SELECT added_at_ms FROM library_tracks WHERE path=?", arrayOf(track.path))
                .use { if (it.moveToFirst()) it.getLong(0) else track.addedAtMs }
        insertWithOnConflict(
            "library_tracks",
            null,
            track.values().apply { put("added_at_ms", added) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        val values =
            track.values().apply {
                remove("path")
                remove("added_at_ms")
            }
        for (table in listOf("favorites", "playlist_items", "playback_history")) update(
            table,
            values,
            "path=?",
            arrayOf(track.path),
        )
    }

    /** Scanned/indexed files only; collections and listening history do not expand the catalog. */
    fun getIndexedTracks(): List<MediaLibraryTrack> =
        tracks("SELECT $TRACK_COLUMNS FROM library_tracks ORDER BY path")

    fun searchLibrary(query: String): List<MediaLibraryTrack> {
        val normalized = query.trim().lowercase(Locale.ROOT)
        val merged = LinkedHashMap<String, MediaLibraryTrack>()
        for ((table, dateColumn) in
            listOf(
                "library_tracks" to "added_at_ms",
                "favorites" to "added_at_ms",
                "playlist_items" to "added_at_ms",
                "playback_history" to "played_at_ms",
            )) {
            for (track in
                tracks(
                    "SELECT path,title,artist,album,is_video,duration_ms,$dateColumn FROM $table"
                )) {
                if (
                    normalized.isEmpty() ||
                        listOf(track.title, track.artist, track.album, track.path).any {
                            it.lowercase(Locale.ROOT).contains(normalized)
                        }
                )
                    merged.putIfAbsent(track.path, track)
            }
        }
        return merged.values.sortedWith(
            compareBy<MediaLibraryTrack> { it.displayTitle.lowercase(Locale.ROOT) }
                .thenBy { it.path }
        )
    }

    private fun cachedMetadata(path: String, durationMs: Long): MediaLibraryTrack {
        val existing =
            tracks("SELECT $TRACK_COLUMNS FROM library_tracks WHERE path=?", path).firstOrNull()
                ?: tracks("SELECT $TRACK_COLUMNS FROM favorites WHERE path=?", path).firstOrNull()
        return existing?.copy(durationMs = maxOf(durationMs, existing.durationMs))
            ?: metadata(path, durationMs)
    }

    private fun tracks(sql: String, vararg args: String): List<MediaLibraryTrack> {
        val result = ArrayList<MediaLibraryTrack>()
        db.rawQuery(sql, if (args.isEmpty()) null else arrayOf(*args)).use { c ->
            while (c.moveToNext()) result += c.libraryTrack()
        }
        return result
    }

    fun recordListeningInterval(
        path: String,
        playbackId: String,
        startedAtMs: Long,
        durationMs: Long,
    ) {
        if (durationMs <= 0L) return
        val track = cachedMetadata(path, 0L)
        val slices = ListeningDay.split(startedAtMs, durationMs)
        val recorded = write {
            ListeningStatsStore.record(this, track, playbackId, startedAtMs, durationMs, slices)
        }
        if (recorded) changed()
    }

    fun recordListeningDelta(
        mediaPath: String,
        deltaMs: Long,
        countTrack: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (deltaMs > 0L)
            recordListeningInterval(
                mediaPath,
                if (countTrack) UUID.randomUUID().toString()
                else "legacy:$mediaPath:${ListeningDay.dateOf(nowMs)}",
                nowMs - deltaMs,
                deltaMs,
            )
    }

    fun getAllDailyStats(): List<ListeningDayStat> = ListeningStatsStore.daily(db)

    fun getDailyStatsBetween(startDate: String, endDate: String): List<ListeningDayStat> =
        ListeningStatsStore.daily(db, startDate, endDate)

    fun getSessionsForDate(date: String): List<ListeningSessionRecord> =
        ListeningStatsStore.sessions(db, date)

    fun getTopListeningItems(
        startDate: String,
        endDate: String,
        limit: Int = 5,
    ): List<TopListeningItem> = ListeningStatsStore.top(db, startDate, endDate, limit)

    override fun close() {
        helper.close()
    }

    companion object {
        @Volatile private var instance: MediaLibraryRepository? = null

        fun getInstance(context: Context): MediaLibraryRepository =
            instance
                ?: synchronized(this) {
                    instance
                        ?: MediaLibraryRepository(
                                MediaLibraryDatabase(context),
                                PlayerTrackMetadataReader::readLibraryTrack,
                            )
                            .also { instance = it }
                }

        /**
         * Isolated named databases let instrumentation verify migrations without touching user
         * data.
         */
        fun open(
            context: Context,
            name: String,
            metadataReader: (File, Long) -> MediaLibraryTrack =
                PlayerTrackMetadataReader::readLibraryTrack,
        ): MediaLibraryRepository =
            MediaLibraryRepository(MediaLibraryDatabase(context, name), metadataReader)
    }
}

internal fun MediaLibraryTrack.values(): ContentValues =
    ContentValues().apply {
        put("path", path)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("is_video", if (isVideo) 1 else 0)
        put("duration_ms", durationMs)
        put("added_at_ms", addedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis())
    }
