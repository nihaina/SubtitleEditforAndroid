package com.subtitleedit.translation

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A disposable disk cache keyed by a digest of the model route and source text. */
internal class TextTranslationCache(private val directory: File) {
    fun get(routeKey: String, text: String): String? = synchronized(lock) {
        bestEffort {
            val entry = entry(routeKey, text)
            if (entry.length() !in (CHECKSUM_SIZE + 1L)..(CHECKSUM_SIZE + MAX_TRANSLATION_BYTES).toLong()) {
                return@bestEffort null
            }
            val bytes = entry.readBytes()
            if (bytes.size <= CHECKSUM_SIZE || bytes.size > CHECKSUM_SIZE + MAX_TRANSLATION_BYTES) {
                return@bestEffort null
            }
            val content = bytes.copyOfRange(CHECKSUM_SIZE, bytes.size)
            if (!MessageDigest.isEqual(bytes.copyOf(CHECKSUM_SIZE), sha256().digest(content))) {
                return@bestEffort null
            }
            val translation = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString()
            if (translation.isBlank()) return@bestEffort null
            entry.setLastModified(System.currentTimeMillis())
            translation
        }
    }

    fun put(routeKey: String, text: String, translated: String) {
        if (translated.isBlank() || translated.length > MAX_TRANSLATION_BYTES) return
        val content = translated.toByteArray(Charsets.UTF_8)
        if (content.size > MAX_TRANSLATION_BYTES) return
        synchronized(lock) {
            bestEffort {
                if (!directory.isDirectory && !directory.mkdirs()) return@bestEffort
                val destination = entry(routeKey, text)
                val temporary = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, directory)
                try {
                    temporary.outputStream().buffered().use { output ->
                        output.write(sha256().digest(content))
                        output.write(content)
                    }
                    Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    temporary.delete()
                }
                trim(destination)
            }
        }
    }

    private fun trim(latest: File) {
        val files = directory.listFiles().orEmpty()
        val tempCutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
        val entries = files.mapNotNull { file ->
            when {
                ENTRY_NAME.matches(file.name) -> DiskEntry(file, file.length(), file.lastModified())
                file.name.startsWith(TEMP_PREFIX) && file.name.endsWith(TEMP_SUFFIX) && file.lastModified() < tempCutoff -> {
                    file.delete()
                    null
                }
                else -> null
            }
        }
        var remainingBytes = entries.sumOf { it.size }
        var remainingEntries = entries.size
        if (remainingBytes <= MAX_BYTES && remainingEntries <= MAX_ENTRIES) return
        for (entry in entries.filterNot { it.file == latest }.sortedBy { it.lastUsed }) {
            if (remainingBytes <= MAX_BYTES && remainingEntries <= MAX_ENTRIES) break
            if (entry.file.delete()) {
                remainingBytes -= entry.size
                remainingEntries--
            }
        }
    }

    private fun entry(routeKey: String, text: String): File {
        val digest = sha256()
        digest.update(KEY_VERSION)
        digest.updateKeyPart(routeKey)
        digest.updateKeyPart(text)
        val name = buildString(2 * CHECKSUM_SIZE) {
            for (byte in digest.digest()) {
                append(HEX[(byte.toInt() ushr 4) and 0xF])
                append(HEX[byte.toInt() and 0xF])
            }
        }
        return File(directory, "$name.translation")
    }

    private fun MessageDigest.updateKeyPart(part: String) {
        // Length-prefixed UTF-16 code units distinguish all possible source strings.
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(part.length).array())
        for (character in part) {
            update((character.code ushr 8).toByte())
            update(character.code.toByte())
        }
    }

    private inline fun <T> bestEffort(block: () -> T): T? = try {
        block()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private data class DiskEntry(val file: File, val size: Long, val lastUsed: Long)

    private companion object {
        const val KEY_VERSION: Byte = 1
        const val CHECKSUM_SIZE = 32
        const val MAX_TRANSLATION_BYTES = 64 * 1024
        const val MAX_BYTES = 8L * 1024 * 1024
        const val MAX_ENTRIES = 1024
        const val TEMP_PREFIX = "translation-"
        const val TEMP_SUFFIX = ".pending"
        const val TEMP_MAX_AGE_MS = 24 * 60 * 60 * 1_000L
        const val HEX = "0123456789abcdef"
        val ENTRY_NAME = Regex("[a-f0-9]{64}\\.translation")
        val lock = Any()

        fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")
    }
}
