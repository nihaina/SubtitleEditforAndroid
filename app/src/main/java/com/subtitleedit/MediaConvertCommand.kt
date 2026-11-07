package com.subtitleedit

import java.util.Locale

internal data class MediaConvertSource(
    val hasVideo: Boolean,
    val durationSeconds: Double?,
    val frameRate: Double? = null,
    val videoBitrateBps: Long? = null,
    val audioSampleRate: Int? = null,
    val audioChannels: Int? = null,
    val audioBitrateBps: Long? = null,
    val audioChannelLayout: String? = null,
    val videoPixelFormat: String? = null,
    val hasAudio: Boolean = true,
    val fileSizeBytes: Long? = null
)

/** Builds argument arrays so SAF URLs and file names remain single arguments. */
internal object MediaConvertCommand {
    fun plan(input: String, output: String, format: MediaConvertFormatInfo, options: MediaConvertOptions, source: MediaConvertSource?,
             logPath: String, supportedPixelFormats: List<String>? = null): List<List<String>> =
        if (MediaConvertVideoSettingsSupport.usesTwoPass(format, options, source)) {
            listOf(build(input, "/dev/null", format, options, source, supportedPixelFormats, MediaConvertPass(1, logPath)),
                build(input, output, format, options, source, supportedPixelFormats, MediaConvertPass(2, logPath)))
        } else listOf(build(input, output, format, options, source, supportedPixelFormats))

    // Keep existing indices stable; the video settings present these values in ascending order.
    val sampleRates = listOf(0, 48000, 44100, 22050, 16000, 8000, 32000, 96000, 88200, 192000, 176400, 24000, 11025, 12000,
        1000, 2000, 4000, 6000, 7350, 64000, 352800, 384000)

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

    fun build(input: String, output: String, format: MediaConvertFormatInfo, options: MediaConvertOptions, source: MediaConvertSource?,
              supportedPixelFormats: List<String>? = null, pass: MediaConvertPass? = null): List<String> {
        val synthetic = !format.isAudioOnly && source?.hasVideo == false
        val selectedVideo = if (format.isAudioOnly) "" else format.videoEncoder(options.selectedVideoCodec)
        val video = if (synthetic && selectedVideo == "copy") format.videoEncoder("auto") else selectedVideo
        if (!format.isAudioOnly && video != "copy") {
            require(options.resolution.isValid) { "Invalid video resolution" }
            require(options.videoSettings.isValid) { "Invalid video settings" }
        }
        require(pass == null || MediaConvertVideoSettingsSupport.usesTwoPass(format, options, source)) { "Two-pass encoding requires a supported encoder and target bitrate" }
        require(pass == null || !synthetic || source?.durationSeconds != null) { "Two-pass generated video requires a known duration" }
        val analysisPass = pass?.number == 1
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
                    addAll(listOf("-map", "0:v:0", "-c:v", video))
                    if (!analysisPass) addAll(listOf("-map", "1:a:0", "-shortest", "-c:a", audio))
                    source?.durationSeconds?.takeIf { it > 0 }?.let { addAll(listOf("-t", String.format(Locale.US, "%.3f", it))) }
                }
                else -> {
                    addAll(listOf("-map", "0:v:0", "-c:v", video))
                    if (!analysisPass) addAll(listOf("-map", "0:a:0?", "-c:a", audio))
                }
            }
            if (analysisPass) add("-an")
            if (!format.isAudioOnly) {
                addAll(listOf("-sn", "-dn"))
                if (video != "copy") {
                    val settings = options.videoSettings
                    val bitrate = MediaConvertVideoSettingsSupport.targetBitrate(format, options, source)
                    val q = if (bitrate == null) settings.crf?.takeIf { video in MediaConvertVideoSettingsSupport.crfCodecs }
                        ?.coerceIn(qualityRange(video)) ?: quality(video, options) else null
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
                    if (!synthetic || settings.rotation != MediaConvertRotation.NONE || settings.flipHorizontal || settings.flipVertical ||
                        settings.deinterlace != MediaConvertDeinterlace.OFF || settings.filter != MediaConvertVideoFilter.NONE) {
                        addAll(listOf("-vf", MediaConvertVideoSettingsSupport.filters(options.resolution, settings)))
                    }
                    addAll(listOf("-pix_fmt", MediaConvertVideoSettingsSupport.pixelFormat(video, settings.pixelFormat, source?.videoPixelFormat, supportedPixelFormats)))
                    val rate = MediaConvertVideoSettingsSupport.frameRate(video, settings, source?.frameRate)
                    rate?.let { addAll(listOf("-r", it)) }
                    MediaConvertVideoSettingsSupport.keyframeFrames(settings, rate, source?.frameRate)?.let {
                        addAll(listOf("-g", it.toString(), "-force_key_frames", "expr:gte(t,n_forced*${settings.keyframeSeconds.toDouble()})"))
                    }
                    if (video == "mpeg1video" || video == "mpeg2video") {
                        if (bitrate == null && q == null) addAll(listOf("-b:v", "2000k"))
                    }
                    if (bitrate != null) addAll(listOf("-b:v", MediaConvertVideoBitrate.ffmpegValue(bitrate)))
                    if (video == "libx265" && format.formatName in setOf("mp4", "mov")) addAll(listOf("-tag:v", "hvc1"))
                    if (pass != null) {
                        if (video == "libx265") {
                            val stats = pass.logPath.replace('\\', '/').replace(":", "\\:")
                            addAll(listOf("-x265-params", "pass=${pass.number}:stats=$stats"))
                        } else addAll(listOf("-pass", pass.number.toString(), "-passlogfile", pass.logPath))
                    }
                }
            }
            if (audio != "copy" && !analysisPass) {
                val profile = MediaConvertAudioCodecs.profile(audio, format.formatName)
                val vbrQuality = MediaConvertAudioVbr.activeQuality(format, options)
                val parameters = MediaConvertAudioCodecs.parameters(audio, format.formatName,
                    if (vbrQuality != null) options.copy(audioBitrate = "") else options, source)
                if (profile.experimental) addAll(listOf("-strict", "-2"))
                if (vbrQuality != null) addAll(listOf("-q:a", vbrQuality.toString()))
                else parameters.bitrate?.let { addAll(listOf("-b:a", MediaConvertVideoBitrate.ffmpegValue(it))) }
                parameters.sampleRate?.let { addAll(listOf("-ar", it.toString())) }
                parameters.channels?.let { addAll(listOf("-ac", it.toString())) }
                parameters.channelLayout?.let { addAll(listOf("-channel_layout:a", it)) }
                MediaConvertAudioVolume.filter(options.audioVolumePercent)?.let { addAll(listOf("-af", it)) }
                if (audio == "aac" && format.formatName == "adts" && (parameters.channels ?: source?.audioChannels ?: 2) > 6) {
                    addAll(listOf("-aac_pce", "1"))
                }
                if (audio == "wavpack" && (parameters.sampleRate ?: source?.audioSampleRate ?: 48000) < 8000) {
                    // Very low rates can produce an automatic frame size below WavPack's minimum of 128.
                    addAll(listOf("-frame_size:a", "1024"))
                }
            }
            addAll(listOf("-f", if (analysisPass) "null" else format.formatName, output))
        }
    }
}
