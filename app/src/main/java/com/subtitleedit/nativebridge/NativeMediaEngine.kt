package com.subtitleedit.nativebridge

import java.io.File

internal data class MediaProbeResult(
    val startTimeSeconds: Double,
    val defaultAudioStreamIndex: Int?
)

internal interface NativeMediaEngine {
    fun probe(file: File, inspectVideoAudioTrack: Boolean): MediaProbeResult
    fun openOperation(): NativeMediaOperation
}

internal interface NativeMediaOperation {
    suspend fun convertToWav(inputFile: File, outputFile: File): Boolean

    suspend fun convertToPcm(inputFile: File, outputFile: File, format: PcmFormat): Boolean

    fun cancel()
}

internal enum class PcmFormat {
    SPEECH_WAV_16K_MONO,
    DEMIX_FLOAT_44K_STEREO
}
