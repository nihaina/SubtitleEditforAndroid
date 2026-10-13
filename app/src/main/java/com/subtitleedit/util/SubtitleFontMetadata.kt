package com.subtitleedit.util

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Reads family names from OpenType name tables so subtitle face names match renderer names. */
internal object SubtitleFontMetadata {
    data class Family(
        val name: String,
        val collectionIndex: Int,
        val weight: Int = 400,
        val italic: Boolean = false
    )

    fun familyNames(file: File): List<String> = families(file).map { it.name }.distinctBy { it.lowercase(Locale.US) }

    fun families(file: File): List<Family> = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val offsets = if (readTag(input, 0) == "ttcf") {
                val count = input.readUnsignedIntAt(8).coerceIn(0, 64).toInt()
                (0 until count).mapNotNull { index ->
                    input.readUnsignedIntAt(12L + index * 4L)
                        .takeIf { it < input.length() }
                        ?.let { index to it }
                }
            } else listOf(0 to 0L)

            offsets.mapNotNull { (index, fontOffset) ->
                readFamilyName(input, fontOffset)?.let { name ->
                    val style = readStyle(input, fontOffset)
                    Family(name, index, style.first, style.second)
                }
            }.distinctBy { it.name.lowercase(Locale.US) to it.collectionIndex }
        }
    }.getOrDefault(emptyList())

    private fun readFamilyName(input: RandomAccessFile, fontOffset: Long): String? {
        val tableCount = input.readUnsignedShortAt(fontOffset + 4).coerceIn(0, 512)
        var nameOffset: Long? = null
        var nameLength = 0
        repeat(tableCount) { index ->
            val record = fontOffset + 12 + index * 16L
            if (readTag(input, record) == "name") {
                // Table offsets inside a TTC are absolute from the collection start.
                nameOffset = input.readUnsignedIntAt(record + 8)
                nameLength = input.readUnsignedIntAt(record + 12).coerceAtMost(1_000_000).toInt()
            }
        }
        val tableOffset = nameOffset ?: return null
        if (tableOffset < 0 || tableOffset + nameLength > input.length()) return null
        val count = input.readUnsignedShortAt(tableOffset + 2).coerceIn(0, 4096)
        val stringsStart = tableOffset + input.readUnsignedShortAt(tableOffset + 4)
        val candidates = mutableListOf<FontName>()
        repeat(count) { index ->
            val record = tableOffset + 6 + index * 12L
            val platform = input.readUnsignedShortAt(record)
            val encoding = input.readUnsignedShortAt(record + 2)
            val language = input.readUnsignedShortAt(record + 4)
            val nameId = input.readUnsignedShortAt(record + 6)
            if (nameId != 1 && nameId != 16) return@repeat
            val length = input.readUnsignedShortAt(record + 8)
            val offset = input.readUnsignedShortAt(record + 10)
            val start = stringsStart + offset
            if (start < tableOffset || start + length > tableOffset + nameLength) return@repeat
            val bytes = ByteArray(length)
            input.seek(start)
            input.readFully(bytes)
            val charset = if (platform == 0 || platform == 3) {
                StandardCharsets.UTF_16BE
            } else {
                StandardCharsets.ISO_8859_1
            }
            val value = String(bytes, charset).trim('\u0000', ' ', '\t', '\r', '\n')
            if (value.isNotEmpty()) candidates += FontName(nameId, language, value)
        }
        return candidates.sortedWith(
            compareBy<FontName> { if (it.nameId == 16) 0 else 1 }
                .thenBy { if (it.language == 0x0409 || it.language == 0) 0 else 1 }
        ).firstOrNull()?.value
    }

    private data class FontName(val nameId: Int, val language: Int, val value: String)

    private fun readStyle(input: RandomAccessFile, fontOffset: Long): Pair<Int, Boolean> {
        val tableCount = input.readUnsignedShortAt(fontOffset + 4).coerceIn(0, 512)
        var weight = 400
        var italic = false
        repeat(tableCount) { index ->
            val record = fontOffset + 12 + index * 16L
            val offset = input.readUnsignedIntAt(record + 8)
            val length = input.readUnsignedIntAt(record + 12)
            if (offset > input.length() || length > input.length() - offset) return@repeat
            when (readTag(input, record)) {
                "OS/2" -> if (length >= 64) {
                    weight = input.readUnsignedShortAt(offset + 4).coerceIn(1, 1000)
                    italic = input.readUnsignedShortAt(offset + 62) and 1 != 0
                }
                "head" -> if (length >= 46) {
                    italic = italic || input.readUnsignedShortAt(offset + 44) and 2 != 0
                }
            }
        }
        return weight to italic
    }

    private fun readTag(input: RandomAccessFile, offset: Long): String {
        if (offset < 0 || offset + 4 > input.length()) return ""
        val bytes = ByteArray(4)
        input.seek(offset)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.US_ASCII)
    }

    private fun RandomAccessFile.readUnsignedShortAt(offset: Long): Int {
        if (offset < 0 || offset + 2 > length()) return 0
        seek(offset)
        return readUnsignedShort()
    }

    private fun RandomAccessFile.readUnsignedIntAt(offset: Long): Long {
        if (offset < 0 || offset + 4 > length()) return Long.MAX_VALUE
        seek(offset)
        return readInt().toLong() and 0xFFFFFFFFL
    }
}
