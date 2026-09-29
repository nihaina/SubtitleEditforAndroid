package com.subtitleedit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

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

/**
 * State for the source editor. It deliberately has no Activity, legacy widget, or XML dependency,
 * so controllers can use the same document API from a Compose screen and tests.
 */
internal class EditorSourceEditorState(initialText: String = "") {
    private var nextLineId = 1L
    private var preferredLineEnding = "\n"
    private var focusSequence = 0L
    private var scrollSequence = 0L

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

    var contentVersion by mutableLongStateOf(0L)
        private set

    /** Approximate pixel scroll position, updated by [EditorSourceEditor]. */
    var scrollPosition by mutableIntStateOf(0)
        private set

    var pendingFocus by mutableStateOf<FocusRequest?>(null)
        private set

    var pendingScroll by mutableStateOf<ScrollRequest?>(null)
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
        documentSelection = null
        lines = parsePhysicalLines(value)
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
            if (targetLine == null && safe in cursor..end) targetLine = index
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

    fun requestLineFocus(position: Int, column: Int) {
        val line = position.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        focusSequence++
        pendingFocus = FocusRequest(
            sequence = focusSequence,
            line = line,
            column = column.coerceIn(0, lines.getOrNull(line)?.text?.length ?: 0)
        )
        requestScroll(line, 0, center = false)
    }

    fun requestLineSelection(position: Int, start: Int, end: Int) {
        val line = position.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val length = lines.getOrNull(line)?.text?.length ?: 0
        focusSequence++
        pendingFocus = FocusRequest(
            sequence = focusSequence,
            line = line,
            column = end.coerceIn(0, length),
            selectionStart = start.coerceIn(0, length),
            selectionEnd = end.coerceIn(0, length)
        )
        requestScroll(line, 0, center = false)
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

    fun updateScrollPosition(firstVisibleItem: Int, firstVisibleOffset: Int, rowHeightPx: Int) {
        scrollPosition = (firstVisibleItem.coerceAtLeast(0) * rowHeightPx +
            firstVisibleOffset.coerceAtLeast(0)).coerceAtLeast(0)
    }

    internal fun updateFocusedLine(position: Int) {
        focusedLine = position
    }

    internal fun updateDocumentSelection(selection: EditorSourceSelection?) {
        documentSelection = selection
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
                updateLocalSelection(position, value.selection)
                return false
            }
            lines = lines.toMutableList().also { mutable ->
                mutable[position] = line.copy(text = normalized)
            }
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
        requestLineFocus(position - 1, previous.text.length)
        notifyDocumentChanged(
            EditorSourceDocumentChange(
                startLine = position - 1,
                oldLineCount = 2,
                newLineCount = 1,
                startOffset = startOffset,
                oldEndOffset = startOffset + previous.serializedLength + current.serializedLength,
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
        requestLineFocus(position, current.text.length)
        notifyDocumentChanged(
            EditorSourceDocumentChange(
                startLine = position,
                oldLineCount = 2,
                newLineCount = 1,
                startOffset = startOffset,
                oldEndOffset = startOffset + current.serializedLength + next.serializedLength,
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
        documentSelection = EditorSourceSelection(anchorLine, anchorOffset, target, targetOffset)
        requestLineSelection(target, targetOffset, targetOffset)
        return true
    }

    internal fun localSelection(position: Int, selection: TextRange) {
        val safeStart = selection.start.coerceAtLeast(0)
        val safeEnd = selection.end.coerceAtLeast(0)
        documentSelection = if (safeStart == safeEnd) {
            null
        } else {
            EditorSourceSelection(position, safeStart, position, safeEnd)
        }
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
        val rowHeight = 28
        val line = (pixel.coerceAtLeast(0) / rowHeight).coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        requestScroll(line, -(pixel.coerceAtLeast(0) % rowHeight), center = false)
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
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
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
        val offset = if (request.center && viewport > 0) -(viewport / 3) else request.offset
        state.lazyListState.scrollToItem(target, offset)
        state.consumeScrollRequest(request.sequence)
    }

    val gutterWidth = max(24, state.lines.size.coerceAtLeast(1).toString().length * 8 + 12).dp
    LazyColumn(
        state = state.lazyListState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
    ) {
        itemsIndexed(state.lines, key = { _, line -> line.stableId }) { position, line ->
            EditorSourceLineRow(
                state = state,
                line = line,
                position = position,
                gutterWidth = gutterWidth,
                onEditorFocusChanged = onEditorFocusChanged,
                onEditorSelectionChanged = onEditorSelectionChanged
            )
        }
    }
}

@Composable
private fun EditorSourceLineRow(
    state: EditorSourceEditorState,
    line: EditorSourceLine,
    position: Int,
    gutterWidth: Dp,
    onEditorFocusChanged: () -> Unit,
    onEditorSelectionChanged: (EditorSourceSelection?) -> Unit
) {
    var value by remember(line.stableId) { mutableStateOf(TextFieldValue(line.text)) }
    val focusRequester = remember(line.stableId) { FocusRequester() }
    var textLayout by remember(line.stableId) { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember(line.stableId) { mutableStateOf(false) }

    LaunchedEffect(state.contentVersion, line.stableId, line.text) {
        if (value.text != line.text) {
            value = TextFieldValue(line.text, TextRange(line.text.length))
        }
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
        state.consumeFocusRequest(request.sequence)
    }

    val searchColor = MaterialTheme.colorScheme.inversePrimary
    val currentSearchColor = MaterialTheme.colorScheme.secondaryContainer
    val selectionColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.42f)
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
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.38f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .background(rowColor)
    ) {
        Box(
            modifier = Modifier
                .width(gutterWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                text = (position + 1).toString(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        BasicTextField(
            value = value,
            onValueChange = { newValue ->
                value = newValue
                val split = state.updateLine(position, newValue)
                if (split) {
                    value = TextFieldValue(
                        state.getDocumentLineText(position),
                        TextRange(state.getDocumentLineText(position).length)
                    )
                }
                onEditorSelectionChanged(state.documentSelection)
            },
            enabled = state.enabled,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    focused = focusState.isFocused
                    if (focusState.isFocused) {
                        state.updateFocusedLine(position)
                        onEditorFocusChanged()
                    }
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
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.None
            ),
            onTextLayout = { textLayout = it }
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
