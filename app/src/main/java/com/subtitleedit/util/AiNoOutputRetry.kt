package com.subtitleedit.util

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val AI_NO_OUTPUT_MAX_RETRIES = 3

internal class AiNoOutputTimeoutException(
    message: String = "AI 连续等待 3 分钟无推理或输出，自动重试 3 次后仍无响应，翻译失败"
) : IOException(message)

internal suspend fun <T> retryAiRequestWhenNoOutput(
    idleTimeoutMs: Long = 3 * 60 * 1_000L,
    maxRetries: Int = AI_NO_OUTPUT_MAX_RETRIES,
    isCancelled: () -> Boolean = { false },
    onTimeout: () -> Unit,
    onRetry: suspend (Int) -> Unit = {},
    request: suspend (onOutput: () -> Unit, isTimedOut: () -> Boolean) -> T
): T {
    require(idleTimeoutMs > 0) { "无输出超时时间必须大于 0" }
    require(maxRetries >= 0) { "无输出重试次数不能小于 0" }

    var retries = 0
    while (true) {
        if (isCancelled()) throw CancellationException("AI 请求已取消")
        val timedOut = AtomicBoolean(false)
        val lastOutputAtNanos = AtomicLong(System.nanoTime())
        try {
            return coroutineScope {
                val requestTask = async {
                    request(
                        { lastOutputAtNanos.set(System.nanoTime()) },
                        timedOut::get
                    )
                }
                val watchdog = launch {
                    while (requestTask.isActive) {
                        if (isCancelled()) return@launch
                        val elapsedMs = (System.nanoTime() - lastOutputAtNanos.get()) / 1_000_000L
                        val remainingMs = idleTimeoutMs - elapsedMs
                        if (remainingMs <= 0L) {
                            if (requestTask.isActive && timedOut.compareAndSet(false, true)) {
                                runCatching(onTimeout)
                                requestTask.cancel(CancellationException("AI 请求 3 分钟无输出"))
                            }
                            return@launch
                        }
                        delay(remainingMs.coerceAtMost(1_000L))
                    }
                }
                try {
                    val result = requestTask.await()
                    if (timedOut.get()) throw NoOutputAttemptTimedOut()
                    result
                } catch (error: CancellationException) {
                    if (timedOut.get()) throw NoOutputAttemptTimedOut()
                    throw error
                } finally {
                    watchdog.cancelAndJoin()
                }
            }
        } catch (_: NoOutputAttemptTimedOut) {
            if (isCancelled()) throw CancellationException("AI 请求已取消")
            if (retries >= maxRetries) throw AiNoOutputTimeoutException()
            retries++
            onRetry(retries)
        }
    }
}

private class NoOutputAttemptTimedOut : Exception()
