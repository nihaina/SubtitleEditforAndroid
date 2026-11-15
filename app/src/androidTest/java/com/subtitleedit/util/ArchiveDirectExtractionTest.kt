package com.subtitleedit.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the native archive writer against isolated app-cache outputs only. */
@RunWith(AndroidJUnit4::class)
class ArchiveDirectExtractionTest {
    @Test
    fun conflictIsResolvedAfterEarlierFileIsWrittenDirectlyToDestination() = withRoot { root ->
        val destination = File(root, "out").apply { mkdir() }
        val conflictName = "重复🌊.txt"
        File(destination, conflictName).writeText("existing")
        val archive = zip(root, listOf(
            "first.txt" to "first",
            conflictName to "new content",
            "nested/last.txt" to "last"
        ))
        var conflictCalls = 0

        val result = ArchiveManager.extractArchive(archive, destination, onConflict = { conflict ->
            conflictCalls++
            assertEquals(conflictName, conflict.entryName)
            assertEquals("first", File(destination, "first.txt").readText())
            assertFalse(File(destination, "nested/last.txt").exists())
            assertNoExtractionStaging(destination)
            ArchiveManager.ConflictResolution(ArchiveManager.ConflictPolicy.RENAME)
        })

        assertEquals(1, conflictCalls)
        assertEquals(3, result.entryCount)
        assertEquals(0, result.skippedCount)
        assertEquals("existing", File(destination, conflictName).readText())
        assertEquals("new content", File(destination, "重复🌊（1）.txt").readText())
        assertEquals("last", File(destination, "nested/last.txt").readText())
        assertNoExtractionStaging(destination)
    }

    @Test
    fun applyToAllSkipsOrOverwritesFurtherConflictsWithoutRestarting() = withRoot { root ->
        val archive = zip(root, listOf(
            "first.txt" to "first",
            "a.txt" to "new a",
            "b.txt" to "new b",
            "last.txt" to "last"
        ))
        for (policy in listOf(ArchiveManager.ConflictPolicy.SKIP, ArchiveManager.ConflictPolicy.OVERWRITE)) {
            val destination = File(root, policy.name).apply { mkdir() }
            File(destination, "a.txt").writeText("old a")
            File(destination, "b.txt").writeText("old b")
            var conflictCalls = 0

            val result = ArchiveManager.extractArchive(archive, destination, onConflict = {
                conflictCalls++
                assertEquals("first", File(destination, "first.txt").readText())
                ArchiveManager.ConflictResolution(policy, applyToAll = true)
            })

            val skip = policy == ArchiveManager.ConflictPolicy.SKIP
            assertEquals(policy.name, 1, conflictCalls)
            assertEquals(if (skip) "old a" else "new a", File(destination, "a.txt").readText())
            assertEquals(if (skip) "old b" else "new b", File(destination, "b.txt").readText())
            assertEquals("last", File(destination, "last.txt").readText())
            assertEquals(if (skip) 2 else 4, result.entryCount)
            assertEquals(if (skip) 2 else 0, result.skippedCount)
            assertNoExtractionStaging(destination)
        }
    }

    @Test
    fun currentDirectoryExtractionKeepsSourceAndReportsOneMonotonicProgressSequence() = withRoot { root ->
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val archive = zipBytes(root, listOf("one.bin" to payload, "two.bin" to payload))
        val originalArchiveBytes = archive.readBytes()
        val updates = mutableListOf<Pair<Long, Long>>()

        val result = ArchiveManager.extractArchive(archive, root, onProgress = { phase, completed, total ->
            assertEquals(ArchiveManager.ProgressPhase.EXTRACTING, phase)
            updates += completed to total
            assertNoExtractionStaging(root)
        })

        assertEquals(2, result.entryCount)
        assertEquals(2L * payload.size, result.totalBytes)
        assertArrayEquals(payload, File(root, "one.bin").readBytes())
        assertArrayEquals(payload, File(root, "two.bin").readBytes())
        assertArrayEquals(originalArchiveBytes, archive.readBytes())
        assertTrue(updates.isNotEmpty())
        assertTrue("progress fell backwards: $updates", updates.zipWithNext().all { (a, b) -> a.first <= b.first })
        assertEquals(2L * payload.size, updates.last().first)
        assertEquals(2L * payload.size, updates.last().second)
        assertNoExtractionStaging(root)
    }

