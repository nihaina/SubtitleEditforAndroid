package com.subtitleedit.player

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.subtitleedit.R
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PlayerFloatingSubtitlePosition(val y: Float)

internal enum class PlayerFloatingSubtitleAction {
    LOCK, PREVIOUS, PLAY_PAUSE, NEXT, CLOSE, TOGGLE_CONTROLS, PICK_COLOR, PICK_BACKGROUND_COLOR,
}

internal data class PlayerFloatingSubtitlePlayback(
    val title: String,
    val playing: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

/** The foreground player service owns one caption and controls window. All calls are on the main thread. */
internal class PlayerFloatingSubtitleWindow(
    context: Context,
    initialPosition: PlayerFloatingSubtitlePosition?,
    private val onPositionChanged: (PlayerFloatingSubtitlePosition) -> Unit,
    private val onAction: (PlayerFloatingSubtitleAction) -> Unit,
    private val onColor: (PlayerFloatingSubtitleColor, Int) -> Unit,
    private val onFontSizeDelta: (Int) -> Unit,
) {
    private val app = context.applicationContext
    private var overlay: Overlay? = null
    private var colorPicker: AlertDialog? = null
    private var position = initialPosition
    private var controlsOpen = false
    private var captionText = ""
    private var style = PlayerFloatingSubtitleStyle()
    private var playback: PlayerFloatingSubtitlePlayback? = null
    private var locked = false

    fun render(text: String?, style: PlayerFloatingSubtitleStyle, playback: PlayerFloatingSubtitlePlayback?, locked: Boolean) {
        if (playback == null || !Settings.canDrawOverlays(app)) {
            hide()
            return
        }
        if (locked) {
            controlsOpen = false
            dismissColorPicker()
        }
        if (text.isNullOrBlank() && !controlsOpen) {
            hide()
            return
        }
        val current = overlay
        val measureAgain = captionText != text.orEmpty() || this.style != style || this.locked != locked
        captionText = text.orEmpty()
        this.style = style
        this.playback = playback
        this.locked = locked
        if (current != null && current.root.parent != null) {
            updateContent(current, measureAgain)
        } else {
            attach()
        }
    }

    private fun attach() {
        try {
            val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return
            val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                app
            }
            val manager = windowContext.getSystemService(WindowManager::class.java) ?: return
            val root = CaptionWindowLayout(windowContext)
            val params = createLayoutParams()
            val current = Overlay(manager, root, params)
            updateContent(current, measureAgain = true)
            manager.addView(root, params)
            overlay = current
            if (!Settings.canDrawOverlays(app)) hide()
        } catch (error: WindowManager.BadTokenException) {
            Log.w(WINDOW_TITLE, "Unable to attach subtitle overlay", error)
        } catch (error: WindowManager.InvalidDisplayException) {
            Log.w(WINDOW_TITLE, "Subtitle overlay display is unavailable", error)
        } catch (error: SecurityException) {
            hide()
            Log.w(WINDOW_TITLE, "Subtitle overlay permission unavailable", error)
        }
    }

    fun hide() {
        dismissColorPicker()
        controlsOpen = false
        playback = null
        val current = overlay ?: return
        overlay = null
        try {
            if (current.root.parent != null) current.manager.removeViewImmediate(current.root)
        } catch (error: IllegalArgumentException) {
            Log.w(WINDOW_TITLE, "Subtitle overlay was already detached", error)
        }
    }

    @Suppress("RtlHardcoded")
    private fun createLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            setTitle(WINDOW_TITLE)
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = systemInsets()
                isFitInsetsIgnoringVisibility = true
            }
        }

    @Suppress("DEPRECATION")
    private fun usableSize(manager: WindowManager): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(systemInsets())
            Point(metrics.bounds.width() - insets.left - insets.right, metrics.bounds.height() - insets.top - insets.bottom)
        } else {
            Point().also { manager.defaultDisplay.getSize(it) }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun systemInsets() = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()

    private fun updateContent(current: Overlay, measureAgain: Boolean) {
        val root = current.root
        root.caption.applyStyle(style)
        if (root.caption.label.text.toString() != captionText) root.caption.label.text = captionText
        root.caption.visibility = if (captionText.isEmpty()) View.GONE else View.VISIBLE
        root.caption.isClickable = !locked
        root.controls.visibility = if (controlsOpen) View.VISIBLE else View.GONE
        if (controlsOpen) root.controls.render(playback, style)
        if (!measureAgain) return

        current.params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        current.params.alpha = if (locked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android only passes touches through a non-touchable overlay up to this opacity.
            root.context.getSystemService(InputManager::class.java)?.maximumObscuringOpacityForTouch ?: 0.8f
        } else 1f
        updatePlacement(current)
    }

    private fun updatePlacement(current: Overlay) {
        val root = current.root
        val screen = usableSize(current.manager)
        current.params.width = (screen.x - root.context.pixels(24)).coerceIn(1, root.context.pixels(560))
        root.measure(View.MeasureSpec.makeMeasureSpec(current.params.width, View.MeasureSpec.EXACTLY), 0)
        current.travelY = (screen.y - root.measuredHeight).coerceAtLeast(0)
        current.screenHeight = screen.y.coerceAtLeast(1)
        val savedPosition = position
        current.params.x = (screen.x - current.params.width).coerceAtLeast(0) / 2
        current.params.y = if (savedPosition == null) {
            (current.travelY - root.context.pixels(BOTTOM_SPACING_DP)).coerceAtLeast(0)
        } else {
            (current.screenHeight * savedPosition.y).roundToInt().coerceIn(0, current.travelY)
        }
        applyLayout(current)
    }

    private fun applyLayout(current: Overlay) {
        if (current.root.parent == null) return
        try {
            current.manager.updateViewLayout(current.root, current.params)
        } catch (error: IllegalArgumentException) {
            hide()
            Log.w(WINDOW_TITLE, "Subtitle overlay already detached", error)
        } catch (error: SecurityException) {
            hide()
            Log.w(WINDOW_TITLE, "Subtitle overlay permission unavailable", error)
        }
    }

    private fun onControlAction(action: PlayerFloatingSubtitleAction) {
        if (locked) return
        when (action) {
            PlayerFloatingSubtitleAction.TOGGLE_CONTROLS -> toggleControls()
            PlayerFloatingSubtitleAction.PICK_COLOR -> openColorPicker(PlayerFloatingSubtitleColor.TEXT)
            PlayerFloatingSubtitleAction.PICK_BACKGROUND_COLOR -> openColorPicker(PlayerFloatingSubtitleColor.BACKGROUND)
            else -> onAction(action)
        }
    }

    private fun openColorPicker(target: PlayerFloatingSubtitleColor) {
        val context = overlay?.root?.context ?: return
        if (colorPicker?.isShowing == true || !Settings.canDrawOverlays(app)) return
        try {
            colorPicker = showFloatingSubtitleColorPicker(
                context = ContextThemeWrapper(context, R.style.Theme_SubtitleEditforAndroid_Compose),
                color = style.colorFor(target),
                windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                background = target == PlayerFloatingSubtitleColor.BACKGROUND,
            ) { color -> onColor(target, color) }
        } catch (error: WindowManager.BadTokenException) {
            Log.w(WINDOW_TITLE, "Unable to attach subtitle color picker", error)
        } catch (error: WindowManager.InvalidDisplayException) {
            Log.w(WINDOW_TITLE, "Subtitle color picker display is unavailable", error)
        } catch (error: SecurityException) {
            hide()
            Log.w(WINDOW_TITLE, "Subtitle overlay permission unavailable", error)
        }
    }

    private fun dismissColorPicker() {
        colorPicker?.dismiss()
        colorPicker = null
    }

    private fun toggleControls() {
        val current = overlay ?: return
        if (locked) return
        controlsOpen = !controlsOpen
        if (!controlsOpen && captionText.isEmpty()) hide() else updateContent(current, measureAgain = true)
    }

    private inner class CaptionWindowLayout(context: Context) : LinearLayout(context) {
        val caption = CaptionCard(context)
        val controls = PlayerFloatingSubtitleControls(context, ::onControlAction, onFontSizeDelta)
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var drag: CaptionDrag? = null

        init {
            orientation = VERTICAL
            addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            caption.setOnClickListener { toggleControls() }
            caption.setOnTouchListener { view, event -> handleCaptionTouch(view, event) }
        }

        private fun handleCaptionTouch(view: View, event: MotionEvent): Boolean {
            val current = this@PlayerFloatingSubtitleWindow.overlay ?: return false
            if (locked) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> drag = CaptionDrag(event.rawX, event.rawY, current.params.y)
                MotionEvent.ACTION_MOVE -> {
                    val movement = drag ?: return false
                    val dx = (event.rawX - movement.startX).roundToInt()
                    val dy = (event.rawY - movement.startY).roundToInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) movement.moved = true
                    if (!movement.vertical && abs(dy) <= touchSlop) return true
                    movement.vertical = true
                    val y = (movement.windowY + dy).coerceIn(0, current.travelY)
                    if (y == current.params.y) return true
                    current.params.y = y
                    position = PlayerFloatingSubtitlePosition(y.toFloat() / current.screenHeight)
                    movement.positionChanged = true
                    applyLayout(current)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val movement = drag
                    drag = null
                    if (movement?.positionChanged == true) position?.let(onPositionChanged)
                    else if (movement?.moved == false && event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                }
            }
            return true
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            drag = null
            caption.applyStyle(style, force = true)
            post {
                val current = this@PlayerFloatingSubtitleWindow.overlay ?: return@post
                if (current.root === this && parent != null) updatePlacement(current)
            }
        }
    }

    private class CaptionCard(context: Context) : FrameLayout(context) {
        val label = TextView(context).apply {
            includeFontPadding = false
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            textAlignment = TEXT_ALIGNMENT_GRAVITY
        }
        private var style: PlayerFloatingSubtitleStyle? = null

        init {
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val freeWidth = right - left - paddingLeft - paddingRight - label.measuredWidth
            val x = paddingLeft + floatingSubtitleLeftOffset(freeWidth, style?.leftMarginPercent ?: 50)
            label.layout(x, paddingTop, x + label.measuredWidth, paddingTop + label.measuredHeight)
        }

        fun applyStyle(style: PlayerFloatingSubtitleStyle, force: Boolean = false) {
            if (!force && this.style == style) return
            this.style = style
            val horizontal = context.pixels(16)
            val vertical = context.pixels(10)
            setPadding(horizontal, vertical, horizontal, vertical)
            label.setLineSpacing(context.pixels(4).toFloat(), 1f)
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.toFloat())
            label.setTextColor(style.colorArgb)
            label.gravity = Gravity.CENTER_VERTICAL or when (style.multilineAlignment) {
                PlayerSubtitleAlignment.LEFT -> @Suppress("RtlHardcoded") Gravity.LEFT
                PlayerSubtitleAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
                PlayerSubtitleAlignment.RIGHT -> @Suppress("RtlHardcoded") Gravity.RIGHT
            }
            label.typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(Typeface.DEFAULT, style.weight.value, false)
            } else {
                when (style.weight) {
                    PlayerFloatingSubtitleWeight.LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
                    PlayerFloatingSubtitleWeight.NORMAL -> Typeface.DEFAULT
                    PlayerFloatingSubtitleWeight.MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    PlayerFloatingSubtitleWeight.BOLD -> Typeface.DEFAULT_BOLD
                }
            }
            background = GradientDrawable().apply {
                cornerRadius = context.pixels(16).toFloat()
                setColor(style.backgroundColorArgb)
            }
            requestLayout()
        }
    }

    private class Overlay(
        val manager: WindowManager,
        val root: CaptionWindowLayout,
        val params: WindowManager.LayoutParams,
    ) {
        var travelY = 0
        var screenHeight = 1
    }

    private data class CaptionDrag(
        val startX: Float,
        val startY: Float,
        val windowY: Int,
        var moved: Boolean = false,
        var vertical: Boolean = false,
        var positionChanged: Boolean = false,
    )

    private companion object {
        const val WINDOW_TITLE = "PlayerFloatingSubtitle"
        const val BOTTOM_SPACING_DP = 48

        fun Context.pixels(dp: Int) = (dp * resources.displayMetrics.density).roundToInt()
    }
}
