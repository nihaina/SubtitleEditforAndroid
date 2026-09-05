package com.subtitleedit.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.DashPathEffect
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleEntryOps
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
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

        /** 高精度采样数上限（聚焦 chunk）—— 提高到 30000，支持最大缩放时保持 4x 超采样 */
        private const val HIGH_RES_SAMPLES = 30_000

        /** 最大放大：屏幕显示 2 秒 */
        private const val MIN_VISIBLE_MS = 2_000L

        /** 最大缩小：屏幕显示 10 分钟 */
        private const val MAX_VISIBLE_MS = 600_000L

        /** 默认视图：显示 3 分钟 */
        private const val DEFAULT_VISIBLE_MS = 180_000L

        /**
         * 字幕块单击的短确认延迟。
         *
         * 系统 onSingleTapConfirmed 需要等待完整双击窗口（通常约 300ms），
         * 对单点选中反馈偏慢；120ms 仍给双击手势留出取消机会，同时更接近即时响应。
         */
        private const val SINGLE_TAP_SELECTION_DELAY_MS = 120L

        /** 重新请求高精度的阈值：现有精度低于目标的 60% 时触发 */
        private const val RESAMPLE_THRESHOLD = 0.6f

        /** 频谱图每个 chunk 的固定生成宽度（像素），与缩放无关，保证放大后仍清晰 */
        private const val SPECTROGRAM_CHUNK_WIDTH = 2048

        /** 频谱生成失败后等待一段时间再重试，避免播放刷新触发紧密失败循环。 */
        private const val SPECTROGRAM_RETRY_DELAY_MS = 5_000L

        private const val MIN_SUBTITLE_DURATION_MS = 100L

        private const val MIN_SPECTROGRAM_CACHE_BYTES = 16 * 1024 * 1024
        private const val MAX_SPECTROGRAM_CACHE_BYTES = 48 * 1024 * 1024
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
    private var currentSubtitleIndex = -1
    private var dragStartX = 0f
    private var dragStartStartTime = 0L
    private var dragStartEndTime = 0L
    private var isDraggingWaveform = false
    private var dragStartVisibleStartMs = 0L
    private var downOnSelectedSubtitle = false  // ACTION_DOWN 时是否点在已选中字幕上
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastActiveTouchX = 0f
    /** 打轴由另一根手指启动时，避免原有波形触摸序列在退出打轴后继续触发拖动或点击。 */
    private var discardTouchSequenceAfterTimestamping = false
    private var isPinching = false
    private var pinchStartSpan = 0f
    private var pinchStartVisibleDurationMs = 0L
    private var pinchAnchorTimeMs = 0L
    private var suppressTapSelection = false
    private var pendingSingleTapSelection: Runnable? = null

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
    private var limitedPlaybackIndex: Int? = null

    // ==================== 振幅缩放 ====================
    /** 垂直振幅缩放倍数，默认 1.0，范围 0.2 ~ 4.0 */
    private var amplitudeScale: Float = 1.0f

    // ==================== 显示模式 ====================
    enum class DisplayMode { WAVEFORM, SPECTROGRAM }
    private var displayMode = DisplayMode.WAVEFORM

    // ==================== 频谱图分块 ====================
    /** 频谱 Bitmap 使用按字节计费的 LRU，避免浏览长媒体后无限占用堆内存。 */
    private val spectrogramChunks = object : LruCache<Int, Bitmap>(spectrogramCacheBytes()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            spectrogramRequested.remove(key)
            if (oldValue !== newValue && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }
    /** 已发起请求的 chunk 集合，避免重复请求 */
    private val spectrogramRequested = mutableSetOf<Int>()
    private var spectrogramRetryAfterMs = LongArray(0)

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
    var onSubtitleChangeListener: ((Int, SubtitleEntry) -> Unit)? = null
    var onDraggedViewportPlayheadCorrection: ((positionMs: Long) -> Unit)? = null
    var onLimitedPlaybackRangeChange: ((subtitleIndex: Int?) -> Unit)? = null
    var onLimitedPlaybackStartRequest: ((subtitleIndex: Int) -> Unit)? = null
    var onSubtitleStartSeekRequest: ((positionMs: Long) -> Unit)? = null
    var onLimitedPlaybackRangeOutOfView: (() -> Unit)? = null

    // ==================== 打轴模式 ====================

    private var isTimestampingMode = false
    private var timestampStartMs = 0L
    private var timestampAnchorX = 0f
    private var lastTouchXForTimestamp = 0f
    /** 虚拟缓冲区等于当前视口时长，使不同缩放下都可拖出完整一屏。 */
    private fun timestampVirtualPaddingMs(): Long =
        visibleDurationMs.coerceIn(250L, MAX_VISIBLE_MS)

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
        strokeWidth = 0.75f * resources.displayMetrics.density
        isAntiAlias = true
    }

    private val loadingPaint = Paint().apply {
        color = Color.parseColor("#9E9E9E")
        isAntiAlias = true
    }

    private val waveformBgPaint = Paint().apply { color = Color.parseColor("#262626") }
    private val timeRulerBgPaint = Paint().apply { color = Color.parseColor("#1A1A1A") }
    private val subtitleBgPaint = Paint().apply { color = Color.parseColor("#1A1A1A") }

    // ==================== 打轴预览画笔 ====================
    private val timestampPreviewPaint = Paint().apply {
        color = Color.argb(100, 76, 175, 80)  // 半透明绿色
        style = Paint.Style.FILL
    }
    private val timestampPreviewBorderPaint = Paint().apply {
        color = Color.argb(200, 76, 175, 80)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = false
    }
    private val limitedPlaybackPaint = Paint().apply {
        color = Color.argb(72, 244, 67, 54)
        style = Paint.Style.FILL
    }
    private val limitedPlaybackBorderPaint = Paint().apply {
        color = Color.argb(220, 244, 67, 54)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = false
    }

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

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (suppressTapSelection) {
                    suppressTapSelection = false
                    return true
                }
                scheduleSingleTap(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                cancelPendingSingleTap()
                val subtitleIndex = findSubtitleIndex(e.x, e.y)
                if (subtitleIndex >= 0) {
                    if (subtitleIndex == limitedPlaybackIndex) {
                        onLimitedPlaybackStartRequest?.invoke(subtitleIndex)
                    } else {
                        subtitles.getOrNull(subtitleIndex)?.let { subtitle ->
                            onSubtitleStartSeekRequest?.invoke(subtitle.startTime)
                        }
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // 字幕拖动和限定区间长按共用同一触摸序列。拖动已开始或已越过
                // 自定义拖动阈值时，忽略仍在等待队列中的长按回调。
                if (isTimestampingMode || isPinching || isDraggingWaveform ||
                    dragMode != DragMode.NONE || suppressTapSelection ||
                    (downOnSelectedSubtitle && abs(lastActiveTouchX - dragStartX) > 8f)) {
                    return
                }
                val subtitleIndex = findSubtitleIndex(e.x, e.y)
                if (subtitleIndex < 0) return
                limitedPlaybackIndex = if (limitedPlaybackIndex == subtitleIndex) null else subtitleIndex
                onLimitedPlaybackRangeChange?.invoke(limitedPlaybackIndex)
                invalidate()
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
        cancelPendingSingleTap()
        waveformCache?.recycle()
        waveformCache = null
        spectrogramChunks.evictAll()
        spectrogramRequested.clear()
        spectrogramRetryAfterMs = LongArray(0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    // ==================== 坐标转换 ====================

    private fun timeToX(timeMs: Long): Float {
        if (visibleDurationMs <= 0) return 0f
        return timeToXUnclamped(timeMs).coerceAtLeast(0f)
    }

    /** 用于可被画布裁剪的元素，保留离开屏幕左侧后的真实坐标。 */
    private fun timeToXUnclamped(timeMs: Long): Float {
        if (visibleDurationMs <= 0) return 0f
        return (timeMs - visibleStartMs).toFloat() / visibleDurationMs * width
    }

    private fun xToTime(x: Float): Long {
        if (width <= 0) return 0L
        return (visibleStartMs + x / width * visibleDurationMs).toLong()
            .coerceIn(0L, durationMs)
    }

    // ==================== Chunk 工具 ====================

    private fun chunkStartMs(idx: Int) = idx * CHUNK_DURATION_MS
    private fun chunkEndMs(idx: Int) = min((idx + 1) * CHUNK_DURATION_MS, durationMs)
    private fun timeToChunkIndex(ms: Long) = (ms / CHUNK_DURATION_MS).toInt()

    /**
     * 根据当前可见时长动态计算期望精度。
     * 原则：屏幕上每个像素对应 >= 2 个采样点（4x 超采样保证 peak 捕获准确）
     */
    private fun targetSamplesForZoom(): Int {
        val pixelsPerMs = width.toFloat() / visibleDurationMs
        val pixelsPerChunk = pixelsPerMs * CHUNK_DURATION_MS
        return (pixelsPerChunk * 4).toInt().coerceIn(LOW_RES_SAMPLES, HIGH_RES_SAMPLES)
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
        // 打轴预览框
        if (isTimestampingMode) {
            drawTimestampPreview(canvas, rulerH, waveH, subH)
        }
        drawLimitedPlaybackRange(canvas, rulerH, waveH, subH)
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
                if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d.%d", h, m, s, ds)
                else       String.format(Locale.getDefault(), "%d:%02d.%d", m, s, ds)
            h > 0 ->
                String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
            else ->
                String.format(Locale.getDefault(), "%d:%02d", m, s)
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

            val bmp = spectrogramChunks.get(chunkIdx)

            if (bmp == null) {
                // 占位灰块 + 触发请求（固定宽度，与缩放无关）
                canvas.drawRect(dstLeft, yOffset, dstRight, yOffset + spectH, placeholderPaint)
                if (chunkIdx !in spectrogramRequested &&
                    SystemClock.uptimeMillis() >= spectrogramRetryAfterMs.getOrElse(chunkIdx) { 0L }
                ) {
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

        // 第一遍：绘制未选中的字幕块
        for ((index, sub) in subtitles.withIndex()) {
            if (index in selectedIndices) continue
            if (sub.endTime < visibleStartMs || sub.startTime > visibleStartMs + visibleDurationMs) continue

            val x1 = timeToX(sub.startTime)
            val x2 = timeToX(sub.endTime)
            val rw = max(x2 - x1, 4f)
            canvas.drawRoundRect(RectF(x1, boxTop, x1 + rw, boxBot), 4f, 4f, subtitlePaint)

            if (sub.text.isNotEmpty()) {
                val availW = rw - 20f
                if (availW > 12f) {
                    canvas.drawText(clipText(sub.text, availW), x1 + 10f, boxTop + boxH / 2f + 8f, textPaint)
                }
            }
        }

        // 第二遍：绘制选中的字幕块（置顶）
        for (index in selectedIndices) {
            if (index < 0 || index >= subtitles.size) continue
            val sub = subtitles[index]
            if (sub.endTime < visibleStartMs || sub.startTime > visibleStartMs + visibleDurationMs) continue

            val rawStartX = timeToXUnclamped(sub.startTime)
            val x1 = timeToX(sub.startTime)
            val x2 = timeToX(sub.endTime)
            val rw = max(x2 - x1, 4f)
            val hw = HANDLE_ZONE_W.coerceAtMost(rw / 3f)
            // 左柄部分或全部离屏后，移动区从左柄的实际右边缘继续绘制，避免预留空隙。
            val moveZoneLeft = maxOf(x1, rawStartX + hw)
            val moveZoneRight = x1 + rw - hw

            // 左缩放柄使用真实坐标，离屏时由画布裁剪，不能钉在左侧边缘。
            canvas.drawRoundRect(RectF(rawStartX, boxTop, rawStartX + hw, boxBot), 4f, 4f, subtitleResizeHandlePaint)
            // 右缩放柄
            canvas.drawRoundRect(RectF(x1 + rw - hw, boxTop, x1 + rw, boxBot), 4f, 4f, subtitleResizeHandlePaint)
            // 中间移动区
            if (moveZoneRight > moveZoneLeft) {
                canvas.drawRect(RectF(moveZoneLeft, boxTop, moveZoneRight, boxBot), subtitleMoveZonePaint)
            }
            // 柄上箭头图标
            if (hw >= 14f) {
                canvas.drawText("‹", rawStartX + hw / 2f, iconY, handleIconPaint)
                canvas.drawText("›", x1 + rw - hw / 2f, iconY, handleIconPaint)
            }

            // 字幕文本
            if (sub.text.isNotEmpty()) {
                val textStartX = moveZoneLeft + 4f
                val availW = moveZoneRight - textStartX - 4f
                if (availW > 12f) {
                    canvas.drawText(clipText(sub.text, availW), textStartX, boxTop + boxH / 2f + 8f, textPaint)
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

    private fun drawTimestampPreview(canvas: Canvas, rulerH: Float, waveH: Float, subH: Float) {
        val startX = timeToX(timestampStartMs)
        val endX = timestampAnchorX
        val left = minOf(startX, endX)
        val right = maxOf(startX, endX)
        if (right - left < 2f) return

        val top = rulerH
        val bottom = rulerH + waveH + subH
        val rect = RectF(left, top, right, bottom)
        canvas.drawRect(rect, timestampPreviewPaint)
        canvas.drawRect(rect, timestampPreviewBorderPaint)
    }

    private fun drawLimitedPlaybackRange(canvas: Canvas, rulerH: Float, waveH: Float, subH: Float) {
        val index = limitedPlaybackIndex ?: return
        val subtitle = subtitles.getOrNull(index) ?: return
        val left = timeToXUnclamped(subtitle.startTime)
        val right = timeToXUnclamped(subtitle.endTime)
        if (right <= 0f || left >= width.toFloat()) return

        val rect = RectF(left, rulerH, right, rulerH + waveH + subH)
        canvas.drawRect(rect, limitedPlaybackPaint)
        canvas.drawRect(rect, limitedPlaybackBorderPaint)
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
        if (!isTimestampingMode && discardTouchSequenceAfterTimestamping) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                discardTouchSequenceAfterTimestamping = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            return true
        }

        // 打轴模式：允许视口进入音频两端的虚拟缓冲区，便于从边界向外打轴。
        if (isTimestampingMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    lastTouchXForTimestamp = event.x
                    lastActiveTouchX = event.x
                }
                MotionEvent.ACTION_MOVE -> {
                    // 多指期间不把任意一根手指的移动误判为时间轴拖动。
                    if (event.pointerCount > 1) return true
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex < 0) return true
                    val touchX = event.getX(pointerIndex)
                    val dx = touchX - lastTouchXForTimestamp
                    val dMs = (-dx / width * visibleDurationMs).toLong()
                    val virtualPaddingMs = timestampVirtualPaddingMs()
                    visibleStartMs = (visibleStartMs + dMs).coerceIn(
                        -virtualPaddingMs,
                        maxOf(0L, durationMs - visibleDurationMs) + virtualPaddingMs
                    )
                    lastTouchXForTimestamp = touchX
                    lastActiveTouchX = touchX
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    rebaseToRemainingPointer(event, timestamping = true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    discardTouchSequenceAfterTimestamping = false
                }
            }
            return true
        }

        // GestureDetector 可能在处理本次 MOVE 时立即触发长按，先记录最新坐标，
        // 让长按回调能识别同一事件中刚刚越过阈值的拖动。
        if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1) {
            val pointerIndex = event.findPointerIndex(activePointerId)
            if (pointerIndex >= 0) lastActiveTouchX = event.getX(pointerIndex)
        }
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                dragStartX = event.x
                lastActiveTouchX = event.x
                suppressTapSelection = false

                if (isInSubtitleArea(event.y)) {
                    val idx = findSubtitleIndexAtX(event.x)
                    val sub = subtitles.getOrNull(idx)
                    if (sub != null && idx >= 0) {
                        currentSubtitle = sub
                        currentSubtitleIndex = idx
                        if (idx in selectedIndices) {
                            // 已选中：DOWN 时保持 NONE，等 MOVE 时确认真正拖动再设置 dragMode
                            downOnSelectedSubtitle = true
                            dragMode = DragMode.NONE
                            dragStartStartTime = sub.startTime
                            dragStartEndTime = sub.endTime
                        } else {
                            downOnSelectedSubtitle = false
                            dragMode = DragMode.NONE
                        }
                    } else {
                        downOnSelectedSubtitle = false
                        currentSubtitle = null
                        currentSubtitleIndex = -1
                    }
                    isDraggingWaveform = false
                } else {
                    downOnSelectedSubtitle = false
                    isDraggingWaveform = true
                    dragStartVisibleStartMs = visibleStartMs
                    dragMode = DragMode.NONE
                    currentSubtitle = null
                    currentSubtitleIndex = -1
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                suppressTapSelection = true
                if (event.pointerCount >= 2) beginPinch(event)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                isPinching = false
                rebaseToRemainingPointer(event, timestamping = false)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    if (!isPinching) beginPinch(event)
                    updatePinch(event)
                    return true
                }
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return true
                val touchX = event.getX(pointerIndex)
                lastActiveTouchX = touchX
                val dx = touchX - dragStartX

                if (isDraggingWaveform) {
                    val dt = (dx / width * visibleDurationMs).toLong()
                    val newStart = (dragStartVisibleStartMs - dt)
                        .coerceIn(0L, max(0L, durationMs - visibleDurationMs))
                    if (newStart != visibleStartMs) {
                        visibleStartMs = newStart
                        correctPlayheadAfterViewportDrag()
                        notifyIfLimitedPlaybackRangeOutOfView()
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
                        suppressTapSelection = true
                    } else {
                        return true  // 位移不够，还不是拖拽
                    }
                }

                if (dragMode == DragMode.NONE || currentSubtitle == null) return true
                val s = currentSubtitle!!
                val dt = xToTime(touchX) - xToTime(dragStartX)
                val previous = subtitles.getOrNull(currentSubtitleIndex - 1)
                val next = subtitles.getOrNull(currentSubtitleIndex + 1)

                var changed = false
                when (dragMode) {
                    DragMode.MOVE -> {
                        val range = SubtitleEntryOps.clampMoveToNeighbors(
                            originalStartTime = dragStartStartTime,
                            originalEndTime = dragStartEndTime,
                            desiredStartTime = dragStartStartTime + dt,
                            previousEndTime = previous?.endTime,
                            nextStartTime = next?.startTime
                        )
                        s.startTime = range.startTime
                        s.endTime = range.endTime
                        changed = true
                    }
                    DragMode.RESIZE_START -> {
                        s.startTime = SubtitleEntryOps.clampStartToNeighbors(
                            originalStartTime = dragStartStartTime,
                            currentEndTime = s.endTime,
                            desiredStartTime = dragStartStartTime + dt,
                            previousEndTime = previous?.endTime,
                            minimumDurationMs = MIN_SUBTITLE_DURATION_MS
                        )
                        changed = true
                    }
                    DragMode.RESIZE_END -> {
                        s.endTime = SubtitleEntryOps.clampEndToNeighbors(
                            originalEndTime = dragStartEndTime,
                            currentStartTime = s.startTime,
                            desiredEndTime = dragStartEndTime + dt,
                            nextStartTime = next?.startTime,
                            minimumDurationMs = MIN_SUBTITLE_DURATION_MS
                        )
                        s.endTimeModified = true
                        changed = true
                    }
                    DragMode.NONE -> {}
                }
                if (changed) {
                    onSubtitleChangeListener?.invoke(currentSubtitleIndex, s.copy())
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != DragMode.NONE && currentSubtitleIndex >= 0) {
                    subtitles.getOrNull(currentSubtitleIndex)?.let { subtitle ->
                        onSubtitleChangeListener?.invoke(currentSubtitleIndex, subtitle.copy())
                    }
                }

                downOnSelectedSubtitle = false
                dragMode = DragMode.NONE
                currentSubtitle = null
                currentSubtitleIndex = -1
                isDraggingWaveform = false
                isPinching = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 用户拖动视口使播放头离开屏幕时，将播放头强制移到当前视口左边缘。 */
    private fun correctPlayheadAfterViewportDrag() {
        if (durationMs <= 0L) return
        val playheadMs = (durationMs * currentPosition).toLong()
        val visibleEndMs = visibleStartMs + visibleDurationMs
        if (playheadMs >= visibleStartMs && playheadMs <= visibleEndMs) return

        val correctedPositionMs = visibleStartMs.coerceIn(0L, durationMs)
        currentPosition = correctedPositionMs.toFloat() / durationMs
        onDraggedViewportPlayheadCorrection?.invoke(correctedPositionMs)
    }

    private fun notifyIfLimitedPlaybackRangeOutOfView() {
        val index = limitedPlaybackIndex ?: return
        val subtitle = subtitles.getOrNull(index) ?: return
        val visibleEndMs = visibleStartMs + visibleDurationMs
        if (subtitle.endTime < visibleStartMs || subtitle.startTime > visibleEndMs) {
            onLimitedPlaybackRangeOutOfView?.invoke()
        }
    }

    private fun beginPinch(event: MotionEvent) {
        if (event.pointerCount < 2 || width <= 0) return
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        pinchStartSpan = hypot(dx, dy).coerceAtLeast(1f)
        pinchStartVisibleDurationMs = visibleDurationMs
        val focusX = (event.getX(0) + event.getX(1)) / 2f
        pinchAnchorTimeMs = xToTime(focusX)
        isPinching = true
    }

    private fun updatePinch(event: MotionEvent) {
        if (!isPinching || event.pointerCount < 2 || width <= 0) return
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        val currentSpan = hypot(dx, dy).coerceAtLeast(1f)
        val scaleFactor = currentSpan / pinchStartSpan
        val newDuration = (pinchStartVisibleDurationMs / scaleFactor)
            .toLong()
            .coerceIn(MIN_VISIBLE_MS, MAX_VISIBLE_MS)

        val focusX = (event.getX(0) + event.getX(1)) / 2f
        val newStart = (pinchAnchorTimeMs - focusX / width * newDuration)
            .toLong()
            .coerceIn(0L, max(0L, durationMs - newDuration))
        if (newDuration == visibleDurationMs && newStart == visibleStartMs) return

        visibleDurationMs = newDuration
        visibleStartMs = newStart
        invalidateCache()
        requestVisibleChunks()
        invalidate()
    }

    /**
     * 多指切回单指时，将当前位置设为新的拖动基准。
     * MotionEvent 的 pointer index 会在手指抬起后重新排列，不能继续沿用旧的 event.x 基准。
     */
    private fun rebaseToRemainingPointer(event: MotionEvent, timestamping: Boolean) {
        val liftedIndex = event.actionIndex
        val liftedPointerId = event.getPointerId(liftedIndex)

        var remainingIndex = if (activePointerId != liftedPointerId) {
            event.findPointerIndex(activePointerId)
        } else {
            -1
        }
        if (remainingIndex < 0 || remainingIndex == liftedIndex) {
            remainingIndex = (0 until event.pointerCount).firstOrNull { it != liftedIndex } ?: -1
        }

        if (remainingIndex < 0) {
            activePointerId = MotionEvent.INVALID_POINTER_ID
            return
        }

        activePointerId = event.getPointerId(remainingIndex)
        val remainingX = event.getX(remainingIndex)
        lastActiveTouchX = remainingX
        if (timestamping) {
            lastTouchXForTimestamp = remainingX
            return
        }

        dragStartX = remainingX
        dragStartVisibleStartMs = visibleStartMs
        currentSubtitle?.let { subtitle ->
            dragStartStartTime = subtitle.startTime
            dragStartEndTime = subtitle.endTime
        }
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

    private fun scheduleSingleTap(x: Float, y: Float) {
        cancelPendingSingleTap()
        val action = Runnable {
            pendingSingleTapSelection = null
            val subtitleIndex = findSubtitleIndex(x, y)
            if (subtitleIndex >= 0) {
                if (subtitleIndex in selectedIndices) clearSelection() else selectSubtitle(subtitleIndex)
            } else {
                if (isInSubtitleArea(y)) clearSelection()
                val t = xToTime(x)
                onTimelineClickListener?.invoke(t.toFloat() / durationMs)
            }
        }
        pendingSingleTapSelection = action
        postDelayed(action, SINGLE_TAP_SELECTION_DELAY_MS)
    }

    private fun cancelPendingSingleTap() {
        pendingSingleTapSelection?.let(::removeCallbacks)
        pendingSingleTapSelection = null
    }

    private fun findSubtitleIndexAtX(x: Float): Int {
        // 选中项绘制在最上层，命中时也应优先。
        for (index in selectedIndices) {
            val subtitle = subtitles.getOrNull(index) ?: continue
            if (x in timeToXUnclamped(subtitle.startTime)..timeToXUnclamped(subtitle.endTime)) {
                return index
            }
        }

        for (index in subtitles.lastIndex downTo 0) {
            if (index in selectedIndices) continue
            val subtitle = subtitles[index]
            if (x in timeToX(subtitle.startTime)..timeToX(subtitle.endTime)) return index
        }
        return -1
    }

    private fun findSubtitleIndex(x: Float, y: Float): Int {
        if (!isInSubtitleArea(y)) return -1
        return findSubtitleIndexAtX(x)
    }

    private fun detectDragMode(x: Float, sub: SubtitleEntry): DragMode {
        val sx = timeToXUnclamped(sub.startTime); val ex = timeToXUnclamped(sub.endTime)
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
        this.limitedPlaybackIndex = null
        onLimitedPlaybackRangeChange?.invoke(null)
        this.totalChunks = ((durationMs + CHUNK_DURATION_MS - 1) / CHUNK_DURATION_MS).toInt()
        this.chunkData = Array(totalChunks) { null }
        this.chunkRequestedSamples = IntArray(totalChunks)
        this.spectrogramChunks.evictAll()
        this.spectrogramRequested.clear()
        this.spectrogramRetryAfterMs = LongArray(totalChunks)
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
        subtitles = list.toMutableList()
        selectedIndices = emptySet()
        if (limitedPlaybackIndex?.let { it !in subtitles.indices } == true) {
            limitedPlaybackIndex = null
            onLimitedPlaybackRangeChange?.invoke(null)
        }
        post { invalidate() }
    }

    /** Refresh subtitle data without dropping the block currently selected on the timeline. */
    fun setSubtitlesPreserveSelection(list: List<SubtitleEntry>) {
        val previousSelection = selectedIndices
        val previousLimitedPlayback = limitedPlaybackIndex
        subtitles = list.toMutableList()
        selectedIndices = selectedIndices.filter { it in subtitles.indices }.toSet()
        limitedPlaybackIndex = limitedPlaybackIndex?.takeIf { it in subtitles.indices }
        if (previousLimitedPlayback != null && limitedPlaybackIndex == null) {
            onLimitedPlaybackRangeChange?.invoke(null)
        }
        if (selectedIndices != previousSelection || limitedPlaybackIndex != previousLimitedPlayback) {
            onSelectedIndicesChangeListener?.invoke(selectedIndices)
        }
        post { invalidate() }
    }

    fun setSubtitlesKeepSelection(list: List<SubtitleEntry>, insertedAt: Int) {
        subtitles = list.toMutableList()
        // 插入位置在选中索引之前或等于时，选中索引 +1
        selectedIndices = selectedIndices.map { if (it >= insertedAt) it + 1 else it }.toSet()
        limitedPlaybackIndex = limitedPlaybackIndex?.let { if (it >= insertedAt) it + 1 else it }
        onSelectedIndicesChangeListener?.invoke(selectedIndices)
        post { invalidate() }
    }

    /**
     * 删除字幕后保持选中状态
     * @param list 新的字幕列表
     * @param deletedIndices 被删除的索引集合（删除前的索引）
     */
    fun setSubtitlesAfterDelete(list: List<SubtitleEntry>, deletedIndices: Set<Int>) {
        subtitles = list.toMutableList()
        // 移除被删除的索引，并调整剩余索引
        val sortedDeleted = deletedIndices.sorted()
        selectedIndices = selectedIndices.mapNotNull { idx ->
            if (idx in deletedIndices) {
                null  // 被删除的索引移除
            } else {
                // 计算有多少个被删除的索引在当前索引之前
                val offset = sortedDeleted.count { it < idx }
                idx - offset
            }
        }.toSet()
        limitedPlaybackIndex = limitedPlaybackIndex?.let { index ->
            if (index in deletedIndices) {
                onLimitedPlaybackRangeChange?.invoke(null)
                null
            } else {
                index - sortedDeleted.count { it < index }
            }
        }
        onSelectedIndicesChangeListener?.invoke(selectedIndices)
        post { invalidate() }
    }

    // ==================== 打轴模式 ====================

    fun startTimestamping(startMs: Long) {
        cancelPendingSingleTap()
        discardTouchSequenceAfterTimestamping = activePointerId != MotionEvent.INVALID_POINTER_ID
        if (discardTouchSequenceAfterTimestamping) {
            lastTouchXForTimestamp = lastActiveTouchX
        }
        downOnSelectedSubtitle = false
        dragMode = DragMode.NONE
        currentSubtitle = null
        currentSubtitleIndex = -1
        isDraggingWaveform = false
        isPinching = false
        isTimestampingMode = true
        timestampStartMs = startMs
        timestampAnchorX = timeToX(startMs)
        invalidate()
    }

    fun stopTimestamping(): Long {
        isTimestampingMode = false
        val endMs = xToTime(timestampAnchorX)
        // 虚拟缓冲区仅用于打轴手势，退出后恢复普通视口边界。
        val normalMaxStart = maxOf(0L, durationMs - visibleDurationMs)
        if (visibleStartMs !in 0L..normalMaxStart) {
            visibleStartMs = visibleStartMs.coerceIn(0L, normalMaxStart)
            invalidateCache()
            requestVisibleChunks()
        }
        invalidate()
        return endMs
    }

    fun isInTimestampingMode() = isTimestampingMode

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
        amplitudeScale = (amplitudeScale * 1.25f).coerceAtMost(10.0f)
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

    /** 返回当前频谱缓存文件应使用的固定尺寸；视图未完成布局时返回 null。 */
    fun getSpectrogramCacheDimensions(): Pair<Int, Int>? {
        if (height <= 0) return null
        return SPECTROGRAM_CHUNK_WIDTH to (height * 0.67f).toInt().coerceAtLeast(64)
    }

    /**
     * 注入某个 chunk 的频谱图 Bitmap（主线程调用）
     */
    fun updateSpectrogramChunk(chunkIndex: Int, bmp: Bitmap): Boolean {
        if (chunkIndex !in 0 until totalChunks) {
            bmp.recycle()
            return false
        }
        val dimensions = getSpectrogramCacheDimensions()
        if (dimensions == null || bmp.width != dimensions.first || bmp.height != dimensions.second) {
            bmp.recycle()
            spectrogramRequested.remove(chunkIndex)
            spectrogramRetryAfterMs[chunkIndex] = 0L
            invalidate()
            return false
        }
        spectrogramRetryAfterMs[chunkIndex] = 0L
        spectrogramChunks.put(chunkIndex, bmp)
        if (displayMode == DisplayMode.SPECTROGRAM) invalidate()
        return true
    }

    fun markSpectrogramChunkFailed(chunkIndex: Int) {
        if (chunkIndex !in 0 until totalChunks) return
        spectrogramRequested.remove(chunkIndex)
        spectrogramRetryAfterMs[chunkIndex] =
            SystemClock.uptimeMillis() + SPECTROGRAM_RETRY_DELAY_MS
        postDelayed({
            if (chunkIndex in 0 until totalChunks &&
                spectrogramChunks.get(chunkIndex) == null &&
                displayMode == DisplayMode.SPECTROGRAM
            ) {
                invalidate()
            }
        }, SPECTROGRAM_RETRY_DELAY_MS)
    }

    /**
     * 切换模式时重置频谱请求（允许重新请求尺寸变化后的 chunk）
     */
    fun resetSpectrogramCache() {
        spectrogramChunks.evictAll()
        spectrogramRequested.clear()
        spectrogramRetryAfterMs.fill(0L)
        invalidate()
    }

    private fun spectrogramCacheBytes(): Int {
        val target = Runtime.getRuntime().maxMemory() / 8L
        return target.coerceIn(
            MIN_SPECTROGRAM_CACHE_BYTES.toLong(),
            MAX_SPECTROGRAM_CACHE_BYTES.toLong()
        ).toInt()
    }

}
