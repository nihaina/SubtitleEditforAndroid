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
import com.subtitleedit.audio.FfmpegWaveformChunkLoader
import com.subtitleedit.databinding.ActivityEditorBinding
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.FileHashUtils
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.view.WaveformTimelineView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class EditorWaveformController(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val scope: CoroutineScope,
    private val hasPlayableMedia: Boolean,
    private val appCacheDir: File,
    private val currentPlaybackPositionMs: () -> Long,
    private val onSubtitlesChanged: (List<SubtitleEntry>) -> Unit,
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

    fun bind() {
        if (!hasPlayableMedia) {
            binding.mediaPlayerContainer.visibility = View.GONE
            return
        }

        binding.mediaPlayerContainer.visibility = View.VISIBLE
        binding.waveformTimelineView.onSubtitleChangeListener = onSubtitlesChanged
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
                    initializeMediaCache(audioFile, durationMs, audioStreamIndex, cacheIndexRequest)
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

    fun setSubtitlesKeepSelection(subtitles: List<SubtitleEntry>, selectedIndex: Int) {
        if (!hasPlayableMedia) return
        binding.waveformTimelineView.setSubtitlesKeepSelection(subtitles, selectedIndex)
    }

    fun setSubtitlesAfterDelete(subtitles: List<SubtitleEntry>, deletedIndices: Set<Int>) {
        if (!hasPlayableMedia) return
        binding.waveformTimelineView.setSubtitlesAfterDelete(subtitles, deletedIndices)
    }

    fun release() {
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
        cacheIndexRequest: Long
    ) {
        if (cacheIndexRequest != cacheIndexGeneration) return

        restoreSpectrogramCacheState(audioFile)
        binding.waveformTimelineView.post {
            if (cacheIndexRequest != cacheIndexGeneration) return@post
            restoreSpectrogramCacheState(audioFile)
            updateGenerateButton()
        }

        val cacheDir = when (SettingsManager.getInstance(context).getWaveformCacheLocation()) {
            SettingsManager.WAVEFORM_CACHE_APP -> File(appCacheDir, "waveform")
            else -> null
        }

        chunkLoader = FfmpegWaveformChunkLoader(scope).also {
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
        val cacheBaseDir = spectrogramCacheBaseDir(currentAudioFile) ?: return
        cacheBaseDir.mkdirs()
        val streamSuffix = audioStreamIndex?.let { ".a$it" }.orEmpty()
        val specFile = File(
            cacheBaseDir,
            "${currentAudioFile.nameWithoutExtension}$streamSuffix.spec_${chunkIndex}_${widthPx}x${heightPx}.png"
        )

        scope.launch(Dispatchers.IO) {
            val bitmap: Bitmap? = if (specFile.exists() && specFile.length() > 0) {
                BitmapFactory.decodeFile(specFile.absolutePath)
            } else {
                val startSec = startMs / 1000.0
                val durationSec = (endMs - startMs) / 1000.0
                val spectrumFilter = "showspectrumpic=s=${widthPx}x${heightPx}:" +
                    "mode=combined:color=intensity:scale=log:legend=0"
                val filterOptions = audioStreamIndex?.let { streamIndex ->
                    "-filter_complex \"[0:$streamIndex]$spectrumFilter[spectrum]\" " +
                        "-map \"[spectrum]\" "
                } ?: "-lavfi $spectrumFilter "
                val command = "-y -ss $startSec -t $durationSec " +
                    "-i \"${currentAudioFile.absolutePath}\" $filterOptions" +
                    "-frames:v 1 \"${specFile.absolutePath}\""
                val session = FFmpegKit.execute(command)
                if (session.getReturnCode()?.isValueSuccess() == true && specFile.exists()) {
                    BitmapFactory.decodeFile(specFile.absolutePath)
                } else {
                    Log.e(TAG, "频谱图分块生成失败：${session.getOutput()}")
                    null
                }
            }

            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    binding.waveformTimelineView.updateSpectrogramChunk(chunkIndex, bitmap)
                    if (spectrogramIsGenerating) {
                        spectrogramDoneChunks++
                        if (spectrogramDoneChunks >= spectrogramTotalChunks) {
                            spectrogramIsGenerating = false
                            showMessage("频谱图缓存生成完成")
                        }
                    }
                }
            }
        }
    }

    private fun restoreSpectrogramCacheState(audioFile: File) {
        spectrogramTotalChunks = calcTotalChunks()
        spectrogramDoneChunks = 0
        spectrogramIsGenerating = false
        isSpectrogramGenerationStarted =
            hasCompleteSpectrogramCache(audioFile, spectrogramTotalChunks)
        if (isSpectrogramGenerationStarted) {
            spectrogramDoneChunks = spectrogramTotalChunks
        }
    }

    private fun hasCompleteSpectrogramCache(audioFile: File, totalChunks: Int): Boolean {
        if (totalChunks <= 0) return false
        val dimensions = binding.waveformTimelineView.getSpectrogramCacheDimensions() ?: return false
        val (width, height) = dimensions
        val cacheBaseDir = spectrogramCacheBaseDir(audioFile) ?: return false
        val streamSuffix = audioStreamIndex?.let { ".a$it" }.orEmpty()
        val prefix = "${audioFile.nameWithoutExtension}$streamSuffix.spec_"
        return (0 until totalChunks).all { chunkIndex ->
            File(cacheBaseDir, "${prefix}${chunkIndex}_${width}x${height}.png")
                .let { it.isFile && it.length() > 0L }
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
        showMessage("正在生成波形缓存，请稍候...")
        chunkLoader?.generateCache { success ->
            isWaveformGenerating = false
            if (success) {
                isWaveformGenerated = true
                showMessage("波形缓存生成完成")
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
        spectrogramDoneChunks = 0
        spectrogramIsGenerating = spectrogramTotalChunks > 0
        updateGenerateButton()
        showMessage("正在生成频谱图缓存，请稍候...")
        binding.waveformTimelineView.resetSpectrogramCache()
        binding.waveformTimelineView.refreshVisibleChunks()
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
    }
}
