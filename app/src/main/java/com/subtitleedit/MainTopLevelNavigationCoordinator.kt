package com.subtitleedit

import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.util.MainNavigationPolicy
import java.io.File

/** Owns top-level page switching while leaving directory/file operations in MainActivity. */
internal class MainTopLevelNavigationCoordinator(
    private val activity: AppCompatActivity,
    private val state: MainDocumentState,
    private val saveDirectoryScroll: () -> Unit,
    private val loadDirectory: (File, Boolean) -> Boolean,
    private val cancelDirectorySearch: () -> Unit,
    private val stopDirectoryWatcher: () -> Unit,
    private val clearDirectorySelection: () -> Unit,
    private val onPageChanged: (Int) -> Unit,
    private val onToolbarChanged: (String, Boolean, (() -> Unit)?) -> Unit
) {
    fun bind() = showPage(state.selectedTopLevelItem)

    fun showPage(itemId: Int) {
        val wasDirectorySelected = state.selectedTopLevelItem == R.id.nav_directory
        if (wasDirectorySelected && itemId != R.id.nav_directory) saveDirectoryScroll()
        state.selectedTopLevelItem = itemId
        onPageChanged(itemId)
        onToolbarChanged(
            activity.getString(
                if (itemId == R.id.nav_directory) R.string.nav_directory
                else MainNavigationPolicy.titleRes(itemId)
            ),
            false,
            null
        )

        if (itemId == R.id.nav_directory) {
            state.currentDirectory?.let { loadDirectory(it, true) }
        } else {
            cancelDirectorySearch()
            stopDirectoryWatcher()
        }
    }

    fun updateToolbar(title: String, showBack: Boolean = false, onBack: (() -> Unit)? = null) {
        onToolbarChanged(title, showBack, onBack)
    }

    fun openDirectoryFromFavorites(directory: File) {
        if (!loadDirectory(directory, true)) return
        clearDirectorySelection()
        showPage(R.id.nav_directory)
    }
}
