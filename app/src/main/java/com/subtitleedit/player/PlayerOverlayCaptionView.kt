package com.subtitleedit.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Draws both overlay variants, leaving each window in charge of position and interaction. */
internal class PlayerOverlayCaptionView(context: Context, private val compact: Boolean = false) : FrameLayout(context) {
    private val label = CueTextView(context)
    private var style: PlayerFloatingSubtitleStyle? = null

    init {
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setCaption(text: String, cue: PlayerOverlaySubtitleCue?) {
        val currentCue = cue?.takeIf { it.text == text }
        val textChanged = label.text.toString() != text
        if (!textChanged && label.cue == currentCue) return
        if (textChanged) label.text = text
        label.cue = currentCue
        if (textChanged || label.scrollEnabled) label.updateWrapping()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val freeWidth = right - left - paddingLeft - paddingRight - label.measuredWidth
        val x = paddingLeft + floatingSubtitleLeftOffset(freeWidth, style?.leftMarginPercent ?: 50)
        label.layout(x, paddingTop, x + label.measuredWidth, paddingTop + label.measuredHeight)
    }

    @Suppress("RtlHardcoded")
    fun applyStyle(style: PlayerFloatingSubtitleStyle, force: Boolean = false) {
        if (!force && this.style == style) return
        this.style = style
        val horizontal = context.captionPixels(if (compact) 5 else 16)
        val vertical = context.captionPixels(if (compact) 1 else 10)
        setPadding(horizontal, vertical, horizontal, vertical)
        label.setLineSpacing(context.captionPixels(if (compact) 2 else 4).toFloat(), 1f)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.toFloat())
        label.setTextColor(style.colorArgb)
        label.outlineColor = style.outlineColorArgb
        label.scrollEnabled = style.scrollEnabled
        label.updateWrapping()
        label.gravity = Gravity.TOP or when (style.multilineAlignment) {
            PlayerSubtitleAlignment.LEFT -> Gravity.LEFT
            PlayerSubtitleAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
            PlayerSubtitleAlignment.RIGHT -> Gravity.RIGHT
        }
        label.typeface = captionTypeface(style.weight)
        if (!compact) {
            background = GradientDrawable().apply {
                cornerRadius = context.captionPixels(16).toFloat()
                setColor(style.backgroundColorArgb)
            }
        }
        requestLayout()
    }

    private class CueTextView(context: Context) : TextView(context) {
        var outlineColor = 0
        var scrollEnabled = false
        var cue: PlayerOverlaySubtitleCue? = null
        private var shouldScroll = false
        private var viewportHeight = 0

        init {
            includeFontPadding = false
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            textAlignment = TEXT_ALIGNMENT_GRAVITY
            val outlinePadding = context.captionPixels(1)
            setPadding(outlinePadding, outlinePadding, outlinePadding, outlinePadding)
        }

        fun updateWrapping() {
            // Only a soft-wrapped single cue scrolls. Keep explicit multi-line lyrics readable.
            val value = scrollEnabled && cue?.timing != null && text.none { it == '\n' || it == '\r' }
            if (shouldScroll != value) {
                shouldScroll = value
                maxLines = if (value) Int.MAX_VALUE else 3
                ellipsize = if (value) null else TextUtils.TruncateAt.END
            }
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val layout = layout
            viewportHeight = if (shouldScroll && layout != null && layout.lineCount > 1) {
                val last = layout.lineCount - 1
                layout.getLineBottom(last) - layout.getLineTop(last)
            } else 0
            if (viewportHeight > 0) setMeasuredDimension(measuredWidth, viewportHeight + paddingTop + paddingBottom)
        }

        override fun onDraw(canvas: Canvas) {
            val layout = layout ?: return
            val cue = cue
            val scroll = viewportHeight > 0 && layout.lineCount > 1
            val progress = if (scroll) cue?.progressAt(SystemClock.elapsedRealtime()) ?: 0f else 0f
            val lastLineTop = if (scroll) layout.getLineTop(layout.lineCount - 1) else 0
            val scrollOffset = progress * lastLineTop
            val saved = canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.translate(paddingLeft.toFloat(), paddingTop - scrollOffset)
            val paint = layout.paint
            val oldStyle = paint.style
            val oldWidth = paint.strokeWidth
            val oldJoin = paint.strokeJoin
            val oldColor = paint.color
            if (outlineColor ushr 24 != 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = resources.displayMetrics.density * 2f
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = outlineColor
                layout.draw(canvas)
            }
            paint.style = Paint.Style.FILL
            paint.color = currentTextColor
            layout.draw(canvas)
            paint.style = oldStyle
            paint.strokeWidth = oldWidth
            paint.strokeJoin = oldJoin
            paint.color = oldColor
            canvas.restoreToCount(saved)
            if (scroll && cue?.playing == true && progress < 1f) postInvalidateOnAnimation()
        }
    }
}

internal fun Context.captionPixels(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

private fun captionTypeface(weight: PlayerFloatingSubtitleWeight): Typeface {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Typeface.create(Typeface.DEFAULT, weight.value, false)
    return when (weight) {
        PlayerFloatingSubtitleWeight.LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        PlayerFloatingSubtitleWeight.NORMAL -> Typeface.DEFAULT
        PlayerFloatingSubtitleWeight.MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        PlayerFloatingSubtitleWeight.BOLD -> Typeface.DEFAULT_BOLD
    }
}
