package com.subtitleedit.mpv

import android.content.Context

/** Runs libmpv for audio without creating or waiting for an Android video surface. */
internal class EditorMpvAudioPlayer(private val context: Context) : MpvPlayerHost {
    private var initialized = false

    override fun initialize(configDir: String, cacheDir: String) {
        if (initialized) return
        check(MPVLib.create(context.applicationContext)) { "无法创建 libmpv 上下文" }
        try {
            MPVLib.setOptionString("config", "no")
            MPVLib.setOptionString("config-dir", configDir)
            MPVLib.setOptionString("gpu-shader-cache-dir", cacheDir)
            MPVLib.setOptionString("icc-cache-dir", cacheDir)
            EditorMpvPlaybackOptions.configure()
            MPVLib.setOptionString("vo", "null")
            MPVLib.setOptionString("vid", "no")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "once")
            val initResult = MPVLib.init()
            if (initResult < 0) error("libmpv 初始化失败：$initResult")
            EditorMpvPlaybackOptions.configureAfterInit()
            EditorMpvPlaybackOptions.observe()
            initialized = true
        } catch (error: Throwable) {
            MPVLib.destroy()
            throw error
        }
    }

    override fun playFile(filePath: String) {
        check(initialized) { "libmpv 尚未初始化" }
        MPVLib.command(arrayOf("loadfile", filePath, "replace"))
    }

    override fun destroyPlayer() {
        if (!initialized) return
        initialized = false
        MPVLib.destroy()
    }
}
