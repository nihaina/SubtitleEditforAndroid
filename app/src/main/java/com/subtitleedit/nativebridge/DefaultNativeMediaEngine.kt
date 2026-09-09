package com.subtitleedit.nativebridge

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import java.io.File

internal class DefaultNativeMediaEngine : NativeMediaEngine {
    override fun probe(file: File, inspectVideoAudioTrack: Boolean): MediaProbeResult {
        val mediaInformation = FFprobeKit.getMediaInformation(file.absolutePath)
            .getMediaInformation()
        val audioStreamIndex = if (inspectVideoAudioTrack) {
            selectDefaultAudioStreamIndex(mediaInformation)
                ?: if (hasAudioTrack(file)) null
                else throw IllegalStateException("视频没有可用音轨")
        } else {
            null
        }
        return MediaProbeResult(
            startTimeSeconds = mediaInformation?.getStartTime()?.toDoubleOrNull() ?: 0.0,
            defaultAudioStreamIndex = audioStreamIndex
        )
    }

    override fun convertToWav(inputFile: File, outputFile: File): Boolean {
        val command = "-y -i \"${inputFile.absolutePath}\" " +
            "-c:a pcm_s16le -ar 44100 -ac 2 \"${outputFile.absolutePath}\""
        val session = FFmpegKit.execute(command)
        return session.getReturnCode()?.isValueSuccess() == true && outputFile.length() > 44L
    }

    private fun selectDefaultAudioStreamIndex(
        mediaInformation: com.arthenica.ffmpegkit.MediaInformation?
    ): Int? {
        val audioStreams = mediaInformation?.getStreams()
            ?.filter { stream ->
                stream.getType().equals("audio", ignoreCase = true) ||
                    stream.getAllProperties()
                        ?.optString("codec_type")
                        .equals("audio", ignoreCase = true)
            }
            .orEmpty()
        val selectedStream = audioStreams.firstOrNull { stream ->
            stream.getAllProperties()
                ?.optJSONObject("disposition")
                ?.optInt("default", 0) == 1
        } ?: audioStreams.firstOrNull()
        return selectedStream?.getIndex()?.toInt()
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
