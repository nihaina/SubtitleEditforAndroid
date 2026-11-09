package com.subtitleedit.mpv

internal data class MpvSubtitleTrack(
    val ffIndex: Long,
    val codec: String,
    val language: String,
    val title: String,
    val isDefault: Boolean
)

/** The native result uses FFmpeg stream indices, never mpv's per-type track ids. */
internal object MpvSubtitleTrackDecoder {
    fun decode(fields: Array<String>): List<MpvSubtitleTrack> {
        require(fields.size % 5 == 0) { "Incomplete mpv subtitle track result" }
        return fields.asList().chunked(5).map { track ->
            val index = track[0].toLongOrNull()
            require(index != null && index >= 0) { "Missing FFmpeg stream index in mpv subtitle track" }
            require(track[4] == "0" || track[4] == "1") { "Invalid default flag in mpv subtitle track" }
            MpvSubtitleTrack(index, track[1], track[2], track[3], track[4] == "1")
        }.distinctBy { it.ffIndex }
    }
}
