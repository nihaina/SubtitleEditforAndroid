package com.subtitleedit.repository

import android.util.Log
import com.subtitleedit.audio.Mp3FileIssues
import com.subtitleedit.audio.Mp3FrameStats
import com.subtitleedit.nativebridge.DefaultNativeMediaEngine
import com.subtitleedit.nativebridge.NativeMediaEngine
import com.subtitleedit.util.FileHashUtils
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DefaultMediaRepository(
    private val cacheDir: File,
    private val nativeMediaEngine: NativeMediaEngine = DefaultNativeMediaEngine()
) : MediaRepository {
    private var temporaryPlaybackFile: File? = null

    override suspend fun inspectMp3(audioFile: File): Mp3FileIssues = withContext(Dispatchers.IO) {
        logDebug("MP3 规范检测开始：${audioFile.absolutePath}")
        val issues = inspectMp3File(audioFile)
        logDebug("MP3 规范检测完成：${audioFile.absolutePath}, hasIssues=${issues.hasIssues}")
        issues
    }

    override suspend fun prepareAudio(
        audioFile: File,
        inspectVideoAudioTrack: Boolean,
        forceMp3Preparation: Boolean
    ): PreparedAudioFile = withContext(Dispatchers.IO) {
        if (inspectVideoAudioTrack) {
            val mediaInformation = nativeMediaEngine.probe(audioFile, inspectVideoAudioTrack = true)
            return@withContext PreparedAudioFile(
                playbackFile = audioFile,
                wasFixed = false,
                audioStreamIndex = mediaInformation.defaultAudioStreamIndex
            )
        }

        val isMp3 = forceMp3Preparation || audioFile.extension.equals("mp3", ignoreCase = true)
        if (!isMp3) {
            return@withContext PreparedAudioFile(audioFile, wasFixed = false)
        }

        // MP3 即使从 0 开始，也可能因码率估算导致 MediaPlayer 跳转偏移。
        Log.d(TAG, "MP3 音频使用临时 WAV 播放：${audioFile.name}")
        val wavFile = try {
            createTemporaryWav("audio_fixed_")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "创建临时 WAV 文件失败，使用原文件", e)
            return@withContext PreparedAudioFile(audioFile, wasFixed = false)
        }

        val operation = nativeMediaEngine.openOperation()
        try {
            if (!operation.convertToWav(audioFile, wavFile)) {
                throw IllegalStateException("Native WAV 转换失败")
            }
            replaceTemporaryPlaybackFile(wavFile)
            Log.d(TAG, "WAV 转换成功：${wavFile.absolutePath}")
            PreparedAudioFile(wavFile, wasFixed = true)
        } catch (e: CancellationException) {
            operation.cancel()
            wavFile.delete()
            throw e
        } catch (e: Exception) {
            operation.cancel()
            wavFile.delete()
            Log.e(TAG, "WAV 转换异常，使用原文件", e)
            PreparedAudioFile(audioFile, wasFixed = false)
        } finally {
            operation.cancel()
        }
    }

    private fun inspectMp3File(file: File): Mp3FileIssues {
        var probe: com.subtitleedit.nativebridge.MediaProbeResult? = null
        var lastProbeError: Throwable? = null
        repeat(MP3_PROBE_ATTEMPTS) { attempt ->
            try {
                val candidate = nativeMediaEngine.probe(file, inspectVideoAudioTrack = false)
                // Preserve issue evidence if a later FFprobe invocation returns a weaker result.
                if (probe == null || candidate.startTimeSeconds != null && candidate.startTimeSeconds != 0.0 ||
                    probe?.audioBitrateBitsPerSecond == null && candidate.audioBitrateBitsPerSecond != null
                ) {
                    probe = candidate
                }
                logDebug(
                    "MP3 规范检测 probe 第 ${attempt + 1}/$MP3_PROBE_ATTEMPTS 次：" +
                        "start=${candidate.startTimeSeconds}, bitrate=${candidate.audioBitrateBitsPerSecond}"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastProbeError = error
                logWarn("MP3 规范检测 probe 第 ${attempt + 1} 次失败", error)
            }
        }
        var audioData: Mp3FrameStats.AudioData? = null
        repeat(MP3_FRAME_ATTEMPTS) { attempt ->
            if (audioData != null) return@repeat
            try {
                audioData = Mp3FrameStats.read(file)
                logDebug(
                    "MP3 规范检测帧统计第 ${attempt + 1}/$MP3_FRAME_ATTEMPTS 次：" +
                        "bytes=${audioData?.byteCount}, duration=${audioData?.durationSeconds}"
                )
            } catch (error: Exception) {
                logWarn("MP3 规范检测帧统计第 ${attempt + 1} 次失败", error)
            }
        }
        return Mp3FileIssues.from(
            startTimeSeconds = probe?.startTimeSeconds,
            audioSizeBytes = audioData?.byteCount,
            durationSeconds = audioData?.durationSeconds,
            nominalBitrateBitsPerSecond = probe?.audioBitrateBitsPerSecond
        ).copy(
            inspectionIncomplete = probe == null || audioData == null
        ).also {
            if (it.inspectionIncomplete) {
                logWarn("MP3 规范检测未获得完整结果", lastProbeError)
            }
        }
    }

    override suspend fun getCacheKey(file: File): String = withContext(Dispatchers.IO) {
        FileHashUtils.md5(file)
    }

    override fun release() {
        temporaryPlaybackFile?.let { file ->
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "无法删除临时播放 WAV：${file.absolutePath}")
            }
        }
        temporaryPlaybackFile = null
    }

    private fun createTemporaryWav(prefix: String): File {
        cacheDir.mkdirs()
        return File.createTempFile(prefix, ".wav", cacheDir)
    }

    private fun replaceTemporaryPlaybackFile(file: File) {
        temporaryPlaybackFile?.takeIf { it != file }?.let { previous ->
            if (previous.exists() && !previous.delete()) {
                Log.w(TAG, "无法删除旧临时播放 WAV：${previous.absolutePath}")
            }
        }
        temporaryPlaybackFile = file
    }

    private fun logDebug(message: String) {
        // Android's Log is unavailable in the JVM test runtime; inspection must remain testable.
        runCatching { Log.d(TAG, message) }
    }

    private fun logWarn(message: String, error: Throwable? = null) {
        runCatching {
            if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
        }
    }

    private companion object {
        const val TAG = "DefaultMediaRepository"
        const val MP3_PROBE_ATTEMPTS = 2
        const val MP3_FRAME_ATTEMPTS = 2
    }
}
