package com.subtitleedit.nativebridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object DirectoryImageDecoder {
    private const val MaxPixels = 24_000_000
    private const val MaxEdge = 8192
    private val decodeMutex = Mutex()

    suspend fun decode(source: File, cacheDir: File): Bitmap? = withContext(Dispatchers.IO) {
        decodeMutex.withLock {
            val png = File.createTempFile("directory_image_", ".png", cacheDir)
            try {
                val operation = NativeCommandOperation { arguments, complete ->
                    val session = FFmpegKit.executeWithArgumentsAsync(arguments) { completed ->
                        complete(ReturnCode.isSuccess(completed.getReturnCode()))
                    }
                    NativeCommandSession { session.cancel() }
                }
                if (!operation.execute(arguments(source, png))) return@withLock null

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(png.absolutePath, bounds)
                if (!canDisplay(bounds.outWidth, bounds.outHeight)) return@withLock null

                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeFile(png.absolutePath, options)?.apply { setHasMipMap(true) }
            } finally {
                png.delete()
            }
        }
    }

    internal fun arguments(source: File, output: File): Array<String> = arrayOf(
        "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-protocol_whitelist", "file", "-max_pixels", MaxPixels.toString(), "-threads", "1",
        "-i", source.absolutePath,
        "-map", "0:v:0", "-frames:v", "1", "-an", "-sn", "-dn",
        "-c:v", "png", "-pix_fmt", "rgba", "-compression_level", "1", "-threads", "1",
        "-f", "image2", "-update", "1", output.absolutePath
    )

    internal fun canDisplay(width: Int, height: Int): Boolean =
        width in 1..MaxEdge && height in 1..MaxEdge && width.toLong() * height <= MaxPixels
}
