package com.subtitleedit.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises archive testing against disposable app-cache inputs, without extracting payloads. */
@RunWith(AndroidJUnit4::class)
class ArchiveTestCancellationTest {
    @Test
    fun cancellingAQueuedTestDoesNotWaitForTheOtherNativeOperationToFinish() = withRoot { root ->
        val archive = zip(root, listOf("payload.txt" to "payload".toByteArray()))
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val otherOperation = Thread {
            try {
                OfficialSevenZip.extract(archive.absolutePath, null, object : SevenZipExtractCallback {
                    override fun onArchiveOpened(volumePaths: Array<String>) {
                        occupied.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    override fun openEntry(name: String, size: Long, isDirectory: Boolean,
                        isSymbolicLink: Boolean, modifiedTimeMillis: Long): Int = -1
                    override fun onProgress(completed: Long, total: Long) = Unit
                    override fun onEntryComplete(success: Boolean) = Unit
                })
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        otherOperation.start()
        try {
            assertTrue(occupied.await(5, TimeUnit.SECONDS))
            val cancelled = AtomicBoolean(false)
            val requested = CancellationException("cancel while waiting for the archive lock")
            val queuedFailure = AtomicReference<Throwable?>()
            val queuedTest = Thread {
                queuedFailure.set(runCatching {
                    ArchiveManager.testArchive(archive,
                        checkCancelled = { if (cancelled.get()) throw requested },
                        onProgress = { _, _ -> cancelled.set(true) })
                }.exceptionOrNull())
            }
            queuedTest.start()
            queuedTest.join(2_000)
            assertTrue("queued test is still waiting for the other native task", !queuedTest.isAlive)
            assertSame(requested, queuedFailure.get())
            assertTrue("the other operation should still own the native lock", otherOperation.isAlive)
        } finally {
            release.countDown()
            otherOperation.join(5_000)
        }
        assertTrue(!otherOperation.isAlive)
        assertEquals(null, failure.get())
    }

    @Test
    fun zipTestChecksContentsAndReportsItsEntriesWithoutWritingOutput() = withRoot { root ->
        val archive = zip(root, listOf(
            "first.txt" to "first".toByteArray(),
            "nested/字幕🌊.txt" to "second".toByteArray()
        ))
        val original = archive.readBytes()
        val updates = mutableListOf<Pair<Long, Long>>()

        val result = ArchiveManager.testArchive(archive, onProgress = { completed, total ->
            updates += completed to total
        })

        assertEquals(2, result.entryCount)
        assertEquals(11L, result.totalBytes)
        assertTrue(updates.isNotEmpty())
        assertTrue("test progress fell backwards: $updates",
            updates.zipWithNext().all { (a, b) -> a.first <= b.first })
        assertArrayEquals(original, archive.readBytes())
        assertOnlyFiles(root, setOf(archive.name))
    }

    @Test
    fun cancellingDuringZipDecodePreservesTheOriginalExceptionAndArchive() = withRoot { root ->
        val archive = File(root, "large.zip")
        val block = ByteArray(64 * 1024) { (it % 251).toByte() }
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("large.bin"))
            repeat(1024) { output.write(block) }
            output.closeEntry()
            output.putNextEntry(ZipEntry("last.txt"))
            output.write("must not finish".toByteArray())
            output.closeEntry()
        }
        val original = archive.readBytes()
        val cancellation = CancellationException("cancel archive test during decoding")
        var cancelledAt = 0L
        var decodeTotal = 0L

        val failure = runCatching {
            ArchiveManager.testArchive(archive, onProgress = { completed, total ->
                if (completed == 0L) {
                    // Let the native progress throttle expire before its next decoder checkpoint.
                    // This makes the test independent of the phone's decode throughput.
                    Thread.sleep(120)
                } else {
                    cancelledAt = completed
                    decodeTotal = total
                    throw cancellation
                }
            })
        }.exceptionOrNull()

        assertSame("the native callback lost the original cancellation", cancellation, failure)
        assertTrue("cancellation happened before any data was decoded", cancelledAt > 0L)
        assertTrue("testing reached the end before cancellation: $cancelledAt / $decodeTotal",
            decodeTotal > cancelledAt)
        assertArrayEquals(original, archive.readBytes())
        assertOnlyFiles(root, setOf(archive.name))
    }

    @Test
    fun preCancelledTestDoesNotReadOrModifyTheArchive() = withRoot { root ->
        val archive = zip(root, listOf("payload.txt" to "payload".toByteArray()))
        val original = archive.readBytes()
        val cancellation = CancellationException("test was cancelled before opening")
        var progressCalls = 0

        val failure = runCatching {
            ArchiveManager.testArchive(
                archive,
                checkCancelled = { throw cancellation },
                onProgress = { _, _ -> progressCalls++ }
            )
        }.exceptionOrNull()

        assertSame(cancellation, failure)
        assertEquals(0, progressCalls)
        assertArrayEquals(original, archive.readBytes())
        assertOnlyFiles(root, setOf(archive.name))
    }

    @Test
    fun corruptedStoredPayloadFailsTheActualCrcTest() = withRoot { root ->
        val archive = zip(root, listOf("broken.txt" to "payload whose crc must be checked".toByteArray()))
        val bytes = archive.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x04034b50, header.getInt(0))
        val nameBytes = header.getShort(26).toInt() and 0xffff
        val extraBytes = header.getShort(28).toInt() and 0xffff
        val payloadOffset = 30 + nameBytes + extraBytes
        bytes[payloadOffset] = (bytes[payloadOffset].toInt() xor 0x40).toByte()
        archive.writeBytes(bytes)

        val failure = runCatching { ArchiveManager.testArchive(archive) }.exceptionOrNull()

        assertNotNull("reading only the ZIP directory must not count as a successful test", failure)
        assertTrue("unexpected failure: $failure", failure is IOException)
        assertArrayEquals(bytes, archive.readBytes())
        assertOnlyFiles(root, setOf(archive.name))
    }

