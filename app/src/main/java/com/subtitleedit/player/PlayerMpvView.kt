package com.subtitleedit.player

import android.content.Context
import android.content.res.Resources
import android.graphics.Point
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Surface
import android.view.TextureView
import com.subtitleedit.mpv.MPVLib
import com.subtitleedit.mpv.MpvPlayerHost
import com.subtitleedit.mpv.MpvVideoDecoding
import com.subtitleedit.util.SubtitleFontManager
import kotlin.math.roundToInt

/** Player output can disappear/reappear without stopping audio or deferring a media load. */
internal class PlayerMpvView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener, MpvPlayerHost {
    private val outputLock = Any()
    private var initialized = false
    private var videoOutputEnabled = true
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
            PlayerMpvOptions.configure(resources)
            // Audio must load even before Compose has supplied the first surface.
            MPVLib.setOptionString("vo", "null")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "yes")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("opengl-es", "yes")
            MpvVideoDecoding.configure(context)
            val result = MPVLib.init()
            check(result >= 0) { "libmpv 初始化失败：$result" }
            PlayerMpvOptions.configureAfterInit()
            PlayerMpvOptions.observe()
            initialized = true
            if (videoOutputEnabled) outputSurface?.let(::attachOutput)
        } catch (error: Throwable) {
            initialized = false
            runCatching { MPVLib.destroy() }
            throw error
        }
    }

    override fun playFile(filePath: String): Unit = synchronized(outputLock) {
        check(initialized) { "libmpv 尚未初始化" }
        MpvVideoDecoding.refresh(context)
        MPVLib.command(arrayOf("loadfile", filePath, "replace"))
    }

    override fun destroyPlayer(): Unit = synchronized(outputLock) {
        if (!initialized) return@synchronized
        initialized = false
        try {
            if (videoOutputEnabled && outputSurface != null) runCatching { MPVLib.detachSurface() }
            MPVLib.destroy()
        } finally {
            outputSurface?.release()
            outputSurface = null
        }
    }

    fun setVideoOutputEnabled(enabled: Boolean): Unit = synchronized(outputLock) {
        if (videoOutputEnabled == enabled) return@synchronized
        videoOutputEnabled = enabled
        val surface = outputSurface ?: return@synchronized
        if (initialized) {
            if (enabled) attachOutput(surface) else detachOutput()
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int): Unit = synchronized(outputLock) {
        outputWidth = width
        outputHeight = height
        outputSurface?.release()
        outputSurface = Surface(texture)
        if (initialized && videoOutputEnabled) attachOutput(checkNotNull(outputSurface))
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int): Unit = synchronized(outputLock) {
        val sizeChanged = outputWidth != width || outputHeight != height
        outputWidth = width
        outputHeight = height
        if (initialized && videoOutputEnabled && outputSurface != null && sizeChanged) {
            // mpv resizes the existing EGL output and redraws its current frame.
            updateSurfaceSize()
        }
    }

    fun pictureInPictureSourceRect(): Rect? = synchronized(outputLock) {
        if (!initialized || !videoOutputEnabled || outputSurface == null || width <= 0 || height <= 0) {
            return@synchronized null
        }
        val visible = Rect()
        val globalOrigin = Point()
        if (!getGlobalVisibleRect(visible, globalOrigin)) return@synchronized null
        val osdWidth = MPVLib.getPropertyInt("osd-dimensions/w")?.takeIf { it > 0 } ?: return@synchronized null
        val osdHeight = MPVLib.getPropertyInt("osd-dimensions/h")?.takeIf { it > 0 } ?: return@synchronized null
        val left = MPVLib.getPropertyInt("osd-dimensions/ml") ?: return@synchronized null
        val right = MPVLib.getPropertyInt("osd-dimensions/mr") ?: return@synchronized null
        val top = MPVLib.getPropertyInt("osd-dimensions/mt") ?: return@synchronized null
        val bottom = MPVLib.getPropertyInt("osd-dimensions/mb") ?: return@synchronized null
        val origin = IntArray(2).also(::getLocationOnScreen)
        visible.offset(origin[0] - globalOrigin.x, origin[1] - globalOrigin.y)
        val scaleX = width.toFloat() / osdWidth
        val scaleY = height.toFloat() / osdHeight
        Rect(
            origin[0] + (left * scaleX).roundToInt(), origin[1] + (top * scaleY).roundToInt(),
            origin[0] + ((osdWidth - right) * scaleX).roundToInt(),
            origin[1] + ((osdHeight - bottom) * scaleY).roundToInt()
        ).takeIf { it.intersect(visible) }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = synchronized(outputLock) {
        try {
            if (initialized && videoOutputEnabled && outputSurface != null) detachOutput()
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

    private fun detachOutput() {
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
    }

    private fun updateSurfaceSize() {
        if (outputWidth > 0 && outputHeight > 0) {
            MPVLib.setPropertyString("android-surface-size", "${outputWidth}x$outputHeight")
        }
    }
}

internal object PlayerMpvOptions {
    /** With window scaling disabled, mpv expects screen pixels rather than Android sp. */
    fun subtitleFontSizePx(size: Int, resources: Resources): Double = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, size.toFloat(), resources.displayMetrics
    ).toDouble()

    fun configure(resources: Resources) {
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
        MPVLib.setOptionString("sub-scale-by-window", "no")
        MPVLib.setOptionString("sub-scale-with-window", "yes")
        MPVLib.setOptionString("sub-font-size", subtitleFontSizePx(PlayerPreferences.DEFAULT_SUBTITLE_FONT_SIZE, resources).toString())
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
        MPVLib.observeProperty("video-params/rotate", MPVLib.MpvFormat.INT64)
        MPVLib.observeProperty("sid", MPVLib.MpvFormat.STRING)
        MPVLib.observeProperty("sub-text", MPVLib.MpvFormat.STRING)
    }
}
