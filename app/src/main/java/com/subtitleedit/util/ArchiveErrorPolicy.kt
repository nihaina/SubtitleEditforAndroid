package com.subtitleedit.util

import java.io.File
import kotlinx.coroutines.CancellationException

/** 压缩操作错误分类规则，与 UI 展示解耦。 */
internal object ArchiveErrorPolicy {
    fun isDestinationConflict(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .any { it is ArchiveManager.DestinationConflictException }

    fun isCancelled(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .any { it is CancellationException }

    fun needsPassword(archive: File, error: Throwable, passwordAttempted: Boolean): Boolean {
        if (error.message?.contains("无法清理") == true ||
            error.message?.contains("未能恢复") == true
        ) return false
        return generateSequence(error) { it.cause }.any { cause ->
            cause is ArchivePasswordRequiredException ||
                cause.message?.contains("password", ignoreCase = true) == true ||
                cause.message?.contains("passphrase", ignoreCase = true) == true ||
                cause.message?.contains("decrypt", ignoreCase = true) == true ||
                (passwordAttempted && archive.extension.equals("7z", ignoreCase = true) &&
                    cause.message?.contains("checksum verification failed", ignoreCase = true) == true)
        }
    }
}
