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
    val fileSizeBytes: Long? = null,
    val videoCodecName: String? = null,
    val audioCodecName: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoStreamIndex: Long? = null,
    val attachedPictureStreamIndex: Long? = null
)

/** Builds argument arrays so SAF URLs and file names remain single arguments. */
internal object MediaConvertCommand {
    fun plan(input: String, output: String, format: MediaConvertFormatInfo, options: MediaConvertOptions, source: MediaConvertSource?,
             logPath: String, supportedPixelFormats: List<String>? = null, subtitle: MediaConvertSubtitleInput? = null,
             allowHardware: Boolean = true, hardwareCodecNames: Map<String, String> = emptyMap(), metadata: MediaConvertMetadata? = null): List<List<String>> =
        if (MediaConvertVideoSettingsSupport.usesTwoPass(format, options, source)) {
            listOf(build(input, "/dev/null", format, options, source, supportedPixelFormats, MediaConvertPass(1, logPath), subtitle, allowHardware, hardwareCodecNames, metadata),
                build(input, output, format, options, source, supportedPixelFormats, MediaConvertPass(2, logPath), subtitle, allowHardware, hardwareCodecNames, metadata))
        } else listOf(build(input, output, format, options, source, supportedPixelFormats, subtitle = subtitle,
            allowHardware = allowHardware, hardwareCodecNames = hardwareCodecNames, metadata = metadata))

    /** True when the plan runs a MediaCodec encoder, i.e. when a software retry is meaningful. */
    fun usesHardwareEncoder(format: MediaConvertFormatInfo, options: MediaConvertOptions, source: MediaConvertSource?,
                            subtitle: MediaConvertSubtitleInput? = null): Boolean =
        !format.isAudioOnly && !MediaConvertStreamCopy.copiesVideo(format, options, source, subtitle) &&
            format.videoEncoder(options.selectedVideoCodec) in MediaConvertVideoSettingsSupport.hardwareCodecs

    // MediaCodec decoders need 8-bit 4:2:0 input; 10-bit and 4:2:2/4:4:4 fail or silently truncate on many devices.
    private val hardwareDecodable = setOf("h264", "hevc", "vp8", "vp9", "av1", "mpeg4", "mpeg2video")
    private val hardwareDecodePixelFormats = setOf("yuv420p", "yuvj420p", "nv12")

    private fun canHardwareDecode(source: MediaConvertSource?) =
        source?.videoCodecName in hardwareDecodable && source?.videoPixelFormat in hardwareDecodePixelFormats

    // Keep existing indices stable; the video settings present these values in ascending order.
    val sampleRates = listOf(0, 48000, 44100, 22050, 16000, 8000, 32000, 96000, 88200, 192000, 176400, 24000, 11025, 12000,
        1000, 2000, 4000, 6000, 7350, 64000, 352800, 384000)

    fun qualityRange(codec: String): IntRange = when (codec) {
        "libx264", "libx265" -> 0..51
        "libvpx", "libvpx-vp9", "libaom-av1" -> 0..63
        "libtheora" -> 0..10
        "h264_mediacodec", "hevc_mediacodec" -> IntRange.EMPTY
        else -> 1..31
    }

