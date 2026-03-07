package com.subtitleedit.view

import android.content.Context
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
import kotlin.math.max
import kotlin.math.min

/**
 * 统一的波形时间轴视图
 * 包含波形、字幕块、播放头、时间刻度
 * 
 * 布局结构（从上到下）：
 * - 时间刻度区域 (顶部 10%)
 * - 波形区域 (中间 55%)
 * - 字幕轨道区域 (底部 35%)
 */
class WaveformTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== 数据结构 ====================
    
    /**
     * 拖拽模式
     */
    enum class DragMode {
        NONE,
        MOVE,
        RESIZE_START,
        RESIZE_END
    }

    // ==================== 核心变量 ====================
    
    // 字幕列表
    private var subtitles: MutableList<SubtitleEntry> = mutableListOf()
    
    // 音频总时长（毫秒）
    private var durationMs: Long = 0
    
    // 可见区域的起始时间和持续时间（毫秒）
    private var visibleStartMs: Long = 0
    private var visibleDurationMs: Long = 10000  // 默认显示 10 秒
    
    // 波形数据
    private var waveformData: FloatArray = floatArrayOf()
    private var isLoading: Boolean = true
    
    // 当前播放位置（0-1 之间）
    private var currentPosition: Float = 0f
    
    // 拖拽相关
    private var dragMode = DragMode.NONE
    private var currentSubtitle: SubtitleEntry? = null
    private var dragStartX: Float = 0f
    private var dragStartStartTime: Long = 0L
    private var dragStartEndTime: Long = 0L
    
    // 缩放相关
    private var zoomLevel: Float = 1f
    private val minZoom = 1f
    private val maxZoom = 3000f
    
    // 选中状态
    private var selectedIndices: Set<Int> = emptySet()
    
    // 点击监听器
    private var onTimelineClickListener: ((Float) -> Unit)? = null
    private var onSubtitleChangeListener: ((List<SubtitleEntry>) -> Unit)? = null
    
    // ==================== 画笔 ====================
    
    // 波形画笔
    private val waveformPaint = Paint().apply {
        color = Color.parseColor("#4FC3F7")
        strokeWidth = 2f
        isAntiAlias = true
    }
    
    // 时间刻度画笔
    private val timeRulerPaint = Paint().apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 24f
        isAntiAlias = true
    }
    
    // 字幕块画笔
    private val subtitlePaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        isAntiAlias = true
    }
    
    private val selectedSubtitlePaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        isAntiAlias = true
    }
    
    // 字幕文本画笔
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        isFakeBoldText = true
    }
    
    // 播放头画笔
    private val playheadPaint = Paint().apply {
        color = Color.parseColor("#FF5722")
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    // 加载动画画笔
    private val loadingPaint = Paint().apply {
        color = Color.parseColor("#9E9E9E")
        isAntiAlias = true
    }
    
    // ==================== 手势检测 ====================
    
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newZoom = (zoomLevel * scaleFactor).coerceIn(minZoom, maxZoom)
            if (newZoom != zoomLevel) {
                zoomLevel = newZoom
                updateVisibleDuration()
                invalidate()
            }
            return true
        }
    })
    
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // 点击时间轴跳转
            val clickTime = xToTime(e.x)
            onTimelineClickListener?.invoke(clickTime.toFloat() / durationMs.toFloat())
            return true
        }
    })
    
    // ==================== 初始化 ====================
    
    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
    
    // ==================== 时间 ↔ 坐标转换 ====================
    
    /**
     * 时间转 X 坐标
     */
    private fun timeToX(timeMs: Long): Float {
        if (visibleDurationMs <= 0) return 0f
        return ((timeMs - visibleStartMs) / visibleDurationMs.toFloat() * width).coerceAtLeast(0f)
    }
    
    /**
     * X 坐标转时间
     */
    private fun xToTime(x: Float): Long {
        if (width <= 0) return 0L
        return (visibleStartMs + x / width * visibleDurationMs).toLong().coerceAtLeast(0L)
    }
    
    // ==================== 更新可见区域 ====================
    
    private fun updateVisibleDuration() {
        visibleDurationMs = max(1000L, (durationMs / zoomLevel).toLong())
        // 确保 visibleStartMs + visibleDurationMs 不超过 durationMs
        if (visibleStartMs + visibleDurationMs > durationMs) {
            visibleStartMs = max(0L, durationMs - visibleDurationMs)
        }
    }
    
    // ==================== 绘制方法 ====================
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width <= 0 || height <= 0) return
        
        if (isLoading) {
            drawLoadingSpinner(canvas)
            return
        }
        
        val height = height.toFloat()
        
        // 计算各区域高度
        val timeRulerHeight = height * 0.1f
        val waveformHeight = height * 0.55f
        val subtitleTrackHeight = height * 0.35f
        
        // 绘制时间刻度
        drawTimeRuler(canvas, timeRulerHeight)
        
        // 绘制波形
        drawWaveform(canvas, timeRulerHeight, waveformHeight)
        
        // 绘制字幕块
        drawSubtitles(canvas, timeRulerHeight + waveformHeight, subtitleTrackHeight)
        
        // 绘制播放头
        drawPlayhead(canvas, height)
    }
    
    /**
     * 绘制时间刻度
     */
    private fun drawTimeRuler(canvas: Canvas, rulerHeight: Float) {
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), rulerHeight)
        
        // 绘制背景
        val bgPaint = Paint().apply {
            color = Color.parseColor("#1A1A1A")
        }
        canvas.drawRect(0f, 0f, width.toFloat(), rulerHeight, bgPaint)
        
        // 计算刻度间隔
        val visibleMinutes = visibleDurationMs / 60000f
        val numMarks = when {
            visibleMinutes <= 0.5 -> 10  // 显示秒
            visibleMinutes <= 1 -> 5
            visibleMinutes <= 5 -> 10
            visibleMinutes <= 10 -> 5
            else -> 10
        }
        
        val interval = visibleDurationMs / numMarks
        
        for (i in 0..numMarks) {
            val time = visibleStartMs + i * interval
            val x = timeToX(time)
            
            // 绘制刻度线
            canvas.drawLine(x, rulerHeight * 0.3f, x, rulerHeight, timeRulerPaint)
            
            // 绘制时间标签
            val timeLabel = formatTimeLabel(time)
            canvas.drawText(timeLabel, x + 4, rulerHeight * 0.8f, timeRulerPaint)
        }
        
        canvas.restore()
    }
    
    /**
     * 格式化时间标签
     */
    private fun formatTimeLabel(timeMs: Long): String {
        val visibleMinutes = visibleDurationMs / 60000f
        return when {
            visibleMinutes <= 0.5 -> {
                // 显示秒和毫秒
                val seconds = (timeMs % 60000) / 1000f
                String.format("%.1fs", seconds)
            }
            visibleMinutes <= 5 -> {
                // 显示分：秒
                val minutes = timeMs / 60000
                val seconds = (timeMs % 60000) / 1000
                String.format("%d:%02d", minutes, seconds)
            }
            else -> {
                // 显示分：秒
                val minutes = timeMs / 60000
                val seconds = (timeMs % 60000) / 1000
                String.format("%d:%02d", minutes, seconds)
            }
        }
    }
    
    /**
     * 绘制波形
     */
    private fun drawWaveform(canvas: Canvas, yOffset: Float, waveHeight: Float) {
        if (waveformData.isEmpty()) return
        
        canvas.save()
        canvas.clipRect(0f, yOffset, width.toFloat(), yOffset + waveHeight)
        
        // 绘制背景
        val bgPaint = Paint().apply {
            color = Color.parseColor("#262626")
        }
        canvas.drawRect(0f, yOffset, width.toFloat(), yOffset + waveHeight, bgPaint)
        
        val centerY = yOffset + waveHeight / 2
        val amplitude = waveHeight / 2 * 0.9f
        
        // 计算可见区域的波形采样点
        val startSample = (visibleStartMs.toFloat() / durationMs.toFloat() * waveformData.size).toInt().coerceIn(0, waveformData.size - 1)
        val endSample = ((visibleStartMs + visibleDurationMs).toFloat() / durationMs.toFloat() * waveformData.size).toInt().coerceIn(0, waveformData.size)
        
        if (endSample <= startSample) {
            canvas.restore()
            return
        }
        
        // 绘制波形
        val stepX = width.toFloat() / (endSample - startSample)
        
        for (i in startSample until endSample) {
            val x = (i - startSample) * stepX
            val amplitudeValue = waveformData[i] * amplitude
            
            // 绘制垂直线
            canvas.drawLine(x, centerY - amplitudeValue, x, centerY + amplitudeValue, waveformPaint)
        }
        
        canvas.restore()
    }
    
    /**
     * 绘制字幕块
     */
    private fun drawSubtitles(canvas: Canvas, yOffset: Float, trackHeight: Float) {
        if (subtitles.isEmpty()) return
        
        canvas.save()
        canvas.clipRect(0f, yOffset, width.toFloat(), yOffset + trackHeight)
        
        // 绘制背景
        val bgPaint = Paint().apply {
            color = Color.parseColor("#1A1A1A")
        }
        canvas.drawRect(0f, yOffset, width.toFloat(), yOffset + trackHeight, bgPaint)
        
        val boxTop = yOffset + trackHeight * 0.15f
        val boxBottom = yOffset + trackHeight * 0.9f
        
        for ((index, subtitle) in subtitles.withIndex()) {
            // 检查是否在可见区域内
            if (subtitle.endTime < visibleStartMs || subtitle.startTime > visibleStartMs + visibleDurationMs) {
                continue
            }
            
            val x1 = timeToX(subtitle.startTime)
            val x2 = timeToX(subtitle.endTime)
            
            // 确保矩形至少有一定宽度
            val rectWidth = max(x2 - x1, 4f)
            
            // 选择画笔（选中状态使用不同颜色）
            val paint = if (index in selectedIndices) selectedSubtitlePaint else subtitlePaint
            
            // 绘制字幕块背景
            val rect = RectF(x1, boxTop, x1 + rectWidth, boxBottom)
            canvas.drawRoundRect(rect, 4f, 4f, paint)
            
            // 绘制字幕文本
            val text = subtitle.text
            if (text.isNotEmpty()) {
                val textWidth = textPaint.measureText(text)
                val textX = if (textWidth > rectWidth - 20) {
                    // 文本太长，裁剪显示
                    x1 + 10
                } else {
                    x1 + 10
                }
                val textY = boxTop + (boxBottom - boxTop) / 2 + 10
                
                if (textWidth > rectWidth - 20) {
                    val clippedText = clipText(text, rectWidth - 20)
                    canvas.drawText(clippedText, textX, textY, textPaint)
                } else {
                    canvas.drawText(text, textX, textY, textPaint)
                }
            }
        }
        
        canvas.restore()
    }
    
    /**
     * 裁剪文本以适应指定宽度
     */
    private fun clipText(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        
        var result = text
        while (textPaint.measureText(result + "...") > maxWidth && result.length > 1) {
            result = result.dropLast(1)
        }
        return result + "..."
    }
    
    /**
     * 绘制播放头
     */
    private fun drawPlayhead(canvas: Canvas, viewHeight: Float) {
        if (durationMs <= 0) return
        
        val playheadTime = (durationMs * currentPosition).toLong()
        val x = timeToX(playheadTime)
        
        // 绘制播放头线
        canvas.drawLine(x, 0f, x, viewHeight, playheadPaint)
        
        // 绘制播放头三角形
        val triangleSize = 8f
        canvas.drawLine(
            x - triangleSize,
            0f,
            x,
            triangleSize,
            playheadPaint
        )
        canvas.drawLine(
            x + triangleSize,
            0f,
            x,
            triangleSize,
            playheadPaint
        )
    }
    
    /**
     * 绘制加载动画
     */
    private fun drawLoadingSpinner(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        
        loadingPaint.style = Paint.Style.STROKE
        loadingPaint.strokeWidth = 4f
        
        // 绘制加载圆环
        val radius = 40f
        canvas.drawCircle(centerX, centerY, radius, loadingPaint)
        
        // 绘制加载文字
        loadingPaint.style = Paint.Style.FILL
        loadingPaint.textSize = 32f
        loadingPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("加载中...", centerX, centerY + 50, loadingPaint)
    }
    
    // ==================== 触摸事件处理 ====================
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 处理缩放手势
        scaleGestureDetector.onTouchEvent(event)
        
        // 处理点击手势
        gestureDetector.onTouchEvent(event)
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                currentSubtitle = findSubtitle(event.x)
                currentSubtitle?.let {
                    dragMode = detectDragMode(event.x, it)
                    dragStartStartTime = it.startTime
                    dragStartEndTime = it.endTime
                }
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                val s = currentSubtitle ?: return true
                
                val currentTime = xToTime(event.x)
                val deltaTime = currentTime - xToTime(dragStartX)
                
                when (dragMode) {
                    DragMode.MOVE -> {
                        // 移动整个字幕块
                        val duration = dragStartEndTime - dragStartStartTime
                        s.startTime = (dragStartStartTime + deltaTime).coerceAtLeast(0L)
                        s.endTime = s.startTime + duration
                    }
                    DragMode.RESIZE_START -> {
                        // 调整开始时间
                        s.startTime = (dragStartStartTime + deltaTime).coerceIn(0L, s.endTime - 100L)
                    }
                    DragMode.RESIZE_END -> {
                        // 调整结束时间
                        s.endTime = (dragStartEndTime + deltaTime).coerceAtLeast(s.startTime + 100L)
                        s.endTimeModified = true
                    }
                    DragMode.NONE -> {}
                }
                
                invalidate()
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != DragMode.NONE) {
                    // 通知字幕变化
                    onSubtitleChangeListener?.invoke(subtitles.toList())
                }
                dragMode = DragMode.NONE
                currentSubtitle = null
                return true
            }
        }
        
        return super.onTouchEvent(event)
    }
    
    /**
     * 查找点击位置的字幕
     */
    private fun findSubtitle(x: Float): SubtitleEntry? {
        val height = height.toFloat()
        val timeRulerHeight = height * 0.1f
        val waveformHeight = height * 0.55f
        val subtitleTrackY = timeRulerHeight + waveformHeight
        
        // 只处理字幕轨道区域的点击
        // 检查 y 坐标是否在字幕轨道区域内（这里简化处理，不检查 y 坐标）
        
        return subtitles.firstOrNull {
            val x1 = timeToX(it.startTime)
            val x2 = timeToX(it.endTime)
            x in x1..x2
        }
    }
    
    /**
     * 检测拖拽类型
     */
    private fun detectDragMode(x: Float, subtitle: SubtitleEntry): DragMode {
        val startX = timeToX(subtitle.startTime)
        val endX = timeToX(subtitle.endTime)
        val edgeThreshold = 30f  // 边缘阈值（像素）
        
        return when {
            kotlin.math.abs(x - startX) < edgeThreshold -> DragMode.RESIZE_START
            kotlin.math.abs(x - endX) < edgeThreshold -> DragMode.RESIZE_END
            else -> DragMode.MOVE
        }
    }
    
    // ==================== 公共方法 ====================
    
    /**
     * 设置时间轴数据
     */
    fun setTimelineData(
        durationMs: Long,
        subtitles: List<SubtitleEntry>,
        waveformAmplitudes: FloatArray
    ) {
        this.durationMs = durationMs
        this.subtitles = subtitles.toMutableList()
        this.waveformData = waveformAmplitudes
        this.isLoading = false
        
        // 初始化可见区域
        visibleStartMs = 0L
        zoomLevel = 1f
        updateVisibleDuration()
        
        invalidate()
    }
    
    /**
     * 设置加载状态
     */
    fun setLoading(loading: Boolean) {
        this.isLoading = loading
        invalidate()
    }
    
    /**
     * 设置当前播放位置
     */
    fun setCurrentPosition(position: Float) {
        currentPosition = position.coerceIn(0f, 1f)
        invalidate()
    }
    
    /**
     * 设置选中状态
     */
    fun setSelectedIndices(indices: Set<Int>) {
        selectedIndices = indices
        invalidate()
    }
    
    /**
     * 设置时间轴点击监听器
     */
    fun setOnTimelineClickListener(listener: (Float) -> Unit) {
        onTimelineClickListener = listener
    }
    
    /**
     * 设置字幕变化监听器
     */
    fun setOnSubtitleChangeListener(listener: (List<SubtitleEntry>) -> Unit) {
        onSubtitleChangeListener = listener
    }
    
    /**
     * 获取当前字幕列表
     */
    fun getSubtitles(): List<SubtitleEntry> = subtitles.toList()
    
    /**
     * 跳转到指定时间
     */
    fun scrollToTime(timeMs: Long) {
        val centerTime = timeMs - visibleDurationMs / 2
        visibleStartMs = centerTime.coerceIn(0L, max(0L, durationMs - visibleDurationMs))
        invalidate()
    }
}
