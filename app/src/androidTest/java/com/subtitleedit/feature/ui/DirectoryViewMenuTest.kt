package com.subtitleedit.feature.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.DirectoryScrollPosition
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SettingsManager
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryViewMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun viewAndSortChoicesUpdateTogetherWithoutClosingMenu() {
        val mode = mutableStateOf(FileBrowserViewMode.DETAILED)
        val field = mutableStateOf(FileSortField.NAME)
        val direction = mutableStateOf(FileSortDirection.ASCENDING)
        compose.setContent { SubtitleEditComposeTheme {
            DirectoryViewMenu(mode.value, field.value, direction.value, { mode.value = it }, { field.value = it }, { direction.value = it })
        } }
        compose.onNodeWithTag("directory-view-button").performClick()
        for (view in FileBrowserViewMode.entries) {
            compose.onNodeWithTag("directory-view-${view.name}").performClick().assertIsSelected()
            assertEquals(view, mode.value)
        }
        for (sort in FileSortField.entries) {
            compose.onNodeWithTag("directory-sort-${sort.name}").performClick().assertIsSelected()
            assertEquals(sort, field.value)
        }
        compose.onNodeWithTag("directory-sort-direction").performClick()
        assertEquals(FileSortDirection.DESCENDING, direction.value)
        compose.onNodeWithTag("directory-sort-direction").performClick()
        assertEquals(FileSortDirection.ASCENDING, direction.value)
    }

    @Test fun gridAndCompactKeepVisibleFileAndRouteClicksAndLongClicks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "directory-views-${System.nanoTime()}").apply { mkdirs() }
        try {
            val files = (0 until 80).map { index -> File(root, "file-%03d.srt".format(index)).apply { writeText("subtitle") } }
            val state = mutableStateOf(MainActivityScreenState(currentDirectory = root, files = files))
            var currentPosition: (() -> DirectoryScrollPosition)? = null
            val clicks = mutableListOf<File>()
            val longClicks = mutableListOf<File>()
            val expectedFiles = mutableListOf<File>()
            compose.setContent { SubtitleEditComposeTheme {
                DirectoryBrowserContent(state.value, { currentPosition = it }, { false }, { clicks += it }, { longClicks += it })
            } }
            compose.onNodeWithTag("directory-files").performScrollToIndex(24)
            compose.waitForIdle()
            for ((generation, mode) in listOf(FileBrowserViewMode.GRID, FileBrowserViewMode.COMPACT, FileBrowserViewMode.DETAILED).withIndex()) {
                compose.runOnIdle {
                    val saved = currentPosition!!.invoke()
                    expectedFiles += files[saved.firstVisibleIndex]
                    state.value = state.value.copy(viewMode = mode,
                        scrollRequest = MainDirectoryScrollRequest(generation.toLong(), root.canonicalPath, saved.copy(offset = 0)))
                }
                compose.waitForIdle()
                val anchor = expectedFiles.last()
                compose.onNodeWithText(anchor.name).assertIsDisplayed().performClick()
                compose.onNodeWithText(anchor.name).performTouchInput { longClick() }
            }
            assertEquals(expectedFiles, clicks)
            assertEquals(expectedFiles, longClicks)
        } finally { root.deleteRecursively() }
    }

    @Test fun viewPreferenceIsSavedAndIncludedInSettingsBackup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager.getInstance(context)
        val previous = settings.getFileViewMode()
        try {
            for (mode in FileBrowserViewMode.entries) {
                settings.setFileViewMode(mode)
                assertEquals(mode, settings.getFileViewMode())
                assertEquals(mode.name, settings.exportSettingsSnapshot()["file_view_mode"])
            }
        } finally { settings.setFileViewMode(previous) }
    }
}
