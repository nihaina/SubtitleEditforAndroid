package com.subtitleedit.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
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
    private val backgroundHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundHighlightPath = Path()
    private var backgroundHighlights: List<SourceLineHighlight> = emptyList()

    var mergePrevious: (() -> Boolean)? = null
    var mergeNext: (() -> Boolean)? = null
    var splitLine: ((Int) -> Boolean)? = null
    var moveVertical: ((Int, Int) -> Boolean)? = null
    var extendVertical: ((Int, Int, Int) -> Boolean)? = null
    var selectionChanged: (() -> Unit)? = null

    /**
     * Keep a view-local copy of the row ranges so selection remains visible even while a
     * RecyclerView payload is being applied. BackgroundColorSpan is still installed by the
     * adapter for normal TextView rendering; this path is drawn underneath the text and makes
     * the document-wide selection deterministic during ActionMode/handle transitions.
     */
    fun setBackgroundHighlights(highlights: List<SourceLineHighlight>) {
        backgroundHighlights = highlights
        invalidate()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        selectionChanged?.invoke()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
        super.onTextChanged(text, start, before, count)
        ensureSoftWrapMargin()
    }

    override fun onDraw(canvas: Canvas) {
        drawBackgroundHighlights(canvas)
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

    private fun drawBackgroundHighlights(canvas: Canvas) {
        val textLayout = layout ?: return
        if (backgroundHighlights.isEmpty()) return
        val originX = compoundPaddingLeft - scrollX
        val availableHeight = height - compoundPaddingTop - compoundPaddingBottom
        val extraHeight = (availableHeight - textLayout.height).coerceAtLeast(0)
        val originY = compoundPaddingTop + when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> extraHeight
            Gravity.CENTER_VERTICAL -> extraHeight / 2
            else -> 0
        } - scrollY
        backgroundHighlightPaint.style = Paint.Style.FILL
        canvas.save()
        canvas.translate(originX.toFloat(), originY.toFloat())
        backgroundHighlights.forEach { highlight ->
            val start = highlight.start.coerceIn(0, textLayout.text.length)
            val end = highlight.end.coerceIn(start, textLayout.text.length)
            if (start >= end) return@forEach
            backgroundHighlightPath.reset()
            textLayout.getSelectionPath(start, end, backgroundHighlightPath)
            backgroundHighlightPaint.color = highlight.color
            canvas.drawPath(backgroundHighlightPath, backgroundHighlightPaint)
        }
        canvas.restore()
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
                if (event.action == KeyEvent.ACTION_DOWN && handleBoundaryKey(event.keyCode, event)) {
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleBoundaryKey(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun handleBoundaryKey(keyCode: Int, event: KeyEvent? = null): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && selectionStart == selectionEnd) {
            if (splitLine?.invoke(selectionStart.coerceAtLeast(0)) == true) return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
            val focusOffset = selectionEnd.coerceIn(0, length())
            val visualLine = layout?.getLineForOffset(focusOffset) ?: 0
            val lastVisualLine = (layout?.lineCount ?: 1) - 1
            val atLogicalBoundary = if (direction < 0) {
                visualLine == 0
            } else {
                visualLine >= lastVisualLine
            }
            if (!atLogicalBoundary) return false
            if (event?.isShiftPressed == true) {
                if (extendVertical?.invoke(
                        direction,
                        selectionStart.coerceIn(0, length()),
                        focusOffset
                    ) == true
                ) return true
            }
            if (selectionStart != selectionEnd) return false
            return moveVertical?.invoke(direction, focusOffset) == true
        }
        if (selectionStart != selectionEnd) return false
        return when {
            keyCode == KeyEvent.KEYCODE_DEL && selectionStart == 0 ->
                mergePrevious?.invoke() == true
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL && selectionStart == length() ->
                mergeNext?.invoke() == true
            else -> false
        }
    }
}
