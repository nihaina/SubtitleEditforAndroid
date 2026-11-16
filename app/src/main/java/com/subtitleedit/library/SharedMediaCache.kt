package com.subtitleedit.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.ParcelFileDescriptor
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaTagAudioRepository
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.util.FfprobeMediaInformation
import com.subtitleedit.util.FileTypePolicy
import java.io.File

internal data class SharedMediaMetadata(val tags: MediaConvertMetadata, val durationMs: Long)
internal data class SharedMediaPreview(val bitmap: Bitmap?, val durationMs: Long)

/** Application-wide media data; directory previews and library scans enrich the same version. */
internal object SharedMediaCache {
    private val cache = IncrementalMediaCache(
        emptyTags = MediaConvertMetadata(),
        load = ::loadMissingFields,
        artworkWeight = Bitmap::getByteCount
    )

    fun read(metadata: BrowserFileMetadata): SharedMediaMetadata {
        if (!metadata.isRegularFile) return SharedMediaMetadata(MediaConvertMetadata(), 0L)
        val data = cache.get(metadata, needDuration = true, needTags = true)
        return SharedMediaMetadata(data.tags, data.durationMs)
    }

    fun preview(metadata: BrowserFileMetadata, targetSize: Int, allowVideoFrame: Boolean = true): SharedMediaPreview {
        if (!metadata.isRegularFile) return SharedMediaPreview(null, 0L)
        val data = cache.get(metadata, needDuration = true,
            artwork = MediaArtworkRequest(targetSize.coerceIn(1, 2048), allowVideoFrame))
        return SharedMediaPreview(data.artwork, data.durationMs)
    }

    fun peekPreview(metadata: BrowserFileMetadata, targetSize: Int, allowVideoFrame: Boolean = true): SharedMediaPreview? =
        cache.peekPreview(metadata, MediaArtworkRequest(targetSize.coerceIn(1, 2048), allowVideoFrame))
            ?.let { SharedMediaPreview(it.artwork, it.durationMs) }

    fun artwork(metadata: BrowserFileMetadata, targetSize: Int, allowVideoFrame: Boolean = true): Bitmap? {
        if (!metadata.isRegularFile) return null
        return cache.get(metadata,
            artwork = MediaArtworkRequest(targetSize.coerceIn(1, 2048), allowVideoFrame)).artwork
    }

    /** Detailed video tags are an additional field, never a prerequisite for indexing. */
    fun videoDetails(metadata: BrowserFileMetadata, cacheDirectory: File): MediaConvertMetadata? =
        cache.details(metadata) {
            runCatching {
                val info = ParcelFileDescriptor.open(metadata.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
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
                }
            }.getOrNull()
        }

    fun invalidate(path: String) = cache.invalidate(path)
    fun clear() = cache.clear()
    fun seedDuration(metadata: BrowserFileMetadata, durationMs: Long) = cache.seedDuration(metadata, durationMs)

    private fun loadMissingFields(
        metadata: BrowserFileMetadata,
        missing: MissingMediaFields,
        knownCover: ByteArray?
    ): LoadedMediaFields<MediaConvertMetadata, Bitmap> {
        val file = metadata.file
        val isVideo = FileTypePolicy.isVideo(file)
        var retriever: MediaMetadataRetriever? = null
        var retrieverAttempted = false
        fun source(): MediaMetadataRetriever? {
            if (!retrieverAttempted) {
                retrieverAttempted = true
                val opened = MediaMetadataRetriever()
                retriever = try {
                    opened.setDataSource(file.absolutePath)
                    opened
                } catch (_: Exception) {
                    runCatching { opened.release() }
                    null
                }
            }
            return retriever
        }
        fun meta(key: Int): String = runCatching { source()?.extractMetadata(key)?.trim() }
            .getOrNull()?.takeUnless { it.equals("<unknown>", true) || it.equals("null", true) }.orEmpty()
        return try {
            val duration = if (missing.duration) meta(MediaMetadataRetriever.METADATA_KEY_DURATION)
                .toLongOrNull()?.coerceAtLeast(0L) ?: 0L else 0L
            val tags = if (missing.tags) {
                val audio = if (!isVideo) runCatching { MediaTagAudioRepository.readMetadata(file) }.getOrNull() else null
                val native = audio ?: MediaConvertMetadata()
                native.copy(
                    title = native.title.ifBlank { meta(MediaMetadataRetriever.METADATA_KEY_TITLE) },
                    artist = native.artist.ifBlank { meta(MediaMetadataRetriever.METADATA_KEY_ARTIST) },
                    album = native.album.ifBlank { meta(MediaMetadataRetriever.METADATA_KEY_ALBUM) },
                    albumArtist = native.albumArtist.ifBlank { meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) },
                    composer = native.composer.ifBlank { meta(MediaMetadataRetriever.METADATA_KEY_COMPOSER) },
                    genre = native.genre.ifBlank { meta(MediaMetadataRetriever.METADATA_KEY_GENRE) },
                    trackNumber = native.trackNumber.ifBlank { meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER) },
                    discNumber = native.discNumber.ifBlank { meta(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER) },
                    year = native.year.ifBlank { meta(MediaMetadataRetriever.METADATA_KEY_YEAR) }
                )
            } else null
            val cover = if (missing.cover) {
                runCatching { source()?.embeddedPicture }.getOrNull() ?: if (!isVideo) {
                    runCatching { MediaTagAudioRepository.pictures(file).firstOrNull()?.data }.getOrNull()
                } else null
            } else knownCover
            val artwork = missing.artwork?.let { request ->
                if (cover != null) runCatching { decodeSampled(cover, request.size) }.getOrNull()
                else if (isVideo && request.allowVideoFrame) {
                    runCatching {
                        val frame = if (Build.VERSION.SDK_INT >= 27) {
                            source()?.getScaledFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                request.size, request.size)
                        } else source()?.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        frame?.let { ThumbnailUtils.extractThumbnail(it, request.size, request.size,
                            ThumbnailUtils.OPTIONS_RECYCLE_INPUT) }
                    }.getOrNull()
                } else null
            }
            LoadedMediaFields(duration, tags, cover, artwork)
        } finally {
            runCatching { retriever?.release() }
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
