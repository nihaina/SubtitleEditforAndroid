package com.subtitleedit.player

/** Initial selection follows the displayed list, while an explicit choice (including off) wins. */
internal object PlayerSubtitleSelection {
    fun initial(
        explicit: PlayerSubtitleOption?,
        internalTracks: List<PlayerSubtitleOption>,
        fallback: PlayerSubtitleOption?
    ): PlayerSubtitleOption = explicit
        ?: internalTracks.firstOrNull { it.kind == PlayerSubtitleKind.INTERNAL }
        ?: fallback?.takeIf { it.kind == PlayerSubtitleKind.EXTERNAL }
        ?: PlayerSubtitleOption("none", "", PlayerSubtitleKind.NONE)
}
