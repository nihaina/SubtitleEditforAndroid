package com.subtitleedit.work

import com.subtitleedit.util.ModelDownloadHttpException
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadRetryPolicyTest {
    @Test
    fun retriesTransientNetworkFailuresBeforeAttemptLimit() {
        assertTrue(ModelDownloadRetryPolicy.shouldRetry(SocketTimeoutException(), 0))
        assertTrue(ModelDownloadRetryPolicy.shouldRetry(ModelDownloadHttpException(503), 1))
        assertTrue(ModelDownloadRetryPolicy.shouldRetry(EOFException(), 0))
    }

    @Test
    fun doesNotRetryPermanentFailuresOrAfterAttemptLimit() {
        assertFalse(ModelDownloadRetryPolicy.shouldRetry(FileNotFoundException(), 0))
        assertFalse(ModelDownloadRetryPolicy.shouldRetry(ModelDownloadHttpException(404), 0))
        assertFalse(ModelDownloadRetryPolicy.shouldRetry(ModelDownloadHttpException(503), 2))
    }

    @Test
    fun retriesOnlyTransientHttpStatusCodes() {
        (300..599).forEach { statusCode ->
            val expected = statusCode == 408 || statusCode == 429 || statusCode in 500..599
            assertEquals(
                "HTTP $statusCode",
                expected,
                ModelDownloadRetryPolicy.shouldRetry(ModelDownloadHttpException(statusCode), 0)
            )
        }
    }

    @Test
    fun retriesTimeoutConnectionAndIncompleteTransferFailures() {
        listOf(
            SocketTimeoutException(), SocketException(), ConnectException(),
            UnknownHostException(), EOFException()
        ).forEach { error ->
            assertTrue(error.javaClass.name, ModelDownloadRetryPolicy.shouldRetry(error, 1))
        }
    }

    @Test
    fun doesNotRetryStorageValidationCertificateOrCancellationErrors() {
        listOf(
            IOException("No space left on device"),
            IOException("HTTP 503 is only an error message"),
            FileNotFoundException(),
            SecurityException(),
            IllegalStateException("模型校验失败"),
            SSLHandshakeException("证书无效"),
            CancellationException("用户取消")
        ).forEach { error ->
            assertFalse(error.javaClass.name, ModelDownloadRetryPolicy.shouldRetry(error, 0))
        }
    }

    @Test
    fun stopsRetryingOnThirdExecutionAndLater() {
        val error = SocketTimeoutException()
        assertEquals(3, ModelDownloadRetryPolicy.MAX_ATTEMPTS)
        assertTrue(ModelDownloadRetryPolicy.shouldRetry(error, 0))
        assertTrue(ModelDownloadRetryPolicy.shouldRetry(error, 1))
        assertFalse(ModelDownloadRetryPolicy.shouldRetry(error, 2))
        assertFalse(ModelDownloadRetryPolicy.shouldRetry(error, Int.MAX_VALUE))
    }
}
