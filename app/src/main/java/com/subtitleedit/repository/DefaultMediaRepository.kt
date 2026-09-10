package com.subtitleedit.repository

import android.util.Log
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
        val mediaInformation = nativeMediaEngine.probe(audioFile, inspectVideoAudioTrack)
        val audioStreamIndex = mediaInformation.defaultAudioStreamIndex

        if (inspectVideoAudioTrack) {
            return@withContext PreparedAudioFile(
                playbackFile = audioFile,
                wasFixed = false,
                audioStreamIndex = audioStreamIndex
            )
        }

        val startTime = mediaInformation.startTimeSeconds
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
