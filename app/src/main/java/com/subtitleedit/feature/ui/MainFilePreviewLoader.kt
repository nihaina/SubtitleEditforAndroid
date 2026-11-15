package com.subtitleedit.feature.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.subtitleedit.R
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.util.ArchiveManager
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.SharedFileResourceCache
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

internal data class MainFilePreview(
    val bitmap: Bitmap? = null,
    val mediaDuration: String = ""
)

internal object MainFilePreviewLoader {
    private val loadPermits = Semaphore(2)
    private val previews = SharedFileResourceCache<MainFilePreview>(
        maximumWeight = 16 * 1024 * 1024,
        weightOf = { it.bitmap?.byteCount ?: 1 },
        permits = loadPermits
    )
    private val directoryCounts = SharedFileResourceCache<Int>(maximumWeight = 2048, permits = loadPermits)
    private val diskLock = Any()
    private var diskDirectory: File? = null
    private val diskEntries = LinkedHashMap<File, Long>(16, 0.75f, true)
    private var diskBytes = 0L
    private val versionLock = Any()
    private val invalidations = mutableMapOf<String, Long>()
    private var nextInvalidation = 0L
    private var cacheEpoch = 0L
    private val sessionId = UUID.randomUUID().toString()

    /** Keeps the file row's media-duration slot stable while an async preview is loading. */
    fun isMediaFile(file: File): Boolean {
        val extension = file.extension.lowercase(Locale.ROOT)
        return extension in audioExtensions || extension in videoExtensions
    }

    /** Compatibility for callers without a snapshot; source attributes stay off Main. */
    suspend fun preview(context: Context, file: File, targetSize: Int): MainFilePreview {
        val metadata = withContext(Dispatchers.IO) { BrowserFileMetadata.read(file, 0L) }
            ?: return MainFilePreview()
        return preview(context, metadata, targetSize)
    }

    suspend fun preview(context: Context, metadata: BrowserFileMetadata, targetSize: Int): MainFilePreview {
        val file = metadata.file
        val extension = file.extension.lowercase(Locale.ROOT)
        val isMedia = extension in audioExtensions || extension in videoExtensions
        val isImage = extension in imageExtensions
        val isApk = extension == "apk"
        if (metadata.isDirectory || (!isMedia && !isImage && !isApk)) return MainFilePreview()
        val size = targetSize.coerceAtLeast(1)
        val version = synchronized(versionLock) {
            "$sessionId:${metadata.cacheKey}:$size:$cacheEpoch:${invalidations[file.absolutePath] ?: 0}"
        }
        return previews.get(file.absolutePath, version) {
            readDiskPreview(context, file.absolutePath, version)?.let { return@get it }
            val result = when {
                isImage -> MainFilePreview(runCatching { decodeSampledBitmap(file, size) }.getOrNull())
                isApk -> MainFilePreview(loadApkIcon(context, file, size))
                isMedia -> readMediaPreview(file, size, extension in videoExtensions).let {
                    MainFilePreview(it.first, it.second)
                }
                else -> MainFilePreview()
            }
            // Cache empty results as well. A file event changes the revision and
            // permits a retry, rather than retrying a broken file on every bind.
            writeDiskPreview(context, file.absolutePath, version, result)
            result
        }
    }

    suspend fun directoryItemCount(file: File): Int {
        val metadata = withContext(Dispatchers.IO) { BrowserFileMetadata.read(file, 0L) } ?: return -1
        return directoryItemCount(metadata)
    }

    suspend fun directoryItemCount(metadata: BrowserFileMetadata): Int {
        if (!metadata.isDirectory || metadata.restricted || metadata.file.name == "..") return -1
        return directoryCounts.get(metadata.file.absolutePath, metadata.cacheKey) {
            runCatching { metadata.file.list()?.size ?: -1 }.getOrDefault(-1)
        }
    }

    fun invalidate(path: String) {
        synchronized(versionLock) {
            if (path !in invalidations && invalidations.size >= 2048) {
                cacheEpoch++
                invalidations.clear()
            }
            invalidations[path] = ++nextInvalidation
        }
        previews.invalidate(path)
        directoryCounts.invalidate(path)
        // Disk data is versioned by the metadata revision. It is never looked
        // up for the new revision; bounded pruning removes obsolete versions.
    }

    fun clear() {
        // Include the epoch in disk keys as well, so clear cannot reload an old
        // disk result while a caller is holding an unchanged metadata snapshot.
        synchronized(versionLock) {
            cacheEpoch++
            invalidations.clear()
        }
        previews.clear()
        directoryCounts.clear()
    }

    fun iconResource(file: File): Int = iconResource(file.name, file.isDirectory)

    /** Classifies virtual entries without reading filesystem metadata. */
    fun iconResource(name: String, isDirectory: Boolean): Int {
        if (isDirectory) return R.drawable.ic_folder
        val file = File(name)
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
        val icon = runCatching {
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            packageInfo?.applicationInfo?.let { applicationInfo ->
                applicationInfo.sourceDir = file.absolutePath
                applicationInfo.publicSourceDir = file.absolutePath
                applicationInfo.loadIcon(context.packageManager)
            }
        }.getOrNull()
        if (icon == null) return null
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

    private fun previewFile(context: Context, path: String, version: String): File {
        val directory = diskDirectory ?: File(context.cacheDir, "directory-previews-v1").also {
            it.mkdirs()
            it.listFiles()?.sortedBy(File::lastModified)?.forEach { file ->
                if (file.extension == "preview") {
                    val bytes = file.length()
                    diskEntries[file] = bytes
                    diskBytes += bytes
                } else if (file.extension == "tmp") {
                    file.delete()
                }
            }
            diskDirectory = it
            pruneDiskCache()
        }
        return File(directory, "${digest(path)}-${digest(version)}.preview")
    }

    private fun readDiskPreview(context: Context, path: String, version: String): MainFilePreview? = synchronized(diskLock) {
        runCatching {
            val source = previewFile(context, path, version)
            DataInputStream(source.inputStream().buffered()).use { input ->
                check(input.readInt() == DISK_FORMAT)
                val duration = input.readUTF()
                val hasBitmap = input.readBoolean()
                val bitmap = if (hasBitmap) BitmapFactory.decodeStream(input) ?: error("Invalid cached bitmap") else null
                diskEntries[source]
                MainFilePreview(bitmap, duration)
            }
        }.getOrNull()
    }

    private fun writeDiskPreview(context: Context, path: String, version: String, preview: MainFilePreview) = synchronized(diskLock) {
        runCatching {
            val destination = previewFile(context, path, version)
            val temporary = File(destination.path + ".tmp")
            var bytes = 0L
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(DISK_FORMAT)
                output.writeUTF(preview.mediaDuration)
                output.writeBoolean(preview.bitmap != null)
                preview.bitmap?.let { check(it.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                bytes = output.size().toLong()
            }
            if (temporary.renameTo(destination)) {
                diskBytes -= diskEntries.put(destination, bytes) ?: 0L
                diskBytes += bytes
                pruneDiskCache()
            } else temporary.delete()
        }
        Unit
    }

    private fun pruneDiskCache() {
        val iterator = diskEntries.iterator()
        while ((diskBytes > 64L * 1024 * 1024 || diskEntries.size > 1024) && iterator.hasNext()) {
            val entry = iterator.next()
            entry.key.delete()
            diskBytes -= entry.value
            iterator.remove()
        }
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(digits[unsigned ushr 4])
                append(digits[unsigned and 0xf])
            }
        }
    }

    private const val DISK_FORMAT = 1

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
