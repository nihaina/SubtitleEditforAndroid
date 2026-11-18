package com.subtitleedit.util

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.MediaInformation
import com.arthenica.ffmpegkit.StreamInformation
import com.subtitleedit.R
import java.io.File
import java.util.Locale
import org.json.JSONObject
import kotlin.math.roundToLong

internal data class MediaTechnicalDetails(
    val durationMs: Long = 0L,
    private val bitrate: Long? = null,
    private val sampleRate: Int? = null,
    private val bitDepth: Int? = null,
    private val format: String = "",
    private val audioCodec: String? = null,
    private val videoCodec: String? = null,
    private val channels: Int? = null,
    private val resolution: String? = null,
    private val frameRate: Double? = null
) {
    fun properties(isVideo: Boolean): List<PropertyDetail> = buildList {
        bitrate?.let { add(PropertyDetail(R.string.media_details_bitrate, "${it / 1000L} kbps")) }
        if (isVideo) {
            resolution?.let { add(PropertyDetail(R.string.media_details_resolution, it)) }
            frameRate?.let { add(PropertyDetail(R.string.media_details_frame_rate,
                if (it % 1.0 == 0.0) "${it.toInt()} fps" else String.format(Locale.US, "%.2f fps", it))) }
        }
        sampleRate?.let { add(PropertyDetail(R.string.media_details_sample_rate, "$it Hz")) }
        if (bitDepth != null || sampleRate != null || audioCodec != null) {
            add(PropertyDetail(R.string.media_details_bit_depth, bitDepth?.let { "$it bit" } ?: "—"))
        }
        format.takeIf { it.isNotBlank() }?.let { add(PropertyDetail(R.string.media_details_format, it)) }
        if (isVideo) videoCodec?.let { add(PropertyDetail(R.string.media_details_video_codec, it)) }
        audioCodec?.let { add(PropertyDetail(if (isVideo) R.string.media_details_audio_codec else R.string.media_details_codec, it)) }
        channels?.let {
            val label = when (it) {
                1 -> "单声道"
                2 -> "立体声"
                else -> "$it 声道"
            }
            add(PropertyDetail(R.string.media_details_channels, label))
        }
    }

    companion object {
        fun fromProbe(file: File, info: MediaInformation, audio: StreamInformation?, video: StreamInformation?): MediaTechnicalDetails {
            val audioProperties = audio?.getAllProperties()
            val videoProperties = video?.getAllProperties()
            val rotationData = videoProperties?.optJSONArray("side_data_list")
            val rotation = (0 until (rotationData?.length() ?: 0)).firstNotNullOfOrNull { index ->
                rotationData?.optJSONObject(index)?.value("rotation")?.toIntOrNull()
            } ?: video?.getTags()?.value("rotate")?.toIntOrNull() ?: 0
            return MediaTechnicalDetails(
                durationMs = ((info.getDuration()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                    ?: 0.0) * 1000).roundToLong(),
                bitrate = info.getBitrate()?.toLongOrNull()?.takeIf { it > 0 }
                    ?: (video ?: audio)?.getBitrate()?.toLongOrNull()?.takeIf { it > 0 },
                sampleRate = audio?.getSampleRate()?.toIntOrNull()?.takeIf { it > 0 },
                bitDepth = audioProperties?.positiveInt("bits_per_raw_sample")
                    ?: audioProperties?.positiveInt("bits_per_sample"),
                format = file.extension.ifBlank { info.getFormat().orEmpty().substringBefore(',') }.uppercase(Locale.ROOT),
                audioCodec = audioProperties?.value("codec_long_name") ?: audio?.getCodec(),
                videoCodec = videoProperties?.value("codec_long_name") ?: video?.getCodec(),
                channels = audioProperties?.positiveInt("channels"),
                resolution = resolution(videoProperties?.positiveInt("width"), videoProperties?.positiveInt("height"), rotation),
                frameRate = video?.getAverageFrameRate()?.let(::parseFrameRate)
            )
        }

        fun fromPlatform(file: File, isVideo: Boolean): MediaTechnicalDetails {
            val retriever = MediaMetadataRetriever()
            val metadata = try {
                retriever.setDataSource(file.absolutePath)
                listOf(MediaMetadataRetriever.METADATA_KEY_DURATION, MediaMetadataRetriever.METADATA_KEY_BITRATE,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    .associateWith(retriever::extractMetadata)
            } catch (_: Exception) {
                emptyMap()
            } finally {
                runCatching { retriever.release() }
            }
            val extractor = MediaExtractor()
            val streams = try {
                extractor.setDataSource(file.absolutePath)
                (0 until extractor.trackCount).map(extractor::getTrackFormat)
            } catch (_: Exception) {
                emptyList()
            } finally {
                runCatching { extractor.release() }
            }
            val audio = streams.firstOrNull { it.mime()?.startsWith("audio/") == true }
            val video = if (isVideo) streams.firstOrNull { it.mime()?.startsWith("video/") == true } else null
            return MediaTechnicalDetails(
                durationMs = metadata[MediaMetadataRetriever.METADATA_KEY_DURATION]?.toLongOrNull() ?: 0L,
                bitrate = metadata[MediaMetadataRetriever.METADATA_KEY_BITRATE]?.toLongOrNull()?.takeIf { it > 0 }
                    ?: (video ?: audio)?.positiveInt(MediaFormat.KEY_BIT_RATE)?.toLong(),
                sampleRate = audio?.positiveInt(MediaFormat.KEY_SAMPLE_RATE),
                format = file.extension.uppercase(Locale.ROOT),
                audioCodec = audio?.mime()?.let(::displayCodec),
                videoCodec = video?.mime()?.let(::displayCodec),
                channels = audio?.positiveInt(MediaFormat.KEY_CHANNEL_COUNT),
                resolution = resolution(
                    metadata[MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH]?.toIntOrNull() ?: video?.positiveInt(MediaFormat.KEY_WIDTH),
                    metadata[MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT]?.toIntOrNull() ?: video?.positiveInt(MediaFormat.KEY_HEIGHT),
                    metadata[MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION]?.toIntOrNull() ?: 0
                ),
                frameRate = metadata[MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE]?.let(::parseFrameRate)
                    ?: video?.positiveInt(MediaFormat.KEY_FRAME_RATE)?.toDouble()
            )
        }

        private fun JSONObject.value(key: String): String? = optString(key).takeUnless { it.isBlank() || it == "null" }
        private fun JSONObject.positiveInt(key: String): Int? = value(key)?.toIntOrNull()?.takeIf { it > 0 }
        private fun MediaFormat.positiveInt(key: String): Int? = runCatching { getInteger(key) }.getOrNull()?.takeIf { it > 0 }
        private fun MediaFormat.mime(): String? = getString(MediaFormat.KEY_MIME)

        private fun resolution(width: Int?, height: Int?, rotation: Int): String? = when {
            width == null || height == null || width <= 0 || height <= 0 -> null
            rotation % 180 != 0 && rotation % 90 == 0 -> "$height × $width"
            else -> "$width × $height"
        }

        private fun parseFrameRate(value: String): Double? {
            val numerator = value.substringBefore('/').toDoubleOrNull() ?: return null
            val denominator = value.substringAfter('/', "1").toDoubleOrNull() ?: return null
            return (numerator / denominator).takeIf { it.isFinite() && it > 0.0 }
        }

        private fun displayCodec(mimeType: String): String = when (mimeType.lowercase(Locale.ROOT)) {
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
            "audio/raw" -> "PCM"
            else -> mimeType.substringAfter('/')
        }
    }
}

internal fun formatMediaDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val seconds = durationMs / 1000L
    return if (seconds >= 3600L) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}
