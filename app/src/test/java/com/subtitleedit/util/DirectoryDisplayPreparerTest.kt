package com.subtitleedit.util

import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryDisplayPreparerTest {
    private fun metadata(name: String, directory: Boolean = false, regular: Boolean = !directory): BrowserFileMetadata {
        val file = object : File(name) {
            override fun isFile(): Boolean = error("Unexpected filesystem query")
            override fun isDirectory(): Boolean = error("Unexpected filesystem query")
            override fun length(): Long = error("Unexpected filesystem query")
            override fun lastModified(): Long = error("Unexpected filesystem query")
        }
        return BrowserFileMetadata(file, directory, 10L, 1000L, isRegularFile = regular)
    }

    private fun options(query: String = "", descending: Boolean = false, showAll: Boolean = false) = DirectoryDisplayOptions(
        query, showHiddenFiles = false, showAllFileTypes = showAll,
        FileSortField.NAME, if (descending) FileSortDirection.DESCENDING else FileSortDirection.ASCENDING
    )

    @Test fun `worker uses cached types and preserves browser filtering and ordering`() = runBlocking {
        val caller = Thread.currentThread()
        val folder = metadata("z-folder", directory = true)
        val subtitle = metadata("a.srt")
        val archive = metadata("b.zip")
        val audio = metadata("c.MP3")
        val video = metadata("d.mp4")
        val snapshot = BrowserDirectorySnapshot(File("browser"), listOf(
            metadata("unknown.bin"), metadata(".hidden.srt"), video, audio, archive, subtitle, folder,
            metadata("special.srt", regular = false)
        ))
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val owner = Job()
            val results = Channel<PreparedDirectoryDisplay>(Channel.UNLIMITED)
            val preparer = DirectoryDisplayPreparer(CoroutineScope(coroutineContext + owner), { result, _ ->
                assertSame(caller, Thread.currentThread())
                results.trySend(result)
            }, worker)
            try {
                preparer.prepare(snapshot, options())
                val visible = withTimeout(5000) { results.receive() }
                assertSame(snapshot, visible.snapshot)
                assertEquals(listOf(folder.file, subtitle.file, archive.file, audio.file, video.file), visible.files)
                assertSame(subtitle, visible.metadata[subtitle.path])
                assertEquals(listOf(folder.file), visible.childDirectories)

                preparer.prepare(snapshot, options(query = "SRT", showAll = true))
                val searched = withTimeout(5000) { results.receive() }
                assertEquals(listOf(subtitle.file), searched.files)
            } finally {
                owner.cancelAndJoin()
            }
        }
    }

    @Test fun `latest sort replaces an unfinished preparation without losing scroll restoration`() = runBlocking {
        val caller = Thread.currentThread()
        val preparingFirst = CompletableDeferred<Unit>()
        val finishFirst = CountDownLatch(1)
        val workerThreads = CopyOnWriteArrayList<Thread>()
        val snapshot = BrowserDirectorySnapshot(File("browser"), listOf(metadata("a.srt"), metadata("b.srt")))
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val owner = Job()
            val results = Channel<Pair<PreparedDirectoryDisplay, Boolean>>(Channel.UNLIMITED)
            val preparer = DirectoryDisplayPreparer(
                CoroutineScope(coroutineContext + owner), { result, restore -> results.trySend(result to restore) }, worker,
                includeFile = { _, _ ->
                    workerThreads += Thread.currentThread()
                    if (!preparingFirst.isCompleted) {
                        preparingFirst.complete(Unit)
                        check(finishFirst.await(5, TimeUnit.SECONDS))
                    }
                    true
                }
            )
            try {
                preparer.prepare(snapshot, options(), restoreScroll = true)
                withTimeout(5000) { preparingFirst.await() }
                preparer.prepare(snapshot, options(descending = true))
                finishFirst.countDown()

                val latest = withTimeout(5000) { results.receive() }
                assertEquals(listOf("b.srt", "a.srt"), latest.first.files.map { it.name })
                assertTrue(latest.second)
                assertTrue(results.tryReceive().isFailure)
                assertTrue(workerThreads.all { it !== caller })
            } finally {
                finishFirst.countDown()
                owner.cancelAndJoin()
            }
        }
    }

    @Test fun `navigation cancellation never transfers an old directory scroll request`() = runBlocking {
        val first = BrowserDirectorySnapshot(File("first"), listOf(metadata("old.srt")))
        val latest = BrowserDirectorySnapshot(File("latest"), listOf(metadata("new.srt")))
        val owner = Job()
        val results = Channel<Pair<PreparedDirectoryDisplay, Boolean>>(Channel.UNLIMITED)
        val preparer = DirectoryDisplayPreparer(CoroutineScope(coroutineContext + owner), { result, restore ->
            results.trySend(result to restore)
        })
        try {
            preparer.prepare(first, options(), restoreScroll = true)
            preparer.cancel()
            preparer.prepare(latest, options())
            val result = withTimeout(5000) { results.receive() }
            assertSame(latest, result.first.snapshot)
            assertEquals(false, result.second)
            assertTrue(results.tryReceive().isFailure)
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test fun `unchanged snapshot and options reuse prepared rows without filtering again`() = runBlocking {
        val snapshot = BrowserDirectorySnapshot(File("browser"), listOf(metadata("a.srt"), metadata("b.srt")))
        val filteringCalls = AtomicInteger()
        val owner = Job()
        val results = Channel<PreparedDirectoryDisplay>(Channel.UNLIMITED)
        val preparer = DirectoryDisplayPreparer(
            CoroutineScope(coroutineContext + owner), { result, _ -> results.trySend(result) },
            includeFile = { _, _ -> filteringCalls.incrementAndGet(); true }
        )
        try {
            preparer.prepare(snapshot, options())
            val first = withTimeout(5000) { results.receive() }
            preparer.cancel()
            preparer.prepare(snapshot, options(), restoreScroll = true)
            val returning = withTimeout(5000) { results.receive() }
            assertSame(first, returning)
            assertEquals(2, filteringCalls.get())
        } finally {
            owner.cancelAndJoin()
        }
    }
}
