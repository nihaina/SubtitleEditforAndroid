package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchResultRetentionTest {
    private data class Result(val label: String)

    @Test
    fun preferredIndex_keepsCurrentResultWhenEarlierResultsChange() {
        val first = Result("first")
        val current = Result("current")
        val last = Result("last")

        assertEquals(
            0,
            SearchResultRetention.preferredIndex(
                previousResults = listOf(first, current, last),
                previousIndex = 1,
                newResults = listOf(current, last)
            )
        )
    }

    @Test
    fun preferredIndex_distinguishesResultsWithEqualContentByIdentity() {
        val previous = Result("same")
        val current = Result("same")

        assertEquals(
            0,
            SearchResultRetention.preferredIndex(
                previousResults = listOf(previous, current),
                previousIndex = 1,
                newResults = listOf(current, previous)
            )
        )
    }

    @Test
    fun preferredIndex_usesPreviousResultWhenCurrentDisappears() {
        val results = List(80) { Result("result-$it") }
        val newResults = results.filterIndexed { index, _ -> index != 9 }

        assertEquals(
            8,
            SearchResultRetention.preferredIndex(
                previousResults = results,
                previousIndex = 9,
                newResults = newResults
            )
        )
    }

    @Test
    fun preferredIndex_walksBackwardAcrossMultipleRemovedResults() {
        val first = Result("first")
        val previous = Result("previous")
        val current = Result("current")
        val next = Result("next")

        assertEquals(
            0,
            SearchResultRetention.preferredIndex(
                previousResults = listOf(first, previous, current, next),
                previousIndex = 2,
                newResults = listOf(first, next)
            )
        )
    }

    @Test
    fun preferredIndex_usesNextResultWhenNoPreviousResultSurvives() {
        val current = Result("current")
        val next = Result("next")

        assertEquals(
            0,
            SearchResultRetention.preferredIndex(
                previousResults = listOf(current, next),
                previousIndex = 0,
                newResults = listOf(next)
            )
        )
    }

    @Test
    fun preferredIndex_usesPreviousOrdinalWhenDocumentObjectsAreRebuilt() {
        val oldResults = List(5) { Result("old-$it") }
        val newResults = List(4) { Result("new-$it") }

        assertEquals(
            2,
            SearchResultRetention.preferredIndex(
                previousResults = oldResults,
                previousIndex = 3,
                newResults = newResults
            )
        )
    }

    @Test
    fun preferredIndex_returnsNullForNoResults() {
        assertNull(
            SearchResultRetention.preferredIndex(
                previousResults = listOf(Result("current")),
                previousIndex = 0,
                newResults = emptyList()
            )
        )
    }
}
