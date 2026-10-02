package com.subtitleedit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.LocaleList
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextSelection
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val SOURCE_SELECTION_MENU_SELECT_ALL = 0x5301
private const val SOURCE_SELECTION_MENU_CUT = 0x5302
private const val SOURCE_SELECTION_MENU_COPY = 0x5303
private const val SOURCE_SELECTION_MENU_PASTE = 0x5304

/** The source editor owns its document-level ActionMode; BasicTextField must not add another. */
private object SourceEditorTextToolbar : TextToolbar {
    override fun showMenu(
        rect: androidx.compose.ui.geometry.Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) = Unit

    override fun hide() = Unit

    override val status: TextToolbarStatus
        get() = TextToolbarStatus.Hidden
}

// While a document-level range is active, the custom range/handles below own selection visuals;
// collapsing each BasicTextField to a caret avoids a second platform range.
private val SourceEditorTextSelectionColors = TextSelectionColors(
    handleColor = Color.Transparent,
    backgroundColor = Color.Transparent
)

/** One physical source line. The line ending is kept separately so source text is lossless. */
internal data class EditorSourceLine(
    val stableId: Long,
    val text: String,
    val lineEnding: String
) {
    val serializedLength: Int
        get() = text.length + lineEnding.length
}

internal data class EditorSourceHighlight(
    val start: Int,
    val end: Int,
    val current: Boolean = false
)

internal data class EditorSourceDocumentChange(
    val startLine: Int,
    val oldLineCount: Int,
    val newLineCount: Int,
    val startOffset: Int,
    val oldEndOffset: Int,
    val newEndOffset: Int
)

internal data class EditorSourceSelection(
    val anchorLine: Int,
    val anchorOffset: Int,
    val focusLine: Int,
    val focusOffset: Int
)

internal data class FocusRequest(
    val sequence: Long,
    val line: Int,
    val column: Int,
    val selectionStart: Int? = null,
    val selectionEnd: Int? = null
)

internal data class ScrollRequest(
    val sequence: Long,
    val line: Int,
    val offset: Int,
    val center: Boolean
)

internal data class EditorSourceHandleDrag(
    val sourcePosition: Int,
    val anchor: Boolean,
    val contentLeftPx: Float,
    val textOriginPx: Float,
    val charWidthPx: Float
)

/**
 * A long-press selection belongs to the editor viewport rather than to the row where it began.
 * Keeping the seed here lets the root pointer observer continue the gesture after LazyColumn
 * recycles that row during edge scrolling.
 */
internal data class EditorSourceSelectionDrag(
    val initialSelection: EditorSourceSelection,
    val initialTouchLine: Int,
    val initialTouchOffset: Int
)

/** Actual text layout used to resolve editor-level pointer coordinates. */
private data class EditorSourceLayoutMetrics(
    val stableId: Long,
    val rootCoordinates: LayoutCoordinates,
    val textCoordinates: LayoutCoordinates,
    val layout: TextLayoutResult
)

/**
 * State for the source editor. It deliberately has no Activity, legacy widget, or XML dependency,
 * so controllers can use the same document API from a Compose screen and tests.
 */
internal class EditorSourceEditorState(initialText: String = "") {
    private var nextLineId = 1L
    private var preferredLineEnding = "\n"
    private var focusSequence = 0L
    private var scrollSequence = 0L
    private var smartSelectionGeneration = 0L
    private var smartSelectionJob: Job? = null
    private val smartSelectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var lines by mutableStateOf(parsePhysicalLines(initialText))
        private set

    var enabled by mutableStateOf(true)
        private set

    private var searchHighlightsState by mutableStateOf(emptyList<EditorSourceHighlight>())
    val searchHighlights: List<EditorSourceHighlight>
        get() = searchHighlightsState

    var documentSelection by mutableStateOf<EditorSourceSelection?>(null)
        private set

    var focusedLine by mutableIntStateOf(-1)
        private set

    /** Last caret column, used when a paste is requested without an active range. */
    var focusedColumn by mutableIntStateOf(0)
        private set

    var contentVersion by mutableLongStateOf(0L)
        private set

    /** Approximate pixel scroll position, updated by [EditorSourceEditor]. */
    var scrollPosition by mutableIntStateOf(0)
        private set

    /**
     * Actual laid out heights for source rows that have entered the viewport.
     *
     * Source rows can wrap when the editor width is narrow, so a fixed row height is only a
     * fallback for rows that have not been measured yet. Stable IDs keep measurements attached
     * to a line while ordinary text edits recompose the row.
     */
    private val measuredRowHeightsPx = mutableMapOf<Long, Int>()
    private var measuredRowOffsetsPx: IntArray? = null
    // Kept outside Compose state because this is hit-test metadata, not document state. Entries
    // are refreshed whenever a visible row is positioned or its text layout changes.
    private val layoutMetrics = mutableMapOf<Long, EditorSourceLayoutMetrics>()

    // The legacy source view converted its 28dp minimum row height to pixels before
    // translating a saved scroll offset back to a line. Compose must use the same
    // density-aware value instead of treating 28 as a raw pixel count.
    private var estimatedRowHeightPx = 28

    var pendingFocus by mutableStateOf<FocusRequest?>(null)
        private set

    var pendingScroll by mutableStateOf<ScrollRequest?>(null)
        private set

    var selectionActionMenuVisible by mutableStateOf(false)
        private set

    /** Active cross-line handle drag, kept outside the row that owns the drawn handle. */
    var activeHandleDrag by mutableStateOf<EditorSourceHandleDrag?>(null)
        private set

    /** Active long-press selection drag, kept outside a recyclable source row. */
    var activeSelectionDrag by mutableStateOf<EditorSourceSelectionDrag?>(null)
        private set

    val lazyListState: LazyListState = LazyListState()

    private val documentChangedListeners = mutableListOf<() -> Unit>()
    private val documentChangeListeners = mutableListOf<(EditorSourceDocumentChange) -> Unit>()

    init {
        preferredLineEnding = lines.firstOrNull { it.lineEnding.isNotEmpty() }?.lineEnding ?: "\n"
    }

    val lineCount: Int
        get() = lines.size

    fun getDocumentText(): String = buildString {
        lines.forEach { line ->
            append(line.text)
            append(line.lineEnding)
        }
    }

    fun getDocumentLineCount(): Int = lines.size

    fun getDocumentLineText(index: Int): String = lines.getOrNull(index)?.text.orEmpty()

    fun addOnDocumentChangedListener(listener: () -> Unit): () -> Unit {
        documentChangedListeners += listener
        return { documentChangedListeners -= listener }
    }

    fun addOnDocumentChangeListener(listener: (EditorSourceDocumentChange) -> Unit): () -> Unit {
        documentChangeListeners += listener
        return { documentChangeListeners -= listener }
    }

    fun setDocumentText(value: String, preserveScroll: Boolean = false) {
        val previousScroll = scrollPosition
        cancelPendingSmartSelection()
        documentSelection = null
        selectionActionMenuVisible = false
        activeHandleDrag = null
        activeSelectionDrag = null
        pendingFocus = null
        focusedLine = -1
        focusedColumn = 0
        pendingScroll = null
        lines = parsePhysicalLines(value)
        measuredRowHeightsPx.clear()
        measuredRowOffsetsPx = null
        layoutMetrics.clear()
        contentVersion++
        if (preserveScroll) {
            requestScrollToPixel(previousScroll)
        }
    }

    fun replaceDocumentText(value: String) {
        val oldLineCount = lines.size
        val oldLength = getDocumentText().length
        setDocumentText(value)
        notifyDocumentChanged(
            EditorSourceDocumentChange(
                startLine = 0,
                oldLineCount = oldLineCount,
                newLineCount = lines.size,
                startOffset = 0,
                oldEndOffset = oldLength,
                newEndOffset = value.length
            )
        )
    }

    fun setDocumentEnabled(value: Boolean) {
        enabled = value
    }

    internal fun dispose() {
        cancelPendingSmartSelection()
        smartSelectionScope.cancel()
    }

    fun setSearchHighlights(ranges: List<EditorSourceHighlight>) {
        searchHighlightsState = ranges.sortedBy { it.start }
    }

    fun clearSearchHighlights() {
        if (searchHighlightsState.isNotEmpty()) searchHighlightsState = emptyList()
    }

    fun getDocumentScrollOffset(): Int = scrollPosition

    /** Scroll to a physical source offset. Compose executes the request after layout. */
    fun scrollToDocumentOffset(offset: Int) {
        val safe = offset.coerceIn(0, getDocumentText().length)
        var cursor = 0
        var targetLine: Int? = null
        lines.forEachIndexed { index, line ->
            val end = cursor + line.serializedLength
            // LineOffsets.findLine() treats a non-final line as [start, end), so an offset at
            // its terminator belongs to the following physical line. Keep the final line's
            // end inclusive so a document-end request still resolves to the last row.
            if (targetLine == null &&
                (safe < end || safe == end && index == lines.lastIndex)
            ) targetLine = index
            cursor = end
        }
        requestScroll(
            (targetLine ?: lines.lastIndex.coerceAtLeast(0)).coerceIn(0, lines.lastIndex.coerceAtLeast(0)),
            0,
            center = true
        )
    }

    /** Compatibility helper for callers that stored a pixel Y rather than a text offset. */
    fun scrollToDocumentY(offset: Int) {
        requestScrollToPixel(offset)
    }

    fun requestLineFocus(position: Int, column: Int, ensureVisible: Boolean = true) {
        val line = position.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        focusedLine = line
        focusedColumn = column.coerceIn(0, lines.getOrNull(line)?.text?.length ?: 0)
        focusSequence++
        pendingFocus = FocusRequest(
            sequence = focusSequence,
            line = line,
            column = column.coerceIn(0, lines.getOrNull(line)?.text?.length ?: 0)
        )
        if (ensureVisible) requestScroll(line, 0, center = false)
    }

    fun requestLineSelection(
        position: Int,
        start: Int,
        end: Int,
        ensureVisible: Boolean = true
    ) {
        val line = position.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val length = lines.getOrNull(line)?.text?.length ?: 0
        focusedLine = line
        focusedColumn = end.coerceIn(0, length)
        focusSequence++
        pendingFocus = FocusRequest(
            sequence = focusSequence,
            line = line,
            column = end.coerceIn(0, length),
            selectionStart = start.coerceIn(0, length),
            selectionEnd = end.coerceIn(0, length)
        )
        if (ensureVisible) requestScroll(line, 0, center = false)
    }

    fun consumeFocusRequest(sequence: Long) {
        if (pendingFocus?.sequence == sequence) {
            pendingFocus = null
        }
    }

    fun consumeScrollRequest(sequence: Long) {
        if (pendingScroll?.sequence == sequence) {
            pendingScroll = null
        }
    }

    /** Record a row's measured height and immediately reconcile the saved viewport offset. */
    internal fun updateMeasuredRowHeight(stableId: Long, heightPx: Int) {
        if (lines.none { it.stableId == stableId }) return
        val height = heightPx.coerceAtLeast(1)
        if (measuredRowHeightsPx[stableId] == height) return
        measuredRowHeightsPx[stableId] = height
        measuredRowOffsetsPx = null
        // A row can be remeasured without LazyListState emitting a new scroll event (for example
        // after a width change). Recalculate now so leaving source view preserves the new offset.
        val lineIndex = lines.indexOfFirst { it.stableId == stableId }
        if (lineIndex in 0 until lazyListState.firstVisibleItemIndex) {
            updateScrollPosition(
                lazyListState.firstVisibleItemIndex,
                lazyListState.firstVisibleItemScrollOffset,
                estimatedRowHeightPx
            )
        }
    }

