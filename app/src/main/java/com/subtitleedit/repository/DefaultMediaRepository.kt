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

    override suspend fun prepareAudio(
        audioFile: File,
        inspectVideoAudioTrack: Boolean
    ): PreparedAudioFile = withContext(Dispatchers.IO) {
        if (inspectVideoAudioTrack) {
            val mediaInformation = nativeMediaEngine.probe(audioFile, inspectVideoAudioTrack = true)
            return@withContext PreparedAudioFile(
                playbackFile = audioFile,
                wasFixed = false,
                audioStreamIndex = mediaInformation.defaultAudioStreamIndex
            )
        }

        if (!audioFile.extension.equals("mp3", ignoreCase = true)) {
            return@withContext PreparedAudioFile(audioFile, wasFixed = false)
        }

        val issues = inspectMp3(audioFile)
        // MP3 即使从 0 开始，也可能因码率估算导致 MediaPlayer 跳转偏移。
        Log.d(TAG, "MP3 音频使用临时 WAV 播放：${audioFile.name}")
        val wavFile = try {
            createTemporaryWav("audio_fixed_")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "创建临时 WAV 文件失败，使用原文件", e)
            return@withContext PreparedAudioFile(audioFile, wasFixed = false, mp3Issues = issues)
        }

        val operation = nativeMediaEngine.openOperation()
        try {
            if (!operation.convertToWav(audioFile, wavFile)) {
                throw IllegalStateException("Native WAV 转换失败")
            }
            replaceTemporaryPlaybackFile(wavFile)
            Log.d(TAG, "WAV 转换成功：${wavFile.absolutePath}")
            PreparedAudioFile(wavFile, wasFixed = true, mp3Issues = issues)
        } catch (e: CancellationException) {
            operation.cancel()
            wavFile.delete()
            throw e
        } catch (e: Exception) {
            operation.cancel()
            wavFile.delete()
            Log.e(TAG, "WAV 转换异常，使用原文件", e)
            PreparedAudioFile(audioFile, wasFixed = false, mp3Issues = issues)
        } finally {
            operation.cancel()
        }
    }

    private fun inspectMp3(file: File): Mp3FileIssues {
        val probe = try {
            nativeMediaEngine.probe(file, inspectVideoAudioTrack = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "无法检测 MP3 起始时间和码率", error)
            null
        }
        val audioData = try {
            Mp3FrameStats.read(file)
        } catch (error: Exception) {
            Log.w(TAG, "无法统计 MP3 音频帧", error)
            null
        }
        return Mp3FileIssues.from(
            startTimeSeconds = probe?.startTimeSeconds,
            audioSizeBytes = audioData?.byteCount,
            durationSeconds = audioData?.durationSeconds,
            nominalBitrateBitsPerSecond = probe?.audioBitrateBitsPerSecond
        )
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

    private companion object {
        const val TAG = "DefaultMediaRepository"
    }
}
