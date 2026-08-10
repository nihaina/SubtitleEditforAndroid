package com.subtitleedit.editor

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PreparedAudioFile(
    val playbackFile: File,
    val wasFixed: Boolean,
    val audioStreamIndex: Int? = null
)

internal class EditorAudioFilePreparer(
    private val cacheDir: File
) {
    private var temporaryPlaybackFile: File? = null

    suspend fun prepare(
        audioFile: File,
        inspectVideoAudioTrack: Boolean = false
    ): PreparedAudioFile = withContext(Dispatchers.IO) {
        val mediaInformation = FFprobeKit.getMediaInformation(audioFile.absolutePath)
            .getMediaInformation()
        val audioStreamIndex = if (inspectVideoAudioTrack) {
            selectDefaultAudioStreamIndex(mediaInformation)
                ?: if (hasAudioTrack(audioFile)) null
                else throw IllegalStateException("视频没有可用音轨")
        } else {
            null
        }

        if (inspectVideoAudioTrack) {
            return@withContext PreparedAudioFile(
                playbackFile = audioFile,
                wasFixed = false,
                audioStreamIndex = audioStreamIndex
            )
        }

        val startTime = mediaInformation?.getStartTime()?.toDoubleOrNull() ?: 0.0
        if (startTime <= 0.001) {
            return@withContext PreparedAudioFile(audioFile, wasFixed = false)
        }

        Log.w(TAG, "音频 start time 不为 0：$startTime，开始转换为 WAV")
        val wavFile = try {
            createTemporaryWav("audio_fixed_")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "创建临时 WAV 文件失败，使用原文件", e)
            return@withContext PreparedAudioFile(audioFile, wasFixed = false)
        }

        try {
            val command = "-y -i \"${audioFile.absolutePath}\" " +
                "-c:a pcm_s16le -ar 44100 -ac 2 \"${wavFile.absolutePath}\""
            executeWavConversion(command, wavFile)
            replaceTemporaryPlaybackFile(wavFile)
            Log.d(TAG, "WAV 转换成功：${wavFile.absolutePath}")
            PreparedAudioFile(wavFile, wasFixed = true)
        } catch (e: CancellationException) {
            wavFile.delete()
            throw e
        } catch (e: Exception) {
            wavFile.delete()
            Log.e(TAG, "WAV 转换异常，使用原文件", e)
            PreparedAudioFile(audioFile, wasFixed = false)
        }
    }

    fun release() {
        temporaryPlaybackFile?.let { file ->
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "无法删除临时播放 WAV：${file.absolutePath}")
            }
        }
        temporaryPlaybackFile = null
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

    private fun createTemporaryWav(prefix: String): File {
        cacheDir.mkdirs()
        return File.createTempFile(prefix, ".wav", cacheDir)
    }

    private fun executeWavConversion(command: String, outputFile: File) {
        val ffmpegSession = FFmpegKit.execute(command)
        if (ffmpegSession.getReturnCode()?.isValueSuccess() != true || outputFile.length() <= 44L) {
            throw IllegalStateException("FFmpeg 返回 ${ffmpegSession.getReturnCode()}")
        }
    }

    private fun replaceTemporaryPlaybackFile(file: File) {
        temporaryPlaybackFile?.takeIf { it != file }?.let { previous ->
            if (previous.exists() && !previous.delete()) {
                Log.w(TAG, "无法删除旧临时播放 WAV：${previous.absolutePath}")
            }
        }
        temporaryPlaybackFile = file
    }

    private companion object {
        const val TAG = "EditorActivity"
    }
}