    internal fun updateLayoutMetrics(
        position: Int,
        rootCoordinates: LayoutCoordinates?,
        textCoordinates: LayoutCoordinates?,
        layout: TextLayoutResult?
    ) {
        val stableId = lines.getOrNull(position)?.stableId ?: return
        if (rootCoordinates == null || textCoordinates == null || layout == null ||
            !rootCoordinates.isAttached || !textCoordinates.isAttached
        ) {
            layoutMetrics.remove(stableId)
            return
        }
        layoutMetrics[stableId] = EditorSourceLayoutMetrics(
            stableId = stableId,
            rootCoordinates = rootCoordinates,
            textCoordinates = textCoordinates,
            layout = layout
        )
    }

    internal fun clearLayoutMetrics(stableId: Long) {
        layoutMetrics.remove(stableId)
    }

    fun updateScrollPosition(firstVisibleItem: Int, firstVisibleOffset: Int, rowHeightPx: Int) {
        val nextEstimatedRowHeightPx = rowHeightPx.coerceAtLeast(1)
        if (estimatedRowHeightPx != nextEstimatedRowHeightPx) measuredRowOffsetsPx = null
        estimatedRowHeightPx = nextEstimatedRowHeightPx
        val item = firstVisibleItem.coerceIn(0, lines.size)
        val offsetBeforeItem = rowOffsets()[item]
        scrollPosition = (offsetBeforeItem.toLong() + firstVisibleOffset.coerceAtLeast(0))
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    internal fun updateFocusedLine(position: Int) {
        focusedLine = position
        focusedColumn = lines.getOrNull(position)?.text?.length ?: 0
    }

    internal fun updateFocusedColumn(position: Int, column: Int) {
        focusedLine = position
        focusedColumn = column.coerceIn(0, lines.getOrNull(position)?.text?.length ?: 0)
    }

    internal fun updateDocumentSelection(selection: EditorSourceSelection?) {
        documentSelection = selection
    }

    internal fun beginHandleDrag(
        sourcePosition: Int,
        anchor: Boolean,
        contentLeftPx: Float,
        textOriginPx: Float,
        charWidthPx: Float
    ) {
        activeHandleDrag = EditorSourceHandleDrag(
            sourcePosition = sourcePosition,
            anchor = anchor,
            contentLeftPx = contentLeftPx,
            textOriginPx = textOriginPx,
            charWidthPx = charWidthPx
        )
    }

    internal fun endHandleDrag() {
        activeHandleDrag = null
        // The legacy source view keeps both handles alive while a drag crosses the other
        // endpoint, even when the temporary range is zero length. Once the pointer is released
        // there is no editable selection left, so collapse that transient state now.
        if (!hasDocumentSelection()) {
            documentSelection = null
            selectionActionMenuVisible = false
        }
    }

    internal fun beginSelectionDrag(
        initialSelection: EditorSourceSelection?,
        initialTouchLine: Int,
        initialTouchOffset: Int
    ) {
        val selection = initialSelection ?: documentSelection ?: return
        activeSelectionDrag = EditorSourceSelectionDrag(
            initialSelection = selection,
            initialTouchLine = initialTouchLine.coerceIn(0, lines.lastIndex.coerceAtLeast(0)),
            initialTouchOffset = initialTouchOffset.coerceIn(
                0,
                lines.getOrNull(initialTouchLine)?.text?.length ?: 0
            )
        )
    }

    internal fun endSelectionDrag() {
        activeSelectionDrag = null
    }

    /** Update a handle from editor-level coordinates so the drag survives row recycling. */
    internal fun moveActiveHandleFromViewport(
        pointerX: Float,
        pointerY: Float,
        edgePx: Float,
        scrollStepPx: Float
    ) {
        val drag = activeHandleDrag ?: return
        autoScrollSelectionAtViewport(pointerY, edgePx, scrollStepPx)
        resolvePointerToText(pointerX, pointerY)?.let { (position, offset) ->
            moveSelectionHandle(drag.anchor, position, offset)
            return
        }
        moveSelectionHandleAtViewport(
            anchor = drag.anchor,
            pointerX = pointerX - drag.contentLeftPx,
            pointerY = pointerY,
            textOriginPx = drag.textOriginPx,
            charWidthPx = drag.charWidthPx
        )
    }

    /** Resolve a root-editor coordinate through the actual BasicTextField layout. */
    private fun resolvePointerToText(pointerX: Float, pointerY: Float): Pair<Int, Int>? {
        val visible = lazyListState.layoutInfo.visibleItemsInfo
        val targetInfo = visible.minByOrNull { item ->
            when {
                pointerY < item.offset.toFloat() -> item.offset.toFloat() - pointerY
                pointerY > (item.offset + item.size).toFloat() ->
                    pointerY - (item.offset + item.size).toFloat()
                else -> 0f
            }
        } ?: return null
        val position = targetInfo.index.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val stableId = lines.getOrNull(position)?.stableId ?: return null
        val metrics = layoutMetrics[stableId] ?: return null
        if (!metrics.rootCoordinates.isAttached || !metrics.textCoordinates.isAttached) {
            layoutMetrics.remove(stableId)
            return null
        }
        val textPosition = runCatching {
            metrics.textCoordinates.localPositionOf(
                metrics.rootCoordinates,
                Offset(pointerX, pointerY)
            )
        }.getOrNull() ?: return null
        val offset = metrics.layout.getOffsetForPosition(textPosition)
            .coerceIn(0, metrics.layout.layoutInput.text.length)
        return position to offset
    }

    private fun autoScrollSelectionAtViewport(
        pointerY: Float,
        edgePx: Float,
        scrollStepPx: Float
    ) {
        val layout = lazyListState.layoutInfo
        val top = layout.viewportStartOffset.toFloat()
        val bottom = layout.viewportEndOffset.toFloat()
        val delta = when {
            pointerY < top + edgePx -> -(top + edgePx - pointerY).coerceAtMost(scrollStepPx)
            pointerY > bottom - edgePx -> (pointerY - (bottom - edgePx)).coerceAtMost(scrollStepPx)
            else -> 0f
        }
        if (delta != 0f) lazyListState.dispatchRawDelta(delta)
    }

    private fun moveSelectionHandleAtViewport(
        anchor: Boolean,
        pointerX: Float,
        pointerY: Float,
        textOriginPx: Float,
        charWidthPx: Float
    ) {
        val targetInfo = lazyListState.layoutInfo.visibleItemsInfo.minByOrNull { item ->
            when {
                pointerY < item.offset.toFloat() -> item.offset.toFloat() - pointerY
                pointerY > (item.offset + item.size).toFloat() ->
                    pointerY - (item.offset + item.size).toFloat()
                else -> 0f
            }
        } ?: return
        val target = targetInfo.index.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val targetLength = lines.getOrNull(target)?.text?.length ?: 0
        val column = ((pointerX - textOriginPx) / charWidthPx.coerceAtLeast(1f))
            .roundToInt()
            .coerceIn(0, targetLength)
        moveSelectionHandle(anchor, target, column)
    }

    fun dismissSelectionActionMenu() {
        selectionActionMenuVisible = false
    }

    fun showSelectionActionMenu() {
        if (hasDocumentSelection()) selectionActionMenuVisible = true
    }

    fun hasDocumentSelection(): Boolean =
        selectedDocumentRange()?.let { it.first < it.second } == true

    fun clearDocumentSelection() {
        cancelPendingSmartSelection()
        documentSelection = null
        selectionActionMenuVisible = false
    }

    /** Select the same localized word or character cluster as the previous source editor. */
    internal fun selectWordAt(
        position: Int,
        offset: Int,
        locale: Locale,
        context: Context? = null
    ) {
        val text = lines.getOrNull(position)?.text ?: return
        if (text.isEmpty()) return
        cancelPendingSmartSelection()
        val localizedRange = sourceWordSelectionRange(text, offset, locale)
        val (start, end) = localizedRange
        documentSelection = EditorSourceSelection(position, start, position, end)
        // SourceEditorView keeps the child EditText at a collapsed caret while its
        // document-level range owns the selection.  Requesting a native range here lets
        // BasicTextField start a second selection controller after the long-press timeout,
        // which can overwrite the custom range on the next onValueChange callback.
        requestLineFocus(position, end, ensureVisible = false)
        val selectionGeneration = smartSelectionGeneration
        val selectionContext = context
        if (selectionContext != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            localizedRange.first < localizedRange.second &&
            sourceLetterOrDigitAt(text, localizedRange.first)
        ) {
            val selectionText = text
            smartSelectionJob = smartSelectionScope.launch {
                val smartRange = withContext(Dispatchers.Default) {
                    runCatching {
                        sourceSmartSelectionRange(selectionContext, selectionText, localizedRange, locale)
                    }.getOrNull()
                } ?: return@launch
                if (selectionGeneration != smartSelectionGeneration ||
                    lines.getOrNull(position)?.text != selectionText
                ) return@launch
                val current = documentSelection ?: return@launch
                val currentRange = selectedDocumentRange() ?: return@launch
                val expectedStart = lineStart(position) + localizedRange.first
                val expectedEnd = lineStart(position) + localizedRange.second
                if (currentRange.first != expectedStart || currentRange.second != expectedEnd) return@launch
                val smartStart = smartRange.first.coerceIn(0, selectionText.length)
                val smartEnd = smartRange.second.coerceIn(smartStart, selectionText.length)
                if (smartStart >= smartEnd ||
                    smartStart > localizedRange.first ||
                    smartEnd < localizedRange.second ||
                    smartStart == localizedRange.first && smartEnd == localizedRange.second
                ) return@launch
                if (sourceCjkCharacter(selectionText[localizedRange.first]) &&
                    (smartStart < localizedRange.first || smartEnd > localizedRange.second)
                ) return@launch
                val expandedSelection = current.copy(
                    anchorOffset = smartStart,
                    focusOffset = smartEnd
                )
                documentSelection = expandedSelection
                // SourceEditorView updates the drag seed when TextClassifier expands the initial
                // localized word. Otherwise the first MOVE after classification would rebuild
                // the range from the stale pre-classifier word and visibly shrink it.
                activeSelectionDrag = activeSelectionDrag?.copy(
                    initialSelection = expandedSelection
                )
                // Keep the classifier's expanded range at the document layer. A native
                // BasicTextField selection here would re-enable its own handles and compete
                // with the custom handles on the next pointer event.
                requestLineFocus(position, smartEnd, ensureVisible = false)
            }
        }
    }

    internal fun cancelPendingSmartSelection() {
        smartSelectionGeneration++
        smartSelectionJob?.cancel()
        smartSelectionJob = null
    }

    internal fun extendSelectionTo(position: Int, offset: Int) {
        cancelPendingSmartSelection()
        val selection = documentSelection ?: return
        val safeLine = position.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val safeOffset = offset.coerceIn(0, lines.getOrNull(safeLine)?.text?.length ?: 0)
        documentSelection = selection.copy(focusLine = safeLine, focusOffset = safeOffset)
        requestLineFocus(safeLine, safeOffset, ensureVisible = false)
    }

    /** Move one endpoint of a cross-line range while keeping the other endpoint fixed. */
    internal fun moveSelectionHandle(anchor: Boolean, position: Int, offset: Int) {
        cancelPendingSmartSelection()
        val selection = documentSelection ?: return
        val safeLine = position.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val safeOffset = offset.coerceIn(0, lines.getOrNull(safeLine)?.text?.length ?: 0)
        val updated = if (anchor) {
            selection.copy(anchorLine = safeLine, anchorOffset = safeOffset)
        } else {
            selection.copy(focusLine = safeLine, focusOffset = safeOffset)
        }
        documentSelection = updated
        val updatedRange = selectedDocumentRange()
        if (updatedRange == null || updatedRange.first >= updatedRange.second) {
            // Keep a zero-length range during an active handle drag. Removing it here would
            // remove both custom handles from composition and make it impossible to drag back
            // across the crossed endpoint. endHandleDrag() clears this transient state on UP.
            if (activeHandleDrag == null) {
                clearDocumentSelection()
            }
            requestLineFocus(safeLine, safeOffset, ensureVisible = false)
            return
        }
        // Native Compose handles are only meaningful for a selection contained in one
        // BasicTextField. Keep the focused row at a caret and draw the two document handles
        // below for a range spanning physical rows.
        // Keep the native field collapsed for same-line and cross-line ranges alike.  The
        // document range and its custom handles are the sole selection source of truth.
        requestLineFocus(updated.focusLine, updated.focusOffset, ensureVisible = false)
    }

    /** Resolve a dragged document handle against the visible physical rows. */
    internal fun moveSelectionHandleToPointer(
        anchor: Boolean,
        sourcePosition: Int,
        localX: Float,
        localY: Float,
        textOriginPx: Float,
        charWidthPx: Float
    ) {
        val sourceInfo = lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == sourcePosition }
            ?: return
        val pointerY = sourceInfo.offset + localY.roundToInt()
        val targetInfo = lazyListState.layoutInfo.visibleItemsInfo.minByOrNull { item ->
            when {
                pointerY < item.offset.toFloat() -> item.offset.toFloat() - pointerY
                pointerY > (item.offset + item.size).toFloat() ->
                    pointerY - (item.offset + item.size).toFloat()
                else -> 0f
            }
        } ?: return
        val target = targetInfo.index.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val targetLength = lines.getOrNull(target)?.text?.length ?: 0
        val column = ((localX - textOriginPx) / charWidthPx.coerceAtLeast(1f))
            .roundToInt()
            .coerceIn(0, targetLength)
        moveSelectionHandle(anchor, target, column)
    }

