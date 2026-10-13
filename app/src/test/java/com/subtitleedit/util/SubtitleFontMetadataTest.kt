package com.subtitleedit.util

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleFontMetadataTest {
    @Test
    fun readsFamilyNameFromOpenTypeNameTableAndCollection() {
        val ttf = tempFontFile("font", buildFont("Test Family", fontOffset = 0, tableOffset = 28))
        val ttc = tempFontFile("collection", buildCollection("Collection Family"))

        assertEquals(listOf("Test Family"), SubtitleFontMetadata.familyNames(ttf))
        assertEquals(listOf("Collection Family"), SubtitleFontMetadata.familyNames(ttc))
    }

    private fun buildCollection(family: String): ByteArray {
        val fontOffset = 16
        return bytes {
            writeBytes("ttcf")
            writeInt(0x00010000)
            writeInt(1)
            writeInt(fontOffset)
            write(buildFont(family, fontOffset, fontOffset + 28))
        }
    }

    private fun buildFont(family: String, fontOffset: Int, tableOffset: Int): ByteArray {
        val value = family.toByteArray(StandardCharsets.UTF_16BE)
        val tableLength = 18 + value.size
        return bytes {
            writeInt(0x00010000)
            writeShort(1)
            writeShort(0)
            writeShort(0)
            writeShort(0)
            writeBytes("name")
            writeInt(0)
            writeInt(tableOffset)
            writeInt(tableLength)
            writeShort(0)
            writeShort(1)
            writeShort(18)
            writeShort(3)
            writeShort(1)
            writeShort(0x0409)
            writeShort(1)
            writeShort(value.size)
            writeShort(0)
            write(value)
        }.also { check(fontOffset == 0 || fontOffset + it.size <= tableOffset + tableLength) }
    }

    private fun tempFontFile(prefix: String, bytes: ByteArray): File =
        File.createTempFile(prefix, ".ttf").apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use(write)
            output.toByteArray()
        }
}
