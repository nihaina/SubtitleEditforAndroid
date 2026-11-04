package com.subtitleedit.view

import android.util.Log
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The touch/draw path only reads heights; full-document text layout belongs to the worker. */
internal class SourceScrollHeightIndex(
    private val parent: RecyclerView,
    private val lines: List<SourceLineBlock>
) {
    private data class VisibleHeight(val text: String, val height: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null
    private var generation = 0L
    private var dirty = true
    private var detached = false
    private var measuredWidth = -1
    private var measuredTextWidth = -1
    private var table = SourceScrollHeightTable(0, 1)
    private var dragTable: SourceScrollHeightTable? = null
    private var pendingTable: SourceScrollHeightTable? = null
    private var ready = false

    val totalHeight: Long
        get() = (dragTable ?: table).totalHeight

    fun invalidate() {
        generation++
        buildJob?.cancel()
        buildJob = null
        pendingTable = null
        dragTable = null
        ready = false
        dirty = true
    }

    fun attach() {
        detached = false
    }

    fun detach() {
        detached = true
        invalidate()
        dragTable = null
    }

    fun setDragging(dragging: Boolean) {
        if (dragging) {
            dragTable = table
        } else {
            dragTable = null
            pendingTable?.let { table = it }
            pendingTable = null
            parent.invalidate()
        }
    }

    fun ensure(width: Int) {
        if (width <= 0 || detached) return
        // Only inspect rows RecyclerView has already rendered. A long visible physical line is
        // immediately scrollable inside the row, even before the background index is ready.
        val firstRow = (0 until parent.childCount).asSequence().map(parent::getChildAt)
            .firstOrNull { it.width == width && !it.isLayoutRequested } ?: return
        val editor = firstRow.findViewById<SourceLineEditText>(R.id.etSourceLine) ?: return
        val textWidth = editor.layout?.width ?: return
        if (width != measuredWidth || textWidth != measuredTextWidth) {
            invalidate()
            measuredWidth = width
            measuredTextWidth = textWidth
        }
        val visible = HashMap<Int, VisibleHeight>()
        for (index in 0 until parent.childCount) {
            val row = parent.getChildAt(index)
            val position = parent.getChildAdapterPosition(row)
            if (position !in lines.indices || row.width != width || row.isLayoutRequested) continue
            visible[position] = VisibleHeight(lines[position].text,
                parent.layoutManager!!.getDecoratedMeasuredHeight(row).coerceAtLeast(1))
        }
        if (dirty || table.count != lines.size) {
            val measurer = SourceScrollTextMeasurer(editor, firstRow.minimumHeight)
            table = SourceScrollHeightTable(lines.size, measurer.defaultHeight)
            dirty = false
            startBuild(measurer, visible)
        }
        if (dragTable == null) {
            visible.forEach { (position, row) -> table.setMeasuredHeight(position, row.height) }
        }
    }

    private fun startBuild(measurer: SourceScrollTextMeasurer, visible: Map<Int, VisibleHeight>) {
        val expectedGeneration = generation
        val count = lines.size
        buildJob = scope.launch {
            try {
                // Coalesce rapid edits and let the editor's first frame/gesture finish first.
                delay(16)
                val texts = withContext(measurementDispatcher) { arrayOfNulls<String>(count) }
                var copied = 0
                while (copied < count) {
                    currentCoroutineContext().ensureActive()
                    // Immutable String references only; never read the mutable line list on the
                    // worker. Snapshot in small UI batches instead of a full-document UI scan.
                    val end = (copied + 256).coerceAtMost(count)
                    while (copied < end) {
                        texts[copied] = lines[copied].text
                        copied++
                    }
                    if (copied < count) delay(1)
                }
                val starts = withContext(measurementDispatcher) {
                    val workContext = currentCoroutineContext()
                    val result = LongArray(count + 1)
                    for (position in 0 until count) {
                        workContext.ensureActive()
                        val text = texts[position]!!
                        val measured = visible[position]?.takeIf { it.text === text }?.height
                            ?: measurer.measure(text) { workContext.ensureActive() }
                        result[position + 1] = result[position] + measured
                        texts[position] = null
                    }
                    result
                }
                if (generation != expectedGeneration || detached) return@launch
                val completed = SourceScrollHeightTable.exact(starts)
                if (dragTable == null) table = completed else pendingTable = completed
                ready = true
                parent.invalidate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                // Keep the lightweight height estimate usable if a device text shaper fails.
                Log.w("SourceScrollHeightIndex", "Unable to build source scrollbar heights", failure)
            }
        }
    }

    fun start(position: Int): Long = (dragTable ?: table).start(position)

    fun positionAt(height: Long): Int = (dragTable ?: table).positionAt(height)

    private companion object {
        // Cancelled builds stop at each row/chunk. Only one text-layout worker runs at a time.
        val measurementDispatcher = Dispatchers.Default.limitedParallelism(1)
    }
}
