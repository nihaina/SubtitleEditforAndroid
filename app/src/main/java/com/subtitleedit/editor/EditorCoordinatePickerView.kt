package com.subtitleedit.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.subtitleedit.util.SubtitleCoordinate
import java.util.Locale

/** Green crosshair used while choosing a subtitle anchor on the visible video frame. */
internal class EditorCoordinatePickerView(
    context: Context,
    initial: SubtitleCoordinate,
    /** Display aspect ratio of the video (width / height), when it is known. */
    private val videoAspectRatio: Float? = null,
    private val onChanged: (SubtitleCoordinate) -> Unit
) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 0, 255, 80)
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private var xPercent = initial.xPercent
    private var yPercent = initial.yPercent
    private var dragging = false

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "拖动字幕坐标"
    }

    fun coordinate(): SubtitleCoordinate = SubtitleCoordinate(xPercent, yPercent)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val content = videoContentRect()
        val x = content.left + content.width() * xPercent / 100f
        val y = content.top + content.height() * yPercent / 100f
        // The crosshair follows the actual video image. Any letterbox/pillarbox area
        // remains untouched so dragging cannot accidentally map black bars to coordinates.
        canvas.drawLine(x, content.top, x, content.bottom, linePaint)
        canvas.drawLine(content.left, y, content.right, y, linePaint)
        canvas.drawText(
            String.format(Locale.US, "%.1f%%, %.1f%%", xPercent, yPercent),
            (x + 12f * resources.displayMetrics.density)
                .coerceAtMost((content.right - 100f).coerceAtLeast(content.left)),
            (y - 12f * resources.displayMetrics.density)
                .coerceAtLeast(content.top + 20f),
            labelPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Do not turn a press on letterbox/pillarbox into a coordinate jump. The
                // selectable surface is the actual video image only.
                dragging = videoContentRect().contains(event.x, event.y)
                if (dragging) update(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                dragging = true
                update(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) update(event.x, event.y)
                dragging = false
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun update(x: Float, y: Float) {
        if (width <= 0 || height <= 0) return
        val content = videoContentRect()
        xPercent = ((x - content.left) * 100f / content.width()).coerceIn(0f, 100f)
        yPercent = ((y - content.top) * 100f / content.height()).coerceIn(0f, 100f)
        invalidate()
        onChanged(coordinate())
    }

    /**
     * Computes the fit-center video image inside this full-screen overlay. mpv renders the
     * video with aspect preservation, so the unused area can be black bars. Keeping this
     * mapping in one place makes drawing and touch coordinates agree exactly.
     */
    private fun videoContentRect(): RectF {
        val ratio = videoAspectRatio?.takeIf { it.isFinite() && it > 0f } ?: return RectF(
            0f, 0f, width.toFloat(), height.toFloat()
        )
        val viewRatio = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        return if (viewRatio > ratio) {
            val contentWidth = height * ratio
            val left = (width - contentWidth) / 2f
            RectF(left, 0f, left + contentWidth, height.toFloat())
        } else {
            val contentHeight = width / ratio
            val top = (height - contentHeight) / 2f
            RectF(0f, top, width.toFloat(), top + contentHeight)
        }
    }
}
