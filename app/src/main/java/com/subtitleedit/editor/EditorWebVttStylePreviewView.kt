package com.subtitleedit.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.subtitleedit.R
import com.subtitleedit.util.subtitle.WebVttStyleSettings
import kotlin.math.ceil
import kotlin.math.max

/** A native sample frame like Subtitle Edit: cue background, text/shadow, no outline. */
internal class EditorWebVttStylePreviewView(context: Context) : View(context) {
    var settings = WebVttStyleSettings()
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sample = context.getString(R.string.vtt_style_sample)

    init { contentDescription = context.getString(R.string.vtt_style_preview) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(38, 38, 38))
        val style = settings
        val family = style.fontName.substringBefore(',').trim().trim('"', '\'').ifBlank { "sans-serif" }
        val typefaceStyle = (if (style.bold) Typeface.BOLD else Typeface.NORMAL) or
            (if (style.italic) Typeface.ITALIC else Typeface.NORMAL)
        paint.typeface = Typeface.create(family, typefaceStyle)
        paint.textSize = (style.fontSize?.takeIf { it > 0 }?.times(2.5) ?: 60.0).toFloat()
        paint.isUnderlineText = style.underline
        paint.isStrikeThruText = style.strikeout
        val metrics = paint.fontMetrics
        val textWidth = paint.measureText(sample)
        val shadow = if (style.useShadow) style.shadowWidth.toFloat().coerceAtLeast(0f) else 0f
        val padding = 20f
        val textBitmapWidth = ceil(textWidth + padding * 2f + shadow).toInt()
        val textBitmapHeight = ceil(metrics.descent - metrics.ascent + padding * 2f + shadow).toInt()
        // Match Subtitle Edit's preview framing: large text grows the virtual frame, then the
        // complete frame is scaled into the dialog instead of clipping the sample.
        val frameWidth = max(640, textBitmapWidth + 160)
        val frameHeight = max((frameWidth * 9f / 16f).toInt(), textBitmapHeight + 60)
        val scale = minOf(width / frameWidth.toFloat(), height / frameHeight.toFloat())
        if (scale <= 0f) return
        val saved = canvas.save()
        canvas.translate((width - frameWidth * scale) / 2f, (height - frameHeight * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.drawColor(Color.rgb(38, 38, 38))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.rgb(90, 90, 90)
        canvas.drawRect(1f, 1f, frameWidth - 1f, frameHeight - 1f, paint)
        paint.style = Paint.Style.FILL
        val x = (frameWidth - textWidth) / 2f
        val baseline = frameHeight - 30f - shadow - metrics.descent
        if (style.useBackgroundColor) {
            paint.color = style.backgroundColor
            canvas.drawRect(x - padding, baseline + metrics.ascent - padding, x + textWidth + padding + shadow,
                baseline + metrics.descent + padding + shadow, paint)
        }
        if (style.useShadow && shadow > 0f) {
            paint.color = style.shadowColor
            canvas.drawText(sample, x + shadow, baseline + shadow, paint)
        }
        paint.color = if (style.useColor) style.color else Color.WHITE
        canvas.drawText(sample, x, baseline, paint)
        canvas.restoreToCount(saved)
    }
}
