package com.subtitleedit.util

import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DirectoryWatcherTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private var watcher: DirectoryWatcher? = null

    @Before
    fun createDirectory() {
        root = File(instrumentation.targetContext.cacheDir, "watcher-test-${System.nanoTime()}")
        assertTrue(root.mkdirs())
    }

    @After
    fun cleanUp() {
        instrumentation.runOnMainSync { watcher?.stop() }
        root.deleteRecursively()
    }

    @Test
    fun renamingAFileReportsBothEndpoints() {
        val delivered = CountDownLatch(1)
        instrumentation.runOnMainSync {
            watcher = DirectoryWatcher(onRefresh = { changes ->
                if (changes.changedNames.containsAll(setOf("before.srt", "after.srt"))) delivered.countDown()
            }).also {
                it.setEnabled(true)
                it.watch(root)
            }
        }

        val before = File(root, "before.srt").apply { writeText("subtitle") }
        assertTrue(before.renameTo(File(root, "after.srt")))

        assertTrue("Rename must update both the removed and added cache entries", delivered.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun changingAChildDirectoryInvalidatesOnlyItsCount() {
        val movies = File(root, "Movies").apply { mkdir() }
        val music = File(root, "Music").apply { mkdir() }
        val delivered = CountDownLatch(1)
        val countChanges = Collections.synchronizedSet(linkedSetOf<String>())
        instrumentation.runOnMainSync {
            watcher = DirectoryWatcher(onRefresh = { changes ->
                countChanges += changes.countChangedDirectories
                if (movies.absolutePath in changes.countChangedDirectories) delivered.countDown()
            }).also {
                it.setEnabled(true)
                it.watch(root, listOf(movies, music))
            }
        }

        File(movies, "new.srt").writeText("subtitle")

        assertTrue("Direct child membership must invalidate its displayed item count", delivered.await(5, TimeUnit.SECONDS))
        assertEquals(setOf(movies.absolutePath), synchronized(countChanges) { countChanges.toSet() })
    }

    @Test
    fun callbacksFromAnOldDirectoryCannotRefreshTheNewDirectory() {
        val first = File(root, "first").apply { mkdir() }
        val second = File(root, "second").apply { mkdir() }
        val batches = Collections.synchronizedList(mutableListOf<DirectoryChanges>())
        val delivered = CountDownLatch(1)
        instrumentation.runOnMainSync {
            watcher = DirectoryWatcher(onRefresh = { changes ->
                batches += changes
                if (changes.directoryPath == second.absolutePath && "new.srt" in changes.changedNames) {
                    delivered.countDown()
                }
            }).also {
                it.setEnabled(true)
                it.watch(first)
                // The native callback can be queued while main is inside this block, then
                // delivered only after the watcher has switched to a different directory.
                File(first, "old.srt").writeText("old")
                it.watch(second)
                File(second, "new.srt").writeText("new")
            }
        }

        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        val queueDrained = CountDownLatch(1)
        Handler(Looper.getMainLooper()).postDelayed({ queueDrained.countDown() }, 500L)
        assertTrue(queueDrained.await(5, TimeUnit.SECONDS))
        val snapshot = synchronized(batches) { batches.toList() }
        assertTrue(snapshot.isNotEmpty())
        assertTrue(snapshot.all { it.directoryPath == second.absolutePath && "old.srt" !in it.changedNames })
    }
}
