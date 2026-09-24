package com.subtitleedit.util

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile

class Qwen3AsrModelBudgetTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun readsFixedAndDynamicCacheLengthFromFifthGraphInput() {
        for (length in listOf(128L, 512L, 2048L, 0L, -1L, null)) {
            val file = temp.newFile()
            file.writeBytes(message(7, graphInputs(length)))
            assertEquals(length?.takeIf { it > 0 }?.toInt(), Qwen3AsrModelBudget.readCacheLength(file))
        }
    }

    @Test
    fun skipsEmbeddedWeightsWithoutReadingOrAllocatingTheirPayload() {
        val file = temp.newFile()
        val inputs = graphInputs(1024)
        val weightSize = 32L * 1024 * 1024
        val weightHeader = varint((5 shl 3 or 2).toLong()) + varint(weightSize)
        val graphSize = weightHeader.size + weightSize + inputs.size
        RandomAccessFile(file, "rw").use {
            it.write(varint((7 shl 3 or 2).toLong()) + varint(graphSize))
            it.write(weightHeader)
            it.seek(it.filePointer + weightSize)
            it.write(inputs)
        }
        assertEquals(1024, Qwen3AsrModelBudget.readCacheLength(file))
    }

    @Test
    fun failsOnMissingMetadataOrTruncatedModelInsteadOfAssuming512() {
        val valid = message(7, graphInputs(512))
        for (data in listOf(byteArrayOf(), message(7, byteArrayOf()), valid.copyOf(valid.size - 1),
            byteArrayOf(58, 127, 1), byteArrayOf(58, -1, -1, -1))) {
            val file = temp.newFile().apply { writeBytes(data) }
            assertThrows(IllegalArgumentException::class.java) {
                // Missing graph/input is IllegalStateException; normalize for this check.
                try { Qwen3AsrModelBudget.readCacheLength(file) }
                catch (e: IllegalStateException) { throw IllegalArgumentException(e) }
            }
        }
    }

    private fun graphInputs(length: Long?): ByteArray {
        fun input(sequence: Long?): ByteArray {
            val dimensions = message(1, scalar(1, 1)) + message(1,
                sequence?.let { scalar(1, it) } ?: message(2, "sequence".toByteArray())) +
                message(1, scalar(1, 128))
            return message(11, message(1, "input".toByteArray()) +
                message(2, message(1, scalar(1, 1) + message(2, dimensions))))
        }
        return input(9999) + input(9999) + input(9999) + input(9999) + input(length)
    }

    private fun scalar(field: Int, value: Long) = varint((field shl 3).toLong()) + varint(value)
    private fun message(field: Int, body: ByteArray) =
        varint((field shl 3 or 2).toLong()) + varint(body.size.toLong()) + body
    private fun varint(value: Long): ByteArray {
        var remaining = value
        val bytes = ByteArrayOutputStream()
        while (remaining and -128L != 0L) {
            bytes.write((remaining.toInt() and 127) or 128)
            remaining = remaining ushr 7
        }
        bytes.write(remaining.toInt())
        return bytes.toByteArray()
    }
}
