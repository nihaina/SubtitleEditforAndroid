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
    fun detectsUtf8WhenSampleBoundarySplitsMultibyteCharacter() {
        val expected = "a".repeat(64 * 1024 - 1) + "\u65e5\u672c\u8a9e"
        val file = temporaryFile(expected.toByteArray(StandardCharsets.UTF_8))

        try {
            assertEquals(StandardCharsets.UTF_8, FileUtils.detectEncoding(file))
            assertEquals(expected, FileUtils.readFile(file, charset = null))
        } finally {
            file.delete()
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

    @Test
    fun detectsShiftJisWhenLegacyBytesAreAmbiguous() {
        val expected = "\u65e5\u672c\u8a9e\u30c6\u30b9\u30c8"
        val file = temporaryFile(expected.toByteArray(Charset.forName("Shift_JIS")))

        try {
            assertEquals(Charset.forName("Shift_JIS"), FileUtils.detectEncoding(file))
            assertEquals(expected, FileUtils.readFile(file, charset = null))
        } finally {
            file.delete()
        }
    }

    @Test
    fun usesChardetForLongLegacyText() {
        val expected = "\u65e5\u672c\u8a9e\u306e\u5b57\u5e55\u30c6\u30b9\u30c8\u3002".repeat(20)
        val file = temporaryFile(expected.toByteArray(Charset.forName("Shift_JIS")))

        try {
            assertEquals(Charset.forName("Shift_JIS"), FileUtils.detectEncoding(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun detectsBig5TraditionalChinese() {
        val expected = "\u7e41\u9ad4\u4e2d\u6587"
        val file = temporaryFile(expected.toByteArray(Charset.forName("BIG5")))

        try {
            assertEquals(Charset.forName("BIG5"), FileUtils.detectEncoding(file))
            assertEquals(expected, FileUtils.readFile(file, charset = null))
        } finally {
            file.delete()
        }
    }

    @Test
    fun detectsEucJpAndEucKr() {
        val japanese = "\u65e5\u672c\u8a9e\u30c6\u30b9\u30c8"
        val korean = "\ud55c\uad6d\uc5b4 \ud14c\uc2a4\ud2b8"
        val japaneseFile = temporaryFile(japanese.toByteArray(Charset.forName("EUC-JP")))
        val koreanFile = temporaryFile(korean.toByteArray(Charset.forName("EUC-KR")))

        try {
            assertEquals(Charset.forName("EUC-JP"), FileUtils.detectEncoding(japaneseFile))
            assertEquals(Charset.forName("EUC-KR"), FileUtils.detectEncoding(koreanFile))
        } finally {
            japaneseFile.delete()
            koreanFile.delete()
        }
    }

    private fun temporaryFile(bytes: ByteArray): File =
        File.createTempFile("subtitle-edit-encoding-", ".txt").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
}
