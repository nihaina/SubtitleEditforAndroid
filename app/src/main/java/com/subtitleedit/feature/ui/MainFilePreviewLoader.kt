package com.subtitleedit.feature.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import android.util.LruCache
import com.subtitleedit.R
import com.subtitleedit.util.ArchiveManager
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.FileTypePolicy
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal data class MainFilePreview(
    val bitmap: Bitmap? = null,
    val mediaDuration: String = ""
)

internal object MainFilePreviewLoader {
    private val bitmapCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val mediaDurationCache = ConcurrentHashMap<String, String>()
    private val directoryCountCache = ConcurrentHashMap<String, Int>()
    private val emptyPreviewKeys = ConcurrentHashMap.newKeySet<String>()
    private val emptyApkIconKeys = ConcurrentHashMap.newKeySet<String>()

    fun preview(context: Context, file: File, targetSize: Int): MainFilePreview {
        val key = key(file)
        val extension = file.extension.lowercase(Locale.ROOT)
        val isMedia = extension in audioExtensions || extension in videoExtensions
        val isImage = extension in imageExtensions
        val isApk = extension == "apk"
        if (!isMedia && !isImage && !isApk) return MainFilePreview()
        val cachedBitmap = bitmapCache.get(key)
        val cachedDuration = mediaDurationCache[key]
        if (cachedBitmap != null || cachedDuration != null || key in emptyPreviewKeys) {
            return MainFilePreview(cachedBitmap, cachedDuration.orEmpty())
        }

        val preview = when {
            isImage -> runCatching { decodeSampledBitmap(file, targetSize) }.getOrNull()
            isApk -> loadApkIcon(context, file, targetSize)
            isMedia -> readMediaPreview(file, targetSize, extension in videoExtensions).also {
                mediaDurationCache[key] = it.second
            }.first
            else -> null
        }
        if (preview != null) bitmapCache.put(key, preview)
        if (preview == null && (!isMedia || cachedDuration != null)) emptyPreviewKeys += key
        return MainFilePreview(preview, mediaDurationCache[key].orEmpty())
    }

    fun directoryItemCount(file: File): Int = directoryCountCache.getOrPut(key(file)) {
        runCatching { file.list()?.size ?: -1 }.getOrDefault(-1)
    }

    fun iconResource(file: File): Int {
        if (file.isDirectory) return R.drawable.ic_folder
        val extension = file.extension.lowercase(Locale.ROOT)
        return when {
            extension in audioExtensions -> R.drawable.ic_file_audio
            extension in videoExtensions -> R.drawable.ic_file_video
            extension in ArchiveManager.recognizedExtensions -> archiveIcon(file)
            FileUtils.isSubtitleFile(file) || FileTypePolicy.isText(file) -> R.drawable.ic_file_text
            else -> R.drawable.ic_file
        }
    }

    private fun loadApkIcon(context: Context, file: File, targetSize: Int): Bitmap? {
        val iconKey = "${file.absolutePath}:${file.lastModified()}"
        if (iconKey in emptyApkIconKeys) return null
        val icon = runCatching {
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            packageInfo?.applicationInfo?.let { applicationInfo ->
                applicationInfo.sourceDir = file.absolutePath
                applicationInfo.publicSourceDir = file.absolutePath
                applicationInfo.loadIcon(context.packageManager)
            }
        }.getOrNull()
        if (icon == null) {
            emptyApkIconKeys += iconKey
            return null
        }
        return runCatching { icon.toBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888) }.getOrNull()
    }

    private fun decodeSampledBitmap(file: File, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetSize &&
            bounds.outHeight / (sampleSize * 2) >= targetSize
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun readMediaPreview(file: File, targetSize: Int, isVideo: Boolean): Pair<Bitmap?, String> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.let(::formatMediaDuration)
            }.getOrNull().orEmpty()
            val cover = runCatching {
                retriever.embeddedPicture?.let { decodeEmbeddedCover(it, targetSize) }
            }.getOrNull()
            val thumbnail = cover ?: if (isVideo) {
                runCatching {
                    val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            -1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetSize, targetSize
                        )
                    } else {
                        retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    frame?.let {
                        ThumbnailUtils.extractThumbnail(
                            it, targetSize, targetSize, ThumbnailUtils.OPTIONS_RECYCLE_INPUT
                        )
                    }
                }.getOrNull()
            } else null
            thumbnail to duration
        } catch (_: Exception) {
            null to ""
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeEmbeddedCover(data: ByteArray, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= targetSize) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            data, 0, data.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null
        return ThumbnailUtils.extractThumbnail(
            bitmap, targetSize, targetSize, ThumbnailUtils.OPTIONS_RECYCLE_INPUT
        )
    }

    private fun formatMediaDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun key(file: File): String =
        "${file.absolutePath}:${file.length()}:${file.lastModified()}"

    private fun archiveIcon(file: File): Int {
        val name = file.name.lowercase(Locale.ROOT)
        return when {
            name.endsWith(".7z") -> R.drawable.ic_file_archive_7z
            name.endsWith(".rar") -> R.drawable.ic_file_archive_rar
            name.endsWith(".tar") -> R.drawable.ic_file_archive_tar
            name.endsWith(".gz") || name.endsWith(".tgz") -> R.drawable.ic_file_archive_gz
            name.endsWith(".bz") || name.endsWith(".bz2") ||
                name.endsWith(".tbz") || name.endsWith(".tbz2") -> R.drawable.ic_file_archive_bz2
            name.endsWith(".xz") || name.endsWith(".txz") -> R.drawable.ic_file_archive_xz
            else -> R.drawable.ic_file_archive
        }
    }

    private val audioExtensions = FileUtils.AUDIO_EXTENSIONS + setOf("opus", "ac3", "amr")
    private val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v",
        "ts", "3gp", "mpg", "mpeg", "mts", "m2ts"
    )
    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff", "avif"
    )

}
