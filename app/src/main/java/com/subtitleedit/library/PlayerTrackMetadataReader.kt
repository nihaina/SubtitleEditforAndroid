package com.subtitleedit.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaTagAudioRepository
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.FfprobeMediaInformation
import java.io.File
import java.util.Locale
import java.util.concurrent.Semaphore

internal data class PlayerTrackDetails(
    val fileName: String,
    val filePath: String,
    val locationDisplay: String,
    val isVideo: Boolean,
    val coverBitmap: Bitmap?,
    val title: String,
    val album: String,
    val artist: String,
    val albumArtist: String,
    val composer: String,
    val genre: String,
    val lyricist: String,
    val trackNumber: String,
    val discNumber: String,
    val year: String,
    val comment: String,
    val copyright: String,
    val language: String,
    val durationText: String,
    val fileSizeText: String,
    val canEditTags: Boolean
)

private data class MediaFileStamp(val path: String, val modified: Long, val length: Long) {
    companion object {
        fun of(file: File) = MediaFileStamp(file.absolutePath, file.lastModified(), file.length())
    }
}

/** Artwork is loaded only when displayed, with bounded native decoder concurrency and memory. */
internal object MediaArtworkLoader {
    private data class Key(val stamp: MediaFileStamp, val size: Int, val allowVideoFrame: Boolean)
    private data class Artwork(val bitmap: Bitmap?)
    private val decoders = Semaphore(2, true)
    private val cache = object : LruCache<Key, Artwork>(16 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Artwork) = value.bitmap?.byteCount ?: 64
    }

    fun invalidate(path: String) {
        cache.snapshot().keys.filter { it.stamp.path == path }.forEach(cache::remove)
    }

    fun loadCover(path: String, targetSizePx: Int = 256, allowVideoFrame: Boolean = true): Bitmap? {
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null
        val key = Key(MediaFileStamp.of(file), targetSizePx.coerceIn(32, 1024), allowVideoFrame)
        cache.get(key)?.let { return it.bitmap }
        decoders.acquire()
        try {
            cache.get(key)?.let { return it.bitmap }
            val picture = runCatching {
                withRetriever(file) { it.embeddedPicture }
            }.getOrNull() ?: if (!FileTypePolicy.isVideo(file)) {
                runCatching { MediaTagAudioRepository.pictures(file).firstOrNull()?.data }.getOrNull()
            } else null
            val bitmap = if (picture != null) {
                runCatching { decodeSampled(picture, key.size) }.getOrNull()
            } else if (allowVideoFrame && FileTypePolicy.isVideo(file)) {
                runCatching {
                    withRetriever(file) { retriever ->
                        val frame = if (Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, key.size, key.size)
                        } else retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        frame?.let { ThumbnailUtils.extractThumbnail(it, key.size, key.size, ThumbnailUtils.OPTIONS_RECYCLE_INPUT) }
                    }
                }.getOrNull()
            } else null
            cache.put(key, Artwork(bitmap))
            return bitmap
        } finally {
            decoders.release()
        }
    }

    private fun decodeSampled(data: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }
}

/** Shared text-only metadata extraction for indexing, favorites, history and the info dialog. */
internal object PlayerTrackMetadataReader {
    private data class Snapshot(val tags: MediaConvertMetadata, val durationMs: Long)
    private val cache = LruCache<MediaFileStamp, Snapshot>(128)
    private val videoDetailsCache = LruCache<MediaFileStamp, MediaConvertMetadata>(32)

    fun invalidate(path: String) {
        cache.snapshot().keys.filter { it.path == path }.forEach(cache::remove)
        videoDetailsCache.snapshot().keys.filter { it.path == path }.forEach(videoDetailsCache::remove)
        MediaArtworkLoader.invalidate(path)
    }

    fun readLibraryTrack(file: File, durationMs: Long = 0L): MediaLibraryTrack {
        val snapshot = snapshot(file)
        return MediaLibraryTrack(
            path = file.absolutePath,
            title = snapshot.tags.title.ifBlank { file.nameWithoutExtension.ifBlank { file.name } },
            artist = snapshot.tags.artist,
            album = snapshot.tags.album,
            isVideo = FileTypePolicy.isVideo(file),
            durationMs = durationMs.takeIf { it > 0L } ?: snapshot.durationMs,
            addedAtMs = System.currentTimeMillis()
        )
    }

    /** Text fields needed by the album browser; never decodes artwork or runs FFprobe. */
    fun readAlbumMetadata(file: File): MediaAlbumMetadata {
        val tags = snapshot(file).tags
        return MediaAlbumMetadata(
            albumArtist = tags.albumArtist,
            composer = tags.composer,
            year = tags.year,
            trackNumber = tags.trackNumber,
            discNumber = tags.discNumber,
            genre = tags.genre
        )
    }

