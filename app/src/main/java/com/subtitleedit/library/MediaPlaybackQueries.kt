package com.subtitleedit.library

internal object MediaPlaybackQueries {
    private const val HISTORY_COLUMNS = "id,path,title,artist,album,is_video,duration_ms,position_ms,played_at_ms,play_count"
    const val RECENT = "SELECT $HISTORY_COLUMNS FROM playback_history ORDER BY played_at_ms DESC,id DESC LIMIT ?"
    const val MOST_PLAYED =
        "SELECT $HISTORY_COLUMNS FROM playback_history ORDER BY play_count DESC,played_at_ms DESC,id DESC LIMIT ?"

    private const val UNPLAYED = """NOT EXISTS (SELECT 1 FROM playback_history h WHERE h.path=candidate.path)
        AND NOT EXISTS (SELECT 1 FROM listening_sessions s WHERE s.media_path=candidate.path)"""

    // SQLite can merge the three ordered index streams and stop at LIMIT. Each arm filters its
    // own candidates, including source precedence, before participating in the merge.
    val NEVER_PLAYED = """SELECT $TRACK_COLUMNS FROM library_tracks candidate WHERE $UNPLAYED
        UNION ALL
        SELECT $TRACK_COLUMNS FROM favorites candidate
        WHERE NOT EXISTS (SELECT 1 FROM library_tracks l WHERE l.path=candidate.path) AND $UNPLAYED
        UNION ALL
        SELECT $TRACK_COLUMNS FROM playlist_items candidate
        WHERE NOT EXISTS (SELECT 1 FROM library_tracks l WHERE l.path=candidate.path)
        AND NOT EXISTS (SELECT 1 FROM favorites f WHERE f.path=candidate.path) AND $UNPLAYED
        AND candidate.id=(SELECT MIN(p.id) FROM playlist_items p WHERE p.path=candidate.path)
        ORDER BY title COLLATE NOCASE,path LIMIT ?"""
}
