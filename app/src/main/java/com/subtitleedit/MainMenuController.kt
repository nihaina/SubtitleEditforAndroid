package com.subtitleedit

import android.view.Menu
import android.view.MenuItem

/** Builds and routes the file-browser menu without owning file operations. */
internal class MainMenuController(
    private val configureSearch: (MenuItem) -> Unit,
    private val selectAll: () -> Unit,
    private val selectRange: () -> Unit,
    private val showCreate: () -> Unit,
    private val showMore: () -> Unit
) {
    private companion object {
        const val MENU_SELECT_ALL = 0x10001
        const val MENU_SELECT_RANGE = 0x10002
        const val MENU_SEARCH = 0x10003
        const val MENU_CREATE = 0x10004
        const val MENU_MORE = 0x10005
    }

    fun prepare(menu: Menu, isDirectorySelected: Boolean, hasSelection: Boolean, hasPendingOperation: Boolean): Boolean {
        menu.clear()
        if (!isDirectorySelected) return true
        if (hasSelection || hasPendingOperation) {
            if (!hasPendingOperation) {
                menu.add(Menu.NONE, MENU_SELECT_ALL, 0, "全选")
                    .setIcon(R.drawable.ic_select_all)
                    .setContentDescription("全选")
                    .setTooltipText("全选")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(Menu.NONE, MENU_SELECT_RANGE, 1, "局部全选")
                    .setIcon(R.drawable.ic_select_range)
                    .setContentDescription("局部全选")
                    .setTooltipText("局部全选")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        } else {
            val searchItem = menu.add(Menu.NONE, MENU_SEARCH, 0, R.string.menu_search)
            searchItem.setIcon(R.drawable.ic_search)
            searchItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
            configureSearch(searchItem)
            menu.add(Menu.NONE, MENU_CREATE, 1, R.string.menu_new)
                .setIcon(R.drawable.ic_add)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, MENU_MORE, 2, R.string.activity_main_text_01)
                .setIcon(R.drawable.ic_more_vertical)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    fun handle(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SELECT_ALL -> { selectAll(); true }
        MENU_SELECT_RANGE -> { selectRange(); true }
        MENU_CREATE -> { showCreate(); true }
        MENU_MORE -> { showMore(); true }
        else -> false
    }
}
