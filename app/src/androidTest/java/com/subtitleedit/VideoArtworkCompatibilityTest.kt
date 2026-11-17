package com.subtitleedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.util.FfprobeMediaInformation
import com.subtitleedit.util.SharedFileCache
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic matrix: a missing platform cover is a result, rather than a failed fixture. */
@RunWith(AndroidJUnit4::class)
class VideoArtworkCompatibilityTest {
    private data class Fixture(val extension: String, val muxer: String, val video: String,
                               val audio: String, val storage: String)

    @Test fun compareStoredPlatformAndApplicationCovers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "video-artwork-matrix-${System.nanoTime()}").apply { check(mkdirs()) }
        val rows = JSONArray()
        try {
            val fixtures = listOf(
                Fixture("mp4", "mp4", "libx264", "aac", "attached_pic"),
                Fixture("m4v", "ipod", "libx264", "aac", "attached_pic"),
                Fixture("mov", "mov", "mpeg4", "aac", "covr"),
                Fixture("mkv", "matroska", "libx264", "aac", "attachment"),
                Fixture("wmv", "asf", "wmv2", "wmav2", "WM/Picture"),
                Fixture("asf", "asf", "wmv2", "wmav2", "WM/Picture"),
                Fixture("webm", "webm", "libvpx", "libvorbis", "attachment"),
                Fixture("ogv", "ogg", "libtheora", "libvorbis", "METADATA_BLOCK_PICTURE")
            )
            for (imageExtension in listOf("jpg", "png")) {
                val image = File(root, "blue.$imageExtension")
                val mime = if (imageExtension == "jpg") "image/jpeg" else "image/png"
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.BLUE)
                    image.outputStream().use {
                        check(bitmap.compress(if (imageExtension == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, it))
                    }
                } finally { bitmap.recycle() }
                val expected = image.readBytes()
                for (fixture in fixtures) {
                    val row = JSONObject().put("extension", fixture.extension).put("codec", fixture.video)
                        .put("image", imageExtension).put("storage", fixture.storage)
                        .put("fixtureSuccess", false).put("storedPictureMatches", false)
                        .put("platformOpened", false).put("platformBytes", 0)
                        .put("platformDecodedBlue", false).put("appArtworkBlue", false)
                    rows.put(row)
                    val file = File(root, "${fixture.extension}-$imageExtension.${fixture.extension}")
                    try {
                        val base = File(root, "base-${fixture.extension}-$imageExtension.${fixture.extension}")
                        val block = if (fixture.storage == "METADATA_BLOCK_PICTURE") pictureBlock(expected, mime) else null
                        ffmpeg(buildList {
                            addAll(listOf("-y", "-f", "lavfi", "-i", "color=c=black:s=160x120:r=10",
                                "-f", "lavfi", "-i", "sine=sample_rate=44100", "-t", "0.3",
                                "-c:v", fixture.video, "-c:a", fixture.audio))
                            if (fixture.video == "libx264") addAll(listOf("-preset", "ultrafast"))
                            if (fixture.video == "libvpx") addAll(listOf("-deadline", "realtime", "-cpu-used", "8"))
                            if (block != null) addAll(listOf("-metadata:s:a:0", "METADATA_BLOCK_PICTURE=$block"))
                            addAll(listOf("-f", fixture.muxer, base.path))
                        })
                        when (fixture.storage) {
                            "WM/Picture", "covr" -> {
                                base.copyTo(file)
                                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                                    check(MediaTagNative.write(descriptor.fd, emptyArray(), emptyArray(), 2, expected, mime)) {
                                        "TagLib could not embed ${fixture.storage}"
                                    }
                                }
                            }
                            "METADATA_BLOCK_PICTURE" -> base.copyTo(file)
                            else -> ffmpeg(buildList {
                                addAll(listOf("-y", "-i", base.path))
                                if (fixture.storage == "attached_pic") {
                                    addAll(listOf("-i", image.path, "-map", "0", "-map", "1:v:0", "-c", "copy",
                                        "-disposition:v:1", "attached_pic"))
                                } else {
                                    addAll(listOf("-map", "0", "-c", "copy", "-attach", image.path,
                                        "-metadata:s:t:0", "mimetype=$mime", "-metadata:s:t:0", "filename=cover.$imageExtension"))
                                }
                                addAll(listOf("-f", fixture.muxer, file.path))
                            })
                        }
                        val matches = verifyPicture(context, root, file, expected, block, row)
                        row.put("storedPictureMatches", matches)
                        check(matches) { "The generated container did not retain a verifiable original cover" }
                        row.put("fixtureSuccess", true)
                        measurePlatform(file, row)
                        try {
                            val metadata = requireNotNull(SharedFileCache.attributes.refresh(file))
                            row.put("appArtworkBlue", isBlue(SharedMediaCache.artwork(metadata, 128, allowVideoFrame = false)))
                        } catch (error: Throwable) {
                            row.put("appError", error.toString())
                        }
                    } catch (error: Throwable) {
                        row.put("error", error.toString().takeLast(3000))
                    } finally {
                        SharedMediaCache.invalidate(file.path)
                        SharedFileCache.attributes.invalidate(file.path)
                        Log.i("ArtworkVideoMatrix", row.toString())
                    }
                }
            }
        } finally {
            val report = JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("release", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("results", rows)
            val reports = File(context.filesDir, "artwork-compatibility").apply { mkdirs() }
            File(reports, "video.json").writeText(report.toString(2))
            root.deleteRecursively()
        }
        assertTrue("MP4 and MKV JPEG/PNG baseline fixtures must contain their original covers",
            (0 until rows.length()).map(rows::getJSONObject)
                .filter { it.getString("extension") in setOf("mp4", "mkv") }
                .all { it.getBoolean("fixtureSuccess") })
    }

    private fun verifyPicture(context: Context, root: File, file: File, expected: ByteArray,
                              block: String?, row: JSONObject): Boolean {
        val native = runCatching { MediaTagAudioRepository.pictures(file) }.getOrNull()
        if (native?.any { it.data.contentEquals(expected) } == true) {
            row.put("verification", "TagLib picture bytes")
            return true
        }
        val info = FfprobeMediaInformation.read(file.path, context.cacheDir)
        val cover = info.getStreams().firstNotNullOfOrNull { stream ->
            val tags = stream.getTags()
            val filename = tags?.optString("filename").orEmpty().lowercase(Locale.ROOT)
            MediaTagCoverExport.cover(MediaTagStream(requireNotNull(stream.getIndex()), stream.getType().orEmpty(),
                attachedPicture = stream.getAllProperties()?.optJSONObject("disposition")?.optInt("attached_pic", 0) == 1,
                coverAttachment = stream.getType() == "attachment" &&
                    tags?.optString("mimetype").orEmpty().startsWith("image/") &&
                    filename.substringBeforeLast('.') in setOf("cover", "cover_land", "small_cover", "small_cover_land")),
                stream.getCodec(), filename)
        }
        if (cover != null) {
            val extracted = File(root, "extracted.${cover.extension}")
            ffmpeg(MediaTagCoverExport.arguments(file.path, extracted.path, cover))
            if (extracted.readBytes().contentEquals(expected)) {
                row.put("verification", "FFprobe and original extracted picture bytes")
                return true
            }
        }
        if (block != null) {
            // Read the saved comment through a real Ogg demux/remux and a separate tag parser.
            // A matching text string elsewhere in the container is not sufficient evidence.
            val audio = File(root, "verified-${file.name}.ogg")
            ffmpeg(listOf("-y", "-i", file.path, "-map", "0:a:0", "-c:a", "copy",
                "-map_metadata", "0:s:a:0", "-f", "ogg", audio.path))
            if (MediaTagAudioRepository.pictures(audio).any { it.data.contentEquals(expected) }) {
                row.put("verification", "Vorbis audio extracted from OGV and original TagLib picture bytes")
                return true
            }
        }
        return false
    }

    private fun measurePlatform(file: File, row: JSONObject) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            row.put("platformOpened", true)
            val data = retriever.embeddedPicture
            row.put("platformBytes", data?.size ?: 0)
            val bitmap = data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            try { row.put("platformDecodedBlue", isBlue(bitmap)) } finally { bitmap?.recycle() }
        } catch (error: Throwable) {
            row.put("platformError", error.toString())
        } finally { runCatching { retriever.release() } }
    }

    private fun isBlue(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        return Color.blue(color) > 180 && Color.red(color) < 60 && Color.green(color) < 60
    }

    private fun pictureBlock(data: ByteArray, mime: String): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(3)
            stream.writeInt(mime.length)
            stream.write(mime.toByteArray(Charsets.US_ASCII))
            stream.writeInt(0)
            stream.writeInt(64)
            stream.writeInt(64)
            stream.writeInt(24)
            stream.writeInt(0)
            stream.writeInt(data.size)
            stream.write(data)
        }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun ffmpeg(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        check(ReturnCode.isSuccess(session.getReturnCode())) {
            "$arguments\n${session.getAllLogsAsString().takeLast(2200)}"
        }
    }
}
