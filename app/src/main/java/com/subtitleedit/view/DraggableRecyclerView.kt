package com.subtitleedit.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * 带可拖拽滚动条的 RecyclerView。
 *
 * 与 DraggableScrollView 逻辑一致，区别：
 * - RecyclerView 有公开的 stopScroll()，无需反射
 * - RecyclerView 的 onDraw canvas 是视口坐标系，无需加 scrollY 补偿
 */
class DraggableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    companion object {
        private const val THUMB_WIDTH_DP       = 8f
        private const val THUMB_MARGIN_DP      = 3f
        private const val TOUCH_ZONE_DP        = 24f
        private const val THUMB_MIN_HEIGHT_DP  = 48f
        private const val FADE_DELAY_MS        = 1200L
        private const val FADE_DURATION_MS     = 300L
    }

    private val density          = context.resources.displayMetrics.density
    private val thumbWidthPx     = THUMB_WIDTH_DP      * density
    private val thumbMarginPx    = THUMB_MARGIN_DP     * density
    private val touchZonePx      = TOUCH_ZONE_DP       * density
    private val thumbMinHeightPx = THUMB_MIN_HEIGHT_DP * density

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAA0A0A0.toInt()
    }
    private val thumbRect   = RectF()
    private val thumbRadius = thumbWidthPx / 2f

    private var thumbAlpha: Int = 0
        set(value) {
            field = value.coerceIn(0, 255)
            thumbPaint.alpha = field
            invalidate()
        }

    private var isDraggingThumb = false
    private var dragOffsetY     = 0f

    // 淡出动画
    private var fadeStartTime  = 0L
    private var fadeStartAlpha = 0

    private val fadeRunnable = object : Runnable {
        override fun run() {
            val elapsed  = System.currentTimeMillis() - fadeStartTime
            val fraction = (elapsed.toFloat() / FADE_DURATION_MS).coerceIn(0f, 1f)
            thumbAlpha   = (fadeStartAlpha * (1f - fraction)).toInt()
            if (fraction < 1f) postDelayed(this, 16)
        }
    }

    private val startFadeRunnable = Runnable {
        fadeStartTime  = System.currentTimeMillis()
        fadeStartAlpha = thumbAlpha
        post(fadeRunnable)
    }

    // ──────────────────────────────────────────────
    // 触摸事件
    // ──────────────────────────────────────────────

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && isInThumbTouchZone(ev.x)) {
            stopScroll()   // RecyclerView 公开方法，直接调用
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                if (isInThumbTouchZone(ev.x)) {
                    stopScroll()
                    isDraggingThumb = true
                    dragOffsetY = ev.y - computeThumbTop()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    showThumb()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDraggingThumb) {
                    val thumbHeight    = computeThumbHeight()
                    val trackHeight    = (height - paddingTop - paddingBottom).toFloat()
                    val maxThumbTop    = trackHeight - thumbHeight
                    val targetThumbTop = (ev.y - paddingTop - dragOffsetY).coerceIn(0f, maxThumbTop)
                    val maxScroll      = computeVerticalScrollRange() - computeVerticalScrollExtent()
                    if (maxThumbTop > 0f && maxScroll > 0) {
                        val ratio     = targetThumbTop / maxThumbTop
                        val targetPos = (ratio * maxScroll).toInt()
                        // RecyclerView 没有 scrollTo，用 scrollBy 相对移动
                        val delta = targetPos - computeVerticalScrollOffset()
                        scrollBy(0, delta)
                    }
                    showThumb()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingThumb) {
                    isDraggingThumb = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    scheduleFadeOut()
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    // ──────────────────────────────────────────────
    // 滚动变化 → 显示滚动条
    // ──────────────────────────────────────────────

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        if (dy != 0) showThumb()
    }

    private fun showThumb() {
        removeCallbacks(startFadeRunnable)
        removeCallbacks(fadeRunnable)
        thumbAlpha = 220
        if (!isDraggingThumb) {
            postDelayed(startFadeRunnable, FADE_DELAY_MS)
        }
    }

    private fun scheduleFadeOut() {
        removeCallbacks(startFadeRunnable)
        removeCallbacks(fadeRunnable)
        postDelayed(startFadeRunnable, FADE_DELAY_MS)
    }

    // ──────────────────────────────────────────────
    // 自绘滚动条
    // ──────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (thumbAlpha <= 0) return

        val scrollRange  = computeVerticalScrollRange()
        val scrollExtent = computeVerticalScrollExtent()
        if (scrollRange <= scrollExtent) return

        val thumbTop    = computeThumbTop()
        val thumbHeight = computeThumbHeight()
        // 贴 View 真正右边缘，忽略 padding，保证滚动条在屏幕最右侧
        val left  = width - thumbMarginPx - thumbWidthPx
        val right = left + thumbWidthPx
        val top    = thumbTop
        val bottom = top + thumbHeight

        thumbRect.set(left, top, right, bottom)
        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbPaint)
    }

    // ──────────────────────────────────────────────
    // 辅助计算
    // ──────────────────────────────────────────────

    private fun isInThumbTouchZone(x: Float) =
        x >= width - touchZonePx

    private fun computeThumbTop(): Float {
        val scrollRange  = computeVerticalScrollRange().toFloat()
        val scrollExtent = computeVerticalScrollExtent().toFloat()
        val scrollOffset = computeVerticalScrollOffset().toFloat()
        val trackHeight  = height.toFloat()
        val thumbHeight  = computeThumbHeight()
        val maxThumbTop  = trackHeight - thumbHeight
        return if (scrollRange > scrollExtent)
            (scrollOffset / (scrollRange - scrollExtent)) * maxThumbTop
        else 0f
    }

    private fun computeThumbHeight(): Float {
        val scrollRange  = computeVerticalScrollRange().toFloat()
        val scrollExtent = computeVerticalScrollExtent().toFloat()
        val trackHeight  = height.toFloat()
        return if (scrollRange > 0)
            (scrollExtent / scrollRange * trackHeight).coerceAtLeast(thumbMinHeightPx)
        else trackHeight
    }
}