package com.subtitleedit.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * 带可拖拽滚动条的 ScrollView。
 *
 * 特性：
 * - 右侧触摸区比视觉滚动条更宽（TOUCH_ZONE_DP），保证易于点中
 * - 手指落入触摸区时立即终止惯性（反射 mScroller.abortAnimation），优先级最高
 * - 拦截发生在 onInterceptTouchEvent，惯性动画期间也能即时响应
 * - 拖拽期间 requestDisallowInterceptTouchEvent 防止父布局抢事件
 * - 自绘滚动条补偿 ScrollView canvas 的 scrollY 平移，确保 thumb 位置固定在屏幕上
 */
class DraggableScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    companion object {
        /** 视觉滚动条宽度 dp */
        private const val THUMB_WIDTH_DP       = 8f
        /** 滚动条距右边缘间距 dp */
        private const val THUMB_MARGIN_DP      = 3f
        /** 可触摸区域宽度 dp（比视觉更宽，方便点中） */
        private const val TOUCH_ZONE_DP        = 24f
        /** 滚动条最小高度 dp */
        private const val THUMB_MIN_HEIGHT_DP  = 48f
        /** 滚动条淡出延迟 ms */
        private const val FADE_DELAY_MS        = 1200L
        /** 淡出动画时长 ms */
        private const val FADE_DURATION_MS     = 300L
    }

    private val density          = context.resources.displayMetrics.density
    private val thumbWidthPx     = THUMB_WIDTH_DP      * density
    private val thumbMarginPx    = THUMB_MARGIN_DP     * density
    private val touchZonePx      = TOUCH_ZONE_DP       * density
    private val thumbMinHeightPx = THUMB_MIN_HEIGHT_DP * density

    // 滚动条绘制
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAA0A0A0.toInt()
    }
    private val thumbRect   = RectF()
    private val thumbRadius = thumbWidthPx / 2f

    // 透明度（setter 同步到 paint 并触发重绘）
    private var thumbAlpha: Int = 0
        set(value) {
            field = value.coerceIn(0, 255)
            thumbPaint.alpha = field
            invalidate()
        }

    // 拖拽状态
    private var isDraggingThumb = false
    /** 手指按下时相对于 thumb 顶部的偏移，保持"抓住"感 */
    private var dragOffsetY = 0f

    // ── 淡出动画 ──────────────────────────────────
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
            // ① 立即终止惯性 —— 必须在 intercept 阶段就做，否则 fling 继续跑
            stopFling()
            // ② 声明自己处理，后续事件直接进 onTouchEvent
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                if (isInThumbTouchZone(ev.x)) {
                    stopFling()
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
                    val targetThumbTop = (ev.y - dragOffsetY).coerceIn(0f, maxThumbTop)
                    val maxScroll      = computeVerticalScrollRange() - computeVerticalScrollExtent()
                    if (maxThumbTop > 0f && maxScroll > 0) {
                        val ratio = targetThumbTop / maxThumbTop
                        scrollTo(scrollX, (ratio * maxScroll).toInt())
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

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        showThumb()
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
        val left  = width - paddingRight - thumbMarginPx - thumbWidthPx
        val right = left + thumbWidthPx

        // ★ 核心修正：
        //   ScrollView 的 onDraw 时，canvas 已被系统向上平移了 scrollY 个像素
        //   （使内容从 scrollY 处开始绘制在屏幕顶部）。
        //   滚动条是固定在屏幕上的 overlay，不能随内容移动，
        //   因此必须加回 scrollY 抵消这个平移，才能保持视觉上"钉"在屏幕右侧。
        val top    = scrollY.toFloat() + paddingTop + thumbTop
        val bottom = top + thumbHeight

        thumbRect.set(left, top, right, bottom)
        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbPaint)
    }

    // ──────────────────────────────────────────────
    // 辅助计算（均在视口坐标系，不含 scrollY 偏移）
    // ──────────────────────────────────────────────

    /** 触摸点是否在右侧触摸区 */
    private fun isInThumbTouchZone(x: Float) =
        x >= width - paddingRight - touchZonePx

    /**
     * thumb 顶部在视口内的 Y 偏移（相对于 paddingTop，不含 scrollY）。
     * 取值范围：[0, trackHeight - thumbHeight]
     */
    private fun computeThumbTop(): Float {
        val scrollRange  = computeVerticalScrollRange().toFloat()
        val scrollExtent = computeVerticalScrollExtent().toFloat()
        val trackHeight  = (height - paddingTop - paddingBottom).toFloat()
        val thumbHeight  = computeThumbHeight()
        val maxThumbTop  = trackHeight - thumbHeight
        return if (scrollRange > scrollExtent)
            (scrollY.toFloat() / (scrollRange - scrollExtent)) * maxThumbTop
        else 0f
    }

    /** thumb 高度（按内容比例，最小 thumbMinHeightPx） */
    private fun computeThumbHeight(): Float {
        val scrollRange  = computeVerticalScrollRange().toFloat()
        val scrollExtent = computeVerticalScrollExtent().toFloat()
        val trackHeight  = (height - paddingTop - paddingBottom).toFloat()
        return if (scrollRange > 0)
            (scrollExtent / scrollRange * trackHeight).coerceAtLeast(thumbMinHeightPx)
        else trackHeight
    }

    /**
     * 终止 ScrollView 内部惯性动画。
     * ScrollView 没有公开的 abortAnimation()，通过反射调用内部 OverScroller。
     */
    private fun stopFling() {
        try {
            val field = ScrollView::class.java.getDeclaredField("mScroller")
            field.isAccessible = true
            val scroller = field.get(this) ?: return
            // OverScroller / Scroller 均有 public abortAnimation()
            scroller.javaClass.getMethod("abortAnimation").invoke(scroller)
        } catch (_: Exception) {
            // 反射失败退路：强制跳到当前位置
            scrollTo(scrollX, scrollY)
        }
    }
}