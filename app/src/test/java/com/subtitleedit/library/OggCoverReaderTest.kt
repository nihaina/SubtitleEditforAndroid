package com.subtitleedit.library

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OggCoverReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsVorbisOpusAndSpeexPicturesFromTheirCommentHeaders() {
        val image = byteArrayOf(1, 2, 3, 4)
        for ((header, prefix) in listOf(
            (byteArrayOf(1) + "vorbis".toByteArray()) to (byteArrayOf(3) + "vorbis".toByteArray()),
            "OpusHead".toByteArray() to "OpusTags".toByteArray(),
            "Speex   ".toByteArray() to byteArrayOf())) {
            val bytes = page(1, 2, header) + page(1, 0, prefix + comments(picture(image)))
            assertArrayEquals(image, OggCoverReader.read(file(bytes)))
        }
    }

    @Test fun theoraPagesDoNotHideTheVorbisAudioComment() {
        val image = byteArrayOf(8, 6, 4, 2)
        val bytes = page(10, 2, byteArrayOf(0x80.toByte()) + "theora".toByteArray()) +
            page(20, 2, byteArrayOf(1) + "vorbis".toByteArray()) +
            page(10, 0, byteArrayOf(0x81.toByte()) + "theora".toByteArray()) +
            page(20, 0, byteArrayOf(3) + "vorbis".toByteArray() + comments(picture(image)))
        assertArrayEquals(image, OggCoverReader.read(file(bytes)))
    }

    @Test fun commentPacketsCanSpanPagesAndPreferAFrontCover() {
        val image = ByteArray(600) { (it % 255).toByte() }
        val packet = byteArrayOf(3) + "vorbis".toByteArray() + comments(picture(byteArrayOf(9), 4), picture(image))
        val split = 510
        val first = pageRaw(1, 0, byteArrayOf(255.toByte(), 255.toByte()), packet.copyOfRange(0, split))
        val second = page(1, 1, packet.copyOfRange(split, packet.size))
        assertArrayEquals(image, OggCoverReader.read(file(page(1, 2, byteArrayOf(1) + "vorbis".toByteArray()) + first + second)))
    }

    @Test fun moviePacketsAreNotSearchedForCommentsAfterTheHeaderEnds() {
        val header = page(1, 2, byteArrayOf(1) + "vorbis".toByteArray()) +
            page(1, 0, byteArrayOf(3) + "vorbis".toByteArray() + comments())
        val misleading = page(1, 0, byteArrayOf(3) + "vorbis".toByteArray() + comments(picture(byteArrayOf(2))))
        assertNull(OggCoverReader.read(file(header + misleading)))
    }

    @Test fun corruptTruncatedAndExternalImageCommentsReturnNull() {
        val identification = page(1, 2, "OpusHead".toByteArray())
        val valid = identification + page(1, 0, "OpusTags".toByteArray() + comments(picture(byteArrayOf(1))))
        val cases = listOf(byteArrayOf(), valid.copyOf(valid.size - 1),
            identification + page(1, 0, "OpusTags".toByteArray() + comments("not base64")),
            identification + page(1, 0, "OpusTags".toByteArray() + comments(picture(byteArrayOf(1), mime = "-->"))),
            identification + pageRaw(1, 1, byteArrayOf(1), byteArrayOf(1)))
        cases.forEach { assertNull(OggCoverReader.read(file(it))) }
    }

    private fun file(data: ByteArray): File = temporary.newFile().apply { writeBytes(data) }
    private fun picture(image: ByteArray, type: Int = 3, mime: String = "image/png"): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeInt(type); it.writeInt(mime.length); it.write(mime.toByteArray())
            it.writeInt(0); repeat(4) { _ -> it.writeInt(0) }; it.writeInt(image.size); it.write(image)
        }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }
    private fun comments(vararg pictures: String): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(little(0)); output.write(little(pictures.size))
        pictures.forEach { picture ->
            val field = "METADATA_BLOCK_PICTURE=$picture".toByteArray()
            output.write(little(field.size)); output.write(field)
        }
        return output.toByteArray()
    }
    private fun page(serial: Int, flags: Int, packet: ByteArray): ByteArray {
        val lacing = ByteArray(packet.size / 255 + 1) { index -> if (index < packet.size / 255) 255.toByte() else (packet.size % 255).toByte() }
        return pageRaw(serial, flags, lacing, packet)
    }
    private fun pageRaw(serial: Int, flags: Int, lacing: ByteArray, data: ByteArray): ByteArray {
        val header = ByteArray(27)
        "OggS".toByteArray().copyInto(header)
        header[5] = flags.toByte(); little(serial).copyInto(header, 14); header[26] = lacing.size.toByte()
        return header + lacing + data
    }
    private fun little(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
}
