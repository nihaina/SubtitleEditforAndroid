package com.subtitleedit.util

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Reads 16-bit PCM WAV files without loading the whole audio into memory.
 */
class Pcm16WavReader(file: File) : Closeable {

    private val raf = RandomAccessFile(file, "r")
    val sampleRate: Int
    val channels: Int
    val totalSamples: Long

    private val dataOffset: Long
    private val dataSize: Long
    private val blockAlign: Int

    init {
        val header = parseHeader()
        sampleRate = header.sampleRate
        channels = header.channels
        dataOffset = header.dataOffset
        dataSize = header.dataSize
        blockAlign = channels * BYTES_PER_SAMPLE
        totalSamples = dataSize / blockAlign
    }

    fun forEachChunk(chunkSamples: Int = DEFAULT_CHUNK_SAMPLES, onChunk: (FloatArray, Long) -> Unit) {
        require(chunkSamples > 0) { "chunkSamples must be greater than 0" }

        var startSample = 0L
        val buffer = ByteArray(chunkSamples * blockAlign)
        raf.seek(dataOffset)

        while (startSample < totalSamples) {
            val samplesToRead = min(chunkSamples.toLong(), totalSamples - startSample).toInt()
            val bytesToRead = samplesToRead * blockAlign
            raf.readFully(buffer, 0, bytesToRead)
            onChunk(convertBytesToFloats(buffer, bytesToRead), startSample)
            startSample += samplesToRead
        }
    }

    fun readRange(startSample: Long, sampleCount: Int): FloatArray {
        if (sampleCount <= 0 || startSample >= totalSamples) return FloatArray(0)

        val safeStart = startSample.coerceAtLeast(0L)
        val safeCount = min(sampleCount.toLong(), totalSamples - safeStart).toInt()
        if (safeCount <= 0) return FloatArray(0)

        val bytesToRead = safeCount * blockAlign
        val buffer = ByteArray(bytesToRead)
        raf.seek(dataOffset + safeStart * blockAlign)
        raf.readFully(buffer)
        return convertBytesToFloats(buffer, bytesToRead)
    }

    override fun close() {
        raf.close()
    }

    private fun parseHeader(): Header {
        raf.seek(0)
        if (raf.readAscii(4) != "RIFF") {
            throw IOException("不是有效的 RIFF WAV 文件")
        }
        raf.readUInt32Le()
        if (raf.readAscii(4) != "WAVE") {
            throw IOException("不是有效的 WAVE 文件")
        }

        var foundFormat = false
        var formatCode = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1L
        var dataSize = 0L

        while (raf.filePointer + 8 <= raf.length()) {
            val chunkId = raf.readAscii(4)
            val chunkSize = raf.readUInt32Le()
            val chunkDataStart = raf.filePointer

            when (chunkId) {
                "fmt " -> {
                    formatCode = raf.readUInt16Le()
                    channels = raf.readUInt16Le()
                    sampleRate = raf.readUInt32Le().toInt()
                    raf.readUInt32Le()
                    raf.readUInt16Le()
                    bitsPerSample = raf.readUInt16Le()
                    foundFormat = true
                }
                "data" -> {
                    dataOffset = chunkDataStart
                    dataSize = chunkSize
                }
            }

            val nextChunk = chunkDataStart + chunkSize + (chunkSize and 1L)
            raf.seek(nextChunk)

            if (foundFormat && dataOffset >= 0) break
        }

        if (!foundFormat || dataOffset < 0) {
            throw IOException("WAV 文件缺少 fmt 或 data 块")
        }
        if (formatCode != PCM_FORMAT || bitsPerSample != 16) {
            throw IOException("仅支持 16-bit PCM WAV，当前 format=$formatCode bits=$bitsPerSample")
        }
        if (channels <= 0 || sampleRate <= 0) {
            throw IOException("WAV 格式信息无效")
        }

        return Header(sampleRate, channels, dataOffset, dataSize)
    }

    private fun convertBytesToFloats(buffer: ByteArray, byteCount: Int): FloatArray {
        val sampleCount = byteCount / blockAlign
        val samples = FloatArray(sampleCount)

        var byteIndex = 0
        for (i in 0 until sampleCount) {
            var mixed = 0f
            for (channel in 0 until channels) {
                val low = buffer[byteIndex].toInt() and 0xFF
                val high = buffer[byteIndex + 1].toInt()
                val sample = (high shl 8) or low
                mixed += sample / 32768.0f
                byteIndex += BYTES_PER_SAMPLE
            }
            samples[i] = mixed / channels
        }

        return samples
    }

    private fun RandomAccessFile.readAscii(length: Int): String {
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readUInt16Le(): Int {
        val b0 = read()
        val b1 = read()
        if (b0 < 0 || b1 < 0) throw IOException("Unexpected EOF")
        return (b1 shl 8) or b0
    }

    private fun RandomAccessFile.readUInt32Le(): Long {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw IOException("Unexpected EOF")
        return ((b3.toLong() and 0xFF) shl 24) or
            ((b2.toLong() and 0xFF) shl 16) or
            ((b1.toLong() and 0xFF) shl 8) or
            (b0.toLong() and 0xFF)
    }

    private data class Header(
        val sampleRate: Int,
        val channels: Int,
        val dataOffset: Long,
        val dataSize: Long
    )

    companion object {
        private const val PCM_FORMAT = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val DEFAULT_CHUNK_SAMPLES = 8192
    }
}
