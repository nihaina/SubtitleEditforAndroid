package com.subtitleedit.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import com.subtitleedit.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 虚拟化源码编辑器。
 *
 * 文档字符串和行索引完整保存在内存中，但 EditText 只承载视口附近的窗口。
 * 其余行直接由 Canvas 绘制，避免 Android 为整份字幕建立 DynamicLayout 和巨型
 * View 高度。窗口滚动到边界时，当前窗口先提交回文档，再换绑新的可见窗口。
 */
class SourceEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DraggableScrollView(context, attrs, defStyleAttr) {

    private companion object {
        // 参考 Subtitle Edit 的可视行缓存：窗口足够大时，快速拖动不会频繁重绑 EditText。
        const val MIN_WINDOW_LINES = 512
        const val MIN_WINDOW_MARGIN_LINES = 128
    }

    data class Highlight(val start: Int, val end: Int, val current: Boolean = false)

    private val documentView = DocumentView(context)
    private val editor = EditText(context)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlights = mutableListOf<Highlight>()
    private var documentText = ""
    private var lineStarts = intArrayOf(0)
    private var windowStartLine = 0
    private var windowEndLine = 0
    private var cursorDocumentOffset = 0
    private var bindingWindow = false
    private var windowRebindPosted = false
    private var pendingWindowStartLine = -1
    private var pendingWindowEndLine = -1
    private val documentChangedListeners = mutableListOf<() -> Unit>()
    private var lineHeight = 0
    private var baselineOffset = 0f
    private var horizontalInset = 12f
    private var verticalInset = 12f

