package com.subtitleedit

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.subtitleedit.databinding.ActivityMainBinding
import com.subtitleedit.util.MainNavigationPolicy
import java.io.File

/** Owns top-level page switching while leaving directory/file operations in MainActivity. */
internal class MainTopLevelNavigationCoordinator(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val state: MainDocumentState,
    private val saveDirectoryScroll: () -> Unit,
    private val loadDirectory: (File, Boolean) -> Boolean,
    private val cancelDirectorySearch: () -> Unit,
    private val stopDirectoryWatcher: () -> Unit,
    private val invalidateMenu: () -> Unit,
    private val clearDirectorySelection: () -> Unit
) {
    fun bind(onPageSelected: (Int) -> Unit) {
        binding.bottomNavigation.selectedItemId = state.selectedTopLevelItem
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            onPageSelected(item.itemId)
            true
        }
        showPage(state.selectedTopLevelItem)
    }

    fun showPage(itemId: Int) {
        val wasDirectorySelected = state.selectedTopLevelItem == R.id.nav_directory
        if (wasDirectorySelected && itemId != R.id.nav_directory) saveDirectoryScroll()
        state.selectedTopLevelItem = itemId

        val directorySelected = itemId == R.id.nav_directory
        binding.directoryContent.visibility = if (directorySelected) View.VISIBLE else View.GONE
        binding.fragmentContainer.visibility = if (directorySelected) View.GONE else View.VISIBLE
        binding.toolbar.navigationIcon = null
        binding.toolbar.setNavigationOnClickListener(null)

        if (directorySelected) {
            activity.supportActionBar?.title = activity.getString(R.string.nav_directory)
            state.currentDirectory?.let { loadDirectory(it, true) }
        } else {
            cancelDirectorySearch()
            stopDirectoryWatcher()
            val fragment = when (itemId) {
                R.id.nav_favorites -> FavoritesFragment()
                R.id.nav_drafts -> DraftsFragment()
                R.id.nav_tools -> ToolsFragment()
                else -> SettingsFragment()
            }
            activity.supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, fragment, itemId.toString())
                .commit()
            activity.supportActionBar?.title = activity.getString(MainNavigationPolicy.titleRes(itemId))
        }
        binding.bottomNavigation.visibility = View.VISIBLE
        binding.bottomDivider.visibility = View.VISIBLE
        invalidateMenu()
    }

    fun updateToolbar(title: String, showBack: Boolean = false, onBack: (() -> Unit)? = null) {
        binding.toolbar.title = title
        binding.toolbar.navigationIcon = if (showBack) {
            ContextCompat.getDrawable(activity, R.drawable.ic_back)
        } else null
        binding.toolbar.setNavigationOnClickListener(
            if (showBack) View.OnClickListener { onBack?.invoke() } else null
        )
    }

    fun openDirectoryFromFavorites(directory: File) {
        if (!loadDirectory(directory, true)) return
        clearDirectorySelection()
        binding.bottomNavigation.selectedItemId = R.id.nav_directory
    }
}
