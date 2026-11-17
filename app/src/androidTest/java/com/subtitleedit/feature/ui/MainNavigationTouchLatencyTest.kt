package com.subtitleedit.feature.ui

import android.os.SystemClock
import android.os.Trace
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.appcompat.view.WindowCallbackWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.subtitleedit.MainActivity
import com.subtitleedit.R
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real input and real frame commits, without the Compose test clock or composition tracing. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class MainNavigationTouchLatencyTest {
    private class Sample(val round: Int, val page: Int, val label: String) {
        var upNs = 0L
        var injectionNs = 0L
        var dispatchEndNs = 0L
        var drawNs = 0L
        val commits = CopyOnWriteArrayList<Long>()
        val ready = CountDownLatch(1)
    }

    @Test fun measureTapDispatchAndFirstTargetFrameCommits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            var pending: Sample? = null
            val stateReader = MainActivity::class.java.getDeclaredMethod("getScreenState").apply { isAccessible = true }
            scenario.onActivity { host ->
                activity = host
                assumeTrue("Keep the existing player mode unchanged", (stateReader.invoke(host) as MainActivityScreenState).playerModeEnabled)
                val original = host.window.callback
                host.window.callback = object : WindowCallbackWrapper(original) {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        val sample = pending
                        if (event.actionMasked != MotionEvent.ACTION_UP || sample == null) return super.dispatchTouchEvent(event)
                        sample.upNs = SystemClock.elapsedRealtimeNanos()
                        Trace.beginSection("NavigationTouch:${sample.label}")
                        return try { super.dispatchTouchEvent(event) } finally {
                            sample.dispatchEndNs = SystemClock.elapsedRealtimeNanos()
                            Trace.endSection()
                        }
                    }
                }
                val root = host.window.decorView
                root.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        val sample = pending ?: return
                        if (sample.upNs == 0L || sample.commits.size >= 3) return
                        if ((stateReader.invoke(host) as MainActivityScreenState).selectedTopLevelItem != sample.page) return
                        if (sample.drawNs == 0L) {
                            sample.drawNs = SystemClock.elapsedRealtimeNanos()
                            Trace.beginSection("NavigationDrawStart:${sample.label}")
                            Trace.endSection()
                        }
                        root.viewTreeObserver.registerFrameCommitCallback {
                            if (sample.commits.size >= 3) return@registerFrameCommitCallback
                            sample.commits += SystemClock.elapsedRealtimeNanos()
                            Trace.beginSection("NavigationFrameCommit:${sample.label}:${sample.commits.size}")
                            Trace.endSection()
                            if (sample.commits.size >= 3) sample.ready.countDown()
                        }
                    }
                })
            }
            SystemClock.sleep(1_000L)
            val targets = listOf(
                R.id.nav_library to R.string.nav_library,
                R.id.nav_tools to R.string.media_categories_navigation,
                R.id.nav_stats to R.string.nav_stats,
                R.id.nav_settings to R.string.menu_main_title_02,
                R.id.nav_directory to R.string.nav_directory,
            )
            repeat(3) { round ->
                for ((id, labelRes) in targets) {
                    val label = activity.getString(labelRes)
                    val sample = Sample(round, id, label)
                    var x = 0f
                    var y = 0f
                    scenario.onActivity {
                        val root = it.window.decorView
                        val location = IntArray(2).also(root::getLocationOnScreen)
                        val insets = root.rootWindowInsets.getInsetsIgnoringVisibility(
                            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                        val index = listOf(R.id.nav_directory, R.id.nav_library, R.id.nav_tools, R.id.nav_stats, R.id.nav_settings).indexOf(id)
                        x = location[0] + insets.left + (root.width - insets.left - insets.right) * (index + 0.5f) / 5f
                        y = location[1] + root.height - insets.bottom - 30f * root.resources.displayMetrics.density
                        pending = sample
                    }
                    val downTime = SystemClock.uptimeMillis()
                    fun inject(action: Int) {
                        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                            x, y, 0)
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        if (action == MotionEvent.ACTION_UP) sample.injectionNs = SystemClock.elapsedRealtimeNanos()
                        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                        finally { event.recycle() }
                    }
                    inject(MotionEvent.ACTION_DOWN)
                    SystemClock.sleep(40L)
                    inject(MotionEvent.ACTION_UP)
                    assertTrue("No committed navigation frames for $label", sample.ready.await(8, TimeUnit.SECONDS))
                    scenario.onActivity {
                        val commits = JSONArray()
                        sample.commits.forEach { commits.put((it - sample.upNs) / 1e6) }
                        output.put(JSONObject().put("round", round).put("page", label)
                            .put("touch_up_ns", sample.upNs)
                            .put("input_queue_ms", (sample.upNs - sample.injectionNs) / 1e6)
                            .put("touch_dispatch_ms", (sample.dispatchEndNs - sample.upNs) / 1e6)
                            .put("first_target_draw_ms", (sample.drawNs - sample.upNs) / 1e6)
                            .put("frame_commits_after_up_ms", commits))
                        pending = null
                    }
                    SystemClock.sleep(650L)
                }
            }
            File(activity.cacheDir, "navigation-touch-latency.json").writeText(output.toString(2))
        }
    }
}