    init {
        isFillViewport = true
        overScrollMode = OVER_SCROLL_ALWAYS
        setBackgroundColor(Color.TRANSPARENT)
        clipToPadding = false
        // 使用 DraggableScrollView 自绘滚动条，避免系统滚动条在大内容上被裁剪或淡出过快。
        isVerticalScrollBarEnabled = false
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.color = context.getColor(R.color.on_surface)
        textPaint.textSize = resources.getDimension(R.dimen.editor_source_text_size)
        linePaint.style = Paint.Style.FILL
        val metrics = textPaint.fontMetrics
        lineHeight = ceil(metrics.descent - metrics.ascent + resources.displayMetrics.density * 4f).toInt()
            .coerceAtLeast((resources.displayMetrics.density * 20f).toInt())
        baselineOffset = -metrics.ascent
        horizontalInset = resources.displayMetrics.density * 12f
        verticalInset = resources.displayMetrics.density * 12f

        documentView.setWillNotDraw(false)
        addView(documentView, LayoutParams(LayoutParams.MATCH_PARENT, 1))
        editor.apply {
            background = null
            gravity = Gravity.TOP or Gravity.START
            includeFontPadding = false
            setPadding(horizontalInset.toInt(), 0, horizontalInset.toInt(), 0)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPaint.textSize)
            setLineSpacing(
                (lineHeight - (textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent)).coerceAtLeast(0f),
                1f
            )
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            setHorizontallyScrolling(true)
            isScrollContainer = false
            isSaveEnabled = false
            // Window rebinding changes the editor text/selection while the parent is flinging.
            // Do not let focus handling ask ScrollView to reveal the transient selection.
            setRevealOnFocusHint(false)
            setTextColor(textPaint.color)
            setHintTextColor(textPaint.color)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!bindingWindow) commitWindowText(s?.toString().orEmpty())
                }
            })
        }
        documentView.addView(editor, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1))
        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            ensureWindowForViewport(scrollY)
        }
    }

    fun addOnDocumentChangedListener(listener: () -> Unit) {
        documentChangedListeners += listener
    }

    fun setDocumentText(value: String, preserveScroll: Boolean = false) {
        val previousScroll = if (preserveScroll) scrollY else 0
        removeCallbacks(windowRebindRunnable)
        windowRebindPosted = false
        commitWindowTextIfNeeded()
        documentText = value
        rebuildLineIndex()
        windowStartLine = 0
        windowEndLine = 0
        cursorDocumentOffset = 0
        scrollTo(0, previousScroll.coerceAtLeast(0))
        ensureWindowForViewport(previousScroll, force = true)
        if (preserveScroll) {
            post {
                scrollTo(0, previousScroll.coerceIn(0, max(0, computeVerticalScrollRange() - height)))
            }
        }
        invalidate()
    }

    fun getDocumentText(): String {
        commitWindowTextIfNeeded()
        return documentText
    }

    fun replaceDocumentText(value: String) {
        setDocumentText(value)
        documentChangedListeners.forEach { it.invoke() }
    }

    fun setDocumentEnabled(enabled: Boolean) {
        editor.isEnabled = enabled
        isEnabled = enabled
    }

    fun setSearchHighlights(ranges: List<Highlight>) {
        highlights.clear()
        highlights.addAll(ranges)
        applyWindowHighlights()
        invalidate()
    }

    fun clearSearchHighlights() {
        highlights.clear()
        applyWindowHighlights()
        invalidate()
    }

    fun scrollToDocumentOffset(offset: Int) {
        commitWindowTextIfNeeded()
        val safeOffset = offset.coerceIn(0, documentText.length)
        val line = findLineForOffset(safeOffset)
        val target = (line * lineHeight - height / 3).coerceAtLeast(0)
        post { scrollTo(0, target) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ensureWindowForViewport(scrollY, force = true)
    }

    override fun requestChildRectangleOnScreen(
        child: View,
        rectangle: Rect,
        immediate: Boolean
    ): Boolean {
        // setText()/setSelection() can request the transient window's cursor rectangle. If
        // ScrollView honors that request during a fling, it jumps back to the rebind target.
        return if (bindingWindow) false
        else super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }

    private val windowRebindRunnable = Runnable {
        windowRebindPosted = false
        val start = pendingWindowStartLine
        val end = pendingWindowEndLine
        pendingWindowStartLine = -1
        pendingWindowEndLine = -1
        if (start >= 0 && end > start && start != windowStartLine) {
            bindWindow(start, end)
        }
    }

    private fun commitWindowTextIfNeeded() {
        if (windowEndLine > windowStartLine) commitWindowText(editor.text?.toString().orEmpty(), notify = false)
    }

    private fun commitWindowText(value: String, notify: Boolean = true) {
        if (bindingWindow || windowEndLine <= windowStartLine) return
        val start = lineStart(windowStartLine)
        val end = lineEnd(windowEndLine)
        val oldCursor = editor.selectionStart.coerceAtLeast(0)
        cursorDocumentOffset = (start + oldCursor).coerceIn(start, end)
        val current = documentText.substring(start, end)
        if (current == value) return
        documentText = documentText.substring(0, start) + value + documentText.substring(end)
        rebuildLineIndex()
        windowEndLine = min(windowStartLine + value.count { it == '\n' } + 1, lineCount())
        updateDocumentHeight()
        if (notify) documentChangedListeners.forEach { it.invoke() }
        documentView.invalidate()
    }

    private fun ensureWindowForViewport(scrollPosition: Int, force: Boolean = false) {
        val count = lineCount()
        if (count == 0) return
        val firstVisible = (scrollPosition / lineHeight).coerceIn(0, count - 1)
        val visibleLines = max(1, ceil(height.toFloat() / lineHeight).toInt())
        val margin = max(MIN_WINDOW_MARGIN_LINES, visibleLines)
        if (!force && firstVisible >= windowStartLine + margin && firstVisible + visibleLines <= windowEndLine - margin) {
            return
        }
        val windowSize = max(MIN_WINDOW_LINES, visibleLines + margin * 2)
        val nextStart = (firstVisible - margin).coerceAtLeast(0)
        val nextEnd = min(count, nextStart + windowSize)
        if (force) {
            bindWindow(nextStart, nextEnd)
            return
        }
        // 滚动回调可能一帧内触发多次，只保留最后一个窗口目标，避免连续 setText/layout。
        pendingWindowStartLine = nextStart
        pendingWindowEndLine = nextEnd
        if (!windowRebindPosted) {
            windowRebindPosted = true
            postOnAnimation(windowRebindRunnable)
        }
    }

    private fun bindWindow(startLine: Int, endLine: Int) {
        commitWindowTextIfNeeded()
        windowStartLine = startLine
        windowEndLine = endLine
        val start = lineStart(startLine)
        val end = lineEnd(endLine)
        val value = documentText.substring(start, end)
        bindingWindow = true
        try {
            editor.setText(value)
            val relativeCursor = (cursorDocumentOffset - start).coerceIn(0, value.length)
            editor.setSelection(relativeCursor)
            applyWindowHighlights()
        } finally {
            bindingWindow = false
        }
        updateDocumentHeight()
        documentView.invalidate()
    }

    private fun applyWindowHighlights() {
        val editable = editor.text ?: return
        editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
            .forEach { editable.removeSpan(it) }
        val start = lineStart(windowStartLine)
        val end = lineEnd(windowEndLine)
        highlights.forEach { highlight ->
            val from = (highlight.start - start).coerceAtLeast(0)
            val to = (highlight.end - start).coerceAtMost(end - start)
            if (from < to) {
                val color = if (highlight.current) context.getColor(R.color.secondary)
                else context.getColor(R.color.inverse_primary)
                editable.setSpan(
                    BackgroundColorSpan(color),
                    from,
                    to,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun updateDocumentHeight() {
        val totalHeight = (verticalInset.toInt().toLong() * 2L + lineCount().toLong() * lineHeight)
            .coerceIn(1L, Int.MAX_VALUE.toLong() - 1L).toInt()
        val documentParams = documentView.layoutParams
        if (documentParams.height != totalHeight) {
            documentParams.height = totalHeight
            documentView.layoutParams = documentParams
        }
        documentView.minimumHeight = totalHeight
        val editorTop = verticalInset.toInt() + windowStartLine * lineHeight
        val editorHeight = max(lineHeight, (windowEndLine - windowStartLine) * lineHeight)
        val params = editor.layoutParams as FrameLayout.LayoutParams
        if (params.height != editorHeight) {
            params.height = editorHeight
            editor.layoutParams = params
        }
        // 窗口换绑只移动编辑器，不改变父容器高度，避免 ScrollView 在 fling 中修正 scrollY。
        editor.translationY = editorTop.toFloat() - editor.top
    }

    private fun rebuildLineIndex() {
        val starts = ArrayList<Int>(max(1, documentText.count { it == '\n' } + 1))
        starts += 0
        documentText.forEachIndexed { index, c -> if (c == '\n') starts += index + 1 }
        lineStarts = starts.toIntArray()
    }

    private fun lineCount(): Int = lineStarts.size

    private fun lineStart(line: Int): Int = lineStarts[line.coerceIn(0, lineStarts.lastIndex)]

    private fun lineEnd(lineExclusive: Int): Int {
        if (lineExclusive >= lineStarts.size) return documentText.length
        return lineStarts[lineExclusive]
    }

    private fun findLineForOffset(offset: Int): Int {
        var low = 0
        var high = lineStarts.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    private inner class DocumentView(context: Context) : FrameLayout(context) {
        private val backgroundRect = RectF()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val desiredHeight = (verticalInset.toInt().toLong() * 2L + lineCount().toLong() * lineHeight)
                .coerceIn(1L, Int.MAX_VALUE.toLong() - 1L).toInt()
            setMeasuredDimension(width, max(desiredHeight, MeasureSpec.getSize(heightMeasureSpec)))
            val childWidth = max(1, width)
            val childHeight = max(lineHeight, (windowEndLine - windowStartLine) * lineHeight)
            editor.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val editorTop = verticalInset.toInt() + windowStartLine * lineHeight
            editor.layout(0, 0, right - left, editor.measuredHeight)
            editor.translationY = editorTop.toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val first = (((canvas.clipBounds.top.toFloat() - verticalInset).coerceAtLeast(0f) / lineHeight)
                .toInt()).coerceIn(0, lineCount() - 1)
            val last = (((canvas.clipBounds.bottom.toFloat() - verticalInset).coerceAtLeast(0f) / lineHeight)
                .toInt() + 1).coerceIn(first, lineCount())
            for (line in first until last) {
                val start = lineStart(line)
                val end = if (line + 1 < lineCount()) lineStart(line + 1) else documentText.length
                val textEnd = if (end > start && documentText[end - 1] == '\n') end - 1 else end
                drawHighlights(canvas, start, textEnd, line)
                if (!(line >= windowStartLine && line < windowEndLine)) {
                    val value = documentText.substring(start, textEnd)
                    canvas.drawText(value, horizontalInset, verticalInset + line * lineHeight + baselineOffset, textPaint)
                }
            }
        }

        private fun drawHighlights(canvas: Canvas, lineStartOffset: Int, lineEndOffset: Int, line: Int) {
            highlights.forEach { highlight ->
                val start = max(lineStartOffset, highlight.start)
                val end = min(lineEndOffset, highlight.end)
                if (start >= end) return@forEach
                val textBefore = documentText.substring(lineStartOffset, start)
                val textValue = documentText.substring(start, end)
                val left = horizontalInset + textPaint.measureText(textBefore)
                val right = left + textPaint.measureText(textValue).coerceAtLeast(textPaint.textSize)
                backgroundRect.set(
                    left,
                    verticalInset + line * lineHeight,
                    right,
                    verticalInset + (line + 1) * lineHeight
                )
                linePaint.color = if (highlight.current) {
                    context.getColor(R.color.secondary)
                } else {
                    context.getColor(R.color.inverse_primary)
                }
                canvas.drawRect(backgroundRect, linePaint)
            }
        }
    }
}