    /** Resolve a long-press drag against the physical rows currently laid out by LazyColumn. */
    internal fun extendSelectionToPointer(
        sourcePosition: Int,
        localX: Float,
        localY: Float,
        textOriginPx: Float,
        charWidthPx: Float,
        sourceOffset: Int? = null,
        initialSelection: EditorSourceSelection? = null,
        initialTouchLine: Int? = null,
        initialTouchOffset: Int? = null,
        locale: Locale = Locale.getDefault()
    ) {
        val sourceInfo = lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == sourcePosition }
            ?: return
        val pointerY = sourceInfo.offset + localY.roundToInt()
        val targetInfo = lazyListState.layoutInfo.visibleItemsInfo.minByOrNull { item ->
            when {
                pointerY < item.offset.toFloat() -> item.offset.toFloat() - pointerY
                pointerY > (item.offset + item.size).toFloat() ->
                    pointerY - (item.offset + item.size).toFloat()
                else -> 0f
            }
        } ?: return
        val target = targetInfo.index.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val targetLength = lines.getOrNull(target)?.text?.length ?: 0
        val column = if (target == sourcePosition && sourceOffset != null) {
            sourceOffset
        } else {
            ((localX - textOriginPx) / charWidthPx.coerceAtLeast(1f)).roundToInt()
        }.coerceIn(0, targetLength)
        if (initialSelection == null || initialTouchLine == null || initialTouchOffset == null) {
            extendSelectionTo(target, column)
            return
        }

