package com.subtitleedit.util

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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
    val mediaInfoTitle: String?,
    val mediaDetails: List<PropertyDetail>
)

internal data class PropertyDetail(val label: String, val value: String)

internal object MediaFilePropertiesReader {
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v",
        "ts", "3gp", "mpg", "mpeg", "mts", "m2ts"
    )

    fun read(file: File): FilePropertiesInfo {
        val isAudioFile = FileUtils.isAudioFile(file)
        val isVideoFile = file.extension.lowercase() in VIDEO_EXTENSIONS
        val type = if (file.isDirectory) "文件夹" else file.extension.uppercase().ifBlank { "未知" } + " 文件"
        val size = if (file.isDirectory) directorySize(file) else file.length()
        val modified = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
        val mediaInfoTitle = when {
            isAudioFile -> "音频信息"
            isVideoFile -> "视频信息"
            else -> null
        }
        val mediaDetails = when {
            isAudioFile -> readAudioProperties(file)
            isVideoFile -> readVideoProperties(file)
            else -> emptyList()
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

    private fun readAudioProperties(file: File): List<PropertyDetail> {
        val technicalDetails = mutableListOf<PropertyDetail>()
        val embeddedDetails = mutableListOf<PropertyDetail>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }
                ?.let { technicalDetails += PropertyDetail("时长", formatMediaDuration(it)) }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let { technicalDetails += PropertyDetail("比特率", "${it / 1000L} kbps") }

            fun addEmbeddedDetail(label: String, metadataKey: Int) {
                retriever.extractMetadata(metadataKey)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
                    ?.let { embeddedDetails += PropertyDetail(label, it) }
            }

            addEmbeddedDetail("歌曲名", MediaMetadataRetriever.METADATA_KEY_TITLE)
            addEmbeddedDetail("艺术家", MediaMetadataRetriever.METADATA_KEY_ARTIST)
            addEmbeddedDetail("专辑", MediaMetadataRetriever.METADATA_KEY_ALBUM)
            addEmbeddedDetail("专辑艺术家", MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            addEmbeddedDetail("作曲", MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            addEmbeddedDetail("流派", MediaMetadataRetriever.METADATA_KEY_GENRE)
            addEmbeddedDetail("年份", MediaMetadataRetriever.METADATA_KEY_YEAR)
        } catch (_: Exception) {
            // Unsupported or damaged media can still show its regular file properties.
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val audioFormat = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { format ->
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
            audioFormat?.let { format ->
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    technicalDetails += PropertyDetail("采样率", formatSampleRate(sampleRate))
                }
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val displayChannels = when (channels) {
                        1 -> "单声道"
                        2 -> "立体声"
                        else -> "${channels} 声道"
                    }
                    technicalDetails += PropertyDetail("声道", displayChannels)
                }
            }
        } catch (_: Exception) {
            // Stream details are optional metadata.
        } finally {
            runCatching { extractor.release() }
        }
        return technicalDetails + embeddedDetails
    }

    private fun readVideoProperties(file: File): List<PropertyDetail> {
        val technicalDetails = linkedMapOf<String, String>()
        val embeddedDetails = linkedMapOf<String, String>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }
                ?.let { technicalDetails["时长"] = formatMediaDuration(it) }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let { technicalDetails["比特率"] = "${it / 1000L} kbps" }

            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
            if (width != null && height != null && width > 0 && height > 0) {
                val displayWidth = if (rotation == 90 || rotation == 270) height else width
                val displayHeight = if (rotation == 90 || rotation == 270) width else height
                technicalDetails["分辨率"] = "$displayWidth × $displayHeight"
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { technicalDetails["帧率"] = formatFrameRate(it) }

            fun addEmbeddedDetail(label: String, metadataKey: Int) {
                retriever.extractMetadata(metadataKey)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
                    ?.let { embeddedDetails[label] = it }
            }

            addEmbeddedDetail("标题", MediaMetadataRetriever.METADATA_KEY_TITLE)
            addEmbeddedDetail("艺术家", MediaMetadataRetriever.METADATA_KEY_ARTIST)
            addEmbeddedDetail("作者", MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            addEmbeddedDetail("日期", MediaMetadataRetriever.METADATA_KEY_DATE)
        } catch (_: Exception) {
            // Unsupported or damaged media can still show its regular file properties.
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            formats.firstOrNull {
                it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }?.let { format ->
                format.getString(MediaFormat.KEY_MIME)?.let {
                    technicalDetails["视频编码"] = displayCodec(it)
                }
                if (!technicalDetails.containsKey("分辨率") &&
                    format.containsKey(MediaFormat.KEY_WIDTH) &&
                    format.containsKey(MediaFormat.KEY_HEIGHT)) {
                    technicalDetails["分辨率"] =
                        "${format.getInteger(MediaFormat.KEY_WIDTH)} × ${format.getInteger(MediaFormat.KEY_HEIGHT)}"
                }
                if (!technicalDetails.containsKey("帧率") && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    technicalDetails["帧率"] = formatFrameRate(format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble())
                }
                if (!technicalDetails.containsKey("比特率") && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    technicalDetails["比特率"] = "${format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000} kbps"
                }
            }
            formats.firstOrNull {
                it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }?.let { format ->
                format.getString(MediaFormat.KEY_MIME)?.let {
                    technicalDetails["音频编码"] = displayCodec(it)
                }
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    technicalDetails["音频采样率"] = formatSampleRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                }
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    technicalDetails["音频声道"] = when (channels) {
                        1 -> "单声道"
                        2 -> "立体声"
                        else -> "${channels} 声道"
                    }
                }
            }
        } catch (_: Exception) {
            // Stream details are optional metadata.
        } finally {
            runCatching { extractor.release() }
        }
        return technicalDetails.map { PropertyDetail(it.key, it.value) } +
            embeddedDetails.map { PropertyDetail(it.key, it.value) }
    }

    private fun formatMediaDuration(durationMs: Long): String {
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

    private fun formatSampleRate(sampleRate: Int): String =
        if (sampleRate % 1000 == 0) {
            "${sampleRate / 1000} kHz"
        } else {
            String.format(Locale.US, "%.1f kHz", sampleRate / 1000.0)
        }

    private fun formatFrameRate(frameRate: Double): String =
        if (frameRate % 1.0 == 0.0) {
            "${frameRate.toInt()} fps"
        } else {
            String.format(Locale.US, "%.2f fps", frameRate)
        }

    private fun displayCodec(mimeType: String): String = when (mimeType.lowercase()) {
        "video/avc" -> "H.264 / AVC"
        "video/hevc" -> "H.265 / HEVC"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mp4v-es" -> "MPEG-4 Video"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        else -> mimeType.substringAfter('/', mimeType)
    }

    private fun directorySize(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
