package com.subtitleedit.util

import com.subtitleedit.model.BrowserFileMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowserDirectoryCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private class Filesystem(val directory: File) {
        val files = linkedMapOf<String, BrowserFileMetadata>()
        val reads = mutableListOf<String>()
        var listings = 0
        val cache = BrowserDirectoryCache(
            metadataReader = { file, revision ->
                reads += file.name
                files[file.name]?.copy(file = file, revision = revision)
            },
            listFiles = {
                listings++
                files.keys.map { File(directory, it) }
            },
            maxMetadataEntries = 2
        )

        fun put(name: String, size: Long = 1L, directory: Boolean = false, modified: Long = 1000L) {
            files[name] = BrowserFileMetadata(File(this.directory, name), directory, size, modified)
        }
    }

    @Test
    fun `valid directory snapshots and metadata hits perform no additional reads`() {
        val fs = Filesystem(temporaryFolder.root).apply {
            put("a.srt"); put("b.srt"); put("c.srt")
        }
        val first = fs.cache.load(fs.directory)
        repeat(5) {
            assertSame(first, fs.cache.load(fs.directory))
            val metadata = fs.cache.metadata(first.entries.map { it.file })
            first.entries.forEach { assertSame(it, metadata[it.path]) }
        }
        // Active snapshots retain their metadata even when the small auxiliary
        // LRU has evicted some rows. Repeated lookup must not cause more stats.
        assertEquals(listOf("a.srt", "b.srt", "c.srt"), fs.reads)
        assertEquals(1, fs.listings)
    }

    @Test
    fun `change reads only named entries and retains unaffected rows`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt"); put("b.srt") }
        val first = fs.cache.load(fs.directory)
        fs.put("b.srt", size = 200L)
        fs.put("new.srt", size = 300L)
        val second = fs.cache.update(fs.directory, setOf("b.srt", "new.srt"))

        assertSame(first.entries.first(), second.entries.first())
        assertEquals(listOf("a.srt", "b.srt", "b.srt", "new.srt"), fs.reads)
        assertEquals(listOf(1L, 200L, 300L), second.entries.map { it.size })
        assertEquals(1, fs.listings)
    }

    @Test
    fun `deletion and move update only source and destination entries`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("old.srt"); put("keep.srt") }
        val first = fs.cache.load(fs.directory)
        fs.files.remove("old.srt")
        fs.put("renamed.srt")
        val second = fs.cache.update(fs.directory, setOf("old.srt", "renamed.srt"))

        assertEquals(listOf("keep.srt", "renamed.srt"), second.entries.map { it.file.name })
        assertSame(first.entries[1], second.entries[0])
        assertNull(fs.cache.cachedMetadata(File(fs.directory, "old.srt").absolutePath))
        assertEquals(listOf("old.srt", "keep.srt", "old.srt", "renamed.srt"), fs.reads)
        assertEquals(1, fs.listings)
    }

    @Test
    fun `change invalidates preview key even if size and modification time are unchanged`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt") }
        val first = fs.cache.load(fs.directory).entries.single()
        val updated = fs.cache.update(fs.directory, setOf("a.srt")).entries.single()

        assertEquals(first.size, updated.size)
        assertEquals(first.modifiedTimeMillis, updated.modifiedTimeMillis)
        assertNotEquals(first.cacheKey, updated.cacheKey)
    }

    @Test
    fun `child content change refreshes only affected directory attributes and count revision`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("folder", directory = true); put("keep.srt") }
        val first = fs.cache.load(fs.directory)
        val changedPath = first.entries.first().path
        fs.put("folder", directory = true, modified = 2000L)
        val second = fs.cache.update(fs.directory, emptySet(), setOf(changedPath))

        assertNotEquals(first.entries.first().cacheKey, second.entries.first().cacheKey)
        assertEquals(2000L, second.entries.first().modifiedTimeMillis)
        assertSame(first.entries[1], second.entries[1])
        assertEquals(listOf("folder", "keep.srt", "folder"), fs.reads)
        assertEquals(1, fs.listings)
    }

    @Test
    fun `named directory and count event invalidate descendants while reading folder only once`() {
        val root = temporaryFolder.root
        val folder = File(root, "folder")
        val child = File(folder, "child.srt")
        val reads = mutableListOf<String>()
        var childSize = 10L
        var folderListings = 0
        val cache = BrowserDirectoryCache(
            metadataReader = { file, revision ->
                reads += file.absolutePath
                BrowserFileMetadata(file, file == folder, if (file == child) childSize else 0L, 1000L, revision = revision)
            },
            listFiles = {
                if (it == root) listOf(folder) else listOf(child).also { folderListings++ }
            }
        )
        cache.load(root)
        val oldChild = cache.load(folder).entries.single()
        childSize = 20L
        val updated = cache.update(root, setOf("folder"), setOf(folder.absolutePath))

        assertEquals(2, reads.count { it == folder.absolutePath })
        assertEquals(1, reads.count { it == child.absolutePath })
        assertSame(updated.entries.single(), cache.cachedMetadata(folder.absolutePath))
        assertNull(cache.cachedMetadata(child.absolutePath))
        val searchedChild = cache.metadata(listOf(child)).getValue(child.absolutePath)
        assertEquals(20L, searchedChild.size)
        assertNotEquals(oldChild.cacheKey, searchedChild.cacheKey)
        cache.load(folder)
        assertEquals(2, folderListings)
    }

    @Test
    fun `count-only event invalidates cached child directory snapshot and search metadata`() {
        val root = temporaryFolder.root
        val folder = File(root, "folder")
        val child = File(folder, "child.srt")
        var folderListings = 0
        val cache = BrowserDirectoryCache(
            metadataReader = { file, revision -> BrowserFileMetadata(file, file == folder, 1L, 1000L, revision = revision) },
            listFiles = { if (it == root) listOf(folder) else listOf(child).also { folderListings++ } }
        )
        cache.load(root)
        val oldChild = cache.load(folder).entries.single()
        cache.update(root, emptySet(), setOf(folder.absolutePath))

        assertNull(cache.cachedMetadata(child.absolutePath))
        val newChild = cache.load(folder).entries.single()
        assertNotEquals(oldChild.cacheKey, newChild.cacheKey)
        assertEquals(2, folderListings)
    }

    @Test
    fun `lifecycle invalidation does not wait for blocked IO and survives until next load`() {
        val file = File(temporaryFolder.root, "a.srt")
        val readerEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val reads = AtomicInteger()
        val cache = BrowserDirectoryCache(
            metadataReader = { entry, revision ->
                if (reads.incrementAndGet() == 1) {
                    readerEntered.countDown()
                    check(releaseReader.await(5, TimeUnit.SECONDS))
                }
                BrowserFileMetadata(entry, false, 1L, 1000L, revision = revision)
            },
            listFiles = { listOf(file) }
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val inFlightLoad = executor.submit<BrowserDirectorySnapshot> { cache.load(temporaryFolder.root) }
            check(readerEntered.await(5, TimeUnit.SECONDS))
            executor.submit { cache.invalidate(temporaryFolder.root.absolutePath) }.get(1, TimeUnit.SECONDS)
            releaseReader.countDown()
            val first = inFlightLoad.get(5, TimeUnit.SECONDS)
            val second = cache.load(temporaryFolder.root)
            assertNotEquals(first.entries.single().cacheKey, second.entries.single().cacheKey)
            assertEquals(2, reads.get())
            assertSame(second, cache.load(temporaryFolder.root))
        } finally {
            releaseReader.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cache instances never reuse revisions for independent snapshots`() {
        val file = File(temporaryFolder.root, "a.srt")
        fun cache() = BrowserDirectoryCache(
            metadataReader = { entry, revision -> BrowserFileMetadata(entry, false, 1L, 1000L, revision = revision) },
            listFiles = { listOf(file) }
        )
        val first = cache().load(temporaryFolder.root).entries.single()
        val second = cache().load(temporaryFolder.root).entries.single()
        assertNotEquals(first.revision, second.revision)
        assertNotEquals(first.cacheKey, second.cacheKey)
    }

    @Test
    fun `observation gap reconciles deleted added and same attribute entries once`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt"); put("old.srt") }
        val first = fs.cache.load(fs.directory)
        fs.cache.invalidate(fs.directory.absolutePath)
        fs.files.remove("old.srt")
        fs.put("new.srt")
        val second = fs.cache.load(fs.directory)

        assertEquals(listOf("a.srt", "new.srt"), second.entries.map { it.file.name })
        assertNotEquals(first.entries.first().cacheKey, second.entries.first().cacheKey)
        assertNull(fs.cache.cachedMetadata(File(fs.directory, "old.srt").absolutePath))
        assertSame(second, fs.cache.load(fs.directory))
        assertEquals(2, fs.listings)
        assertEquals(listOf("a.srt", "old.srt", "a.srt", "new.srt"), fs.reads)
    }

    @Test
    fun `invalid relative event names never read paths outside the directory`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt") }
        val first = fs.cache.load(fs.directory)
        val second = fs.cache.update(fs.directory, setOf("../outside", "sub/file", "..", "", "sub\\file"))

        assertEquals(first.entries, second.entries)
        assertEquals(listOf("a.srt"), fs.reads)
    }

    @Test
    fun `real filesystem reads all attributes together and missing files are absent`() {
        val file = temporaryFolder.newFile("sample.srt").apply { writeText("test"); setLastModified(1000L) }
        val metadata = BrowserFileMetadata.read(file, 10L)!!

        assertEquals(false, metadata.isDirectory)
        assertEquals(true, metadata.isRegularFile)
        assertEquals(4L, metadata.size)
        assertEquals(1000L, metadata.modifiedTimeMillis)
        assertEquals(10L, metadata.revision)
        val generated = BrowserFileMetadata.read(file)!!
        assertNotEquals(generated.cacheKey, BrowserFileMetadata.read(file)!!.cacheKey)
        file.delete()
        assertNull(BrowserFileMetadata.read(file, 11L))
    }
}
