package com.subtitleedit.feature.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toBitmap
import com.subtitleedit.R
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.util.SharedFileCache
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
import kotlinx.coroutines.sync.withPermit
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
    private val diskLock = Any()
    private var diskDirectory: File? = null
    private val diskEntries = LinkedHashMap<File, Long>(16, 0.75f, true)
    private var diskBytes = 0L
    private val sessionId = UUID.randomUUID().toString()

    /** Keeps the file row's media-duration slot stable while an async preview is loading. */
    fun isMediaFile(file: File): Boolean {
        val extension = file.extension.lowercase(Locale.ROOT)
        return extension in audioExtensions || extension in videoExtensions
    }

    /** Memory-only first frame; loading a cold preview remains asynchronous. */
    fun peek(metadata: BrowserFileMetadata, targetSize: Int): MainFilePreview? {
        val file = metadata.file
        val extension = file.extension.lowercase(Locale.ROOT)
        if (metadata.isDirectory) return MainFilePreview()
        if (extension in audioExtensions || extension in videoExtensions) {
            return SharedMediaCache.peekPreview(metadata, targetSize)
                ?.let { MainFilePreview(it.bitmap, formatMediaDuration(it.durationMs)) }
        }
        if (extension !in imageExtensions && extension != "apk") return MainFilePreview()
        return previews.peek(file.absolutePath, "$sessionId:${metadata.cacheKey}:${targetSize.coerceAtLeast(1)}")
    }

    fun peekDirectoryItemCount(metadata: BrowserFileMetadata): Int? =
        if (metadata.isDirectory && !metadata.restricted && metadata.file.name != "..") {
            SharedFileCache.directories.peekItemCount(metadata.path)
        } else null

    suspend fun preview(context: Context, metadata: BrowserFileMetadata, targetSize: Int): MainFilePreview {
        val file = metadata.file
        val extension = file.extension.lowercase(Locale.ROOT)
        val isMedia = extension in audioExtensions || extension in videoExtensions
        val isImage = extension in imageExtensions
        val isApk = extension == "apk"
        if (isMedia && !metadata.isDirectory) {
            return loadPermits.withPermit {
                withContext(Dispatchers.IO) {
                    val media = SharedMediaCache.preview(metadata, targetSize)
                    MainFilePreview(media.bitmap, formatMediaDuration(media.durationMs))
                }
            }
        }
        if (metadata.isDirectory || (!isMedia && !isImage && !isApk)) return MainFilePreview()
        val size = targetSize.coerceAtLeast(1)
        val version = "$sessionId:${metadata.cacheKey}:$size"
        return previews.get(file.absolutePath, version) {
            readDiskPreview(context, file.absolutePath, version)?.let { return@get it }
            val result = when {
                isImage -> MainFilePreview(runCatching { decodeSampledBitmap(file, size) }.getOrNull())
                isApk -> MainFilePreview(loadApkIcon(context, file, size))
                else -> MainFilePreview()
            }
            // Empty image/APK results also remain valid until the shared file revision changes.
            writeDiskPreview(context, file.absolutePath, version, result)
            result
        }
    }

    suspend fun directoryItemCount(metadata: BrowserFileMetadata): Int {
        if (!metadata.isDirectory || metadata.restricted || metadata.file.name == "..") return -1
        return loadPermits.withPermit {
            withContext(Dispatchers.IO) { SharedFileCache.directories.itemCount(metadata.file) }
        }
    }

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

    private fun formatMediaDuration(durationMs: Long): String {
        if (durationMs <= 0L) return ""
        val totalSeconds = durationMs / 1000L
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
        val directory = diskDirectory ?: File(context.cacheDir, "file-previews-v2").also {
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
