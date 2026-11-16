package com.subtitleedit

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.feature.ui.MainActivityScreenState
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.util.BrowserDirectoryCache
import com.subtitleedit.util.BrowserDirectorySnapshot
import com.subtitleedit.util.DirectorySnapshotLoader
import com.subtitleedit.util.DirectoryWatcher
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The published browser snapshot must stay coherent while a real directory request is blocked. */
@RunWith(AndroidJUnit4::class)
class MainDirectoryNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun pendingNavigationKeepsTheDisplayedPathRowsMetadataAndScrollUntilTheNewSnapshotIsReady() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "main-directory-navigation-${System.nanoTime()}").apply { check(mkdirs()) }
        val child = File(root, "child").apply { check(mkdir()) }
        val sibling = File(root, "sibling").apply { check(mkdir()) }
        val old = File(root, "old.srt").apply { writeText("old") }
        val next = File(child, "new.srt").apply { writeText("new") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val cache = BrowserDirectoryCache(listFiles = { directory ->
            if (directory.absolutePath == child.absolutePath) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "The test did not release the child-directory load" }
            }
            directory.listFiles()?.toList()
        })
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        try {
            scenario.onActivity {
                activity = it
                // Exercise the Activity's browser publication without a rendered list replacing our scroll provider.
                it.setContent { Text("Directory navigation snapshot test") }
                field<DirectoryWatcher>(it, "directoryWatcher").setEnabled(false)
                val loader = DirectorySnapshotLoader(scope, cache, { snapshot, restore ->
                    invoke(it, "applyDirectorySnapshot", arrayOf(BrowserDirectorySnapshot::class.java, Boolean::class.javaPrimitiveType!!), snapshot, restore)
                })
                setField(it, "directorySnapshotLoader", loader)
                assertTrue(load(it, root))
            }
            await { onMain { screen(activity).currentDirectory == root && !screen(activity).isDirectoryLoading } }
            val before = onMain { screen(activity) }
            assertTrue(old in before.files)
            assertNotNull(before.fileMetadata[old.absolutePath])
            val saved = DirectoryScrollPosition(old.absolutePath, before.files.indexOf(old), 13, FileBrowserViewMode.DETAILED)

            onMain {
                setField(activity, "directoryScrollPosition", { saved })
                assertTrue(load(activity, child))
            }
            assertTrue("The replacement directory is being read", entered.await(5, TimeUnit.SECONDS))
            try {
                onMain {
                    // Search/sort refreshes during a pending jump previously published an empty target list.
                    invoke(activity, "displayDirectoryFiles", arrayOf(Boolean::class.javaPrimitiveType!!), false)
                    invoke(activity, "clearFileSearch", arrayOf(Boolean::class.javaPrimitiveType!!), false)
                    val pending = screen(activity)
                    assertTrue(pending.isDirectoryNavigationPending)
                    assertTrue(pending.isDirectoryLoading)
                    assertEquals(before.currentDirectory, pending.currentDirectory)
                    assertEquals(before.canonicalDirectoryPath, pending.canonicalDirectoryPath)
                    assertSame(before.files, pending.files)
                    assertSame(before.fileMetadata, pending.fileMetadata)
                    assertFalse(pending.showDirectoryEmptyState)

                    invoke(activity, "enterSelectionMode", arrayOf(File::class.java), old)
                    invoke(activity, "onFileClicked", arrayOf(File::class.java), sibling)
                    val model = ViewModelProvider(activity)[MainViewModel::class.java]
                    assertTrue("Retained rows cannot start a stale selection", model.selectedPaths.isEmpty())
                    assertEquals("Retained rows cannot replace the requested destination", child, model.currentDirectory)

                    invoke(activity, "saveCurrentDirectoryScrollPosition", emptyArray())
                    assertEquals(saved, model.directoryScrollPositions[before.canonicalDirectoryPath])
                    assertFalse("The old viewport must not be saved under the pending directory", model.directoryScrollPositions.containsKey(child.canonicalPath))
                }
            } finally {
                release.countDown()
            }
            await { onMain { screen(activity).currentDirectory == child && !screen(activity).isDirectoryLoading } }
            onMain {
                val loaded = screen(activity)
                assertFalse(loaded.isDirectoryNavigationPending)
                assertTrue(next in loaded.files)
                assertFalse(old in loaded.files)
                assertEquals(next.absolutePath, loaded.fileMetadata[next.absolutePath]?.path)
                assertEquals(child.canonicalPath, loaded.canonicalDirectoryPath)
            }
        } finally {
            release.countDown()
            scenario.close()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private fun load(activity: MainActivity, directory: File): Boolean =
        invoke(activity, "loadDirectory", arrayOf(File::class.java, Boolean::class.javaPrimitiveType!!), directory, true) as Boolean

    private fun screen(activity: MainActivity): MainActivityScreenState =
        invoke(activity, "getScreenState", emptyArray()) as MainActivityScreenState

    private fun invoke(target: Any, name: String, types: Array<Class<*>>, vararg arguments: Any?): Any? =
        target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(target, *arguments)

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "The directory snapshot did not become ready" }
            Thread.sleep(15)
        }
    }
}