    @Test
    fun encryptedZipAndSevenZipVolumesStillValidatePasswords() = withRoot { root ->
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixtureNames = assets.list("archive-direct")!!.toSet()
        fixtureNames.forEach { name ->
            assets.open("archive-direct/$name").use { input ->
                File(root, name).outputStream().use(input::copyTo)
            }
        }
        val originalFiles = fixtureNames.associateWith { File(root, it).readBytes() }

        for (name in listOf("encrypted.zip", "split.7z.001")) {
            val archive = File(root, name)
            val wrongPassword = runCatching {
                ArchiveManager.testArchive(archive, password = "wrong-password".toCharArray())
            }.exceptionOrNull()
            assertNotNull("$name accepted the wrong password", wrongPassword)
            assertTrue("$name lost the password-error classification: $wrongPassword",
                ArchiveErrorPolicy.needsPassword(archive, checkNotNull(wrongPassword), true))

            val result = ArchiveManager.testArchive(archive, password = "fixture-password".toCharArray())
            assertEquals(name, 1, result.entryCount)
            assertEquals(name, 2048L, result.totalBytes)
        }

        originalFiles.forEach { (name, bytes) -> assertArrayEquals(bytes, File(root, name).readBytes()) }
        assertOnlyFiles(root, fixtureNames)
    }

    @Test
    fun compressedTarCanBeCancelledWhileItsPayloadIsRead() = withRoot { root ->
        val archive = File(root, "large.tar.gz")
        val data = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        GZIPOutputStream(archive.outputStream()).use { output ->
            output.write(tarHeader("payload.bin", data.size))
            output.write(data)
            output.write(ByteArray(1024))
        }
        val original = archive.readBytes()
        val cancellation = CancellationException("cancel compressed TAR test")
        var bytesRead = 0L

        val failure = runCatching {
            ArchiveManager.testArchive(archive, onProgress = { completed, _ ->
                if (completed > 0L) {
                    bytesRead = completed
                    throw cancellation
                }
                Thread.sleep(120)
            })
        }.exceptionOrNull()

        assertSame(cancellation, failure)
        assertTrue(bytesRead > 0L)
        assertArrayEquals(original, archive.readBytes())
        assertOnlyFiles(root, setOf(archive.name))
    }

    @Test
    fun compressedTarValidatesItsOuterChecksumWithoutWritingFiles() = withRoot { root ->
        val archive = File(root, "source.tar.gz")
        val data = "TAR payload".toByteArray()
        GZIPOutputStream(archive.outputStream()).use { output ->
            output.write(tarHeader("payload.txt", data.size))
            output.write(data)
            output.write(ByteArray((512 - data.size % 512) % 512))
            output.write(ByteArray(1024))
        }
        val result = ArchiveManager.testArchive(archive)
        assertEquals(1, result.entryCount)
        assertEquals(data.size.toLong(), result.totalBytes)
        val corrupt = archive.readBytes()
        corrupt[corrupt.size - 8] = (corrupt[corrupt.size - 8].toInt() xor 1).toByte()
        archive.writeBytes(corrupt)
        val failure = runCatching { ArchiveManager.testArchive(archive) }.exceptionOrNull()
        assertTrue("the GZip CRC was not checked: $failure", failure is IOException)
        assertOnlyFiles(root, setOf(archive.name))
    }

    private fun zip(root: File, entries: List<Pair<String, ByteArray>>): File {
        val archive = File(root, "source.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                })
                output.write(bytes)
                output.closeEntry()
            }
        }
        return archive
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

    private fun assertOnlyFiles(root: File, expectedNames: Set<String>) {
        assertEquals("archive test created extracted files or staging directories",
            expectedNames, root.listFiles()!!.map { it.name }.toSet())
        assertTrue(root.listFiles()!!.all { it.isFile })
    }

    private fun withRoot(action: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "archive-test-cancel-").toFile()
        try {
            action(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
