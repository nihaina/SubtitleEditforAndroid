package com.subtitleedit.util

import java.util.IdentityHashMap

/** Keeps a search selection stable while the result collection changes. */
object SearchResultRetention {
    fun <T : Any> preferredIndex(
        previousResults: List<T>,
        previousIndex: Int,
        newResults: List<T>
    ): Int? {
        if (newResults.isEmpty()) return null

        val previousCurrent = previousResults.getOrNull(previousIndex) ?: return 0
        val newIndices = IdentityHashMap<T, Int>()
        newResults.forEachIndexed { index, result ->
            if (!newIndices.containsKey(result)) {
                newIndices[result] = index
            }
        }

        newIndices[previousCurrent]?.let { return it }

        for (index in previousIndex - 1 downTo 0) {
            newIndices[previousResults[index]]?.let { return it }
        }
        for (index in previousIndex + 1..previousResults.lastIndex) {
            newIndices[previousResults[index]]?.let { return it }
        }

        // The document may have been rebuilt with all-new objects. In that case,
        // keep the closest preceding ordinal because the old current result is gone.
        return (previousIndex - 1).coerceIn(0, newResults.lastIndex)
    }
}