        val initialStart = lineStart(initialSelection.anchorLine) + initialSelection.anchorOffset
        val initialEnd = lineStart(initialSelection.focusLine) + initialSelection.focusOffset
        val touchAbsolute = lineStart(initialTouchLine) + initialTouchOffset
        val targetAbsolute = lineStart(target) + column
        val movingRight = targetAbsolute >= touchAbsolute
        val targetWord = sourceWordSelectionRange(
            lines.getOrNull(target)?.text.orEmpty(),
            column,
            locale
        )
        val targetWordStart = lineStart(target) + targetWord.first
        val targetWordEnd = lineStart(target) + targetWord.second
        val anchorAbsolute: Int
        val focusAbsolute: Int
        if (movingRight) {
            anchorAbsolute = initialStart
            focusAbsolute = if (targetAbsolute <= initialEnd) initialEnd else targetWordEnd
        } else {
            anchorAbsolute = initialEnd
            focusAbsolute = if (targetAbsolute >= initialStart) initialStart else targetWordStart
        }
        val anchorPoint = lineAndColumnForAbsoluteOffset(anchorAbsolute)
        val focusPoint = lineAndColumnForAbsoluteOffset(focusAbsolute)
        documentSelection = EditorSourceSelection(
            anchorLine = anchorPoint.first,
            anchorOffset = anchorPoint.second,
            focusLine = focusPoint.first,
            focusOffset = focusPoint.second
        )
        // The legacy RecyclerView keeps the current viewport stable during a selection drag;
        // only the explicit edge-scroll path is allowed to move it.  Requesting a normal focus
        // here would enqueue scrollToItem on every MOVE and fight both pointer hit testing and
        // the edge-scroll delta.
        requestLineFocus(focusPoint.first, focusPoint.second, ensureVisible = false)
    }

    /**
     * Resolve the active long-press selection against editor coordinates. Unlike the row-local
     * helper above, this path does not require the source row that started the gesture to remain
     * visible, so LazyColumn recycling cannot terminate a cross-line drag.
     */
    internal fun extendSelectionDragFromViewport(
        pointerX: Float,
        pointerY: Float,
        textOriginPx: Float,
        charWidthPx: Float,
        edgePx: Float,
        scrollStepPx: Float,
        locale: Locale = Locale.getDefault()
    ) {
        val drag = activeSelectionDrag ?: return
        val layout = lazyListState.layoutInfo
        val top = layout.viewportStartOffset.toFloat()
        val bottom = layout.viewportEndOffset.toFloat()
        val delta = when {
            pointerY < top + edgePx -> -(top + edgePx - pointerY).coerceAtMost(scrollStepPx)
            pointerY > bottom - edgePx -> (pointerY - (bottom - edgePx)).coerceAtMost(scrollStepPx)
            else -> 0f
        }
        if (delta != 0f) lazyListState.dispatchRawDelta(delta)

        val resolvedTarget = resolvePointerToText(pointerX, pointerY)
        val target: Int
        val targetColumn: Int
        if (resolvedTarget != null) {
            target = resolvedTarget.first
            targetColumn = resolvedTarget.second
        } else {
            val targetInfo = lazyListState.layoutInfo.visibleItemsInfo.minByOrNull { item ->
                when {
                    pointerY < item.offset.toFloat() -> item.offset.toFloat() - pointerY
                    pointerY > (item.offset + item.size).toFloat() ->
                        pointerY - (item.offset + item.size).toFloat()
                    else -> 0f
                }
            } ?: return
            target = targetInfo.index.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
            val targetLength = lines.getOrNull(target)?.text?.length ?: 0
            targetColumn = ((pointerX - textOriginPx) / charWidthPx.coerceAtLeast(1f))
                .roundToInt()
                .coerceIn(0, targetLength)
        }

        val initialStart = lineStart(drag.initialSelection.anchorLine) +
            drag.initialSelection.anchorOffset
        val initialEnd = lineStart(drag.initialSelection.focusLine) +
            drag.initialSelection.focusOffset
        val touchAbsolute = lineStart(drag.initialTouchLine) + drag.initialTouchOffset
        val targetAbsolute = lineStart(target) + targetColumn

        // A stationary long press is allowed to finish the platform smart-word request. Once
        // the pointer crosses a document position, the explicit drag owns the range.
        if (targetAbsolute != touchAbsolute) cancelPendingSmartSelection()

        val movingRight = targetAbsolute >= touchAbsolute
        val targetWord = sourceWordSelectionRange(
            lines.getOrNull(target)?.text.orEmpty(),
            targetColumn,
            locale
        )
        val targetWordStart = lineStart(target) + targetWord.first
        val targetWordEnd = lineStart(target) + targetWord.second
        val anchorAbsolute: Int
        val focusAbsolute: Int
        if (movingRight) {
            anchorAbsolute = initialStart
            focusAbsolute = if (targetAbsolute <= initialEnd) initialEnd else targetWordEnd
        } else {
            anchorAbsolute = initialEnd
            focusAbsolute = if (targetAbsolute >= initialStart) initialStart else targetWordStart
        }
        val anchorPoint = lineAndColumnForAbsoluteOffset(anchorAbsolute)
        val focusPoint = lineAndColumnForAbsoluteOffset(focusAbsolute)
        val updated = EditorSourceSelection(
            anchorLine = anchorPoint.first,
            anchorOffset = anchorPoint.second,
            focusLine = focusPoint.first,
            focusOffset = focusPoint.second
        )
        if (updated == documentSelection) return
        documentSelection = updated
        // During a long-press drag the root pointer handler owns viewport movement.  Enqueueing
        // scrollToItem for every MOVE fights the explicit edge-scroll delta and can move the row
        // under the pointer before the next hit test, breaking cross-line selection.
        requestLineFocus(focusPoint.first, focusPoint.second, ensureVisible = false)
    }

    /** Scroll a long-press selection when the pointer reaches the viewport edge. */
    internal fun autoScrollSelection(
        sourcePosition: Int,
        localY: Float,
        edgePx: Float,
        scrollStepPx: Float = 24f
    ) {
        val layout = lazyListState.layoutInfo
        val source = layout.visibleItemsInfo.firstOrNull { it.index == sourcePosition } ?: return
        val pointerY = source.offset + localY
        val top = layout.viewportStartOffset.toFloat()
        val bottom = layout.viewportEndOffset.toFloat()
        val delta = when {
            pointerY < top + edgePx -> -(top + edgePx - pointerY).coerceAtMost(scrollStepPx)
            pointerY > bottom - edgePx -> (pointerY - (bottom - edgePx)).coerceAtMost(scrollStepPx)
            else -> 0f
        }
        if (delta != 0f) lazyListState.dispatchRawDelta(delta)
    }

    fun selectedDocumentText(): String? {
        val range = selectedDocumentRange()?.takeIf { it.first < it.second } ?: return null
        return getDocumentText().substring(range.first, range.second)
    }

    fun copySelectionToClipboard(context: Context): Boolean {
        val text = selectedDocumentText() ?: return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("source-selection", text))
        return true
    }

    fun cutSelection(context: Context): Boolean {
        if (!copySelectionToClipboard(context)) return false
        val range = selectedDocumentRange()?.takeIf { it.first < it.second } ?: return false
        replaceDocumentRange(range, "")
        clearDocumentSelection()
        return true
    }

    fun pasteFromClipboard(context: Context): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val clip = clipboard.primaryClip ?: return false
        if (clip.itemCount == 0) return false
        val pasted = clip.getItemAt(0).coerceToText(context).toString()
        val range = selectedDocumentRange()?.takeIf { it.first < it.second } ?: return false
        replaceDocumentRange(range, pasted)
        clearDocumentSelection()
        return true
    }

    fun selectAllDocument() {
        if (lines.isEmpty()) return
        cancelPendingSmartSelection()
        val last = lines.lastIndex
        documentSelection = EditorSourceSelection(0, 0, last, lines[last].text.length)
        if (last == 0) {
            // Custom document handles are used for same-line ranges too, so keep the native
            // field at a collapsed caret just like the legacy source view.
            requestLineFocus(0, lines[0].text.length, ensureVisible = false)
        } else {
            // Selecting the whole document must not jump the viewport to the last row.  The
            // legacy SourceEditorView kept the current RecyclerView position and rendered its
            // custom endpoints only for rows that were already attached.
            requestLineFocus(last, lines[last].text.length, ensureVisible = false)
        }
        showSelectionActionMenu()
    }

    /** Apply a BasicTextField value to one physical line, splitting embedded newlines. */
    internal fun updateLine(position: Int, value: TextFieldValue): Boolean {
        val line = lines.getOrNull(position) ?: return false
        val normalized = normalizeEditorText(value.text)
        val oldText = line.text
        val selection = documentSelection
        if (selection != null && selection.focusLine == position) {
            val change = textDiff(oldText, normalized)
            val safeStart = change.first.coerceIn(0, normalized.length)
            val safeBefore = change.second.coerceIn(0, (oldText.length - safeStart).coerceAtLeast(0))
            val safeCount = change.third.coerceIn(0, normalized.length - safeStart)
            val replacement = normalized.substring(safeStart, safeStart + safeCount)
            val expected = buildString(oldText.length - safeBefore + safeCount) {
                append(oldText, 0, safeStart)
                append(replacement)
                append(oldText, safeStart + safeBefore, oldText.length)
            }
            if (expected == normalized && (safeBefore > 0 || safeCount > 0)) {
                selectedDocumentRange()?.takeIf { it.first < it.second }?.let { range ->
                    replaceDocumentRange(range, replacement)
                    return true
                }
            }
        }
        if ('\n' !in normalized) {
            if (oldText == normalized) {
                // Selection callbacks without a text change are transient implementation
                // details. Keep same-line keyboard selection in BasicTextField, matching the
                // legacy SourceLineEditText; long-press and cross-line ranges are seeded through
                // the document selection model.
                return false
            }
            lines = lines.toMutableList().also { mutable ->
                mutable[position] = line.copy(text = normalized)
            }
            measuredRowHeightsPx.remove(line.stableId)
            measuredRowOffsetsPx = null
            pruneMeasuredRowHeights()
            cancelPendingSmartSelection()
            updateLocalSelection(position, value.selection)
            notifyDocumentChanged(
                EditorSourceDocumentChange(
                    startLine = position,
                    oldLineCount = 1,
                    newLineCount = 1,
                    startOffset = lineStart(position),
                    oldEndOffset = lineStart(position) + oldText.length,
                    newEndOffset = lineStart(position) + normalized.length
                )
            )
            return true
        }

        val parts = splitEditorLines(normalized)
        cancelPendingSmartSelection()
        val originalEnding = line.lineEnding
        val inserted = parts.drop(1).mapIndexed { index, text ->
            EditorSourceLine(
                stableId = nextLineId++,
                text = text,
                lineEnding = if (index == parts.lastIndex - 1) originalEnding else preferredLineEnding
            )
        }
        val replacement = line.copy(text = parts.first(), lineEnding = preferredLineEnding)
        lines = lines.toMutableList().also { mutable ->
            mutable[position] = replacement
            mutable.addAll(position + 1, inserted)
        }
        measuredRowHeightsPx.remove(line.stableId)
        measuredRowOffsetsPx = null
        pruneMeasuredRowHeights()
        val prefix = normalizeEditorText(value.text.take(value.selection.end.coerceIn(0, value.text.length)))
        val targetLine = (position + prefix.count { it == '\n' }).coerceIn(0, lines.lastIndex)
        val targetColumn = prefix.substringAfterLast('\n').length
        requestLineFocus(targetLine, targetColumn)
        notifyDocumentChanged(
            EditorSourceDocumentChange(
                startLine = position,
                oldLineCount = 1,
                newLineCount = parts.size,
                startOffset = lineStart(position),
                oldEndOffset = lineStart(position) + line.serializedLength,
                newEndOffset = lineStart(position) + normalized.length + originalEnding.length
            )
        )
        return true
    }

    internal fun splitLineAt(position: Int, column: Int): Boolean {
        val line = lines.getOrNull(position) ?: return false
        val safeColumn = column.coerceIn(0, line.text.length)
        val before = line.text.substring(0, safeColumn)
        val after = line.text.substring(safeColumn)
        val first = line.copy(text = before, lineEnding = preferredLineEnding)
        val second = EditorSourceLine(nextLineId++, after, line.lineEnding)
        val startOffset = lineStart(position)
        lines = lines.toMutableList().also { mutable ->
            mutable[position] = first
            mutable.add(position + 1, second)
        }
        measuredRowHeightsPx.remove(line.stableId)
        measuredRowOffsetsPx = null
        pruneMeasuredRowHeights()
        requestLineFocus(position + 1, 0)
        notifyDocumentChanged(
            EditorSourceDocumentChange(
                startLine = position,
                oldLineCount = 1,
                newLineCount = 2,
                startOffset = startOffset,
                oldEndOffset = startOffset + line.serializedLength,
                newEndOffset = startOffset + first.serializedLength + second.serializedLength
            )
        )
        return true
    }

    internal fun mergeWithPrevious(position: Int): Boolean {
        if (position <= 0 || position !in lines.indices) return false
        val previous = lines[position - 1]
        val current = lines[position]
        val merged = previous.copy(text = previous.text + current.text, lineEnding = current.lineEnding)
        val startOffset = lineStart(position - 1)
        lines = lines.toMutableList().also { mutable ->
            mutable[position - 1] = merged
            mutable.removeAt(position)
        }
        measuredRowHeightsPx.remove(previous.stableId)
        measuredRowOffsetsPx = null
        pruneMeasuredRowHeights()
        requestLineFocus(position - 1, previous.text.length)
        notifyDocumentChanged(
            EditorSourceDocumentChange(
                startLine = position - 1,
                oldLineCount = 2,
                newLineCount = 1,
                startOffset = startOffset,
                // The current line's terminator becomes the merged line's terminator and is
                // therefore not part of the replaced range. This matches SourceEditorView's
                // offset contract and keeps incremental source synchronisation lossless.
                oldEndOffset = startOffset + previous.serializedLength + current.text.length,
                newEndOffset = startOffset + merged.serializedLength
            )
        )
        return true
    }

    internal fun mergeWithNext(position: Int): Boolean {
        if (position !in 0 until lines.lastIndex) return false
        val current = lines[position]
        val next = lines[position + 1]
        val merged = current.copy(text = current.text + next.text, lineEnding = next.lineEnding)
        val startOffset = lineStart(position)
        lines = lines.toMutableList().also { mutable ->
            mutable[position] = merged
            mutable.removeAt(position + 1)
        }
        measuredRowHeightsPx.remove(current.stableId)
        measuredRowOffsetsPx = null
        pruneMeasuredRowHeights()
        requestLineFocus(position, current.text.length)
        notifyDocumentChanged(
            EditorSourceDocumentChange(
                startLine = position,
                oldLineCount = 2,
                newLineCount = 1,
                startOffset = startOffset,
                // Preserve the next line's terminator when the two physical lines merge.
                oldEndOffset = startOffset + current.serializedLength + next.text.length,
                newEndOffset = startOffset + merged.serializedLength
            )
        )
        return true
    }

    internal fun moveCursorVertically(position: Int, direction: Int, column: Int): Boolean {
        val target = position + direction
        if (target !in lines.indices) return false
        requestLineFocus(target, column.coerceAtMost(lines[target].text.length))
        return true
    }

    internal fun extendCursorVertically(
        position: Int,
        direction: Int,
        anchorColumn: Int,
        focusColumn: Int
    ): Boolean {
        val target = position + direction
        if (target !in lines.indices) return false
        val current = documentSelection
        val anchorLine = current?.anchorLine?.coerceIn(0, lines.lastIndex) ?: position
        val anchorOffset = current?.anchorOffset?.coerceIn(0, lines[anchorLine].text.length)
            ?: anchorColumn.coerceIn(0, lines[position].text.length)
        val targetOffset = focusColumn.coerceIn(0, lines[target].text.length)
        // Match SourceEditorView: moving the active endpoint back onto the anchor
        // collapses the document selection and restores an ordinary caret. Keeping a
        // zero-length selection here leaves the cross-row selection affordances and
        // action menu state out of sync with the editor's actual caret.
        if (current != null && anchorLine == target && anchorOffset == targetOffset) {
            clearDocumentSelection()
            requestLineFocus(target, targetOffset)
            return true
        }
        documentSelection = EditorSourceSelection(anchorLine, anchorOffset, target, targetOffset)
        requestLineFocus(target, targetOffset, ensureVisible = false)
        return true
    }

    internal fun localSelection(position: Int, selection: TextRange) {
        val safeStart = selection.start.coerceAtLeast(0)
        val safeEnd = selection.end.coerceAtLeast(0)
        updateFocusedColumn(position, safeEnd)
        documentSelection = if (safeStart == safeEnd) {
            null
        } else {
            EditorSourceSelection(position, safeStart, position, safeEnd)
        }
        if (safeStart == safeEnd) selectionActionMenuVisible = false
    }

    private fun updateLocalSelection(position: Int, selection: TextRange) {
        localSelection(position, selection)
    }

    private fun lineStart(position: Int): Int = lines.take(position).sumOf { it.serializedLength }

    private fun selectedDocumentRange(): Pair<Int, Int>? {
        val selection = documentSelection ?: return null
        val anchor = lineStart(selection.anchorLine) +
            selection.anchorOffset.coerceIn(0, lines.getOrNull(selection.anchorLine)?.text?.length ?: 0)
        val focus = lineStart(selection.focusLine) +
            selection.focusOffset.coerceIn(0, lines.getOrNull(selection.focusLine)?.text?.length ?: 0)
        return minOf(anchor, focus) to maxOf(anchor, focus)
    }

    private fun replaceDocumentRange(range: Pair<Int, Int>, replacement: String) {
        val oldText = getDocumentText()
        val start = range.first.coerceIn(0, oldText.length)
        val end = range.second.coerceIn(start, oldText.length)
        val startLine = lineAndColumnForAbsoluteOffset(start).first
        val updatedText = buildString(oldText.length - (end - start) + replacement.length) {
            append(oldText, 0, start)
            append(replacement)
            append(oldText, end, oldText.length)
        }
        val oldLineCount = lines.size
        setDocumentText(updatedText, preserveScroll = true)
        notifyDocumentChanged(
            EditorSourceDocumentChange(
                startLine = startLine,
                oldLineCount = oldLineCount,
                newLineCount = lines.size,
                startOffset = start,
                oldEndOffset = end,
                newEndOffset = start + replacement.length
            )
        )
        val targetOffset = (start + replacement.length).coerceIn(0, updatedText.length)
        val target = lineAndColumnForAbsoluteOffset(targetOffset)
        requestLineFocus(target.first, target.second)
    }

    private fun lineAndColumnForAbsoluteOffset(offset: Int): Pair<Int, Int> {
        val safe = offset.coerceIn(0, getDocumentText().length)
        var cursor = 0
        lines.forEachIndexed { index, line ->
            val textEnd = cursor + line.text.length
            val serializedEnd = cursor + line.serializedLength
            if (safe <= textEnd || safe <= serializedEnd && index == lines.lastIndex) {
                return index to (safe - cursor).coerceIn(0, line.text.length)
            }
            cursor = serializedEnd
        }
        val last = lines.lastIndex.coerceAtLeast(0)
        return last to (lines.getOrNull(last)?.text?.length ?: 0)
    }

    private fun requestScroll(line: Int, offset: Int, center: Boolean) {
        scrollSequence++
        pendingScroll = ScrollRequest(scrollSequence, line, offset, center)
    }

    private fun requestScrollToPixel(pixel: Int) {
        val offsets = rowOffsets()
        val target = pixel.coerceIn(0, offsets.last())
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (offsets[middle] <= target) low = middle + 1 else high = middle
        }
        val line = (low - 1).coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        requestScroll(line, -(target - offsets[line]), center = false)
    }

    private fun rowOffsets(): IntArray {
        measuredRowOffsetsPx?.let { return it }
        val offsets = IntArray(lines.size + 1)
        val fallback = estimatedRowHeightPx.coerceAtLeast(1)
        lines.forEachIndexed { index, line ->
            val height = measuredRowHeightsPx[line.stableId] ?: fallback
            offsets[index + 1] = (offsets[index].toLong() + height)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
        measuredRowOffsetsPx = offsets
        return offsets
    }

    private fun pruneMeasuredRowHeights() {
        val currentIds = lines.asSequence().map { it.stableId }.toSet()
        if (measuredRowHeightsPx.keys.retainAll(currentIds)) measuredRowOffsetsPx = null
    }

    private fun notifyDocumentChanged(change: EditorSourceDocumentChange) {
        documentChangedListeners.toList().forEach { it() }
        documentChangeListeners.toList().forEach { it(change) }
    }

    private fun parsePhysicalLines(value: String): List<EditorSourceLine> {
        val result = mutableListOf<EditorSourceLine>()
        if (value.isEmpty()) return listOf(EditorSourceLine(nextLineId++, "", ""))
        var start = 0
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
            result += EditorSourceLine(nextLineId++, value.substring(start, index), ending)
            index += ending.length
            start = index
        }
        result += EditorSourceLine(nextLineId++, value.substring(start), "")
        preferredLineEnding = result.firstOrNull { it.lineEnding.isNotEmpty() }?.lineEnding ?: "\n"
        return result
    }

    private fun normalizeEditorText(value: String): String = value
        .replace("\r\n", "\n")
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

    private fun textDiff(old: String, new: String): Triple<Int, Int, Int> {
        var prefix = 0
        while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (
            suffix < old.length - prefix && suffix < new.length - prefix &&
            old[old.length - suffix - 1] == new[new.length - suffix - 1]
        ) suffix++
        return Triple(prefix, old.length - prefix - suffix, new.length - prefix - suffix)
    }

}

