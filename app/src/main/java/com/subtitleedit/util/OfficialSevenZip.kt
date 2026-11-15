package com.subtitleedit.util

import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Keep
internal object OfficialSevenZip {
    init { System.loadLibrary("subtitleedit_7zip") }

    private val executionLock = ReentrantLock()

    @Keep
    private external fun executeNative(
        arguments: Array<String>,
        capturePath: String?,
        workingDirectory: String?,
        stdoutFd: Int
    ): Int

    @Keep
    private external fun duplicateFdAboveStandardNative(fd: Int): Int

    @Keep
    private external fun extractNative(
        archivePath: String,
        password: String?,
        callback: SevenZipExtractCallback
    )

    @Keep
    private external fun testNative(
        archivePath: String,
        password: String?,
        callback: SevenZipTestCallback,
        maxEntries: Int,
        maxBytes: Long
    ): LongArray

    fun test(
        archivePath: String,
        password: CharArray?,
        limits: ArchiveManager.ExtractionLimits,
        callback: SevenZipTestCallback
    ): ArchiveManager.TestResult {
        // Other native operations also use this lock because 7-Zip has shared process state.
        // Check cancellation while queued, before entering a potentially long native operation.
        while (true) {
            callback.onProgress(0L, 0L)
            if (executionLock.tryLock(100L, TimeUnit.MILLISECONDS)) break
        }
        try {
            callback.onProgress(0L, 0L)
            val result = testNative(archivePath, password?.let(::String), callback, limits.maxEntries, limits.maxBytes)
            return ArchiveManager.TestResult(result[0].toInt(), result[1])
        } finally {
            executionLock.unlock()
        }
    }

    fun extract(archivePath: String, password: CharArray?, callback: SevenZipExtractCallback) = executionLock.withLock {
        extractNative(archivePath, password?.let(::String), callback)
    }

    fun execute(arguments: List<String>, capturePath: String? = null, workingDirectory: String? = null): Int =
        executionLock.withLock { executeNative(arguments.toTypedArray(), capturePath, workingDirectory, -1) }

    fun executeToFd(
        arguments: List<String>,
        stdoutFd: Int,
        capturePath: String? = null,
        checkCancelled: () -> Unit = {}
    ): Int {
        require(stdoutFd >= 3) { "stdout 文件描述符必须避开标准流" }
        while (true) {
            checkCancelled()
            if (executionLock.tryLock(100L, TimeUnit.MILLISECONDS)) break
        }
        try {
            checkCancelled()
            return executeNative(arguments.toTypedArray(), capturePath, null, stdoutFd)
        } finally {
            executionLock.unlock()
        }
    }

    fun duplicateFdAboveStandard(fd: Int): Int {
        require(fd >= 0) { "文件描述符无效" }
        return duplicateFdAboveStandardNative(fd)
    }
}

/** Called synchronously by 7-Zip before it writes each output file. */
@Keep
internal interface SevenZipExtractCallback {
    fun onArchiveOpened(volumePaths: Array<String>)

    /** The caller owns the descriptor; a negative value skips the entry. */
    fun openEntry(
        name: String,
        size: Long,
        isDirectory: Boolean,
        isSymbolicLink: Boolean,
        modifiedTimeMillis: Long
    ): Int

    fun onProgress(completed: Long, total: Long)
    fun onEntryComplete(success: Boolean)
}

@Keep
internal interface SevenZipTestCallback {
    fun onEntry(name: String, size: Long, isDirectory: Boolean, isSymbolicLink: Boolean)
    fun onProgress(completed: Long, total: Long)
}