    fun read(file: File, fallbackDurationMs: Long = 0L, cacheDirectory: File? = null): PlayerTrackDetails {
        val snapshot = snapshot(file)
        val isVideo = FileTypePolicy.isVideo(file)
        // Probe extended video tags only when opening details, never while scanning the library.
        val videoTags = if (isVideo && cacheDirectory != null) videoDetails(file, cacheDirectory) else null
        val tags = if (videoTags == null) snapshot.tags else {
            MediaConvertMetadata.audioFields.fold(snapshot.tags) { metadata, field ->
                videoTags.value(field).takeIf { it.isNotBlank() }?.let { metadata.withValue(field, it) } ?: metadata
            }.copy(editedFields = emptySet())
        }
        return PlayerTrackDetails(
            fileName = file.name,
            filePath = file.absolutePath,
            locationDisplay = formatStorageLocation(file.parentFile?.absolutePath.orEmpty()),
            isVideo = isVideo,
            coverBitmap = MediaArtworkLoader.loadCover(file.absolutePath, 320),
            title = tags.title.ifBlank { file.nameWithoutExtension },
            album = tags.album,
            artist = tags.artist,
            albumArtist = tags.albumArtist,
            composer = tags.composer,
            genre = tags.genre,
            lyricist = tags.lyricist,
            trackNumber = tags.trackNumber,
            discNumber = tags.discNumber,
            year = tags.year,
            comment = tags.comment,
            copyright = tags.copyright,
            language = tags.language,
            durationText = formatDuration(fallbackDurationMs.takeIf { it > 0L } ?: snapshot.durationMs),
            fileSizeText = if (file.isFile) FileUtils.formatFileSize(file.length()) else "",
            canEditTags = file.isFile && file.canRead() && (FileUtils.isAudioFile(file) || isVideo)
        )
    }

    private fun videoDetails(file: File, cacheDirectory: File): MediaConvertMetadata? {
        if (!file.isFile || !file.canRead()) return null
        val stamp = MediaFileStamp.of(file)
        videoDetailsCache.get(stamp)?.let { return it }
        return runCatching {
            val info = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                FfprobeMediaInformation.read("/proc/self/fd/${descriptor.fd}", cacheDirectory)
            }
            val primaryVideo = info.getStreams().firstOrNull { stream ->
                stream.getType() == "video" &&
                    stream.getAllProperties()?.optJSONObject("disposition")?.optInt("attached_pic", 0) != 1
            }
            val primaryAudio = info.getStreams().firstOrNull { it.getType() == "audio" }
            val sources = listOfNotNull(info.getTags(), primaryVideo?.getTags(), primaryAudio?.getTags())
            MediaConvertMetadata.fromTags { key ->
                sources.firstNotNullOfOrNull { tags ->
                    tags.keys().asSequence().firstOrNull { it.equals(key, ignoreCase = true) }
                        ?.let { tags.optString(it).takeIf { value -> value.isNotBlank() && !value.equals("null", true) } }
                }
            }.also { videoDetailsCache.put(stamp, it) }
        }.getOrNull()
    }

    private fun snapshot(file: File): Snapshot {
        if (!file.isFile || !file.canRead()) return Snapshot(MediaConvertMetadata(), 0L)
        val stamp = MediaFileStamp.of(file)
        cache.get(stamp)?.let { return it }
        val audio = if (FileTypePolicy.isVideo(file)) null else {
            runCatching { MediaTagAudioRepository.readMetadata(file) }.getOrNull()
        }
        var duration = 0L
        val fallback = runCatching {
            withRetriever(file) { retriever ->
                fun meta(key: Int): String = retriever.extractMetadata(key)?.trim()
                    ?.takeUnless { it.equals("<unknown>", true) }.orEmpty()
                duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                MediaConvertMetadata(
                    title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    albumArtist = meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    composer = meta(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                    genre = meta(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    trackNumber = meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                    discNumber = meta(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                    year = meta(MediaMetadataRetriever.METADATA_KEY_YEAR)
                )
            }
        }.getOrDefault(MediaConvertMetadata())
        val tags = if (audio == null) fallback else audio.copy(
            title = audio.title.ifBlank { fallback.title }, artist = audio.artist.ifBlank { fallback.artist },
            album = audio.album.ifBlank { fallback.album }, albumArtist = audio.albumArtist.ifBlank { fallback.albumArtist },
            composer = audio.composer.ifBlank { fallback.composer }, genre = audio.genre.ifBlank { fallback.genre },
            trackNumber = audio.trackNumber.ifBlank { fallback.trackNumber }, discNumber = audio.discNumber.ifBlank { fallback.discNumber },
            year = audio.year.ifBlank { fallback.year }
        )
        return Snapshot(tags, duration).also { cache.put(stamp, it) }
    }

    private fun formatStorageLocation(parent: String): String = parent
        .replaceFirst(Regex("^/storage/emulated/0(?=/|$)"), "内部存储器")
        .split('/').filter { it.isNotBlank() }.joinToString(" › ")

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return ""
        val seconds = ms / 1000L
        return if (seconds >= 3600L) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }
}

private inline fun <T> withRetriever(file: File, block: (MediaMetadataRetriever) -> T): T {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        block(retriever)
    } finally {
        runCatching { retriever.release() }
    }
}