/** The old source view seeded long-press selection with ICU word and grapheme boundaries. */
internal fun sourceWordSelectionRange(text: String, offset: Int, locale: Locale): Pair<Int, Int> {
    if (text.isEmpty()) return 0 to 0
    val pivot = offset.coerceIn(0, text.lastIndex)
    val iterator = BreakIterator.getWordInstance(locale).apply { setText(text) }
    if (!sourceLetterOrDigitAt(text, pivot)) return sourceCharacterClusterRange(text, pivot, locale)
    if (pivot > 0 && sourceCjkCharacter(text[pivot - 1]) && sourceCjkCharacter(text[pivot]) &&
        iterator.isBoundary(pivot)
    ) {
        val rightOffset = (pivot + 1).coerceAtMost(text.length)
        val rightStart = sourceWordBeginning(iterator, text, rightOffset)
        val rightEnd = sourceWordEnd(iterator, text, rightOffset)
        if (rightStart >= 0 && rightEnd > rightStart) return rightStart to rightEnd
    }
    val start = sourceWordBeginning(iterator, text, pivot)
    val end = sourceWordEnd(iterator, text, pivot)
    if (start >= 0 && end > start) return start to end
    if (sourceCjkCharacter(text[pivot])) return sourceCharacterClusterRange(text, pivot, locale)
    if (sourceLetterOrDigitAt(text, pivot)) {
        var fallbackStart = pivot
        var fallbackEnd = pivot + 1
        while (fallbackStart > 0 && sourceLetterOrDigitAt(text, fallbackStart - 1)) fallbackStart--
        while (fallbackEnd < text.length && sourceLetterOrDigitAt(text, fallbackEnd)) fallbackEnd++
        return fallbackStart to fallbackEnd
    }
    return sourceCharacterClusterRange(text, pivot, locale)
}

private fun sourceWordBeginning(iterator: BreakIterator, text: String, offset: Int): Int {
    if (sourceLetterOrDigitAt(text, offset)) {
        if (iterator.isBoundary(offset) && !sourceLetterOrDigitBefore(text, offset)) return offset
        return iterator.preceding(offset)
    }
    if (sourceLetterOrDigitBefore(text, offset)) return iterator.preceding(offset)
    return BreakIterator.DONE
}

@Suppress("DEPRECATION")
private fun sourceSmartSelectionRange(
    context: Context,
    text: String,
    localizedRange: Pair<Int, Int>,
    locale: Locale
): Pair<Int, Int>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        localizedRange.first >= localizedRange.second ||
        !sourceLetterOrDigitAt(text, localizedRange.first)
    ) return null
    val manager = context.getSystemService(TextClassificationManager::class.java) ?: return null
    val classifier = manager.textClassifier ?: return null
    val defaultLocales = LocaleList(locale)
    val result = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            classifier.suggestSelection(
                TextSelection.Request.Builder(
                    text,
                    localizedRange.first,
                    localizedRange.second
                ).setDefaultLocales(defaultLocales).build()
            )
        } else {
            classifier.suggestSelection(
                text,
                localizedRange.first,
                localizedRange.second,
                defaultLocales
            )
        }
    }.getOrNull() ?: return null
    val start = result.selectionStartIndex.coerceIn(0, text.length)
    val end = result.selectionEndIndex.coerceIn(start, text.length)
    if (start >= localizedRange.first && end <= localizedRange.second) return null
    if (sourceCjkCharacter(text[localizedRange.first]) &&
        (start < localizedRange.first || end > localizedRange.second)
    ) return null
    return (start to end).takeIf { it.first <= localizedRange.first && it.second >= localizedRange.second }
}

private fun sourceWordEnd(iterator: BreakIterator, text: String, offset: Int): Int {
    if (sourceLetterOrDigitBefore(text, offset)) {
        if (iterator.isBoundary(offset) && !sourceLetterOrDigitAt(text, offset)) return offset
        return iterator.following(offset)
    }
    if (sourceLetterOrDigitAt(text, offset)) return iterator.following(offset)
    return BreakIterator.DONE
}

private fun sourceCharacterClusterRange(text: String, pivot: Int, locale: Locale): Pair<Int, Int> {
    val iterator = BreakIterator.getCharacterInstance(locale).apply { setText(text) }
    val start = iterator.preceding((pivot + 1).coerceAtMost(text.length))
    val end = iterator.following(pivot)
    val safeStart = if (start == BreakIterator.DONE) 0 else start
    val safeEnd = if (end == BreakIterator.DONE) text.length else end
    return if (safeStart < safeEnd) safeStart to safeEnd else pivot to (pivot + 1).coerceAtMost(text.length)
}

private fun sourceLetterOrDigitAt(text: String, offset: Int): Boolean =
    offset in text.indices && Character.isLetterOrDigit(Character.codePointAt(text, offset))

private fun sourceLetterOrDigitBefore(text: String, offset: Int): Boolean =
    offset in 1..text.length && Character.isLetterOrDigit(Character.codePointBefore(text, offset))

private fun sourceCjkCharacter(character: Char): Boolean = when (Character.UnicodeScript.of(character.code)) {
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL -> true
    else -> false
}

@Composable
internal fun rememberEditorSourceEditorState(initialText: String = ""): EditorSourceEditorState =
    remember { EditorSourceEditorState(initialText) }

