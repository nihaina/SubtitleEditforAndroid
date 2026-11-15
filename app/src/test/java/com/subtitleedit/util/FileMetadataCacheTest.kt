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

class FileMetadataCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private class Filesystem(val root: File) {
        val values = mutableMapOf<String, BrowserFileMetadata>()
        val reads = mutableListOf<String>()
        val cache = FileMetadataCache(reader = { file, revision ->
            reads += file.name
            values[file.name]?.copy(file = file, revision = revision)
        })
        fun put(name: String, size: Long = 1L): File = File(root, name).also {
            values[name] = BrowserFileMetadata(it, false, size, 1000L)
        }
    }

    @Test fun `screens and scans share one version and no additional stat on cache hits`() {
        val fs = Filesystem(temporaryFolder.root)
        val file = fs.put("video.mp4")
        val directoryValue = fs.cache.get(file)
        repeat(10) { assertSame(directoryValue, fs.cache.get(File(file.path))) }
        assertEquals(listOf("video.mp4"), fs.reads)
    }

    @Test fun `validation after a page switch preserves unchanged media keys`() {
        val fs = Filesystem(temporaryFolder.root)
        val file = fs.put("audio.wav")
        val initial = fs.cache.get(file)
        fs.cache.requireValidation(fs.root.path, recursive = true)
        assertSame(initial, fs.cache.get(file))
        assertSame(initial, fs.cache.refresh(file))
        assertEquals(listOf("audio.wav", "audio.wav", "audio.wav"), fs.reads)
    }

    @Test fun `actual change invalidates same size same timestamp once and leaves siblings cached`() {
        val fs = Filesystem(temporaryFolder.root)
        val changed = fs.put("changed.wav")
        val sibling = fs.put("keep.wav")
        val old = fs.cache.get(changed)!!
        val keep = fs.cache.get(sibling)
        fs.cache.invalidate(changed.path)
        assertNull(fs.cache.peek(changed.path))
        val updated = fs.cache.get(changed)!!
        assertNotEquals(old.cacheKey, updated.cacheKey)
        assertSame(keep, fs.cache.get(sibling))
        assertSame(updated, fs.cache.get(changed))
        assertEquals(listOf("changed.wav", "keep.wav", "changed.wav"), fs.reads)
    }

    @Test fun `recursive validation uses path boundaries and updates changed attributes`() {
        val root = temporaryFolder.root
        val folder = File(root, "folder")
        val reads = mutableListOf<String>()
        var changedSize = 1L
        val child = File(folder, "song.wav")
        val unrelated = File(root, "folder-other/song.wav")
        val cache = FileMetadataCache(reader = { file, revision ->
            reads += file.path
            BrowserFileMetadata(file, false, if (file == child) changedSize else 1L, 1000L, revision = revision)
        })
        val old = cache.get(child)!!
        val other = cache.get(unrelated)
        changedSize = 20L
        cache.requireValidation(folder.path, recursive = true)
        val updated = cache.get(child)!!
        assertEquals(20L, updated.size)
        assertNotEquals(old.revision, updated.revision)
        assertSame(other, cache.get(unrelated))
        assertEquals(listOf(child.path, unrelated.path, child.path), reads)
    }

    @Test fun `missing paths are cached and retried only when signalled`() {
        val fs = Filesystem(temporaryFolder.root)
        val file = File(fs.root, "new.wav")
        repeat(3) { assertNull(fs.cache.get(file)) }
        fs.put("new.wav")
        fs.cache.invalidate(file.path)
        val created = fs.cache.get(file)!!
        fs.values.remove("new.wav")
        assertNull(fs.cache.refresh(file, forceRevision = true))
        assertNull(fs.cache.get(file))
        assertEquals(3, fs.reads.size)
        assertTrue(created.revision > 0L)
    }

    @Test fun `same path concurrent misses are coalesced while another path does not wait`() {
        val root = temporaryFolder.root
        val first = File(root, "first.wav")
        val second = File(root, "second.wav")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val cache = FileMetadataCache(reader = { file, revision ->
            reads.incrementAndGet()
            if (file == first) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            BrowserFileMetadata(file, false, 1L, 1000L, revision = revision)
        })
        val executor = Executors.newFixedThreadPool(3)
        try {
            val initial = executor.submit<BrowserFileMetadata?> { cache.get(first) }
            check(entered.await(5, TimeUnit.SECONDS))
            val duplicate = executor.submit<BrowserFileMetadata?> { cache.get(first) }
            val independent = executor.submit<BrowserFileMetadata?> { cache.get(second) }
            assertNotNull(independent.get(1, TimeUnit.SECONDS))
            release.countDown()
            assertSame(initial.get(5, TimeUnit.SECONDS), duplicate.get(5, TimeUnit.SECONDS))
            assertEquals(2, reads.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `invalidation during a blocked read cannot publish a pre-event version as current`() {
        val file = File(temporaryFolder.root, "song.wav")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val cache = FileMetadataCache(reader = { source, revision ->
            val number = reads.incrementAndGet()
            if (number == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            BrowserFileMetadata(source, false, number.toLong(), 1000L, revision = revision)
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val reading = executor.submit<BrowserFileMetadata?> { cache.get(file) }
            check(entered.await(5, TimeUnit.SECONDS))
            executor.submit { cache.invalidate(file.path) }.get(1, TimeUnit.SECONDS)
            release.countDown()
            val result = reading.get(5, TimeUnit.SECONDS)!!
            assertEquals(2L, result.size)
            assertSame(result, cache.get(file))
            assertEquals(2, reads.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `bounded cache evicts old entries and clear discards retained snapshots`() {
        val reads = AtomicInteger()
        val cache = FileMetadataCache(reader = { file, revision ->
            reads.incrementAndGet()
            BrowserFileMetadata(file, false, 1L, 1000L, revision = revision)
        }, maxEntries = 2)
        val first = File(temporaryFolder.root, "first")
        val before = cache.get(first)
        cache.get(File(temporaryFolder.root, "second"))
        cache.get(File(temporaryFolder.root, "third"))
        assertNull(cache.peek(first.path))
        assertNotEquals(before?.revision, cache.get(first)?.revision)
        cache.clear()
        assertNull(cache.peek(first.path))
        cache.get(first)
        assertEquals(5, reads.get())
    }

    @Test fun `live invalidation sequence never advances for reads or unchanged refreshes`() {
        val fs = Filesystem(temporaryFolder.root)
        val file = fs.put("song.wav")
        fs.cache.get(file)
        fs.cache.refresh(file)
        assertEquals(0L, fs.cache.invalidationRevision.value)
        fs.cache.invalidate(file.path)
        assertEquals(1L, fs.cache.invalidationRevision.value)
        fs.cache.get(file)
        assertEquals(1L, fs.cache.invalidationRevision.value)
        fs.cache.requireValidation(file.path)
        fs.cache.refresh(file, forceRevision = true)
        assertEquals(3L, fs.cache.invalidationRevision.value)
        fs.cache.clear()
        assertEquals(4L, fs.cache.invalidationRevision.value)
    }
}
