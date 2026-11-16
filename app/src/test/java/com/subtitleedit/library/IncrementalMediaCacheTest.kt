package com.subtitleedit.library

import com.subtitleedit.model.BrowserFileMetadata
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class IncrementalMediaCacheTest {
    private fun metadata(path: String = "/music/song.wav", revision: Long = 1L) =
        BrowserFileMetadata(File(path), false, 100L, 1000L, revision = revision)

    private class Reader {
        val requests = mutableListOf<MissingMediaFields>()
        val knownCovers = mutableListOf<ByteArray?>()
        var empty = false
        val cache = IncrementalMediaCache<String, String>(
            emptyTags = "",
            artworkWeight = String::length,
            load = { _, missing, cover ->
                requests += missing
                knownCovers += cover
                LoadedMediaFields(
                    durationMs = if (missing.duration && !empty) 123_000L else 0L,
                    tags = if (missing.tags && !empty) "Title and artist" else null,
                    cover = if (missing.cover && !empty) byteArrayOf(1, 2, 3) else cover,
                    artwork = if (missing.artwork != null && !empty) "bitmap-${missing.artwork.size}" else null
                )
            }
        )
    }

    @Test fun `directory preview then scan only reads missing tags`() {
        val reader = Reader()
        val metadata = metadata()
        val preview = reader.cache.get(metadata, needDuration = true, artwork = MediaArtworkRequest(120, true))
        val scan = reader.cache.get(metadata, needDuration = true, needTags = true)

        assertEquals(123_000L, preview.durationMs)
        assertEquals(123_000L, scan.durationMs)
        assertEquals("Title and artist", scan.tags)
        assertEquals(MissingMediaFields(true, false, true, MediaArtworkRequest(120, true)), reader.requests[0])
        assertEquals(MissingMediaFields(false, true, false, null), reader.requests[1])
        reader.cache.get(metadata, needDuration = true, needTags = true, artwork = MediaArtworkRequest(120, true))
        assertEquals(2, reader.requests.size)
    }

    @Test fun `scan then directory preview reads artwork without repeating duration or tags`() {
        val reader = Reader()
        val metadata = metadata()
        reader.cache.get(metadata, needDuration = true, needTags = true)
        val preview = reader.cache.get(metadata, needDuration = true, artwork = MediaArtworkRequest(120, true))

        assertEquals(123_000L, preview.durationMs)
        assertEquals("bitmap-120", preview.artwork)
        assertEquals(MissingMediaFields(true, true, false, null), reader.requests[0])
        assertEquals(MissingMediaFields(false, false, true, MediaArtworkRequest(120, true)), reader.requests[1])
    }

    @Test fun `another artwork size reuses encoded cover and duration`() {
        val reader = Reader()
        val metadata = metadata()
        reader.cache.get(metadata, needDuration = true, artwork = MediaArtworkRequest(120, true))
        reader.cache.get(metadata, needDuration = true, artwork = MediaArtworkRequest(480, false))

        assertEquals(MissingMediaFields(false, false, false, MediaArtworkRequest(480, false)), reader.requests[1])
        assertArrayEquals(byteArrayOf(1, 2, 3), reader.knownCovers[1])
    }

    @Test fun `validated persistent duration avoids another duration extraction after process restart`() {
        val reader = Reader()
        reader.cache.seedDuration(metadata(), 90_000L)
        val preview = reader.cache.get(metadata(), needDuration = true, artwork = MediaArtworkRequest(120, true))
        assertEquals(90_000L, preview.durationMs)
        assertFalse(reader.requests.single().duration)
        assertFalse(reader.requests.single().tags)
        reader.cache.get(metadata(), needDuration = true, needTags = true)
        assertEquals(MissingMediaFields(false, true, false, null), reader.requests.last())
    }

    @Test fun `blank tags missing artwork and zero duration are cached results`() {
        val reader = Reader().apply { empty = true }
        val metadata = metadata()
        repeat(4) {
            val result = reader.cache.get(metadata, needDuration = true, needTags = true,
                artwork = MediaArtworkRequest(120, false))
            assertEquals("", result.tags)
            assertNull(result.artwork)
            assertEquals(0L, result.durationMs)
        }
        assertEquals(1, reader.requests.size)
    }

    @Test fun `preview peek reuses only a completed matching version without loading`() {
        val reader = Reader().apply { empty = true }
        val request = MediaArtworkRequest(120, false)
        val source = metadata()
        assertNull(reader.cache.peekPreview(source, request))
        assertTrue(reader.requests.isEmpty())

        reader.cache.get(source, needDuration = true, artwork = request)
        assertEquals(CachedMediaPreview<String>(0L, null), reader.cache.peekPreview(source, request))
        assertNull(reader.cache.peekPreview(source.copy(revision = 2L), request))
        assertNull(reader.cache.peekPreview(metadata("/another.wav"), request))
        assertNull(reader.cache.peekPreview(source, request.copy(size = 480)))
        assertEquals(1, reader.requests.size)
    }

    @Test fun `preview peek never waits for an in flight metadata read`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = IncrementalMediaCache<String, String>("", { _, _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            LoadedMediaFields(1000L, "tags", artwork = "image")
        }, String::length)
        val pool = Executors.newFixedThreadPool(2)
        val source = metadata()
        val request = MediaArtworkRequest(120, false)
        try {
            val load = pool.submit { cache.get(source, needDuration = true, artwork = request) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(pool.submit<CachedMediaPreview<String>?> { cache.peekPreview(source, request) }
                .get(1, TimeUnit.SECONDS))
            release.countDown()
            load.get(5, TimeUnit.SECONDS)
            assertEquals(CachedMediaPreview(1000L, "image"), cache.peekPreview(source, request))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `same version concurrent consumers share one field load`() {
        val requests = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = IncrementalMediaCache<String, String>("", { _, _, _ ->
            requests.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            LoadedMediaFields(1000L, "tags", null, "image")
        }, String::length)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<CachedMediaFields<String, String>> {
                cache.get(metadata(), needDuration = true, needTags = true)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit<CachedMediaFields<String, String>> {
                cache.get(metadata(), needDuration = true, needTags = true)
            }
            release.countDown()
            assertEquals("tags", first.get(5, TimeUnit.SECONDS).tags)
            assertEquals("tags", second.get(5, TimeUnit.SECONDS).tags)
            assertEquals(1, requests.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `invalidated in flight result cannot overwrite a replacement with same version`() {
        val reads = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = IncrementalMediaCache<String, String>("", { _, _, _ ->
            if (reads.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                LoadedMediaFields(tags = "old")
            } else LoadedMediaFields(tags = "new")
        }, String::length)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val old = pool.submit<CachedMediaFields<String, String>> { cache.get(metadata(), needTags = true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            cache.invalidate(metadata().path)
            assertEquals("new", cache.get(metadata(), needTags = true).tags)
            release.countDown()
            assertEquals("old", old.get(5, TimeUnit.SECONDS).tags)
            assertEquals("new", cache.get(metadata(), needTags = true).tags)
            assertEquals(2, reads.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `capacity eviction cannot detach an active same-version request`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstReads = AtomicInteger()
        val cache = IncrementalMediaCache<String, String>("", { metadata, _, _ ->
            if (metadata.path.endsWith("first.wav")) {
                firstReads.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            LoadedMediaFields(tags = metadata.path)
        }, String::length, maximumEntries = 1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<CachedMediaFields<String, String>> {
                cache.get(metadata("/first.wav"), needTags = true)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            cache.get(metadata("/other.wav"), needTags = true)
            val same = pool.submit<CachedMediaFields<String, String>> {
                cache.get(metadata("/first.wav"), needTags = true)
            }
            release.countDown()
            assertEquals(metadata("/first.wav").path, first.get(5, TimeUnit.SECONDS).tags)
            assertEquals(metadata("/first.wav").path, same.get(5, TimeUnit.SECONDS).tags)
            assertEquals(1, firstReads.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `new file revision loads fields afresh while keeping old version separate`() {
        val reader = Reader()
        reader.cache.get(metadata(), needDuration = true, needTags = true)
        reader.cache.get(metadata(revision = 2L), needDuration = true, needTags = true)
        assertEquals(2, reader.requests.size)
        assertTrue(reader.requests.all { it.duration && it.tags })
    }

    @Test fun `artwork eviction does not discard text fields or reload the encoded cover`() {
        val requests = mutableListOf<MissingMediaFields>()
        val cache = IncrementalMediaCache<String, String>("", { _, missing, knownCover ->
            requests += missing
            LoadedMediaFields(1000L, "tags", knownCover ?: byteArrayOf(1), "img")
        }, String::length, maximumArtworkBytes = 3)
        cache.get(metadata(), needDuration = true, needTags = true, artwork = MediaArtworkRequest(120, true))
        cache.get(metadata(), needDuration = true, artwork = MediaArtworkRequest(480, true))
        cache.get(metadata(), needDuration = true, needTags = true, artwork = MediaArtworkRequest(120, true))

        assertEquals(MissingMediaFields(false, false, false, MediaArtworkRequest(120, true)), requests.last())
    }

    @Test fun `details are independently loaded once and do not request artwork`() {
        val reader = Reader()
        val detailsReads = AtomicInteger()
        repeat(3) { assertEquals("extra tags", reader.cache.details(metadata()) { detailsReads.incrementAndGet(); "extra tags" }) }
        assertEquals(1, detailsReads.get())
        assertTrue(reader.requests.isEmpty())
        reader.cache.clear()
        assertNull(reader.cache.details(metadata()) { null })
        assertNull(reader.cache.details(metadata()) { error("Missing details are already loaded") })
    }

    @Test fun `native concurrency is bounded across different media paths`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val reads = Collections.synchronizedList(mutableListOf<String>())
        val cache = IncrementalMediaCache<String, String>("", { metadata, _, _ ->
            val active = running.incrementAndGet()
            maximum.updateAndGet { maxOf(it, active) }
            reads += metadata.path
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            running.decrementAndGet()
            LoadedMediaFields(tags = "tags")
        }, String::length)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val tasks = (0..2).map { index -> pool.submit { cache.get(metadata("/$index.wav"), needTags = true) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(2, reads.size)
            release.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(2, maximum.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
