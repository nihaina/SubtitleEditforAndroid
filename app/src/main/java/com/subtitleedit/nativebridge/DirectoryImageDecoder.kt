package com.subtitleedit.nativebridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.ExifInterface
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object DirectoryImageDecoder {
    private val ffmpegMutex = Mutex()

    suspend fun open(source: File, cacheDir: File): DirectoryImageSource? {
        openRegionDecoder(source)?.let { decoder ->
            val orientation = when (source.extension.lowercase()) {
                "jpg", "jpeg", "png", "webp" -> runCatching {
                    ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                }.getOrDefault(1)
                else -> 1
            }
            return DirectoryImageSource(decoder, orientation)
        }

        return ffmpegMutex.withLock {
            val png = File.createTempFile("directory_image_", ".png", cacheDir)
            var opened: DirectoryImageSource? = null
            try {
                val operation = NativeCommandOperation { arguments, complete ->
                    val session = FFmpegKit.executeWithArgumentsAsync(arguments) { completed ->
                        complete(ReturnCode.isSuccess(completed.getReturnCode()))
                    }
                    NativeCommandSession { session.cancel() }
                }
                if (!operation.execute(arguments(source, png))) return@withLock null
                opened = openRegionDecoder(png)?.let { DirectoryImageSource(it, orientation = 1, temporaryFile = png) }
                opened
            } finally {
                if (opened == null) png.delete()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun openRegionDecoder(file: File): BitmapRegionDecoder? =
        runCatching { BitmapRegionDecoder.newInstance(file.absolutePath, false) }.getOrNull()

    internal fun arguments(source: File, output: File): Array<String> = arrayOf(
        "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-protocol_whitelist", "file", "-threads", "1",
        "-i", source.absolutePath,
        "-map", "0:v:0", "-frames:v", "1", "-an", "-sn", "-dn",
        "-c:v", "png", "-pix_fmt", "rgba", "-compression_level", "1", "-threads", "1",
        "-f", "image2", "-update", "1", output.absolutePath
    )
}

internal class DirectoryImageSource(
    private val decoder: BitmapRegionDecoder,
    orientation: Int,
    private val temporaryFile: File? = null
) {
    val size = IntSize(decoder.width, decoder.height)
    val quarterTurns = when (orientation) {
        6, 7 -> 1
        3, 4 -> 2
        5, 8 -> 3
        else -> 0
    }
    val mirrorHorizontally = orientation in setOf(2, 4, 5, 7)

    fun decode(bounds: IntRect, sample: Int): Bitmap? = synchronized(decoder) {
        if (decoder.isRecycled) return@synchronized null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        decoder.decodeRegion(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), options)
            ?.apply { setHasMipMap(true) }
    }

    fun close() = synchronized(decoder) {
        decoder.recycle()
        temporaryFile?.delete()
    }
}
