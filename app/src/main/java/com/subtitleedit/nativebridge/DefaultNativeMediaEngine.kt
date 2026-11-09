package com.subtitleedit.nativebridge

import com.arthenica.ffmpegkit.FFmpegKit
import com.subtitleedit.util.FfprobeMediaInformation
import java.io.File
import org.json.JSONObject

internal class DefaultNativeMediaEngine(private val cacheDir: File) : NativeMediaEngine {
    override fun probe(file: File): MediaProbeResult {
        val mediaInformation = requireNotNull(FfprobeMediaInformation.read(file.absolutePath, cacheDir).getAllProperties())
        val audioStream = selectDefaultAudioStream(mediaInformation)
        return MediaProbeResult(
            startTimeSeconds = mediaInformation.optJSONObject("format")
                ?.optString("start_time")?.toDoubleOrNull(),
            // MP3 duration may itself be estimated from this bitrate. The repository measures
            // real frame bytes and sample counts independently before comparing the rates.
            audioBitrateBitsPerSecond = audioStream?.optString("bit_rate")?.toDoubleOrNull()
        )
    }

    override fun openOperation(): NativeMediaOperation = Operation()

    private class Operation : NativeMediaOperation {
        private val commandOperation = NativeCommandOperation { arguments, complete ->
            val session = FFmpegKit.executeWithArgumentsAsync(arguments) { completed ->
                complete(completed.getReturnCode()?.isValueSuccess() == true)
            }
            NativeCommandSession { session.cancel() }
        }

        override suspend fun convertToWav(inputFile: File, outputFile: File): Boolean {
            return commandOperation.execute(arrayOf(
                "-y", "-i", inputFile.absolutePath, "-c:a", "pcm_s16le",
                "-ar", "44100", "-ac", "2", outputFile.absolutePath
            )) && outputFile.length() > 44L
        }

        override suspend fun convertToPcm(
            inputFile: File,
            outputFile: File,
            format: PcmFormat
        ): Boolean {
            val options = when (format) {
                PcmFormat.SPEECH_WAV_16K_MONO -> arrayOf(
                    "-vn", "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", "-f", "wav"
                )
                PcmFormat.DEMIX_FLOAT_44K_STEREO -> arrayOf(
                    "-vn", "-ar", "44100", "-ac", "2", "-f", "f32le", "-c:a", "pcm_f32le"
                )
            }
            return commandOperation.execute(
                arrayOf("-y", "-i", inputFile.absolutePath) + options + outputFile.absolutePath
            ) && outputFile.isFile && outputFile.length() >
                if (format == PcmFormat.SPEECH_WAV_16K_MONO) 44L else 0L
        }

        override fun cancel() = commandOperation.cancel()
    }

    private fun selectDefaultAudioStream(mediaInformation: JSONObject): JSONObject? {
        val streams = mediaInformation.optJSONArray("streams") ?: return null
        var firstAudio: JSONObject? = null
        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index) ?: continue
            if (!stream.optString("codec_type").equals("audio", ignoreCase = true)) continue
            if (firstAudio == null) firstAudio = stream
            if (stream.optJSONObject("disposition")?.optInt("default", 0) == 1) return stream
        }
        return firstAudio
    }
}
