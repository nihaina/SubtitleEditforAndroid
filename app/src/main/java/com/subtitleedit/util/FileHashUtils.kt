package com.subtitleedit.util

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Utilities for deriving stable indexes from file contents. */
object FileHashUtils {
    private const val BUFFER_SIZE = 8192
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    /** Returns the lowercase MD5 digest of the file contents. */
    fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
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
}