/** Pure Compose source editor. No AndroidView, XML binding, or legacy source editor is used. */
@Composable
internal fun EditorSourceEditor(
    state: EditorSourceEditorState,
    modifier: Modifier = Modifier,
    onDocumentChanged: () -> Unit = {},
    onDocumentChange: (EditorSourceDocumentChange) -> Unit = {},
    onEditorFocusChanged: () -> Unit = {},
    onEditorSelectionChanged: (EditorSourceSelection?) -> Unit = {}
) {
    DisposableEffect(state, onDocumentChanged, onDocumentChange) {
        val removeChanged = state.addOnDocumentChangedListener(onDocumentChanged)
        val removeChange = state.addOnDocumentChangeListener(onDocumentChange)
        onDispose {
            removeChanged()
            removeChange()
            // The activity keeps this state while switching between list and source modes;
            // cancel the pending classifier request but keep the scope reusable on re-entry.
            state.cancelPendingSmartSelection()
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    // Keep the platform range visible for ordinary keyboard selection.  The transparent
    // palette is only needed while the editor-level document range owns the selection handles.
    val nativeTextSelectionColors = LocalTextSelectionColors.current
    val textSelectionColors = if (state.documentSelection == null) {
        nativeTextSelectionColors
    } else {
        SourceEditorTextSelectionColors
    }
    val rowHeightPx = with(density) { 28.dp.roundToPx().coerceAtLeast(1) }
    LaunchedEffect(state.lazyListState, rowHeightPx) {
        androidx.compose.runtime.snapshotFlow {
            state.lazyListState.firstVisibleItemIndex to state.lazyListState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            state.updateScrollPosition(index, offset, rowHeightPx)
        }
    }

    val scrollRequest = state.pendingScroll
    LaunchedEffect(scrollRequest?.sequence) {
        val request = scrollRequest ?: return@LaunchedEffect
        val target = request.line.coerceIn(0, (state.lines.size - 1).coerceAtLeast(0))
        val viewport = state.lazyListState.layoutInfo.viewportEndOffset -
            state.lazyListState.layoutInfo.viewportStartOffset
        // SourceEditorView used scrollToPositionWithOffset(line, height / 3): a searched
        // source line was placed one third of the viewport below the top edge. Compose's
        // scrollToItem uses the same positive offset convention.
        val offset = if (request.center && viewport > 0) viewport / 3 else request.offset
        state.lazyListState.scrollToItem(target, offset)
        state.consumeScrollRequest(request.sequence)
    }

    val context = LocalContext.current
    val hostView = LocalView.current
    var editorCoordinates by remember(state) { mutableStateOf<LayoutCoordinates?>(null) }
    val actionModeHolder = remember(hostView) { mutableStateOf<ActionMode?>(null) }
    var selectionContentRect by remember(state) { mutableStateOf<Rect?>(null) }
    var selectionGestureInProgress by remember(state) { mutableStateOf(false) }
    var selectionToolbarSuppressedForScroll by remember(state) { mutableStateOf(false) }
    val currentSelectionContentRect = rememberUpdatedState(selectionContentRect)

    val updateSelectionContentRect: (Int, LayoutCoordinates, TextLayoutResult?) -> Unit =
        remember(state, hostView) {
            { position, coordinates, layout ->
                val selection = state.documentSelection
                if (selection?.focusLine == position && layout != null) {
                    val offset = selection.focusOffset.coerceIn(0, layout.layoutInput.text.length)
                    val caret = layout.getCursorRect(offset)
                    val point = coordinates.localToWindow(Offset(caret.left, caret.top))
                    val hostLocation = IntArray(2)
                    hostView.getLocationInWindow(hostLocation)
                    val left = (point.x - hostLocation[0]).roundToInt()
                    val top = (point.y - hostLocation[1]).roundToInt()
                    val height = (caret.bottom - caret.top).roundToInt().coerceAtLeast(1)
                    selectionContentRect = Rect(left, top, left + 1, top + height)
                }
            }
        }

    val actionModeCallback = remember(hostView, state, context) {
        object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                if (!state.hasDocumentSelection()) return false
                actionModeHolder.value = mode
                addSourceSelectionMenu(context, menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.findItem(SOURCE_SELECTION_MENU_PASTE)?.isEnabled =
                    clipboardHasText(context)
                return true
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val rect = currentSelectionContentRect.value
                if (rect == null) {
                    outRect.set(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
                    return
                }
                if (view === hostView) {
                    outRect.set(rect)
                } else {
                    val hostLocation = IntArray(2)
                    val viewLocation = IntArray(2)
                    hostView.getLocationInWindow(hostLocation)
                    view.getLocationInWindow(viewLocation)
                    outRect.set(rect)
                    outRect.offset(
                        hostLocation[0] - viewLocation[0],
                        hostLocation[1] - viewLocation[1]
                    )
                }
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
                when (item.itemId) {
                    SOURCE_SELECTION_MENU_SELECT_ALL -> {
                        state.selectAllDocument()
                        mode.invalidateContentRect()
                        true
                    }
                    SOURCE_SELECTION_MENU_CUT -> state.cutSelection(context)
                    SOURCE_SELECTION_MENU_COPY -> state.copySelectionToClipboard(context).also {
                        if (it) mode.finish()
                    }
                    SOURCE_SELECTION_MENU_PASTE -> state.pasteFromClipboard(context)
                    else -> false
                }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (actionModeHolder.value === mode) {
                    actionModeHolder.value = null
                    state.dismissSelectionActionMenu()
                }
            }
        }
    }

    LaunchedEffect(state, state.lazyListState) {
        snapshotFlow { state.lazyListState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && state.documentSelection != null && !selectionGestureInProgress) {
                selectionToolbarSuppressedForScroll = true
                state.dismissSelectionActionMenu()
            }
        }
    }

    LaunchedEffect(
        state.selectionActionMenuVisible,
        state.documentSelection,
        selectionToolbarSuppressedForScroll,
        actionModeHolder.value
    ) {
        val hasSelection = state.hasDocumentSelection()
        if (!state.selectionActionMenuVisible || !hasSelection || selectionToolbarSuppressedForScroll) {
            actionModeHolder.value?.finish()
        } else if (actionModeHolder.value == null) {
            actionModeHolder.value = hostView.startActionMode(
                actionModeCallback,
                ActionMode.TYPE_FLOATING
            )
            if (actionModeHolder.value == null) state.dismissSelectionActionMenu()
        }
    }

    LaunchedEffect(selectionContentRect, state.documentSelection, actionModeHolder.value) {
        actionModeHolder.value?.invalidateContentRect()
    }

    DisposableEffect(hostView) {
        onDispose { actionModeHolder.value?.finish() }
    }

    val onSelectionGestureStarted: () -> Unit = {
        selectionGestureInProgress = true
        selectionToolbarSuppressedForScroll = false
    }
    val onSelectionGestureEnded: () -> Unit = {
        selectionGestureInProgress = false
        if (state.hasDocumentSelection()) state.showSelectionActionMenu()
    }
    val onSelectionHandleDragStarted: () -> Unit = {
        selectionGestureInProgress = true
        selectionToolbarSuppressedForScroll = false
        state.dismissSelectionActionMenu()
    }
    val onSelectionHandleDragEnded: () -> Unit = {
        selectionGestureInProgress = false
        selectionToolbarSuppressedForScroll = false
        if (state.hasDocumentSelection()) state.showSelectionActionMenu()
    }

    // Match SourceEditorView's 48dp edge zone for custom handle auto-scroll.
    val editorHandleEdgePx = with(density) { 48.dp.toPx() }
    val editorHandleScrollStepPx = with(density) { 24.dp.toPx() }
    val editorSelectionTouchSlopPx = LocalViewConfiguration.current.touchSlop
    val gutterDigits = state.lines.size.coerceAtLeast(1).toString().length
    val gutterNumberPaint = remember(context) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                12f,
                context.resources.displayMetrics
            )
        }
    }
    val gutterNumberWidth = ceil((0..9).maxOf { digit ->
        gutterNumberPaint.measureText(digit.toString().repeat(gutterDigits))
    }).toInt()
    val gutterWidthPx = max(
        with(density) { 24.dp.roundToPx() },
        gutterNumberWidth +
            with(density) { 4.dp.roundToPx() + 8.dp.roundToPx() } +
            (2f * density.density).roundToInt()
    )
    val gutterWidth = with(density) { gutterWidthPx.toDp() }
    val editorSelectionTextOriginPx = with(density) {
        8.dp.toPx() + gutterWidthPx + 1.dp.toPx() + 12.dp.toPx()
    }
    val editorSelectionCharWidthPx = with(density) { 14.sp.toPx() * 0.6f }
    val imeBottomPadding = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    CompositionLocalProvider(
        LocalTextToolbar provides SourceEditorTextToolbar,
        LocalTextSelectionColors provides textSelectionColors
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .onGloballyPositioned { editorCoordinates = it }
                .pointerInput(
                    state,
                    editorSelectionTextOriginPx,
                    editorSelectionCharWidthPx,
                    editorHandleEdgePx,
                    editorHandleScrollStepPx,
                    editorSelectionTouchSlopPx
                ) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastX = down.position.x
                    var lastY = down.position.y
                    val activePointerId = down.id
                    var released = false
                    var selectionPointerMoved = false
                    var handleDragSessionStarted = false
                    var handleDragSessionEnded = false
                    var selectionDragSessionStarted = false
                    var selectionDragSessionEnded = false
                    try {
                        while (!released) {
                            // Observe in Initial before LazyColumn can turn a handle or document
                            // selection drag into a list scroll. This editor-level observer remains
                            // alive after the originating LazyColumn row is recycled.
                            val event = withTimeoutOrNull(16L) {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }
                            if (event == null) {
                                when {
                                    state.activeHandleDrag != null -> {
                                        handleDragSessionStarted = true
                                        state.moveActiveHandleFromViewport(
                                            pointerX = lastX,
                                            pointerY = lastY,
                                            edgePx = editorHandleEdgePx,
                                            scrollStepPx = editorHandleScrollStepPx
                                        )
                                    }
                                    state.activeSelectionDrag != null && selectionPointerMoved -> {
                                        selectionDragSessionStarted = true
                                        state.dismissSelectionActionMenu()
                                        state.extendSelectionDragFromViewport(
                                            pointerX = lastX,
                                            pointerY = lastY,
                                            textOriginPx = editorSelectionTextOriginPx,
                                            charWidthPx = editorSelectionCharWidthPx,
                                            edgePx = editorHandleEdgePx,
                                            scrollStepPx = editorHandleScrollStepPx
                                        )
                                    }
                                }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == activePointerId }
                                ?: continue
                            if (change.pressed) {
                                if (!selectionPointerMoved &&
                                    (change.position - down.position).getDistance() >
                                    editorSelectionTouchSlopPx
                                ) {
                                    selectionPointerMoved = true
                                }
                                lastX = change.position.x
                                lastY = change.position.y
                                when {
                                    state.activeHandleDrag != null -> {
                                        handleDragSessionStarted = true
                                        state.moveActiveHandleFromViewport(
                                            pointerX = lastX,
                                            pointerY = lastY,
                                            edgePx = editorHandleEdgePx,
                                            scrollStepPx = editorHandleScrollStepPx
                                        )
                                        change.consume()
                                    }
                                    state.activeSelectionDrag != null && selectionPointerMoved -> {
                                        selectionDragSessionStarted = true
                                        state.dismissSelectionActionMenu()
                                        state.extendSelectionDragFromViewport(
                                            pointerX = lastX,
                                            pointerY = lastY,
                                            textOriginPx = editorSelectionTextOriginPx,
                                            charWidthPx = editorSelectionCharWidthPx,
                                            edgePx = editorHandleEdgePx,
                                            scrollStepPx = editorHandleScrollStepPx
                                        )
                                        change.consume()
                                    }
                                }
                            }
                            if (!change.pressed) {
                                released = true
                                when {
                                    state.activeHandleDrag != null -> {
                                        change.consume()
                                        state.endHandleDrag()
                                        handleDragSessionEnded = true
                                        onSelectionHandleDragEnded()
                                    }
                                    state.activeSelectionDrag != null -> {
                                        change.consume()
                                        state.endSelectionDrag()
                                        selectionDragSessionEnded = true
                                        onSelectionGestureEnded()
                                    }
                                    !selectionPointerMoved && state.documentSelection != null -> {
                                        // Match SourceEditorView.dispatchTouchEvent: a stationary
                                        // tap clears an existing document range, while a scroll
                                        // leaves the range intact (the toolbar is dismissed by the
                                        // scroll observer).
                                        state.clearDocumentSelection()
                                    }
                                }
                            }
                        }
                    } finally {
                        if (handleDragSessionStarted && !handleDragSessionEnded) {
                            state.endHandleDrag()
                            onSelectionHandleDragEnded()
                        }
                        if (selectionDragSessionStarted && !selectionDragSessionEnded) {
                            state.endSelectionDrag()
                            onSelectionGestureEnded()
                        }
                    }
                }
                }
        ) {
            LazyColumn(
                state = state.lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    top = 8.dp,
                    end = 8.dp,
                    bottom = 8.dp + imeBottomPadding
                )
            ) {
                itemsIndexed(state.lines, key = { _, line -> line.stableId }) { position, line ->
                    EditorSourceLineRow(
                        state = state,
                        context = context,
                        line = line,
                        position = position,
                        gutterWidth = gutterWidth,
                        onEditorFocusChanged = onEditorFocusChanged,
                        onEditorSelectionChanged = onEditorSelectionChanged,
                        onSelectionContentRectChanged = updateSelectionContentRect,
                        onSelectionGestureStarted = onSelectionGestureStarted,
                        onSelectionGestureEnded = onSelectionGestureEnded,
                        onSelectionHandleDragStarted = onSelectionHandleDragStarted,
                        onSelectionHandleDragEnded = onSelectionHandleDragEnded,
                        editorCoordinates = editorCoordinates,
                        onLayoutMetricsChanged = state::updateLayoutMetrics,
                        contentLeftPx = with(density) { 8.dp.toPx() + gutterWidthPx + 1.dp.toPx() }
                    )
                }
            }
            DraggableScrollbar(
                state = state.lazyListState,
                modifier = Modifier.align(Alignment.CenterEnd),
                fixedThumbSize = true
            )
        }
    }
}

