package com.subtitleedit

import java.util.Locale
import kotlin.math.ceil

internal enum class MediaConvertRotation { NONE, CLOCKWISE, HALF, COUNTERCLOCKWISE }
internal enum class MediaConvertDeinterlace { OFF, AUTO, ON }
internal enum class MediaConvertVideoFilter { NONE, GRAYSCALE, YELLOW, RED, GREEN, BLUE, GOLD }

internal data class MediaConvertVideoSettings(
    val frameRate: String = "",
    val rotation: MediaConvertRotation = MediaConvertRotation.NONE,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val crf: Int? = null,
    val sizeLimitPercent: Int? = null,
    val twoPass: Boolean = false,
    val keyframeSeconds: String = "",
    val deinterlace: MediaConvertDeinterlace = MediaConvertDeinterlace.OFF,
    val filter: MediaConvertVideoFilter = MediaConvertVideoFilter.NONE,
    val pixelFormat: String = "auto"
) {
    val isValid: Boolean get() =
        (frameRate.isEmpty() || MediaConvertVideoSettingsSupport.rate(frameRate)?.let { it in 1.0..240.0 } == true) &&
            (crf == null || crf in 0..63) && (sizeLimitPercent == null || sizeLimitPercent in MediaConvertVideoSettingsSupport.sizePercents) &&
            (keyframeSeconds.isEmpty() || keyframeSeconds.toDoubleOrNull()?.let { it.isFinite() && it in 0.1..600.0 } == true) &&
            pixelFormat in MediaConvertVideoSettingsSupport.pixelFormats + listOf("auto", "original")
}

internal data class MediaConvertPass(val number: Int, val logPath: String) {
    init { require(number in 1..2 && logPath.isNotBlank()) }
}

internal enum class MediaConvertVideoSizeError { UNKNOWN_SOURCE_SIZE, UNKNOWN_DURATION, UNKNOWN_AUDIO_BITRATE, INSUFFICIENT_SIZE }
internal class MediaConvertVideoSizeException(val reason: MediaConvertVideoSizeError) : IllegalArgumentException(reason.name)

/** Shared by the settings UI and command builder; unsupported choices never reach FFmpeg. */
internal object MediaConvertVideoSettingsSupport {
    val sizePercents = (90 downTo 10 step 10).toList()
    val crfCodecs = setOf("libx264", "libx265", "libvpx", "libvpx-vp9", "libaom-av1")
    // libxvid's second pass opens an internal /tmp or current-directory file; neither is an app cache path on Android.
    val twoPassCodecs = setOf("libx264", "libx265", "mpeg1video", "mpeg2video", "mpeg4", "libvpx", "libvpx-vp9")
    val frameRates = listOf("12", "15", "24000/1001", "24", "25", "30000/1001", "30", "48", "50", "60000/1001", "60", "100", "120", "144", "240")
    val mpegRates = listOf("24000/1001", "24", "25", "30000/1001", "30", "50", "60000/1001", "60")
    val pixelFormats = listOf("yuv420p", "yuvj420p", "yuv422p", "yuvj422p", "yuv444p", "yuvj444p", "nv12", "nv16", "nv21",
        "yuv420p10le", "yuv422p10le", "yuv444p10le", "nv20le", "gray", "gray10le")

    fun rate(value: String): Double? {
        val parts = value.split('/')
        if (parts.size !in 1..2) return null
        val numerator = parts[0].toDoubleOrNull() ?: return null
        val denominator = if (parts.size == 2) parts[1].toDoubleOrNull() ?: return null else 1.0
        return (numerator / denominator).takeIf { denominator > 0 && it.isFinite() && it > 0 }
    }

    fun rateLabel(value: String): String = when (value) {
        "24000/1001" -> "23.976"
        "30000/1001" -> "29.97"
        "60000/1001" -> "59.94"
        else -> value
    }

    fun parsePixelFormats(log: String): List<String> = Regex("Supported pixel formats:\\s*([^\\r\\n]+)")
        .find(log)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.filter { it in pixelFormats }.orEmpty()

    fun supportedPixels(codec: String, detected: List<String>? = null): List<String> = detected?.takeIf { it.isNotEmpty() } ?: when (codec) {
        "libx264" -> listOf("yuv420p", "yuv422p", "yuv444p", "nv12", "nv16", "nv21", "yuv420p10le", "yuv422p10le", "yuv444p10le", "gray", "gray10le")
        // The bundled x265 build is Main10; an encoder probe takes precedence on other builds.
        "libx265" -> listOf("yuv420p10le", "yuv422p10le", "yuv444p10le", "gray10le")
        "libvpx-vp9", "libaom-av1" -> listOf("yuv420p", "yuv422p", "yuv444p", "yuv420p10le", "yuv422p10le", "yuv444p10le")
        "mpeg2video", "libtheora" -> listOf("yuv420p", "yuv422p")
        else -> listOf("yuv420p")
    }

    fun pixelFormat(codec: String, requested: String, source: String?, detected: List<String>?): String {
        val available = supportedPixels(codec, detected)
        val selected = if (requested == "original") source else requested
        return selected?.takeIf { it in available } ?: "yuv420p".takeIf { it in available } ?: available.first()
    }

    fun usesTwoPass(format: MediaConvertFormatInfo, options: MediaConvertOptions, source: MediaConvertSource?): Boolean {
        if (format.isAudioOnly) return false
        val codec = format.videoEncoder(options.selectedVideoCodec).let { if (it == "copy" && source?.hasVideo == false) format.videoEncoder("auto") else it }
        return codec in twoPassCodecs && options.videoSettings.twoPass &&
            (options.videoSettings.sizeLimitPercent != null || options.videoBitrate.targetBitsPerSecond(source?.videoBitrateBps) != null)
    }

