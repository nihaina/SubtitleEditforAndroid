package com.subtitleedit

import java.util.Locale
import kotlin.math.abs

internal data class MediaConvertSource(
    val hasVideo: Boolean,
    val durationSeconds: Double?,
    val frameRate: Double? = null,
    val videoBitrateBps: Long? = null
)

/** Builds argument arrays so SAF URLs and file names remain single arguments. */
internal object MediaConvertCommand {
    val sampleRates = listOf(0, 48000, 44100, 22050, 16000, 8000)

    fun qualityRange(codec: String): IntRange = when (codec) {
        "libx264", "libx265" -> 0..51
        "libvpx", "libvpx-vp9", "libaom-av1" -> 0..63
        "libtheora" -> 0..10
        else -> 1..31
    }

    fun quality(codec: String, options: MediaConvertOptions): Int? {
        if (options.selectedQualityId == "-1") return null
        if (options.selectedQualityId == "custom") return options.customQuality.toIntOrNull()?.coerceIn(qualityRange(codec))
        val level = listOf("18", "23", "28", "33").indexOf(options.selectedQualityId)
        if (level < 0) return null
        return when (codec) {
            "libx264", "libx265", "libvpx", "libvpx-vp9", "libaom-av1" -> listOf(18, 23, 28, 33)[level]
            "libtheora" -> listOf(9, 7, 5, 3)[level]
            else -> listOf(2, 4, 8, 12)[level]
        }
    }

    fun build(input: String, output: String, format: MediaConvertFormatInfo, options: MediaConvertOptions, source: MediaConvertSource?): List<String> {
        val synthetic = !format.isAudioOnly && source?.hasVideo == false
        val selectedVideo = if (format.isAudioOnly) "" else format.videoEncoder(options.selectedVideoCodec)
        val video = if (synthetic && selectedVideo == "copy") format.videoEncoder("auto") else selectedVideo
        if (!format.isAudioOnly && video != "copy") require(options.resolution.isValid) { "Invalid video resolution" }
        val audio = format.audioEncoder(options.selectedAudioCodec)
        return buildList {
            addAll(listOf("-y", "-hide_banner"))
            if (synthetic) {
                val target = options.resolution.size ?: MediaConvertVideoSize(1280, 720)
                val size = "${target.width}x${target.height}"
                addAll(listOf("-f", "lavfi", "-i", "color=c=black:s=$size:r=30"))
            }
            addAll(listOf("-i", input))
            when {
                format.isAudioOnly -> addAll(listOf("-map", "0:a:0", "-vn", "-c:a", audio))
                synthetic -> {
                    addAll(listOf("-map", "0:v:0", "-map", "1:a:0", "-shortest", "-c:v", video, "-c:a", audio))
                    source?.durationSeconds?.takeIf { it > 0 }?.let { addAll(listOf("-t", String.format(Locale.US, "%.3f", it))) }
                }
                else -> addAll(listOf("-map", "0:v:0", "-c:v", video, "-map", "0:a:0?", "-c:a", audio))
            }
            if (!format.isAudioOnly) {
                addAll(listOf("-sn", "-dn"))
                if (video != "copy") {
                    val bitrate = options.videoBitrate.targetBitsPerSecond(source?.videoBitrateBps)
                    val q = if (bitrate == null) quality(video, options) else null
                    if (q != null) {
                        addAll(listOf(if (video in setOf("libx264", "libx265", "libvpx", "libvpx-vp9", "libaom-av1")) "-crf" else "-q:v", q.toString()))
                        if (bitrate == null && video in setOf("libvpx", "libvpx-vp9", "libaom-av1")) addAll(listOf("-b:v", "0"))
                    }
                    when (video) {
                        "libx264" -> addAll(listOf("-preset", "veryfast"))
                        "libx265" -> addAll(listOf("-preset", "fast"))
                        "libaom-av1" -> {
                            addAll(listOf("-cpu-used", "6", "-row-mt", "1"))
                            if (q == null && bitrate == null) addAll(listOf("-crf", "30", "-b:v", "0"))
                        }
                    }
                    if (!synthetic) addAll(listOf("-vf", MediaConvertResolution.filter(options.resolution)))
                    addAll(listOf("-pix_fmt", "yuv420p"))
                    if (video == "mpeg1video" || video == "mpeg2video") {
                        val rates = listOf(24000.0 / 1001, 24.0, 25.0, 30000.0 / 1001, 30.0, 50.0, 60000.0 / 1001, 60.0)
                        val rate = rates.minBy { abs(it - (source?.frameRate ?: 25.0)) }
                        addAll(listOf("-r", String.format(Locale.US, "%.6f", rate)))
                        if (bitrate == null && q == null) addAll(listOf("-b:v", "2000k"))
                    }
                    if (bitrate != null) addAll(listOf("-b:v", MediaConvertVideoBitrate.ffmpegValue(bitrate)))
                    if (video == "libx265" && format.formatName in setOf("mp4", "mov")) addAll(listOf("-tag:v", "hvc1"))
                }
            }
            if (audio != "copy") {
                if (!audio.startsWith("pcm_") && audio != "flac") options.audioBitrate.trim().toIntOrNull()?.takeIf { it > 0 }?.let {
                    addAll(listOf("-b:a", "${it}k"))
                }
                sampleRates.getOrNull(options.sampleRateIndex)?.takeIf { it > 0 }?.let { addAll(listOf("-ar", it.toString())) }
                if (options.channelIndex > 0) addAll(listOf("-ac", if (options.channelIndex == 1) "2" else "1"))
            }
            addAll(listOf("-f", format.formatName, output))
        }
    }
}