@Composable
private fun EditorSourceLineRow(
    state: EditorSourceEditorState,
    context: Context,
    line: EditorSourceLine,
    position: Int,
    gutterWidth: Dp,
    onEditorFocusChanged: () -> Unit,
    onEditorSelectionChanged: (EditorSourceSelection?) -> Unit,
    onSelectionContentRectChanged: (Int, LayoutCoordinates, TextLayoutResult?) -> Unit,
    onSelectionGestureStarted: () -> Unit,
    onSelectionGestureEnded: () -> Unit,
    onSelectionHandleDragStarted: () -> Unit,
    onSelectionHandleDragEnded: () -> Unit,
    editorCoordinates: LayoutCoordinates?,
    onLayoutMetricsChanged: (Int, LayoutCoordinates?, LayoutCoordinates?, TextLayoutResult?) -> Unit,
    contentLeftPx: Float
) {
    var value by remember(line.stableId) { mutableStateOf(TextFieldValue(line.text)) }
    val focusRequester = remember(line.stableId) { FocusRequester() }
    var textLayout by remember(line.stableId) { mutableStateOf<TextLayoutResult?>(null) }
    var textCoordinates by remember(line.stableId) { mutableStateOf<LayoutCoordinates?>(null) }
    var rowCoordinates by remember(line.stableId) { mutableStateOf<LayoutCoordinates?>(null) }
    var focused by remember(line.stableId) { mutableStateOf(false) }
    val bringIntoViewRequester = remember(line.stableId) { BringIntoViewRequester() }
    val bringIntoViewScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    DisposableEffect(line.stableId) {
        onDispose { state.clearLayoutMetrics(line.stableId) }
    }

    fun publishLayoutMetrics(
        text: LayoutCoordinates? = textCoordinates,
        layout: TextLayoutResult? = textLayout
    ) {
        onLayoutMetricsChanged(position, editorCoordinates, text, layout)
    }

    LaunchedEffect(editorCoordinates, textCoordinates, textLayout, position) {
        publishLayoutMetrics()
    }

    LaunchedEffect(state.contentVersion, line.stableId, line.text) {
        if (value.text != line.text) {
            value = TextFieldValue(line.text, TextRange(line.text.length))
        }
    }

    LaunchedEffect(state.documentSelection, textLayout, textCoordinates, position) {
        val coordinates = textCoordinates ?: return@LaunchedEffect
        if (state.documentSelection?.focusLine == position) {
            onSelectionContentRectChanged(position, coordinates, textLayout)
        }
    }

    // Every document selection is rendered by the source editor itself, including ranges that
    // stay on one physical row. Collapse any stale BasicTextField range so the platform's native
    // highlight/handles cannot race the custom range and expose a second selection state.
    LaunchedEffect(state.documentSelection, position) {
        val selection = state.documentSelection ?: return@LaunchedEffect
        if (value.selection.start == value.selection.end) return@LaunchedEffect
        // A BasicTextField can retain the range created by its own long-press detector. The
        // document selection is the sole source of truth, so collapse every child field
        // (including the focused row) to a caret and leave the range to custom highlights and
        // handles. This also prevents same-line native handles from taking over the next drag.
        val caret = if (selection.focusLine == position) {
            selection.focusOffset.coerceIn(0, value.text.length)
        } else {
            0
        }
        value = value.copy(selection = TextRange(caret))
    }

    val focusRequest = state.pendingFocus
    LaunchedEffect(focusRequest?.sequence, line.stableId, position) {
        val request = focusRequest ?: return@LaunchedEffect
        if (request.line != position) return@LaunchedEffect
        focusRequester.requestFocus()
        val start = request.selectionStart ?: request.column
        val end = request.selectionEnd ?: request.column
        value = value.copy(
            selection = TextRange(
                start.coerceIn(0, value.text.length),
                end.coerceIn(0, value.text.length)
            )
        )
        if (state.documentSelection == null) {
            bringIntoViewRequester.bringIntoView()
        }
        state.consumeFocusRequest(request.sequence)
    }

    // Keep the same colors and font metrics as the legacy SourceEditorView rows.
    val searchColor = colorResource(com.subtitleedit.R.color.inverse_primary)
    val currentSearchColor = colorResource(com.subtitleedit.R.color.secondary)
    val selectionColor = colorResource(com.subtitleedit.R.color.source_selection)
    val lineHighlights = remember(
        state.searchHighlights,
        state.documentSelection,
        position,
        line.text,
        searchColor,
        currentSearchColor,
        selectionColor
    ) {
        state.highlightsForLine(
            position = position,
            searchColor = searchColor,
            currentSearchColor = currentSearchColor,
            selectionColor = selectionColor
        )
    }
    val visualTransformation = remember(lineHighlights) {
        SourceLineHighlightTransformation(lineHighlights)
    }
    val rowColor = if (focused) {
        colorResource(com.subtitleedit.R.color.source_current_line)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val density = LocalDensity.current
    val selectionLocale = LocalLocale.current.platformLocale
    val textOriginPx = with(density) { gutterWidth.toPx() + 1.dp.toPx() + 12.dp.toPx() }
    val charWidthPx = with(density) { 14.sp.toPx() * 0.6f }
    val selectionEdgePx = with(density) { 48.dp.toPx() }
    val selectionScrollStepPx = with(density) { 24.dp.toPx() }
    val selectionTouchSlopPx = LocalViewConfiguration.current.touchSlop
    val longPressTimeoutMillis = LocalViewConfiguration.current.longPressTimeoutMillis.toLong()
    val markerTextSizePx = with(density) { 12.sp.toPx() }
    val markerPaint = remember(markerTextSizePx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66909090
            textSize = markerTextSizePx
        }
    }
    val markerWidthPx = remember(markerPaint) { markerPaint.measureText("↳") }
    val continuationIndent = with(density) { (markerWidthPx + 4.dp.toPx()).toSp() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .onSizeChanged { size ->
                state.updateMeasuredRowHeight(line.stableId, size.height)
            }
            .onGloballyPositioned { rowCoordinates = it }
            .background(rowColor)
            .pointerInput(line.stableId, value.text) {
                awaitEachGesture {
                    // BasicTextField has no public equivalent of the legacy
                    // SourceLineEditText.suppressNativeTouchSelection(). Consume the initial
                    // down in Initial so its own tap/long-press detector never starts. The
                    // parent LazyColumn has already observed the down in its outer Initial pass,
                    // so ordinary vertical scrolling remains available.
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    down.consume()
                    var rowPointerMoved = false
                    val longPressResult = withTimeoutOrNull(
                        longPressTimeoutMillis
                    ) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull false
                            if (!change.pressed) return@withTimeoutOrNull false
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (dx * dx + dy * dy > selectionTouchSlopPx * selectionTouchSlopPx) {
                                rowPointerMoved = true
                                return@withTimeoutOrNull false
                            }
                        }
                        false
                    }
                    // A timeout has no pointer event to consume. Seed immediately so a MOVE
                    // that follows the long-press can extend the range; the loop below consumes
                    // all subsequent events before BasicTextField can establish a native range.
                    val longPress = longPressResult == null
                    if (longPress && state.activeHandleDrag == null) {
                        // The row only seeds the selection. The editor-level pointer observer
                        // owns the rest of the gesture so recycling this LazyColumn item cannot
                        // cancel a cross-line drag or its edge scrolling.
                        // Pointer coordinates belong to the Row, while TextLayoutResult uses
                        // the BasicTextField's local content coordinates. The field can be
                        // vertically centered inside the row (especially for a one-line field
                        // in the 28dp minimum row), and wrapped text has multiple visual lines;
                        // passing Row Y directly shifts the long-press hit test to the wrong
                        // visual line. Convert through the actual layout coordinates so the
                        // selection seed matches the legacy EditText hit test.
                        val textPosition = runCatching {
                            val text = textCoordinates
                            val row = rowCoordinates
                            if (text != null && row != null) {
                                text.localPositionOf(row, down.position)
                            } else {
                                Offset(down.position.x - textOriginPx, down.position.y)
                            }
                        }.getOrElse {
                            Offset(down.position.x - textOriginPx, down.position.y)
                        }
                        val offset = textLayout?.getOffsetForPosition(textPosition)
                            ?: value.selection.end
                        val seededSelection = if (
                            down.position.x >= textOriginPx &&
                            state.getDocumentLineText(position).isNotEmpty()
                        ) {
                            state.selectWordAt(position, offset, selectionLocale, context)
                            state.documentSelection?.takeIf {
                                it.anchorLine == position || it.focusLine == position
                            }
                        } else {
                            null
                        }
                        if (seededSelection != null) {
                            onSelectionGestureStarted()
                            state.showSelectionActionMenu()
                            state.beginSelectionDrag(seededSelection, position, offset)
                        }

                        // Consume the rest of the stream even for an empty/gutter long press.
                        // This is the Compose equivalent of isLongClickable=false plus
                        // performLongClick=true on the legacy row editor.
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            change.consume()
                            if (!change.pressed) break
                        }
                    } else if (!longPress && !rowPointerMoved &&
                        state.activeHandleDrag == null &&
                        state.activeSelectionDrag == null &&
                        down.position.x >= textOriginPx
                    ) {
                        // The initial down was consumed to suppress BasicTextField's native
                        // selection detector, so reproduce its ordinary tap behavior explicitly:
                        // focus the row, place the caret using the real text layout, and update
                        // the editor's keyboard selection model.
                        val textPosition = runCatching {
                            val text = textCoordinates
                            val row = rowCoordinates
                            if (text != null && row != null) {
                                text.localPositionOf(row, down.position)
                            } else {
                                Offset(down.position.x - textOriginPx, down.position.y)
                            }
                        }.getOrElse {
                            Offset(down.position.x - textOriginPx, down.position.y)
                        }
                        val offset = textLayout?.getOffsetForPosition(textPosition)
                            ?.coerceIn(0, value.text.length)
                            ?: value.selection.end.coerceIn(0, value.text.length)
                        value = value.copy(selection = TextRange(offset))
                        state.localSelection(position, TextRange(offset))
                        onEditorSelectionChanged(state.documentSelection)
                        focusRequester.requestFocus()
                    }
                }
            },
        // Keep the gutter number anchored to the first visual line.  Centering the
        // row vertically moves the number into the middle of wrapped source text,
        // which no longer matches the legacy source editor.
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(gutterWidth)
                .fillMaxHeight()
                .background(colorResource(com.subtitleedit.R.color.source_gutter_background))
        ) {
            Text(
                text = (position + 1).toString(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 6.dp),
                color = colorResource(com.subtitleedit.R.color.source_gutter_text),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                textAlign = TextAlign.End,
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(colorResource(com.subtitleedit.R.color.source_gutter_divider))
        )
        Box(Modifier.weight(1f)) {
        BasicTextField(
            value = value,
            onValueChange = { newValue ->
                val documentSelection = state.documentSelection
                value = if (documentSelection != null) {
                    // Keep the native field collapsed while the document-level range is active;
                    // a text replacement still reaches updateLine below and will clear the
                    // document selection when the model is updated. This applies to same-line
                    // ranges too; otherwise BasicTextField can briefly restore its own native
                    // handles and race the editor-level handles.
                    newValue.copy(selection = TextRange(newValue.selection.end))
                } else {
                    newValue
                }
                if (documentSelection == null) {
                    state.updateFocusedColumn(position, newValue.selection.end)
                }
                val changed = state.updateLine(position, newValue)
                // Ordinary edits must keep BasicTextField's cursor/selection. Only a
                // structural edit replaces this row's text and needs a local reset.
                val rowText = state.getDocumentLineText(position)
                if (changed && rowText != newValue.text) {
                    value = TextFieldValue(
                        rowText,
                        TextRange(rowText.length)
                    )
                }
                state.showSelectionActionMenu()
                onEditorSelectionChanged(state.documentSelection)
            },
            enabled = state.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    focused = focusState.isFocused
                    if (focusState.isFocused) {
                        state.updateFocusedLine(position)
                        onEditorFocusChanged()
                        if (state.documentSelection == null) {
                            bringIntoViewScope.launch {
                                bringIntoViewRequester.bringIntoView()
                                // The legacy SourceEditorView explicitly showed the IME after
                                // programmatic focus (for example, a search result jump). BasicTextField
                                // does not reliably do that when focus is requested by a LaunchedEffect.
                                keyboardController?.show()
                            }
                        }
                    }
                }
                .bringIntoViewRequester(bringIntoViewRequester)
                .onGloballyPositioned {
                    textCoordinates = it
                    onSelectionContentRectChanged(position, it, textLayout)
                    publishLayoutMetrics(text = it)
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !state.enabled) return@onPreviewKeyEvent false
                    val selection = value.selection
                    val collapsed = selection.start == selection.end
                    when (event.key) {
                        Key.Enter -> state.splitLineAt(position, selection.end.coerceAtLeast(0))
                        Key.Backspace -> collapsed && selection.start == 0 && state.mergeWithPrevious(position)
                        Key.Delete -> collapsed && selection.end == value.text.length && state.mergeWithNext(position)
                        Key.DirectionUp, Key.DirectionDown -> {
                            val direction = if (event.key == Key.DirectionUp) -1 else 1
                            val visualLine = textLayout?.getLineForOffset(selection.end.coerceIn(0, value.text.length)) ?: 0
                            val lastVisualLine = ((textLayout?.lineCount ?: 1) - 1)
                            if (visualLine != if (direction < 0) 0 else lastVisualLine) {
                                false
                            } else if (event.isShiftPressed) {
                                state.extendCursorVertically(
                                    position,
                                    direction,
                                    selection.start,
                                    selection.end
                                )
                            } else if (collapsed) {
                                state.moveCursorVertically(position, direction, selection.end)
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                },
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                textIndent = TextIndent(firstLine = 0.sp, restLine = continuationIndent),
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.None,
                autoCorrectEnabled = false
            ),
             onTextLayout = {
                 textLayout = it
                 publishLayoutMetrics(layout = it)
             }
        )
        Canvas(Modifier.fillMaxSize()) {
            val layout = textLayout ?: return@Canvas
            if (layout.lineCount <= 1) return@Canvas
            val left = (12.dp.toPx() - markerWidthPx) * 0.5f
            val right = size.width - 12.dp.toPx() - markerWidthPx * 0.5f
            val canvas = drawContext.canvas.nativeCanvas
            for (visualLine in 0 until layout.lineCount) {
                val baseline = layout.getLineBaseline(visualLine)
                if (visualLine > 0) canvas.drawText("↳", left, baseline, markerPaint)
                if (visualLine < layout.lineCount - 1) canvas.drawText("↲", right, baseline, markerPaint)
            }
        }

        // Native BasicTextField handles are intentionally transparent so they cannot compete
        // with a document range spanning rows. Draw both endpoints here for same-line and
        // cross-line ranges alike; this keeps handles visible after a long press and gives them
        // identical drag semantics in either case.
        // Keep the two endpoint handles rendered while an active drag temporarily collapses the
        // range to zero length. The state clears that transient caret when the pointer is lifted.
        val documentSelection = state.documentSelection?.takeIf {
            state.hasDocumentSelection() || state.activeHandleDrag != null
        }
        documentSelection?.let { selection ->
            if (selection.anchorLine == position) {
                SourceDocumentHandle(
                    state = state,
                    position = position,
                    offset = selection.anchorOffset,
                    anchor = true,
                    textLayout = textLayout,
                    onDragStarted = onSelectionHandleDragStarted,
                    onDragEnded = onSelectionHandleDragEnded,
                    contentLeftPx = contentLeftPx
                )
            }
            if (selection.focusLine == position) {
                SourceDocumentHandle(
                    state = state,
                    position = position,
                    offset = selection.focusOffset,
                    anchor = false,
                    textLayout = textLayout,
                    onDragStarted = onSelectionHandleDragStarted,
                    onDragEnded = onSelectionHandleDragEnded,
                    contentLeftPx = contentLeftPx
                )
            }
        }
        }
    }
}

