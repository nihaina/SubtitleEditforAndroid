package com.subtitleedit.mpv

/** Owns the single libmpv instance used by an editor playback session. */
internal interface MpvPlayerHost {
    fun initialize(configDir: String, cacheDir: String)
    fun playFile(filePath: String)
    fun destroyPlayer()
}
