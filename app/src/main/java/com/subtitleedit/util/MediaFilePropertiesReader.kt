package com.subtitleedit.util

import androidx.annotation.StringRes
import com.subtitleedit.R
import com.subtitleedit.library.SharedMediaCache
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class FilePropertiesInfo(
    val name: String,
    val path: String,
    val type: String,
    val size: String,
    val modifiedTime: String,
    @get:StringRes val mediaInfoTitle: Int?,
    val mediaDetails: List<PropertyDetail>
)

internal data class PropertyDetail(@get:StringRes val label: Int, val value: String)

internal object MediaFilePropertiesReader {
    fun read(file: File, readTags: Boolean = false, cacheDirectory: File? = null): FilePropertiesInfo {
        val mediaInfoTitle = when {
            file.isDirectory -> null
            FileTypePolicy.isVideo(file) -> R.string.media_details_video_title
            FileUtils.isAudioFile(file) -> R.string.media_details_audio_title
            else -> null
        }
        val isVideo = mediaInfoTitle == R.string.media_details_video_title
        val type = if (file.isDirectory) "文件夹" else file.extension.uppercase().ifBlank { "未知" } + " 文件"
        val size = if (file.isDirectory) directorySize(file) else file.length()
        val modified = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
        val mediaDetails = buildList {
            val metadata = if (mediaInfoTitle != null) SharedFileCache.attributes.get(file) else null
            if (metadata != null) {
                SharedMediaCache.technicalDetails(metadata, cacheDirectory)?.let { technical ->
                    formatMediaDuration(technical.durationMs).takeIf { it.isNotEmpty() }?.let {
                        add(PropertyDetail(R.string.player_info_duration, it))
                    }
                    addAll(technical.properties(isVideo))
                }
                if (readTags) {
                    val tags = runCatching { SharedMediaCache.readInfo(metadata, cacheDirectory).tags }.getOrNull()
                    if (tags != null) addAll(MediaTagPropertyDetails.fromMetadata(tags, isVideo))
                }
            }
        }
        return FilePropertiesInfo(
            name = file.name,
            path = file.absolutePath,
            type = type,
            size = FileUtils.formatFileSize(size),
            modifiedTime = modified,
            mediaInfoTitle = mediaInfoTitle,
            mediaDetails = mediaDetails
        )
    }

    private fun directorySize(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
