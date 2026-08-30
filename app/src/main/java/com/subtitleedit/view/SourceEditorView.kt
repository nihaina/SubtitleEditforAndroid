package com.subtitleedit.view

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.os.Build
import android.os.SystemClock
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.PopupWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.subtitleedit.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Source editor backed by the same block/list lifecycle as the subtitle list.
 *
 * A subtitle-list item represents one parsed cue; a source-list item represents one physical
 * source line. Empty lines and the empty line after a trailing line ending are real items.
 * RecyclerView owns viewport rendering/recycling, while line split/merge operations use the
 * same item changed/inserted/removed notifications as list-view edits.
 */
class SourceEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DraggableRecyclerView(context, attrs, defStyleAttr) {

    data class Highlight(val start: Int, val end: Int, val current: Boolean = false)

    data class DocumentChange(
        val startLine: Int,
        val oldLineCount: Int,
        val newLineCount: Int,
        val startOffset: Int,
        val oldEndOffset: Int,
        val newEndOffset: Int
    )

    private val lineLayoutManager = LinearLayoutManager(context)
    private val lines = mutableListOf<SourceLineBlock>()
    private val offsets = LineOffsets()
    private val highlights = mutableListOf<Highlight>()
    private val documentChangedListeners = mutableListOf<() -> Unit>()
    private val documentChangeListeners = mutableListOf<(DocumentChange) -> Unit>()
    private var nextLineId = 1L
    private var preferredLineEnding = "\n"
    private val basePaddingLeftPx = (resources.displayMetrics.density * 8f).toInt()
    private val basePaddingTopPx = (resources.displayMetrics.density * 8f).toInt()
    private val basePaddingRightPx = (resources.displayMetrics.density * 8f).toInt()
    private val basePaddingBottomPx = (resources.displayMetrics.density * 8f).toInt()
    private val imeFocusMarginPx = (resources.displayMetrics.density * 8f).toInt()
    private var imeVisible = false
    private var imeInsetBottomPx = 0
    private var imeEnsurePosted = false
    private val selectionEdgeScrollPx = (resources.displayMetrics.density * 24f).toInt()
    private val selectionEdgeZonePx = (resources.displayMetrics.density * 48f).toInt()
    private val selectionTouchSlopPx = ViewConfiguration.get(context).scaledTouchSlop

    private data class DocumentSelection(
        val anchorLine: Int,
        val anchorOffset: Int,
        val focusLine: Int,
        val focusOffset: Int
    )

    private data class SelectionGesture(
        val line: Int,
        val editor: SourceLineEditText,
        val downX: Float,
        val downY: Float
    )

    private var documentSelection: DocumentSelection? = null
    private var selectionGesture: SelectionGesture? = null
    private var selectionTouchMoved = false
    private var selectionActionMode: ActionMode? = null
    // Keep a generous touch target while drawing a compact MT-style marker inside it.
    private val selectionHandleSizePx = (resources.displayMetrics.density * 40f).toInt()
    // The popup remains large enough to grab reliably, but the visible marker matches the
    // compact system/MT handle instead of filling the whole touch target.
    private val selectionHandleVisualSizePx = (resources.displayMetrics.density * 32f).toInt()
    private val selectionHandleColor = context.getColor(R.color.secondary)

    private enum class SelectionHandleSide {
        LEFT,
        RIGHT
    }

    private var customHandleSide: SelectionHandleSide? = null
    private var customHandleAnchor: Int? = null
    private var customHandleDragPoint: Point? = null
    // Raw pointer displacement from the text endpoint. The visible marker is intentionally
    // offset to the outside of the endpoint, so using raw coordinates directly would select a
    // neighboring row before the finger has visibly moved.
    private var customHandleTouchOffsetX = 0f
    private var customHandleTouchOffsetY = 0f
    private var customHandleLastRawX = 0f
    private var customHandleLastRawY = 0f
    private var customLeftHandleOffset: Int? = null
    private var customRightHandleOffset: Int? = null
    private var customHandleTouchFromSource = false
    private var selectionAutoScrollPosted = false
    private var selectionTouchActive = false
    // Once a long-press has published a document selection, the child EditText must no longer
    // receive the remainder of that pointer stream. Otherwise TextView keeps its native handle
    // drag alive underneath the custom document handles and the first handle drag is ignored.
    private var nativeSelectionDragBlocked = false
    private var imeSuppressedEditor: SourceLineEditText? = null
    private val selectionImeSuppressRunnable = Runnable {
        if (selectionTouchActive && documentSelection == null) {
            imeSuppressedEditor?.let(::suppressImeForSelection)
        }
    }
    private var customLeftHandle = SelectionHandlePopup(SelectionHandleSide.LEFT)
    private var customRightHandle = SelectionHandlePopup(SelectionHandleSide.RIGHT)

    private val selectionAutoScrollRunnable = Runnable {
        selectionAutoScrollPosted = false
        if (customHandleSide == null) return@Runnable
        val location = IntArray(2)
        getLocationOnScreen(location)
        val targetY = customHandleLastRawY - customHandleTouchOffsetY
        val localY = targetY - location[1]
        val delta = selectionAutoScrollDelta(localY)
        if (delta == 0) return@Runnable
        scrollBy(0, delta)
        // Resolve the endpoint against the newly visible rows, while keeping the active popup
        // anchored to the same finger hotspot.
        updateCustomHandleDrag(customHandleLastRawX, customHandleLastRawY, scheduleAutoScroll = false)
        maybeAutoScrollForSelection(localY)
    }

    private companion object {
        const val MENU_SELECT_ALL = 0x53450001
        const val MENU_CUT = 0x53450002
        const val MENU_COPY = 0x53450003
        const val MENU_PASTE = 0x53450004
    }

    private val imeEnsureRunnable = Runnable {
        imeEnsurePosted = false
        if (selectionTouchActive || documentSelection != null) return@Runnable
        updateImeBottomPadding()
        // Padding changes are applied during the next layout pass. Defer the visibility check
        // one frame so RecyclerView's new scroll range (including the keyboard tail spacer) is
        // available before attempting to scroll the focused row.
        postOnAnimation { ensureFocusedLineVisible() }
    }

    private val lineAdapter = SourceLineAdapter(
        context = context,
        lines = lines,
        onTextChanged = ::onLineTextChanged,
        onSplitLine = ::splitLineAt,
        onMergePrevious = ::mergeWithPrevious,
        onMergeNext = ::mergeWithNext,
        onMoveVertical = ::moveCursorVertically,
        onExtendVertical = ::extendCursorVertically,
        highlightsForLine = ::highlightsForLine,
        onEditorFocusChanged = ::onEditorFocusChanged,
        onEditorSelectionChanged = ::onEditorSelectionChanged,
        onEditorSelectionActionMode = ::createEditorSelectionActionMode
    )

