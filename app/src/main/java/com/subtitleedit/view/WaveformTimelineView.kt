package com.subtitleedit.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.DashPathEffect
import android.graphics.Path
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

        /** 最大放大：屏幕显示 5 秒 */
        private const val MIN_VISIBLE_MS = 5_000L

        /** 最大缩小：屏幕显示 10 分钟 */
        private const val MAX_VISIBLE_MS = 600_000L

        /** 默认视图：显示 3 分钟 */
        private const val DEFAULT_VISIBLE_MS = 180_000L

        /** 重新请求高精度的阈值：现有精度低于目标的 60% 时触发 */
        private const val RESAMPLE_THRESHOLD = 0.6f

        /** 频谱图每个 chunk 的固定生成宽度（像素），与缩放无关，保证放大后仍清晰 */
        private const val SPECTROGRAM_CHUNK_WIDTH = 2048
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
    private var downOnSelectedSubtitle = false  // ACTION_DOWN 时是否点在已选中字幕上

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

    // ==================== 振幅缩放 ====================
    /** 垂直振幅缩放倍数，默认 1.0，范围 0.2 ~ 4.0 */
    private var amplitudeScale: Float = 1.0f

    // ==================== 显示模式 ====================
    enum class DisplayMode { WAVEFORM, SPECTROGRAM }
    private var displayMode = DisplayMode.WAVEFORM

    // ==================== 频谱图分块 ====================
    /** 每个 chunk 的频谱图 Bitmap，null = 未生成 */
    private var spectrogramChunks: Array<Bitmap?> = emptyArray()
    /** 已发起请求的 chunk 集合，避免重复请求 */
    private val spectrogramRequested = mutableSetOf<Int>()

    var onSpectrogramChunkRequest: ((chunkIndex: Int, startMs: Long, endMs: Long, widthPx: Int, heightPx: Int) -> Unit)? = null

    private val spectrogramHintPaint = Paint().apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

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

    /** 选中字幕块：中间移动区（蓝色，略深） */
    private val subtitleMoveZonePaint = Paint().apply {
        color = Color.parseColor("#1976D2")
        isAntiAlias = true
    }

    /** 选中字幕块：左/右缩放柄区（琥珀色） */
    private val subtitleResizeHandlePaint = Paint().apply {
        color = Color.parseColor("#F57C00")
        isAntiAlias = true
    }

    /** 缩放柄上的箭头符号 */
    private val handleIconPaint = Paint().apply {
        color = Color.WHITE
        textSize = 22f
        isAntiAlias = true
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
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

    // ==================== 字幕边界虚线画笔 ====================
    private val subtitleEdgePaint = Paint().apply {
        color = Color.parseColor("#A54CAF50")   // 绿色半透明
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = false
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val subtitleEdgeSelectedPaint = Paint().apply {
        color = Color.parseColor("#CC64B5F6")   // 蓝色半透明
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = false
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }

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

    // 播放头上次绘制的 X 坐标，用于局部刷新（脏区更新）
    private var lastPlayheadX = -1f

    init {
        // 软件渲染对 invalidate(Rect) 的响应更直接，且方便局部绘制缓存
        // 硬件加速在处理频繁的小面积重绘时反而可能变慢，因为它涉及复杂的纹理更新
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    // ==================== 生命周期 ====================

    fun release() {
        waveformCache?.recycle()
        waveformCache = null
        spectrogramChunks.forEach { it?.recycle() }
        spectrogramChunks = emptyArray()
        spectrogramRequested.clear()
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
        // 缓存失效时重置播放头位置，下次需要全屏重绘
        lastPlayheadX = -1f
    }

    // ==================== 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        if (!isInitialized) {
            drawLoadingSpinner(canvas)
            return
        }

        val h      = height.toFloat()
        val rulerH = h * 0.08f
        val waveH  = h * 0.67f
        val subH   = h * 0.25f

        drawTimeRuler(canvas, rulerH)
        when (displayMode) {
            DisplayMode.WAVEFORM    -> drawWaveform(canvas, rulerH, waveH)
            DisplayMode.SPECTROGRAM -> drawSpectrogram(canvas, rulerH, waveH)
        }
        drawSubtitles(canvas, rulerH + waveH, subH)
        // 字幕边界虚线：独立 pass，不受 subtitle clipRect 限制，可穿入波形区
        drawSubtitleEdgeLines(canvas, rulerH, waveH + subH)
        drawPlayhead(canvas, h)
    }

    // ---------- 时间刻度 ----------

    private fun drawTimeRuler(canvas: Canvas, rulerH: Float) {
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), rulerH)
        canvas.drawRect(0f, 0f, width.toFloat(), rulerH, timeRulerBgPaint)

        // 1. 根据可见时长选一个"整点对齐"的主刻度间隔
        //    目标：屏幕上出现 5~8 个主刻度
        val intervalMs = pickNiceInterval(visibleDurationMs)

        // 2. 副刻度间隔 = 主刻度 / 5（最小 100ms）
        val minorIntervalMs = (intervalMs / 5L).coerceAtLeast(100L)

        val visibleEndMs = visibleStartMs + visibleDurationMs

        // 3. 先画副刻度（细线，无文字）
        val minorStart = (visibleStartMs / minorIntervalMs) * minorIntervalMs
        var t = minorStart
        while (t <= visibleEndMs) {
            if (t % intervalMs != 0L) {          // 非主刻度才画副刻度
                val x = timeToX(t)
                if (x in 0f..width.toFloat()) {
                    canvas.drawLine(x, rulerH * 0.65f, x, rulerH, timeRulerPaint.apply {
                        alpha = 80
                    })
                }
            }
            t += minorIntervalMs
        }
        timeRulerPaint.alpha = 255

        // 4. 画主刻度（长线 + 文字），从最近的主刻度整点开始
        val majorStart = (visibleStartMs / intervalMs) * intervalMs
        t = majorStart
        var prevLabelRight = -Float.MAX_VALUE   // 防止文字重叠
        while (t <= visibleEndMs) {
            val x = timeToX(t)
            if (x >= 0f && x <= width.toFloat()) {
                // 长刻度线
                canvas.drawLine(x, rulerH * 0.25f, x, rulerH, timeRulerPaint)

                // 文字：只在不与前一个标签重叠时才绘制
                val label = formatRulerTime(t, intervalMs)
                val labelW = timeRulerPaint.measureText(label)
                val labelX = (x + 4f).coerceAtMost(width - labelW - 2f)
                if (labelX > prevLabelRight + 4f) {
                    canvas.drawText(label, labelX, rulerH * 0.78f, timeRulerPaint)
                    prevLabelRight = labelX + labelW
                }
            }
            t += intervalMs
        }

        canvas.restore()
    }

    /**
     * 根据可见时长选出最合适的主刻度间隔（毫秒），目标在屏幕上出现 5~8 个刻度。
     *
     * 候选间隔列表（均为"整点"：整百 ms / 整秒 / 整分钟）：
     * 100ms, 200ms, 500ms,
     * 1s, 2s, 5s, 10s, 15s, 30s,
     * 1min, 2min, 5min, 10min
     */
    private fun pickNiceInterval(visibleMs: Long): Long {
        val candidates = longArrayOf(
            100, 200, 500,
            1_000, 2_000, 5_000, 10_000, 15_000, 30_000,
            60_000, 120_000, 300_000, 600_000
        )
        // 目标 6 个主刻度
        val target = visibleMs / 6L
        return candidates.minByOrNull { abs(it - target) } ?: 1_000L
    }

    /**
     * 格式化刻度标签，根据当前间隔决定精度：
     * - 间隔 < 1s  → 显示 m:ss.S（精确到百毫秒）
     * - 间隔 < 1min → 显示 m:ss
     * - 间隔 >= 1min → 显示 h:mm:ss 或 m:ss
     */
    private fun formatRulerTime(ms: Long, intervalMs: Long): String {
        val h  =  ms / 3_600_000L
        val m  = (ms % 3_600_000L) / 60_000L
        val s  = (ms % 60_000L) / 1_000L
        val ds = (ms % 1_000L) / 100L   // 十分之一秒

        return when {
            intervalMs < 1_000L ->
                if (h > 0) String.format("%d:%02d:%02d.%d", h, m, s, ds)
                else       String.format("%d:%02d.%d", m, s, ds)
            h > 0 ->
                String.format("%d:%02d:%02d", h, m, s)
            else ->
                String.format("%d:%02d", m, s)
        }
    }

    // ---------- 频谱图（分块版本）----------

    private fun drawSpectrogram(canvas: Canvas, yOffset: Float, spectH: Float) {
        canvas.save()
        canvas.clipRect(0f, yOffset, width.toFloat(), yOffset + spectH)
        canvas.drawRect(0f, yOffset, width.toFloat(), yOffset + spectH, waveformBgPaint)

        if (durationMs <= 0) { canvas.restore(); return }

        val startChunk = timeToChunkIndex(visibleStartMs).coerceIn(0, totalChunks - 1)
        val endChunk   = timeToChunkIndex(visibleStartMs + visibleDurationMs).coerceIn(0, totalChunks - 1)

        for (chunkIdx in startChunk..endChunk) {
            val chunkStart = chunkStartMs(chunkIdx)
            val chunkEnd   = chunkEndMs(chunkIdx)
            val chunkDur   = (chunkEnd - chunkStart).toFloat()

            // 该 chunk 在当前视口中实际可见的时间段
            val visStart = maxOf(visibleStartMs, chunkStart)
            val visEnd   = minOf(visibleStartMs + visibleDurationMs, chunkEnd)

            val dstLeft  = timeToX(visStart)
            val dstRight = timeToX(visEnd)
            if (dstLeft >= dstRight) continue

            val bmp = spectrogramChunks.getOrNull(chunkIdx)

            if (bmp == null) {
                // 占位灰块 + 触发请求（固定宽度，与缩放无关）
                canvas.drawRect(dstLeft, yOffset, dstRight, yOffset + spectH, placeholderPaint)
                if (chunkIdx !in spectrogramRequested) {
                    spectrogramRequested.add(chunkIdx)
                    onSpectrogramChunkRequest?.invoke(
                        chunkIdx, chunkStart, chunkEnd,
                        SPECTROGRAM_CHUNK_WIDTH,
                        spectH.toInt().coerceAtLeast(64)
                    )
                }
                continue
            }

            // 将可见时间段映射到 bitmap 的像素列范围（src），绘制到屏幕范围（dst）
            // 无论缩放级别，bitmap 始终以固定 2048px 宽生成，由 Android 负责缩放插值
            val srcLeft  = ((visStart  - chunkStart) / chunkDur * bmp.width)
                .toInt().coerceIn(0, bmp.width - 1)
            val srcRight = ((visEnd    - chunkStart) / chunkDur * bmp.width)
                .toInt().coerceIn(srcLeft + 1, bmp.width)

            val src = android.graphics.Rect(srcLeft, 0, srcRight, bmp.height)
            val dst = RectF(dstLeft, yOffset, dstRight, yOffset + spectH)
            canvas.drawBitmap(bmp, src, dst, null)
        }

        canvas.restore()
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

            // data 为交错格式 [max₀, absMin₀, max₁, absMin₁ …]
            val frameCount = data.size / 2

            for (px in px1 until px2) {
                val tStart = xToTime(px.toFloat())
                val tEnd   = xToTime((px + 1).toFloat())

                val posStart = ((tStart - chunkStart) / chunkDur).coerceIn(0f, 1f)
                val posEnd   = ((tEnd   - chunkStart) / chunkDur).coerceIn(0f, 1f)

                val fromFrame = (posStart * frameCount).toInt().coerceIn(0, frameCount - 1)
                val toFrame   = (posEnd   * frameCount).toInt().coerceIn(fromFrame + 1, frameCount)

                var peakMax = 0f
                var peakMin = 0f
                for (i in fromFrame until toFrame) {
                    val mx = data[i * 2]
                    val mn = data[i * 2 + 1]
                    if (mx > peakMax) peakMax = mx
                    if (mn > peakMin) peakMin = mn
                }

                val topH    = (peakMax * amplitude * amplitudeScale).coerceAtMost(amplitude)
                val bottomH = (peakMin * amplitude * amplitudeScale).coerceAtMost(amplitude)
                c.drawLine(px.toFloat(), centerY - topH, px.toFloat(), centerY + bottomH, waveformPaint)
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

    /** 与 detectDragMode 保持同步：左右边缘 30px 为缩放热区 */
    private val HANDLE_ZONE_W = 30f

    private fun drawSubtitles(canvas: Canvas, yOffset: Float, trackH: Float) {
        canvas.save()
        canvas.clipRect(0f, yOffset, width.toFloat(), yOffset + trackH)
        canvas.drawRect(0f, yOffset, width.toFloat(), yOffset + trackH, subtitleBgPaint)

        val boxTop = yOffset + trackH * 0.15f
        val boxBot = yOffset + trackH * 0.90f
        val boxH   = boxBot - boxTop
        val iconY  = boxTop + boxH / 2f + 8f

        for ((index, sub) in subtitles.withIndex()) {
            if (sub.endTime < visibleStartMs || sub.startTime > visibleStartMs + visibleDurationMs) continue

            val x1 = timeToX(sub.startTime)
            val x2 = timeToX(sub.endTime)
            val rw = max(x2 - x1, 4f)
            val rect = RectF(x1, boxTop, x1 + rw, boxBot)

            if (index !in selectedIndices) {
                // 未选中：整块统一绿色
                canvas.drawRoundRect(rect, 4f, 4f, subtitlePaint)
            } else {
                // 选中：三区分色
                val hw = HANDLE_ZONE_W.coerceAtMost(rw / 3f)

                // 左缩放柄
                canvas.drawRoundRect(RectF(x1, boxTop, x1 + hw, boxBot), 4f, 4f, subtitleResizeHandlePaint)
                // 右缩放柄
                canvas.drawRoundRect(RectF(x1 + rw - hw, boxTop, x1 + rw, boxBot), 4f, 4f, subtitleResizeHandlePaint)
                // 中间移动区
                if (rw > hw * 2f) {
                    canvas.drawRect(RectF(x1 + hw, boxTop, x1 + rw - hw, boxBot), subtitleMoveZonePaint)
                }
                // 柄上箭头图标
                if (hw >= 14f) {
                    canvas.drawText("‹", x1 + hw / 2f, iconY, handleIconPaint)
                    canvas.drawText("›", x1 + rw - hw / 2f, iconY, handleIconPaint)
                }
            }

            // 字幕文本
            if (sub.text.isNotEmpty()) {
                val textMarginL = if (index in selectedIndices) HANDLE_ZONE_W.coerceAtMost(rw / 3f) + 4f else 10f
                val textMarginR = if (index in selectedIndices) HANDLE_ZONE_W.coerceAtMost(rw / 3f) + 4f else 10f
                val availW = rw - textMarginL - textMarginR
                if (availW > 12f) {
                    canvas.drawText(clipText(sub.text, availW), x1 + textMarginL, boxTop + boxH / 2f + 8f, textPaint)
                }
            }
        }
        canvas.restore()
    }

    /**
     * 字幕边界虚线：从波形区顶部画到字幕区底部，穿越两个区域。
     * 不加 clipRect，直接绘制在全 canvas 上。
     */
    private fun drawSubtitleEdgeLines(canvas: Canvas, waveTop: Float, totalH: Float) {
        val lineTop = waveTop          // 波形区顶部
        val lineBot = waveTop + totalH // 字幕区底部

        for ((index, sub) in subtitles.withIndex()) {
            if (sub.endTime < visibleStartMs || sub.startTime > visibleStartMs + visibleDurationMs) continue

            val edgePaint = if (index in selectedIndices) subtitleEdgeSelectedPaint else subtitleEdgePaint

            val x1 = timeToX(sub.startTime)
            val x2 = timeToX(sub.endTime)

            if (x1 in 0f..width.toFloat()) {
                canvas.drawLine(x1, lineTop, x1, lineBot, edgePaint)
            }
            if (x2 in 0f..width.toFloat()) {
                canvas.drawLine(x2, lineTop, x2, lineBot, edgePaint)
            }
        }
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
                            // 已选中：DOWN 时保持 NONE，等 MOVE 时确认真正拖动再设置 dragMode
                            downOnSelectedSubtitle = true
                            dragMode = DragMode.NONE
                            dragStartStartTime = sub.startTime
                            dragStartEndTime = sub.endTime
                        } else {
                            downOnSelectedSubtitle = false
                            selectSubtitle(idx)
                            dragMode = DragMode.NONE
                        }
                    } else {
                        downOnSelectedSubtitle = false
                        clearSelection(); currentSubtitle = null
                    }
                    isDraggingWaveform = false
                } else {
                    downOnSelectedSubtitle = false
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

                // 已选中字幕的延迟拖拽激活：位移超过 8px 才确认为拖拽操作
                if (downOnSelectedSubtitle && dragMode == DragMode.NONE && currentSubtitle != null) {
                    if (Math.abs(dx) > 8f) {
                        dragMode = detectDragMode(dragStartX, currentSubtitle!!)
                    } else {
                        return true  // 位移不够，还不是拖拽
                    }
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
                val dx = event.x - dragStartX

                if (downOnSelectedSubtitle && dragMode == DragMode.NONE && Math.abs(dx) < 10f) {
                    // 点在已选中字幕上，且没有拖动 → 取消选中
                    clearSelection()
                    currentSubtitle = null
                } else {
                    if (dragMode != DragMode.NONE) onSubtitleChangeListener?.invoke(subtitles.toList())
                }

                downOnSelectedSubtitle = false
                dragMode = DragMode.NONE
                currentSubtitle = null
                isDraggingWaveform = false
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
        this.spectrogramChunks = Array(totalChunks) { null }
        this.spectrogramRequested.clear()
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
     * 使用脏区更新（Dirty Rect）技术，只刷新受影响的矩形区域，提高性能
     */
    fun setCurrentPosition(position: Float) {
        val newPos = position.coerceIn(0f, 1f)
        val changed = newPos != currentPosition
        currentPosition = newPos

        if (changed && durationMs > 0) {
            val pt = (durationMs * currentPosition).toLong()
            val newX = timeToX(pt)
            val oldX = lastPlayheadX
            
            // 播放头超出可见区域时，贴左边跟随（与旧版一致）
            val ratio = if (visibleDurationMs > 0) {
                (pt - visibleStartMs).toFloat() / visibleDurationMs
            } else 0f
            
            if (ratio > 1f || ratio < 0f) {
                visibleStartMs = pt.coerceIn(0L, max(0L, durationMs - visibleDurationMs))
                invalidateCache()
                requestVisibleChunks()
                // 视口变化后需要全屏重绘
                lastPlayheadX = newX
                invalidate()
            } else {
                // 视口未变化，使用局部刷新优化性能
                if (oldX != newX) {
                    // 播放头宽度假设为 4dp，加上阴影或缓冲，取左右 10px 范围
                    val padding = 10f
                    val dirtyRect = android.graphics.Rect(
                        (minOf(oldX, newX) - padding).toInt().coerceAtLeast(0),
                        0,
                        (maxOf(oldX, newX) + padding).toInt().coerceAtMost(width),
                        height
                    )
                    
                    lastPlayheadX = newX
                    
                    // 关键：只刷新受影响的矩形区域
                    if (dirtyRect.left < dirtyRect.right) {
                        invalidate(dirtyRect)
                    } else {
                        invalidate()
                    }
                }
            }
        }
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

    /**
     * 重新触发可见区域的 chunk 加载请求
     * 用于在回调设置完成后重新加载已缓存的数据
     */
    fun refreshVisibleChunks() {
        if (isInitialized && totalChunks > 0) {
            // 重置请求计数，允许重新请求
            chunkRequestedSamples = IntArray(totalChunks)
            // 触发可见区域 chunk 加载
            requestVisibleChunks()
        }
    }

    // ==================== 公共 API - 振幅缩放 ====================

    /** 放大振幅（每次 ×1.25） */
    fun zoomInAmplitude() {
        amplitudeScale = (amplitudeScale * 1.25f).coerceAtMost(4.0f)
        invalidateCache()
        invalidate()
    }

    /** 缩小振幅（每次 ×0.8） */
    fun zoomOutAmplitude() {
        amplitudeScale = (amplitudeScale * 0.8f).coerceAtLeast(0.2f)
        invalidateCache()
        invalidate()
    }

    /** 重置振幅缩放为默认值 */
    fun resetAmplitudeScale() {
        amplitudeScale = 1.0f
        invalidateCache()
        invalidate()
    }

    // ==================== 公共 API - 显示模式 ====================

    /**
     * 切换显示模式（波形 / 频谱）
     */
    fun setDisplayMode(mode: DisplayMode) {
        if (displayMode == mode) return
        displayMode = mode
        invalidateCache()   // 波形缓存在频谱模式下不需要，切回时需重建
        invalidate()
    }
    fun getDisplayMode(): DisplayMode = displayMode

    /**
     * 注入某个 chunk 的频谱图 Bitmap（主线程调用）
     */
    fun updateSpectrogramChunk(chunkIndex: Int, bmp: Bitmap) {
        if (chunkIndex !in 0 until totalChunks) return
        spectrogramChunks[chunkIndex]?.recycle()
        spectrogramChunks[chunkIndex] = bmp
        if (displayMode == DisplayMode.SPECTROGRAM) invalidate()
    }

    /**
     * 切换模式时重置频谱请求（允许重新请求尺寸变化后的 chunk）
     */
    fun resetSpectrogramCache() {
        spectrogramChunks.forEachIndexed { i, bmp ->
            bmp?.recycle()
            spectrogramChunks[i] = null
        }
        spectrogramRequested.clear()
        invalidate()
    }

}
