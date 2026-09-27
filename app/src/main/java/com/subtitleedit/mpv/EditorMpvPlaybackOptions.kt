package com.subtitleedit.mpv

/** Playback settings shared by the visible video player and the headless audio player. */
internal object EditorMpvPlaybackOptions {
    fun configure() {
        MPVLib.setOptionString("profile", "fast")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("audio-set-media-role", "yes")
        MPVLib.setOptionString("audio-display", "no")
        MPVLib.setOptionString("pause", "yes")
        MPVLib.setOptionString("osc", "no")
        MPVLib.setOptionString("input-default-bindings", "no")
        MPVLib.setOptionString("access-references", "no")
        MPVLib.setOptionString("load-unsafe-playlists", "no")
        MPVLib.setOptionString("sub-auto", "no")
        MPVLib.setOptionString("sid", "no")
        MPVLib.setOptionString("terminal", "no")
        MPVLib.setOptionString("msg-level", "all=warn")
        MPVLib.setOptionString("demuxer-max-bytes", (64 * 1024 * 1024).toString())
        MPVLib.setOptionString("demuxer-max-back-bytes", (32 * 1024 * 1024).toString())
    }

    fun configureAfterInit() {
        MPVLib.setOptionString("save-position-on-quit", "no")
        MPVLib.setOptionString("keep-open", "yes")
    }

    fun observe() {
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.DOUBLE)
        MPVLib.observeProperty("duration/full", MPVLib.MpvFormat.DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.FLAG)
        MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.FLAG)
        MPVLib.observeProperty("track-list", MPVLib.MpvFormat.NONE)
    }
}
