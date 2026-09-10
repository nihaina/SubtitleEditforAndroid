package com.subtitleedit.nativebridge

import java.io.File

internal data class MediaProbeResult(
    val startTimeSeconds: Double,
    val defaultAudioStreamIndex: Int?
)

internal interface NativeMediaEngine {
    fun probe(file: File, inspectVideoAudioTrack: Boolean): MediaProbeResult

    fun convertToWav(inputFile: File, outputFile: File): Boolean

    fun convertToPcm(inputFile: File, outputFile: File): Boolean

    fun cancel() {}
}
