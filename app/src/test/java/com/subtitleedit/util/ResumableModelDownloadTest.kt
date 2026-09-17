package com.subtitleedit.util

import com.subtitleedit.work.ModelDownloadRetryPolicy
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableModelDownloadTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    @get:Rule val server = MockWebServer()
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
    private val content = java.util.Random(42L).let { random ->
        CharArray(256 * 1024) { ('a'.code + random.nextInt(26)).toChar() }.concatToString()
    }
    private val etag = "\"version-1\""
    private val destination get() = File(temporaryFolder.root, "model.onnx")
    private val part get() = File(temporaryFolder.root, "model.onnx.part")
    private val metadata get() = File(temporaryFolder.root, "model.onnx.part.properties")
    private val url get() = server.url("/model").toString()

    @Test
    fun interruptedDownloadResumesWithNewDownloaderAndReportsOverallProgress() = runBlocking {
        interruptDownload()
        val offset = part.length()
        enqueueRemainder(offset)
        val progress = mutableListOf<ModelDownloader.Progress>()

        downloader().download(url, destination, "下载模型", progress::add)

        val request = server.takeRequest()
        assertEquals("bytes=$offset-", request.getHeader("Range"))
        assertEquals(etag, request.getHeader("If-Range"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertEquals(offset, progress.first().downloadedBytes)
        assertEquals(content.length.toLong(), progress.first().totalBytes)
        assertTrue(progress.first().message.contains("断点续传"))
        assertEquals(content, destination.readText())
        assertFalse(part.exists())
        assertFalse(metadata.exists())
    }

    @Test
    fun rangeIgnoredByServerReplacesPartialBytesWithFullResponse() = runBlocking {
        interruptDownload()
        server.enqueue(MockResponse().setBody("new model content").setHeader("ETag", "\"v2\""))

        download()

        assertTrue(server.takeRequest().getHeader("Range") != null)
        assertEquals("new model content", destination.readText())
    }

    @Test
    fun repeatedInterruptionsContinueFromLatestSavedOffset() = runBlocking {
        interruptDownload()
        val firstOffset = part.length()
        server.enqueue(partialResponse(firstOffset).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try {
            download()
            fail("Interrupted resumed transfer should fail")
        } catch (error: IOException) {
            assertTrue(ModelDownloadRetryPolicy.shouldRetry(error, 1))
        }
        assertEquals("bytes=$firstOffset-", server.takeRequest().getHeader("Range"))
        val nextOffset = part.length()
        assertTrue(nextOffset > firstOffset)
        assertTrue(nextOffset < content.length)
        enqueueRemainder(nextOffset)

        download()

        assertEquals("bytes=$nextOffset-", server.takeRequest().getHeader("Range"))
        assertEquals(content, destination.readText())
    }

    @Test
    fun changedValidatorOnPartialResponseRetriesWithoutRange() = runBlocking {
        interruptDownload()
        val offset = part.length()
        server.enqueue(partialResponse(offset).setHeader("ETag", "\"different-version\""))
        server.enqueue(MockResponse().setBody("replacement").setHeader("ETag", "\"different-version\""))

        download()

        assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("replacement", destination.readText())
    }

    @Test
    fun invalidContentRangeRetriesWithoutAppendingIncorrectBytes() = runBlocking {
        interruptDownload()
        server.enqueue(partialResponse(part.length()).setHeader("Content-Range", "bytes 0-2/3"))
        server.enqueue(MockResponse().setBody(content).setHeader("ETag", etag))

        download()

        assertTrue(server.takeRequest().getHeader("Range") != null)
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals(content, destination.readText())
    }

    @Test
    fun unsatisfiedRangeRestartsWhenRemoteFileChanged() = runBlocking {
        interruptDownload()
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */3"))
        server.enqueue(MockResponse().setBody("new").setHeader("ETag", "\"new\""))

        download()

        assertTrue(server.takeRequest().getHeader("Range") != null)
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("new", destination.readText())
    }

    @Test
    fun completePartCanBeInstalledAfterCancellationWithValidated416() = runBlocking {
        server.enqueue(MockResponse().setBody(content).setHeader("ETag", etag))
        val job = launch {
            val context = currentCoroutineContext()
            downloader().download(url, destination, "下载模型", {
                if (it.downloadedBytes == content.length.toLong()) context.cancel()
            })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(destination.exists())
        assertEquals(content.length.toLong(), part.length())
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(416)
            .setHeader("Content-Range", "bytes */${content.length}").setHeader("ETag", etag))

        download()

        assertEquals("bytes=${content.length}-", server.takeRequest().getHeader("Range"))
        assertEquals(content, destination.readText())
    }

    @Test
    fun cancellationClosesWriterBeforeNextDownloadResumes() = runBlocking {
        server.enqueue(MockResponse().setBody(content).setHeader("ETag", etag)
            .throttleBody(16 * 1024, 20, TimeUnit.MILLISECONDS))
        val job = launch {
            val context = currentCoroutineContext()
            downloader().download(url, destination, "下载模型", {
                if (it.downloadedBytes > 0L) context.cancel()
            })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(part.length() in 1 until content.length.toLong())
        assertFalse(destination.exists())
        server.takeRequest()
        val offset = part.length()
        enqueueRemainder(offset)

        download()

        assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
        assertEquals(content, destination.readText())
    }

    @Test
    fun transientHttpFailurePreservesPartialBytesAndValidatorForRetry() = runBlocking {
        destination.writeText("previous model")
        interruptDownload()
        val savedBytes = part.readBytes()
        val savedMetadata = metadata.readText()
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            download()
            fail("503 should fail")
        } catch (error: ModelDownloadHttpException) {
            assertTrue(ModelDownloadRetryPolicy.shouldRetry(error, 0))
            assertEquals(503, error.statusCode)
        }
        server.takeRequest()
        assertTrue(savedBytes.contentEquals(part.readBytes()))
        assertEquals(savedMetadata, metadata.readText())
        assertEquals("previous model", destination.readText())
        enqueueRemainder(part.length())

        download()

        assertEquals(content, destination.readText())
        assertFalse(File(temporaryFolder.root, "model.onnx.backup").exists())
    }

    @Test
    fun lastModifiedIsUsedWhenStrongEtagIsUnavailable() = runBlocking {
        val modified = "Wed, 16 Sep 2026 10:00:00 GMT"
        interruptDownload(response = MockResponse().setBody(content)
            .setHeader("ETag", "W/\"weak\"").setHeader("Last-Modified", modified))
        val offset = part.length()
        server.enqueue(partialResponse(offset).removeHeader("ETag").setHeader("Last-Modified", modified))

        download()

        val request = server.takeRequest()
        assertEquals("bytes=$offset-", request.getHeader("Range"))
        assertEquals(modified, request.getHeader("If-Range"))
        assertEquals(content, destination.readText())
    }

    @Test
    fun missingValidatorOrCorruptMetadataRestartsSafely() = runBlocking {
        interruptDownload(response = MockResponse().setBody(content).setHeader("ETag", "W/\"weak\""))
        server.enqueue(MockResponse().setBody(content))
        download()
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals(content, destination.readText())

        interruptDownload()
        metadata.writeText("broken metadata")
        server.enqueue(MockResponse().setBody("replacement"))
        download()
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("replacement", destination.readText())
    }

    @Test
    fun changedUrlDoesNotReuseAnotherModelsPartialFile() = runBlocking {
        interruptDownload()
        server.enqueue(MockResponse().setBody("another model"))

        downloader().download(server.url("/other-model").toString(), destination, "下载模型", {})

        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("another model", destination.readText())
    }

    @Test
    fun serverMayReturnSeveralBoundedRanges() = runBlocking {
        interruptDownload()
        val offset = part.length()
        val end = offset + 1023L
        server.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", etag)
            .setHeader("Content-Range", "bytes $offset-$end/${content.length}")
            .setBody(content.substring(offset.toInt(), end.toInt() + 1)))
        enqueueRemainder(end + 1L)

        download()

        assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=${end + 1}-", server.takeRequest().getHeader("Range"))
        assertEquals(content, destination.readText())
    }

    @Test
    fun tooSmallCompletedFileIsRejectedWithoutReplacingExistingModel() = runBlocking {
        destination.writeText("old model")
        server.enqueue(MockResponse().setBody("bad").setHeader("ETag", etag))
        try {
            downloader().download(url, destination, "下载模型", {}, minimumSize = 100L)
            fail("Invalid model size should fail")
        } catch (_: IOException) {
            assertEquals("old model", destination.readText())
            assertFalse(part.exists())
            assertFalse(metadata.exists())
        }
    }

    private suspend fun interruptDownload(
        response: MockResponse = MockResponse().setBody(content).setHeader("ETag", etag)
    ) {
        server.enqueue(response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try {
            download()
            fail("Interrupted transfer should fail")
        } catch (error: IOException) {
            assertTrue(error.toString(), ModelDownloadRetryPolicy.shouldRetry(error, 0))
        }
        server.takeRequest()
        assertTrue(part.length() in 1 until content.length.toLong())
        assertTrue(metadata.isFile)
    }

    private fun partialResponse(offset: Long) = MockResponse().setResponseCode(206)
        .setHeader("ETag", etag)
        .setHeader("Content-Range", "bytes $offset-${content.length - 1}/${content.length}")
        .setBody(content.substring(offset.toInt()))

    private fun enqueueRemainder(offset: Long) = server.enqueue(partialResponse(offset))
    private fun downloader() = ResumableModelDownload(client)
    private suspend fun download() = downloader().download(url, destination, "下载模型", {})
}
