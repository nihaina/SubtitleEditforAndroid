package com.subtitleedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.media.ExifInterface
import com.subtitleedit.util.PickedUriAccess
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

/** Normalizes picked cover art to a bounded JPEG for container cover streams. Call on an IO dispatcher. */
internal object MediaConvertCoverImages {
    private const val MAX_EDGE = 2048

    /** Checks decodable image dimensions without allocating the complete bitmap. */
    fun validate(context: Context, uri: Uri) {
        readBounds(context, uri)
    }

    fun prepare(context: Context, uri: Uri, directory: File): File {
        val bounds = readBounds(context, uri)
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_EDGE) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            openInput(context, uri).use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IllegalArgumentException("The selected cover is not a decodable image")
        } catch (error: OutOfMemoryError) {
            throw IllegalArgumentException("The selected cover image is too large to decode", error)
        }
        var oriented: Bitmap? = null
        var normalized: Bitmap? = null
        val output = File(directory, "cover.jpg")
        try {
            val orientation = runCatching {
                openInput(context, uri).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val image = if (transform.isIdentity) decoded else {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true).also { oriented = it }
            }
            val scale = (MAX_EDGE.toDouble() / maxOf(image.width, image.height)).coerceAtMost(1.0)
            val width = (image.width * scale).roundToInt().coerceAtLeast(1)
            val height = (image.height * scale).roundToInt().coerceAtLeast(1)
            val jpegBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            normalized = jpegBitmap
            jpegBitmap.setHasAlpha(false)
            Canvas(jpegBitmap).apply {
                drawColor(Color.WHITE)
                drawBitmap(image, null, Rect(0, 0, width, height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the cover image directory")
            output.outputStream().use { stream ->
                if (!jpegBitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) throw IOException("Cannot encode the cover image")
            }
            if (output.length() <= 0L) throw IOException("The normalized cover image is empty")
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            normalized?.recycle()
            oriented?.takeIf { it !== decoded }?.recycle()
            decoded.recycle()
        }
    }

    private fun readBounds(context: Context, uri: Uri): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInput(context, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected cover is not a decodable image" }
        return bounds
    }

    private fun openInput(context: Context, uri: Uri): InputStream =
        PickedUriAccess.file(uri)?.inputStream()
            ?: context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open the selected cover image")
}