    /** Integer arithmetic avoids overflow and preserves byte precision for large source files. */
    fun targetSizeBytes(sourceBytes: Long?, percent: Int?): Long? {
        if (sourceBytes == null || sourceBytes <= 0 || percent !in sizePercents) return null
        requireNotNull(percent)
        return sourceBytes / 100 * percent + sourceBytes % 100 * percent / 100
    }

    /** Reserve audio and muxing overhead while keeping the full duration. This is an average-size target. */
    fun targetBitrate(format: MediaConvertFormatInfo, options: MediaConvertOptions, source: MediaConvertSource?): Long? {
        val percent = options.videoSettings.sizeLimitPercent ?: return options.videoBitrate.targetBitsPerSecond(source?.videoBitrateBps)
        val targetBytes = targetSizeBytes(source?.fileSizeBytes, percent)
            ?: throw MediaConvertVideoSizeException(MediaConvertVideoSizeError.UNKNOWN_SOURCE_SIZE)
        val duration = source?.durationSeconds?.takeIf { it.isFinite() && it > 0 }
            ?: throw MediaConvertVideoSizeException(MediaConvertVideoSizeError.UNKNOWN_DURATION)
        requireNotNull(source)
        val audio = format.audioEncoder(options.selectedAudioCodec)
        val audioRate = if (!source.hasAudio) 0L else if (audio == "copy") {
            source.audioBitrateBps ?: throw MediaConvertVideoSizeException(MediaConvertVideoSizeError.UNKNOWN_AUDIO_BITRATE)
        } else {
            val parameters = MediaConvertAudioCodecs.parameters(audio, format.formatName, options, source)
            parameters.bitrate ?: if (MediaConvertAudioCodecs.profile(audio, format.formatName).adjustableBitrate) 256_000L else {
                // A conservative PCM budget also covers lossless encoders before their compression ratio is known.
                val bytes = when {
                    audio.contains("64") -> 8
                    audio.contains("32") -> 4
                    audio.contains("24") -> 3
                    audio.contains("16") -> 2
                    audio.endsWith("u8") || audio.endsWith("s8") || audio in setOf("pcm_alaw", "pcm_mulaw") -> 1
                    else -> 4
                }
                (parameters.sampleRate ?: source.audioSampleRate ?: 48000).toLong() *
                    (parameters.channels ?: source.audioChannels ?: 2) * bytes * 8
            }
        }
        val videoRate = (targetBytes.toDouble() * 8 * 0.97 / duration - audioRate).toLong()
        if (videoRate !in 32_000L..999_999_000L) throw MediaConvertVideoSizeException(MediaConvertVideoSizeError.INSUFFICIENT_SIZE)
        return videoRate
    }

    fun filters(resolution: MediaConvertResolutionSettings, settings: MediaConvertVideoSettings): String = buildList {
        when (settings.deinterlace) {
            MediaConvertDeinterlace.AUTO -> add("yadif=mode=send_frame:parity=auto:deint=interlaced")
            MediaConvertDeinterlace.ON -> add("yadif=mode=send_frame:parity=auto:deint=all")
            MediaConvertDeinterlace.OFF -> Unit
        }
        when (settings.rotation) {
            MediaConvertRotation.CLOCKWISE -> add("transpose=clock")
            MediaConvertRotation.HALF -> addAll(listOf("hflip", "vflip"))
            MediaConvertRotation.COUNTERCLOCKWISE -> add("transpose=cclock")
            MediaConvertRotation.NONE -> Unit
        }
        if (settings.flipHorizontal) add("hflip")
        if (settings.flipVertical) add("vflip")
        add(MediaConvertResolution.filter(resolution))
        when (settings.filter) {
            MediaConvertVideoFilter.GRAYSCALE -> add("hue=s=0")
            MediaConvertVideoFilter.YELLOW -> add("colorchannelmixer=rr=1:gg=1:bb=0.5")
            MediaConvertVideoFilter.RED -> add("colorchannelmixer=rr=1:gg=0.65:bb=0.65")
            MediaConvertVideoFilter.GREEN -> add("colorchannelmixer=rr=0.65:gg=1:bb=0.65")
            MediaConvertVideoFilter.BLUE -> add("colorchannelmixer=rr=0.65:gg=0.65:bb=1")
            MediaConvertVideoFilter.GOLD -> add("colorchannelmixer=rr=0.393:rg=0.769:rb=0.189:gr=0.349:gg=0.686:gb=0.168:br=0.272:bg=0.534:bb=0.131")
            MediaConvertVideoFilter.NONE -> Unit
        }
    }.joinToString(",")

    fun frameRate(codec: String, settings: MediaConvertVideoSettings, sourceRate: Double?): String? {
        if (codec !in setOf("mpeg1video", "mpeg2video")) return settings.frameRate.takeIf { it.isNotEmpty() }
        val desired = rate(settings.frameRate) ?: sourceRate ?: 25.0
        return String.format(Locale.US, "%.6f", mpegRates.mapNotNull(::rate).minBy { kotlin.math.abs(it - desired) })
    }

    fun keyframeFrames(settings: MediaConvertVideoSettings, outputRate: String?, sourceRate: Double?): Int? =
        settings.keyframeSeconds.toDoubleOrNull()?.let { ceil(it * (outputRate?.let(::rate) ?: sourceRate ?: 30.0)).toInt().coerceAtLeast(1) }
}
