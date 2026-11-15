package com.subtitleedit.util

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedFileResourceCacheTest {
    @Test
    fun `concurrent requests share work and retain the result`() = runBlocking {
        val cache = SharedFileResourceCache<String>(100)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val first = async {
            cache.get("/video.mp4", "one") {
                reads.incrementAndGet()
                entered.complete(Unit)
                release.await()
                "preview"
            }
        }
        entered.await()
        val second = async { cache.get("/video.mp4", "one") { error("Duplicate file read") } }
        release.complete(Unit)
        assertEquals("preview", first.await())
        assertEquals("preview", second.await())
        assertEquals("preview", cache.get("/video.mp4", "one") { error("Repeated file read") })
        assertEquals(1, reads.get())
    }

    @Test
    fun `recycled row cancellation leaves shared work usable`() = runBlocking {
        val cache = SharedFileResourceCache<String>(100)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val row = launch {
            cache.get("/image.png", "one") {
                entered.complete(Unit)
                release.await()
                "image"
            }
        }
        entered.await()
        row.cancelAndJoin()
        val nextRow = async { cache.get("/image.png", "one") { error("Cancelled shared work") } }
        release.complete(Unit)
        assertEquals("image", nextRow.await())
    }

    @Test
    fun `changed file invalidates only its resources and old work cannot refill cache`() = runBlocking {
        val cache = SharedFileResourceCache<String>(100)
        cache.get("/unchanged.srt", "one") { "unchanged" }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stale = async {
            cache.get("/changed.mp4", "one") {
                entered.complete(Unit)
                release.await()
                "stale"
            }
        }
        entered.await()
        cache.invalidate("/changed.mp4")
        assertEquals("fresh", cache.get("/changed.mp4", "one") { "fresh" })
        release.complete(Unit)
        assertEquals("stale", stale.await())
        assertEquals("fresh", cache.peek("/changed.mp4", "one"))
        assertEquals("unchanged", cache.peek("/unchanged.srt", "one"))
    }

    @Test
    fun `new revision reloads even when file attributes did not change`() = runBlocking {
        val cache = SharedFileResourceCache<String>(100)
        assertEquals("before", cache.get("/same-size.mp4", "revision-1") { "before" })
        assertEquals("after", cache.get("/same-size.mp4", "revision-2") { "after" })
    }

    @Test
    fun `clear detaches pending requests`() = runBlocking {
        val cache = SharedFileResourceCache<String>(100)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async {
            cache.get("/pending.mp4", "one") {
                entered.complete(Unit)
                release.await()
                "old"
            }
        }
        entered.await()
        cache.clear()
        release.complete(Unit)
        pending.await()
        assertNull(cache.peek("/pending.mp4", "one"))
    }

    @Test
    fun `weighted resources and empty results have bounded retention`() = runBlocking {
        val cache = SharedFileResourceCache<String>(maximumWeight = 5, maximumEntries = 2, weightOf = String::length)
        cache.get("/a", "one") { "123" }
        cache.get("/b", "one") { "456" }
        assertNull(cache.peek("/a", "one"))
        cache.get("/c", "one") { "" }
        cache.get("/d", "one") { "" }
        assertNull(cache.peek("/b", "one"))
        assertEquals("", cache.peek("/c", "one"))
        assertEquals("", cache.peek("/d", "one"))
    }

    @Test
    fun `different resource caches share one decoder limit`() = runBlocking {
        val permits = Semaphore(1)
        val previews = SharedFileResourceCache<String>(100, permits = permits)
        val counts = SharedFileResourceCache<Int>(100, permits = permits)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            previews.get("/video", "one") {
                entered.complete(Unit)
                release.await()
                "preview"
            }
        }
        entered.await()
        val second = async { counts.get("/folder", "one") { 42 } }
        assertEquals(0, permits.availablePermits)
        release.complete(Unit)
        assertEquals("preview", first.await())
        assertEquals(42, second.await())
    }

    @Test
    fun `recycled rows discard queued reads that nobody still needs`() = runBlocking {
        val permits = Semaphore(1, acquiredPermits = 1)
        val cache = SharedFileResourceCache<String>(100, permits = permits)
        val obsoleteReads = AtomicInteger()
        val obsoleteRow = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.get("/offscreen.mp4", "one") {
                obsoleteReads.incrementAndGet()
                "obsolete"
            }
        }
        obsoleteRow.cancelAndJoin()
        permits.release()
        assertEquals("fresh", cache.get("/offscreen.mp4", "one") { "fresh" })
        assertEquals(0, obsoleteReads.get())
    }

    @Test
    fun `failed read releases its request so a later caller can retry`() = runBlocking {
        val cache = SharedFileResourceCache<String>(100)
        try {
            cache.get("/temporary-error.mp4", "one") { error("Temporary error") }
            error("Expected read failure")
        } catch (_: IllegalStateException) {
            // Failed work is not retained as an in-flight request.
        }
        assertEquals("recovered", cache.get("/temporary-error.mp4", "one") { "recovered" })
    }
}
