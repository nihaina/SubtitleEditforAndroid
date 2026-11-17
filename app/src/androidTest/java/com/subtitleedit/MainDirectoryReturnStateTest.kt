package com.subtitleedit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.feature.ui.MainActivityScreenState
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.util.DirectoryWatcher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real directory click, asynchronous publication and system Back together. */
@RunWith(AndroidJUnit4::class)
class MainDirectoryReturnStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun systemBackRestoresTheParentListViewport() = checkReturn(FileBrowserViewMode.DETAILED)

    @Test fun systemBackRestoresTheParentGridAnchor() = checkReturn(FileBrowserViewMode.GRID)

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
