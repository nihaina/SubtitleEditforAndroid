package com.subtitleedit.util

import com.subtitleedit.R

internal object MainNavigationPolicy {
    fun titleRes(itemId: Int): Int = when (itemId) {
        R.id.nav_favorites -> R.string.nav_favorites
        R.id.nav_drafts -> R.string.drafts
        R.id.nav_tools -> R.string.menu_main_title_01
        else -> R.string.menu_main_title_02
    }
}
