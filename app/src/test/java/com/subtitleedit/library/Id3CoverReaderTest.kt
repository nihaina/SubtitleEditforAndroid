package com.subtitleedit.library

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Id3CoverReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsAllSupportedId3VersionsAndPrefersFrontCover() {
        val expected = byteArrayOf(1, 3, 5, 7)
        for (version in 2..4) {
            val tag = tag(version, frame(version, byteArrayOf(9), 4) + frame(version, expected, 3))
            assertArrayEquals(expected, Id3CoverReader.read(file(tag + ByteArray(10))))
        }
    }

    @Test fun unicodeDescriptionsAndUnsynchronizedPicturesAreReadWithoutChangingImageBytes() {
        val image = byteArrayOf(0xff.toByte(), 0xe1.toByte(), 1, 2)
        val description = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 'A'.code.toByte(), 0, 0, 0)
        val body = byteArrayOf(1) + "image/jpeg".toByteArray() + byteArrayOf(0, 3) + description + image
        val rawFrame = "APIC".toByteArray() + int(body.size) + byteArrayOf(0, 0) + body
        val unsynchronized = ByteArrayOutputStream().apply {
            rawFrame.forEach { value -> write(value.toInt()); if (value == 0xff.toByte()) write(0) }
        }.toByteArray()
        val source = "ID3".toByteArray() + byteArrayOf(3, 0, 0x80.toByte()) + syncsafe(unsynchronized.size) + unsynchronized
        assertArrayEquals(image, Id3CoverReader.read(file(source)))
    }

    @Test fun riffChunkAfterAudioContainsTheOriginalPicture() {
        val image = byteArrayOf(2, 4, 6)
        val content = chunk("fmt ", ByteArray(16)) + chunk("data", ByteArray(1024)) + chunk("id3 ", tag(3, frame(3, image)))
        assertArrayEquals(image, Id3CoverReader.read(file("RIFF".toByteArray() + little(content.size + 4L, 4) + "WAVE".toByteArray() + content)))
    }

    @Test fun rf64SeeksPastAudioLargerThanTwoGiBUsingDs64() {
        val image = byteArrayOf(2, 4, 8)
        val audioSize = 3L * 1024 * 1024 * 1024
        val pictureChunk = chunk("id3 ", tag(4, frame(4, image)))
        val output = File(temporary.root, "large-rf64.wav")
        Files.newByteChannel(output.toPath(), StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, StandardOpenOption.SPARSE).close()
        RandomAccessFile(output, "rw").use { target ->
            val total = 12 + 36L + 8 + audioSize + pictureChunk.size
            target.write("RF64".toByteArray() + little(0xffff_ffffL, 4) + "WAVE".toByteArray())
            target.write(chunk("ds64", little(total - 8, 8) + little(audioSize, 8) + little(0, 8) + little(0, 4)))
            target.write("data".toByteArray() + little(0xffff_ffffL, 4))
            target.seek(target.filePointer + audioSize)
            target.write(pictureChunk)
        }
        assertArrayEquals(image, Id3CoverReader.read(output))
    }

    @Test fun missingTruncatedOversizedAndExternalPicturesAreRejected() {
        val valid = tag(3, frame(3, byteArrayOf(1, 2)))
        val external = byteArrayOf(0) + "-->".toByteArray() + byteArrayOf(0, 3, 0) + "https://image".toByteArray()
        val externalFrame = "APIC".toByteArray() + int(external.size) + byteArrayOf(0, 0) + external
        val cases = listOf(byteArrayOf(), ByteArray(10), valid.copyOf(valid.size - 1),
            "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncsafe(40 * 1024 * 1024), tag(3, externalFrame),
            tag(3, frame(3, ByteArray(16 * 1024 * 1024 + 1))))
        cases.forEach { assertNull(Id3CoverReader.read(file(it))) }
    }

    @Test fun malformedRf64SizeTablesDoNotSearchAudioForPictureSignatures() {
        val invalid = "RF64".toByteArray() + little(0xffff_ffffL, 4) + "WAVE".toByteArray() +
            chunk("ds64", little(Long.MAX_VALUE, 8) + little(0, 8) + little(0, 8) + little(0, 4)) +
            chunk("id3 ", tag(3, frame(3, byteArrayOf(1))))
        assertNull(Id3CoverReader.read(file(invalid)))
    }

    private fun file(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }
    private fun tag(version: Int, frames: ByteArray) = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncsafe(frames.size) + frames
    private fun frame(version: Int, image: ByteArray, type: Int = 3): ByteArray {
        val body = byteArrayOf(0) + (if (version == 2) "PNG".toByteArray() else "image/png".toByteArray() + byteArrayOf(0)) +
            byteArrayOf(type.toByte(), 0) + image
        return if (version == 2) "PIC".toByteArray() + byteArrayOf((body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
        else "APIC".toByteArray() + (if (version == 4) syncsafe(body.size) else int(body.size)) + byteArrayOf(0, 0) + body
    }
    private fun chunk(name: String, data: ByteArray) = name.toByteArray() + little(data.size.toLong(), 4) + data + if (data.size % 2 != 0) byteArrayOf(0) else byteArrayOf()
    private fun int(value: Int) = ByteArrayOutputStream().apply { DataOutputStream(this).writeInt(value) }.toByteArray()
    private fun syncsafe(value: Int) = ByteArray(4) { ((value ushr (21 - it * 7)) and 127).toByte() }
    private fun little(value: Long, width: Int) = ByteArray(width) { (value ushr (it * 8)).toByte() }
}
