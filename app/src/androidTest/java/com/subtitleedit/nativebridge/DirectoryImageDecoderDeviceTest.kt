package com.subtitleedit.nativebridge

import android.graphics.Bitmap
import android.graphics.Color
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
    @Test fun ffmpegPreservesOriginalResolutionAndAlpha() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val workDir = File(cache, "image-decode-${System.nanoTime()}").apply { check(mkdir()) }
        val source = File(workDir, "原图 01.png")
        val original = Bitmap.createBitmap(5000, 64, Bitmap.Config.ARGB_8888)
        try {
            original.setPixel(4999, 63, Color.RED)
            source.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = checkNotNull(DirectoryImageDecoder.decode(source, workDir))
            try {
                assertEquals(original.width, decoded.width)
                assertEquals(original.height, decoded.height)
                assertEquals(Color.TRANSPARENT, decoded.getPixel(0, 0))
                assertEquals(Color.RED, decoded.getPixel(4999, 63))
                assertEquals(listOf(source), workDir.listFiles()?.toList())
            } finally {
                decoded.recycle()
            }
        } finally {
            original.recycle()
            workDir.deleteRecursively()
        }
    }
}