    @Test
    fun cancellationRemovesCurrentPartialOutputAndKeepsCompletedAndUnrelatedFiles() = withRoot { root ->
        val destination = File(root, "out").apply { mkdir() }
        File(destination, "unrelated.txt").writeText("keep me")
        val archive = zipBytes(root, listOf(
            "first.txt" to "done".toByteArray(),
            "cancel.bin" to ByteArray(4 * 1024 * 1024) { 7 },
            "last.txt" to "must not appear".toByteArray()
        ))

        val failure = runCatching {
            ArchiveManager.extractArchive(archive, destination, checkCancelled = {
                if (File(destination, "cancel.bin").length() > 0L) {
                    throw CancellationException("test cancellation")
                }
            })
        }.exceptionOrNull()

        assertTrue("original cancellation was lost: $failure", failure is CancellationException)
        assertEquals("done", File(destination, "first.txt").readText())
        assertEquals("keep me", File(destination, "unrelated.txt").readText())
        assertFalse(File(destination, "cancel.bin").exists())
        assertFalse(File(destination, "last.txt").exists())
        assertNoExtractionStaging(destination)
    }

    @Test
    fun crcFailureRemovesFailedOutputAndKeepsEarlierFiles() = withRoot { root ->
        val destination = File(root, "out").apply { mkdir() }
        File(destination, "unrelated.txt").writeText("keep me")
        val archive = zip(root, listOf("first.txt" to "done", "broken.txt" to "payload"))
        corruptStoredPayload(archive, "broken.txt")

        val failure = runCatching { ArchiveManager.extractArchive(archive, destination) }.exceptionOrNull()

        assertNotNull("corrupted CRC should fail extraction", failure)
        assertEquals("done", File(destination, "first.txt").readText())
        assertEquals("keep me", File(destination, "unrelated.txt").readText())
        assertFalse(File(destination, "broken.txt").exists())
        assertNoExtractionStaging(destination)
    }

    @Test
    fun failedOverwriteRestoresExistingFile() = withRoot { root ->
        val destination = File(root, "out").apply { mkdir() }
        val existing = File(destination, "broken.txt").apply { writeText("original valuable content") }
        val archive = zip(root, listOf("broken.txt" to "corrupt replacement"))
        corruptStoredPayload(archive, "broken.txt")

        val failure = runCatching {
            ArchiveManager.extractArchive(archive, destination, onConflict = {
                ArchiveManager.ConflictResolution(ArchiveManager.ConflictPolicy.OVERWRITE)
            })
        }.exceptionOrNull()

        assertNotNull("corrupted replacement should fail extraction", failure)
        assertTrue("failed replacement deleted the previous file", existing.isFile)
        assertEquals("original valuable content", existing.readText())
        assertNoExtractionStaging(destination)
    }

