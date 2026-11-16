package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class FavoriteDirectorySectionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun detailedGroupsKeepLibraryBelowCommonAndPreserveDirectoryActions() =
        checkGroups(FileBrowserViewMode.DETAILED)

    @Test fun compactGroupsKeepLibraryBelowCommonAndPreserveDirectoryActions() =
        checkGroups(FileBrowserViewMode.COMPACT)

    @Test fun gridGroupsUseFullWidthHeadersAndPreserveDirectoryActions() =
        checkGroups(FileBrowserViewMode.GRID)

    @Test fun promotionMovesAnExistingDirectoryToLibraryWithoutDuplicatingIt() {
        val harness = show(FileBrowserViewMode.DETAILED, libraryInitially = false)
        compose.onNodeWithText("媒体库").assertDoesNotExist()

        compose.runOnIdle { harness.libraryPaths.value = setOf(harness.library.absolutePath) }

        assertSectionOrder(harness)
        compose.onAllNodesWithText(harness.library.name).assertCountEquals(1)
        compose.onNodeWithText(harness.library.name).performClick()
        compose.runOnIdle { assertEquals(listOf(harness.library), harness.opened) }
    }

    private fun checkGroups(viewMode: FileBrowserViewMode) {
        val harness = show(viewMode)
        assertSectionOrder(harness)
        if (viewMode == FileBrowserViewMode.GRID) {
            val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
            listOf("常用目录", "媒体库").forEach { title ->
                assertTrue("Section headers span all grid columns",
                    compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.width > rootWidth * 0.9f)
            }
        }

        compose.onNodeWithText(harness.common.name).performClick()
        compose.onNodeWithText(harness.library.name).performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(listOf(harness.common), harness.opened)
            assertEquals(listOf(harness.library), harness.removalRequests)
        }
    }

    private fun assertSectionOrder(harness: Harness) {
        val commonHeader = compose.onNodeWithText("常用目录").fetchSemanticsNode().boundsInRoot
        val common = compose.onNodeWithText(harness.common.name).fetchSemanticsNode().boundsInRoot
        val libraryHeader = compose.onNodeWithText("媒体库").fetchSemanticsNode().boundsInRoot
        val library = compose.onNodeWithText(harness.library.name).fetchSemanticsNode().boundsInRoot
        assertTrue("Common directories stay above the library section", commonHeader.bottom <= common.top)
        assertTrue("Library section starts after common directories", common.bottom <= libraryHeader.top)
        assertTrue("Library directories stay below their section header", libraryHeader.bottom <= library.top)
    }

    private fun show(viewMode: FileBrowserViewMode, libraryInitially: Boolean = true): Harness {
        val harness = Harness(temporary.newFolder("Common"), temporary.newFolder("Library"))
        if (libraryInitially) harness.libraryPaths.value = setOf(harness.library.absolutePath)
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface {
                    FavoritesScreen(
                        // Reverse the input so the section ordering cannot rely on name sorting.
                        directories = listOf(harness.library, harness.common),
                        pendingRemoval = null,
                        onAddDirectory = {},
                        onOpenDirectory = { harness.opened += it },
                        onRequestRemoval = { harness.removalRequests += it },
                        onConfirmRemoval = {},
                        onDismissRemoval = {},
                        viewMode = viewMode,
                        mediaLibraryDirectoryPaths = harness.libraryPaths.value
                    )
                }
            }
        }
        return harness
    }

    private class Harness(val common: File, val library: File) {
        val libraryPaths: MutableState<Set<String>> = mutableStateOf(emptySet())
        val opened = mutableListOf<File>()
        val removalRequests = mutableListOf<File>()
    }
}
