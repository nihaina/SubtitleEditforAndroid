package com.subtitleedit.library

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MatroskaCoverReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsOriginalCoverBytesAndPrefersTheFullPortraitCover() {
        val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        val attachments = attachments(
            picture("small_cover.jpg", byteArrayOf(3)),
            picture("cover_land.png", byteArrayOf(2)),
            picture("COVER.PNG", expected, "IMAGE/PNG"),
            picture("cover.jpg", byteArrayOf(9))
        )
        assertArrayEquals(expected, MatroskaCoverReader.read(file(segment(attachments))))
    }

    @Test fun ignoresFontsAndImagesThatAreNotNamedAsCovers() {
        val content = attachments(
            picture("cover.ttf", byteArrayOf(1), "application/x-truetype-font"),
            picture("poster.jpg", byteArrayOf(2)),
            picture("/other/cover.jpg", byteArrayOf(3))
        )
        assertNull(MatroskaCoverReader.read(file(segment(content))))
    }

    @Test fun seeksPastALargeClusterWithoutReadingItsPackets() {
        val image = byteArrayOf(5, 4, 3, 2, 1)
        val attached = attachments(picture("cover.png", image))
        val clusterSize = 128L * 1024 * 1024
        val clusterHeader = id(0x1F43B675) + size(clusterSize)
        val contentSize = clusterHeader.size + clusterSize + attached.size
        val output = temporary.newFile()
        RandomAccessFile(output, "rw").use { stream ->
            stream.write(header())
            stream.write(id(0x18538067) + size(contentSize))
            stream.write(clusterHeader)
            stream.seek(stream.filePointer + clusterSize)
            stream.write(attached)
        }
        assertArrayEquals(image, MatroskaCoverReader.read(output))
    }

    @Test fun seekHeadFindsAttachmentsBeyondAnUnknownSizeCluster() {
        val image = byteArrayOf(11, 22, 33)
        val cluster = id(0x1F43B675) + byteArrayOf(0xff.toByte()) + ByteArray(1_024)
        val headSize = seekHead(0).size
        val attached = attachments(picture("cover.png", image))
        val head = seekHead(headSize.toLong() + cluster.size)
        val content = head + cluster + attached
        assertArrayEquals(image, MatroskaCoverReader.read(file(segment(content, unknownSize = true))))
    }

    @Test fun unknownSizeClusterWithoutASeekIndexIsNotSearchedForAttachmentLikeBytes() {
        val nested = attachments(picture("cover.png", byteArrayOf(1, 2, 3)))
        val cluster = id(0x1F43B675) + byteArrayOf(0xff.toByte()) + nested
        assertNull(MatroskaCoverReader.read(file(segment(cluster, unknownSize = true))))
    }

    @Test fun seekHeadCyclesCannotLoopForever() {
        val self = element(0x114D9B74, element(0x4DBB,
            element(0x53AB, id(0x114D9B74)) + element(0x53AC, byteArrayOf(0))))
        assertNull(MatroskaCoverReader.read(file(segment(self))))
    }

    @Test fun oversizedPicturesAreSkippedWithoutAllocatingThem() {
        val imageSize = 16L * 1024 * 1024 + 1L
        val fields = element(0x466E, "cover.png".toByteArray()) + element(0x4660, "image/png".toByteArray())
        val dataHeader = id(0x465C) + size(imageSize)
        val fileHeader = id(0x61A7) + size(fields.size + dataHeader.size + imageSize)
        val attachmentsHeader = id(0x1941A469) + size(fileHeader.size + fields.size + dataHeader.size + imageSize)
        val output = temporary.newFile()
        RandomAccessFile(output, "rw").use { stream ->
            stream.write(header())
            stream.write(id(0x18538067) + byteArrayOf(0xff.toByte()))
            stream.write(attachmentsHeader + fileHeader + fields + dataHeader)
            stream.setLength(stream.filePointer + imageSize)
        }
        assertNull(MatroskaCoverReader.read(output))
    }

    @Test fun malformedAndTruncatedFilesReturnNull() {
        val valid = segment(attachments(picture("cover.png", byteArrayOf(1, 2, 3))))
        val malformed = listOf(
            byteArrayOf(), byteArrayOf(0, 0), "not matroska".toByteArray(),
            valid.copyOf(valid.size - 1),
            header() + id(0x18538067) + byteArrayOf(0),
            header() + id(0x18538067) + size(100),
            segment(element(0x1941A469, id(0x61A7) + byteArrayOf(0xff.toByte()))),
            segment(element(0x114D9B74, element(0x4DBB,
                element(0x53AB, id(0x1941A469)) + element(0x53AC, ByteArray(8) { 0xff.toByte() }))))
        )
        malformed.forEach { assertNull(MatroskaCoverReader.read(file(it))) }
    }

    @Test fun excessiveElementCountsAreBounded() {
        val emptyElement = element(0xEC, byteArrayOf())
        val content = ByteArray(emptyElement.size * 16_385) { emptyElement[it % emptyElement.size] }
        assertNull(MatroskaCoverReader.read(file(segment(content))))
    }

    @Test fun imageDataCanPrecedeTheAttachmentNameAndMimeType() {
        val image = byteArrayOf(2, 4, 6)
        val attachment = element(0x61A7, element(0x465C, image) +
            element(0x4660, "image/png".toByteArray()) + element(0x466E, "small_cover_land.png".toByteArray()))
        assertArrayEquals(image, MatroskaCoverReader.read(file(segment(element(0x1941A469, attachment)))))
    }

    private fun file(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }
    private fun header() = element(0x1A45DFA3, element(0x4282, "matroska".toByteArray()))
    private fun segment(content: ByteArray, unknownSize: Boolean = false): ByteArray = header() +
        id(0x18538067) + (if (unknownSize) byteArrayOf(0xff.toByte()) else size(content.size.toLong())) + content
    private fun attachments(vararg files: ByteArray) = element(0x1941A469, files.fold(byteArrayOf()) { all, item -> all + item })
    private fun picture(name: String, data: ByteArray, mime: String = "image/png") = element(0x61A7,
        element(0x466E, name.toByteArray()) + element(0x4660, mime.toByteArray()) + element(0x465C, data))
    private fun seekHead(position: Long) = element(0x114D9B74, element(0x4DBB,
        element(0x53AB, id(0x1941A469)) + element(0x53AC, ByteArray(8) { index ->
            (position ushr (7 - index) * 8).toByte()
        })))
    private fun element(identifier: Long, data: ByteArray) = id(identifier) + size(data.size.toLong()) + data
    private fun id(value: Long): ByteArray {
        val length = (1..4).first { value ushr (it * 8) == 0L }
        return ByteArray(length) { (value ushr ((length - it - 1) * 8)).toByte() }
    }
    private fun size(value: Long): ByteArray {
        val length = (1..8).first { value < (1L shl (it * 7)) - 1 }
        val bytes = ByteArray(length) { (value ushr ((length - it - 1) * 8)).toByte() }
        bytes[0] = (bytes[0].toInt() or (0x80 ushr (length - 1))).toByte()
        return bytes
    }
}
