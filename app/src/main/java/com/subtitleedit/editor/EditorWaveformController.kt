package com.subtitleedit.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.subtitleedit.audio.FfmpegWaveformChunkLoader
import com.subtitleedit.databinding.ActivityEditorBinding
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.FileHashUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.view.WaveformTimelineView
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal class EditorWaveformController(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val scope: CoroutineScope,
    private val hasPlayableMedia: Boolean,
    private val appCacheDir: File,
    private val currentPlaybackPositionMs: () -> Long,
    private val onSubtitleChanged: (Int, SubtitleEntry) -> Unit,
    private val onSelectedIndexChanged: (Int) -> Unit,
    private val onTimestampInserted: (Long, Long) -> Unit,
    private val showMessage: (String) -> Unit
) {
    private var chunkLoader: FfmpegWaveformChunkLoader? = null
    private var cacheIndexJob: Job? = null
    private var cacheIndexGeneration = 0L
    private var audioFile: File? = null
    private var audioCacheKey: String? = null
    private var durationMs = 0L
    private var audioStreamIndex: Int? = null
    private var hasAudioTrack = true
    private var isWaveformExpanded = true
    private var currentDisplayMode = WaveformTimelineView.DisplayMode.WAVEFORM
    private var spectrogramTotalChunks = 0
    private var spectrogramDoneChunks = 0
    private var spectrogramIsGenerating = false
    private var isWaveformGenerated = false
    private var isSpectrogramGenerationStarted = false
    private var isWaveformGenerating = false
    private var isPreparingCacheIndex = false
    private var cacheIndexFailure: String? = null
    private val spectrogramStateLock = Any()
    private val spectrogramGenerationSemaphore = Semaphore(MAX_CONCURRENT_SPECTROGRAM_GENERATIONS)
    private val spectrogramJobs = mutableMapOf<SpectrogramChunkKey, Job>()
    private val spectrogramReadyChunks = mutableSetOf<Int>()
    private var spectrogramGenerationVersion = 0L
    private var spectrogramCacheDimensions: Pair<Int, Int>? = null

    private data class SpectrogramChunkKey(
        val chunkIndex: Int,
        val width: Int,
        val height: Int
    )

    fun bind() {
        if (!hasPlayableMedia) {
            binding.mediaPlayerContainer.visibility = View.GONE
            return
        }

        binding.mediaPlayerContainer.visibility = View.VISIBLE
        binding.waveformTimelineView.onSubtitleChangeListener = onSubtitleChanged
        binding.waveformTimelineView.onSelectedIndicesChangeListener = { indices ->
            indices.firstOrNull()?.let(onSelectedIndexChanged)
        }

        binding.btnToggleWaveform.setOnClickListener {
            isWaveformExpanded = !isWaveformExpanded
            binding.timelineContainer.visibility =
                if (isWaveformExpanded) View.VISIBLE else View.GONE
            binding.btnToggleWaveform.text = if (isWaveformExpanded) "▼" else "▶"
            updateGenerateButton()
            refreshWaveformToolbarState()
        }

        binding.btnToggleDisplayMode.setOnClickListener {
            currentDisplayMode =
                if (currentDisplayMode == WaveformTimelineView.DisplayMode.WAVEFORM) {
                    WaveformTimelineView.DisplayMode.SPECTROGRAM
                } else {
                    WaveformTimelineView.DisplayMode.WAVEFORM
                }
            binding.waveformTimelineView.setDisplayMode(currentDisplayMode)

            if (currentDisplayMode == WaveformTimelineView.DisplayMode.SPECTROGRAM) {
                if (isSpectrogramGenerationStarted) {
                    spectrogramIsGenerating = spectrogramDoneChunks < spectrogramTotalChunks
                    binding.waveformTimelineView.refreshVisibleChunks()
                } else {
                    binding.waveformTimelineView.resetSpectrogramCache()
                    spectrogramTotalChunks = calcTotalChunks()
                    spectrogramDoneChunks = 0
                    spectrogramIsGenerating = false
                }
            }

            updateGenerateButton()
            refreshWaveformToolbarState()
        }

        binding.waveformTimelineView.onSpectrogramChunkRequest =
            { chunkIndex, startMs, endMs, widthPx, heightPx ->
                if (isSpectrogramGenerationStarted) {
                    generateSpectrogramChunkAsync(
                        chunkIndex,
                        startMs,
                        endMs,
                        widthPx,
                        heightPx
                    )
                }
            }

        binding.btnAmplitudeZoomIn.setOnClickListener {
            binding.waveformTimelineView.zoomInAmplitude()
        }
        binding.btnAmplitudeZoomIn.setOnLongClickListener {
            binding.waveformTimelineView.resetAmplitudeScale()
            showMessage("振幅已重置")
            true
        }
        binding.btnAmplitudeZoomOut.setOnClickListener {
            binding.waveformTimelineView.zoomOutAmplitude()
        }

        binding.btnGenerateCache.setOnClickListener {
            if (currentDisplayMode == WaveformTimelineView.DisplayMode.WAVEFORM) {
                startWaveformGeneration()
            } else {
                startSpectrogramGeneration()
            }
        }

        var timestampStartMs = 0L
        binding.btnInsertSubtitle.setOnLongClickListener {
            timestampStartMs = currentPlaybackPositionMs()
            binding.waveformTimelineView.startTimestamping(timestampStartMs)
            true
        }
        binding.btnInsertSubtitle.setOnTouchListener { _, event ->
            if ((event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) &&
                binding.waveformTimelineView.isInTimestampingMode()
            ) {
                val endMs = binding.waveformTimelineView.stopTimestamping()
                onTimestampInserted(timestampStartMs, endMs)
            }
            false
        }
    }

    fun load(
        audioFile: File,
        durationMs: Long,
        subtitles: List<SubtitleEntry>,
        audioStreamIndex: Int? = null
    ) {
        cancelSpectrogramJobs()
        spectrogramReadyChunks.clear()
        spectrogramCacheDimensions = null
        cacheIndexJob?.cancel()
        val cacheIndexRequest = ++cacheIndexGeneration
        this.audioFile = audioFile
        this.audioCacheKey = null
        this.durationMs = durationMs
        this.audioStreamIndex = audioStreamIndex
        hasAudioTrack = true
        isPreparingCacheIndex = true
        cacheIndexFailure = null
        isWaveformGenerated = false
        isSpectrogramGenerationStarted = false
        spectrogramIsGenerating = false
        chunkLoader?.release()
        chunkLoader = null
        binding.waveformTimelineView.initialize(durationMs, subtitles)
        updateGenerateButton()

        cacheIndexJob = scope.launch(Dispatchers.IO) {
            val cacheKey = runCatching { FileHashUtils.md5(audioFile) }
            withContext(Dispatchers.Main) {
                if (cacheIndexRequest != cacheIndexGeneration) return@withContext

                isPreparingCacheIndex = false
                cacheKey.onSuccess { key ->
                    audioCacheKey = key
                    initializeMediaCache(
                        audioFile,
                        durationMs,
                        audioStreamIndex,
                        key,
                        cacheIndexRequest
                    )
                }.onFailure { error ->
                    cacheIndexFailure = error.message ?: error.javaClass.simpleName
                    Log.e(TAG, "计算媒体缓存索引失败", error)
                    showMessage("无法读取媒体文件")
                    updateGenerateButton()
                }
            }
        }
    }

    fun showNoAudioTrack(durationMs: Long, subtitles: List<SubtitleEntry>) {
        cancelSpectrogramJobs()
        spectrogramReadyChunks.clear()
        spectrogramCacheDimensions = null
        cacheIndexGeneration++
        cacheIndexJob?.cancel()
        cacheIndexJob = null
        this.audioFile = null
        this.audioCacheKey = null
        this.durationMs = durationMs
        this.audioStreamIndex = null
        hasAudioTrack = false
        isPreparingCacheIndex = false
        cacheIndexFailure = null
        chunkLoader?.release()
        chunkLoader = null
        isWaveformGenerated = false
        isSpectrogramGenerationStarted = false
        binding.waveformTimelineView.initialize(durationMs, subtitles)
        binding.btnGenerateCache.visibility = View.VISIBLE
        binding.btnGenerateCache.isEnabled = false
        binding.btnGenerateCache.text = context.getString(com.subtitleedit.R.string.editor_video_no_audio)
    }

    fun setSubtitles(subtitles: List<SubtitleEntry>) {
        if (!hasPlayableMedia) return
        binding.waveformTimelineView.setSubtitles(subtitles)
    }

    fun setSubtitlesPreserveSelection(subtitles: List<SubtitleEntry>) {
        if (!hasPlayableMedia) return
        binding.waveformTimelineView.setSubtitlesPreserveSelection(subtitles)
    }

    fun setSubtitlesKeepSelection(subtitles: List<SubtitleEntry>, selectedIndex: Int) {
        if (!hasPlayableMedia) return
        binding.waveformTimelineView.setSubtitlesKeepSelection(subtitles, selectedIndex)
    }

    fun getAudioCacheKey(file: File): String? =
        audioCacheKey.takeIf { audioFile?.absolutePath == file.absolutePath }

    fun setSubtitlesAfterDelete(subtitles: List<SubtitleEntry>, deletedIndices: Set<Int>) {
        if (!hasPlayableMedia) return
        binding.waveformTimelineView.setSubtitlesAfterDelete(subtitles, deletedIndices)
    }

    fun release() {
        cancelSpectrogramJobs()
        cacheIndexGeneration++
        cacheIndexJob?.cancel()
        cacheIndexJob = null
        chunkLoader?.release()
        chunkLoader = null
        audioCacheKey = null
        isPreparingCacheIndex = false
        cacheIndexFailure = null
    }

    private fun initializeMediaCache(
        audioFile: File,
        durationMs: Long,
        audioStreamIndex: Int?,
        audioCacheKey: String,
        cacheIndexRequest: Long
    ) {
        if (cacheIndexRequest != cacheIndexGeneration) return

        restoreSpectrogramCacheState(audioFile)
        binding.waveformTimelineView.post {
            if (cacheIndexRequest != cacheIndexGeneration) return@post
            restoreSpectrogramCacheState(audioFile)
            if (isSpectrogramGenerationStarted &&
                currentDisplayMode == WaveformTimelineView.DisplayMode.SPECTROGRAM
            ) {
                binding.waveformTimelineView.resetSpectrogramCache()
            }
            updateGenerateButton()
        }

        val cacheDir = when (SettingsManager.getInstance(context).getWaveformCacheLocation()) {
            SettingsManager.WAVEFORM_CACHE_APP -> File(appCacheDir, "waveform")
            else -> null
        }

        chunkLoader = FfmpegWaveformChunkLoader(context, scope).also {
            it.prepare(audioFile.absolutePath, durationMs, cacheDir, audioStreamIndex, audioCacheKey)
        }

        if (chunkLoader?.isCacheReady() == true) {
            isWaveformGenerated = true
            connectWaveformLoader()
        } else {
            isWaveformGenerated = false
        }
        updateGenerateButton()
    }

    private fun calcTotalChunks(): Int {
        if (durationMs <= 0) return 0
        val chunkMs = WaveformTimelineView.CHUNK_DURATION_MS
        return ((durationMs + chunkMs - 1) / chunkMs).toInt()
    }

    private fun refreshWaveformToolbarState() {
        val isSpectrogram = currentDisplayMode == WaveformTimelineView.DisplayMode.SPECTROGRAM
        (binding.btnToggleDisplayMode as? TextView)?.text =
            if (isSpectrogram) "频谱" else "波形"

        val amplitudeEnabled = isWaveformExpanded && !isSpectrogram
        binding.btnAmplitudeZoomIn.isEnabled = amplitudeEnabled
        binding.btnAmplitudeZoomOut.isEnabled = amplitudeEnabled
        val color = if (amplitudeEnabled) "#CCCCCC" else "#555555"
        (binding.btnAmplitudeZoomIn as? TextView)?.setTextColor(Color.parseColor(color))
        (binding.btnAmplitudeZoomOut as? TextView)?.setTextColor(Color.parseColor(color))
    }

    private fun generateSpectrogramChunkAsync(
        chunkIndex: Int,
        startMs: Long,
        endMs: Long,
        widthPx: Int,
        heightPx: Int
    ) {
        val currentAudioFile = audioFile ?: return
        val requestDimensions = widthPx to heightPx
        if (spectrogramCacheDimensions != requestDimensions) {
            cancelSpectrogramJobs()
            spectrogramReadyChunks.clear()
            spectrogramDoneChunks = 0
            spectrogramCacheDimensions = requestDimensions
        }
        val cacheBaseDir = spectrogramCacheBaseDir(currentAudioFile) ?: return
        val currentAudioStreamIndex = audioStreamIndex
        val streamSuffix = currentAudioStreamIndex?.let { ".a$it" }.orEmpty()
        val specFile = File(
            cacheBaseDir,
            "${currentAudioFile.nameWithoutExtension}$streamSuffix.spec_${chunkIndex}_${widthPx}x${heightPx}.png"
        )
        val key = SpectrogramChunkKey(chunkIndex, widthPx, heightPx)

        synchronized(spectrogramStateLock) {
            if (spectrogramJobs.containsKey(key)) return
            val requestVersion = spectrogramGenerationVersion
            val job = scope.launch(
                context = Dispatchers.IO,
                start = CoroutineStart.LAZY
            ) {
                processSpectrogramChunk(
                    key = key,
                    requestVersion = requestVersion,
                    audioFile = currentAudioFile,
                    audioStreamIndex = currentAudioStreamIndex,
                    cacheFile = specFile,
                    startMs = startMs,
                    endMs = endMs
                )
            }
            spectrogramJobs[key] = job
            job.start()
        }
    }

    private suspend fun processSpectrogramChunk(
        key: SpectrogramChunkKey,
        requestVersion: Long,
        audioFile: File,
        audioStreamIndex: Int?,
        cacheFile: File,
        startMs: Long,
        endMs: Long
    ) {
        var bitmap: Bitmap? = null
        try {
            bitmap = spectrogramGenerationSemaphore.withPermit {
                decodeSpectrogramCache(cacheFile, key.width, key.height)
                    ?: generateSpectrogramCache(
                        audioFile = audioFile,
                        audioStreamIndex = audioStreamIndex,
                        cacheFile = cacheFile,
                        startMs = startMs,
                        endMs = endMs,
                        width = key.width,
                        height = key.height
                    )
            }
        } catch (error: CancellationException) {
            bitmap?.recycle()
            throw error
        } catch (error: Exception) {
            bitmap?.recycle()
            bitmap = null
            Log.e(TAG, "频谱图分块处理失败：chunk=${key.chunkIndex}", error)
        } finally {
            synchronized(spectrogramStateLock) {
                if (requestVersion == spectrogramGenerationVersion) {
                    spectrogramJobs.remove(key)
                }
            }
        }

        withContext(Dispatchers.Main) {
            if (requestVersion != spectrogramGenerationVersion ||
                this@EditorWaveformController.audioFile?.absolutePath != audioFile.absolutePath
            ) {
                bitmap?.recycle()
                return@withContext
            }

            if (bitmap == null) {
                binding.waveformTimelineView.markSpectrogramChunkFailed(key.chunkIndex)
                return@withContext
            }

            val accepted = binding.waveformTimelineView.updateSpectrogramChunk(key.chunkIndex, bitmap!!)
            if (!accepted) return@withContext
            if (spectrogramReadyChunks.add(key.chunkIndex)) {
                spectrogramDoneChunks = spectrogramReadyChunks.size
            }
            spectrogramIsGenerating = spectrogramDoneChunks < spectrogramTotalChunks
            if (!spectrogramIsGenerating && spectrogramTotalChunks > 0) {
                showMessage("频谱图缓存生成完成")
            }
        }
    }

    private suspend fun generateSpectrogramCache(
        audioFile: File,
        audioStreamIndex: Int?,
        cacheFile: File,
        startMs: Long,
        endMs: Long,
        width: Int,
        height: Int
    ): Bitmap? {
        val parent = cacheFile.parentFile ?: return null
        if (!parent.exists() && !parent.mkdirs()) {
            Log.e(TAG, "无法创建频谱缓存目录：${parent.absolutePath}")
            return null
        }
        val partFile = File(parent, ".spectrogram_part_${UUID.randomUUID()}.png")

        return try {
            val spectrumFilter = "showspectrumpic=s=${width}x${height}:" +
                "mode=combined:color=intensity:scale=log:legend=0"
            val arguments = mutableListOf(
                "-hide_banner",
                "-loglevel", "error",
                "-nostdin",
                "-y",
                "-ss", formatFfmpegSeconds(startMs),
                "-t", formatFfmpegSeconds(endMs - startMs),
                "-i", audioFile.absolutePath
            )
            if (audioStreamIndex != null) {
                arguments += listOf(
                    "-filter_complex", "[0:$audioStreamIndex]$spectrumFilter[spectrum]",
                    "-map", "[spectrum]"
                )
            } else {
                arguments += listOf("-lavfi", spectrumFilter)
            }
            arguments += listOf(
                "-frames:v", "1",
                "-f", "image2",
                "-c:v", "png",
                partFile.absolutePath
            )

            val session = executeFfmpeg(arguments.toTypedArray())
            if (session.getReturnCode()?.isValueSuccess() != true) {
                Log.e(TAG, "频谱图分块生成失败：${session.getOutput()}")
                return null
            }

            val bitmap = decodeSpectrogramCache(partFile, width, height) ?: return null
            try {
                publishSpectrogramCache(partFile, cacheFile)
                bitmap
            } catch (error: Exception) {
                bitmap.recycle()
                throw error
            }
        } finally {
            if (partFile.exists() && !partFile.delete()) {
                Log.w(TAG, "无法删除频谱临时文件：${partFile.absolutePath}")
            }
        }
    }

    private suspend fun executeFfmpeg(arguments: Array<String>): FFmpegSession {
        return suspendCancellableCoroutine { continuation ->
            val sessionRef = AtomicReference<FFmpegSession?>()
            continuation.invokeOnCancellation {
                sessionRef.get()?.cancel()
            }
            val session = FFmpegKit.executeWithArgumentsAsync(arguments) { completedSession ->
                if (continuation.isActive) {
                    continuation.resume(completedSession)
                }
            }
            sessionRef.set(session)
            if (!continuation.isActive) {
                session.cancel()
            }
        }
    }

    private fun decodeSpectrogramCache(file: File, width: Int, height: Int): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth != width || bounds.outHeight != height) {
            deleteInvalidSpectrogramCache(file)
            return null
        }

        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap?.recycle()
            deleteInvalidSpectrogramCache(file)
            return null
        }
        return bitmap
    }

    private fun isSpectrogramCacheValid(file: File, width: Int, height: Int): Boolean {
        return readPngDimensions(file) == (width to height)
    }

    private fun readPngDimensions(file: File): Pair<Int, Int>? {
        if (!file.isFile || file.length() < 45L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(24)
                input.readFully(header)
                val signature = byteArrayOf(
                    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
                )
                if (!header.copyOfRange(0, 8).contentEquals(signature) ||
                    header[12] != 'I'.code.toByte() ||
                    header[13] != 'H'.code.toByte() ||
                    header[14] != 'D'.code.toByte() ||
                    header[15] != 'R'.code.toByte()
                ) {
                    return@use null
                }

                input.seek(input.length() - 12L)
                val trailer = ByteArray(12)
                input.readFully(trailer)
                val iend = byteArrayOf(
                    0, 0, 0, 0,
                    'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(),
                    0xae.toByte(), 0x42, 0x60, 0x82.toByte()
                )
                if (!trailer.contentEquals(iend)) return@use null

                val width =
                    ((header[16].toInt() and 0xff) shl 24) or
                        ((header[17].toInt() and 0xff) shl 16) or
                        ((header[18].toInt() and 0xff) shl 8) or
                        (header[19].toInt() and 0xff)
                val height =
                    ((header[20].toInt() and 0xff) shl 24) or
                        ((header[21].toInt() and 0xff) shl 16) or
                        ((header[22].toInt() and 0xff) shl 8) or
                        (header[23].toInt() and 0xff)
                if (width > 0 && height > 0) width to height else null
            }
        }.getOrNull()
    }

    private fun deleteInvalidSpectrogramCache(file: File) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "无法删除损坏的频谱缓存：${file.absolutePath}")
        }
    }

    private fun publishSpectrogramCache(partFile: File, cacheFile: File) {
        try {
            Files.move(
                partFile.toPath(),
                cacheFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                partFile.toPath(),
                cacheFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun formatFfmpegSeconds(milliseconds: Long): String {
        val seconds = milliseconds / 1000L
        val remainder = milliseconds % 1000L
        return "$seconds.${remainder.toString().padStart(3, '0')}"
    }

    private fun restoreSpectrogramCacheState(audioFile: File) {
        spectrogramTotalChunks = calcTotalChunks()
        val dimensions = binding.waveformTimelineView.getSpectrogramCacheDimensions() ?: return
        val (width, height) = dimensions
        spectrogramCacheDimensions = dimensions
        val cacheBaseDir = spectrogramCacheBaseDir(audioFile) ?: return
        val streamSuffix = audioStreamIndex?.let { ".a$it" }.orEmpty()
        val prefix = "${audioFile.nameWithoutExtension}$streamSuffix.spec_"

        spectrogramReadyChunks.clear()
        cleanupStaleSpectrogramParts(cacheBaseDir)
        for (chunkIndex in 0 until spectrogramTotalChunks) {
            val file = File(cacheBaseDir, "${prefix}${chunkIndex}_${width}x${height}.png")
            if (isSpectrogramCacheValid(file, width, height)) {
                spectrogramReadyChunks.add(chunkIndex)
            } else if (file.exists()) {
                deleteInvalidSpectrogramCache(file)
            }
        }
        spectrogramDoneChunks = spectrogramReadyChunks.size
        isSpectrogramGenerationStarted = spectrogramDoneChunks > 0
        spectrogramIsGenerating =
            isSpectrogramGenerationStarted && spectrogramDoneChunks < spectrogramTotalChunks
    }

    private fun cleanupStaleSpectrogramParts(cacheDir: File) {
        val cutoff = System.currentTimeMillis() - STALE_SPECTROGRAM_PART_MAX_AGE_MS
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile &&
                file.name.startsWith(".spectrogram_part_") &&
                file.lastModified() < cutoff &&
                !file.delete()
            ) {
                Log.w(TAG, "无法删除过期频谱临时文件：${file.absolutePath}")
            }
        }
    }

    private fun spectrogramCacheBaseDir(audioFile: File): File? {
        val cacheKey = audioCacheKey.takeIf { this.audioFile?.absolutePath == audioFile.absolutePath }
            ?: return null
        val cacheRootDir = when (SettingsManager.getInstance(context).getWaveformCacheLocation()) {
            SettingsManager.WAVEFORM_CACHE_APP -> File(appCacheDir, "waveform")
            else -> audioFile.parentFile ?: File(appCacheDir, "waveform")
        }.apply { mkdirs() }
        return File(cacheRootDir, cacheKey).apply { mkdirs() }
    }

    private fun updateGenerateButton() {
        if (!hasAudioTrack) {
            binding.btnGenerateCache.visibility = if (isWaveformExpanded) View.VISIBLE else View.GONE
            binding.btnGenerateCache.isEnabled = false
            binding.btnGenerateCache.text = context.getString(com.subtitleedit.R.string.editor_video_no_audio)
            return
        }
        if (isPreparingCacheIndex) {
            binding.btnGenerateCache.visibility = if (isWaveformExpanded) View.VISIBLE else View.GONE
            binding.btnGenerateCache.isEnabled = false
            binding.btnGenerateCache.text = "正在准备缓存..."
            return
        }
        if (cacheIndexFailure != null) {
            binding.btnGenerateCache.visibility = if (isWaveformExpanded) View.VISIBLE else View.GONE
            binding.btnGenerateCache.isEnabled = false
            binding.btnGenerateCache.text = "缓存准备失败"
            return
        }
        val needsGenerate = when (currentDisplayMode) {
            WaveformTimelineView.DisplayMode.WAVEFORM -> !isWaveformGenerated
            WaveformTimelineView.DisplayMode.SPECTROGRAM -> !isSpectrogramGenerationStarted
        }
        binding.btnGenerateCache.visibility =
            if (needsGenerate && isWaveformExpanded) View.VISIBLE else View.GONE

        if (currentDisplayMode == WaveformTimelineView.DisplayMode.WAVEFORM) {
            if (isWaveformGenerating) {
                binding.btnGenerateCache.text = "生成中..."
                binding.btnGenerateCache.isEnabled = false
            } else {
                binding.btnGenerateCache.text = "生成波形图"
                binding.btnGenerateCache.isEnabled = true
            }
        } else {
            binding.btnGenerateCache.text = "生成频谱图"
            binding.btnGenerateCache.isEnabled = true
        }
    }

    private fun startWaveformGeneration() {
        if (chunkLoader == null) return
        isWaveformGenerating = true
        updateGenerateButton()
        showMessage("已启用波形图按需生成")
        chunkLoader?.generateCache { success ->
            isWaveformGenerating = false
            if (success) {
                isWaveformGenerated = true
                showMessage("波形图将在浏览时按需生成")
                connectWaveformLoader()
            } else {
                showMessage("波形缓存生成失败")
            }
            updateGenerateButton()
        }
    }

    private fun startSpectrogramGeneration() {
        if (audioCacheKey == null) return
        isSpectrogramGenerationStarted = true
        spectrogramTotalChunks = calcTotalChunks()
        spectrogramDoneChunks = spectrogramReadyChunks.size
        spectrogramIsGenerating = spectrogramDoneChunks < spectrogramTotalChunks
        updateGenerateButton()
        showMessage("频谱图将在浏览时按需生成")
        binding.waveformTimelineView.resetSpectrogramCache()
    }

    private fun cancelSpectrogramJobs() {
        val jobs = synchronized(spectrogramStateLock) {
            spectrogramGenerationVersion++
            val activeJobs = spectrogramJobs.values.toList()
            spectrogramJobs.clear()
            activeJobs
        }
        jobs.forEach { it.cancel() }
    }

    private fun connectWaveformLoader() {
        binding.waveformTimelineView.onChunkLoadRequest =
            { chunkIndex, startMs, endMs, targetSamples ->
                chunkLoader?.requestChunk(
                    chunkIndex,
                    startMs,
                    endMs,
                    targetSamples
                ) { index, data ->
                    binding.waveformTimelineView.post {
                        binding.waveformTimelineView.updateChunk(index, data)
                    }
                }
            }
        binding.waveformTimelineView.refreshVisibleChunks()
    }

    private companion object {
        const val TAG = "EditorWaveformController"
        const val MAX_CONCURRENT_SPECTROGRAM_GENERATIONS = 2
        const val STALE_SPECTROGRAM_PART_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
