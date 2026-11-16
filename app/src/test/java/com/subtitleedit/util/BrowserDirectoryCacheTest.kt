package com.subtitleedit.util

import com.subtitleedit.model.BrowserFileMetadata
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserDirectoryCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private class Filesystem(val directory: File) {
        val files = linkedMapOf<String, BrowserFileMetadata>()
        val reads = mutableListOf<String>()
        var listings = 0
        val attributes = FileMetadataCache(reader = { file, revision ->
            reads += file.name
            files[file.name]?.copy(file = file, revision = revision)
        })
        val cache = BrowserDirectoryCache(attributes, listFiles = {
            listings++
            files.keys.map { File(directory, it) }
        })

        fun put(name: String, size: Long = 1L, directory: Boolean = false, modified: Long = 1000L) {
            files[name] = BrowserFileMetadata(File(this.directory, name), directory, size, modified)
        }
    }

    @Test fun `directory and scan reuse the same snapshots without additional attribute reads`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt"); put("b.srt"); put("c.srt") }
        val first = fs.cache.load(fs.directory)
        repeat(5) {
            assertSame(first, fs.cache.load(fs.directory))
            first.entries.forEach { assertSame(it, fs.attributes.get(it.file)) }
        }
        assertEquals(listOf("a.srt", "b.srt", "c.srt"), fs.reads)
        assertEquals(1, fs.listings)
    }

    @Test fun `preloaded attributes are not reread when a directory is first listed`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt"); put("b.srt") }
        val before = fs.attributes.get(File(fs.directory, "a.srt"))
        val entries = fs.cache.load(fs.directory).entries
        assertSame(before, entries.first())
        assertEquals(listOf("a.srt", "b.srt"), fs.reads)
    }

    @Test fun `count then scan only lists once and count does not stat children`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt"); put("b.srt") }
        assertEquals(2, fs.cache.itemCount(fs.directory))
        assertTrue(fs.reads.isEmpty())
        assertEquals(2, fs.cache.load(fs.directory).entries.size)
        assertEquals(2, fs.cache.itemCount(fs.directory))
        assertEquals(1, fs.listings)
        assertEquals(listOf("a.srt", "b.srt"), fs.reads)
    }

    @Test fun `cached count peek never lists a directory and rejects invalidated membership`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt"); put("b.srt") }
        assertNull(fs.cache.peekItemCount(fs.directory.absolutePath))
        assertEquals(0, fs.listings)
        assertEquals(2, fs.cache.itemCount(fs.directory))
        assertEquals(2, fs.cache.peekItemCount(fs.directory.absolutePath))
        assertEquals(1, fs.listings)

        fs.cache.invalidate(fs.directory.absolutePath)
        assertNull(fs.cache.peekItemCount(fs.directory.absolutePath))
        assertEquals(1, fs.listings)
        assertTrue(fs.reads.isEmpty())
    }

    @Test fun `load then count and incremental membership do not enumerate again`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("old.srt"); put("keep.srt") }
        val first = fs.cache.load(fs.directory)
        assertEquals(2, fs.cache.itemCount(fs.directory))
        fs.files.remove("old.srt"); fs.put("renamed.srt")
        val second = fs.cache.update(fs.directory, setOf("old.srt", "renamed.srt"))
        assertEquals(listOf("keep.srt", "renamed.srt"), second.entries.map { it.file.name })
        assertSame(first.entries[1], second.entries[0])
        assertEquals(2, fs.cache.itemCount(fs.directory))
        assertNull(fs.attributes.peek(File(fs.directory, "old.srt").absolutePath))
        assertEquals(1, fs.listings)
        assertEquals(listOf("old.srt", "keep.srt", "old.srt", "renamed.srt"), fs.reads)
    }

    @Test fun `only changed entries are read and same attribute writes get a new version`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt"); put("b.srt") }
        val first = fs.cache.load(fs.directory)
        val second = fs.cache.update(fs.directory, setOf("b.srt"))
        assertSame(first.entries[0], second.entries[0])
        assertNotEquals(first.entries[1].cacheKey, second.entries[1].cacheKey)
        assertEquals(listOf("a.srt", "b.srt", "b.srt"), fs.reads)
        assertEquals(1, fs.listings)
    }

    @Test fun `observation gaps reconcile membership but retain unchanged media versions`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt"); put("old.srt") }
        val first = fs.cache.load(fs.directory)
        fs.cache.invalidate(fs.directory.absolutePath)
        fs.files.remove("old.srt"); fs.put("new.srt")
        val second = fs.cache.load(fs.directory)
        assertEquals(listOf("a.srt", "new.srt"), second.entries.map { it.file.name })
        assertSame(first.entries.first(), second.entries.first())
        assertNull(fs.attributes.get(File(fs.directory, "old.srt")))
        assertEquals(2, fs.listings)
        assertSame(second, fs.cache.load(fs.directory))
    }

    @Test fun `shared attribute invalidation updates an already cached directory snapshot`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt"); put("b.srt") }
        val first = fs.cache.load(fs.directory)
        fs.put("a.srt", size = 20L)
        fs.attributes.invalidate(File(fs.directory, "a.srt").absolutePath)
        val second = fs.cache.load(fs.directory)
        assertEquals(20L, second.entries.first().size)
        assertSame(first.entries[1], second.entries[1])
        assertEquals(listOf("a.srt", "b.srt", "a.srt"), fs.reads)
        assertEquals(1, fs.listings)
    }

    @Test fun `child membership refreshes its row and invalidates only that raw listing`() {
        val root = temporaryFolder.root
        val folder = File(root, "folder")
        val child = File(folder, "child.srt")
        val reads = mutableListOf<String>()
        var childSize = 10L
        var folderModified = 1000L
        var folderListings = 0
        val attributes = FileMetadataCache(reader = { file, revision ->
            reads += file.absolutePath
            BrowserFileMetadata(file, file == folder, if (file == child) childSize else 0L,
                if (file == folder) folderModified else 1000L, revision = revision)
        })
        val cache = BrowserDirectoryCache(attributes, listFiles = {
            if (it == root) listOf(folder) else listOf(child).also { folderListings++ }
        })
        cache.load(root)
        val oldChild = cache.load(folder).entries.single()
        childSize = 20L; folderModified = 2000L
        val updated = cache.update(root, setOf("folder"), setOf(folder.absolutePath))
        assertEquals(2000L, updated.entries.single().modifiedTimeMillis)
        assertEquals(2, reads.count { it == folder.absolutePath })
        assertNull(attributes.peek(child.absolutePath))
        val reloaded = cache.load(folder).entries.single()
        assertEquals(20L, reloaded.size)
        assertNotEquals(oldChild.cacheKey, reloaded.cacheKey)
        assertEquals(2, folderListings)
    }

    @Test fun `invalid relative event names cannot stat outside the directory`() {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt") }
        val first = fs.cache.load(fs.directory)
        val second = fs.cache.update(fs.directory, setOf("../outside", "sub/file", "..", "", "sub\\file"))
        assertEquals(first.entries, second.entries)
        assertEquals(listOf("a.srt"), fs.reads)
    }

    @Test fun `lifecycle invalidation never waits for a blocked path read`() {
        val root = temporaryFolder.root
        val file = File(root, "a.srt")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val cache = BrowserDirectoryCache(FileMetadataCache(reader = { entry, revision ->
            if (reads.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            BrowserFileMetadata(entry, false, 1L, 1000L, revision = revision)
        }), listFiles = { listOf(file) })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val load = executor.submit<BrowserDirectorySnapshot> { cache.load(root) }
            check(entered.await(5, TimeUnit.SECONDS))
            executor.submit { cache.invalidate(root.absolutePath) }.get(1, TimeUnit.SECONDS)
            release.countDown()
            val snapshot = load.get(5, TimeUnit.SECONDS)
            assertSame(snapshot.entries.single(), cache.load(root).entries.single())
            assertEquals(2, reads.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `count failure is cached independently of attributes`() {
        var listings = 0
        val cache = BrowserDirectoryCache(listFiles = { listings++; null })
        assertEquals(-1, cache.itemCount(temporaryFolder.root))
        assertEquals(-1, cache.itemCount(temporaryFolder.root))
        val snapshot = cache.load(temporaryFolder.root)
        assertTrue(snapshot.entries.isEmpty())
        assertFalse(snapshot.listingSucceeded)
        assertEquals(1, listings)
    }

    @Test fun `unreadable listing never invalidates previously known files as removals`() {
        val file = File(temporaryFolder.root, "keep.wav")
        var readable = true
        val attributes = FileMetadataCache(reader = { source, revision ->
            BrowserFileMetadata(source, false, 1L, 1000L, revision = revision)
        })
        val cache = BrowserDirectoryCache(attributes, listFiles = { if (readable) listOf(file) else null })
        val original = cache.load(temporaryFolder.root)
        readable = false
        cache.invalidate(temporaryFolder.root.path)
        val failed = cache.load(temporaryFolder.root)
        assertFalse(failed.listingSucceeded)
        assertSame(original.entries.single(), failed.entries.single())
        assertSame(original.entries.single(), attributes.get(file))
    }

    @Test fun `same stamp change still forces shared version after directory snapshot eviction`() {
        val root = temporaryFolder.root
        val first = File(root, "changed.wav")
        val keep = File(root, "keep.wav")
        val reads = mutableListOf<File>()
        val attributes = FileMetadataCache(reader = { file, revision ->
            reads += file
            BrowserFileMetadata(file, false, 1L, 1000L, revision = revision)
        })
        val cache = BrowserDirectoryCache(attributes,
            listFiles = { if (it == root) listOf(first, keep) else emptyList() }, maxDirectories = 1)
        val original = cache.load(root)
        cache.load(File(root, "other"))
        val changed = cache.update(root, setOf(first.name))
        assertNotEquals(original.entries.first().cacheKey, changed.entries.first().cacheKey)
        assertSame(original.entries[1], changed.entries[1])
        assertEquals(listOf(first, keep, first), reads)
    }
}
