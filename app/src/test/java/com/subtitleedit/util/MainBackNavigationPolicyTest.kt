package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MainBackNavigationPolicyTest {
    @Test
    fun nonDirectoryPageDelegatesToItsFragment() {
        assertEquals(
            MainBackNavigationPolicy.Decision.DELEGATE_TO_TOP_LEVEL,
            MainBackNavigationPolicy.decide(false, false, true, true)
        )
    }

    @Test
    fun destinationSelectionTakesPriorityOverSelectedFiles() {
        assertEquals(
            MainBackNavigationPolicy.Decision.NAVIGATE_DESTINATION,
            MainBackNavigationPolicy.decide(true, true, true, true)
        )
    }

    @Test
    fun selectionClosesBeforeDirectoryHistory() {
        assertEquals(
            MainBackNavigationPolicy.Decision.EXIT_SELECTION,
            MainBackNavigationPolicy.decide(true, false, true, true)
        )
    }

    @Test
    fun cleanRootFinishes() {
        assertEquals(
            MainBackNavigationPolicy.Decision.FINISH,
            MainBackNavigationPolicy.decide(true, false, false, false)
        )
    }
}