    @Test
    fun failedChildRestoresAFileOverwrittenByItsParentDirectory() = withRoot { root ->
        val destination = File(root, "out").apply { mkdir() }
        val existing = File(destination, "folder").apply { writeText("original parent file") }
        val archive = zip(root, listOf("folder/broken.txt" to "corrupt replacement"))
        corruptStoredPayload(archive, "folder/broken.txt")
        val failure = runCatching {
            ArchiveManager.extractArchive(archive, destination, onConflict = {
                ArchiveManager.ConflictResolution(ArchiveManager.ConflictPolicy.OVERWRITE)
            })
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(existing.isFile)
        assertEquals("original parent file", existing.readText())
        assertNoExtractionStaging(destination)
    }

    @Test
    fun tarAlsoWritesBeforeTheNextConflictAndContinuesAfterRename() = withRoot { root ->
        val destination = File(root, "out").apply { mkdir() }
        File(destination, "same.txt").writeText("old")
        val archive = File(root, "source.tar")
        archive.outputStream().use { output ->
            for ((name, text) in listOf("first.txt" to "first", "same.txt" to "new", "last.txt" to "last")) {
                val data = text.toByteArray()
                output.write(tarHeader(name, data.size))
                output.write(data)
                output.write(ByteArray((512 - data.size % 512) % 512))
            }
            output.write(ByteArray(1024))
        }
        var calls = 0

        val result = ArchiveManager.extractArchive(archive, destination, onConflict = {
            calls++
            assertEquals("first", File(destination, "first.txt").readText())
            assertNoExtractionStaging(destination)
            ArchiveManager.ConflictResolution(ArchiveManager.ConflictPolicy.RENAME)
        })

        assertEquals(1, calls)
        assertEquals(3, result.entryCount)
        assertEquals("old", File(destination, "same.txt").readText())
        assertEquals("new", File(destination, "same（1）.txt").readText())
        assertEquals("last", File(destination, "last.txt").readText())
        assertNoExtractionStaging(destination)
    }

    @Test
    fun encryptedZipAndSevenZipVolumesUseTheSameDirectWriter() = withRoot { root ->
        val payload = ByteArray(2048) { (it % 251).toByte() }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assets.list("archive-direct")!!.forEach { name ->
            assets.open("archive-direct/$name").use { input -> File(root, name).outputStream().use(input::copyTo) }
        }
        val password = "fixture-password".toCharArray()
        for (format in listOf(ArchiveManager.CreateFormat.ZIP, ArchiveManager.CreateFormat.SEVEN_Z)) {
            val input = File(root, if (format == ArchiveManager.CreateFormat.SEVEN_Z) "split.7z.001" else "encrypted.zip")
            val destination = File(root, format.name).apply { mkdir() }
            assertTrue(ArchiveErrorPolicy.needsPassword(input,
                runCatching { ArchiveManager.extractArchive(input, destination) }.exceptionOrNull()!!, false))
            val result = ArchiveManager.extractArchive(input, destination, password = password)
            assertEquals(1, result.entryCount)
            assertArrayEquals(payload, File(destination, "payload.bin").readBytes())
            assertNoExtractionStaging(destination)
        }
    }

    @Test
    fun compressedTarAndBareStreamsWriteDirectly() = withRoot { root ->
        val sourceDirectory = File(root, "sources").apply { mkdir() }
        val source = File(sourceDirectory, "payload.txt").apply { writeText("stream content".repeat(1000)) }
        for (method in listOf(ArchiveManager.CompressionMethod.TAR_GZIP,
            ArchiveManager.CompressionMethod.TAR_BZIP2, ArchiveManager.CompressionMethod.TAR_XZ)) {
            val archive = File(root, "archive.${ArchiveManager.outputExtension(ArchiveManager.CreateFormat.TAR, method)}")
            ArchiveManager.createArchive(listOf(source), archive, ArchiveManager.CreateFormat.TAR, method)
            val destination = File(root, method.name).apply { mkdir() }
            assertEquals(1, ArchiveManager.extractArchive(archive, destination).entryCount)
            assertEquals(source.readText(), File(destination, source.name).readText())
            assertNoExtractionStaging(destination)
        }
        for ((type, suffix) in listOf("gzip" to "gz", "bzip2" to "bz2", "xz" to "xz")) {
            val archive = File(root, "payload.txt.$suffix")
            assertEquals(0, OfficialSevenZip.execute(listOf("a", "-y", "-t$type", archive.absolutePath, source.name),
                workingDirectory = sourceDirectory.absolutePath))
            val destination = File(root, type).apply { mkdir() }
            assertEquals(1, ArchiveManager.extractArchive(archive, destination).entryCount)
            assertEquals(source.readText(), File(destination, source.name).readText())
            assertNoExtractionStaging(destination)
        }
    }

    @Test
    fun extractionCannotOverwriteTheArchiveBeingRead() = withRoot { root ->
        val archive = zip(root, listOf("source.zip" to "must not replace archive"))
        val original = archive.readBytes()
        val failure = runCatching {
            ArchiveManager.extractArchive(archive, root, conflictPolicy = ArchiveManager.ConflictPolicy.OVERWRITE)
        }.exceptionOrNull()
        assertNotNull(failure)
        assertArrayEquals(original, archive.readBytes())
    }

    @Test
    fun currentFileReportsTheRenamedOutputAndIgnoresSkippedFilesAndDirectories() = withRoot { root ->
        val renamedEntry = "目录/字幕🌊.srt"
        val skippedEntry = "目录/skip.txt"
        val entries = listOf(renamedEntry to "new subtitle", skippedEntry to "skip payload", "empty/" to "")
        for (format in listOf("zip", "tar")) {
            val archive = if (format == "zip") zip(root, entries) else File(root, "source.tar").also { tar ->
                tar.outputStream().use { output ->
                    for ((name, text) in entries) {
                        val data = text.toByteArray()
                        val header = tarHeader(name, data.size)
                        if (name.endsWith('/')) {
                            header[156] = '5'.code.toByte()
                            for (index in 148..155) header[index] = 32
                            header.sumOf { it.toInt() and 0xff }.toString(8).padStart(6, '0')
                                .toByteArray().copyInto(header, 148)
                            header[154] = 0
                        }
                        output.write(header)
                        output.write(data)
                        output.write(ByteArray((512 - data.size % 512) % 512))
                    }
                    output.write(ByteArray(1024))
                }
            }
            val destination = File(root, format).apply { mkdir() }
            File(destination, "目录").mkdir()
            File(destination, renamedEntry).writeText("existing subtitle")
            File(destination, skippedEntry).writeText("existing skipped file")
            val reported = mutableListOf<File>()

            val result = ArchiveManager.extractArchive(
                archive = archive,
                destination = destination,
                onConflict = { conflict ->
                    ArchiveManager.ConflictResolution(when (conflict.entryName) {
                        renamedEntry -> ArchiveManager.ConflictPolicy.RENAME
                        skippedEntry -> ArchiveManager.ConflictPolicy.SKIP
                        else -> error("unexpected conflict: ${conflict.entryName}")
                    })
                },
                onCurrentFile = { file ->
                    assertTrue("current file was reported before opening it: $file", file.isFile)
                    reported += file
                }
            )

            val renamedOutput = File(destination, "目录/字幕🌊（1）.srt")
            assertEquals(format, listOf(renamedOutput), reported)
            assertEquals("new subtitle", renamedOutput.readText())
            assertEquals("existing subtitle", File(destination, renamedEntry).readText())
            assertEquals("existing skipped file", File(destination, skippedEntry).readText())
            assertTrue(File(destination, "empty").isDirectory)
            assertEquals(1, result.skippedCount)
            assertNoExtractionStaging(destination)
        }
    }

    private fun zip(root: File, entries: List<Pair<String, String>>) =
        zipBytes(root, entries.map { (name, text) -> name to text.toByteArray() })

    private fun zipBytes(root: File, entries: List<Pair<String, ByteArray>>): File {
        val archive = File(root, "source.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
        return archive
    }

    private fun corruptStoredPayload(archive: File, name: String) {
        val bytes = archive.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        while (buffer.getInt(offset) == 0x04034b50) {
            val packedSize = buffer.getInt(offset + 18)
            val nameSize = buffer.getShort(offset + 26).toInt() and 0xffff
            val extraSize = buffer.getShort(offset + 28).toInt() and 0xffff
            val payloadOffset = offset + 30 + nameSize + extraSize
            val entryName = String(bytes, offset + 30, nameSize, Charsets.UTF_8)
            if (entryName == name) {
                check(packedSize > 0)
                bytes[payloadOffset] = (bytes[payloadOffset].toInt() xor 0x40).toByte()
                archive.writeBytes(bytes)
                return
            }
            offset = payloadOffset + packedSize
        }
        error("stored ZIP entry not found: $name")
    }

    private fun tarHeader(name: String, size: Int): ByteArray = ByteArray(512).also { header ->
        name.toByteArray().copyInto(header)
        fun octal(offset: Int, length: Int, value: Long) {
            value.toString(8).padStart(length - 1, '0').toByteArray().copyInto(header, offset)
        }
        octal(100, 8, 420)
        octal(108, 8, 0)
        octal(116, 8, 0)
        octal(124, 12, size.toLong())
        octal(136, 12, 0)
        for (index in 148..155) header[index] = 32
        header[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(header, 257)
        "00".toByteArray().copyInto(header, 263)
        octal(148, 7, header.sumOf { it.toInt() and 0xff }.toLong())
        header[155] = 32
    }

    private fun assertNoExtractionStaging(destination: File) {
        assertFalse(destination.walkTopDown().any {
            it.name.startsWith(".subtitleedit-extract-") || it.name.startsWith(".subtitleedit-overwrite-")
        })
    }

    private fun withRoot(action: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "archive-direct-").toFile()
        try {
            action(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
