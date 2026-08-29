package com.subtitleedit.view

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.LinearLayoutManager
import com.subtitleedit.R

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

    private val lineAdapter = SourceLineAdapter(
        lines = lines,
        onTextChanged = ::onLineTextChanged,
        onSplitLine = ::splitLineAt,
        onMergePrevious = ::mergeWithPrevious,
        onMergeNext = ::mergeWithNext,
        onMoveVertical = ::moveCursorVertically,
        highlightsForLine = ::highlightsForLine
    )

    init {
        setBackgroundColor(context.getColor(R.color.surface))
        layoutManager = lineLayoutManager
        adapter = lineAdapter
        setHasFixedSize(true)
        setItemViewCacheSize(12)
        clipToPadding = false
        setPadding(
            (resources.displayMetrics.density * 8f).toInt(),
            (resources.displayMetrics.density * 8f).toInt(),
            (resources.displayMetrics.density * 8f).toInt(),
            (resources.displayMetrics.density * 8f).toInt()
        )
        replaceLines("")
    }

    fun addOnDocumentChangedListener(listener: () -> Unit) {
        documentChangedListeners += listener
    }

    fun addOnDocumentChangeListener(listener: (DocumentChange) -> Unit) {
        documentChangeListeners += listener
    }

    fun setDocumentText(value: String, preserveScroll: Boolean = false) {
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

    private fun replaceLines(value: String) {
        val parsed = parsePhysicalLines(value)
        lines.clear()
        parsed.forEach { parsedLine ->
            lines += SourceLineBlock(nextLineId++, parsedLine.first, parsedLine.second)
        }
        preferredLineEnding = parsed.firstOrNull { it.second.isNotEmpty() }?.second ?: "\n"
        offsets.reset(lines)
    }

    private fun onLineTextChanged(position: Int, rawValue: String, cursor: Int) {
        val line = lines.getOrNull(position) ?: return
        val normalized = normalizeEditorText(rawValue)
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

    private fun requestFocus(position: Int, column: Int) {
        scrollToPosition(position)
        post { lineAdapter.requestLineFocus(position, column) }
    }

    private fun highlightsForLine(position: Int): List<SourceLineHighlight> {
        val line = lines.getOrNull(position) ?: return emptyList()
        if (line.text.isEmpty() || highlights.isEmpty()) return emptyList()
        val lineStart = offsets.start(position)
        val lineEnd = lineStart + line.text.length
        var low = 0
        var high = highlights.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (highlights[middle].end <= lineStart) low = middle + 1 else high = middle
        }
        val result = mutableListOf<SourceLineHighlight>()
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
        return result
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
