package com.subtitleedit.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal class ColorWheelView(context: Context) : View(context) {
    var onColorSelected: ((hue: Float, saturation: Float) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hue = 0f
    private var saturation = 0f
    private var value = 1f
    private var radius = 0f
    private var wheelShader: Shader? = null

    init {
        isFocusable = true
        isClickable = true
        contentDescription = "色轮"
    }

    fun setHsv(hue: Float, saturation: Float, value: Float) {
        this.hue = hue
        this.saturation = saturation
        if (this.value != value) {
            this.value = value
            updateShader()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(208f).roundToInt()
        val width = resolveSize(desired, widthMeasureSpec)
        val height = resolveSize(width, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius = (min(w, h) / 2f - dp(10f)).coerceAtLeast(1f)
        updateShader()
    }

    private fun updateShader() {
        if (radius <= 0f) return
        val colors = IntArray(7) { index ->
            Color.HSVToColor(floatArrayOf(index * 60f, 1f, value))
        }
        val center = Color.HSVToColor(floatArrayOf(0f, 0f, value))
        wheelShader = ComposeShader(
            SweepGradient(width / 2f, height / 2f, colors, null),
            RadialGradient(
                width / 2f, height / 2f, radius,
                center, center and 0x00ffffff, Shader.TileMode.CLAMP
            ),
            PorterDuff.Mode.SRC_OVER
        )
    }

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        paint.shader = wheelShader
        canvas.drawCircle(centerX, centerY, radius, paint)
        val angle = Math.toRadians(hue.toDouble())
        val markerX = centerX + cos(angle).toFloat() * saturation * radius
        val markerY = centerY + sin(angle).toFloat() * saturation * radius
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        paint.color = Color.BLACK
        canvas.drawCircle(markerX, markerY, dp(7f), paint)
        paint.strokeWidth = dp(1.5f)
        paint.color = Color.WHITE
        canvas.drawCircle(markerX, markerY, dp(7f), paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val x = event.x - width / 2f
                val y = event.y - height / 2f
                hue = ((Math.toDegrees(atan2(y, x).toDouble()).toFloat() + 360f) % 360f)
                saturation = (hypot(x, y) / radius).coerceIn(0f, 1f)
                onColorSelected?.invoke(hue, saturation)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> hue = (hue + 355f) % 360f
            KeyEvent.KEYCODE_DPAD_RIGHT -> hue = (hue + 5f) % 360f
            KeyEvent.KEYCODE_DPAD_UP -> saturation = (saturation + 0.05f).coerceAtMost(1f)
            KeyEvent.KEYCODE_DPAD_DOWN -> saturation = (saturation - 0.05f).coerceAtLeast(0f)
            else -> return super.onKeyDown(keyCode, event)
        }
        onColorSelected?.invoke(hue, saturation)
        invalidate()
        return true
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}

internal class ColorGradientSliderView(context: Context) : View(context) {
    var onValueChanged: ((Float) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var startColor = Color.BLACK
    private var endColor = Color.WHITE
    private var position = 0f
    private var markerColor = Color.BLACK

    init {
        minimumHeight = dp(48f).roundToInt()
        isClickable = true
        isFocusable = true
    }

    fun setGradient(startColor: Int, endColor: Int, position: Float, markerColor: Int) {
        this.startColor = startColor
        this.endColor = endColor
        this.position = position.coerceIn(0f, 1f)
        this.markerColor = markerColor
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(120f).roundToInt(), widthMeasureSpec),
            resolveSize(minimumHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val left = dp(10f)
        val right = (width - dp(10f)).coerceAtLeast(left + 1f)
        val top = height / 2f - dp(6f)
        val bottom = height / 2f + dp(6f)
        drawCheckerboard(canvas, paint, left, top, right, bottom, dp(6f))
        paint.shader = LinearGradient(left, top, right, top, startColor, endColor, Shader.TileMode.CLAMP)
        canvas.drawRect(left, top, right, bottom, paint)
        paint.shader = null
        val markerX = left + (right - left) * position
        paint.color = Color.WHITE
        canvas.drawCircle(markerX, height / 2f, dp(9f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.DKGRAY
        canvas.drawCircle(markerX, height / 2f, dp(9f), paint)
        paint.style = Paint.Style.FILL
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(markerX, height / 2f, dp(6f), Path.Direction.CW) })
        drawCheckerboard(
            canvas, paint, markerX - dp(6f), height / 2f - dp(6f),
            markerX + dp(6f), height / 2f + dp(6f), dp(3f)
        )
        paint.color = markerColor
        canvas.drawCircle(markerX, height / 2f, dp(6f), paint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val inset = dp(10f)
                updatePosition((event.x - inset) / (width - inset * 2f).coerceAtLeast(1f))
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, 255f, (position * 255f).roundToInt().toFloat()
        )
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                val value = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false
                updatePosition(value / 255f)
            }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> updatePosition(position + 1f / 255f)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> updatePosition(position - 1f / 255f)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> updatePosition(position - 1f / 255f)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> updatePosition(position + 1f / 255f)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun updatePosition(value: Float) {
        position = value.coerceIn(0f, 1f)
        onValueChanged?.invoke(position)
        invalidate()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}

internal class ColorPreviewView(context: Context) : View(context) {
    var color: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val inset = resources.displayMetrics.density * 4f
        drawCheckerboard(canvas, paint, inset, inset, width - inset, height - inset, inset * 2f)
        paint.color = color
        canvas.drawRect(inset, inset, width - inset, height - inset, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = Color.GRAY
        canvas.drawRect(inset, inset, width - inset, height - inset, paint)
        paint.style = Paint.Style.FILL
    }
}

private fun drawCheckerboard(
    canvas: Canvas,
    paint: Paint,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    cell: Float
) {
    paint.shader = null
    paint.style = Paint.Style.FILL
    var row = 0
    var y = top
    while (y < bottom) {
        var column = 0
        var x = left
        while (x < right) {
            paint.color = if ((row + column) % 2 == 0) Color.WHITE else Color.LTGRAY
            canvas.drawRect(x, y, min(x + cell, right), min(y + cell, bottom), paint)
            x += cell
            column++
        }
        y += cell
        row++
    }
}
