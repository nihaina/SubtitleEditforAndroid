package com.subtitleedit

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.feature.ui.MainActivityScreenState
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.util.DirectoryWatcher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real directory click, asynchronous publication and system Back together. */
@RunWith(AndroidJUnit4::class)
class MainDirectoryReturnStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun systemBackRestoresTheParentListViewport() = checkReturn(FileBrowserViewMode.DETAILED)

    @Test fun systemBackRestoresTheParentGridAnchor() = checkReturn(FileBrowserViewMode.GRID)

    @Test fun imagePreviewKeepsTheViewportAndUsesDirectorySelection() {
        val activity = compose.activity
        val root = File(activity.cacheDir, "directory-image-${System.nanoTime()}").apply { check(mkdir()) }
        repeat(40) { index -> check(File(root, "folder-$index").mkdir()) }
        val image = File(root, "cover.PNG")
        val bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val original = screen()
        try {
            compose.runOnIdle {
                call("showTopLevelPage", arrayOf(Int::class.javaPrimitiveType!!), R.id.nav_directory)
                call("changeFileViewMode", arrayOf(FileBrowserViewMode::class.java), FileBrowserViewMode.DETAILED)
                load(root)
            }
            awaitDirectory(root)
            val tag = "directory-file:${image.path}"
            val row = compose.onNodeWithTag(tag)
            compose.onNodeWithTag("directory-files").performScrollToNode(hasTestTag(tag))
            val before = position()
            row.performClick()
            val previewLabel = activity.getString(R.string.directory_image_preview)
            compose.waitUntil(5_000L) { compose.onAllNodes(hasContentDescription(previewLabel)).fetchSemanticsNodes().isNotEmpty() }
            val preview = compose.onNodeWithContentDescription(previewLabel)
            preview.assertIsDisplayed()
            val previewNodeId = preview.fetchSemanticsNode().id
            val checkbox = compose.onNodeWithContentDescription(activity.getString(R.string.select_file))
            val copyAction = compose.onNodeWithText(activity.getString(R.string.activity_main_contentdescription_01))
            val previewBottom = preview.fetchSemanticsNode().boundsInRoot.bottom
            compose.mainClock.autoAdvance = false
            try {
                checkbox.assertIsOff().performClick()
                compose.mainClock.advanceTimeBy(64L)
                val enteringTop = copyAction.getUnclippedBoundsInRoot().top
                assertEquals(previewBottom, preview.fetchSemanticsNode().boundsInRoot.bottom, 0f)
                compose.mainClock.advanceTimeBy(400L)
                val settledTop = copyAction.getUnclippedBoundsInRoot().top
                assertTrue("Selection actions should slide up from the bottom", enteringTop > settledTop)
                checkbox.assertIsOn()
                assertEquals(setOf(image.absolutePath), screen().selectedPaths)
                assertEquals(previewNodeId, preview.fetchSemanticsNode().id)

                checkbox.performClick()
                compose.mainClock.advanceTimeBy(128L)
                assertTrue("Deselected actions should slide down", copyAction.getUnclippedBoundsInRoot().top > settledTop)
                compose.mainClock.advanceTimeBy(250L)
                copyAction.assertDoesNotExist()
                checkbox.assertIsOff()
                assertEquals(emptySet<String>(), screen().selectedPaths)
                assertEquals(previewNodeId, preview.fetchSemanticsNode().id)
                assertEquals(previewBottom, preview.fetchSemanticsNode().boundsInRoot.bottom, 0f)
            } finally {
                compose.mainClock.autoAdvance = true
            }

            pressBack()
            preview.assertDoesNotExist()
            row.assertIsDisplayed()
            assertEquals(before, position())

            row.performClick()
            checkbox.performClick()
            copyAction.performClick()
            preview.assertDoesNotExist()
            assertEquals(FileOperation.COPY, screen().pendingOperation)
            assertEquals(setOf(image.absolutePath), screen().selectedPaths)
            assertEquals(root, screen().currentDirectory)
        } finally {
            compose.runOnIdle {
                call("closeImagePreview", emptyArray())
                call("exitSelectionMode", emptyArray())
                call("changeFileViewMode", arrayOf(FileBrowserViewMode::class.java), original.viewMode)
                original.currentDirectory?.takeIf { it.isDirectory }?.let(::load)
            }
            root.deleteRecursively()
        }
    }

    @Test fun togglingPlayerModeKeepsTheMountedDirectoryAndItsScrollPosition() {
        val activity = compose.activity
        val root = File(activity.cacheDir, "directory-mode-${System.nanoTime()}").apply { check(mkdir()) }
        val folders = (0 until 60).map { File(root, "folder-%03d".format(it)).apply { check(mkdir()) } }
        val original = screen()
        try {
            compose.runOnIdle {
                call("showTopLevelPage", arrayOf(Int::class.javaPrimitiveType!!), R.id.nav_directory)
                call("changeFileViewMode", arrayOf(FileBrowserViewMode::class.java), FileBrowserViewMode.DETAILED)
                load(root)
            }
            awaitDirectory(root)
            val tag = "directory-file:${folders[30].path}"
            val list = compose.onNodeWithTag("directory-files")
            list.performScrollToNode(hasTestTag(tag))
            val row = compose.onNodeWithTag(tag)
            val listId = list.fetchSemanticsNode().id
            val rowId = row.fetchSemanticsNode().id
            val before = position()
            val files = screen().files
            val bottomNavigation = hasAnyAncestor(hasTestTag("main-bottom-navigation"))
            for (enabled in listOf(!original.playerModeEnabled, original.playerModeEnabled)) {
                compose.onNodeWithTag("directory-player-mode").performClick()
                val tab = if (enabled) R.string.nav_library else R.string.nav_favorites
                compose.onNode(hasText(activity.getString(tab)) and bottomNavigation).assertIsDisplayed()
                compose.onNode(hasText(activity.getString(R.string.nav_directory)) and bottomNavigation).assertIsSelected()
                assertEquals(enabled, screen().playerModeEnabled)
                assertEquals(root, screen().currentDirectory)
                assertSame(files, screen().files)
                assertEquals(listId, list.fetchSemanticsNode().id)
                assertEquals(rowId, row.fetchSemanticsNode().id)
                assertEquals(before, position())
                row.assertIsDisplayed()
            }
        } finally {
            compose.runOnIdle {
                if (screen().playerModeEnabled != original.playerModeEnabled) call("togglePlayerMode", emptyArray())
                call("changeFileViewMode", arrayOf(FileBrowserViewMode::class.java), original.viewMode)
                original.currentDirectory?.takeIf { it.isDirectory }?.let(::load)
            }
            root.deleteRecursively()
        }
    }

    private fun checkReturn(mode: FileBrowserViewMode) {
        val activity = compose.activity
        val root = File(activity.cacheDir, "directory-return-${System.nanoTime()}").apply { check(mkdir()) }
        val folders = (0 until 60).map { File(root, "folder-%03d".format(it)).apply { check(mkdir()) } }
        val child = folders[30]
        val original = screen().currentDirectory
        val originalMode = screen().viewMode
        try {
            compose.runOnIdle {
                activity.javaClass.getDeclaredField("directoryWatcher").apply { isAccessible = true }
                    .get(activity).let { (it as DirectoryWatcher).setEnabled(false) }
                call("showTopLevelPage", arrayOf(Int::class.javaPrimitiveType!!), R.id.nav_directory)
                call("changeFileViewMode", arrayOf(FileBrowserViewMode::class.java), mode)
                load(root)
            }
            awaitDirectory(root)
            val tag = "directory-file:${child.path}"
            compose.onNodeWithTag("directory-files").performScrollToNode(hasTestTag(tag))
            val row = compose.onNodeWithTag(tag)
            val before = row.fetchSemanticsNode().boundsInRoot
            val position = position()
            row.performClick()
            awaitDirectory(child)
            pressBack()
            awaitDirectory(root)

            row.assertIsDisplayed()
            assertEquals(position.firstVisiblePath, position().firstVisiblePath)
            if (mode != FileBrowserViewMode.GRID) {
                assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
            }
        } finally {
            compose.runOnIdle {
                call("changeFileViewMode", arrayOf(FileBrowserViewMode::class.java), originalMode)
                original?.takeIf { it.isDirectory }?.let(::load)
            }
            root.deleteRecursively()
        }
    }

    private fun awaitDirectory(directory: File) {
        compose.waitUntil(5_000L) {
            screen().let { it.currentDirectory == directory && !it.isDirectoryLoading }
        }
        compose.waitForIdle()
    }

    private fun load(directory: File) = call("loadDirectory",
        arrayOf(File::class.java, Boolean::class.javaPrimitiveType!!), directory, true)

    private fun screen() = call("getScreenState", emptyArray()) as MainActivityScreenState

    @Suppress("UNCHECKED_CAST")
    private fun position(): DirectoryScrollPosition {
        val provider = compose.activity.javaClass.getDeclaredField("directoryScrollPosition")
            .apply { isAccessible = true }.get(compose.activity) as () -> DirectoryScrollPosition
        return compose.runOnIdle { provider() }
    }

    private fun call(name: String, parameters: Array<Class<*>>, vararg arguments: Any?): Any? =
        compose.activity.javaClass.getDeclaredMethod(name, *parameters).apply { isAccessible = true }
            .invoke(compose.activity, *arguments)
}
