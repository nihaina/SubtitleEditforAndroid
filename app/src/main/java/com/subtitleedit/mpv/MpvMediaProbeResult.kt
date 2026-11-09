package com.subtitleedit.mpv

internal data class MpvMediaProbeResult(
    val subtitleTracks: List<MpvSubtitleTrack>,
    val lyricMetadata: List<Pair<String, String>>
)

/** A separate metadata section prevents lyric tags from becoming fabricated stream indices. */
internal object MpvMediaProbeResultDecoder {
    fun decode(fields: Array<String>): MpvMediaProbeResult {
        require(fields.size >= 3 && fields[0] == "mpv-media-1") { "Invalid mpv media probe result" }
        val tracks = fields[1].toIntOrNull()
        val tags = fields[2].toIntOrNull()
        require(tracks != null && tracks in 0..10000 && tags != null && tags in 0..10000) { "Invalid mpv media item count" }
        val tagOffset = 3 + tracks * 5
        require(fields.size == tagOffset + tags * 2) { "Incomplete mpv media probe result" }
        return MpvMediaProbeResult(MpvSubtitleTrackDecoder.decode(fields.copyOfRange(3, tagOffset)),
            fields.asList().subList(tagOffset, fields.size).chunked(2).map { it[0] to it[1] })
    }
}
