package com.subtitleedit.nativebridge

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.compose.ui.unit.IntRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryImageDecoderDeviceTest {
    @Test fun regionDecoderPreviewsAnOversizedImageAndRetainsOriginalPixels() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val workDir = File(cache, "image-decode-${System.nanoTime()}").apply { check(mkdir()) }
        val source = File(workDir, "原图 01.png")
        val original = Bitmap.createBitmap(10_000, 128, Bitmap.Config.ARGB_8888)
        try {
            original.setPixel(9999, 127, Color.RED)
            source.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val opened = checkNotNull(DirectoryImageDecoder.open(source, workDir))
            try {
                assertEquals(original.width, opened.size.width)
                assertEquals(original.height, opened.size.height)
                val overview = checkNotNull(opened.decode(IntRect(0, 0, 10_000, 128), 8))
                val detail = checkNotNull(opened.decode(IntRect(9936, 64, 10_000, 128), 1))
                try {
                    assertEquals(1250, overview.width)
                    assertEquals(64, detail.width)
                    assertEquals(Color.TRANSPARENT, detail.getPixel(0, 0))
                    assertEquals(Color.RED, detail.getPixel(63, 63))
                } finally {
                    detail.recycle()
                    overview.recycle()
                }
                assertEquals(listOf(source), workDir.listFiles()?.toList())
            } finally {
                opened.close()
            }
        } finally {
            original.recycle()
            workDir.deleteRecursively()
        }
    }

    @Test fun ffmpegMakesUnsupportedFormatsAvailableForRegionDecoding() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val workDir = File(cache, "image-fallback-${System.nanoTime()}").apply { check(mkdir()) }
        try {
            val source = File(workDir, "原图 01.gif")
            source.writeBytes(Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT))
            val opened = checkNotNull(DirectoryImageDecoder.open(source, workDir))
            try {
                assertTrue(workDir.listFiles().orEmpty().any { it.extension == "png" })
                val decoded = checkNotNull(opened.decode(IntRect(0, 0, 1, 1), 1))
                try {
                    assertEquals(Color.TRANSPARENT, decoded.getPixel(0, 0))
                } finally {
                    decoded.recycle()
                }
            } finally {
                opened.close()
            }
            assertEquals(listOf(source), workDir.listFiles()?.toList())
        } finally {
            workDir.deleteRecursively()
        }
    }
}
