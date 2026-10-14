package com.subtitleedit.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.subtitleedit.R
import com.subtitleedit.util.SubtitleFontChoice
import com.subtitleedit.util.SubtitleFontManager
import com.subtitleedit.util.subtitle.WebVttStyleSettings
import kotlin.math.ceil
import kotlin.math.max

/** A native sample frame like Subtitle Edit: cue background, text/shadow, no outline. */
internal class EditorWebVttStylePreviewView(context: Context) : View(context) {
    var settings = WebVttStyleSettings()
        set(value) {
            field = value
            // A font can be imported between two updates while its family name
            // stays the same; force the file lookup to run again in that case.
            resolvedTypefaceKey = null
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sample = context.getString(R.string.vtt_style_sample)
    private var resolvedTypefaceKey: String? = null
    private var resolvedTypeface: Typeface = Typeface.DEFAULT

    init { contentDescription = context.getString(R.string.vtt_style_preview) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(38, 38, 38))
        val style = settings
        val family = style.fontName
            .substringBefore(',')
            .trim()
            .trim('"', '\'')
            .ifBlank { "sans-serif" }
        val typefaceStyle = (if (style.bold) Typeface.BOLD else Typeface.NORMAL) or
            (if (style.italic) Typeface.ITALIC else Typeface.NORMAL)
        paint.typeface = resolveTypeface(family, typefaceStyle)
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

    /**
     * Resolve the same concrete face used by the font picker and subtitle renderer.
     * Typeface.create("name", ...) only works for Android's built-in families; it
     * silently falls back to the default face for imported fonts (and for many
     * names read from fonts.xml).  The picker keeps the actual file and TTC face
     * index, so use those when available and only fall back to the family name as
     * a last resort.
     */
    private fun resolveTypeface(family: String, style: Int): Typeface {
        val key = "${family.lowercase()}#$style"
        if (key == resolvedTypefaceKey) return resolvedTypeface

        val choice = runCatching {
            SubtitleFontManager.resolveSystemFont(context, family)
                ?: SubtitleFontManager.listFonts(context).firstOrNull {
                    it.name.equals(family, ignoreCase = true) ||
                        it.file?.name.equals(family, ignoreCase = true)
                }
        }.getOrNull()
        val base = choice?.let(::loadTypeface)
            ?: Typeface.create(family, Typeface.NORMAL)
        resolvedTypeface = Typeface.create(base, style)
        resolvedTypefaceKey = key
        return resolvedTypeface
    }

    private fun loadTypeface(choice: SubtitleFontChoice): Typeface? = runCatching {
        val file = choice.file ?: return@runCatching null
        Typeface.Builder(file)
            .setTtcIndex(choice.collectionIndex)
            .build()
    }.getOrNull()
}
