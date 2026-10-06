package com.subtitleedit.util

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUtilsEncodingTest {
    @Test
    fun automaticEncodingIsListedWithStableSettingId() {
        val automatic = FileUtils.SUPPORTED_ENCODINGS.first { it.isAuto }

        assertTrue(automatic.isAuto)
        assertEquals(SettingsManager.AUTO_ENCODING, automatic.id)
    }

    @Test
    fun detectsUtf8AndUtf16ByteOrderMarks() {
        val utf8 = temporaryFile(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "字幕".toByteArray(StandardCharsets.UTF_8))
        val utf16Le = temporaryFile(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "字幕".toByteArray(StandardCharsets.UTF_16LE))
        val utf16Be = temporaryFile(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "字幕".toByteArray(StandardCharsets.UTF_16BE))

        try {
            assertEquals(StandardCharsets.UTF_8, FileUtils.detectEncoding(utf8))
            assertEquals(StandardCharsets.UTF_16LE, FileUtils.detectEncoding(utf16Le))
            assertEquals(StandardCharsets.UTF_16BE, FileUtils.detectEncoding(utf16Be))
        } finally {
            utf8.delete()
            utf16Le.delete()
            utf16Be.delete()
        }
    }

    @Test
    fun detectsGbkWhenUtf8DecodingIsInvalid() {
        val file = temporaryFile("字幕".toByteArray(Charset.forName("GBK")))

        try {
            assertEquals(Charset.forName("GBK"), FileUtils.detectEncoding(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun readFileWithNullCharsetUsesDetectedEncoding() {
        val expected = "字幕内容"
        val file = temporaryFile(expected.toByteArray(Charset.forName("GBK")))

        try {
            assertEquals(expected, FileUtils.readFile(file, charset = null))
        } finally {
            file.delete()
        }
    }

    private fun temporaryFile(bytes: ByteArray): File =
        File.createTempFile("subtitle-edit-encoding-", ".txt").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
}
