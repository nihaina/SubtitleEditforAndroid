package com.subtitleedit.nativebridge

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import java.io.File
import org.json.JSONObject

internal class DefaultNativeMediaEngine : NativeMediaEngine {
    override fun probe(file: File, inspectVideoAudioTrack: Boolean): MediaProbeResult {
        val mediaInformation = probeJson(file)
        val audioStream = selectDefaultAudioStream(mediaInformation)
        val audioStreamIndex = if (inspectVideoAudioTrack) {
            audioStream?.takeIf { it.has("index") }?.optInt("index")
                ?: if (hasAudioTrack(file)) null
                else throw IllegalStateException("视频没有可用音轨")
        } else {
            null
        }
        return MediaProbeResult(
            startTimeSeconds = mediaInformation.optJSONObject("format")
                ?.optString("start_time")?.toDoubleOrNull(),
            defaultAudioStreamIndex = audioStreamIndex,
            // MP3 duration may itself be estimated from this bitrate. The repository measures
            // real frame bytes and sample counts independently before comparing the rates.
            audioBitrateBitsPerSecond = audioStream?.optString("bit_rate")?.toDoubleOrNull()
        )
    }

    /** Runs a fresh FFprobe process and parses its output directly, bypassing MediaInformationJsonParser. */
    private fun probeJson(file: File): JSONObject {
        val session = FFprobeKit.executeWithArguments(arrayOf(
            "-v", "error", "-hide_banner", "-print_format", "json",
            "-show_format", "-show_streams", "-show_chapters", "-i", file.absolutePath
        ))
        val output = session.getAllLogsAsString().trim()
        if (output.isEmpty()) throw IllegalStateException("FFprobe 未返回媒体信息")
        return try {
            val start = output.indexOf('{')
            val end = output.lastIndexOf('}')
            if (start < 0 || end <= start) throw IllegalArgumentException("JSON 对象为空")
            JSONObject(output.substring(start, end + 1))
        } catch (error: Exception) {
            throw IllegalStateException("FFprobe 返回的信息无效", error)
        }
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

    private fun hasAudioTrack(mediaFile: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(mediaFile.absolutePath)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
        } catch (error: Exception) {
            Log.w(TAG, "无法通过 MediaExtractor 检测视频音轨", error)
            false
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "DefaultNativeMediaEngine"
    }
}
