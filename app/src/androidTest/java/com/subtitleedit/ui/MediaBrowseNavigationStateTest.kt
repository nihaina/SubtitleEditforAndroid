package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.library.MediaAlbumTrack
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.library.buildMediaAlbums
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Round trips must restore the viewport, including its partial-row pixel offset. */
@RunWith(AndroidJUnit4::class)
class MediaBrowseNavigationStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun returningFromARootFolderRestoresTheRootListPosition() {
        val roots = (0 until 60).map { File("/browse-state/root-%03d".format(it)) }
        val path = mutableStateOf<String?>(null)
        showDirectories(roots, path = { path.value }, entries = { emptyList() }, onOpen = { path.value = it.path })

        compose.onNode(hasScrollAction()).performScrollToIndex(30)
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 19f) }
        val row = compose.onNodeWithText(roots[30].name)
        val before = row.fetchSemanticsNode().boundsInRoot
        row.performClick()
        compose.runOnIdle { assertEquals(roots[30].path, path.value) }
        compose.runOnIdle { path.value = null }

        row.assertIsDisplayed()
        assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun eachNestedDirectoryRestoresItsOwnPositionAfterLoading() {
        val root = File("/browse-state/root")
        val child = File(root, "folder-030")
        val grandchild = File(child, "folder-020")
        val path = mutableStateOf<String?>(root.path)
        val ready = mutableStateOf(true)
        showDirectories(listOf(root), path = { path.value }, ready = { ready.value },
            entries = { current -> if (current == grandchild.path) emptyList() else folders(File(current!!)) },
            onOpen = { path.value = it.path })

        val rootBounds = scrollToFolder(child, 30)
        compose.onNodeWithTag("library-file:${child.path}").performClick()
        val childBounds = scrollToFolder(grandchild, 20)
        compose.onNodeWithTag("library-file:${grandchild.path}").performClick()

        // Returning can first display an unprepared folder. It must not erase the saved viewport.
        compose.runOnIdle { ready.value = false; path.value = child.path }
        compose.waitForIdle()
        compose.runOnIdle { ready.value = true }
        assertFolderBounds(grandchild, childBounds)
        compose.runOnIdle { ready.value = false; path.value = root.path }
        compose.waitForIdle()
        compose.runOnIdle { ready.value = true }
        assertFolderBounds(child, rootBounds)

        compose.onNodeWithTag("library-file:${child.path}").performClick()
        assertFolderBounds(grandchild, childBounds)
    }

    @Test fun allFiveCategoryListsRestoreTheirPositionAfterDetailsAndTabSwitches() {
        showCategories(AlbumViewMode.LIST_NO_ART)
        val positions = linkedMapOf<String, Rect>()
        val categories = linkedMapOf(
            MediaCategory.ALBUMS to "album", MediaCategory.SINGERS to "singer",
            MediaCategory.ARTISTS to "artist", MediaCategory.COMPOSERS to "composer",
            MediaCategory.GENRES to "genre",
        )
        categories.forEach { (category, prefix) ->
            selectCategory(category)
            compose.onNodeWithTag("$prefix-list").performScrollToIndex(30)
            compose.onNodeWithTag("$prefix-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 19f) }
            val tag = "$prefix-item:$prefix 030"
            positions[tag] = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag(tag).performClick()
            compose.onNodeWithTag("$prefix-detail").assertIsDisplayed()
            compose.onNodeWithTag("$prefix-detail-back").performClick()
            compose.onNodeWithTag(tag).assertIsDisplayed()
            assertEquals(positions[tag], compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("$prefix-settings").performClick()
            compose.onNodeWithTag("$prefix-settings-back").performClick()
            compose.onNodeWithTag(tag).assertIsDisplayed()
            assertEquals(positions[tag], compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        }
        categories.forEach { (category, prefix) ->
            selectCategory(category)
            val tag = "$prefix-item:$prefix 030"
            compose.onNodeWithTag(tag).assertIsDisplayed()
            assertEquals(positions[tag], compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun albumGridRestoresItsPositionAfterOpeningAnAlbum() {
        showCategories(AlbumViewMode.GRID)
        compose.onNodeWithTag("album-grid").performScrollToIndex(30)
        compose.onNodeWithTag("album-grid").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 19f) }
        val row = compose.onNodeWithTag("album-item:album 030")
        val before = row.fetchSemanticsNode().boundsInRoot
        row.performClick()
        compose.onNodeWithTag("album-detail-back").performClick()

        row.assertIsDisplayed()
        assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun savedBrowsePositionSurvivesStateRestorationWhileAnAlbumIsOpen() {
        val restoration = showCategories(AlbumViewMode.LIST_NO_ART)
        compose.onNodeWithTag("album-list").performScrollToIndex(30)
        compose.onNodeWithTag("album-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 19f) }
        val row = compose.onNodeWithTag("album-item:album 030")
        val before = row.fetchSemanticsNode().boundsInRoot
        row.performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("album-detail").assertIsDisplayed()
        compose.onNodeWithTag("album-detail-back").performClick()

        row.assertIsDisplayed()
        assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
    }

    private fun showDirectories(
        roots: List<File>,
        path: () -> String?,
        entries: (String?) -> List<MediaFolderEntry>,
        ready: () -> Boolean = { true },
        onOpen: (File) -> Unit,
    ) {
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 500.dp)) {
                    FilesTabContent(
                        roots = roots, rootPath = roots.first().path, currentPath = path(),
                        entries = if (path() == null) emptyList() else entries(path()),
                        entriesReady = ready(), rootMediaCounts = emptyMap(), selectedPaths = emptySet(),
                        selectedDurationMs = 0L, onOpenRoot = onOpen, onOpenFolder = onOpen,
                        onPlayFile = {}, onLongPressFolder = {}, onLongPressFile = {},
                        onToggleSelection = {}, onClearSelection = {}, onAdvancedSelection = {},
                        onFavoriteSelection = {}, onAddSelectionToPlaylist = {}, onShowInfo = {},
                    )
                }
            }
        }
    }

    private fun folders(root: File) = (0 until 60).map {
        val folder = File(root, "folder-%03d".format(it))
        MediaFolderEntry(folder.path, folder.name, true, 1, 1_000L)
    }

    private fun scrollToFolder(folder: File, index: Int): Rect {
        compose.onNodeWithTag("library-files-list").performScrollToIndex(index)
        compose.onNodeWithTag("library-files-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 19f) }
        return compose.onNodeWithTag("library-file:${folder.path}").fetchSemanticsNode().boundsInRoot
    }

    private fun assertFolderBounds(folder: File, expected: Rect) {
        val row = compose.onNodeWithTag("library-file:${folder.path}")
        row.assertIsDisplayed()
        assertEquals(expected, row.fetchSemanticsNode().boundsInRoot)
    }

    private fun showCategories(viewMode: AlbumViewMode): StateRestorationTester {
        val albums = buildMediaAlbums((0 until 80).map { index ->
            val suffix = "%03d".format(index)
            MediaAlbumTrack(
                MediaLibraryTrack("/browse-state/$suffix.m4a", "Track $suffix", "Singer $suffix", "Album $suffix"),
                albumArtist = "Artist $suffix", composer = "Composer $suffix", genre = "Genre $suffix",
            )
        })
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 500.dp)) {
                    MediaAlbumPageContent(
                        albums = albums, options = MediaAlbumViewOptions(viewMode = viewMode),
                        onOptionsChanged = {}, onPlay = { _, _ -> },
                    )
                }
            }
        }
        return restoration
    }

    private fun selectCategory(category: MediaCategory) {
        val tag = "media-category:${category.name}"
        compose.onNodeWithTag("media-category-tabs").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
}
