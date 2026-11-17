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
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.util.SharedFileCache
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Less common codecs use existing upstream fixtures rather than fabricated file headers. */
@RunWith(AndroidJUnit4::class)
class ExtendedAudioArtworkCompatibilityTest {
    @Test fun compareEmbeddedPicturesAcrossAdditionalAudioContainers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val root = File(context.cacheDir, "extended-cover-${System.nanoTime()}").apply { check(mkdirs()) }
        val results = JSONArray()
        try {
            for (sample in listOf("mac-399.ape", "click.mpc", "empty10ms.dsf", "empty10ms.dff", "empty.spx")) {
                for (image in listOf("jpg", "png")) {
                    val extension = sample.substringAfterLast('.')
                    val file = File(root, "covered-$image.$extension")
                    val row = JSONObject().put("extension", extension).put("image", image)
                        .put("sample", sample).put("fixtureSuccess", false).put("storedPictureMatches", false)
                        .put("platformOpened", false).put("platformBytes", 0)
                        .put("platformDecodedBlue", false).put("appArtworkBlue", false)
                    results.put(row)
                    try {
                        assets.open("artwork-compatibility/$sample").use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        }
                        val expected = bluePicture(image)
                        val written = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use {
                            MediaTagNative.write(it.fd, emptyArray(), emptyArray(), 2, expected,
                                if (image == "png") "image/png" else "image/jpeg")
                        }
                        check(written) { "Native tag writer does not support this fixture" }
                        val stored = MediaTagAudioRepository.pictures(file)
                        val matches = stored.any { it.data.contentEquals(expected) }
                        row.put("storedPictureMatches", matches)
                        check(matches) { "Stored cover differs from the original image" }
                        row.put("fixtureSuccess", true)
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.path)
                            row.put("platformOpened", true)
                            val picture = retriever.embeddedPicture
                            row.put("platformBytes", picture?.size ?: 0)
                            val decoded = picture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            row.put("platformDecodedBlue", isBlue(decoded))
                            decoded?.recycle()
                        } catch (error: Exception) {
                            row.put("platformError", error.toString())
                        } finally { runCatching { retriever.release() } }
                        try {
                            val metadata = requireNotNull(SharedFileCache.attributes.refresh(file))
                            row.put("appArtworkBlue", isBlue(SharedMediaCache.artwork(metadata, 128, allowVideoFrame = false)))
                        } catch (error: Exception) {
                            row.put("appArtworkBlue", false).put("appError", error.toString())
                        }
                    } catch (error: Exception) {
                        row.put("error", error.toString())
                    } finally {
                        SharedMediaCache.invalidate(file.path)
                        SharedFileCache.attributes.invalidate(file.path)
                        file.delete()
                    }
                    Log.i("ArtworkCompatibility", row.toString())
                }
            }
            val report = JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("release", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("results", results)
            File(context.filesDir, "artwork-compatibility").apply { mkdirs() }
                .resolve("extended-audio.json").writeText(report.toString(2))
            assertTrue("APE JPEG must have a verified stored cover", (0 until results.length()).any {
                val row = results.getJSONObject(it)
                row.getString("extension") == "ape" && row.getString("image") == "jpg" && row.getBoolean("fixtureSuccess")
            })
        } finally { root.deleteRecursively() }
    }

    private fun bluePicture(image: String): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.BLUE)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(if (image == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun isBlue(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        return Color.blue(pixel) > 180 && Color.red(pixel) < 60 && Color.green(pixel) < 60
    }
}
