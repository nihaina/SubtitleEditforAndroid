package com.subtitleedit.util

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Utilities for deriving stable indexes from file contents. */
object FileHashUtils {
    private const val BUFFER_SIZE = 8192
    private const val HASH_SECTION_SIZE = 1024L * 1024L
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    /** Returns the lowercase MD5 digest of the file's head and tail sections. */
    fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER_SIZE)
        RandomAccessFile(file, "r").use { input ->
            val fileLength = input.length()
            if (fileLength <= HASH_SECTION_SIZE * 2) {
                updateDigest(input, digest, buffer, fileLength)
            } else {
                updateDigest(input, digest, buffer, HASH_SECTION_SIZE)
                input.seek(fileLength - HASH_SECTION_SIZE)
                updateDigest(input, digest, buffer, HASH_SECTION_SIZE)
            }
        }

        val bytes = digest.digest()
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }
    }

    private fun updateDigest(
        input: RandomAccessFile,
        digest: MessageDigest,
        buffer: ByteArray,
        byteCount: Long
    ) {
        var remaining = byteCount
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            digest.update(buffer, 0, read)
            remaining -= read
        }
    }
}
