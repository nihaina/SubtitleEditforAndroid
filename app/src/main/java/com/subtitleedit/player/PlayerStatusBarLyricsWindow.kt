package com.subtitleedit.player

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.roundToInt

/** A read-only application overlay placed in the status-bar area, under Android's system UI layer. */
internal class PlayerStatusBarLyricsWindow(context: Context) {
    private val app = context.applicationContext
    private var attached: Overlay? = null
    private var cue: PlayerOverlaySubtitleCue? = null
    private var settings = PlayerStatusBarLyricsSettings()

    fun render(cue: PlayerOverlaySubtitleCue?, settings: PlayerStatusBarLyricsSettings) {
        if (!settings.enabled || cue == null || !Settings.canDrawOverlays(app)) {
            hide()
            return
        }
        val needsLayout = this.settings != settings || this.cue?.text != cue.text ||
            (this.cue?.timing == null) != (cue.timing == null)
        this.settings = settings
        this.cue = cue
        val current = attached
        if (current == null || current.view.parent == null) {
            attach(cue)
        } else {
            current.view.applyStyle(settings.style)
            current.view.setCaption(cue.text, cue)
            if (needsLayout) place(current)
        }
    }

    @Suppress("RtlHardcoded")
    private fun attach(cue: PlayerOverlaySubtitleCue) {
        try {
            val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else app
            val manager = windowContext.getSystemService(WindowManager::class.java) ?: return
            val view = PlayerOverlayCaptionView(windowContext, compact = true)
            view.applyStyle(settings.style)
            view.setCaption(cue.text, cue)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                setTitle(WINDOW_TITLE)
                gravity = Gravity.TOP or Gravity.LEFT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alpha = windowContext.getSystemService(InputManager::class.java)?.maximumObscuringOpacityForTouch ?: 0.8f
                }
            }
            val overlay = Overlay(manager, display, view, params)
            place(overlay)
            manager.addView(view, params)
            attached = overlay
            if (!Settings.canDrawOverlays(app)) hide()
        } catch (error: WindowManager.BadTokenException) {
            Log.w(WINDOW_TITLE, "Unable to attach status bar lyrics", error)
        } catch (error: WindowManager.InvalidDisplayException) {
            Log.w(WINDOW_TITLE, "Status bar lyrics display unavailable", error)
        } catch (error: SecurityException) {
            hide()
            Log.w(WINDOW_TITLE, "Status bar lyrics permission unavailable", error)
        }
    }

    fun refreshLayout() {
        attached?.let { overlay ->
            overlay.view.applyStyle(settings.style, force = true)
            place(overlay)
        }
    }

    fun hide() {
        cue = null
        val current = attached ?: return
        attached = null
        try {
            if (current.view.parent != null) current.manager.removeViewImmediate(current.view)
        } catch (_: IllegalArgumentException) {
            // Android may already have removed the window after revoking permission.
        }
    }

    @Suppress("DEPRECATION")
    private fun place(overlay: Overlay) {
        val size = Point().also(overlay.display::getRealSize)
        val insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) overlay.manager.currentWindowMetrics.windowInsets else null
        val statusBarHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top ?: 0
        } else overlay.view.rootWindowInsets?.stableInsetTop ?: overlay.view.context.captionPixels(24)
        val width = (size.x * (settings.widthPercent / 100f)).roundToInt().coerceAtLeast(1)
        val upperThird = size.y / 3
        overlay.view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(upperThird, View.MeasureSpec.AT_MOST),
        )
        val height = overlay.view.measuredHeight
        val end = (upperThird - height).coerceAtLeast(0)
        val first = ((statusBarHeight - height) / 2).coerceIn(0, end)
        val position = settings.verticalPositionPercent.coerceIn(0, 100) / 100f
        overlay.params.width = width
        overlay.params.height = height
        overlay.params.x = (size.x - width) / 2
        overlay.params.y = first + ((end - first) * position).roundToInt()
        if (overlay.view.parent != null) {
            try {
                overlay.manager.updateViewLayout(overlay.view, overlay.params)
            } catch (error: IllegalArgumentException) {
                hide()
                Log.w(WINDOW_TITLE, "Status bar lyrics window was detached", error)
            } catch (error: SecurityException) {
                hide()
                Log.w(WINDOW_TITLE, "Status bar lyrics permission unavailable", error)
            }
        }
    }

    private class Overlay(
        val manager: WindowManager,
        val display: Display,
        val view: PlayerOverlayCaptionView,
        val params: WindowManager.LayoutParams,
    )

    private companion object {
        const val WINDOW_TITLE = "PlayerStatusBarLyrics"
    }
}
