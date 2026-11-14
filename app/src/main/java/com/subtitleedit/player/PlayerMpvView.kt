package com.subtitleedit.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.subtitleedit.mpv.MPVLib
import com.subtitleedit.mpv.MpvPlayerHost
import com.subtitleedit.util.SubtitleFontManager

/** Player output can disappear/reappear without stopping audio or deferring a media load. */
internal class PlayerMpvView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener, MpvPlayerHost {
    private val outputLock = Any()
    private var initialized = false
    private var outputSurface: Surface? = null
    private var outputWidth = 0
    private var outputHeight = 0

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun initialize(configDir: String, cacheDir: String): Unit = synchronized(outputLock) {
        if (initialized) return@synchronized
        check(MPVLib.create(context.applicationContext)) { "无法创建 libmpv 上下文" }
        try {
            MPVLib.setOptionString("config", "no")
            MPVLib.setOptionString("config-dir", configDir)
            MPVLib.setOptionString("gpu-shader-cache-dir", cacheDir)
            MPVLib.setOptionString("icc-cache-dir", cacheDir)
            val fontDir = SubtitleFontManager.writeFontConfig(context)
            MPVLib.setOptionString("sub-font-provider", "fontconfig")
            MPVLib.setOptionString("sub-fonts-dir", fontDir.absolutePath)
            PlayerMpvOptions.configure()
            // Audio must load even before Compose has supplied the first surface.
            MPVLib.setOptionString("vo", "null")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "yes")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("opengl-es", "yes")
            MPVLib.setOptionString("hwdec", "mediacodec-copy,mediacodec")
            MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            val result = MPVLib.init()
            check(result >= 0) { "libmpv 初始化失败：$result" }
            PlayerMpvOptions.configureAfterInit()
            PlayerMpvOptions.observe()
            initialized = true
            outputSurface?.let(::attachOutput)
        } catch (error: Throwable) {
            initialized = false
            runCatching { MPVLib.destroy() }
            throw error
        }
    }

    override fun playFile(filePath: String): Unit = synchronized(outputLock) {
        check(initialized) { "libmpv 尚未初始化" }
        MPVLib.command(arrayOf("loadfile", filePath, "replace"))
    }

    override fun destroyPlayer(): Unit = synchronized(outputLock) {
        if (!initialized) return@synchronized
        initialized = false
        try {
            if (outputSurface != null) runCatching { MPVLib.detachSurface() }
            MPVLib.destroy()
        } finally {
            outputSurface?.release()
            outputSurface = null
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int): Unit = synchronized(outputLock) {
        outputWidth = width
        outputHeight = height
        outputSurface?.release()
        outputSurface = Surface(texture)
        if (initialized) attachOutput(checkNotNull(outputSurface))
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int): Unit = synchronized(outputLock) {
        outputWidth = width
        outputHeight = height
        if (initialized && outputSurface != null) updateSurfaceSize()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = synchronized(outputLock) {
        try {
            if (initialized) {
                MPVLib.setPropertyString("vo", "null")
                MPVLib.setPropertyString("force-window", "no")
                if (outputSurface != null) MPVLib.detachSurface()
            }
        } finally {
            outputSurface?.release()
            outputSurface = null
        }
        true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun attachOutput(surface: Surface) {
        MPVLib.attachSurface(surface)
        updateSurfaceSize()
        MPVLib.setPropertyString("force-window", "yes")
        MPVLib.setPropertyString("vo", "gpu-next")
    }

    private fun updateSurfaceSize() {
        if (outputWidth > 0 && outputHeight > 0) {
            MPVLib.setPropertyString("android-surface-size", "${outputWidth}x$outputHeight")
        }
    }
}

internal object PlayerMpvOptions {
    fun configure() {
        MPVLib.setOptionString("profile", "fast")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("audio-set-media-role", "yes")
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
        MPVLib.setPropertyString("save-position-on-quit", "no")
        MPVLib.setPropertyString("keep-open", "yes")
        MPVLib.setPropertyString("keep-open-pause", "yes")
    }

    fun observe() {
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.DOUBLE)
        MPVLib.observeProperty("duration/full", MPVLib.MpvFormat.DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.FLAG)
        MPVLib.observeProperty("seeking", MPVLib.MpvFormat.FLAG)
        MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.FLAG)
        MPVLib.observeProperty("track-list", MPVLib.MpvFormat.NONE)
        MPVLib.observeProperty("sid", MPVLib.MpvFormat.STRING)
        MPVLib.observeProperty("sub-text", MPVLib.MpvFormat.STRING)
    }
}
