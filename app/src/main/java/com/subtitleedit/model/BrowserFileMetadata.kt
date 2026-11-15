package com.subtitleedit.model

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong

/** A filesystem snapshot. Consumers can render and sort it without filesystem calls. */
internal data class BrowserFileMetadata(
    val file: File,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedTimeMillis: Long,
    val restricted: Boolean = false,
    val revision: Long = 0L,
    val isRegularFile: Boolean = !isDirectory
) {
    val path: String get() = file.absolutePath
    val cacheKey: String get() = "$path:$revision:$size:$modifiedTimeMillis"

    companion object {
        private val revisions = AtomicLong()
        internal fun nextRevision(): Long = revisions.incrementAndGet()

        /** Reads type, size and modification time together in one stat operation. */
        fun read(file: File, revision: Long = nextRevision()): BrowserFileMetadata? = try {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            BrowserFileMetadata(
                file = file,
                isDirectory = attributes.isDirectory,
                size = attributes.size(),
                modifiedTimeMillis = attributes.lastModifiedTime().toMillis(),
                revision = revision,
                isRegularFile = attributes.isRegularFile
            )
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
