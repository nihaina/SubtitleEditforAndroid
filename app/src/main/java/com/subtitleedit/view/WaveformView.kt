package com.subtitleedit.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 波形图自定义视图
 * 显示音频波形并支持点击跳转、左右滑动和缩放
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 波形数据（振幅值，0-1 之间）
    private var waveformData: FloatArray = floatArrayOf()
    
    // 当前播放位置（0-1 之间）
    private var currentPosition: Float = 0f
    
    // 缩放级别（1.0 = 显示全部，>1.0 = 放大）
    private var zoomLevel: Float = 1f
    private var minZoom = 1f
    private var maxZoom = 10f
    
    // 可见区域的起始位置（0-1 之间）
    private var visibleStart: Float = 0f
    private var visibleEnd: Float = 1f
    
    // 画笔
    private val wavePaint = Paint().apply {
        color = Color.parseColor("#607D8B")
        strokeWidth = 2f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val playedWavePaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        strokeWidth = 2f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val playheadPaint = Paint().apply {
        color = Color.parseColor("#FF5722")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    // 手势检测器
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // 点击波形图跳转到对应时间
            val clickX = e.x
            val position = (visibleStart + (clickX / width.toFloat()) * (visibleEnd - visibleStart)).coerceIn(0f, 1f)
            onWaveformClickListener?.invoke(position)
            return true
        }
        
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // 左右滑动平移波形图
            val delta = distanceX / width.toFloat() * (visibleEnd - visibleStart)
            visibleStart = (visibleStart + delta).coerceIn(0f, 1f - (visibleEnd - visibleStart))
            visibleEnd = (visibleEnd + delta).coerceIn(visibleEnd - visibleStart, 1f)
            invalidate()
            return true
        }
    })
    
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newZoom = (zoomLevel * scaleFactor).coerceIn(minZoom, maxZoom)
            
            if (newZoom != zoomLevel) {
                val oldVisibleWidth = visibleEnd - visibleStart
                val newVisibleWidth = 1f / newZoom
                val center = (visibleStart + visibleEnd) / 2f
                
                visibleStart = (center - newVisibleWidth / 2f).coerceIn(0f, 1f - newVisibleWidth)
                visibleEnd = (visibleStart + newVisibleWidth).coerceIn(newVisibleWidth, 1f)
                zoomLevel = newZoom
                invalidate()
            }
            return true
        }
    })
    
    // 波形点击监听器
    var onWaveformClickListener: ((position: Float) -> Unit)? = null
    
    // 缩放变化监听器
    var onZoomLevelChangedListener: ((Float) -> Unit)? = null
    
    /**
     * 设置波形数据
     * @param amplitudes 振幅数组，每个值在 0-1 之间
     */
    fun setWaveformData(amplitudes: FloatArray) {
        waveformData = amplitudes
        invalidate()
    }
    
    /**
     * 设置当前播放位置
     * @param position 0-1 之间的值，表示播放进度
     */
    fun setCurrentPosition(position: Float) {
        currentPosition = position.coerceIn(0f, 1f)
        // 如果播放头不在可见区域内，滚动到播放头位置
        if (currentPosition < visibleStart || currentPosition > visibleEnd) {
            val visibleWidth = visibleEnd - visibleStart
            visibleStart = (currentPosition - visibleWidth / 2f).coerceIn(0f, 1f - visibleWidth)
            visibleEnd = (visibleStart + visibleWidth).coerceIn(visibleWidth, 1f)
        }
        invalidate()
    }
    
    /**
     * 设置缩放级别
     * @param zoom 缩放级别（1.0 = 显示全部，>1.0 = 放大）
     */
    fun setZoomLevel(zoom: Float) {
        zoomLevel = zoom.coerceIn(minZoom, maxZoom)
        val visibleWidth = 1f / zoomLevel
        visibleStart = 0f
        visibleEnd = visibleWidth.coerceIn(0f, 1f)
        onZoomLevelChangedListener?.invoke(zoomLevel)
        invalidate()
    }
    
    /**
     * 获取当前缩放级别
     */
    fun getZoomLevel(): Float = zoomLevel
    
    /**
     * 生成模拟波形数据（用于 UI 展示）
     * @param sampleCount 采样点数量
     */
    fun generateMockWaveform(sampleCount: Int = 200) {
        val amplitudes = FloatArray(sampleCount)
        var phase = 0f
        
        for (i in 0 until sampleCount) {
            // 使用多个正弦波叠加模拟真实音频波形
            val base = kotlin.math.sin(phase).toFloat() * 0.5f
            val harmonic1 = kotlin.math.sin(phase * 2.3f).toFloat() * 0.3f
            val harmonic2 = kotlin.math.sin(phase * 0.7f).toFloat() * 0.2f
            
            // 添加随机变化
            val noise = (Math.random().toFloat() - 0.5f) * 0.3f
            
            amplitudes[i] = (base + harmonic1 + harmonic2 + noise).coerceIn(-1f, 1f)
            phase += 0.1f + Math.random().toFloat() * 0.05f
        }
        
        waveformData = amplitudes
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (waveformData.isEmpty()) {
            // 没有数据时显示占位符
            drawPlaceholder(canvas)
            return
        }
        
        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2
        
        // 计算可见区域内的采样点
        val visibleSampleCount = waveformData.size * (visibleEnd - visibleStart)
        val startSampleIndex = (waveformData.size * visibleStart).toInt()
        val endSampleIndex = (waveformData.size * visibleEnd).toInt()
        
        // 计算每个采样点的宽度（基于可见区域）
        val sampleWidth = width / visibleSampleCount
        
        // 计算播放头在可见区域内的位置
        val playheadX = if (visibleEnd > visibleStart) {
            ((currentPosition - visibleStart) / (visibleEnd - visibleStart)) * width
        } else {
            currentPosition * width
        }.coerceIn(0f, width)
        
        // 绘制已播放部分（左侧）
        val leftPath = Path()
        val rightPathLeft = Path()
        
        var x = 0f
        var i = startSampleIndex
        while (x < playheadX && i < endSampleIndex) {
            val amplitude = waveformData[i] * (height / 2 - 4)
            
            if (x == 0f) {
                leftPath.moveTo(x, centerY - amplitude)
                rightPathLeft.moveTo(x, centerY + amplitude)
            } else {
                leftPath.lineTo(x, centerY - amplitude)
                rightPathLeft.lineTo(x, centerY + amplitude)
            }
            
            x += sampleWidth
            i++
        }
        
        // 闭合已播放部分的路径
        if (i > startSampleIndex) {
            leftPath.lineTo(playheadX, centerY)
            rightPathLeft.lineTo(playheadX, centerY)
            
            canvas.drawPath(leftPath, playedWavePaint)
            canvas.drawPath(rightPathLeft, playedWavePaint)
        }
        
        // 绘制未播放部分（右侧）
        val rightPath = Path()
        val rightPathBottom = Path()
        
        x = playheadX
        while (x < width && i < endSampleIndex) {
            val amplitude = waveformData[i] * (height / 2 - 4)
            
            if (x == playheadX) {
                rightPath.moveTo(x, centerY - amplitude)
                rightPathBottom.moveTo(x, centerY + amplitude)
            } else {
                rightPath.lineTo(x, centerY - amplitude)
                rightPathBottom.lineTo(x, centerY + amplitude)
            }
            
            x += sampleWidth
            i++
        }
        
        // 闭合未播放部分的路径
        if (i >= endSampleIndex - 1) {
            rightPath.lineTo(width, centerY)
            rightPathBottom.lineTo(width, centerY)
            
            canvas.drawPath(rightPath, wavePaint)
            canvas.drawPath(rightPathBottom, wavePaint)
        }
        
        // 绘制播放头
        canvas.drawLine(playheadX, 0f, playheadX, height, playheadPaint)
    }
    
    private fun drawPlaceholder(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2
        
        // 绘制占位符波形
        val placeholderPaint = Paint().apply {
            color = Color.parseColor("#E0E0E0")
            strokeWidth = 2f
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        val path = Path()
        val bottomPath = Path()
        
        val segments = 50
        val segmentWidth = width / segments
        
        for (i in 0 until segments) {
            val x = i * segmentWidth
            val amplitude = (Math.sin(i * 0.3) * 10 + 5).toFloat()
            
            if (i == 0) {
                path.moveTo(x, centerY - amplitude)
                bottomPath.moveTo(x, centerY + amplitude)
            } else {
                path.lineTo(x, centerY - amplitude)
                bottomPath.lineTo(x, centerY + amplitude)
            }
        }
        
        path.lineTo(width, centerY)
        bottomPath.lineTo(width, centerY)
        
        canvas.drawPath(path, placeholderPaint)
        canvas.drawPath(bottomPath, placeholderPaint)
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 将触摸事件传递给手势检测器
        gestureDetector.onTouchEvent(event)
        scaleGestureDetector.onTouchEvent(event)
        return true
    }
}
