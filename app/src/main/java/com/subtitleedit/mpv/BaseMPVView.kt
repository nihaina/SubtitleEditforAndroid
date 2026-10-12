package com.subtitleedit.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView

internal abstract class BaseMPVView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener, MpvPlayerHost {
    private var pendingFilePath: String? = null
    private var voInUse = "gpu-next"
    private var initialized = false
    private var outputSurface: Surface? = null

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun initialize(configDir: String, cacheDir: String) {
        if (initialized) return
        check(MPVLib.create(context.applicationContext)) { "无法创建 libmpv 上下文" }
        try {
            MPVLib.setOptionString("config", "no")
            MPVLib.setOptionString("config-dir", configDir)
            MPVLib.setOptionString("gpu-shader-cache-dir", cacheDir)
            MPVLib.setOptionString("icc-cache-dir", cacheDir)
            initOptions()
            val initResult = MPVLib.init()
            if (initResult < 0) error("libmpv 初始化失败：$initResult")
            postInitOptions()
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "once")
            observeProperties()
            initialized = true
            // The texture can become available before libmpv is initialized.
            if (isAvailable) {
                surfaceTexture?.let { attachOutputSurface(it, width, height) }
            }
        } catch (error: Throwable) {
            initialized = false
            try {
                if (outputSurface != null) runCatching { MPVLib.detachSurface() }
                MPVLib.destroy()
            } finally {
                releaseOutputSurface()
            }
            throw error
        }
    }

    override fun destroyPlayer() {
        if (!initialized) return
        initialized = false
        try {
            if (outputSurface != null) runCatching { MPVLib.detachSurface() }
            MPVLib.destroy()
        } finally {
            releaseOutputSurface()
            pendingFilePath = null
        }
    }

    override fun playFile(filePath: String) {
        check(initialized) { "libmpv 尚未初始化" }
        if (outputSurface?.isValid == true) {
            MPVLib.command(arrayOf("loadfile", filePath, "replace"))
        } else {
            pendingFilePath = filePath
        }
    }

    protected fun setVo(vo: String) {
        voInUse = vo
        MPVLib.setOptionString("vo", vo)
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()
    protected abstract fun observeProperties()

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (initialized) attachOutputSurface(surface, width, height)
    }

    private fun attachOutputSurface(texture: SurfaceTexture, width: Int, height: Int) {
        if (outputSurface == null) {
            outputSurface = Surface(texture)
            MPVLib.attachSurface(checkNotNull(outputSurface))
        }
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setPropertyString("vo", voInUse)
        updateSurfaceSize(width, height)
        pendingFilePath?.let { path ->
            MPVLib.command(arrayOf("loadfile", path, "replace"))
            pendingFilePath = null
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (initialized && outputSurface != null) updateSurfaceSize(width, height)
    }

    private fun updateSurfaceSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            MPVLib.setPropertyString("android-surface-size", "${width}x$height")
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        try {
            if (initialized) {
                MPVLib.setPropertyString("vo", "null")
                MPVLib.setPropertyString("force-window", "no")
                if (outputSurface != null) MPVLib.detachSurface()
            }
        } finally {
            releaseOutputSurface()
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun releaseOutputSurface() {
        outputSurface?.release()
        outputSurface = null
    }
}
