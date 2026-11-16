package com.subtitleedit.filepicker

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilePickerNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Test fun navigationKeepsThePreviousDirectoryUntilItsReplacementAndMetadataAreReady() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        withPicker(listDirectory = { directory ->
            if (directory.name == "child") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            directory.listFiles()
        }) { fixture ->
            val before = fixture.model.state.value
            onMain { assertTrue(fixture.model.navigate(fixture.child)) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val pending = fixture.model.state.value
                assertTrue(pending.loading)
                assertEquals(fixture.child, pending.pendingDirectory)
                assertEquals(before.directory, pending.directory)
                assertSame(before.files, pending.files)
                assertSame(before.fileMetadata, pending.fileMetadata)
                onMain {
                    fixture.model.select(fixture.oldFile)
                    fixture.model.selectAll()
                }
                assertTrue("The retained rows cannot change selection during navigation", fixture.model.state.value.selectedPaths.isEmpty())
            } finally {
                release.countDown()
            }
            val loaded = fixture.awaitLoaded()
            assertEquals(fixture.child, loaded.directory)
            assertNull(loaded.pendingDirectory)
            assertEquals(listOf(fixture.childFile), loaded.files)
            assertEquals(setOf(fixture.childFile.path), loaded.fileMetadata.keys)
        }
    }

    @Test fun anotherDirectoryCanReplaceAPendingLoadWithoutDisplayingItsObsoleteResults() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        withPicker(listDirectory = { directory ->
            if (directory.name == "child") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            directory.listFiles()
        }) { fixture ->
            onMain { fixture.model.navigate(fixture.child) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                onMain { fixture.model.navigate(fixture.second) }
                val loaded = fixture.awaitLoaded()
                assertEquals(fixture.second, loaded.directory)
                assertEquals(listOf(fixture.secondFile), loaded.files)
            } finally {
                release.countDown()
            }
            instrumentation.waitForIdleSync()
            assertEquals(fixture.second, fixture.model.state.value.directory)
            assertEquals(listOf(fixture.secondFile), fixture.model.state.value.files)
        }
    }

    @Test fun aFailedJumpRetainsTheLastUsableDirectoryAndRestoresItsSavedPath() {
        withPicker(listDirectory = { if (it.name == "child") null else it.listFiles() }) { fixture ->
            val before = fixture.model.state.value
            onMain { fixture.model.navigate(fixture.child) }
            val failed = fixture.awaitLoaded()
            assertEquals(before.directory, failed.directory)
            assertSame(before.files, failed.files)
            assertNotNull(failed.error)
            assertNull(failed.pendingDirectory)
            assertEquals(before.directory.path, fixture.saved.get<String>("directory"))
        }
    }

    private fun withPicker(listDirectory: (File) -> Array<File>?, block: (Fixture) -> Unit) {
        val directory = File(app.cacheDir, "picker-navigation-${System.nanoTime()}").apply { check(mkdirs()) }
        val child = File(directory, "child").apply { check(mkdir()) }
        val second = File(directory, "second").apply { check(mkdir()) }
        val old = File(directory, "old.srt").apply { writeText("old") }
        val next = File(child, "new.srt").apply { writeText("new") }
        val latest = File(second, "latest.srt").apply { writeText("latest") }
        val saved = SavedStateHandle()
        val store = ViewModelStore()
        try {
            val model = onMain {
                FilePickerViewModel(app, saved, listDirectory).also {
                    store.put("picker", it)
                    it.initialize(FilePickerRequest(FilePickerMode.OPEN_FILES, initialPath = directory.path))
                    it.updateStoragePermission(true)
                }
            }
            val fixture = Fixture(model, saved, child, second, old, next, latest)
            fixture.awaitLoaded()
            block(fixture)
        } finally {
            onMain { store.clear() }
            directory.deleteRecursively()
        }
    }

    private class Fixture(
        val model: FilePickerViewModel,
        val saved: SavedStateHandle,
        val child: File,
        val second: File,
        val oldFile: File,
        val childFile: File,
        val secondFile: File
    ) {
        fun awaitLoaded() = runBlocking { withTimeout(5_000) { model.state.first { !it.loading } } }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}
