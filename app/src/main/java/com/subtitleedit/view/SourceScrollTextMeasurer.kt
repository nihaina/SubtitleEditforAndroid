package com.subtitleedit.view

import android.graphics.Paint
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.view.View
import androidx.core.widget.TextViewCompat
import kotlin.math.max

/**
 * A main-thread snapshot of one laid-out source editor's text metrics. Once constructed, height
 * calculation touches no View and can run on a worker without creating Editables or editor state.
 */
internal class SourceScrollTextMeasurer(editor: SourceLineEditText, minimumRowHeight: Int) {
    private val paint = TextPaint(editor.paint)
    private val width = (editor.layout?.width
        ?: (editor.measuredWidth - editor.compoundPaddingLeft - editor.compoundPaddingRight))
        .coerceAtLeast(1)
    private val verticalPadding = editor.compoundPaddingTop + editor.compoundPaddingBottom
    private val minimumHeight = max(minimumRowHeight, editor.minimumHeight)
    private val continuationMargin = editor.wrapContinuationMarginPx
    private val alignment = editor.layout?.alignment ?: Layout.Alignment.ALIGN_NORMAL
    private val direction = TextViewCompat.getTextMetricsParams(editor).textDirection
    private val includeFontPadding = editor.includeFontPadding
    private val spacingExtra = editor.lineSpacingExtra
    private val spacingMultiplier = editor.lineSpacingMultiplier
    private val breakStrategy = editor.breakStrategy
    private val hyphenationFrequency = editor.hyphenationFrequency
    private val justificationMode = if (Build.VERSION.SDK_INT >= 26) editor.justificationMode else 0
    private val fallbackLineSpacing = Build.VERSION.SDK_INT >= 28 && editor.isFallbackLineSpacing
    private val lineBreakStyle = if (Build.VERSION.SDK_INT >= 33) editor.lineBreakStyle else 0
    private val lineBreakWordStyle = if (Build.VERSION.SDK_INT >= 33) editor.lineBreakWordStyle else 0
    private val minimumFontMetrics = if (Build.VERSION.SDK_INT >= 35) {
        editor.minimumFontMetrics?.let { source ->
            Paint.FontMetrics().apply {
                top = source.top
                ascent = source.ascent
                descent = source.descent
                bottom = source.bottom
                leading = source.leading
            }
        }
    } else {
        null
    }
    private val useBoundsForWidth = Build.VERSION.SDK_INT >= 35 && editor.useBoundsForWidth

    val defaultHeight: Int = max(minimumHeight, editor.lineHeight + verticalPadding).coerceAtLeast(1)

    fun measure(text: String, checkCancelled: () -> Unit): Int {
        checkCancelled()
        if (text.isEmpty()) return defaultHeight

        // Keep paragraph direction stable when a very long physical line is measured in pieces.
        val textDirection = if (direction?.isRtl(text, 0, text.length) == true) {
            TextDirectionHeuristics.RTL
        } else {
            TextDirectionHeuristics.LTR
        }
        var start = 0
        var height = 0L
        while (start < text.length) {
            checkCancelled()
            var end = minOf(text.length, start + MAX_LAYOUT_CHARS)
            // Never create an unpaired UTF-16 surrogate at the end of an intermediate piece.
            if (end < text.length && end > start && Character.isHighSurrogate(text[end - 1]) &&
                Character.isLowSurrogate(text[end])
            ) {
                end--
            }
            val piece = SpannableString(text.substring(start, end)).apply {
                setSpan(
                    LeadingMarginSpan.Standard(if (start == 0) 0 else continuationMargin, continuationMargin),
                    0,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val builder = StaticLayout.Builder.obtain(piece, 0, piece.length, paint, width)
                .setAlignment(alignment)
                .setTextDirection(textDirection)
                .setIncludePad(includeFontPadding && start == 0)
                .setLineSpacing(spacingExtra, spacingMultiplier)
                .setBreakStrategy(breakStrategy)
                .setHyphenationFrequency(hyphenationFrequency)
                .setJustificationMode(justificationMode)
            if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(fallbackLineSpacing)
            if (Build.VERSION.SDK_INT >= 33) {
                builder.setLineBreakConfig(LineBreakConfig.Builder()
                    .setLineBreakStyle(lineBreakStyle)
                    .setLineBreakWordStyle(lineBreakWordStyle)
                    .build())
            }
            if (Build.VERSION.SDK_INT >= 35) {
                minimumFontMetrics?.let(builder::setMinimumFontMetrics)
                builder.setUseBoundsForWidth(useBoundsForWidth)
            }
            val layout = builder.build()
            if (end == text.length) {
                height += layout.height
                if (start > 0 && includeFontPadding) {
                    val metrics = paint.fontMetricsInt
                    height += (metrics.bottom - metrics.descent).coerceAtLeast(0)
                }
                break
            }

            // The last line may be only partly filled because the piece ended mid-word. Carry
            // it into the next piece so a boundary cannot add an extra wrapped visual line.
            val lastLine = layout.lineCount - 1
            val consumed = layout.getLineStart(lastLine)
            if (consumed > 0) {
                height += layout.getLineTop(lastLine)
                start += consumed
            } else {
                // A viewport wider than an entire piece (or one enormous unbroken cluster)
                // cannot provide a preceding complete visual line. Keep memory bounded and
                // advance; visible rows later replace this exceptional estimate with their
                // actual decorated height.
                height += layout.height
                start = end
            }
            if (height >= View.MEASURED_SIZE_MASK) return View.MEASURED_SIZE_MASK
        }
        return max(minimumHeight.toLong(), height + verticalPadding)
            .coerceIn(1L, View.MEASURED_SIZE_MASK.toLong()).toInt()
    }

    private companion object {
        // StaticLayout/MeasuredText allocate per-character state. Keep this bound independent
        // of the file size, including when a whole file consists of one physical source line.
        const val MAX_LAYOUT_CHARS = 16_384
    }
}
