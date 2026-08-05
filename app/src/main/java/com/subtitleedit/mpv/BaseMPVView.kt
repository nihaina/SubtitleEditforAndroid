package com.subtitleedit.mpv

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

internal abstract class BaseMPVView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private var pendingFilePath: String? = null
    private var voInUse = "gpu-next"
    private var initialized = false

    fun initialize(configDir: String, cacheDir: String) {
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
        } catch (error: Throwable) {
            MPVLib.destroy()
            throw error
        }
        postInitOptions()
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("idle", "once")
        holder.addCallback(this)
        observeProperties()
        initialized = true
    }

    fun destroyPlayer() {
        if (!initialized) return
        holder.removeCallback(this)
        if (holder.surface?.isValid == true) runCatching { MPVLib.detachSurface() }
        MPVLib.destroy()
        initialized = false
    }

    fun playFile(filePath: String) {
        if (holder.surface?.isValid == true) {
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        MPVLib.attachSurface(holder.surface)
        MPVLib.setOptionString("force-window", "yes")
        pendingFilePath?.let { path ->
            MPVLib.command(arrayOf("loadfile", path, "replace"))
            pendingFilePath = null
        } ?: MPVLib.setPropertyString("vo", voInUse)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
    }
}
