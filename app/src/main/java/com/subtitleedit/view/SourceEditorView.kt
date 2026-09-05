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
import android.icu.text.BreakIterator
import android.util.AttributeSet
import android.os.Build
import android.os.LocaleList
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
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextSelection
import android.widget.PopupWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.subtitleedit.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.Locale
import java.util.concurrent.Executors

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

    private val lineLayoutManager = LinearLayoutManager(context).apply {
        // Source rows may wrap to different visual heights. Smooth scrollbar metrics estimate
        // the document range from the currently visible rows, so that estimate can change while
        // dragging and make the thumb move backwards after a quick reverse drag. Item-based
        // metrics remain monotonic as rows are recycled.
        isSmoothScrollbarEnabled = false
    }
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
    private var imeShowRequested = false
    private var imeEnsureBlockedByUserScroll = false
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
        val downY: Float,
        val downRawX: Float,
        val downRawY: Float
    )

    private var documentSelection: DocumentSelection? = null
    private var selectionGesture: SelectionGesture? = null
    private var internalSelectionLongPressPosted = false
    private var internalSelectionDragActive = false
    private var internalSelectionInitialStart = 0
    private var internalSelectionInitialEnd = 0
    private var internalSelectionInitialTouch = 0
    private var internalSelectionLastRawX = 0f
    private var internalSelectionLastRawY = 0f
    private var selectionTouchMoved = false
    // Once the finger has entered a real drag, the classifier result belongs to the old
    // long-press seed and must never be allowed to overwrite the actively dragged range. This
    // flag is separate from selectionTouchMoved because that flag is reset on ACTION_UP.
    private var internalSelectionSmartSelectionCancelled = false
    private var internalSelectionInitializing = false
    private var internalSelectionGeneration = 0L
    private var viewportTouchMoved = false
    private var viewportTouchDownX = 0f
    private var viewportTouchDownY = 0f
    private var selectionActionMode: ActionMode? = null
    private var selectionActionModeSuppressedForScroll = false
    private var selectionActionModeSuppressedForHandleDrag = false
    // Material selection-handle assets are 44dp wide but only about 22dp of that bitmap is
    // opaque. The popup is cropped to that opaque portion so its touch area is the visible
    // marker, rather than the transparent padding used by TextView's larger native target.
    private val selectionHandleSizePx = (resources.displayMetrics.density * 22f).toInt()
    private val selectionHandleVisualSizePx = selectionHandleSizePx
    private val selectionHandleColor = context.getColor(R.color.secondary)
    private val selectionHandleAlpha = 0.72f

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
    private var imeSuppressedEditor: SourceLineEditText? = null
    private var customLeftHandle = SelectionHandlePopup(SelectionHandleSide.LEFT)
    private var customRightHandle = SelectionHandlePopup(SelectionHandleSide.RIGHT)

    // Android's smart-selection classifier is explicitly a worker-thread API. Keep it off the
    // UI thread just like TextView's SelectionActionModeHelper does, then apply a result only if
    // the long-press that requested it is still the current document selection.
    private val wordClassifierExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SourceEditorWordClassifier").apply { isDaemon = true }
    }

    private val internalSelectionLongPressRunnable = Runnable {
        internalSelectionLongPressPosted = false
        val gesture = selectionGesture ?: return@Runnable
        if (!selectionTouchActive || selectionTouchMoved || customHandleSide != null) return@Runnable
        beginInternalLongPressSelection(gesture)
    }

    private val selectionAutoScrollRunnable = Runnable {
        selectionAutoScrollPosted = false
        if (customHandleSide == null && !internalSelectionDragActive) return@Runnable
        val location = IntArray(2)
        getLocationOnScreen(location)
        val targetY = if (customHandleSide != null) {
            customHandleLastRawY - customHandleTouchOffsetY
        } else {
            internalSelectionLastRawY
        }
        val localY = targetY - location[1]
        val delta = selectionAutoScrollDelta(localY)
        if (delta == 0) return@Runnable
        scrollBy(0, delta)
        // Resolve the endpoint against the newly visible rows after each edge-scroll frame.
        if (customHandleSide != null) {
            updateCustomHandleDrag(
                customHandleLastRawX,
                customHandleLastRawY,
                scheduleAutoScroll = false
            )
        } else {
            updateInternalSelectionDrag(
                internalSelectionLastRawX,
                internalSelectionLastRawY,
                scheduleAutoScroll = false
            )
        }
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
        updateImeBottomPadding()
        // A manual viewport scroll must not be followed by a caret-visibility correction. The
        // bottom inset is still reconciled above so the custom scrollbar geometry stays current.
        if (documentSelection != null || imeEnsureBlockedByUserScroll) return@Runnable
        // Padding changes are applied during the next layout pass. Defer the visibility check
        // one frame so RecyclerView's new scroll range (including the keyboard tail spacer) is
        // available before attempting to scroll the focused row.
        postOnAnimation {
            if (!imeEnsureBlockedByUserScroll) ensureFocusedLineVisible()
        }
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
        onEditorSelectionChanged = ::onEditorSelectionChanged
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
                if (visible && !imeVisible) imeEnsureBlockedByUserScroll = false
                imeVisible = visible
                imeInsetBottomPx = bottom
                if (visible) imeShowRequested = false
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
        if (internalSelectionDragActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    // The long-press path receives the same tiny pointer jitter that Android
                    // reports while a finger is resting on the screen. Treating every MOVE as
                    // a drag races the smart-word result: the classifier may publish 原作 and
                    // the next jitter event immediately resolves the endpoint back to 原. Keep
                    // consuming those events, but do not change the document range until the
                    // pointer has actually crossed the touch slop from the original press.
                    val gesture = selectionGesture
                    val movedBeyondSlop = gesture != null &&
                        (abs(event.x - gesture.downX) > selectionTouchSlopPx ||
                            abs(event.y - gesture.downY) > selectionTouchSlopPx)
                    if (movedBeyondSlop) {
                        selectionTouchMoved = true
                        internalSelectionSmartSelectionCancelled = true
                        updateInternalSelectionDrag(event.rawX, event.rawY)
                    }
                    return true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    finishInternalSelectionDrag()
                    removeCallbacks(internalSelectionLongPressRunnable)
                    internalSelectionLongPressPosted = false
                    selectionGesture = null
                    selectionTouchMoved = false
                    selectionTouchActive = false
                    return true
                }
                MotionEvent.ACTION_DOWN -> finishInternalSelectionDrag()
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                imeShowRequested = false
                // Defer caret visibility correction until this gesture is known to be a tap.
                // Starting a scroll while the IME is visible must not be interrupted by the
                // pending one-frame correction posted when the keyboard appeared.
                imeEnsureBlockedByUserScroll = imeVisible
                if (imeEnsureBlockedByUserScroll) {
                    removeCallbacks(imeEnsureRunnable)
                    imeEnsurePosted = false
                }
                viewportTouchMoved = false
                viewportTouchDownX = event.x
                viewportTouchDownY = event.y
                removeCallbacks(internalSelectionLongPressRunnable)
                internalSelectionLongPressPosted = false
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
                        downY = event.y,
                        downRawX = event.rawX,
                        downRawY = event.rawY
                    )
                }
                if (selectionGesture != null) {
                    internalSelectionLongPressPosted = true
                    postDelayed(
                        internalSelectionLongPressRunnable,
                        ViewConfiguration.getLongPressTimeout().toLong()
                    )
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!viewportTouchMoved &&
                    (abs(event.x - viewportTouchDownX) > selectionTouchSlopPx ||
                        abs(event.y - viewportTouchDownY) > selectionTouchSlopPx)
                ) {
                    viewportTouchMoved = true
                    if (imeVisible) {
                        imeEnsureBlockedByUserScroll = true
                        removeCallbacks(imeEnsureRunnable)
                        imeEnsurePosted = false
                    }
                }
                val gesture = selectionGesture
                if (gesture != null &&
                    (abs(event.x - gesture.downX) > selectionTouchSlopPx ||
                        abs(event.y - gesture.downY) > selectionTouchSlopPx)
                ) {
                    selectionTouchMoved = true
                    // The long-press runnable will not run after this point. Keep the pending
                    // classifier request invalid even if its callback arrives after ACTION_UP.
                    internalSelectionSmartSelectionCancelled = true
                    if (internalSelectionLongPressPosted) {
                        removeCallbacks(internalSelectionLongPressRunnable)
                        internalSelectionLongPressPosted = false
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> Unit
        }
        val handled = super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                removeCallbacks(internalSelectionLongPressRunnable)
                internalSelectionLongPressPosted = false
                val gesture = selectionGesture
                val longPressed = gesture != null &&
                    event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()
                val moved = selectionTouchMoved || viewportTouchMoved
                // A moving gesture that has not entered the custom long-press path is a
                // RecyclerView scroll. A stationary tap explicitly clears an existing selection.
                if (documentSelection != null && !moved && !longPressed) {
                    clearDocumentSelection()
                }
                selectionGesture = null
                selectionTouchMoved = false
                selectionTouchActive = false
                if (documentSelection == null) {
                    restoreImeSuppression()
                    if (moved) {
                        // Keep the IME-sized tail in sync after a manual scroll, but do not run
                        // ensureFocusedLineVisible() and pull the list back to the caret.
                        updateImeBottomPadding()
                    } else {
                        imeEnsureBlockedByUserScroll = false
                        scheduleImeEnsure()
                        if (!longPressed && gesture?.editor?.hasFocus() == true) {
                            showImeForFocusedEditor()
                        }
                    }
                }
                viewportTouchMoved = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(internalSelectionLongPressRunnable)
                internalSelectionLongPressPosted = false
                selectionGesture = null
                selectionTouchMoved = false
                selectionTouchActive = false
                if (documentSelection == null) {
                    restoreImeSuppression()
                    if (viewportTouchMoved) {
                        updateImeBottomPadding()
                    } else {
                        imeEnsureBlockedByUserScroll = false
                        scheduleImeEnsure()
                    }
                }
                viewportTouchMoved = false
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
        // A normal viewport scroll dismisses the floating selection toolbar and leaves it
        // dismissed. Handle/selection drags own their toolbar state and restore it on release.
        if (documentSelection != null && dy != 0 && customHandleSide == null &&
            !internalSelectionDragActive && !selectionActionModeSuppressedForHandleDrag
        ) {
            selectionActionModeSuppressedForScroll = true
            selectionActionMode?.finish()
        }
        if (documentSelection != null && customHandleSide == null &&
            !internalSelectionDragActive && !selectionActionModeSuppressedForScroll &&
            !selectionActionModeSuppressedForHandleDrag && selectionActionMode == null
        ) {
            postOnAnimation { ensureSelectionActionMode() }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(internalSelectionLongPressRunnable)
        removeCallbacks(selectionAutoScrollRunnable)
        internalSelectionLongPressPosted = false
        internalSelectionDragActive = false
        customLeftHandle.dismissSafely()
        customRightHandle.dismissSafely()
        super.onDetachedFromWindow()
    }

    private fun onEditorFocusChanged() {
        if (selectionTouchActive || documentSelection != null) return
        scheduleImeEnsure()
        showImeForFocusedEditor()
    }

    /** Track a possible long press without suppressing IME for an ordinary tap. */
    private fun beginSelectionTouch(editor: SourceLineEditText) {
        if (imeSuppressedEditor !== editor) {
            imeSuppressedEditor?.showSoftInputOnFocus = true
            imeSuppressedEditor = editor
        }
        // A normal tap must be able to focus the row and open the keyboard. The custom long-press
        // runnable turns this off only after it has created the document selection.
        editor.showSoftInputOnFocus = true
    }

    /** Keep the long-press selection visual-only and close an IME that was already visible. */
    private fun suppressImeForSelection(editor: SourceLineEditText) {
        beginSelectionTouch(editor)
        editor.showSoftInputOnFocus = false
        imeShowRequested = false
        val inputMethod = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        inputMethod?.hideSoftInputFromWindow(editor.windowToken, 0)
    }

    private fun restoreImeSuppression() {
        imeSuppressedEditor?.showSoftInputOnFocus = true
        imeSuppressedEditor = null
    }

    private fun showImeForFocusedEditor() {
        if (imeVisible || imeShowRequested) return
        val editor = findFocus() as? SourceLineEditText ?: return
        if (!editor.showSoftInputOnFocus) return
        val inputMethod = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager ?: return
        imeShowRequested = true
        editor.post {
            if (!selectionTouchActive && documentSelection == null && editor.hasFocus()) {
                inputMethod.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            } else {
                imeShowRequested = false
            }
        }
    }

    private fun onEditorSelectionChanged(position: Int, editor: SourceLineEditText) {
        onEditorFocusChanged()
        if (internalSelectionInitializing) return
        // A handle drag owns the document endpoints for the duration of the gesture. Ignore the
        // child EditText's transient callbacks while that gesture is active; single-line and
        // multi-line selections otherwise follow the same document-level path.
        if (customHandleSide != null || internalSelectionDragActive || documentSelection != null) return
        val start = editor.selectionStart.coerceIn(0, editor.length())
        val end = editor.selectionEnd.coerceIn(0, editor.length())
        val selection = documentSelection
        if (selection == null) {
            // The child callback also fires for keyboard/programmatic selection changes. Do not
            // turn those into a document selection. A new document
            // range is published only by the parent-owned long-press/handle paths or by the
            // vertical-extension keyboard path.
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
        if (!imeVisible || imeEnsureBlockedByUserScroll || selectionTouchActive ||
            documentSelection != null || height <= 0
        ) return
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

    private fun beginInternalLongPressSelection(gesture: SelectionGesture) {
        val editor = gesture.editor
        val position = gesture.line.coerceIn(0, lines.lastIndex)
        if (editor.length() == 0) return
        val touchOffset = offsetForRawPosition(editor, gesture.downRawX, gesture.downRawY)
        // Word classification is intentionally performed on the physical row only. Newline
        // separators are document metadata here, not part of the text under the finger; passing
        // the entire document to a classifier can return a boundary in an adjacent row and make
        // the initial long-press range collapse.
        val localText = editor.text ?: ""
        val word = runCatching { wordSelectionRange(localText, touchOffset) }
            .getOrElse { characterClusterRange(localText, touchOffset) }
            .let { range ->
                val safeStart = range.first.coerceIn(0, localText.length)
                val safeEnd = range.second.coerceIn(safeStart, localText.length)
                if (safeStart < safeEnd) {
                    safeStart to safeEnd
                } else {
                    val fallback = touchOffset.coerceIn(0, (localText.length - 1).coerceAtLeast(0))
                    fallback to (fallback + 1).coerceAtMost(localText.length)
                }
            }
        val initialStart = absoluteOffset(position, word.first)
        val initialEnd = absoluteOffset(position, word.second)
        if (initialStart >= initialEnd) return
        val startPoint = position to word.first
        val endPoint = position to word.second

        // A new long press starts a new document range. It is deliberately independent of
        // TextView's selection state, so a recycled child cannot leave a second native range
        // active underneath this one.
        if (documentSelection != null) clearDocumentSelection()
        val selectionGeneration = ++internalSelectionGeneration
        selectionActionModeSuppressedForScroll = false
        selectionActionModeSuppressedForHandleDrag = false
        suppressImeForSelection(editor)
        resetHandleEndpointMapping()
        documentSelection = DocumentSelection(
            anchorLine = startPoint.first,
            anchorOffset = startPoint.second,
            focusLine = endPoint.first,
            focusOffset = endPoint.second
        )
        internalSelectionInitialStart = initialStart
        internalSelectionInitialEnd = initialEnd
        internalSelectionInitialTouch = absoluteOffset(position, touchOffset)
        internalSelectionLastRawX = gesture.downRawX
        internalSelectionLastRawY = gesture.downRawY
        internalSelectionDragActive = true
        internalSelectionSmartSelectionCancelled = false
        internalSelectionInitializing = true
        try {
            parent?.requestDisallowInterceptTouchEvent(true)
            refreshVisibleSelectionHighlights()
            // Cancel TextView's already dispatched DOWN stream before it can turn a later MOVE
            // into a native selection drag. All subsequent movement is consumed by this view.
            editor.cancelLongPress()
            cancelNativeSelectionGesture(editor)
        } finally {
            internalSelectionInitializing = false
        }
        // Keep the focused child range aligned for keyboard replacement/deletion. It is only a
        // caret for cross-row ranges; the document highlight and custom handles remain the source
        // of truth for the complete selection.
        syncNativeSelection(documentSelection!!)
        ensureSelectionActionMode()
        updateSelectionHandlePopups()

        // Native TextView performs smart selection after its initial WordIterator range has been
        // published. Do the same here. In particular, a CJK touch must not fall back to selecting
        // every adjacent ideograph when the classifier is unavailable or still starting up.
        requestSmartWordSelection(
            text = localText.toString(),
            initialStart = word.first,
            initialEnd = word.second,
            position = position,
            generation = selectionGeneration
        )
    }

    private fun updateInternalSelectionDrag(
        rawX: Float,
        rawY: Float,
        scheduleAutoScroll: Boolean = true
    ) {
        if (!internalSelectionDragActive || documentSelection == null) return
        internalSelectionLastRawX = rawX
        internalSelectionLastRawY = rawY
        val sourceLocation = IntArray(2)
        getLocationOnScreen(sourceLocation)
        val localY = rawY - sourceLocation[1]
        if (scheduleAutoScroll) maybeAutoScrollForSelection(localY)
        val target = editorAtRaw(rawY) ?: return
        val targetOffset = offsetForRawPosition(target.second, rawX, rawY)
        val movingOffset = absoluteOffset(target.first, targetOffset)

        // The long-press selection remains word-based while the finger is held. A MOVE event
        // can arrive after smart selection has expanded the initial character (and even with a
        // few pixels of jitter while the finger is stationary). Resolving the raw insertion point
        // directly in that case would shrink 原作 back to 原, which looks like a second selector
        // fighting the classifier. Keep the current word intact until the finger leaves it, and
        // snap subsequent positions to the corresponding word boundary.
        val movingRight = movingOffset >= internalSelectionInitialTouch
        val resolvedMovingOffset = if (movingRight) {
            if (movingOffset <= internalSelectionInitialEnd) {
                internalSelectionInitialEnd
            } else {
                absoluteOffset(
                    target.first,
                    wordSelectionRange(target.second.text ?: "", targetOffset).second
                )
            }
        } else {
            if (movingOffset >= internalSelectionInitialStart) {
                internalSelectionInitialStart
            } else {
                absoluteOffset(
                    target.first,
                    wordSelectionRange(target.second.text ?: "", targetOffset).first
                )
            }
        }

        // Preserve the word selected by the long press until the finger chooses a direction;
        // moving right keeps the word start fixed, moving left keeps the word end fixed. This
        // mirrors the native gesture without allowing TextView to own the pointer stream.
        val anchorOffset = if (movingRight) {
            internalSelectionInitialStart
        } else {
            internalSelectionInitialEnd
        }
        val anchorLineOffset = lineAndColumnForAbsoluteOffset(anchorOffset)
        val focusLineOffset = lineAndColumnForAbsoluteOffset(resolvedMovingOffset)
        documentSelection = DocumentSelection(
            anchorLine = anchorLineOffset.first,
            anchorOffset = anchorLineOffset.second,
            focusLine = focusLineOffset.first,
            focusOffset = focusLineOffset.second
        )
        hideSelectionActionModeForHandleDrag()
        resetHandleEndpointMapping()
        refreshVisibleSelectionHighlights()
        updateSelectionHandlePopups()
    }

    private fun finishInternalSelectionDrag() {
        if (!internalSelectionDragActive) return
        internalSelectionDragActive = false
        // A classifier result is only valid while the long-press gesture is still active. Once
        // the pointer is released (or a fresh DOWN supersedes this gesture), keep the published
        // range stable instead of applying a late asynchronous suggestion after the user has
        // already seen/dragged the handles.
        internalSelectionSmartSelectionCancelled = true
        removeCallbacks(selectionAutoScrollRunnable)
        selectionAutoScrollPosted = false
        parent?.requestDisallowInterceptTouchEvent(false)
        documentSelection?.let(::syncNativeSelection)
        selectionActionMode?.invalidateContentRect()
        updateSelectionHandlePopups()
        restoreSelectionActionModeAfterDrag()
    }

    /**
     * Return the same initial word range that TextView obtains from its WordIterator. Smart
     * dictionary expansion is deliberately separate and asynchronous (see below).
     */
    private fun wordSelectionRange(text: CharSequence, offset: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        var pivot = offset.coerceIn(0, text.length - 1)
        // Layout hit testing is right-biased at a glyph boundary. Treat punctuation directly
        // after a word as the word character immediately before it, matching TextView's touch
        // behavior instead of producing a punctuation-only range.
        if (pivot > 0 && isPunctuation(text[pivot]) &&
            isLetterOrDigitAt(text, pivot - 1)
        ) {
            pivot--
        }

        // ICU's word iterator has an intentional boundary bias for CJK text: the first
        // character of a run and the second character can produce different initial ranges. That
        // is useful for native cursor movement but is wrong for a touch selection seed. Start
        // from the actual character under the finger and let TextClassifier expand it only when
        // the platform dictionary recognizes a real word.
        if (isCjkCharacter(text[pivot])) return characterClusterRange(text, pivot)

        val locales = resources.configuration.locales
        val iterator = BreakIterator.getWordInstance(
            if (locales.isEmpty) Locale.getDefault() else locales[0]
        )
        iterator.setText(text.toString())
        val start = wordBeginning(iterator, text, pivot)
        val end = wordEnd(iterator, text, pivot)
        if (start >= 0 && end > start) return start to end
        return fallbackWordRange(text, pivot)
    }

    /** Equivalent to android.text.method.WordIterator#getBeginning(). */
    private fun wordBeginning(
        iterator: BreakIterator,
        text: CharSequence,
        offset: Int
    ): Int {
        if (isLetterOrDigitAt(text, offset)) {
            if (iterator.isBoundary(offset) && !isLetterOrDigitBefore(text, offset)) {
                return offset
            }
            return iterator.preceding(offset)
        }
        if (isLetterOrDigitBefore(text, offset)) return iterator.preceding(offset)
        return BreakIterator.DONE
    }

    /** Equivalent to android.text.method.WordIterator#getEnd(). */
    private fun wordEnd(
        iterator: BreakIterator,
        text: CharSequence,
        offset: Int
    ): Int {
        if (isLetterOrDigitBefore(text, offset)) {
            if (iterator.isBoundary(offset) && !isLetterOrDigitAt(text, offset)) {
                return offset
            }
            return iterator.following(offset)
        }
        if (isLetterOrDigitAt(text, offset)) return iterator.following(offset)
        return BreakIterator.DONE
    }

    private fun fallbackWordRange(text: CharSequence, pivot: Int): Pair<Int, Int> {
        // Do not group adjacent CJK ideographs: without a dictionary Android's own WordIterator
        // selects one character, and grouping the whole run is the source of the long-press
        // mismatch reported for phrases containing several Chinese words.
        if (isCjkCharacter(text[pivot])) return characterClusterRange(text, pivot)
        if (isLetterOrDigitAt(text, pivot)) {
            var start = pivot
            var end = pivot + 1
            while (start > 0 && isLetterOrDigitAt(text, start - 1)) start--
            while (end < text.length && isLetterOrDigitAt(text, end)) end++
            return start to end
        }
        return characterClusterRange(text, pivot)
    }

    private fun characterClusterRange(text: CharSequence, initialPivot: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val pivot = initialPivot.coerceIn(0, text.length - 1)
        val locales = resources.configuration.locales
        val iterator = BreakIterator.getCharacterInstance(
            if (locales.isEmpty) Locale.getDefault() else locales[0]
        )
        iterator.setText(text.toString())
        val start = iterator.preceding((pivot + 1).coerceAtMost(text.length))
        val end = iterator.following(pivot)
        val safeStart = if (start == BreakIterator.DONE) 0 else start
        val safeEnd = if (end == BreakIterator.DONE) text.length else end
        return if (safeStart < safeEnd) safeStart to safeEnd else {
            pivot to (pivot + 1).coerceAtMost(text.length)
        }
    }

    private fun isLetterOrDigitAt(text: CharSequence, offset: Int): Boolean {
        if (offset < 0 || offset >= text.length) return false
        return Character.isLetterOrDigit(Character.codePointAt(text, offset))
    }

    private fun isLetterOrDigitBefore(text: CharSequence, offset: Int): Boolean {
        if (offset <= 0 || offset > text.length) return false
        return Character.isLetterOrDigit(Character.codePointBefore(text, offset))
    }

    private fun isPunctuation(character: Char): Boolean {
        return when (Character.getType(character)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isCjkCharacter(character: Char): Boolean {
        return when (Character.UnicodeScript.of(character.code)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL -> true
            else -> false
        }
    }

    private fun requestSmartWordSelection(
        text: String,
        initialStart: Int,
        initialEnd: Int,
        position: Int,
        generation: Long
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || initialStart >= initialEnd) return
        wordClassifierExecutor.execute {
            val suggested = runCatching {
                suggestTextClassifierSelection(text, initialStart, initialEnd)
            }.getOrNull()
                ?: return@execute
            if (initialEnd - initialStart == 1 &&
                isCjkCharacter(text[initialStart]) &&
                suggested.second - suggested.first > 1 &&
                !isStableCjkSuggestion(text, suggested)
            ) return@execute
            post {
                if (generation != internalSelectionGeneration ||
                    customHandleSide != null ||
                    documentSelection == null ||
                    internalSelectionSmartSelectionCancelled
                ) return@post
                val current = documentSelection ?: return@post
                val expectedStart = absoluteOffset(position, initialStart)
                val expectedEnd = absoluteOffset(position, initialEnd)
                val currentStart = min(
                    absoluteOffset(current.anchorLine, current.anchorOffset),
                    absoluteOffset(current.focusLine, current.focusOffset)
                )
                val currentEnd = max(
                    absoluteOffset(current.anchorLine, current.anchorOffset),
                    absoluteOffset(current.focusLine, current.focusOffset)
                )
                if (currentStart != expectedStart || currentEnd != expectedEnd) return@post
                val suggestedStart = suggested.first.coerceIn(0, text.length)
                val suggestedEnd = suggested.second.coerceIn(suggestedStart, text.length)
                if (suggestedStart >= suggestedEnd ||
                    suggestedStart > initialStart || suggestedEnd < initialEnd ||
                    lines.getOrNull(position)?.text != text
                ) return@post
                if (suggestedStart == initialStart && suggestedEnd == initialEnd) return@post
                documentSelection = DocumentSelection(
                    anchorLine = position,
                    anchorOffset = suggestedStart,
                    focusLine = position,
                    focusOffset = suggestedEnd
                )
                internalSelectionInitialStart = absoluteOffset(position, suggestedStart)
                internalSelectionInitialEnd = absoluteOffset(position, suggestedEnd)
                resetHandleEndpointMapping()
                syncNativeSelection(documentSelection!!)
                refreshVisibleSelectionHighlights()
                selectionActionMode?.invalidateContentRect()
                updateSelectionHandlePopups()
            }
        }
    }

    /**
     * A single CJK seed can occasionally be expanded by a classifier only because it is on one
     * side of an ICU boundary (for example, the second character of an unrelated pair). Accept a
     * smart range only when every CJK character inside that range independently maps to the same
     * range. This removes the touch-position-dependent "粘连" selection while retaining genuine
     * dictionary words such as 原作.
     */
    private fun isStableCjkSuggestion(text: String, candidate: Pair<Int, Int>): Boolean {
        var index = candidate.first
        var foundCjk = false
        while (index < candidate.second) {
            val codePoint = Character.codePointAt(text, index)
            val next = (index + Character.charCount(codePoint)).coerceAtMost(candidate.second)
            if (isCjkCharacter(text[index])) {
                foundCjk = true
                val suggestion = runCatching {
                    suggestTextClassifierSelection(text, index, next)
                }.getOrNull() ?: return false
                if (suggestion.first != candidate.first || suggestion.second != candidate.second) {
                    return false
                }
            }
            index = next
        }
        return foundCjk
    }

    /** Ask the platform classifier with the same request shape used by native TextView. */
    private fun suggestTextClassifierSelection(
        text: CharSequence,
        selectionStart: Int,
        selectionEnd: Int
    ): Pair<Int, Int>? {
        val manager = context.getSystemService(TextClassificationManager::class.java)
            ?: return null
        val classifier = manager.textClassifier
        val locales = resources.configuration.locales
        val defaultLocales = if (locales.isEmpty) LocaleList(Locale.getDefault()) else locales
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val requestBuilder = TextSelection.Request.Builder(text, selectionStart, selectionEnd)
                .setDefaultLocales(defaultLocales)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestBuilder.setIncludeTextClassification(true)
            }
            classifier.suggestSelection(requestBuilder.build())
        } else {
            @Suppress("DEPRECATION")
            classifier.suggestSelection(text, selectionStart, selectionEnd, defaultLocales)
        }
        val start = selection.selectionStartIndex.coerceIn(0, text.length)
        val end = selection.selectionEndIndex.coerceIn(start, text.length)
        return if (start < end) start to end else null
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
        // A handle drag takes over from a toolbar that may have been dismissed by a prior
        // viewport scroll. Its release path is responsible for showing the toolbar again.
        selectionActionModeSuppressedForScroll = false
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
        hideSelectionActionModeForHandleDrag()
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
        hideSelectionActionModeForHandleDrag()
        customHandleLastRawX = rawX
        customHandleLastRawY = rawY
        val targetRawX = rawX - customHandleTouchOffsetX
        val targetRawY = rawY - customHandleTouchOffsetY
        val sourceLocation = IntArray(2)
        getLocationOnScreen(sourceLocation)
        if (scheduleAutoScroll) maybeAutoScrollForSelection(targetRawY - sourceLocation[1])
        val rawDragPoint = rawPointToWindow(targetRawX, targetRawY)
        val target = editorAtRaw(targetRawY)
        if (target == null) {
            customHandleDragPoint = rawDragPoint
            return
        }
        val movingOffset = absoluteOffset(
            target.first,
            offsetForRawPosition(target.second, targetRawX, targetRawY)
        )
        val crossesOtherHandle = when (side) {
            SelectionHandleSide.LEFT -> movingOffset > fixedOffset
            SelectionHandleSide.RIGHT -> movingOffset < fixedOffset
        }
        if (crossesOtherHandle) {
            // Exchange the logical direction and endpoint role once the snapped endpoint passes
            // the stationary handle. This matches native selection behavior and avoids a
            // disappearing/teleporting marker when the two endpoints cross.
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
        // The finger supplies a candidate coordinate, but the visible handle must stay attached
        // to the resolved text insertion point. Keep the raw point only while RecyclerView has no
        // layout for the target row yet (for example during an edge-scroll frame).
        customHandleDragPoint = selectionEndpointPoint(movingOffset)
            ?: rawDragPoint
        refreshVisibleSelectionHighlights()
        // Do not ask the floating ActionMode to relayout on every MOVE. The custom popup follows
        // the snapped text endpoint; refresh the toolbar once the drag ends.
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
        restoreSelectionActionModeAfterDrag()
    }

    private fun hideSelectionActionModeForHandleDrag() {
        selectionActionModeSuppressedForHandleDrag = true
        selectionActionMode?.finish()
    }

    private fun restoreSelectionActionModeAfterDrag() {
        if (documentSelection == null) return
        selectionActionModeSuppressedForScroll = false
        selectionActionModeSuppressedForHandleDrag = false
        ensureSelectionActionMode()
        selectionActionMode?.invalidateContentRect()
    }

    /** Keep the focused EditText's native caret/range aligned with the document endpoint. */
    private fun syncNativeSelection(selection: DocumentSelection) {
        val focusLine = selection.focusLine.coerceIn(0, lines.lastIndex)
        val focusColumn = selection.focusOffset.coerceIn(0, lines[focusLine].text.length)
        scrollToPosition(focusLine)
        // The document selection is painted by SourceEditorView for both single- and
        // cross-row ranges. Keeping a native range in the child as well creates a second,
        // independently-updated selection state; TextView can redraw that state after the smart
        // classifier finishes and make a correctly matched word appear to revert to one
        // character. Keep only a collapsed native caret at the document focus endpoint.
        lineAdapter.requestLineFocus(focusLine, focusColumn)
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
        val leftContains = customLeftHandle.containsRaw(rawX, rawY)
        val rightContains = customRightHandle.containsRaw(rawX, rawY)
        return when {
            leftContains && !rightContains -> SelectionHandleSide.LEFT
            rightContains && !leftContains -> SelectionHandleSide.RIGHT
            leftContains && rightContains -> {
                if (customLeftHandle.distanceToCenterRaw(rawX, rawY) <=
                    customRightHandle.distanceToCenterRaw(rawX, rawY)
                ) {
                    SelectionHandleSide.LEFT
                } else {
                    SelectionHandleSide.RIGHT
                }
            }
            else -> null
        }
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
        // While a handle is being dragged, the active popup follows the resolved text endpoint.
        // The touch-to-endpoint offset is retained separately, so the finger can remain over the
        // marker while the endpoint snaps between characters and rows.
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
                // Refresh the inactive endpoint as rows move underneath the active drag.
                customLeftHandle.positionAt(dragPoint ?: selectionEndpointPoint(leftOffset))
                customRightHandle.positionAt(visibleSelectionEndpointPoint(rightOffset))
            }
            SelectionHandleSide.RIGHT -> {
                customRightHandle.positionAt(dragPoint ?: selectionEndpointPoint(rightOffset))
                customLeftHandle.positionAt(visibleSelectionEndpointPoint(leftOffset))
            }
            null -> {
                customLeftHandle.positionAt(visibleSelectionEndpointPoint(leftOffset))
                customRightHandle.positionAt(visibleSelectionEndpointPoint(rightOffset))
            }
        }
    }

    private fun visibleSelectionEndpointPoint(absoluteOffset: Int): Point? {
        val point = selectionEndpointPoint(absoluteOffset) ?: return null
        val screenPoint = windowPointToScreen(point)
        val location = IntArray(2)
        getLocationOnScreen(location)
        return point.takeIf {
            screenPoint.x >= location[0] && screenPoint.x <= location[0] + width &&
                screenPoint.y >= location[1] && screenPoint.y <= location[1] + height
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
        if (holder == null) return null
        val editor = holder.itemView.findViewById<SourceLineEditText>(R.id.etSourceLine)
            ?: return null
        val layout = editor.layout ?: return null
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
        internalSelectionGeneration++
        documentSelection = null
        selectionActionModeSuppressedForScroll = false
        selectionActionModeSuppressedForHandleDrag = false
        removeCallbacks(internalSelectionLongPressRunnable)
        internalSelectionLongPressPosted = false
        internalSelectionDragActive = false
        selectionGesture = null
        customHandleSide = null
        customHandleAnchor = null
        customHandleDragPoint = null
        customHandleTouchOffsetX = 0f
        customHandleTouchOffsetY = 0f
        customHandleTouchFromSource = false
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

    /** Stop any TextView gesture that was dispatched before the custom long press fired. */
    private fun cancelNativeSelectionGesture(editor: SourceLineEditText) {
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
        selectionActionModeSuppressedForScroll = false
        selectionActionModeSuppressedForHandleDrag = false
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
            handleView.alpha = selectionHandleAlpha
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
            if (point == null) {
                // An endpoint can be outside the attached RecyclerView while the document range
                // remains valid. Hide only this visual handle; never clear documentSelection.
                dismissSafely()
                return
            }
            if (!this@SourceEditorView.isAttachedToWindow) {
                return
            }
            try {
                // showAtLocation() consumes WindowManager coordinates relative to the attached
                // application window. selectionEndpointPoint() already returns that same coordinate
                // space via getLocationInWindow(); subtracting the RecyclerView's location here would
                // move the popup a second time (often completely off the text).
                // MT places each visible handle directly below its text endpoint. The left popup
                // ends at that endpoint and the right popup starts there, so the visible marker
                // stays on the selection edge while its touch area remains limited to the marker.
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
            return rawX >= topLeft.x && rawX <= topLeft.x + width &&
                rawY >= topLeft.y && rawY <= topLeft.y + height
        }

        fun distanceToCenterRaw(rawX: Float, rawY: Float): Float {
            if (!isShowing || lastWindowX == Int.MIN_VALUE || lastWindowY == Int.MIN_VALUE) {
                return Float.MAX_VALUE
            }
            val topLeft = windowPointToScreen(Point(lastWindowX, lastWindowY))
            val centerX = topLeft.x + width * 0.5f
            val centerY = topLeft.y + height * 0.5f
            return (rawX - centerX) * (rawX - centerX) +
                (rawY - centerY) * (rawY - centerY)
        }
    }

    private fun hotspotX(side: SelectionHandleSide): Int = when (side) {
        // The cropped left popup ends at the text endpoint; the cropped right popup starts at it.
        SelectionHandleSide.LEFT -> selectionHandleSizePx
        SelectionHandleSide.RIGHT -> 0
    }

    // selectionEndpointPoint() already returns the line bottom, which is the native handle's
    // popup top. Do not shift it upward: that would detach the stem from the selected text.
    private fun hotspotY(): Int = 0

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
            systemHandleDrawable?.let { drawable ->
                val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: width
                val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: height
                // Keep the drawable's native aspect ratio. The Android Material assets have
                // transparent horizontal margins; placing their 3/4 or 1/4 hotspot at the
                // popup edge crops those margins and leaves only the visible marker touchable.
                val scale = visualSize.toFloat() / intrinsicHeight.coerceAtLeast(1)
                val drawWidth = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
                val drawHeight = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
                val drawableHotspot = if (side == SelectionHandleSide.LEFT) {
                    drawWidth * 0.75f
                } else {
                    drawWidth * 0.25f
                }
                val left = if (side == SelectionHandleSide.LEFT) {
                    width - drawableHotspot
                } else {
                    -drawableHotspot
                }.toInt()
                val top = 0
                drawable.setBounds(left, top, left + drawWidth, top + drawHeight)
                drawable.draw(canvas)
                return
            }
            val visualTop = 0f
            val centerX = if (side == SelectionHandleSide.LEFT) {
                width * 0.75f
            } else {
                width * 0.25f
            }
            val stemWidth = visualSize * 0.09f
            val stemTop = visualTop + visualSize * 0.10f
            val stemBottom = visualTop + visualSize * 0.58f
            canvas.drawRoundRect(
                centerX - stemWidth,
                stemTop,
                centerX + stemWidth,
                stemBottom,
                stemWidth,
                stemWidth,
                paint
            )
            val circleY = visualTop + visualSize * 0.68f
            canvas.drawCircle(width * 0.5f, circleY, visualSize * 0.22f, paint)
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
        view: View
    ): Rect? {
        val selection = documentSelection ?: return null
        val targetLine = selection.focusLine.coerceIn(0, lines.lastIndex)
        val editor = findViewHolderForAdapterPosition(targetLine)?.itemView
            ?.findViewById<SourceLineEditText>(R.id.etSourceLine)
            ?: findFocus() as? SourceLineEditText
            ?: return null
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
        lines.clear()
        if (value.isEmpty()) {
            lines += SourceLineBlock(nextLineId++, "", "")
            preferredLineEnding = "\n"
        } else {
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
                lines += SourceLineBlock(
                    nextLineId++,
                    value.substring(lineStart, index),
                    ending
                )
                index += ending.length
                lineStart = index
            }
            lines += SourceLineBlock(nextLineId++, value.substring(lineStart), "")
            preferredLineEnding = lines.firstOrNull { it.lineEnding.isNotEmpty() }?.lineEnding ?: "\n"
        }
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
            selectionActionModeSuppressedForScroll = false
            selectionActionModeSuppressedForHandleDrag = false
            selectionActionMode?.finish()
            requestFocus(target, targetColumn)
            lineAdapter.refreshHighlights()
            return true
        }
        selectionActionModeSuppressedForScroll = false
        selectionActionModeSuppressedForHandleDrag = false
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
        if (documentSelection == null || selectedDocumentRange()?.let { it.first < it.second } != true) return
        if (selectionActionModeSuppressedForScroll || selectionActionModeSuppressedForHandleDrag) return
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

    private inner class SourceSelectionActionMode : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            if (selectedDocumentRange()?.let { it.first < it.second } != true) return false
            selectionActionMode = mode
            addSelectionMenu(menu)
            lineAdapter.refreshHighlights()
            postOnAnimation {
                if (selectionActionMode === mode) lineAdapter.refreshHighlights()
                if (selectionActionMode === mode) {
                    // Reposition the document handles after the floating toolbar has completed
                    // its first layout pass. The child TextView has no native selection mode.
                    updateSelectionHandlePopups()
                }
            }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            addSelectionMenu(menu)
            menu.findItem(MENU_PASTE)?.isEnabled = hasClipboardText()
            return true
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            selectionContentRect(view)?.let(outRect::set)
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
            // Keep the document range when Android tears down the floating toolbar during
            // RecyclerView recycling/scrolling. A later tap or document edit explicitly clears
            // it; this prevents selection highlights from disappearing on scroll.
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
