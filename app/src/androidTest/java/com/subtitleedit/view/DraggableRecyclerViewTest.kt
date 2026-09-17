package com.subtitleedit.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraggableRecyclerViewTest {
    @Test
    fun thumbFollowsFingerAcrossWrappedRowsAndReverseMoves() = onMainThread {
        val view = createView(IntArray(80) { if (it % 5 < 2) 160 else 40 })
        val initialThumb = thumb(view)
        val downY = initialThumb.centerY()
        touch(view, MotionEvent.ACTION_DOWN, downY)

        for (distance in listOf(15f, 30f, 90f, 140f, 70f, 35f, 100f)) {
            touch(view, MotionEvent.ACTION_MOVE, downY + distance)
            // Verify both immediate feedback and the position after rows have been recycled.
            assertEquals(initialThumb.top + distance, thumb(view).top, 0.01f)
            flushScrollAndLayout(view)
            val drawn = thumb(view)
            assertEquals(initialThumb.top + distance, drawn.top, 0.01f)
            assertEquals(initialThumb.height(), drawn.height(), 0.01f)
        }
        touch(view, MotionEvent.ACTION_UP, downY + 100f)
    }

    @Test
    fun halfwayDragScrollsHalfTheScrollableContent() = onMainThread {
        val view = createView(IntArray(30) { 40 })
        val initialThumb = thumb(view)
        val travel = view.height - view.paddingTop - view.paddingBottom - initialThumb.height()
        val downY = initialThumb.centerY()
        touch(view, MotionEvent.ACTION_DOWN, downY)
        touch(view, MotionEvent.ACTION_MOVE, downY + travel / 2f)
        flushScrollAndLayout(view)

        // Ten of thirty rows fit: halfway is row 10 at the top, not row 15.
        val manager = view.layoutManager as LinearLayoutManager
        assertEquals(10, manager.findFirstVisibleItemPosition())
        assertEquals(view.paddingTop, manager.getDecoratedTop(manager.findViewByPosition(10)!!))
        val dragged = thumb(view)
        touch(view, MotionEvent.ACTION_UP, downY + travel / 2f)
        flushScrollAndLayout(view)
        assertEquals(dragged.top, thumb(view).top, 0.01f)
    }

    @Test
    fun dragClampsAtBothEndsAndCanReverseImmediately() = onMainThread {
        val view = createView(IntArray(30) { 40 })
        val initialThumb = thumb(view)
        val downY = initialThumb.centerY()
        val travel = view.height - view.paddingTop - view.paddingBottom - initialThumb.height()
        touch(view, MotionEvent.ACTION_DOWN, downY)
        touch(view, MotionEvent.ACTION_MOVE, downY + travel + 100f)
        flushScrollAndLayout(view)
        assertEquals(view.height - view.paddingBottom.toFloat(), thumb(view).bottom, 0.01f)
        assertFalse(view.canScrollVertically(1))

        touch(view, MotionEvent.ACTION_MOVE, downY + travel - 25f)
        flushScrollAndLayout(view)
        assertEquals(initialThumb.top + travel - 25f, thumb(view).top, 0.01f)
        touch(view, MotionEvent.ACTION_MOVE, downY - 100f)
        flushScrollAndLayout(view)
        assertEquals(view.paddingTop.toFloat(), thumb(view).top, 0.01f)
        assertFalse(view.canScrollVertically(-1))
        touch(view, MotionEvent.ACTION_CANCEL, downY)
    }

    @Test
    fun regrabPreservesPartiallyVisibleRow() = onMainThread {
        val view = createView(IntArray(30) { 40 })
        view.scrollBy(0, 93)
        val manager = view.layoutManager as LinearLayoutManager
        val first = manager.findFirstVisibleItemPosition()
        val offset = manager.getDecoratedTop(manager.findViewByPosition(first)!!)
        val downY = thumb(view).centerY()
        touch(view, MotionEvent.ACTION_DOWN, downY)
        touch(view, MotionEvent.ACTION_MOVE, downY)
        flushScrollAndLayout(view)
        assertEquals(first, manager.findFirstVisibleItemPosition())
        assertEquals(offset, manager.getDecoratedTop(manager.findViewByPosition(first)!!))
        touch(view, MotionEvent.ACTION_CANCEL, downY)
    }

    private fun createView(heights: IntArray): DraggableRecyclerView {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return object : DraggableRecyclerView(context) {
            override val useFixedSizeScrollThumb = true
        }.apply {
            layoutManager = LinearLayoutManager(context).apply { isSmoothScrollbarEnabled = false }
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = heights.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(View(context)) {}

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    holder.itemView.layoutParams = RecyclerView.LayoutParams(300, heights[position])
                }
            }
            itemAnimator = null
            setPadding(0, 12, 0, 12)
            layoutView(this)
            showDragThumb()
        }
    }

    private fun layoutView(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(424, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 300, 424)
    }

    private fun touch(view: View, action: Int, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, view.width - 1f, y, 0)
        try {
            assertTrue(view.onTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun flushScrollAndLayout(view: DraggableRecyclerView) {
        // The view is deliberately detached, so drive its queued frame without opening an
        // activity or touching an editor document on the device running these tests.
        DraggableRecyclerView::class.java.getDeclaredMethod("applyPendingThumbScroll").apply {
            isAccessible = true
        }.invoke(view)
        layoutView(view)
    }

    private fun thumb(view: DraggableRecyclerView): RectF {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            return RectF(DraggableRecyclerView::class.java.getDeclaredField("thumbRect").apply {
                isAccessible = true
            }.get(view) as RectF)
        } finally {
            bitmap.recycle()
        }
    }

    private fun onMainThread(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
