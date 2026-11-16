package com.subtitleedit.util

import java.io.File

internal data class SettingsCacheSizes(
    val waveform: Long,
    val spectrogram: Long,
    val quickTranscribe: Long
)

/** One worker-side traversal supplies every cache group, reading each matching file size once. */
internal fun readSettingsCacheSizes(cacheDirectory: File, checkCancelled: () -> Unit = {}): SettingsCacheSizes {
    var waveform = 0L
    var spectrogram = 0L
    var quickTranscribe = 0L
    val waveformPrefix = File(cacheDirectory, "waveform").absolutePath + File.separator
    cacheDirectory.walkTopDown().forEach { file ->
        checkCancelled()
        if (!file.isFile) return@forEach
        val inWaveform = file.absolutePath.startsWith(waveformPrefix)
        val isWaveform = inWaveform && file.extension == "wave"
        val isSpectrogram = inWaveform && file.extension == "png" && file.name.contains(".spec_")
        val isQuickTranscribe = file.name.startsWith("quick_transcribe_") && file.name.endsWith("_16k.wav")
        if (isWaveform || isSpectrogram || isQuickTranscribe) {
            val bytes = file.length()
            if (isWaveform) waveform += bytes
            if (isSpectrogram) spectrogram += bytes
            if (isQuickTranscribe) quickTranscribe += bytes
        }
    }
    return SettingsCacheSizes(waveform, spectrogram, quickTranscribe)
}
