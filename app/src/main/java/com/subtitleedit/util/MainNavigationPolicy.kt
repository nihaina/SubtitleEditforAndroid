package com.subtitleedit.util

import com.subtitleedit.R

internal object MainNavigationPolicy {
    fun pageForMode(itemId: Int, playerModeEnabled: Boolean): Int = when {
        playerModeEnabled -> itemId
        itemId == R.id.nav_library -> R.id.nav_favorites
        itemId == R.id.nav_stats -> R.id.nav_drafts
        else -> itemId
    }

    fun isAuxiliaryPage(itemId: Int, playerModeEnabled: Boolean): Boolean =
        playerModeEnabled && (itemId == R.id.nav_favorites || itemId == R.id.nav_drafts)

    fun titleRes(itemId: Int): Int = when (itemId) {
        R.id.nav_library -> R.string.nav_library
        R.id.nav_stats -> R.string.nav_stats
        R.id.nav_favorites -> R.string.nav_favorites
        R.id.nav_drafts -> R.string.drafts
        R.id.nav_tools -> R.string.menu_main_title_01
        else -> R.string.menu_main_title_02
    }
}