    fun quality(codec: String, options: MediaConvertOptions): Int? {
        if (codec in MediaConvertVideoSettingsSupport.hardwareCodecs) return null
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
              supportedPixelFormats: List<String>? = null, pass: MediaConvertPass? = null, subtitle: MediaConvertSubtitleInput? = null,
              allowHardware: Boolean = true, hardwareCodecNames: Map<String, String> = emptyMap(), metadata: MediaConvertMetadata? = null): List<String> {
        subtitle?.let { MediaConvertSubtitles.validate(format, options, it) }
        val synthetic = !format.isAudioOnly && source?.hasVideo == false
        val selectedVideo = if (format.isAudioOnly) ""
            else if (MediaConvertStreamCopy.copiesVideo(format, options, source, subtitle)) "copy"
            else format.videoEncoder(options.selectedVideoCodec)
        val requestedVideo = if (synthetic && selectedVideo == "copy") format.videoEncoder("auto") else selectedVideo
        // A failed MediaCodec attempt is retried with the matching software encoder.
        val video = if (!allowHardware) MediaConvertVideoSettingsSupport.softwareFallback(requestedVideo) ?: requestedVideo else requestedVideo
        val hardware = video in MediaConvertVideoSettingsSupport.hardwareCodecs
        if (!format.isAudioOnly && video != "copy") {
            require(options.resolution.isValid) { "Invalid video resolution" }
            require(options.videoSettings.isValid) { "Invalid video settings" }
        }
        require(pass == null || MediaConvertVideoSettingsSupport.usesTwoPass(format, options, source)) { "Two-pass encoding requires a supported encoder and target bitrate" }
        require(pass == null || !synthetic || source?.durationSeconds != null) { "Two-pass generated video requires a known duration" }
        val analysisPass = pass?.number == 1
        val softSubtitle = subtitle?.takeIf { it.type == MediaConvertSubtitleType.SOFT && !analysisPass }
        val burnSubtitle = subtitle?.takeIf { it.type == MediaConvertSubtitleType.BURN_IN }
        val audio = if (MediaConvertStreamCopy.copiesAudio(format, options, source)) "copy"
            else format.audioEncoder(options.selectedAudioCodec)
        val sourceInputIndex = if (synthetic) 1 else 0
        val coverSupported = MediaConvertMetadataSupport.supportsCover(format)
        val customCover = metadata?.coverPath?.takeIf {
            !analysisPass && coverSupported && metadata.cover == MediaConvertCoverMode.CUSTOM
        }
        if (!analysisPass && coverSupported && metadata?.cover == MediaConvertCoverMode.CUSTOM) {
            require(!customCover.isNullOrBlank()) { "A selected cover image must be prepared before conversion" }
        }
        val copiedCoverIndex = source?.attachedPictureStreamIndex?.takeIf {
            !analysisPass && coverSupported && (metadata == null || metadata.cover == MediaConvertCoverMode.DEFAULT)
        }
        val customCoverInputIndex = sourceInputIndex + 1 + if (softSubtitle != null) 1 else 0
        // Matroska stores a custom cover as an attachment; other muxers use an attached-picture video stream.
        val streamCover = copiedCoverIndex != null || (customCover != null && format.formatName != "matroska")
        val mainVideoSpecifier = if (streamCover) ":v:0" else ":v"
        fun mainVideoOption(name: String) = "-$name$mainVideoSpecifier"
        var hasVideoFilters = false
        return buildList {
            addAll(listOf("-y", "-hide_banner"))
            if (synthetic) {
                val target = options.resolution.size ?: MediaConvertVideoSize(1280, 720)
                val size = "${target.width}x${target.height}"
                addAll(listOf("-f", "lavfi", "-i", "color=c=black:s=$size:r=30"))
            }
            // Must precede the -i it applies to; frames come back as system-memory nv12/yuv420p, so filters still work.
            // Only paired with a hardware encoder: with libx264 the encode dominates and the gain is marginal.
            if (hardware && !synthetic && canHardwareDecode(source)) addAll(listOf("-hwaccel", "mediacodec"))
            addAll(listOf("-i", input))
            softSubtitle?.let { addAll(listOf("-i", it.path)) }
            if (customCover != null && format.formatName != "matroska") addAll(listOf("-i", customCover))
            when {
                format.isAudioOnly -> {
                    addAll(listOf("-map", "0:a:0", "-c:a", audio))
                    if (!streamCover) add("-vn")
                }
                synthetic -> {
                    addAll(listOf("-map", "0:v:0", mainVideoOption("c"), video))
                    if (!analysisPass) addAll(listOf("-map", "1:a:0", "-shortest", "-c:a", audio))
                    source?.durationSeconds?.takeIf { it > 0 }?.let { addAll(listOf("-t", String.format(Locale.US, "%.3f", it))) }
                }
                else -> {
                    addAll(listOf("-map", "0:${source?.videoStreamIndex ?: "v:0"}", mainVideoOption("c"), video))
                    if (!analysisPass) addAll(listOf("-map", "0:a:0?", "-c:a", audio))
                }
            }
            if (!analysisPass) {
                val coverVideoIndex = if (format.isAudioOnly) 0 else 1
                val coverMap = when {
                    customCover != null && format.formatName != "matroska" -> "$customCoverInputIndex:v:0"
                    copiedCoverIndex != null -> "$sourceInputIndex:$copiedCoverIndex"
                    else -> null
                }
                coverMap?.let {
                    addAll(listOf("-map", it, "-c:v:$coverVideoIndex", "copy", "-disposition:v:$coverVideoIndex", "attached_pic"))
                }
                if (customCover != null && format.formatName == "matroska") {
                    addAll(listOf("-attach", customCover, "-metadata:s:t:0", "mimetype=image/jpeg", "-metadata:s:t:0", "filename=cover.jpg"))
                } else if (format.formatName == "matroska" && (metadata == null || metadata.cover == MediaConvertCoverMode.DEFAULT)) {
                    // Matroska artwork is normally an attachment, rather than an attached-picture stream.
                    addAll(listOf("-map", "$sourceInputIndex:t?", "-c:t", "copy"))
                }
            }
            if (analysisPass) add("-an")
            if (!format.isAudioOnly) {
                if (softSubtitle == null) add("-sn") else {
                    val subtitleIndex = if (synthetic) 2 else 1
                    addAll(listOf("-map", "$subtitleIndex:s:0", "-c:s", MediaConvertSubtitles.encoder(format, MediaConvertSubtitles.extension(softSubtitle.fileName)),
                        "-disposition:s:0", "default"))
                }
                add("-dn")
                if (video != "copy") {
                    val settings = options.videoSettings
                    val targetBitrate = MediaConvertVideoSettingsSupport.targetBitrate(format, options, source)
                    // MediaCodec has no CRF, and FFmpeg's 200 kb/s default would ruin the output, so always set a bitrate.
                    val bitrate = targetBitrate ?: if (hardware) {
                        val size = options.resolution.size
                        MediaConvertVideoSettingsSupport.defaultHardwareBitrate(video, size?.width ?: source?.videoWidth,
                            size?.height ?: source?.videoHeight, settings.frameRate.takeIf { it.isNotEmpty() }
                                ?.let(MediaConvertVideoSettingsSupport::rate) ?: source?.frameRate)
                    } else null
                    val q = if (bitrate == null) settings.crf?.takeIf { video in MediaConvertVideoSettingsSupport.crfCodecs }
                        ?.coerceIn(qualityRange(video)) ?: quality(video, options) else null
                    if (q != null) {
                        addAll(listOf(if (video in setOf("libx264", "libx265", "libvpx", "libvpx-vp9", "libaom-av1")) {
                            if (streamCover) "-crf:v:0" else "-crf"
                        } else mainVideoOption("q"), q.toString()))
                        if (bitrate == null && video in setOf("libvpx", "libvpx-vp9", "libaom-av1")) addAll(listOf(mainVideoOption("b"), "0"))
                    }
                    when (video) {
                        "libx264" -> addAll(listOf(if (streamCover) "-preset:v:0" else "-preset", "veryfast"))
                        "libx265" -> addAll(listOf(if (streamCover) "-preset:v:0" else "-preset", "fast"))
                        "libaom-av1" -> {
                            addAll(listOf(if (streamCover) "-cpu-used:v:0" else "-cpu-used", "6", if (streamCover) "-row-mt:v:0" else "-row-mt", "1"))
                            if (q == null && bitrate == null) addAll(listOf(if (streamCover) "-crf:v:0" else "-crf", "30", mainVideoOption("b"), "0"))
                        }
                    }
                    if (!synthetic || settings.rotation != MediaConvertRotation.NONE || settings.flipHorizontal || settings.flipVertical ||
                        settings.deinterlace != MediaConvertDeinterlace.OFF || settings.filter != MediaConvertVideoFilter.NONE || burnSubtitle != null) {
                        val filters = listOfNotNull(MediaConvertVideoSettingsSupport.filters(options.resolution, settings, source),
                            burnSubtitle?.let(MediaConvertSubtitles::burnFilter)).joinToString(",")
                        if (filters.isNotEmpty()) {
                            addAll(listOf(if (streamCover) "-filter:v:0" else "-vf", filters))
                            hasVideoFilters = true
                        }
                    }
                    val targetPixFmt = MediaConvertVideoSettingsSupport.pixelFormat(video, settings.pixelFormat, source?.videoPixelFormat, supportedPixelFormats)
                    if (targetPixFmt != source?.videoPixelFormat) addAll(listOf(if (streamCover) "-pix_fmt:v:0" else "-pix_fmt", targetPixFmt))
                    val rate = MediaConvertVideoSettingsSupport.frameRate(video, settings, source?.frameRate)
                    rate?.let { addAll(listOf(if (streamCover) "-r:v:0" else "-r", it)) }
                    MediaConvertVideoSettingsSupport.keyframeFrames(settings, rate, source?.frameRate)?.let {
                        addAll(listOf(if (streamCover) "-g:v:0" else "-g", it.toString()))
                        // MediaCodec rounds -g to whole seconds and ignores forced keyframes.
                        if (!hardware) addAll(listOf(if (streamCover) "-force_key_frames:v:0" else "-force_key_frames", "expr:gte(t,n_forced*${settings.keyframeSeconds.toDouble()})"))
                    }
                    if (video == "mpeg1video" || video == "mpeg2video") {
                        if (bitrate == null && q == null) addAll(listOf(mainVideoOption("b"), "2000k"))
                    }
                    if (bitrate != null) addAll(listOf(mainVideoOption("b"), MediaConvertVideoBitrate.ffmpegValue(bitrate)))
                    // Without a pinned name FFmpeg takes whatever the platform lists first, often a software c2.android.* codec.
                    if (hardware) hardwareCodecNames[video]?.let { addAll(listOf(if (streamCover) "-codec_name:v:0" else "-codec_name", it)) }
                    if ((video == "libx265" || video == "hevc_mediacodec") && format.formatName in setOf("mp4", "mov")) addAll(listOf(mainVideoOption("tag"), "hvc1"))
                    if (pass != null) {
                        if (video == "libx265") {
                            val stats = pass.logPath.replace('\\', '/').replace(":", "\\:")
                            addAll(listOf(if (streamCover) "-x265-params:v:0" else "-x265-params", "pass=${pass.number}:stats=$stats"))
                        } else addAll(listOf(if (streamCover) "-pass:v:0" else "-pass", pass.number.toString(), if (streamCover) "-passlogfile:v:0" else "-passlogfile", pass.logPath))
                    }
                } else if (MediaConvertStreamCopy.videoEncoderName(source?.videoCodecName) == "libx265" &&
                    format.formatName in setOf("mp4", "mov")) {
                    // The mov muxer otherwise tags copied HEVC as hev1, which several players reject.
                    addAll(listOf(mainVideoOption("tag"), "hvc1"))
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
            if (video != "copy" && !hardware) {
                // Use the available CPU for software encoders while keeping thread creation
                // bounded on devices that report a large heterogeneous core count.
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
                addAll(listOf(if (streamCover && !format.isAudioOnly) "-threads:v:0" else "-threads", threads.toString()))
                if (hasVideoFilters) addAll(listOf("-filter_threads", threads.toString()))
            }
            if (!analysisPass) metadata?.let {
                addAll(listOf("-map_metadata", sourceInputIndex.toString()))
                addAll(it.arguments(format.isAudioOnly))
            }
            addAll(listOf("-f", if (analysisPass) "null" else format.formatName, output))
        }
    }
}
