package com.subtitleedit.feature.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.DirectoryScrollPosition
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryNavigationFrameTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aNewDirectoryStartsAtItsOwnTopInsteadOfThePreviousDirectoryOffset() =
        checkNavigation(FileBrowserViewMode.DETAILED, savedIndex = null)

    @Test fun aRestoredDirectoryUsesItsSavedListAnchorInItsFirstComposition() =
        checkNavigation(FileBrowserViewMode.DETAILED, savedIndex = 16)

    @Test fun aRestoredDirectoryUsesItsSavedGridAnchorInItsFirstComposition() =
        checkNavigation(FileBrowserViewMode.GRID, savedIndex = 16)

    @Test fun retainedRowsKeepTheirPositionButIgnoreActionsWhileAnotherDirectoryLoads() {
        val initial = directory("retained", FileBrowserViewMode.DETAILED)
        val state = mutableStateOf(initial)
        val clicks = mutableListOf<File>()
        val longClicks = mutableListOf<File>()
        show(state, mutableListOf(), clicks, longClicks)
        compose.onNodeWithTag("directory-files").performScrollToIndex(24)
        compose.waitForIdle()
        val row = compose.onNodeWithText(initial.files[24].name)
        val before = row.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(isDirectoryLoading = true, isDirectoryNavigationPending = true) }
        row.assertIsDisplayed().performClick()
        row.performTouchInput { longClick() }
        compose.runOnIdle {
            assertTrue(clicks.isEmpty())
            assertTrue(longClicks.isEmpty())
        }
        assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state.value = state.value.copy(isDirectoryLoading = false, isDirectoryNavigationPending = false) }
        row.performClick()
        compose.runOnIdle { assertEquals(listOf(initial.files[24]), clicks) }
    }

    private fun checkNavigation(mode: FileBrowserViewMode, savedIndex: Int?) {
        val first = directory("first", mode)
        val second = directory("second", mode)
        val observations = mutableListOf<Pair<String?, DirectoryScrollPosition>>()
        val state = mutableStateOf(first)
        show(state, observations)
        compose.onNodeWithTag("directory-files").performScrollToIndex(40)
        compose.waitForIdle()
        compose.runOnIdle {
            observations.clear()
            state.value = second.copy(scrollRequest = savedIndex?.let { index ->
                MainDirectoryScrollRequest(1L, second.currentDirectory!!.absolutePath,
                    DirectoryScrollPosition(second.files[index].path, index, 7, mode))
            })
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val firstFrame = observations.first { it.first == second.currentDirectory!!.path }.second
            val expected = savedIndex ?: 0
            assertEquals("The destination's first composition must have its own anchor", expected, firstFrame.firstVisibleIndex)
            assertEquals(second.files[expected].path, firstFrame.firstVisiblePath)
            assertEquals(if (mode == FileBrowserViewMode.GRID || savedIndex == null) 0 else 7, firstFrame.offset)
        }
        compose.onNodeWithText(second.files[savedIndex ?: 0].name).assertIsDisplayed()
    }

    private fun show(
        state: MutableState<MainActivityScreenState>,
        observations: MutableList<Pair<String?, DirectoryScrollPosition>>,
        clicks: MutableList<File> = mutableListOf(),
        longClicks: MutableList<File> = mutableListOf()
    ) {
        compose.setContent {
            SubtitleEditComposeTheme {
                val snapshot = state.value
                // MainActivity keeps these owners outside the animated top-level page host.
                val list = rememberDirectoryListState(snapshot)
                val grid = rememberDirectoryGridState(snapshot)
                DirectoryBrowserContent(snapshot,
                    onScrollPosition = { observations += snapshot.currentDirectory?.path to it() },
                    onDirectoryBreadcrumbClick = { false }, onFileClick = { clicks += it },
                    onFileLongClick = { longClicks += it }, listState = list, gridState = grid)
            }
        }
    }

    private fun directory(name: String, mode: FileBrowserViewMode): MainActivityScreenState {
        val root = temporary.newFolder(name)
        val files = (0 until 80).map { File(root, "$name-%03d.srt".format(it)).apply { writeText("text") } }
        return MainActivityScreenState(currentDirectory = root, canonicalDirectoryPath = root.absolutePath,
            files = files, fileMetadata = files.associate { it.path to BrowserFileMetadata(it, false, 4L, 1_000L) },
            viewMode = mode)
    }
}
