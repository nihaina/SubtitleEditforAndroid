package com.subtitleedit.util

internal object SelectionRangePolicy {
    fun contiguousRange(selectedPositions: Collection<Int>): IntRange? {
        if (selectedPositions.size < 2) return null
        val sorted = selectedPositions.sorted()
        return sorted.first()..sorted.last()
    }
}
