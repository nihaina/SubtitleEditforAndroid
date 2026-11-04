package com.subtitleedit.view

/**
 * Scrollbar height lookup with either a uniform estimate or a compact exact prefix array.
 * Visible rows can correct either representation without rebuilding the document-wide array.
 */
internal class SourceScrollHeightTable private constructor(
    val count: Int,
    private val defaultHeight: Int,
    private val starts: LongArray?
) {
    constructor(count: Int, defaultHeight: Int) : this(count, defaultHeight, null)

    init {
        require(count >= 0)
        require(defaultHeight > 0)
    }

    private val corrections = HashMap<Int, Long>()
    // Sparse Fenwick nodes keep lookups logarithmic even after many rows have been visited.
    // A uniform initial estimate still allocates no array proportional to the file's size.
    private val correctionSums = HashMap<Int, Long>()
    private var totalCorrection = 0L

    val totalHeight: Long
        get() = baseStart(count) + totalCorrection

    fun start(position: Int): Long {
        val safePosition = position.coerceIn(0, count)
        var height = baseStart(safePosition)
        var node = safePosition
        while (node > 0) {
            height += correctionSums[node] ?: 0L
            node -= node and -node
        }
        return height
    }

    /** Returns the physical row containing this cumulative pixel offset. */
    fun positionAt(height: Long): Int {
        if (count == 0) return 0
        val target = height.coerceIn(0L, totalHeight - 1L)
        var low = 0
        var high = count
        while (low + 1 < high) {
            val middle = low + (high - low) / 2
            if (start(middle) <= target) low = middle else high = middle
        }
        return low
    }

    fun setMeasuredHeight(position: Int, height: Int) {
        // A layout callback can refer to a row removed by a newer document generation.
        if (position !in 0 until count) return
        val baseHeight = baseStart(position + 1) - baseStart(position)
        val correction = height.coerceAtLeast(1).toLong() - baseHeight
        val previous = if (correction == 0L) {
            corrections.remove(position)
        } else {
            corrections.put(position, correction)
        } ?: 0L
        val delta = correction - previous
        if (delta == 0L) return
        totalCorrection += delta
        var node = position + 1
        while (node <= count) {
            val sum = (correctionSums[node] ?: 0L) + delta
            if (sum == 0L) correctionSums.remove(node) else correctionSums[node] = sum
            val step = node and -node
            if (node > count - step) break
            node += step
        }
    }

    private fun baseStart(position: Int): Long =
        starts?.get(position) ?: position.toLong() * defaultHeight

    companion object {
        /**
         * Takes ownership of an already computed prefix array. Callers supply strictly increasing
         * row starts beginning at zero and must not mutate the array after publication. Only the
         * header is checked here so publishing a background result never scans the whole file.
         */
        fun exact(starts: LongArray): SourceScrollHeightTable {
            require(starts.isNotEmpty() && starts[0] == 0L)
            require(starts.size == 1 || starts.last() > 0L)
            return SourceScrollHeightTable(starts.size - 1, 1, starts)
        }
    }
}