private fun addSourceSelectionMenu(context: Context, menu: Menu) {
    menu.clear()
    menu.add(Menu.NONE, SOURCE_SELECTION_MENU_SELECT_ALL, 0, com.subtitleedit.R.string.source_selection_select_all)
        .apply { configureSourceSelectionItem(com.subtitleedit.R.drawable.ic_select_all) }
    menu.add(Menu.NONE, SOURCE_SELECTION_MENU_CUT, 1, com.subtitleedit.R.string.source_selection_cut)
        .apply { configureSourceSelectionItem(com.subtitleedit.R.drawable.ic_content_cut) }
    menu.add(Menu.NONE, SOURCE_SELECTION_MENU_COPY, 2, com.subtitleedit.R.string.source_selection_copy)
        .apply { configureSourceSelectionItem(com.subtitleedit.R.drawable.ic_content_copy) }
    menu.add(Menu.NONE, SOURCE_SELECTION_MENU_PASTE, 3, com.subtitleedit.R.string.source_selection_paste)
        .apply {
            configureSourceSelectionItem(com.subtitleedit.R.drawable.ic_content_paste)
            isEnabled = clipboardHasText(context)
        }
}

private fun MenuItem.configureSourceSelectionItem(iconRes: Int) {
    val title = title.toString()
    setIcon(iconRes)
    contentDescription = title
    tooltipText = title
    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
}

private fun clipboardHasText(context: Context): Boolean =
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
        ?.primaryClip?.itemCount?.let { it > 0 } == true

/**
 * A document-level selection handle. Compose's native handle pair belongs to a single field and
 * cannot represent a document range across physical rows. The source editor also uses these
 * handles for same-line ranges because native handles are hidden to prevent duplicate controls.
 */
@Composable
private fun SourceDocumentHandle(
    state: EditorSourceEditorState,
    position: Int,
    offset: Int,
    anchor: Boolean,
    textLayout: TextLayoutResult?,
    onDragStarted: () -> Unit,
    onDragEnded: () -> Unit,
    contentLeftPx: Float
) {
    val density = LocalDensity.current
    val handleSize = with(density) { 22.dp.toPx() }
    val textPadding = with(density) { 12.dp.toPx() }
    val charWidth = with(density) { 14.sp.toPx() * 0.6f }
    val layout = textLayout ?: return
    val safeOffset = offset.coerceIn(0, layout.layoutInput.text.length)
    val visualLine = layout.getLineForOffset(safeOffset)
    val x = textPadding + layout.getCursorRect(safeOffset).left
    val y = layout.getLineBottom(visualLine).coerceAtLeast(handleSize)
    val handleColor = colorResource(com.subtitleedit.R.color.secondary)

    Canvas(
        modifier = Modifier
            .offset {
                androidx.compose.ui.unit.IntOffset(
                    (x - handleSize / 2f).roundToInt(),
                    (y - handleSize).roundToInt()
                )
            }
            .width(22.dp)
            .height(22.dp)
            .pointerInput(state, position, anchor) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val activePointerId = down.id
                    state.beginHandleDrag(
                        sourcePosition = position,
                        anchor = anchor,
                        contentLeftPx = contentLeftPx,
                        textOriginPx = textPadding,
                        charWidthPx = charWidth
                    )
                    onDragStarted()
                    var released = false
                    try {
                        while (true) {
                            // MOVE, hit testing, and edge scrolling are owned by the editor
                            // root. Keeping those calculations here as well processes the same
                            // pointer twice and makes recycled rows race the root observer.
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == activePointerId }
                                ?: continue
                            if (!change.pressed) {
                                released = true
                                break
                            }
                        }
                    } finally {
                        // A recomposition can remove this row's handle while the pointer is
                        // still down. The editor-level pointerInput owns that session and will
                        // finish it on the actual up/cancel event.
                        if (released && state.activeHandleDrag?.let {
                                it.sourcePosition == position && it.anchor == anchor
                            } == true
                        ) {
                            state.endHandleDrag()
                            onDragEnded()
                        }
                    }
                }
            }
    ) {
        // Keep the marker inside the row's layout bounds so it remains draggable while the
        // LazyColumn recycles adjacent rows. The short stem and round tip match Android's
        // selection affordance without taking a dependency on a legacy View drawable.
        val centerX = size.width / 2f
        val stemWidth = size.width * 0.12f
        drawRoundRect(
            color = handleColor,
            topLeft = Offset(centerX - stemWidth, 1.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(
                stemWidth * 2f,
                size.height * 0.55f
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(stemWidth, stemWidth)
        )
        drawCircle(
            color = handleColor,
            radius = size.width * 0.34f,
            center = Offset(centerX, size.height * 0.72f)
        )
    }
}

private class SourceLineHighlightTransformation(
    private val highlights: List<LocalHighlight>
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (highlights.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val builder = androidx.compose.ui.text.buildAnnotatedString {
            append(text)
            highlights.forEach { highlight ->
                val start = highlight.start.coerceIn(0, text.length)
                val end = highlight.end.coerceIn(start, text.length)
                if (start < end) {
                    addStyle(
                        androidx.compose.ui.text.SpanStyle(background = highlight.color),
                        start,
                        end
                    )
                }
            }
        }
        return TransformedText(builder, OffsetMapping.Identity)
    }
}

private data class LocalHighlight(val start: Int, val end: Int, val color: androidx.compose.ui.graphics.Color)

private fun EditorSourceEditorState.highlightsForLine(
    position: Int,
    searchColor: androidx.compose.ui.graphics.Color,
    currentSearchColor: androidx.compose.ui.graphics.Color,
    selectionColor: androidx.compose.ui.graphics.Color
): List<LocalHighlight> {
    val line = lines.getOrNull(position) ?: return emptyList()
    val startOffset = lines.take(position).sumOf { it.serializedLength }
    val endOffset = startOffset + line.text.length
    val result = mutableListOf<LocalHighlight>()
    searchHighlights.forEach { highlight ->
        if (highlight.end <= startOffset || highlight.start >= endOffset) return@forEach
        result += LocalHighlight(
            start = (highlight.start - startOffset).coerceAtLeast(0),
            end = (highlight.end - startOffset).coerceAtMost(line.text.length),
            color = if (highlight.current) currentSearchColor else searchColor
        )
    }
    val selection = documentSelection
    if (selection != null) {
        val anchor = lines.take(selection.anchorLine).sumOf { it.serializedLength } + selection.anchorOffset
        val focus = lines.take(selection.focusLine).sumOf { it.serializedLength } + selection.focusOffset
        val rangeStart = minOf(anchor, focus)
        val rangeEnd = maxOf(anchor, focus)
        if (rangeStart < endOffset && rangeEnd > startOffset) {
            result += LocalHighlight(
                start = (rangeStart - startOffset).coerceAtLeast(0),
                end = (rangeEnd - startOffset).coerceAtMost(line.text.length),
                color = selectionColor
            )
        }
    }
    return result
}
