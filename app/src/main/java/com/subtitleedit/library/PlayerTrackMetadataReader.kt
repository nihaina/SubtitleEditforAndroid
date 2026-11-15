package com.subtitleedit.library

import android.graphics.Bitmap
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.SharedFileCache
import java.io.File
import java.util.Locale

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

/** Model mapping only. All media and filesystem reads belong to the shared caches. */
internal object PlayerTrackMetadataReader {
    fun invalidate(path: String) {
        SharedFileCache.attributes.invalidate(path)
        SharedMediaCache.invalidate(path)
    }

    fun readLibraryTrack(metadata: BrowserFileMetadata, durationMs: Long = 0L): MediaLibraryTrack {
        if (durationMs > 0L) SharedMediaCache.seedDuration(metadata, durationMs)
        val snapshot = SharedMediaCache.read(metadata)
        val file = metadata.file
        return MediaLibraryTrack(
            path = metadata.path,
            title = snapshot.tags.title.ifBlank { file.nameWithoutExtension.ifBlank { file.name } },
            artist = snapshot.tags.artist,
            album = snapshot.tags.album,
            isVideo = FileTypePolicy.isVideo(file),
            durationMs = durationMs.takeIf { it > 0L } ?: snapshot.durationMs,
            addedAtMs = System.currentTimeMillis()
        )
    }

    fun readAlbumMetadata(metadata: BrowserFileMetadata): MediaAlbumMetadata {
        val tags = SharedMediaCache.read(metadata).tags
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
        val metadata = SharedFileCache.attributes.get(file)
        if (metadata != null && fallbackDurationMs > 0L) SharedMediaCache.seedDuration(metadata, fallbackDurationMs)
        val snapshot = metadata?.let(SharedMediaCache::read) ?: SharedMediaMetadata(MediaConvertMetadata(), 0L)
        val isVideo = FileTypePolicy.isVideo(file)
        val videoTags = if (isVideo && cacheDirectory != null && metadata != null) {
            SharedMediaCache.videoDetails(metadata, cacheDirectory)
        } else null
        val tags = if (videoTags == null) snapshot.tags else {
            MediaConvertMetadata.audioFields.fold(snapshot.tags) { current, field ->
                videoTags.value(field).takeIf { it.isNotBlank() }?.let { current.withValue(field, it) } ?: current
            }.copy(editedFields = emptySet())
        }
        return PlayerTrackDetails(
            fileName = file.name,
            filePath = file.absolutePath,
            locationDisplay = formatStorageLocation(file.parentFile?.absolutePath.orEmpty()),
            isVideo = isVideo,
            coverBitmap = metadata?.let { SharedMediaCache.artwork(it, 320) },
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
            fileSizeText = metadata?.takeIf { it.isRegularFile }?.let { FileUtils.formatFileSize(it.size) }.orEmpty(),
            canEditTags = metadata?.isRegularFile == true && (FileUtils.isAudioFile(file) || isVideo)
        )
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
