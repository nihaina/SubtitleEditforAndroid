package com.subtitleedit.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.subtitleedit.SubtitleEntry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 波形时间轴视图 —— 分块加载版本
 *
 * 核心设计：
 * 1. 音频时间轴划分为固定长度的 chunk（每块 30 秒）
 * 2. 默认视图显示 3 分钟，最大缩小到 10 分钟（不再显示全部）
 * 3. 初始化时只请求可见区域的 chunk，其余后台加载
 * 4. 缩放时根据屏幕像素密度动态调整采样精度
 * 5. 跳转播放位置时优先加载播放头附近的 chunk
 *
 * 使用方式：
 *   // 1. 初始化（无需波形数据）
 *   view.initialize(durationMs, subtitles)
 *
 *   // 2. 注册 chunk 加载回调
 *   view.onChunkLoadRequest = { chunkIndex, startMs, endMs, targetSamples ->
 *       // 在后台线程解码音频并调用：
 *       view.post { view.updateChunk(chunkIndex, amplitudeArray) }
 *   }
 */
class WaveformTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== 常量 ====================

    enum class DragMode { NONE, MOVE, RESIZE_START, RESIZE_END }

    companion object {
        /** 每个 chunk 的时长（毫秒）*/
        const val CHUNK_DURATION_MS = 30_000L

        /** 低精度采样数（未聚焦 chunk，约 5 点/秒）*/
        private const val LOW_RES_SAMPLES = 150

        /** 高精度采样数上限（聚焦 chunk，约 100 点/秒）*/
        private const val HIGH_RES_SAMPLES = 3000

        /** 最大放大：屏幕显示 10 秒 */
        private const val MIN_VISIBLE_MS = 10_000L

        /** 最大缩小：屏幕显示 10 分钟 */
        private const val MAX_VISIBLE_MS = 600_000L

        /** 默认视图：显示 3 分钟 */
        private const val DEFAULT_VISIBLE_MS = 180_000L

        /** 重新请求高精度的阈值：现有精度低于目标的 60% 时触发 */
        private const val RESAMPLE_THRESHOLD = 0.6f
    }

    // ==================== 数据 ====================

    private var subtitles: MutableList<SubtitleEntry> = mutableListOf()
    private var durationMs: Long = 0
    private var totalChunks = 0

    /** 每个 chunk 的波形数据，null = 尚未加载 */
    private var chunkData: Array<FloatArray?> = emptyArray()

    /** 已请求的采样数（0 = 未请求），用于避免重复请求 */
    private var chunkRequestedSamples: IntArray = IntArray(0)

    // ==================== 视口 ====================

    private var visibleStartMs: Long = 0
    private var visibleDurationMs: Long = DEFAULT_VISIBLE_MS

    // ==================== 状态 ====================

    private var isInitialized = false
    private var currentPosition = 0f

    // ==================== 拖拽 ====================

    private var dragMode = DragMode.NONE
    private var currentSubtitle: SubtitleEntry? = null
    private var dragStartX = 0f
    private var dragStartStartTime = 0L
    private var dragStartEndTime = 0L
    private var isDraggingWaveform = false
    private var dragStartVisibleStartMs = 0L
    private var clickedOnSubtitle = false

    // ==================== Bitmap 缓存 ====================

    private var waveformCache: Bitmap? = null
    private var cacheVisibleStart: Long = -1
    private var cacheVisibleDuration: Long = -1
    private var cacheWidth: Int = -1
    private var cacheWaveHeight: Int = -1

    /**
     * chunk 数据版本号：每次 updateChunk 都递增。
     * 缓存记录生成时的版本，版本不同则重绘。
     */
    private var chunkVersion = 0
    private var cachedChunkVersion = -1

    // ==================== 选中 ====================

    private var selectedIndices: Set<Int> = emptySet()

    // ==================== 回调 ====================

    /**
     * 需要加载某个 chunk 时触发（可能在主线程调用，请在回调内切换到后台线程）
     * @param chunkIndex chunk 索引
     * @param startMs    该 chunk 起始时间（毫秒）
     * @param endMs      该 chunk 结束时间（毫秒）
     * @param targetSamples 期望的采样点数（根据当前缩放动态计算）
     */
    var onChunkLoadRequest: ((chunkIndex: Int, startMs: Long, endMs: Long, targetSamples: Int) -> Unit)? = null

    var onSelectedIndicesChangeListener: ((Set<Int>) -> Unit)? = null
    var onTimelineClickListener: ((Float) -> Unit)? = null
    var onSubtitleChangeListener: ((List<SubtitleEntry>) -> Unit)? = null

    // ==================== 画笔（全部预创建，避免 onDraw 中分配对象）====================

    private val waveformPaint = Paint().apply {
        color = Color.parseColor("#4FC3F7")
        strokeWidth = 1f
        isAntiAlias = false   // 垂直线无需抗锯齿
        style = Paint.Style.STROKE
    }

    private val placeholderPaint = Paint().apply {
        color = Color.parseColor("#3A3A3A")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val timeRulerPaint = Paint().apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 24f
        isAntiAlias = true
    }

    private val subtitlePaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        isAntiAlias = true
    }

    private val selectedSubtitlePaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val playheadPaint = Paint().apply {
        color = Color.parseColor("#FF5722")
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val loadingPaint = Paint().apply {
        color = Color.parseColor("#9E9E9E")
        isAntiAlias = true
    }

    private val waveformBgPaint = Paint().apply { color = Color.parseColor("#262626") }
    private val timeRulerBgPaint = Paint().apply { color = Color.parseColor("#1A1A1A") }
    private val subtitleBgPaint = Paint().apply { color = Color.parseColor("#1A1A1A") }

    // ==================== 手势 ====================

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // 以缩放焦点为中心，保持焦点时间位置不变
                val pivotMs = xToTime(detector.focusX)
                val pivotRatio = (pivotMs - visibleStartMs).toFloat() / visibleDurationMs

                val newDuration = (visibleDurationMs / detector.scaleFactor)
                    .toLong()
                    .coerceIn(MIN_VISIBLE_MS, MAX_VISIBLE_MS)

                if (newDuration == visibleDurationMs) return true

                visibleDurationMs = newDuration
                visibleStartMs = (pivotMs - pivotRatio * visibleDurationMs)
                    .toLong()
                    .coerceIn(0L, max(0L, durationMs - visibleDurationMs))

                invalidateCache()
                requestVisibleChunks()
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!clickedOnSubtitle) {
                    val t = xToTime(e.x)
                    onTimelineClickListener?.invoke(t.toFloat() / durationMs)
                }
                clickedOnSubtitle = false
                return true
            }
        })

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ==================== 生命周期 ====================

    fun release() {
        waveformCache?.recycle()
        waveformCache = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    // ==================== 坐标转换 ====================

    private fun timeToX(timeMs: Long): Float {
        if (visibleDurationMs <= 0) return 0f
        return ((timeMs - visibleStartMs).toFloat() / visibleDurationMs * width).coerceAtLeast(0f)
    }

    private fun xToTime(x: Float): Long {
        if (width <= 0) return 0L
        return (visibleStartMs + x / width * visibleDurationMs).toLong().coerceAtLeast(0L)
    }

    // ==================== Chunk 工具 ====================

    private fun chunkStartMs(idx: Int) = idx * CHUNK_DURATION_MS
    private fun chunkEndMs(idx: Int) = min((idx + 1) * CHUNK_DURATION_MS, durationMs)
    private fun timeToChunkIndex(ms: Long) = (ms / CHUNK_DURATION_MS).toInt()

    /**
     * 根据当前可见时长动态计算期望精度。
     * 原则：屏幕上每个像素对应 >= 1 个采样点（2x 超采样保证细节）
     */
    private fun targetSamplesForZoom(): Int {
        val pixelsPerMs = width.toFloat() / visibleDurationMs
        val pixelsPerChunk = pixelsPerMs * CHUNK_DURATION_MS
        return (pixelsPerChunk * 2).toInt().coerceIn(LOW_RES_SAMPLES, HIGH_RES_SAMPLES)
    }

    /**
     * 请求当前可见区域的 chunk，同时预加载左右各 2 个 chunk（低精度）
     */
    private fun requestVisibleChunks() {
        if (totalChunks == 0) return

        val startChunk = timeToChunkIndex(visibleStartMs).coerceIn(0, totalChunks - 1)
        val endChunk = timeToChunkIndex(visibleStartMs + visibleDurationMs).coerceIn(0, totalChunks - 1)
        val targetSamples = targetSamplesForZoom()

        // 可见区域：高精度
        for (i in startChunk..endChunk) {
            val existing = chunkData[i]
            val requested = chunkRequestedSamples[i]
            val needUpgrade = existing != null && existing.size < targetSamples * RESAMPLE_THRESHOLD
            val notRequested = requested < targetSamples
            if ((existing == null && requested == 0) || (needUpgrade && notRequested)) {
                chunkRequestedSamples[i] = targetSamples
                onChunkLoadRequest?.invoke(i, chunkStartMs(i), chunkEndMs(i), targetSamples)
            }
        }

        // 相邻预加载：低精度
        val prefetch = LOW_RES_SAMPLES
        for (i in max(0, startChunk - 2) until startChunk) {
            if (chunkData[i] == null && chunkRequestedSamples[i] == 0) {
                chunkRequestedSamples[i] = prefetch
                onChunkLoadRequest?.invoke(i, chunkStartMs(i), chunkEndMs(i), prefetch)
            }
        }
        for (i in (endChunk + 1)..min(totalChunks - 1, endChunk + 2)) {
            if (chunkData[i] == null && chunkRequestedSamples[i] == 0) {
                chunkRequestedSamples[i] = prefetch
                onChunkLoadRequest?.invoke(i, chunkStartMs(i), chunkEndMs(i), prefetch)
            }
        }
    }

    /**
     * 跳转时优先加载播放头附近 chunk，由近到远
     */
    private fun requestChunksAroundTime(timeMs: Long) {
        val center = timeToChunkIndex(timeMs)
        val target = targetSamplesForZoom()
        for (offset in 0..4) {
            for (delta in if (offset == 0) listOf(0) else listOf(-offset, offset)) {
                val idx = center + delta
                if (idx < 0 || idx >= totalChunks) continue
                val existing = chunkData[idx]
                val requested = chunkRequestedSamples[idx]
                if (existing == null && requested == 0) {
                    chunkRequestedSamples[idx] = target
                    onChunkLoadRequest?.invoke(idx, chunkStartMs(idx), chunkEndMs(idx), target)
                }
            }
        }
    }

    // ==================== 缓存管理 ====================

    private fun invalidateCache() {
        waveformCache?.recycle()
        waveformCache = null
        cacheVisibleStart = -1
        cacheVisibleDuration = -1
    }

    // ==================== 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        if (!isInitialized) {
            drawLoadingSpinner(canvas)
            return
        }

        val h = height.toFloat()
        val rulerH = h * 0.10f
        val waveH = h * 0.55f
        val subH = h * 0.35f

        drawTimeRuler(canvas, rulerH)
        drawWaveform(canvas, rulerH, waveH)
        drawSubtitles(canvas, rulerH + waveH, subH)
        drawPlayhead(canvas, h)
    }

    // ---------- 时间刻度 ----------

    private fun drawTimeRuler(canvas: Canvas, rulerH: Float) {
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), rulerH)
        canvas.drawRect(0f, 0f, width.toFloat(), rulerH, timeRulerBgPaint)

        val numMarks = 10
        val interval = visibleDurationMs / numMarks
        for (i in 0..numMarks) {
            val t = visibleStartMs + i * interval
            val x = timeToX(t)
            canvas.drawLine(x, rulerH * 0.3f, x, rulerH, timeRulerPaint)
            canvas.drawText(formatTime(t), x + 4, rulerH * 0.8f, timeRulerPaint)
        }
        canvas.restore()
    }

    private fun formatTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1_000
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    // ---------- 波形（带 Bitmap 缓存）----------

    private fun drawWaveform(canvas: Canvas, yOffset: Float, waveH: Float) {
        canvas.save()
        canvas.clipRect(0f, yOffset, width.toFloat(), yOffset + waveH)

        val cacheValid = waveformCache != null &&
                cacheVisibleStart == visibleStartMs &&
                cacheVisibleDuration == visibleDurationMs &&
                cacheWidth == width &&
                cacheWaveHeight == waveH.toInt() &&
                cachedChunkVersion == chunkVersion

        if (cacheValid) {
            canvas.drawBitmap(waveformCache!!, 0f, yOffset, null)
            canvas.restore()
            return
        }

        renderWaveformToCache(waveH.toInt())
        canvas.drawBitmap(waveformCache!!, 0f, yOffset, null)
        canvas.restore()
    }

    /**
     * 按 chunk 渲染波形到 Bitmap 缓存。
     * Bitmap 坐标系从 0 开始，绘制完后 drawBitmap 时传入 yOffset。
     */
    private fun renderWaveformToCache(waveHeight: Int) {
        waveformCache?.recycle()
        val bmp = Bitmap.createBitmap(width, waveHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        c.drawRect(0f, 0f, width.toFloat(), waveHeight.toFloat(), waveformBgPaint)

        val centerY = waveHeight / 2f
        val amplitude = waveHeight / 2f * 0.9f

        if (durationMs <= 0) {
            waveformCache = bmp
            cacheVisibleStart = visibleStartMs
            cacheVisibleDuration = visibleDurationMs
            cacheWidth = width
            cacheWaveHeight = waveHeight
            cachedChunkVersion = chunkVersion
            return
        }

        val startChunk = timeToChunkIndex(visibleStartMs).coerceIn(0, totalChunks - 1)
        val endChunk = timeToChunkIndex(visibleStartMs + visibleDurationMs).coerceIn(0, totalChunks - 1)

        for (chunkIdx in startChunk..endChunk) {
            val data = chunkData[chunkIdx]

            // 该 chunk 在屏幕上的像素范围
            val px1 = timeToX(chunkStartMs(chunkIdx)).toInt().coerceIn(0, width)
            val px2 = timeToX(chunkEndMs(chunkIdx)).toInt().coerceIn(0, width)
            if (px1 >= px2) continue

            if (data == null || data.isEmpty()) {
                // 未加载：绘制灰色占位线
                for (px in px1 until px2) {
                    c.drawLine(px.toFloat(), centerY - 4f, px.toFloat(), centerY + 4f, placeholderPaint)
                }
                continue
            }

            val chunkStart = chunkStartMs(chunkIdx).toFloat()
            val chunkDur = (chunkEndMs(chunkIdx) - chunkStartMs(chunkIdx)).toFloat()

            for (px in px1 until px2) {
                // 该像素对应的时间范围 → chunk 内位置 → 采样区间
                val tStart = xToTime(px.toFloat())
                val tEnd = xToTime((px + 1).toFloat())

                val posStart = ((tStart - chunkStart) / chunkDur).coerceIn(0f, 1f)
                val posEnd = ((tEnd - chunkStart) / chunkDur).coerceIn(0f, 1f)

                val fromSample = (posStart * data.size).toInt().coerceIn(0, data.size - 1)
                val toSample = (posEnd * data.size).toInt().coerceIn(fromSample + 1, data.size)

                // 取区间内最大振幅（保留峰值）
                var maxAmp = 0f
                for (i in fromSample until toSample) {
                    if (data[i] > maxAmp) maxAmp = data[i]
                }

                val h = maxAmp * amplitude
                c.drawLine(px.toFloat(), centerY - h, px.toFloat(), centerY + h, waveformPaint)
            }
        }

        waveformCache = bmp
        cacheVisibleStart = visibleStartMs
        cacheVisibleDuration = visibleDurationMs
        cacheWidth = width
        cacheWaveHeight = waveHeight
        cachedChunkVersion = chunkVersion
    }

    // ---------- 字幕块 ----------

    private fun drawSubtitles(canvas: Canvas, yOffset: Float, trackH: Float) {
        canvas.save()
        canvas.clipRect(0f, yOffset, width.toFloat(), yOffset + trackH)
        canvas.drawRect(0f, yOffset, width.toFloat(), yOffset + trackH, subtitleBgPaint)

        val boxTop = yOffset + trackH * 0.15f
        val boxBot = yOffset + trackH * 0.90f

        for ((index, sub) in subtitles.withIndex()) {
            if (sub.endTime < visibleStartMs || sub.startTime > visibleStartMs + visibleDurationMs) continue

            val x1 = timeToX(sub.startTime)
            val x2 = timeToX(sub.endTime)
            val rw = max(x2 - x1, 4f)

            val paint = if (index in selectedIndices) selectedSubtitlePaint else subtitlePaint
            canvas.drawRoundRect(RectF(x1, boxTop, x1 + rw, boxBot), 4f, 4f, paint)

            if (sub.text.isNotEmpty()) {
                val tx = x1 + 10
                val ty = boxTop + (boxBot - boxTop) / 2 + 10
                canvas.drawText(clipText(sub.text, rw - 20), tx, ty, textPaint)
            }
        }
        canvas.restore()
    }

    /** O(log n) 二分裁剪文本 */
    private fun clipText(text: String, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (textPaint.measureText(text) <= maxWidth) return text
        var lo = 0; var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (textPaint.measureText(text.substring(0, mid) + "...") <= maxWidth) lo = mid else hi = mid - 1
        }
        return if (lo > 0) text.substring(0, lo) + "..." else ""
    }

    // ---------- 播放头 ----------

    private fun drawPlayhead(canvas: Canvas, viewH: Float) {
        if (durationMs <= 0) return
        val x = timeToX((durationMs * currentPosition).toLong())
        canvas.drawLine(x, 0f, x, viewH, playheadPaint)
        canvas.drawLine(x - 8f, 0f, x, 8f, playheadPaint)
        canvas.drawLine(x + 8f, 0f, x, 8f, playheadPaint)
    }

    // ---------- 加载动画 ----------

    private fun drawLoadingSpinner(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        loadingPaint.style = Paint.Style.STROKE
        loadingPaint.strokeWidth = 4f
        canvas.drawCircle(cx, cy, 40f, loadingPaint)
        loadingPaint.style = Paint.Style.FILL
        loadingPaint.textSize = 32f
        loadingPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("加载中...", cx, cy + 60f, loadingPaint)
    }

    // ==================== 触摸 ====================

    private fun subtitleTrackY(): Float = height * 0.65f   // 10% + 55%
    private fun isInSubtitleArea(y: Float) = y >= subtitleTrackY() && y <= height.toFloat()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                clickedOnSubtitle = false

                if (isInSubtitleArea(event.y)) {
                    val sub = findSubtitle(event.x)
                    if (sub != null) {
                        clickedOnSubtitle = true
                        currentSubtitle = sub
                        val idx = subtitles.indexOf(sub)
                        if (idx in selectedIndices) {
                            dragMode = detectDragMode(event.x, sub)
                            dragStartStartTime = sub.startTime
                            dragStartEndTime = sub.endTime
                        } else {
                            selectSubtitle(idx)
                            dragMode = DragMode.NONE
                        }
                    } else {
                        clearSelection(); currentSubtitle = null
                    }
                    isDraggingWaveform = false
                } else {
                    isDraggingWaveform = true
                    dragStartVisibleStartMs = visibleStartMs
                    dragMode = DragMode.NONE; currentSubtitle = null
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - dragStartX

                if (isDraggingWaveform) {
                    val dt = (dx / width * visibleDurationMs).toLong()
                    val newStart = (dragStartVisibleStartMs - dt)
                        .coerceIn(0L, max(0L, durationMs - visibleDurationMs))
                    if (newStart != visibleStartMs) {
                        visibleStartMs = newStart
                        invalidateCache()
                        requestVisibleChunks()
                        invalidate()
                    }
                    return true
                }

                if (dragMode == DragMode.NONE || currentSubtitle == null) return true
                val s = currentSubtitle!!
                val dt = xToTime(event.x) - xToTime(dragStartX)

                var changed = false
                when (dragMode) {
                    DragMode.MOVE -> {
                        val dur = dragStartEndTime - dragStartStartTime
                        s.startTime = (dragStartStartTime + dt).coerceAtLeast(0L)
                        s.endTime = s.startTime + dur
                        changed = true
                    }
                    DragMode.RESIZE_START -> {
                        s.startTime = (dragStartStartTime + dt).coerceIn(0L, s.endTime - 100L)
                        changed = true
                    }
                    DragMode.RESIZE_END -> {
                        s.endTime = (dragStartEndTime + dt).coerceAtLeast(s.startTime + 100L)
                        s.endTimeModified = true
                        changed = true
                    }
                    DragMode.NONE -> {}
                }
                if (changed) onSubtitleChangeListener?.invoke(subtitles.toList())
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != DragMode.NONE) onSubtitleChangeListener?.invoke(subtitles.toList())
                dragMode = DragMode.NONE; currentSubtitle = null; isDraggingWaveform = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun selectSubtitle(index: Int) {
        if (index !in 0 until subtitles.size) return
        selectedIndices = setOf(index)
        onSelectedIndicesChangeListener?.invoke(selectedIndices)
        invalidate()
    }

    private fun clearSelection() {
        selectedIndices = emptySet()
        onSelectedIndicesChangeListener?.invoke(selectedIndices)
        invalidate()
    }

    private fun findSubtitle(x: Float) = subtitles.firstOrNull {
        x in timeToX(it.startTime)..timeToX(it.endTime)
    }

    private fun detectDragMode(x: Float, sub: SubtitleEntry): DragMode {
        val sx = timeToX(sub.startTime); val ex = timeToX(sub.endTime)
        return when {
            abs(x - sx) < 30f -> DragMode.RESIZE_START
            abs(x - ex) < 30f -> DragMode.RESIZE_END
            else -> DragMode.MOVE
        }
    }

    // ==================== 公共 API ====================

    /**
     * 初始化时间轴（不含波形数据）。
     * 调用后立即可渲染，波形区域显示占位灰线，chunk 数据按需加载后自动刷新。
     */
    fun initialize(durationMs: Long, subtitles: List<SubtitleEntry>) {
        this.durationMs = durationMs
        this.subtitles = subtitles.toMutableList()
        this.totalChunks = ((durationMs + CHUNK_DURATION_MS - 1) / CHUNK_DURATION_MS).toInt()
        this.chunkData = Array(totalChunks) { null }
        this.chunkRequestedSamples = IntArray(totalChunks)
        this.isInitialized = true

        // 默认视口：从头开始，显示 3 分钟（或音频更短时显示全部）
        visibleStartMs = 0L
        visibleDurationMs = minOf(DEFAULT_VISIBLE_MS, durationMs)

        invalidateCache()
        invalidate()

        // View 尺寸确定后触发首批加载
        post { requestVisibleChunks() }
    }

    /**
     * 提供某个 chunk 的波形数据（必须在主线程调用）。
     * Activity/ViewModel 在后台解码完成后通过 view.post { view.updateChunk(...) } 调用。
     */
    fun updateChunk(chunkIndex: Int, data: FloatArray) {
        if (chunkIndex !in 0 until totalChunks) return
        chunkData[chunkIndex] = data
        chunkVersion++

        // 只有该 chunk 在可见区域内才立即重绘
        val cs = chunkStartMs(chunkIndex); val ce = chunkEndMs(chunkIndex)
        if (ce >= visibleStartMs && cs <= visibleStartMs + visibleDurationMs) {
            invalidateCache()
            invalidate()
        }
    }

    /**
     * 更新播放头位置，播放头超出可见区域时自动跟随滚动
     */
    fun setCurrentPosition(position: Float) {
        val newPos = position.coerceIn(0f, 1f)
        val changed = newPos != currentPosition
        currentPosition = newPos

        if (changed && durationMs > 0) {
            val pt = (durationMs * currentPosition).toLong()
            val ratio = if (visibleDurationMs > 0) {
                (pt - visibleStartMs).toFloat() / visibleDurationMs
            } else 0f
            // 播放头超出可见区域时，贴左边跟随（与旧版一致）
            if (ratio > 1f || ratio < 0f) {
                visibleStartMs = pt.coerceIn(0L, max(0L, durationMs - visibleDurationMs))
                invalidateCache()
                requestVisibleChunks()
            }
        }
        invalidate()
    }

    /**
     * 跳转到指定时间（如点击字幕列表）。
     * 视口贴左对齐到 timeMs，优先加载该位置附近的 chunk。
     */
    fun seekToTime(timeMs: Long) {
        visibleStartMs = timeMs.coerceIn(0L, max(0L, durationMs - visibleDurationMs))
        invalidateCache()
        requestChunksAroundTime(timeMs)
        invalidate()
    }

    fun setSelectedIndices(indices: Set<Int>) {
        selectedIndices = indices; invalidate()
    }

    fun setSubtitles(list: List<SubtitleEntry>) {
        subtitles = list.toMutableList(); invalidate()
    }

    fun getSubtitles(): List<SubtitleEntry> = subtitles.toList()

    /**
     * 兼容旧版 API：一次性传入字幕列表，立即渲染字幕块。
     * 波形数据忽略（由 onChunkLoadRequest 分块加载）。
     * Activity 未迁移到 initialize() 之前调用此方法可确保字幕正常显示。
     */
    fun setTimelineData(
        durationMs: Long,
        subtitles: List<SubtitleEntry>,
        @Suppress("UNUSED_PARAMETER") waveformAmplitudes: FloatArray = FloatArray(0)
    ) {
        initialize(durationMs, subtitles)
    }

    fun scrollToTime(timeMs: Long) {
        visibleStartMs = timeMs.coerceIn(0L, max(0L, durationMs - visibleDurationMs))
        invalidateCache()
        requestVisibleChunks()
        invalidate()
    }
}
