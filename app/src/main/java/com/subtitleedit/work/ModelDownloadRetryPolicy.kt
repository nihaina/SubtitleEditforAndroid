package com.subtitleedit.work

import com.subtitleedit.util.ModelDownloadHttpException
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal object ModelDownloadRetryPolicy {
    const val MAX_ATTEMPTS = 3

    fun shouldRetry(error: Throwable, runAttemptCount: Int): Boolean {
        if (runAttemptCount >= MAX_ATTEMPTS - 1) return false
        return when (error) {
            is ModelDownloadHttpException -> error.statusCode == 408 ||
                error.statusCode == 429 || error.statusCode in 500..599
            is SocketTimeoutException, is SocketException, is UnknownHostException,
            is EOFException -> true
            else -> false
        }
    }
}
