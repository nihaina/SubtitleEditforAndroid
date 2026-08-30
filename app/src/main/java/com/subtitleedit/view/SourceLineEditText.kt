package com.subtitleedit.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText
import kotlin.math.roundToInt

/**
 * One logical source-line input that delegates cross-line editing to the RecyclerView model.
 *
 * Text that is wider than the viewport wraps visually but remains one EditText/adapter item.
 * MT-style soft-wrap markers make the relationship between visual fragments explicit: `↲` on
 * a fragment that continues to the right and `↳` on a fragment continued from the left.
 */
internal class SourceLineEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private val wrapMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66909090
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            resources.displayMetrics
        )
    }
    private val leftWrapMarker = "↳"
    private val rightWrapMarker = "↲"
    private val wrapContinuationMarginPx =
        (wrapMarkerPaint.measureText(leftWrapMarker) + resources.displayMetrics.density * 4f)
            .roundToInt()

    var mergePrevious: (() -> Boolean)? = null
    var mergeNext: (() -> Boolean)? = null
    var splitLine: ((Int) -> Boolean)? = null
    var moveVertical: ((Int, Int) -> Boolean)? = null

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
        super.onTextChanged(text, start, before, count)
        ensureSoftWrapMargin()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Layout line count includes visual soft wraps. Explicit newlines are never present in
        // this view because SourceEditorView splits them into separate RecyclerView items.
        val textLayout = layout ?: return
        val visualLineCount = textLayout.lineCount
        if (visualLineCount <= 1) return

        val markerWidth = wrapMarkerPaint.measureText(rightWrapMarker)
        val leftX = (paddingLeft - markerWidth) * 0.5f
        val rightX = width - paddingRight - markerWidth * 0.5f
        for (line in 0 until visualLineCount) {
            val baseline = textLayout.getLineBaseline(line).toFloat()
            if (line > 0) canvas.drawText(leftWrapMarker, leftX, baseline, wrapMarkerPaint)
            if (line < visualLineCount - 1) canvas.drawText(rightWrapMarker, rightX, baseline, wrapMarkerPaint)
        }
    }

    /**
     * Indent only visual continuation fragments. The span applies a zero margin to the first
     * visual line and reserves room for the left `↳` marker on every wrapped line after it; the
     * underlying text remains unchanged and therefore still serializes as one logical line.
     */
    private fun ensureSoftWrapMargin() {
        val editable = text ?: return
        val existing = editable.getSpans(0, editable.length, SoftWrapMarginSpan::class.java)
        val current = existing.firstOrNull()
        if (
            current != null &&
            current.restMargin == wrapContinuationMarginPx &&
            editable.getSpanStart(current) == 0 &&
            editable.getSpanEnd(current) == editable.length
        ) {
            return
        }
        existing.forEach(editable::removeSpan)
        if (editable.isNotEmpty()) {
            editable.setSpan(
                SoftWrapMarginSpan(wrapContinuationMarginPx),
                0,
                editable.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private class SoftWrapMarginSpan(
        val restMargin: Int
    ) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean): Int = if (first) 0 else restMargin

        override fun drawLeadingMargin(
            canvas: Canvas,
            paint: Paint,
            x: Int,
            dir: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            first: Boolean,
            layout: Layout
        ) = Unit
    }

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(target, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (selectionStart == selectionEnd) {
                    if (beforeLength > 0 && afterLength == 0 && selectionStart == 0) {
                        if (mergePrevious?.invoke() == true) return true
                    }
                    if (beforeLength == 0 && afterLength > 0 && selectionStart == length()) {
                        if (mergeNext?.invoke() == true) return true
                    }
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && handleBoundaryKey(event.keyCode)) {
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleBoundaryKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun handleBoundaryKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && selectionStart == selectionEnd) {
            if (splitLine?.invoke(selectionStart.coerceAtLeast(0)) == true) return true
        }
        if (selectionStart != selectionEnd) return false
        return when {
            keyCode == KeyEvent.KEYCODE_DEL && selectionStart == 0 ->
                mergePrevious?.invoke() == true
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL && selectionStart == length() ->
                mergeNext?.invoke() == true
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                // Let EditText move within a soft-wrapped visual fragment first. Only the
                // first visual fragment crosses the logical RecyclerView-row boundary.
                val visualLine = layout?.getLineForOffset(selectionStart.coerceIn(0, length())) ?: 0
                if (visualLine > 0) false
                else moveVertical?.invoke(-1, selectionStart.coerceAtLeast(0)) == true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                val currentOffset = selectionStart.coerceIn(0, length())
                val visualLine = layout?.getLineForOffset(currentOffset) ?: 0
                val lastVisualLine = (layout?.lineCount ?: 1) - 1
                if (visualLine < lastVisualLine) false
                else moveVertical?.invoke(1, currentOffset) == true
            }
            else -> false
        }
    }
}
