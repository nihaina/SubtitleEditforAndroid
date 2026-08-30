package com.subtitleedit.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * 带可拖拽滚动条的 RecyclerView。
 *
 * 与 DraggableScrollView 逻辑一致，区别：
 * - RecyclerView 有公开的 stopScroll()，无需反射
 * - RecyclerView 的 onDraw canvas 是视口坐标系，无需加 scrollY 补偿
 */
open class DraggableRecyclerView @JvmOverloads constructor(
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

    private data class ThumbGeometry(
        val trackTop: Float,
        val trackHeight: Float,
        val thumbHeight: Float,
        val thumbTop: Float,
        val scrollRange: Float,
        val scrollExtent: Float
    ) {
        val maxThumbTop: Float
            get() = (trackHeight - thumbHeight).coerceAtLeast(0f)

        fun hasScrollRange(): Boolean = scrollRange > scrollExtent && maxThumbTop > 0f
    }

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
    private var dragLastY        = 0f
    private var dragGeometry: ThumbGeometry? = null
    private var pendingScrollRatio: Float? = null
    private var scrollFramePosted = false
    private var lastThumbAdapterPosition = RecyclerView.NO_POSITION
    private var thumbDragPositionCorrection = 0

    // 高频 MOVE 事件只保留最后一个目标位置，每帧最多触发一次 RecyclerView 布局。
    private val applyPendingScrollRunnable = Runnable {
        scrollFramePosted = false
        applyPendingThumbScroll()
    }

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
        if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
            isInThumbTouchZone(ev.x) && canDragThumb()) {
            stopScroll()   // RecyclerView 公开方法，直接调用
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                if (isInThumbTouchZone(ev.x) && canDragThumb()) {
                    stopScroll()
                    val geometry = calculateThumbGeometry()
                    isDraggingThumb = true
                    dragLastY = ev.y
                    dragGeometry = geometry
                    // Insets may have changed after the last draw but before this DOWN event.
                    // Anchor to the thumb the user actually sees when available; the next MOVE
                    // will re-anchor if the new layout geometry has become active.
                    val drawnThumbTop = if (!thumbRect.isEmpty()) {
                        thumbRect.top
                    } else {
                        geometry.trackTop + geometry.thumbTop
                    }
                    dragOffsetY = ev.y - drawnThumbTop
                    captureThumbDragAnchor()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    showThumb()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDraggingThumb) {
                    // 数据刷新后列表可能已经不再可滚动，立即结束旧滑块的拖拽状态。
                    if (!canDragThumb()) {
                        finishThumbDrag()
                        return true
                    }

                    val geometry = calculateThumbGeometry()
                    val previousGeometry = dragGeometry
                    if (previousGeometry == null || !sameTrackGeometry(previousGeometry, geometry)) {
                        // Insets/layout changes (most commonly an IME hide) can alter the
                        // RecyclerView track between two MOVE events. Re-anchor to the thumb that
                        // is actually visible at the last finger position before mapping the new
                        // event, otherwise the drag appears vertically offset.
                        dragOffsetY = dragLastY - geometry.trackTop - geometry.thumbTop
                        dragGeometry = geometry
                    }
                    val targetThumbTop = (ev.y - geometry.trackTop - dragOffsetY)
                        .coerceIn(0f, geometry.maxThumbTop)
                    val maxScroll      = geometry.scrollRange - geometry.scrollExtent
                    if (geometry.maxThumbTop > 0f && maxScroll > 0f) {
                        val ratio     = targetThumbTop / geometry.maxThumbTop
                        enqueueThumbScroll(ratio)
                    }
                    dragLastY = ev.y
                    showThumb()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingThumb) {
                    applyPendingThumbScroll()
                    finishThumbDrag()
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
        if (isDraggingThumb && thumbAlpha == 220) return
        thumbAlpha = 220
        if (!isDraggingThumb) {
            postDelayed(startFadeRunnable, FADE_DELAY_MS)
        }
    }

    fun showDragThumb() {
        if (canDragThumb()) showThumb()
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
    }

    /** Draw after RecyclerView children so full-width rows cannot cover the thumb. */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawThumb(canvas)
    }

    private fun drawThumb(canvas: Canvas) {
        if (thumbAlpha <= 0) {
            thumbRect.setEmpty()
            return
        }

        val geometry = calculateThumbGeometry()
        if (!geometry.hasScrollRange()) {
            thumbRect.setEmpty()
            return
        }
        // 贴 View 真正右边缘，忽略 padding，保证滚动条在屏幕最右侧
        val left  = width - thumbMarginPx - thumbWidthPx
        val right = left + thumbWidthPx
        val top    = geometry.trackTop + geometry.thumbTop
        val bottom = top + geometry.thumbHeight

        thumbRect.set(left, top, right, bottom)
        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbPaint)
    }

    // ──────────────────────────────────────────────
    // 辅助计算
    // ──────────────────────────────────────────────

    private fun isInThumbTouchZone(x: Float) =
        x >= width - touchZonePx

    private fun canDragThumb(): Boolean =
        calculateThumbGeometry().hasScrollRange()

    private fun enqueueThumbScroll(targetRatio: Float) {
        pendingScrollRatio = targetRatio
        if (!scrollFramePosted) {
            scrollFramePosted = true
            postOnAnimation(applyPendingScrollRunnable)
        }
    }

    private fun applyPendingThumbScroll() {
        val targetRatio = pendingScrollRatio ?: return
        pendingScrollRatio = null
        if (!isDraggingThumb || !canDragThumb()) return

        val linearLayoutManager = layoutManager as? LinearLayoutManager
        val itemCount = adapter?.itemCount ?: 0
        if (linearLayoutManager != null && itemCount > 0) {
            val targetPosition = (
                (targetRatio * (itemCount - 1)).roundToInt() + thumbDragPositionCorrection
            ).coerceIn(0, itemCount - 1)
            if (targetPosition != lastThumbAdapterPosition) {
                linearLayoutManager.scrollToPositionWithOffset(targetPosition, 0)
                lastThumbAdapterPosition = targetPosition
            }
        } else {
            val maxScroll = computeVerticalScrollRange() - computeVerticalScrollExtent()
            val delta = (targetRatio * maxScroll).toInt() - computeVerticalScrollOffset()
            if (delta != 0) scrollBy(0, delta)
        }
    }

    private fun finishThumbDrag() {
        isDraggingThumb = false
        dragGeometry = null
        pendingScrollRatio = null
        lastThumbAdapterPosition = RecyclerView.NO_POSITION
        thumbDragPositionCorrection = 0
        if (scrollFramePosted) {
            removeCallbacks(applyPendingScrollRunnable)
            scrollFramePosted = false
        }
        parent?.requestDisallowInterceptTouchEvent(false)
        scheduleFadeOut()
    }

    private fun captureThumbDragAnchor() {
        val linearLayoutManager = layoutManager as? LinearLayoutManager ?: return
        val itemCount = adapter?.itemCount ?: return
        if (itemCount <= 0) return

        val scrollRange = computeVerticalScrollRange()
        val scrollExtent = computeVerticalScrollExtent()
        val currentRatio = if (scrollRange > scrollExtent) {
            computeVerticalScrollOffset().toFloat() / (scrollRange - scrollExtent)
        } else {
            0f
        }
        val estimatedPosition = (currentRatio * (itemCount - 1)).roundToInt()
        val firstVisiblePosition = linearLayoutManager.findFirstVisibleItemPosition()
        if (firstVisiblePosition != RecyclerView.NO_POSITION) {
            thumbDragPositionCorrection = firstVisiblePosition - estimatedPosition
            lastThumbAdapterPosition = firstVisiblePosition
        }
    }

    private fun calculateThumbGeometry(): ThumbGeometry {
        val scrollRange = computeVerticalScrollRange().toFloat().coerceAtLeast(0f)
        val scrollExtent = computeVerticalScrollExtent().toFloat().coerceAtLeast(0f)
        val trackTop = paddingTop.toFloat()
        val trackHeight = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(0f)
        val thumbHeight = if (scrollRange > 0f) {
            (scrollExtent / scrollRange * trackHeight)
                .coerceAtLeast(thumbMinHeightPx.coerceAtMost(trackHeight))
                .coerceAtMost(trackHeight)
        } else {
            trackHeight
        }
        val maxThumbTop = (trackHeight - thumbHeight).coerceAtLeast(0f)
        val maxScroll = (scrollRange - scrollExtent).coerceAtLeast(0f)
        val thumbTop = if (maxScroll > 0f && maxThumbTop > 0f) {
            (computeVerticalScrollOffset().toFloat() / maxScroll * maxThumbTop)
                .coerceIn(0f, maxThumbTop)
        } else {
            0f
        }
        return ThumbGeometry(
            trackTop = trackTop,
            trackHeight = trackHeight,
            thumbHeight = thumbHeight,
            thumbTop = thumbTop,
            scrollRange = scrollRange,
            scrollExtent = scrollExtent
        )
    }

    private fun sameTrackGeometry(first: ThumbGeometry, second: ThumbGeometry): Boolean =
        first.trackTop == second.trackTop &&
            first.trackHeight == second.trackHeight &&
            first.thumbHeight == second.thumbHeight &&
            first.scrollRange == second.scrollRange &&
            first.scrollExtent == second.scrollExtent
}
