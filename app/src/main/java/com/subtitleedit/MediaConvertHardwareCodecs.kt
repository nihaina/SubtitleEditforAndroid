package com.subtitleedit

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * FFmpeg lists h264_mediacodec/hevc_mediacodec whenever the library was built with MediaCodec support, which says
 * nothing about the device. Only offer them when the platform reports a real hardware encoder, and pin that encoder
 * by name because FFmpeg's default lookup can land on a software c2.android.* codec.
 */
internal object MediaConvertHardwareCodecs {
    private val mimeTypes = mapOf("h264_mediacodec" to "video/avc", "hevc_mediacodec" to "video/hevc")

    fun isHardware(info: MediaCodecInfo): Boolean {
        if (!info.isEncoder) return false
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isSoftwareOnly
        return !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.") && !info.name.contains(".sw.")
    }

    /** Maps each usable FFmpeg encoder name to the platform codec name that should be passed as -codec_name. */
    fun detect(infos: List<MediaCodecInfo> = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }.getOrDefault(emptyList())): Map<String, String> =
        mimeTypes.mapNotNull { (encoder, mime) ->
            val match = infos.firstOrNull { info -> isHardware(info) && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            match?.let { encoder to it.name }
        }.toMap()
}
