package com.subtitleedit

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Native input avoids waiting for Compose to become idle while edge scrolling is active. */
internal fun dragQueueWithLongPress(
    compose: ComposeTestRule,
    activity: Activity,
    start: Offset,
    end: Offset,
    holdAtEndMs: Long = 0L,
    holdBeforeMoveMs: Long = 750L,
    onFrame: (() -> Unit)? = null
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val origin = IntArray(2)
    instrumentation.runOnMainSync {
        activity.findViewById<View>(android.R.id.content).getLocationOnScreen(origin)
    }
    val downTime = SystemClock.uptimeMillis()
    fun inject(action: Int, point: Offset) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            point.x + origin[0], point.y + origin[1], 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }
    val auto = compose.mainClock.autoAdvance
    compose.mainClock.autoAdvance = false
    var released = false
    try {
        inject(MotionEvent.ACTION_DOWN, start)
        compose.mainClock.advanceTimeBy(holdBeforeMoveMs)
        if (holdBeforeMoveMs > 0L) SystemClock.sleep(holdBeforeMoveMs)
        repeat(12) { index ->
            inject(MotionEvent.ACTION_MOVE, start + (end - start) * ((index + 1) / 12f))
            compose.mainClock.advanceTimeByFrame()
            onFrame?.let { instrumentation.runOnMainSync(it) }
        }
        var heldFor = 0L
        while (heldFor < holdAtEndMs) {
            val step = minOf(16L, holdAtEndMs - heldFor)
            compose.mainClock.advanceTimeBy(step)
            onFrame?.let { instrumentation.runOnMainSync(it) }
            heldFor += step
        }
        inject(MotionEvent.ACTION_UP, end)
        released = true
    } finally {
        if (!released) inject(MotionEvent.ACTION_CANCEL, end)
        compose.mainClock.autoAdvance = auto
    }
    compose.waitForIdle()
}
