package com.subtitleedit.player

/** Browsing context used when advancing beyond the last track of a queue. */
internal enum class PlayerQueueSourceKind {
    DIRECTORY, PLAYLIST, ALBUM, SINGER, ARTIST, COMPOSER, GENRE
}

internal data class PlayerQueueSource(
    val kind: PlayerQueueSourceKind,
    val key: String,
    val roots: List<String> = emptyList()
)

internal data class PlayerResolvedQueue(
    val source: PlayerQueueSource,
    val tracks: List<PlayerTrack>,
    val title: String
)
