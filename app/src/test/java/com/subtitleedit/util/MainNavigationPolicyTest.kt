package com.subtitleedit.util

import com.subtitleedit.R
import org.junit.Assert.*
import org.junit.Test

class MainNavigationPolicyTest {
    @Test fun restoredPlayerPagesBecomeTheirOriginalTabsWhenModeIsDisabled() {
        assertEquals(R.id.nav_favorites, MainNavigationPolicy.pageForMode(R.id.nav_library, false))
        assertEquals(R.id.nav_drafts, MainNavigationPolicy.pageForMode(R.id.nav_stats, false))
    }

    @Test fun ordinaryPagesAndPlayerModeDestinationsKeepTheirIdentity() {
        listOf(R.id.nav_directory, R.id.nav_favorites, R.id.nav_drafts, R.id.nav_tools, R.id.nav_settings).forEach {
            assertEquals(it, MainNavigationPolicy.pageForMode(it, false))
            assertEquals(it, MainNavigationPolicy.pageForMode(it, true))
        }
        assertEquals(R.id.nav_library, MainNavigationPolicy.pageForMode(R.id.nav_library, true))
        assertEquals(R.id.nav_stats, MainNavigationPolicy.pageForMode(R.id.nav_stats, true))
    }

    @Test fun originalTabsOnlyHaveSecondaryPageBackBehaviorInPlayerMode() {
        listOf(R.id.nav_favorites, R.id.nav_drafts).forEach {
            assertFalse(MainNavigationPolicy.isAuxiliaryPage(it, false))
            assertTrue(MainNavigationPolicy.isAuxiliaryPage(it, true))
        }
        listOf(R.id.nav_directory, R.id.nav_library, R.id.nav_stats, R.id.nav_tools, R.id.nav_settings).forEach {
            assertFalse(MainNavigationPolicy.isAuxiliaryPage(it, true))
        }
    }
}
