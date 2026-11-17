package com.subtitleedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.util.FfprobeMediaInformation
import com.subtitleedit.util.SharedFileCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Records device-specific platform support separately from verified artwork stored in the file. */
@RunWith(AndroidJUnit4::class)
class AudioArtworkCompatibilityTest {
    private data class Format(
        val label: String,
        val extension: String,
        val codec: String,
        val muxer: String,
        val id3Version: Int? = null,
        val rf64: Boolean = false
    )

    @Test fun inspectEmbeddedArtworkAcrossAudioContainers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "audio-artwork-compatibility-${System.nanoTime()}").apply { check(mkdirs()) }
        val rows = JSONArray()
        val formats = listOf(
            Format("MP3 ID3v2.3", "mp3", "libmp3lame", "mp3", id3Version = 3),
            Format("MP3 ID3v2.4", "mp3", "libmp3lame", "mp3", id3Version = 4),
            Format("AAC ID3v2.3", "aac", "aac", "adts", id3Version = 3),
            Format("AAC ID3v2.4", "aac", "aac", "adts", id3Version = 4),
            Format("M4A AAC", "m4a", "aac", "ipod"),
            Format("M4B ALAC", "m4b", "alac", "ipod"),
            Format("FLAC", "flac", "flac", "flac"),
            Format("OGG Vorbis", "ogg", "libvorbis", "ogg"),
            Format("OGA FLAC", "oga", "flac", "ogg"),
            Format("Opus", "opus", "libopus", "opus"),
            Format("WMA", "wma", "wmav2", "asf"),
            Format("WavPack", "wv", "wavpack", "wv"),
            Format("AIFF", "aiff", "pcm_s16be", "aiff"),
            Format("AIF", "aif", "pcm_s16be", "aiff"),
            Format("WAV", "wav", "pcm_s16le", "wav"),
            Format("WAV RF64", "wav", "pcm_s16le", "wav", rf64 = true),
            Format("TTA", "tta", "tta", "tta"),
            Format("MKA FLAC", "mka", "flac", "matroska")
        )
        try {
            for ((formatIndex, format) in formats.withIndex()) {
                for (image in listOf("jpeg", "png")) {
                    val bytes = bluePicture(image)
                    val row = JSONObject().put("format", format.label).put("extension", format.extension)
                        .put("codec", format.codec).put("image", image).put("fixtureSuccess", false)
                        .put("expectedPictureBytes", bytes.size).put("storedPictureMatches", false)
                        .put("platformOpened", false).put("platformBytes", 0)
                        .put("platformDecodedBlue", false).put("appArtworkBlue", false)
                    rows.put(row)
                    val file = File(root, "fixture-$formatIndex-$image.${format.extension}")
                    try {
                        createFixture(format, file, image, bytes, root)
                        val stored = storedPicture(format, file)
                        val matches = stored?.contentEquals(bytes) == true
                        row.put("storedPictureMatches", matches).put("storedPictureBytes", stored?.size ?: 0)
                        check(matches) { "Stored cover readback did not match the original image" }
                        if (format.rf64) {
                            val streams = FfprobeMediaInformation.read(file.path, root).getStreams()
                            check(streams.any { it.getType() == "audio" }) { "RF64 audio is not readable after attaching ID3" }
                            val attached = streams.firstOrNull {
                                it.getAllProperties()?.optJSONObject("disposition")?.optInt("attached_pic", 0) == 1
                            }
                            if (attached != null) {
                                val exported = File(root, "rf64-stored-$image.${if (image == "jpeg") "jpg" else "png"}")
                                val cover = requireNotNull(MediaTagCoverExport.cover(MediaTagStream(
                                    requireNotNull(attached.getIndex()), "video", attachedPicture = true), attached.getCodec(), null))
                                ffmpeg(MediaTagCoverExport.arguments(file.path, exported.path, cover))
                                val probeMatches = exported.readBytes().contentEquals(bytes)
                                row.put("ffmpegExtractedPictureMatches", probeMatches)
                                check(probeMatches) { "RF64 FFmpeg cover readback did not match" }
                            }
                            row.put("ffprobeAudioVerified", true)
                        }
                        row.put("fixtureSuccess", true)
                        inspectPlatform(file, row)
                        try {
                            val metadata = requireNotNull(SharedFileCache.attributes.refresh(file))
                            val artwork = SharedMediaCache.artwork(metadata, 128, allowVideoFrame = false)
                            row.put("appArtworkBlue", artwork?.let(::isBlue) == true)
                        } catch (error: Throwable) {
                            row.put("appError", errorDescription(error))
                        }
                    } catch (error: Throwable) {
                        row.put("error", errorDescription(error))
                    } finally {
                        SharedMediaCache.invalidate(file.path)
                        SharedFileCache.attributes.invalidate(file.path)
                    }
                    Log.i("ArtworkCompatibility", row.toString())
                }
            }
        } finally {
            val report = JSONObject().put("suite", "audio")
                .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("androidRelease", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("results", rows)
            val reportDirectory = File(context.filesDir, "artwork-compatibility").apply { check(exists() || mkdirs()) }
            File(reportDirectory, "audio.json").writeText(report.toString(2))
            root.deleteRecursively()
        }
        // Platform omissions are diagnostic results. Only broken mainstream fixtures fail this audit.
        val required = setOf("MP3 ID3v2.3", "MP3 ID3v2.4", "M4A AAC", "FLAC", "OGG Vorbis", "Opus", "WMA", "WavPack", "AIFF", "WAV", "MKA FLAC")
        val broken = (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.getString("format") in required && !it.getBoolean("fixtureSuccess") }
        assertTrue("Failed to prepare independent cover fixtures: $broken", broken.isEmpty())
    }

    private fun createFixture(format: Format, file: File, image: String, bytes: ByteArray, root: File) {
        val args = mutableListOf("-y", "-hide_banner", "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "0.3", "-c:a", format.codec)
        if (format.extension == "mp3") args += listOf("-id3v2_version", "0", "-write_id3v1", "0")
        if (format.rf64) args += listOf("-rf64", "always")
        args += listOf("-f", format.muxer, file.path)
        ffmpeg(args)
        when {
            format.id3Version != null -> {
                prependId3Cover(file, format.id3Version, "image/$image", bytes)
            }
            format.rf64 -> appendRf64Cover(file, "image/$image", bytes)
            format.muxer == "matroska" -> {
                val picture = File(root, "cover.${if (image == "jpeg") "jpg" else "png"}").apply { writeBytes(bytes) }
                val staged = File(root, "covered-${file.name}")
                ffmpeg(listOf("-y", "-i", file.path, "-map", "0:a:0", "-c", "copy", "-attach", picture.path,
                    "-metadata:s:t:0", "filename=${picture.name}", "-metadata:s:t:0", "mimetype=image/$image", "-f", "matroska", staged.path))
                check(file.delete() && staged.renameTo(file))
            }
            else -> {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                    check(MediaTagNative.supportsCover(descriptor.fd)) { "TagLib does not support artwork for this fixture" }
                    check(MediaTagNative.write(descriptor.fd, emptyArray(), emptyArray(), 2, bytes, "image/$image")) {
                        "TagLib could not write this cover"
                    }
                }
            }
        }
    }

    private fun storedPicture(format: Format, file: File): ByteArray? {
        return when {
            format.id3Version != null -> readId3Picture(file, format.id3Version)
            format.rf64 -> readRf64Cover(file)
            format.muxer == "matroska" -> {
                val stream = FfprobeMediaInformation.read(file.path, file.parentFile!!).getStreams().firstOrNull {
                    it.getAllProperties()?.optJSONObject("disposition")?.optInt("attached_pic", 0) == 1 ||
                        (it.getType() == "attachment" && it.getTags()?.optString("mimetype").orEmpty().startsWith("image/"))
                } ?: return null
                val picture = requireNotNull(MediaTagCoverExport.cover(
                    MediaTagStream(requireNotNull(stream.getIndex()), stream.getType().orEmpty(),
                        attachedPicture = stream.getType() == "video", coverAttachment = stream.getType() == "attachment"),
                    stream.getCodec(), stream.getTags()?.optString("filename")))
                val extracted = File(file.parentFile, "stored-${file.name}.${picture.extension}")
                try {
                    ffmpeg(MediaTagCoverExport.arguments(file.path, extracted.path, picture))
                    extracted.readBytes()
                } finally { extracted.delete() }
            }
            else -> MediaTagAudioRepository.pictures(file).firstOrNull()?.data
        }
    }

    private fun inspectPlatform(file: File, row: JSONObject) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            row.put("platformOpened", true)
            val picture = retriever.embeddedPicture
            row.put("platformBytes", picture?.size ?: 0)
            val decoded = picture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            try {
                row.put("platformDecodedBlue", decoded?.let(::isBlue) == true)
            } finally { decoded?.recycle() }
        } catch (error: Throwable) {
            row.put("platformError", errorDescription(error))
        } finally { runCatching { retriever.release() } }
    }

    private fun bluePicture(image: String): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.BLUE)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(if (image == "jpeg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun isBlue(bitmap: Bitmap): Boolean {
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        return Color.blue(pixel) > 180 && Color.red(pixel) < 60 && Color.green(pixel) < 60
    }

    private fun prependId3Cover(file: File, version: Int, mime: String, picture: ByteArray) {
        val audio = file.readBytes()
        check(!audio.take(3).toByteArray().contentEquals("ID3".toByteArray())) { "Expected untagged input" }
        file.writeBytes(id3Cover(version, mime, picture) + audio)
    }

    private fun id3Cover(version: Int, mime: String, picture: ByteArray): ByteArray {
        val body = byteArrayOf(0) + mime.toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 3, 0) + picture
        val size = if (version == 4) syncsafe(body.size) else ByteBuffer.allocate(4).putInt(body.size).array()
        val frame = "APIC".toByteArray(Charsets.US_ASCII) + size + byteArrayOf(0, 0) + body
        val header = "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(version.toByte(), 0, 0) + syncsafe(frame.size)
        return header + frame
    }

    private fun appendRf64Cover(file: File, mime: String, picture: ByteArray) {
        val tag = id3Cover(3, mime, picture)
        RandomAccessFile(file, "rw").use { output ->
            val header = ByteArray(20).also(output::readFully)
            check(String(header, 0, 4, Charsets.US_ASCII) == "RF64")
            check(String(header, 12, 4, Charsets.US_ASCII) == "ds64")
            output.seek(output.length())
            output.write("id3 ".toByteArray(Charsets.US_ASCII))
            output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(tag.size).array())
            output.write(tag)
            if (tag.size % 2 != 0) output.write(0)
            val riffSize = output.length() - 8L
            output.seek(20L)
            output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(riffSize).array())
        }
    }

    private fun readRf64Cover(file: File): ByteArray? {
        val bytes = file.readBytes()
        val dataSize = ByteBuffer.wrap(bytes, 28, 8).order(ByteOrder.LITTLE_ENDIAN).long
        var offset = 12L
        while (offset + 8 <= bytes.size) {
            val cursor = offset.toInt()
            val name = String(bytes, cursor, 4, Charsets.US_ASCII)
            val storedSize = ByteBuffer.wrap(bytes, cursor + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
            val length = if (name == "data" && storedSize == 0xffff_ffffL) dataSize else storedSize
            check(length >= 0 && offset + 8 + length <= bytes.size)
            if (name.equals("id3 ", ignoreCase = true)) {
                return readId3Picture(bytes.copyOfRange(cursor + 8, (offset + 8 + length).toInt()), 3)
            }
            offset += 8 + length + length % 2
        }
        return null
    }

    /** Read back the ID3 frame from disk instead of trusting that the write call succeeded. */
    private fun readId3Picture(file: File, version: Int): ByteArray? {
        return readId3Picture(file.readBytes(), version)
    }

    private fun readId3Picture(bytes: ByteArray, version: Int): ByteArray? {
        check(String(bytes, 0, 3, Charsets.US_ASCII) == "ID3" && bytes[3].toInt() == version)
        val end = 10 + syncsafeValue(bytes, 6)
        var offset = 10
        while (offset + 10 <= end) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = if (version == 4) syncsafeValue(bytes, offset + 4) else ByteBuffer.wrap(bytes, offset + 4, 4).int
            check(length >= 0 && offset + 10L + length <= end)
            if (id == "APIC") {
                val body = offset + 10
                check(bytes[body] == 0.toByte())
                val mimeEnd = (body + 1 until body + length).first { bytes[it] == 0.toByte() }
                check(bytes[mimeEnd + 1] == 3.toByte() && bytes[mimeEnd + 2] == 0.toByte())
                return bytes.copyOfRange(mimeEnd + 3, body + length)
            }
            offset += 10 + length
        }
        return null
    }

    private fun syncsafe(value: Int) = ByteArray(4) { index -> ((value ushr (21 - index * 7)) and 127).toByte() }
    private fun syncsafeValue(bytes: ByteArray, offset: Int) = (0..3).fold(0) { value, index ->
        (value shl 7) or (bytes[offset + index].toInt() and 127)
    }
    private fun errorDescription(error: Throwable) = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(1_000)
    private fun ffmpeg(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        check(ReturnCode.isSuccess(session.getReturnCode())) { session.getAllLogsAsString().takeLast(1_000) }
    }
}