    init {
        setBackgroundColor(context.getColor(R.color.surface))
        layoutManager = lineLayoutManager
        adapter = lineAdapter
        setHasFixedSize(true)
        setItemViewCacheSize(12)
        clipToPadding = false
        setPadding(
            basePaddingLeftPx,
            basePaddingTopPx,
            basePaddingRightPx,
            basePaddingBottomPx
        )
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val imeType = WindowInsetsCompat.Type.ime()
            val visible = insets.isVisible(imeType)
            val bottom = insets.getInsets(imeType).bottom
            if (visible != imeVisible || bottom != imeInsetBottomPx) {
                imeVisible = visible
                imeInsetBottomPx = bottom
                scheduleImeEnsure()
            }
            insets
        }
        ViewCompat.requestApplyInsets(this)
        replaceLines("")
    }

    /** Observe editor taps so an existing document selection is cleared on a normal tap. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (customHandleTouchFromSource) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> updateCustomHandleDrag(event.rawX, event.rawY)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    finishCustomHandleDrag()
                    customHandleTouchFromSource = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
        if (nativeSelectionDragBlocked) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // Recover if the platform delivered a fresh gesture without the cancellation or
                // ACTION_UP that normally closes the long-press stream.
                nativeSelectionDragBlocked = false
            } else {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        selectionTouchMoved = true
                        return true
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        nativeSelectionDragBlocked = false
                        selectionGesture = null
                        selectionTouchMoved = false
                        selectionTouchActive = false
                        return true
                    }
                }
                return true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val handleSide = selectionHandleSideAtRaw(event.rawX, event.rawY)
                if (handleSide != null && startCustomHandleDrag(handleSide, event.rawX, event.rawY)) {
                    customHandleTouchFromSource = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                val target = editorAt(event.x, event.y)
                selectionTouchActive = target != null
                target?.second?.let(::beginSelectionTouch)
                selectionTouchMoved = false
                selectionGesture = target?.let {
                    SelectionGesture(
                        line = it.first,
                        editor = it.second,
                        downX = event.x,
                        downY = event.y
                    )
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val gesture = selectionGesture
                if (gesture != null &&
                    (abs(event.x - gesture.downX) > selectionTouchSlopPx ||
                        abs(event.y - gesture.downY) > selectionTouchSlopPx)
                ) {
                    selectionTouchMoved = true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> Unit
        }
        val handled = super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val gesture = selectionGesture
                val longPressed = gesture != null &&
                    event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()
                val moved = selectionTouchMoved
                // A moving gesture is a RecyclerView scroll, not a second text-selection mode.
                // A stationary tap explicitly clears an existing document selection.
                if (documentSelection != null && !moved && !longPressed) {
                    clearDocumentSelection()
                }
                selectionGesture = null
                selectionTouchMoved = false
                selectionTouchActive = false
                if (documentSelection == null) {
                    restoreImeSuppression()
                    if (!moved && !longPressed && gesture?.editor?.hasFocus() == true) {
                        scheduleImeEnsure()
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                selectionGesture = null
                selectionTouchMoved = false
                selectionTouchActive = false
                if (documentSelection == null) restoreImeSuppression()
            }
        }
        return handled
    }

    fun addOnDocumentChangedListener(listener: () -> Unit) {
        documentChangedListeners += listener
    }

    fun addOnDocumentChangeListener(listener: (DocumentChange) -> Unit) {
        documentChangeListeners += listener
    }

    fun setDocumentText(value: String, preserveScroll: Boolean = false) {
        if (documentSelection != null) clearDocumentSelection()
        val previousScroll = if (preserveScroll) getDocumentScrollOffset() else 0
        replaceLines(value)
        lineAdapter.notifyDataSetChanged()
        if (preserveScroll) post { scrollToDocumentY(previousScroll) }
    }

    fun getDocumentText(): String = buildString(offsets.documentLength) {
        lines.forEach { line ->
            append(line.text)
            append(line.lineEnding)
        }
    }

    fun replaceDocumentText(value: String) {
        val oldLineCount = lines.size
        val oldLength = offsets.documentLength
        setDocumentText(value)
        notifyDocumentChanged(
            DocumentChange(
                startLine = 0,
                oldLineCount = oldLineCount,
                newLineCount = lines.size,
                startOffset = 0,
                oldEndOffset = oldLength,
                newEndOffset = value.length
            )
        )
    }

    fun getDocumentLineCount(): Int = lines.size

    fun setDocumentEnabled(enabled: Boolean) {
        isEnabled = enabled
        lineAdapter.setEditorEnabled(enabled)
    }

    fun setSearchHighlights(ranges: List<Highlight>) {
        highlights.clear()
        highlights += ranges.sortedBy { it.start }
        lineAdapter.refreshHighlights()
    }

    fun clearSearchHighlights() {
        if (highlights.isEmpty()) return
        highlights.clear()
        lineAdapter.refreshHighlights()
    }

    fun scrollToDocumentOffset(offset: Int) {
        val line = offsets.findLine(offset.coerceIn(0, offsets.documentLength))
        lineLayoutManager.scrollToPositionWithOffset(line, height / 3)
    }

    fun getDocumentScrollOffset(): Int = computeVerticalScrollOffset()

    fun scrollToDocumentY(offset: Int) {
        val rowHeight = (resources.displayMetrics.density * 28f).toInt().coerceAtLeast(1)
        val position = (offset / rowHeight).coerceIn(0, lines.lastIndex)
        lineLayoutManager.scrollToPositionWithOffset(position, -(offset % rowHeight))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (imeVisible && height != oldHeight) scheduleImeEnsure()
        post { updateSelectionHandlePopups() }
    }

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        updateSelectionHandlePopups()
        if (documentSelection != null && customHandleSide == null && selectionActionMode == null) {
            postOnAnimation { ensureSelectionActionMode() }
        }
    }

    override fun onDetachedFromWindow() {
        customLeftHandle.dismissSafely()
        customRightHandle.dismissSafely()
        super.onDetachedFromWindow()
    }

    private fun onEditorFocusChanged() {
        if (selectionTouchActive || documentSelection != null) return
        scheduleImeEnsure()
    }

    /** Prevent a focus gained for a possible long-press from opening the IME prematurely. */
    private fun beginSelectionTouch(editor: SourceLineEditText) {
        if (imeSuppressedEditor !== editor) {
            imeSuppressedEditor?.showSoftInputOnFocus = true
            imeSuppressedEditor = editor
        }
        editor.showSoftInputOnFocus = false
        removeCallbacks(selectionImeSuppressRunnable)
        postDelayed(
            selectionImeSuppressRunnable,
            ViewConfiguration.getLongPressTimeout().toLong()
        )
    }

    /** Keep the long-press selection visual-only and close an IME that was already visible. */
    private fun suppressImeForSelection(editor: SourceLineEditText) {
        removeCallbacks(selectionImeSuppressRunnable)
        beginSelectionTouch(editor)
        removeCallbacks(selectionImeSuppressRunnable)
        val inputMethod = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        inputMethod?.hideSoftInputFromWindow(editor.windowToken, 0)
    }

    private fun restoreImeSuppression() {
        removeCallbacks(selectionImeSuppressRunnable)
        imeSuppressedEditor?.showSoftInputOnFocus = true
        imeSuppressedEditor = null
    }

    private fun onEditorSelectionChanged(position: Int, editor: SourceLineEditText) {
        onEditorFocusChanged()
        // A handle drag owns the document endpoints for the duration of the gesture. Ignore the
        // child EditText's transient callbacks while that gesture is active; single-line and
        // multi-line selections otherwise follow the same document-level path.
        if (customHandleSide != null) return
        if (selectionTouchActive && selectionTouchMoved && documentSelection != null) return
        val start = editor.selectionStart.coerceIn(0, editor.length())
        val end = editor.selectionEnd.coerceIn(0, editor.length())
        val selection = documentSelection
        if (selection == null) {
            // The child callback also fires for keyboard/programmatic selection changes and for
            // a plain text drag. Do not turn those into a document selection. A new document
            // range is published only by SourceSelectionActionMode (long-press) or by the
            // custom document handle/vertical-extension paths.
            return
        }
        if (selection.anchorLine != position || selection.focusLine != position) {
            // A cross-row document range is represented natively by a caret in the focus row.
            // Shift+Left/Right then changes that child selection; propagate its new endpoint
            // instead of leaving the document range frozen at the drag result.
            if (selection.focusLine == position && start != end) {
                val previousFocus = selection.focusOffset.coerceIn(0, editor.length())
                val focus = when {
                    start == previousFocus -> end
                    end == previousFocus -> start
                    else -> end
                }.coerceIn(0, editor.length())
                val updated = selection.copy(focusOffset = focus)
                if (updated != selection) {
                    resetHandleEndpointMapping()
                    documentSelection = updated
                    lineAdapter.refreshHighlights()
                    selectionActionMode?.invalidateContentRect()
                    updateSelectionHandlePopups()
                }
            }
            return
        }
        if (start == end) {
            // TextView can briefly collapse its child selection while Android is creating or
            // updating ActionMode/handles. That transient callback must not erase the document
            // range (otherwise the custom BackgroundColorSpan disappears immediately after a
            // long-press). A normal tap is handled by dispatchTouchEvent once ActionMode has
            // finished, so it remains the single place that clears an established selection.
            return
        }
        val updated = selection.copy(
            anchorOffset = start,
            focusOffset = end
        )
        if (updated != selection) {
            resetHandleEndpointMapping()
            documentSelection = updated
            lineAdapter.refreshHighlights()
            selectionActionMode?.invalidateContentRect()
            updateSelectionHandlePopups()
        }
    }

    private fun scheduleImeEnsure() {
        if (imeEnsurePosted) return
        imeEnsurePosted = true
        post(imeEnsureRunnable)
    }

    /**
     * Keep a keyboard-sized, line-number-free tail area while the IME is visible. This lets the
     * last real source line scroll above the keyboard instead of being permanently trapped at
     * the bottom edge of the RecyclerView.
     */
    private fun updateImeBottomPadding() {
        // Use the actual window overlap when available, but retain the IME inset as a tail
        // spacer on adjustResize devices where the RecyclerView itself has already shrunk.
        val overlap = if (imeVisible) {
            max(calculateImeOverlap(), imeInsetBottomPx)
        } else {
            0
        }
        val targetBottom = basePaddingBottomPx + overlap
        if (paddingBottom != targetBottom) {
            setPadding(
                basePaddingLeftPx,
                basePaddingTopPx,
                basePaddingRightPx,
                targetBottom
            )
        }
    }

    private fun calculateImeOverlap(): Int {
        val visibleFrame = Rect()
        getWindowVisibleDisplayFrame(visibleFrame)
        if (visibleFrame.bottom <= 0 || height <= 0) return imeInsetBottomPx
        val location = IntArray(2)
        getLocationOnScreen(location)
        return (location[1] + height - visibleFrame.bottom).coerceAtLeast(0)
    }

    /** Scroll only when the focused visual line is actually hidden below the IME. */
    private fun ensureFocusedLineVisible() {
        if (!imeVisible || selectionTouchActive || documentSelection != null || height <= 0) return
        val focusedEditor = findFocus() as? SourceLineEditText ?: return
        val caretLayout = focusedEditor.layout
        val caretOffset = focusedEditor.selectionStart.coerceIn(0, focusedEditor.length())
        val rect = if (caretLayout != null) {
            val visualLine = caretLayout.getLineForOffset(caretOffset)
            Rect(
                0,
                caretLayout.getLineTop(visualLine),
                focusedEditor.width,
                caretLayout.getLineBottom(visualLine)
            )
        } else {
            Rect(0, 0, focusedEditor.width, focusedEditor.height)
        }
        offsetDescendantRectToMyCoords(focusedEditor, rect)

        val imeOverlap = calculateImeOverlap()
        val visibleBottom = height - imeOverlap - imeFocusMarginPx
        val delta = rect.bottom - visibleBottom
        if (delta > 0) scrollBy(0, delta)
    }

    private fun editorAt(x: Float, y: Float): Pair<Int, SourceLineEditText>? {
        val itemView = findChildViewUnder(x, y) ?: return null
        val holder = getChildViewHolder(itemView)
        val position = holder.bindingAdapterPosition
        if (position == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return null
        val editor = itemView.findViewById<SourceLineEditText>(R.id.etSourceLine)
            ?: return null
        return position to editor
    }

    private fun offsetForRawPosition(editor: SourceLineEditText, rawX: Float, rawY: Float): Int {
        val location = IntArray(2)
        editor.getLocationOnScreen(location)
        return editor.getOffsetForPosition(
            rawX - location[0],
            rawY - location[1]
        ).coerceIn(0, editor.length())
    }

    private fun startCustomHandleDrag(
        side: SelectionHandleSide,
        rawX: Float = Float.NaN,
        rawY: Float = Float.NaN,
        localTouchOffsetX: Float? = null,
        localTouchOffsetY: Float? = null
    ): Boolean {
        val range = selectedDocumentRange() ?: return false
        if (range.first >= range.second) return false
        // Keep the physical marker that received the touch as the moving endpoint. Do not infer
        // start/end from proximity: when the selection is reversed, or when both endpoints are
        // on the same visual row, that heuristic can map the right marker to the left endpoint
        // and make downward cross-row dragging impossible.
        if (customLeftHandleOffset == null || customRightHandleOffset == null) {
            customLeftHandleOffset = range.first
            customRightHandleOffset = range.second
        }
        val movingOffset = if (side == SelectionHandleSide.LEFT) {
            customLeftHandleOffset ?: range.first
        } else {
            customRightHandleOffset ?: range.second
        }
        val fixedOffset = if (side == SelectionHandleSide.LEFT) {
            customRightHandleOffset ?: range.second
        } else {
            customLeftHandleOffset ?: range.first
        }
        customHandleSide = side
        customHandleAnchor = fixedOffset
        val endpoint = selectionEndpointPoint(movingOffset)
        if (localTouchOffsetX != null && localTouchOffsetY != null) {
            customHandleTouchOffsetX = localTouchOffsetX
            customHandleTouchOffsetY = localTouchOffsetY
        } else if (endpoint != null && !rawX.isNaN() && !rawY.isNaN()) {
            val endpointOnScreen = windowPointToScreen(endpoint)
            customHandleTouchOffsetX = rawX - endpointOnScreen.x
            customHandleTouchOffsetY = rawY - endpointOnScreen.y
        } else {
            customHandleTouchOffsetX = 0f
            customHandleTouchOffsetY = 0f
        }
        customHandleLastRawX = if (rawX.isNaN()) 0f else rawX
        customHandleLastRawY = if (rawY.isNaN()) 0f else rawY
        customHandleDragPoint = null
        return true
    }

    private fun updateCustomHandleDrag(
        rawX: Float,
        rawY: Float,
        scheduleAutoScroll: Boolean = true
    ) {
        val side = customHandleSide ?: return
        val fixedOffset = customHandleAnchor ?: return
        customHandleLastRawX = rawX
        customHandleLastRawY = rawY
        val targetRawX = rawX - customHandleTouchOffsetX
        val targetRawY = rawY - customHandleTouchOffsetY
        val sourceLocation = IntArray(2)
        getLocationOnScreen(sourceLocation)
        if (scheduleAutoScroll) maybeAutoScrollForSelection(targetRawY - sourceLocation[1])
        customHandleDragPoint = rawPointToWindow(targetRawX, targetRawY)
        val target = editorAtRaw(targetRawY) ?: return
        val movingOffset = absoluteOffset(
            target.first,
            offsetForRawPosition(target.second, targetRawX, targetRawY)
        )
        val crossesOtherHandle = when (side) {
            SelectionHandleSide.LEFT -> movingOffset > fixedOffset
            SelectionHandleSide.RIGHT -> movingOffset < fixedOffset
        }
        if (crossesOtherHandle) {
            // Keep the dragged window under the finger, but exchange its logical direction and
            // endpoint role once it passes the stationary handle. This is the same behavior users
            // expect from native text selection handles and avoids a disappearing/teleporting
            // marker when the two endpoints cross.
            swapCustomHandleSides()
        }
        val fixedLineOffset = lineAndColumnForAbsoluteOffset(fixedOffset)
        documentSelection = DocumentSelection(
            anchorLine = fixedLineOffset.first,
            anchorOffset = fixedLineOffset.second,
            focusLine = target.first,
            focusOffset = (movingOffset - offsets.start(target.first))
                .coerceIn(0, lines[target.first].text.length)
        )
        if (customHandleSide == SelectionHandleSide.LEFT) {
            customLeftHandleOffset = movingOffset
        } else {
            customRightHandleOffset = movingOffset
        }
        refreshVisibleSelectionHighlights()
        // Do not ask the floating ActionMode to relayout on every MOVE. Its platform handle
        // manager may synchronously reposition/cancel a PopupWindow that currently owns the
        // pointer stream (the failure was especially visible on the right handle). The custom
        // popup is already kept under the finger; refresh the toolbar once the drag ends.
        updateSelectionHandlePopups()
    }

    private fun refreshVisibleSelectionHighlights() {
        // Update attached holders in place instead of dispatching a RecyclerView range change
        // for every pointer MOVE. Rebinding during a handle drag can invalidate the popup's
        // touch target and is the source of the apparent one-sided cross-line behavior.
        for (childIndex in 0 until childCount) {
            val item = getChildAt(childIndex)
            val holder = getChildViewHolder(item) as? SourceLineAdapter.LineViewHolder ?: continue
            val position = holder.bindingAdapterPosition
            if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                holder.bindHighlights(highlightsForLine(position))
            }
        }
    }

    private fun finishCustomHandleDrag() {
        val selection = documentSelection
        customHandleSide = null
        customHandleAnchor = null
        customHandleDragPoint = null
        customHandleTouchOffsetX = 0f
        customHandleTouchOffsetY = 0f
        customHandleTouchFromSource = false
        removeCallbacks(selectionAutoScrollRunnable)
        selectionAutoScrollPosted = false
        if (selection != null) syncNativeSelection(selection)
        selectionActionMode?.invalidateContentRect()
        updateSelectionHandlePopups()
    }

    /** Keep the focused EditText's native caret/range aligned with the document endpoint. */
    private fun syncNativeSelection(selection: DocumentSelection) {
        val focusLine = selection.focusLine.coerceIn(0, lines.lastIndex)
        val focusColumn = selection.focusOffset.coerceIn(0, lines[focusLine].text.length)
        val anchorLine = selection.anchorLine.coerceIn(0, lines.lastIndex)
        val anchorColumn = selection.anchorOffset.coerceIn(0, lines[anchorLine].text.length)
        scrollToPosition(focusLine)
        if (anchorLine == focusLine) {
            lineAdapter.requestLineSelection(focusLine, anchorColumn, focusColumn)
        } else {
            // A child EditText cannot represent a cross-row range. Put its native caret at the
            // document focus endpoint; the SourceEditorView highlight remains authoritative.
            lineAdapter.requestLineFocus(focusLine, focusColumn)
        }
    }

    private fun editorAtRaw(rawY: Float): Pair<Int, SourceLineEditText>? {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val localY = rawY - location[1]
        // Resolve the logical row from Y only. The handle is often outside the editor's X range
        // (especially at the right edge), so findChildViewUnder(x, y) can otherwise reject a
        // valid row and make the drag appear to stop at the previous line.
        var nearest: Pair<Int, SourceLineEditText>? = null
        var nearestDistance = Float.MAX_VALUE
        for (childIndex in 0 until childCount) {
            val item = getChildAt(childIndex)
            val holder = getChildViewHolder(item)
            val position = holder.bindingAdapterPosition
            if (position == androidx.recyclerview.widget.RecyclerView.NO_POSITION) continue
            val top = item.top.toFloat()
            val bottom = item.bottom.toFloat()
            if (localY >= top && localY < bottom) {
                val editor = item.findViewById<SourceLineEditText>(R.id.etSourceLine)
                if (editor != null) return position to editor
            }
            val distance = when {
                localY < top -> top - localY
                localY > bottom -> localY - bottom
                else -> 0f
            }
            if (distance < nearestDistance) {
                val editor = item.findViewById<SourceLineEditText>(R.id.etSourceLine)
                if (editor != null) {
                    nearest = position to editor
                    nearestDistance = distance
                }
            }
        }
        return nearest
    }

    private fun selectionHandleSideAtRaw(rawX: Float, rawY: Float): SelectionHandleSide? {
        if (customHandleSide != null) return null
        val range = selectedDocumentRange() ?: return null
        if (range.first >= range.second) return null
        val leftOffset = customLeftHandleOffset ?: range.first
        val rightOffset = customRightHandleOffset ?: range.second
        val candidates = arrayOf(
            SelectionHandleSide.LEFT to leftOffset,
            SelectionHandleSide.RIGHT to rightOffset
        )
        var closest: SelectionHandleSide? = null
        var closestDistance = Float.MAX_VALUE
        candidates.forEach { (side, offset) ->
            val endpoint = selectionEndpointPoint(offset) ?: return@forEach
            val screenPoint = windowPointToScreen(endpoint)
            val centerX = screenPoint.x + if (side == SelectionHandleSide.LEFT) {
                -selectionHandleVisualSizePx * 0.5f
            } else {
                selectionHandleVisualSizePx * 0.5f
            }
            val centerY = screenPoint.y + selectionHandleVisualSizePx * 0.40f
            val distance = (rawX - centerX) * (rawX - centerX) +
                (rawY - centerY) * (rawY - centerY)
            val radius = selectionHandleSizePx * 0.70f
            val popupContains = if (side == SelectionHandleSide.LEFT) {
                customLeftHandle.containsRaw(rawX, rawY)
            } else {
                customRightHandle.containsRaw(rawX, rawY)
            }
            if ((popupContains || distance <= radius * radius) && distance < closestDistance) {
                closest = side
                closestDistance = distance
            }
        }
        return closest
    }

    private fun lineAndColumnForAbsoluteOffset(offset: Int): Pair<Int, Int> {
        val safe = offset.coerceIn(0, offsets.documentLength)
        val line = offsets.findLine(safe).coerceIn(0, lines.lastIndex)
        return line to (safe - offsets.start(line)).coerceIn(0, lines[line].text.length)
    }

    private fun updateSelectionHandlePopups() {
        val range = selectedDocumentRange()
        if (range == null) {
            customLeftHandle.dismissSafely()
            customRightHandle.dismissSafely()
            return
        }
        // While a handle is being dragged, keep that popup under the finger. Repositioning the
        // touched PopupWindow to the newly resolved text endpoint can make Android cancel its
        // pointer stream when the endpoint crosses into another RecyclerView item.
        val dragPoint = customHandleDragPoint
        val leftOffset = customLeftHandleOffset ?: range.first
        val rightOffset = customRightHandleOffset ?: range.second
        if (range.first >= range.second) {
            if (customHandleSide == null) {
                customLeftHandle.dismissSafely()
                customRightHandle.dismissSafely()
            } else {
                // Keep the merged handles alive until ACTION_UP so the user can continue dragging
                // back across the same endpoint instead of losing the active gesture.
                val point = dragPoint ?: selectionEndpointPoint(leftOffset)
                customLeftHandle.positionAt(point)
                customRightHandle.positionAt(point)
            }
            return
        }
        when (customHandleSide) {
            SelectionHandleSide.LEFT -> {
                // Keep the active popup under the finger and refresh the inactive endpoint as
                // rows move underneath it. Only the active window owns the pointer stream.
                customLeftHandle.positionAt(dragPoint ?: selectionEndpointPoint(leftOffset))
                customRightHandle.positionAt(selectionEndpointPoint(rightOffset))
            }
            SelectionHandleSide.RIGHT -> {
                customRightHandle.positionAt(dragPoint ?: selectionEndpointPoint(rightOffset))
                customLeftHandle.positionAt(selectionEndpointPoint(leftOffset))
            }
            null -> {
                customLeftHandle.positionAt(selectionEndpointPoint(leftOffset))
                customRightHandle.positionAt(selectionEndpointPoint(rightOffset))
            }
        }
    }

    private fun rawPointToWindow(rawX: Float, rawY: Float): Point {
        val screenLocation = IntArray(2)
        val windowLocation = IntArray(2)
        getLocationOnScreen(screenLocation)
        getLocationInWindow(windowLocation)
        return Point(
            (rawX - screenLocation[0] + windowLocation[0]).toInt(),
            (rawY - screenLocation[1] + windowLocation[1]).toInt()
        )
    }

    private fun windowPointToScreen(point: Point): Point {
        val screenLocation = IntArray(2)
        val windowLocation = IntArray(2)
        getLocationOnScreen(screenLocation)
        getLocationInWindow(windowLocation)
        return Point(
            point.x - windowLocation[0] + screenLocation[0],
            point.y - windowLocation[1] + screenLocation[1]
        )
    }

    private fun selectionEndpointPoint(absoluteOffset: Int): Point? {
        val lineAndColumn = lineAndColumnForAbsoluteOffset(absoluteOffset)
        val holder = findViewHolderForAdapterPosition(lineAndColumn.first)
        if (holder == null) return offscreenEndpointPoint(lineAndColumn.first)
        val editor = holder.itemView.findViewById<SourceLineEditText>(R.id.etSourceLine)
            ?: return offscreenEndpointPoint(lineAndColumn.first)
        val layout = editor.layout ?: return offscreenEndpointPoint(lineAndColumn.first)
        val column = lineAndColumn.second.coerceIn(0, editor.length())
        val visualLine = layout.getLineForOffset(column)
        val location = IntArray(2)
        editor.getLocationInWindow(location)
        val availableHeight = editor.height - editor.compoundPaddingTop - editor.compoundPaddingBottom
        val extraHeight = (availableHeight - layout.height).coerceAtLeast(0)
        val gravityOffset = when (editor.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> extraHeight
            Gravity.CENTER_VERTICAL -> extraHeight / 2
            else -> 0
        }
        val x = location[0] + editor.compoundPaddingLeft - editor.scrollX +
            layout.getPrimaryHorizontal(column)
        val y = location[1] + editor.compoundPaddingTop + gravityOffset - editor.scrollY +
            layout.getLineBottom(visualLine)
        return Point(x.toInt(), y.toInt())
    }

    private fun maybeAutoScrollForSelection(y: Float) {
        if (selectionAutoScrollDelta(y) == 0 || selectionAutoScrollPosted) return
        selectionAutoScrollPosted = true
        postOnAnimation(selectionAutoScrollRunnable)
    }

    private fun selectionAutoScrollDelta(y: Float): Int = when {
        y < selectionEdgeZonePx -> -selectionEdgeScrollPx
        y > height - selectionEdgeZonePx -> selectionEdgeScrollPx
        else -> 0
    }

    private fun offscreenEndpointPoint(line: Int): Point? {
        if (!isAttachedToWindow || height <= 0) return null
        val windowLocation = IntArray(2)
        getLocationInWindow(windowLocation)
        var firstVisible = Int.MAX_VALUE
        var lastVisible = Int.MIN_VALUE
        var firstTop = 0
        var lastBottom = 0
        var visibleHeightTotal = 0
        var visibleHeightCount = 0
        for (childIndex in 0 until childCount) {
            val child = getChildAt(childIndex)
            val holder = getChildViewHolder(child)
            val position = holder.bindingAdapterPosition
            if (position == androidx.recyclerview.widget.RecyclerView.NO_POSITION) continue
            if (position < firstVisible) firstTop = child.top
            if (position > lastVisible) lastBottom = child.bottom
            firstVisible = min(firstVisible, position)
            lastVisible = max(lastVisible, position)
            visibleHeightTotal += child.height
            visibleHeightCount++
        }
        if (firstVisible == Int.MAX_VALUE) return null
        val rowHeight = (visibleHeightTotal / visibleHeightCount.coerceAtLeast(1))
            .coerceAtLeast((resources.displayMetrics.density * 28f).toInt())
        val y = when {
            line < firstVisible -> windowLocation[1] + firstTop -
                (firstVisible - line) * rowHeight
            line > lastVisible -> windowLocation[1] + lastBottom +
                (line - lastVisible) * rowHeight
            else -> windowLocation[1] + height / 2
        }
        val x = windowLocation[0] + paddingLeft
        return Point(x, y)
    }

    private fun selectedDocumentRange(): Pair<Int, Int>? {
        val selection = documentSelection ?: return null
        val anchor = absoluteOffset(selection.anchorLine, selection.anchorOffset)
        val focus = absoluteOffset(selection.focusLine, selection.focusOffset)
        return min(anchor, focus) to max(anchor, focus)
    }

    private fun absoluteOffset(line: Int, offset: Int): Int {
        val safeLine = line.coerceIn(0, lines.lastIndex)
        return (offsets.start(safeLine) + offset.coerceIn(0, lines[safeLine].text.length))
    }

    private fun clearDocumentSelection() {
        val hadSelection = documentSelection != null
        documentSelection = null
        selectionGesture = null
        customHandleSide = null
        customHandleAnchor = null
        customHandleDragPoint = null
        customHandleTouchOffsetX = 0f
        customHandleTouchOffsetY = 0f
        customHandleTouchFromSource = false
        nativeSelectionDragBlocked = false
        resetHandleEndpointMapping()
        removeCallbacks(selectionAutoScrollRunnable)
        selectionAutoScrollPosted = false
        val mode = selectionActionMode
        selectionActionMode = null
        mode?.finish()
        if (hadSelection) lineAdapter.refreshHighlights()
        customLeftHandle.dismissSafely()
        customRightHandle.dismissSafely()
        restoreImeSuppression()
    }

    private fun resetHandleEndpointMapping() {
        customLeftHandleOffset = null
        customRightHandleOffset = null
    }

    /** Stop TextView's in-progress long-press/selection gesture at the document boundary. */
    private fun blockNativeSelectionDrag(editor: SourceLineEditText) {
        nativeSelectionDragBlocked = true
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            editor.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    private fun swapCustomHandleSides() {
        val popup = customLeftHandle
        customLeftHandle = customRightHandle
        customRightHandle = popup
        customLeftHandle.setSide(SelectionHandleSide.LEFT)
        customRightHandle.setSide(SelectionHandleSide.RIGHT)
        val offset = customLeftHandleOffset
        customLeftHandleOffset = customRightHandleOffset
        customRightHandleOffset = offset
        customHandleSide = when (customHandleSide) {
            SelectionHandleSide.LEFT -> SelectionHandleSide.RIGHT
            SelectionHandleSide.RIGHT -> SelectionHandleSide.LEFT
            null -> null
        }
    }

    private fun copyDocumentSelection(): Boolean {
        val range = selectedDocumentRange() ?: return false
        if (range.first >= range.second) return false
        val text = getDocumentText().substring(range.first, range.second)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("source-selection", text))
        selectionActionMode?.invalidate()
        return true
    }

    private fun clipboardText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context).toString()
    }

    private fun hasClipboardText(): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        return clipboard.primaryClip?.itemCount?.let { it > 0 } == true
    }

    private fun cutDocumentSelection(): Boolean {
        val range = selectedDocumentRange() ?: return false
        if (range.first >= range.second) return false
        if (!copyDocumentSelection()) return false

        val oldText = getDocumentText()
        val startLine = offsets.findLine(range.first.coerceIn(0, oldText.length))
        val updatedText = oldText.removeRange(range.first, range.second)
        val oldLineCount = lines.size
        setDocumentText(updatedText, preserveScroll = true)
        notifyDocumentChanged(
            DocumentChange(
                startLine = startLine,
                oldLineCount = oldLineCount,
                newLineCount = lines.size,
                startOffset = range.first,
                oldEndOffset = range.second,
                newEndOffset = range.first
            )
        )
        clearDocumentSelection()
        val targetLine = offsets.findLine(range.first.coerceIn(0, offsets.documentLength))
        val targetColumn = range.first - offsets.start(targetLine)
        requestFocus(targetLine, targetColumn)
        return true
    }

    private fun pasteDocumentSelection(): Boolean {
        val pasted = clipboardText() ?: return false
        val range = selectedDocumentRange() ?: return false
        if (range.first >= range.second) return false
        replaceDocumentRange(range, pasted)
        return true
    }

    private fun replaceDocumentRange(range: Pair<Int, Int>, replacement: String) {
        val oldText = getDocumentText()
        val startLine = offsets.findLine(range.first.coerceIn(0, oldText.length))
        val updatedText = buildString(oldText.length - (range.second - range.first) + replacement.length) {
            append(oldText, 0, range.first)
            append(replacement)
            append(oldText, range.second, oldText.length)
        }
        val oldLineCount = lines.size
        setDocumentText(updatedText, preserveScroll = true)
        notifyDocumentChanged(
            DocumentChange(
                startLine = startLine,
                oldLineCount = oldLineCount,
                newLineCount = lines.size,
                startOffset = range.first,
                oldEndOffset = range.second,
                newEndOffset = range.first + replacement.length
            )
        )
        clearDocumentSelection()
        val targetOffset = (range.first + replacement.length).coerceIn(0, offsets.documentLength)
        val targetLine = offsets.findLine(targetOffset)
        val targetColumn = targetOffset - offsets.start(targetLine)
        requestFocus(targetLine, targetColumn)
    }

    private fun selectAllDocument() {
        if (lines.isEmpty()) return
        resetHandleEndpointMapping()
        documentSelection = DocumentSelection(
            anchorLine = 0,
            anchorOffset = 0,
            focusLine = lines.lastIndex,
            focusOffset = lines.last().text.length
        )
        lineAdapter.refreshHighlights()
        selectionActionMode?.invalidateContentRect()
        updateSelectionHandlePopups()
    }

    private fun selectionHighlightForLine(position: Int): SourceLineHighlight? {
        val range = selectedDocumentRange() ?: return null
        if (range.first >= range.second) return null
        val line = lines.getOrNull(position) ?: return null
        val lineStart = offsets.start(position)
        val lineEnd = lineStart + line.text.length
        val start = max(range.first, lineStart)
        val end = min(range.second, lineEnd)
        if (start >= end) return null
        return SourceLineHighlight(
            start = start - lineStart,
            end = end - lineStart,
            color = context.getColor(R.color.source_selection)
        )
    }

    private fun createEditorSelectionActionMode(
        position: Int,
        editor: SourceLineEditText
    ): ActionMode.Callback = SourceSelectionActionMode(position, editor)

    /**
     * Selection handles live in their own PopupWindow, just like Android's TextView handles.
     * Keeping these handles owned by the source view lets a drag continue when the pointer moves
     * over a different RecyclerView item (native handles are bound to one EditText only).
     */
    private inner class SelectionHandlePopup(
        private var side: SelectionHandleSide
    ) : PopupWindow(context) {
        private val handleView = SelectionHandleView(context, side)
        private var lastWindowX = Int.MIN_VALUE
        private var lastWindowY = Int.MIN_VALUE

        init {
            width = selectionHandleSizePx
            height = selectionHandleSizePx
            contentView = handleView
            isFocusable = false
            isTouchable = true
            isOutsideTouchable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isClippingEnabled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // A line-end handle can sit on the screen edge. Keep the complete touch target
                // available so dragging it vertically is not cut off by WindowManager clipping.
                setIsClippedToScreen(false)
            }
            windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
            elevation = resources.displayMetrics.density * 16f
            // PopupDecorView dispatches through this interceptor before it tries to route the
            // event to the tiny content view. Handling the gesture at the window boundary keeps
            // the drag alive when the pointer leaves the marker or crosses a RecyclerView row.
            setTouchInterceptor { _, event -> handleTouch(event) }
        }

        fun setSide(newSide: SelectionHandleSide) {
            if (side == newSide) return
            side = newSide
            handleView.setSide(newSide)
        }

        private fun handleTouch(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!startCustomHandleDrag(
                            side,
                            event.rawX,
                            event.rawY,
                            event.x - hotspotX(side),
                            event.y - hotspotY()
                        )
                    ) return false
                    handleView.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> updateCustomHandleDrag(event.rawX, event.rawY)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    finishCustomHandleDrag()
                    handleView.parent?.requestDisallowInterceptTouchEvent(false)
                }
                else -> return false
            }
            return true
        }

        fun positionAt(point: Point?) {
            // A PopupWindow's content view is not attached until the popup is shown. Checking
            // the popup itself here therefore made every first placement immediately dismiss
            // the handle. The source RecyclerView is the actual owner/attachment anchor.
            if (point == null || !this@SourceEditorView.isAttachedToWindow) {
                return
            }
            try {
                // showAtLocation() consumes WindowManager coordinates relative to the attached
                // application window. selectionEndpointPoint() already returns that same coordinate
                // space via getLocationInWindow(); subtracting the RecyclerView's location here would
                // move the popup a second time (often completely off the text).
                // The endpoint is the upper inside corner of the directional handle, rather than
                // the center of its touch target. This leaves the left marker extending left and the
                // right marker extending right, so short selections remain independently tappable.
                val x = point.x - hotspotX(side)
                val y = point.y - hotspotY()
                if (x == lastWindowX && y == lastWindowY && isShowing) return
                if (isShowing) {
                    update(x, y, width, height)
                } else if (this@SourceEditorView.windowToken != null) {
                    showAtLocation(this@SourceEditorView, Gravity.TOP or Gravity.START, x, y)
                } else {
                    return
                }
                lastWindowX = x
                lastWindowY = y
            } catch (_: RuntimeException) {
                // WindowManager can invalidate a sub-panel between a RecyclerView scroll and
                // this callback. Treat that frame as unavailable; the next layout callback will
                // show the handle again once the host token is valid.
                dismissSafely()
            }
        }

        fun dismissSafely() {
            lastWindowX = Int.MIN_VALUE
            lastWindowY = Int.MIN_VALUE
            try {
                if (isShowing) dismiss()
            } catch (_: RuntimeException) {
                // The host window may already be detached.
            }
        }

        fun containsRaw(rawX: Float, rawY: Float): Boolean {
            if (!isShowing || lastWindowX == Int.MIN_VALUE || lastWindowY == Int.MIN_VALUE) {
                return false
            }
            val topLeft = windowPointToScreen(Point(lastWindowX, lastWindowY))
            val slop = selectionTouchSlopPx.toFloat()
            return rawX >= topLeft.x - slop && rawX <= topLeft.x + width + slop &&
                rawY >= topLeft.y - slop && rawY <= topLeft.y + height + slop
        }
    }

    private fun hotspotX(side: SelectionHandleSide): Int = when (side) {
        // Keep the text endpoint at the same local hotspot used by the fallback marker. The
        // popup remains generous enough to grab without pushing the visible marker away from
        // the selected text edge.
        SelectionHandleSide.LEFT -> (selectionHandleSizePx * 0.80f).toInt()
        SelectionHandleSide.RIGHT -> (selectionHandleSizePx * 0.20f).toInt()
    }

    private fun hotspotY(): Int = (selectionHandleSizePx * 0.20f).toInt()

    private inner class SelectionHandleView(
        context: Context,
        initialSide: SelectionHandleSide
    ) : View(context) {
        private var side = initialSide
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = selectionHandleColor
            style = Paint.Style.FILL
        }
        private var systemHandleDrawable: Drawable? = loadSystemHandleDrawable(initialSide)

        @SuppressLint("ResourceType")
        private fun loadSystemHandleDrawable(side: SelectionHandleSide): Drawable? =
            context.obtainStyledAttributes(
                intArrayOf(
                    if (side == SelectionHandleSide.LEFT) {
                        android.R.attr.textSelectHandleLeft
                    } else {
                        android.R.attr.textSelectHandleRight
                    },
                    android.R.attr.textSelectHandle
                )
            ).let { attributes ->
                try {
                    (attributes.getDrawable(0) ?: attributes.getDrawable(1))?.mutate()
                } finally {
                    attributes.recycle()
                }
            }

        fun setSide(newSide: SelectionHandleSide) {
            if (side == newSide) return
            side = newSide
            systemHandleDrawable = loadSystemHandleDrawable(newSide)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val visualSize = selectionHandleVisualSizePx.coerceAtMost(width).coerceAtMost(height)
            val visualTop = (height - visualSize) / 2f
            systemHandleDrawable?.let { drawable ->
                val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: width
                val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: height
                val scale = min(
                    visualSize.toFloat() / intrinsicWidth.coerceAtLeast(1),
                    visualSize.toFloat() / intrinsicHeight.coerceAtLeast(1)
                )
                val drawWidth = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
                val drawHeight = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
                val anchorX = hotspotX(side)
                val left = if (side == SelectionHandleSide.LEFT) {
                    anchorX - drawWidth
                } else {
                    anchorX
                }
                val top = (height - drawHeight) / 2
                drawable.setBounds(left, top, left + drawWidth, top + drawHeight)
                drawable.draw(canvas)
                return
            }
            val centerX = if (side == SelectionHandleSide.LEFT) {
                width * 0.80f
            } else {
                width * 0.20f
            }
            val stemWidth = visualSize * 0.09f
            val stemTop = visualTop + if (side == SelectionHandleSide.LEFT) visualSize * 0.10f else visualSize * 0.28f
            val stemBottom = visualTop + if (side == SelectionHandleSide.LEFT) visualSize * 0.58f else visualSize * 0.78f
            canvas.drawRoundRect(
                centerX - stemWidth,
                stemTop,
                centerX + stemWidth,
                stemBottom,
                stemWidth,
                stemWidth,
                paint
            )
            val circleY = visualTop + if (side == SelectionHandleSide.LEFT) visualSize * 0.68f else visualSize * 0.32f
            canvas.drawCircle(centerX, circleY, visualSize * 0.22f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Kept as a fallback for devices that bypass PopupWindow's touch interceptor.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!startCustomHandleDrag(
                            side,
                            event.rawX,
                            event.rawY,
                            event.x - hotspotX(side),
                            event.y - hotspotY()
                        )
                    ) return false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> updateCustomHandleDrag(event.rawX, event.rawY)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    finishCustomHandleDrag()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
    }

    private fun addSelectionMenu(menu: Menu) {
        menu.clear()
        menu.add(Menu.NONE, MENU_SELECT_ALL, 0, R.string.source_selection_select_all).apply {
            setIcon(R.drawable.ic_select_all)
            setContentDescription(context.getString(R.string.source_selection_select_all))
            setTooltipText(context.getString(R.string.source_selection_select_all))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(Menu.NONE, MENU_CUT, 1, R.string.source_selection_cut).apply {
            setIcon(R.drawable.ic_content_cut)
            setContentDescription(context.getString(R.string.source_selection_cut))
            setTooltipText(context.getString(R.string.source_selection_cut))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(Menu.NONE, MENU_COPY, 2, R.string.source_selection_copy).apply {
            setIcon(R.drawable.ic_content_copy)
            setContentDescription(context.getString(R.string.source_selection_copy))
            setTooltipText(context.getString(R.string.source_selection_copy))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(Menu.NONE, MENU_PASTE, 3, R.string.source_selection_paste).apply {
            setIcon(R.drawable.ic_content_paste)
            setContentDescription(context.getString(R.string.source_selection_paste))
            setTooltipText(context.getString(R.string.source_selection_paste))
            isEnabled = hasClipboardText()
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    /** Return the caret rectangle in the coordinates expected by a floating ActionMode. */
    private fun selectionContentRect(
        view: View,
        sourceLine: Int?,
        sourceEditor: SourceLineEditText?
    ): Rect? {
        val selection = documentSelection ?: return null
        val targetLine = selection.focusLine.coerceIn(0, lines.lastIndex)
        val editor = if (sourceLine == targetLine && sourceEditor != null) {
            sourceEditor
        } else {
            findViewHolderForAdapterPosition(targetLine)?.itemView
            ?.findViewById<SourceLineEditText>(R.id.etSourceLine)
            ?: findFocus() as? SourceLineEditText
            ?: return null
        }
        val offset = if (targetLine == selection.focusLine) {
            selection.focusOffset.coerceIn(0, editor.length())
        } else {
            editor.selectionEnd.coerceIn(0, editor.length())
        }
        val layout = editor.layout
        val localRect = if (layout != null && layout.lineCount > 0) {
            val visualLine = layout.getLineForOffset(offset)
            val x = layout.getPrimaryHorizontal(offset).toInt()
            Rect(
                x,
                layout.getLineTop(visualLine),
                x + 1,
                layout.getLineBottom(visualLine).coerceAtLeast(layout.getLineTop(visualLine) + 1)
            )
        } else {
            Rect(0, 0, editor.width.coerceAtLeast(1), editor.height.coerceAtLeast(1))
        }
        if (view === editor) return localRect
        val editorLocation = IntArray(2)
        val viewLocation = IntArray(2)
        editor.getLocationOnScreen(editorLocation)
        view.getLocationOnScreen(viewLocation)
        localRect.offset(
            editorLocation[0] - viewLocation[0],
            editorLocation[1] - viewLocation[1]
        )
        return localRect
    }

    private fun replaceLines(value: String) {
        val parsed = parsePhysicalLines(value)
        lines.clear()
        parsed.forEach { parsedLine ->
            lines += SourceLineBlock(nextLineId++, parsedLine.first, parsedLine.second)
        }
        preferredLineEnding = parsed.firstOrNull { it.second.isNotEmpty() }?.second ?: "\n"
        offsets.reset(lines)
        // Keep the gutter only as wide as the largest line number currently displayed.
        // A full data-set refresh below rebinds visible rows with the new width.
        lineAdapter.setLineCount(lines.size, notify = false)
    }

    private fun onLineTextChanged(
        position: Int,
        rawValue: String,
        cursor: Int,
        changeStart: Int,
        changeBefore: Int,
        changeCount: Int
    ) {
        val line = lines.getOrNull(position) ?: return
        val normalized = normalizeEditorText(rawValue)
        val selection = documentSelection
        if (selection != null && customHandleSide == null && selection.focusLine == position) {
            val safeStart = changeStart.coerceIn(0, normalized.length)
            val safeBefore = changeBefore.coerceIn(0, line.text.length - safeStart)
            val safeCount = changeCount.coerceIn(0, normalized.length - safeStart)
            val replacement = normalized.substring(safeStart, safeStart + safeCount)
            val expected = buildString(line.text.length - safeBefore + safeCount) {
                append(line.text, 0, safeStart)
                append(replacement)
                append(line.text, safeStart + safeBefore, line.text.length)
            }
            // TextWatcher can also observe a rebinding/recomposition. Only consume the event as
            // document-selection input when its local diff exactly matches the model line.
            if (expected == normalized && (safeBefore > 0 || safeCount > 0)) {
                val range = selectedDocumentRange()
                if (range != null && range.first < range.second) {
                    replaceDocumentRange(range, replacement)
                    return
                }
            }
        }
        if ('\n' !in normalized) {
            if (line.text == normalized) return
            val startOffset = offsets.start(position)
            val oldLength = line.text.length
            line.text = normalized
            offsets.update(position, normalized.length - oldLength)
            notifyDocumentChanged(
                DocumentChange(
                    startLine = position,
                    oldLineCount = 1,
                    newLineCount = 1,
                    startOffset = startOffset,
                    oldEndOffset = startOffset + oldLength,
                    newEndOffset = startOffset + normalized.length
                )
            )
            return
        }

        val startOffset = offsets.start(position)
        val oldTextLength = line.text.length
        val originalEnding = line.lineEnding
        val oldSerializedLength = line.serializedLength
        val parts = splitEditorLines(normalized)
        line.text = parts.first()
        line.lineEnding = preferredLineEnding
        val inserted = parts.drop(1).mapIndexed { index, text ->
            SourceLineBlock(
                stableId = nextLineId++,
                text = text,
                lineEnding = if (index == parts.lastIndex - 1) originalEnding else preferredLineEnding
            )
        }
        lines.addAll(position + 1, inserted)
        offsets.update(position, line.serializedLength - oldSerializedLength)
        offsets.insert(
            position + 1,
            inserted.map { it.serializedLength }
        )
        lineAdapter.notifyLineSplit(position, inserted.size)

        val normalizedCursorPrefix = normalizeEditorText(rawValue.take(cursor.coerceIn(0, rawValue.length)))
        val targetLineOffset = normalizedCursorPrefix.count { it == '\n' }
        val targetColumn = normalizedCursorPrefix.substringAfterLast('\n').length
        requestFocus(position + targetLineOffset, targetColumn)
        notifyDocumentChanged(
            DocumentChange(
                startLine = position,
                oldLineCount = 1,
                newLineCount = parts.size,
                startOffset = startOffset,
                oldEndOffset = startOffset + oldTextLength + originalEnding.length,
                newEndOffset = startOffset + normalized.length + originalEnding.length
            )
        )
    }

    /** Handle an Enter key even when the single-row EditText rejects a literal newline. */
    private fun splitLineAt(position: Int, column: Int): Boolean {
        val line = lines.getOrNull(position) ?: return false
        val safeColumn = column.coerceIn(0, line.text.length)
        val startOffset = offsets.start(position)
        val oldSerializedLength = line.serializedLength
        val originalEnding = line.lineEnding
        val before = line.text.substring(0, safeColumn)
        val after = line.text.substring(safeColumn)
        line.text = before
        line.lineEnding = preferredLineEnding
        val inserted = SourceLineBlock(nextLineId++, after, originalEnding)
        lines.add(position + 1, inserted)
        offsets.update(position, line.serializedLength - oldSerializedLength)
        offsets.insert(position + 1, listOf(inserted.serializedLength))
        lineAdapter.notifyLineSplit(position, 1)
        requestFocus(position + 1, 0)
        notifyDocumentChanged(
            DocumentChange(
                startLine = position,
                oldLineCount = 1,
                newLineCount = 2,
                startOffset = startOffset,
                oldEndOffset = startOffset + oldSerializedLength,
                newEndOffset = startOffset + line.serializedLength + inserted.serializedLength
            )
        )
        return true
    }

    private fun mergeWithPrevious(position: Int): Boolean {
        if (position <= 0 || position !in lines.indices) return false
        val previousPosition = position - 1
        val previous = lines[previousPosition]
        val current = lines[position]
        val startOffset = offsets.start(previousPosition)
        val oldEndOffset = offsets.start(position) + current.text.length
        val cursor = previous.text.length
        val oldPreviousLength = previous.serializedLength
        previous.text += current.text
        previous.lineEnding = current.lineEnding
        lines.removeAt(position)
        offsets.update(previousPosition, previous.serializedLength - oldPreviousLength)
        offsets.remove(position, 1)
        lineAdapter.notifyLinesMerged(previousPosition, position)
        requestFocus(previousPosition, cursor)
        notifyDocumentChanged(
            DocumentChange(
                startLine = previousPosition,
                oldLineCount = 2,
                newLineCount = 1,
                startOffset = startOffset,
                oldEndOffset = oldEndOffset,
                newEndOffset = startOffset + previous.serializedLength
            )
        )
        return true
    }

    private fun mergeWithNext(position: Int): Boolean {
        if (position !in 0 until lines.lastIndex) return false
        val current = lines[position]
        val next = lines[position + 1]
        val startOffset = offsets.start(position)
        val oldEndOffset = offsets.start(position + 1) + next.text.length
        val cursor = current.text.length
        val oldCurrentLength = current.serializedLength
        current.text += next.text
        current.lineEnding = next.lineEnding
        lines.removeAt(position + 1)
        offsets.update(position, current.serializedLength - oldCurrentLength)
        offsets.remove(position + 1, 1)
        lineAdapter.notifyLinesMerged(position, position + 1)
        requestFocus(position, cursor)
        notifyDocumentChanged(
            DocumentChange(
                startLine = position,
                oldLineCount = 2,
                newLineCount = 1,
                startOffset = startOffset,
                oldEndOffset = oldEndOffset,
                newEndOffset = startOffset + current.serializedLength
            )
        )
        return true
    }

    private fun moveCursorVertically(position: Int, direction: Int, column: Int): Boolean {
        val target = position + direction
        if (target !in lines.indices) return false
        requestFocus(target, column.coerceAtMost(lines[target].text.length))
        return true
    }

    private fun extendCursorVertically(
        position: Int,
        direction: Int,
        anchorColumn: Int,
        focusColumn: Int
    ): Boolean {
        val target = position + direction
        if (target !in lines.indices) return false
        val existing = documentSelection
        val anchorLine = existing?.anchorLine?.coerceIn(0, lines.lastIndex) ?: position
        val anchorOffset = existing?.anchorOffset?.coerceIn(0, lines[anchorLine].text.length)
            ?: anchorColumn.coerceIn(0, lines[position].text.length)
        val targetColumn = focusColumn.coerceIn(0, lines[target].text.length)
        if (anchorLine == target && anchorOffset == targetColumn) {
            resetHandleEndpointMapping()
            documentSelection = null
            selectionActionMode?.finish()
            requestFocus(target, targetColumn)
            lineAdapter.refreshHighlights()
            return true
        }
        resetHandleEndpointMapping()
        documentSelection = DocumentSelection(
            anchorLine = anchorLine,
            anchorOffset = anchorOffset,
            focusLine = target,
            focusOffset = targetColumn
        )
        requestFocus(target, targetColumn)
        lineAdapter.refreshHighlights()
        updateSelectionHandlePopups()
        ensureSelectionActionMode()
        selectionActionMode?.invalidateContentRect()
        updateSelectionHandlePopups()
        return true
    }

    private fun requestFocus(position: Int, column: Int) {
        scrollToPosition(position)
        post { lineAdapter.requestLineFocus(position, column) }
    }

    private fun ensureSelectionActionMode() {
        if (selectionActionMode != null) return
        selectionActionMode = startActionMode(
            SourceSelectionActionMode(),
            ActionMode.TYPE_FLOATING
        )
    }

    private fun highlightsForLine(position: Int): List<SourceLineHighlight> {
        val line = lines.getOrNull(position) ?: return emptyList()
        if (line.text.isEmpty()) return emptyList()
        val lineStart = offsets.start(position)
        val lineEnd = lineStart + line.text.length
        val result = mutableListOf<SourceLineHighlight>()
        if (highlights.isNotEmpty()) {
            var low = 0
            var high = highlights.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (highlights[middle].end <= lineStart) low = middle + 1 else high = middle
            }
            var index = low
            while (index < highlights.size) {
                val highlight = highlights[index]
                if (highlight.start >= lineEnd) break
                val start = (highlight.start - lineStart).coerceAtLeast(0)
                val end = (highlight.end - lineStart).coerceAtMost(line.text.length)
                if (start < end) {
                    result += SourceLineHighlight(
                        start,
                        end,
                        context.getColor(
                            if (highlight.current) R.color.secondary else R.color.inverse_primary
                        )
                    )
                }
                index++
            }
        }
        selectionHighlightForLine(position)?.let(result::add)
        return result
    }

    private inner class SourceSelectionActionMode(
        private val sourceLine: Int? = null,
        private val sourceEditor: SourceLineEditText? = null
    ) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            // Mark the mode as active before reading the child range. TextView may synchronously
            // emit a collapsed onSelectionChanged callback while the mode is being initialized;
            // that callback must not clear the range we are about to publish.
            selectionActionMode = mode
            if (sourceLine != null && sourceEditor != null && documentSelection == null) {
                suppressImeForSelection(sourceEditor)
                val start = sourceEditor.selectionStart.coerceIn(0, sourceEditor.length())
                val end = sourceEditor.selectionEnd.coerceIn(0, sourceEditor.length())
                if (start == end) {
                    selectionActionMode = null
                    return false
                }
                resetHandleEndpointMapping()
                documentSelection = DocumentSelection(
                    anchorLine = sourceLine,
                    anchorOffset = start,
                    focusLine = sourceLine,
                    focusOffset = end
                )
                // The long press has done its one allowed job: creating the initial single-row
                // range. End the child's native drag now; subsequent range changes must come from
                // one of the custom document handles after the pointer is released.
                blockNativeSelectionDrag(sourceEditor)
            }
            addSelectionMenu(menu)
            lineAdapter.refreshHighlights()
            postOnAnimation {
                if (selectionActionMode === mode) lineAdapter.refreshHighlights()
                if (selectionActionMode === mode) {
                    // Android creates its own handle popups while the ActionMode is starting.
                    // Re-show ours after that transaction so the source-view handles remain the
                    // topmost touch target.
                    customLeftHandle.dismissSafely()
                    customRightHandle.dismissSafely()
                    updateSelectionHandlePopups()
                }
            }
            return selectedDocumentRange()?.let { it.first < it.second } == true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            addSelectionMenu(menu)
            menu.findItem(MENU_PASTE)?.isEnabled = hasClipboardText()
            return true
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            selectionContentRect(view, sourceLine, sourceEditor)?.let(outRect::set)
                ?: outRect.set(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                MENU_SELECT_ALL -> {
                    selectAllDocument()
                    true
                }
                MENU_CUT -> cutDocumentSelection()
                MENU_COPY -> copyDocumentSelection().also { copied ->
                    if (copied) mode.finish()
                }
                MENU_PASTE -> pasteDocumentSelection()
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (selectionActionMode !== mode) return
            selectionActionMode = null
            // Keep the document range when Android tears down a child TextView's action mode
            // during RecyclerView recycling/scrolling. A later tap or document edit explicitly
            // clears it; this prevents select-all highlights from disappearing on scroll.
            // Keep the cross-row guard as well while the floating handles own that range; a
            // recycled child must not collapse it through a transient selection callback.
        }
    }

    private fun notifyDocumentChanged(change: DocumentChange) {
        documentChangedListeners.forEach { it.invoke() }
        documentChangeListeners.forEach { it.invoke(change) }
    }

    private fun parsePhysicalLines(value: String): List<Pair<String, String>> {
        if (value.isEmpty()) return listOf("" to "")
        val result = mutableListOf<Pair<String, String>>()
        var lineStart = 0
        var index = 0
        while (index < value.length) {
            val ending = when (value[index]) {
                '\r' -> if (index + 1 < value.length && value[index + 1] == '\n') "\r\n" else "\r"
                '\n' -> "\n"
                '\u2028', '\u2029', '\u0085' -> value[index].toString()
                else -> null
            }
            if (ending == null) {
                index++
                continue
            }
            result += value.substring(lineStart, index) to ending
            index += ending.length
            lineStart = index
        }
        result += value.substring(lineStart) to ""
        return result
    }

    private fun normalizeEditorText(value: String): String =
        value.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u2028', '\n')
            .replace('\u2029', '\n')
            .replace('\u0085', '\n')

    private fun splitEditorLines(value: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        value.forEachIndexed { index, character ->
            if (character == '\n') {
                result += value.substring(start, index)
                start = index + 1
            }
        }
        result += value.substring(start)
        return result
    }

    /** Implicit treap of serialized line lengths; structural edits are O(log n). */
    private class LineOffsets {
        private class Node(
            var value: Int,
            val priority: Int,
            var left: Node? = null,
            var right: Node? = null
        ) {
            var sum: Int = value
            var size: Int = 1

            fun update() {
                sum = (left?.sum ?: 0) + value + (right?.sum ?: 0)
                size = 1 + (left?.size ?: 0) + (right?.size ?: 0)
            }
        }

        private var root: Node? = null
        private var priorityState = 0x13579BDF

        val documentLength: Int
            get() = root?.sum ?: 0

        fun reset(lines: List<SourceLineBlock>) {
            root = null
            lines.forEach { line ->
                root = merge(root, Node(line.serializedLength, nextPriority()))
            }
        }

        fun update(position: Int, delta: Int) {
            if (delta == 0) return
            updateAt(root, position, delta)
        }

        fun insert(position: Int, values: List<Int>) {
            if (values.isEmpty()) return
            val (prefix, suffix) = split(root, position)
            var inserted: Node? = null
            values.forEach { inserted = merge(inserted, Node(it, nextPriority())) }
            root = merge(merge(prefix, inserted), suffix)
        }

        fun remove(position: Int, count: Int) {
            if (count <= 0) return
            val (prefix, tail) = split(root, position)
            val (_, suffix) = split(tail, count)
            root = merge(prefix, suffix)
        }

        fun start(position: Int): Int = prefix(root, position)

        fun findLine(offset: Int): Int {
            val safeOffset = offset.coerceAtLeast(0)
            var node = root
            var before = 0
            var line = 0
            while (node != null) {
                val leftSum = node.left?.sum ?: 0
                when {
                    safeOffset < before + leftSum -> node = node.left
                    safeOffset < before + leftSum + node.value -> {
                        return line + size(node.left)
                    }
                    else -> {
                        before += leftSum + node.value
                        line += size(node.left) + 1
                        node = node.right
                    }
                }
            }
            return (line - 1).coerceAtLeast(0)
        }

        private fun updateAt(node: Node?, position: Int, delta: Int) {
            node ?: return
            val leftSize = size(node.left)
            when {
                position < leftSize -> updateAt(node.left, position, delta)
                position > leftSize -> updateAt(node.right, position - leftSize - 1, delta)
                else -> node.value += delta
            }
            node.update()
        }

        private fun prefix(node: Node?, count: Int): Int {
            if (node == null || count <= 0) return 0
            val leftSize = size(node.left)
            return when {
                count <= leftSize -> prefix(node.left, count)
                count == leftSize + 1 -> (node.left?.sum ?: 0) + node.value
                else -> (node.left?.sum ?: 0) + node.value + prefix(node.right, count - leftSize - 1)
            }
        }

        private fun split(node: Node?, count: Int): Pair<Node?, Node?> {
            node ?: return null to null
            val leftSize = size(node.left)
            return when {
                count < leftSize -> {
                    val (prefix, remaining) = split(node.left, count)
                    node.left = remaining
                    node.update()
                    prefix to node
                }
                count > leftSize + 1 -> {
                    val (remaining, suffix) = split(node.right, count - leftSize - 1)
                    node.right = remaining
                    node.update()
                    node to suffix
                }
                count == leftSize -> {
                    val prefix = node.left
                    node.left = null
                    node.update()
                    prefix to node
                }
                else -> {
                    val suffix = node.right
                    node.right = null
                    node.update()
                    node to suffix
                }
            }
        }

        private fun merge(left: Node?, right: Node?): Node? {
            left ?: return right
            right ?: return left
            return if (left.priority <= right.priority) {
                left.right = merge(left.right, right)
                left.update()
                left
            } else {
                right.left = merge(left, right.left)
                right.update()
                right
            }
        }

        private fun size(node: Node?): Int = node?.size ?: 0

        private fun nextPriority(): Int {
            var value = priorityState
            value = value xor (value shl 13)
            value = value xor (value ushr 17)
            value = value xor (value shl 5)
            priorityState = value
            return value
        }
    }
}
