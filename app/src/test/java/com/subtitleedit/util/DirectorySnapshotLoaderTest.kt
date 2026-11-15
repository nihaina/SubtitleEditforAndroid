package com.subtitleedit.util

import com.subtitleedit.model.BrowserFileMetadata
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectorySnapshotLoaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private class Filesystem(val directory: File) {
        private val files = linkedMapOf<String, BrowserFileMetadata>()
        val reads = CopyOnWriteArrayList<String>()
        val ioThreads = CopyOnWriteArrayList<Thread>()
        val listings = AtomicInteger()
        var onRead: ((String) -> Unit)? = null
        val cache = BrowserDirectoryCache(
            attributes = FileMetadataCache(reader = { file, revision ->
                ioThreads += Thread.currentThread()
                reads += file.name
                val metadata = synchronized(files) { files[file.name] }
                onRead?.invoke(file.name)
                metadata?.copy(file = file, revision = revision)
            }),
            listFiles = {
                ioThreads += Thread.currentThread()
                listings.incrementAndGet()
                synchronized(files) { files.keys.map { File(directory, it) } }
            }
        )

        fun put(name: String, size: Long = 1L) = synchronized(files) {
            files[name] = BrowserFileMetadata(File(directory, name), false, size, 1000L)
        }

        fun remove(name: String) = synchronized(files) { files.remove(name) }
    }

    @Test
    fun `repeated initial loads share one scan and preserve scroll restore request`() = runBlocking {
        val callerThread = Thread.currentThread()
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt") }
        val reading = CompletableDeferred<Unit>()
        val finishRead = CountDownLatch(1)
        fs.onRead = {
            reading.complete(Unit)
            check(finishRead.await(5, TimeUnit.SECONDS))
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val owner = Job()
            val results = Channel<Pair<BrowserDirectorySnapshot, Boolean>>(Channel.UNLIMITED)
            val loader = DirectorySnapshotLoader(
                CoroutineScope(coroutineContext + owner), fs.cache,
                { snapshot, restore -> results.trySend(snapshot to restore) }, worker
            )
            try {
                loader.load(fs.directory)
                withTimeout(5000) { reading.await() }
                loader.load(fs.directory, restoreScrollPosition = true)
                loader.load(fs.directory)
                finishRead.countDown()

                val result = withTimeout(5000) { results.receive() }
                assertTrue(result.second)
                assertEquals(listOf("a.srt"), result.first.entries.map { it.file.name })
                assertEquals(1, fs.listings.get())
                assertEquals(listOf("a.srt"), fs.reads)
                assertTrue(results.tryReceive().isFailure)
                assertTrue(fs.ioThreads.all { it !== callerThread })
            } finally {
                finishRead.countDown()
                owner.cancelAndJoin()
            }
        }
    }

    @Test
    fun `ordered change batches keep every delta without rescanning unaffected entries`() = runBlocking {
        val callerThread = Thread.currentThread()
        val fs = Filesystem(temporaryFolder.root).apply { put("old.srt"); put("keep.srt") }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val owner = Job()
            val results = Channel<BrowserDirectorySnapshot>(Channel.UNLIMITED)
            val loader = DirectorySnapshotLoader(
                CoroutineScope(coroutineContext + owner), fs.cache,
                { snapshot, _ -> results.trySend(snapshot) }, worker
            )
            try {
                loader.load(fs.directory)
                val initial = withTimeout(5000) { results.receive() }
                val unchanged = initial.entries.single { it.file.name == "keep.srt" }
                fs.put("first.srt", 10L)
                fs.put("second.srt", 20L)
                fs.remove("old.srt")
                loader.update(DirectoryChanges(fs.directory.absolutePath, setOf("first.srt")))
                loader.update(DirectoryChanges(fs.directory.absolutePath, setOf("second.srt")))
                loader.update(DirectoryChanges(fs.directory.absolutePath, setOf("old.srt")))

                val first = withTimeout(5000) { results.receive() }
                val second = withTimeout(5000) { results.receive() }
                val third = withTimeout(5000) { results.receive() }
                assertEquals(setOf("old.srt", "keep.srt", "first.srt"), first.names())
                assertEquals(setOf("old.srt", "keep.srt", "first.srt", "second.srt"), second.names())
                assertEquals(setOf("keep.srt", "first.srt", "second.srt"), third.names())
                assertSame(unchanged, third.entries.single { it.file.name == "keep.srt" })
                assertEquals(listOf("old.srt", "keep.srt", "first.srt", "second.srt", "old.srt"), fs.reads)
                assertEquals(1, fs.listings.get())
                assertTrue(fs.ioThreads.all { it !== callerThread })
            } finally {
                owner.cancelAndJoin()
            }
        }
    }

    @Test
    fun `invalidation survives cancelled Activity scope and reloads shared cache`() = runBlocking {
        val callerThread = Thread.currentThread()
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt") }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val firstOwner = Job()
            val firstResults = Channel<BrowserDirectorySnapshot>(Channel.UNLIMITED)
            val firstLoader = DirectorySnapshotLoader(
                CoroutineScope(coroutineContext + firstOwner), fs.cache,
                { snapshot, _ -> firstResults.trySend(snapshot) }, worker
            )
            firstLoader.load(fs.directory)
            val initial = withTimeout(5000) { firstResults.receive() }
            firstOwner.cancelAndJoin()

            fs.put("a.srt", 50L)
            fs.put("new.srt")
            firstLoader.invalidate(fs.directory.absolutePath)
            val nextOwner = Job()
            val nextResults = Channel<BrowserDirectorySnapshot>(Channel.UNLIMITED)
            val nextLoader = DirectorySnapshotLoader(
                CoroutineScope(coroutineContext + nextOwner), fs.cache,
                { snapshot, _ -> nextResults.trySend(snapshot) }, worker
            )
            try {
                nextLoader.load(fs.directory)
                val refreshed = withTimeout(5000) { nextResults.receive() }
                assertEquals(setOf("a.srt", "new.srt"), refreshed.names())
                val changed = refreshed.entries.single { it.file.name == "a.srt" }
                assertEquals(50L, changed.size)
                assertNotEquals(initial.entries.single().cacheKey, changed.cacheKey)
                assertEquals(2, fs.listings.get())
                assertTrue(fs.ioThreads.all { it !== callerThread })
            } finally {
                nextOwner.cancelAndJoin()
            }
        }
    }

    @Test
    fun `same path load after watch gap is not absorbed by an older in flight scan`() = runBlocking {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt") }
        val readingOldAttributes = CompletableDeferred<Unit>()
        val finishOldRead = CountDownLatch(1)
        fs.onRead = {
            if (!readingOldAttributes.isCompleted) {
                readingOldAttributes.complete(Unit)
                check(finishOldRead.await(5, TimeUnit.SECONDS))
            }
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val owner = Job()
            val results = Channel<BrowserDirectorySnapshot>(Channel.UNLIMITED)
            val loader = DirectorySnapshotLoader(
                CoroutineScope(coroutineContext + owner), fs.cache,
                { snapshot, _ -> results.trySend(snapshot) }, worker
            )
            try {
                loader.load(fs.directory)
                withTimeout(5000) { readingOldAttributes.await() }
                // The old scan has already read a.srt. The observer is then stopped,
                // external files change, and the user returns before that scan finishes.
                fs.put("a.srt", 70L)
                fs.put("new.srt")
                loader.invalidate(fs.directory.absolutePath)
                loader.load(fs.directory)
                finishOldRead.countDown()

                val refreshed = withTimeout(5000) {
                    var snapshot = results.receive()
                    while (snapshot.names() != setOf("a.srt", "new.srt") ||
                        snapshot.entries.single { it.file.name == "a.srt" }.size != 70L) {
                        snapshot = results.receive()
                    }
                    snapshot
                }
                assertEquals(setOf("a.srt", "new.srt"), refreshed.names())
                assertEquals(2, fs.listings.get())
            } finally {
                finishOldRead.countDown()
                owner.cancelAndJoin()
            }
        }
    }

    @Test
    fun `obsolete queued directories and change batches do not scan before latest navigation`() = runBlocking {
        val callerThread = Thread.currentThread()
        val first = temporaryFolder.newFolder("first")
        val intermediateOne = temporaryFolder.newFolder("intermediate-one")
        val intermediateTwo = temporaryFolder.newFolder("intermediate-two")
        val latest = temporaryFolder.newFolder("latest")
        val listingFirst = CompletableDeferred<Unit>()
        val finishFirstListing = CountDownLatch(1)
        val listedDirectories = CopyOnWriteArrayList<String>()
        val readPaths = CopyOnWriteArrayList<String>()
        val ioThreads = CopyOnWriteArrayList<Thread>()
        val cache = BrowserDirectoryCache(
            attributes = FileMetadataCache(reader = { file, revision ->
                ioThreads += Thread.currentThread()
                readPaths += file.absolutePath
                BrowserFileMetadata(file, false, 1L, 1000L, revision = revision)
            }),
            listFiles = { directory ->
                ioThreads += Thread.currentThread()
                listedDirectories += directory.absolutePath
                if (directory == first) {
                    listingFirst.complete(Unit)
                    check(finishFirstListing.await(5, TimeUnit.SECONDS))
                }
                listOf(File(directory, "entry.srt"))
            }
        )
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val owner = Job()
            val results = Channel<BrowserDirectorySnapshot>(Channel.UNLIMITED)
            val loader = DirectorySnapshotLoader(
                CoroutineScope(coroutineContext + owner), cache,
                { snapshot, _ -> results.trySend(snapshot) }, worker
            )
            try {
                loader.load(first)
                withTimeout(5000) { listingFirst.await() }
                loader.update(DirectoryChanges(first.absolutePath, setOf("stale.srt")))
                loader.invalidate(first.absolutePath)
                loader.load(intermediateOne)
                loader.update(DirectoryChanges(intermediateOne.absolutePath, setOf("stale.srt")))
                loader.invalidate(intermediateOne.absolutePath)
                loader.load(intermediateTwo)
                loader.invalidate(intermediateTwo.absolutePath)
                loader.load(latest)
                finishFirstListing.countDown()

                val displayed = withTimeout(5000) { results.receive() }
                assertEquals(latest, displayed.directory)
                assertEquals(listOf(first.absolutePath, latest.absolutePath), listedDirectories)
                assertEquals(
                    listOf(File(first, "entry.srt").absolutePath, File(latest, "entry.srt").absolutePath),
                    readPaths
                )
                assertTrue(results.tryReceive().isFailure)
                assertTrue(ioThreads.all { it !== callerThread })
            } finally {
                finishFirstListing.countDown()
                owner.cancelAndJoin()
            }
        }
    }

    @Test
    fun `a shared cache change is not absorbed by an older in flight load`() = runBlocking {
        val fs = Filesystem(temporaryFolder.root).apply { put("a.srt") }
        val reading = CompletableDeferred<Unit>()
        val finishRead = CountDownLatch(1)
        fs.onRead = {
            if (!reading.isCompleted) {
                reading.complete(Unit)
                check(finishRead.await(5, TimeUnit.SECONDS))
            }
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val owner = Job()
            val results = Channel<BrowserDirectorySnapshot>(Channel.UNLIMITED)
            val loader = DirectorySnapshotLoader(CoroutineScope(coroutineContext + owner), fs.cache,
                { snapshot, _ -> results.trySend(snapshot) }, worker)
            try {
                loader.load(fs.directory)
                withTimeout(5000) { reading.await() }
                fs.put("new.srt")
                // The process observer updates the shared cache, while the local
                // observation generation remains unchanged.
                fs.cache.invalidate(fs.directory.absolutePath)
                loader.load(fs.directory)
                finishRead.countDown()
                val latest = withTimeout(5000) {
                    var snapshot = results.receive()
                    while ("new.srt" !in snapshot.names()) snapshot = results.receive()
                    snapshot
                }
                assertEquals(setOf("a.srt", "new.srt"), latest.names())
                assertEquals(2, fs.listings.get())
            } finally {
                finishRead.countDown()
                owner.cancelAndJoin()
            }
        }
    }

    private fun BrowserDirectorySnapshot.names(): Set<String> = entries.map { it.file.name }.toSet()
}
