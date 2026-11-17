package com.subtitleedit.feature.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Debug
import android.os.Trace
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import androidx.appcompat.view.WindowCallbackWrapper
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.MainActivity
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.player.PlayerPreferences
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real-clock input, page geometry at draw time and committed frames; no Compose test clock. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class MainNavigationMotionLatencyTest {
    private class Sample(val round: Int, val page: Int, val label: String) {
        var upNs = 0L
        var dispatchEndNs = 0L
        var visibleNs = 0L
        var fullNs = 0L
        var confirmationFrame = false
        val commits = mutableListOf<Long>()
        val ready = CountDownLatch(1)
    }
    private data class Frame(val vsync: Long, val duration: Long, val deadline: Long)

    @Test fun measureVisibleTargetFramesAndNavigationFrameDurations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val functionalVerification = InstrumentationRegistry.getArguments().getString("functionalVerification") == "true"
        val preferences = PlayerPreferences.getInstance(instrumentation.targetContext)
        val originalMode = preferences.playerModeEnabled
        val restoreMode = when (InstrumentationRegistry.getArguments().getString("restoreNavigationMode")) {
            "player" -> true
            "editor" -> false
            else -> originalMode
        }
        val playerMode = when (InstrumentationRegistry.getArguments().getString("navigationMode")) {
            "player" -> true
            "editor" -> false
            else -> originalMode
        }
        preferences.playerModeEnabled = playerMode
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                var pending: Sample? = null
                val frames = mutableListOf<Frame>()
                val stateReader = MainActivity::class.java.getDeclaredMethod("getScreenState").apply { isAccessible = true }
                val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    frames += Frame(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                        metrics.getMetric(FrameMetrics.TOTAL_DURATION), metrics.getMetric(FrameMetrics.DEADLINE))
                }
                scenario.onActivity { host ->
                    activity = host
                    host.window.addOnFrameMetricsAvailableListener(frameListener, Handler(Looper.getMainLooper()))
                    host.window.callback = object : WindowCallbackWrapper(host.window.callback) {
                        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                            val sample = pending
                            if (event.actionMasked != MotionEvent.ACTION_UP || sample == null) return super.dispatchTouchEvent(event)
                            sample.upNs = System.nanoTime()
                            Trace.beginSection("PagerTouch:${sample.label}")
                            return try { super.dispatchTouchEvent(event) }
                            finally { sample.dispatchEndNs = System.nanoTime(); Trace.endSection() }
                        }
                    }
                }
                SystemClock.sleep(1_500L)
                val initialPssKb = Debug.getPss()
                scenario.onActivity { host ->
                    val root = host.window.decorView
                    val owners = mutableListOf<SemanticsOwner>()
                    findSemanticsOwners(root, owners)
                    check(owners.isNotEmpty()) { "No Compose semantics root for frame geometry" }
                    root.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                        override fun onDraw() {
                            val sample = pending ?: return
                            if (sample.upNs == 0L || sample.fullNs != 0L) return
                            // A swipe updates selection only after settling. Its final visual
                            // frame can commit before that semantics-only update, which needs
                            // no additional draw with a continuously driven bottom indicator.
                            if (sample.label != "swipe" &&
                                (stateReader.invoke(host) as MainActivityScreenState).selectedTopLevelItem != sample.page) return
                            val viewport = owners.firstNotNullOfOrNull {
                                it.unmergedRootSemanticsNode.findTag("main-page-pager")
                            }?.boundsInRoot ?: return
                            val page = owners.firstNotNullOfOrNull {
                                it.unmergedRootSemanticsNode.findTag("main-page:${sample.page}")
                            }?.boundsInRoot
                            val width = page?.let {
                                (minOf(it.right, viewport.right) - maxOf(it.left, viewport.left)).coerceAtLeast(0f)
                            } ?: 0f
                            val coverage = if (viewport.width > 0f) width / viewport.width else 0f
                            root.viewTreeObserver.registerFrameCommitCallback {
                                val now = System.nanoTime()
                                if (sample.commits.size < 3) sample.commits += now
                                if (coverage >= 0.01f && sample.visibleNs == 0L) {
                                    sample.visibleNs = now
                                    Trace.beginSection("PagerVisible:${sample.label}"); Trace.endSection()
                                }
                                if (coverage >= 0.995f && sample.fullNs == 0L) {
                                    sample.fullNs = now
                                    Trace.beginSection("PagerFull:${sample.label}"); Trace.endSection()
                                    sample.ready.countDown()
                                }
                            }
                        }
                    })
                }
                val items = bottomNavigationItems(playerMode)
                val targets = items.drop(1) + items.take(1)
                val output = JSONArray()
                val rounds = if (InstrumentationRegistry.getArguments().getString("swipeOnly") == "true") 0 else 3
                repeat(rounds) { round ->
                    for ((id, _, labelRes) in targets) {
                        val sample = Sample(round, id, activity.getString(labelRes))
                        var x = 0f
                        var y = 0f
                        scenario.onActivity { host ->
                            assertTrue("Navigation test lost foreground focus", host.window.decorView.hasWindowFocus())
                            val root = host.window.decorView
                            val origin = IntArray(2).also(root::getLocationOnScreen)
                            val insets = root.rootWindowInsets.getInsetsIgnoringVisibility(
                                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                            val index = items.indexOfFirst { it.first == id }
                            x = origin[0] + insets.left + (root.width - insets.left - insets.right) * (index + .5f) / items.size
                            y = origin[1] + root.height - insets.bottom - 30f * root.resources.displayMetrics.density
                            pending = sample
                        }
                        val downTime = SystemClock.uptimeMillis()
                        fun inject(action: Int) {
                            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                            event.source = InputDevice.SOURCE_TOUCHSCREEN
                            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                            finally { event.recycle() }
                        }
                        inject(MotionEvent.ACTION_DOWN)
                        SystemClock.sleep(40L)
                        inject(MotionEvent.ACTION_UP)
                        var committed = sample.ready.await(if (functionalVerification) 1_200L else 8_000L, TimeUnit.MILLISECONDS)
                        if (!committed && functionalVerification) {
                            // Some emulator renderers update the final layer position without
                            // another decor OnDraw callback. Confirm already-settled geometry
                            // with one explicitly requested frame, excluded from latency results.
                            scenario.onActivity { host ->
                                val owners = mutableListOf<SemanticsOwner>()
                                val root = host.window.decorView
                                findSemanticsOwners(root, owners)
                                val viewport = owners.firstNotNullOfOrNull {
                                    it.unmergedRootSemanticsNode.findTag("main-page-pager")
                                }?.boundsInRoot
                                val bounds = owners.firstNotNullOfOrNull {
                                    it.unmergedRootSemanticsNode.findTag("main-page:${sample.page}")
                                }?.boundsInRoot
                                val selected = (stateReader.invoke(host) as MainActivityScreenState).selectedTopLevelItem
                                if (selected == sample.page && viewport != null && bounds != null &&
                                    kotlin.math.abs(bounds.left - viewport.left) <= 1f &&
                                    kotlin.math.abs(bounds.right - viewport.right) <= 1f) {
                                    // The ordinary OnDraw listener can also receive this
                                    // requested frame, so mark it before invalidating the root.
                                    sample.confirmationFrame = true
                                    root.viewTreeObserver.registerFrameCommitCallback {
                                        if (sample.fullNs == 0L) {
                                            sample.fullNs = System.nanoTime()
                                            sample.ready.countDown()
                                        }
                                    }
                                    root.postInvalidateOnAnimation()
                                }
                            }
                            committed = sample.ready.await(2, TimeUnit.SECONDS)
                        }
                        if (!committed) scenario.onActivity { host ->
                            val owners = mutableListOf<SemanticsOwner>()
                            findSemanticsOwners(host.window.decorView, owners)
                            val details = JSONObject().put("round", round).put("target", sample.label)
                                .put("selected", (stateReader.invoke(host) as MainActivityScreenState).selectedTopLevelItem)
                                .put("expected", sample.page).put("focus", host.window.decorView.hasWindowFocus())
                                .put("bounds", owners.firstNotNullOfOrNull {
                                    it.unmergedRootSemanticsNode.findTag("main-page:${sample.page}")
                                }?.boundsInRoot.toString())
                                .put("viewport", owners.firstNotNullOfOrNull {
                                    it.unmergedRootSemanticsNode.findTag("main-page-pager")
                                }?.boundsInRoot.toString())
                                .put("touch_up_ns", sample.upNs).put("visible_ns", sample.visibleNs)
                                .put("full_ns", sample.fullNs).put("commits", JSONArray(sample.commits))
                            File(host.cacheDir, "navigation-motion-failure.json").writeText(details.toString(2))
                        }
                        assertTrue("No fully visible committed frame for ${sample.label}", committed)
                        // Include refresh work after the target covers the viewport, not just movement.
                        SystemClock.sleep(500L)
                        scenario.onActivity {
                            val end = System.nanoTime()
                            val durations = JSONArray()
                            frames.filter { it.vsync in sample.upNs..end }.forEach { frame ->
                                durations.put(JSONObject().put("duration_ms", frame.duration / 1e6)
                                    .put("deadline_ms", frame.deadline / 1e6))
                            }
                            output.put(JSONObject().put("round", round).put("page", sample.label)
                                .put("touch_dispatch_ms", (sample.dispatchEndNs - sample.upNs) / 1e6)
                                .put("first_visible_commit_ms", (sample.visibleNs - sample.upNs) / 1e6)
                                .put("first_full_commit_ms", if (sample.confirmationFrame) JSONObject.NULL else (sample.fullNs - sample.upNs) / 1e6)
                                .put("explicit_confirmation_frame", sample.confirmationFrame)
                                .put("frame_commits_after_up_ms", JSONArray(sample.commits.map { (it - sample.upNs) / 1e6 }))
                                .put("frames", durations))
                            pending = null
                        }
                    }
                }
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(frameListener) }
                File(activity.cacheDir, "navigation-motion-${if (playerMode) "player" else "editor"}.json")
                    .writeText(output.toString(2))
                // Exercise the real MainActivity swipe callback in both modes, after the tap data.
                for ((target, left) in listOf(items[1].first to true, items[0].first to false)) {
                    val sample = Sample(3, target, "swipe")
                    var width = 0f
                    var y = 0f
                    var originX = 0f
                    scenario.onActivity { host ->
                        assertTrue("Swipe test lost foreground focus", host.window.decorView.hasWindowFocus())
                        val root = host.window.decorView
                        val origin = IntArray(2).also(root::getLocationOnScreen)
                        originX = origin[0].toFloat()
                        width = root.width.toFloat()
                        y = origin[1] + root.height * .6f
                        pending = sample
                    }
                    val downTime = SystemClock.uptimeMillis()
                    for (step in 0..24) {
                        val fraction = step / 24f
                        val x = originX + width * if (left) .8f - .6f * fraction else .2f + .6f * fraction
                        val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 24 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                        finally { event.recycle() }
                        if (left && step == 12) {
                            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                                File(activity.cacheDir, "navigation-manual-swipe-mid.png").outputStream().use {
                                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                }
                                screenshot.recycle()
                            }
                        }
                        SystemClock.sleep(10L)
                    }
                    val committed = sample.ready.await(8, TimeUnit.SECONDS)
                    val selectionDeadline = SystemClock.uptimeMillis() + 2_000L
                    var selected = -1
                    do {
                        scenario.onActivity { selected = (stateReader.invoke(it) as MainActivityScreenState).selectedTopLevelItem }
                        if (selected != target) SystemClock.sleep(16L)
                    } while (selected != target && SystemClock.uptimeMillis() < selectionDeadline)
                    var details = ""
                    scenario.onActivity { host ->
                        val owners = mutableListOf<SemanticsOwner>()
                        findSemanticsOwners(host.window.decorView, owners)
                        details = "selected=${(stateReader.invoke(host) as MainActivityScreenState).selectedTopLevelItem}, " +
                            "targetBounds=${owners.firstNotNullOfOrNull { it.unmergedRootSemanticsNode.findTag("main-page:$target") }?.boundsInRoot}, " +
                            "touchUp=${sample.upNs}, visible=${sample.visibleNs}, full=${sample.fullNs}"
                    }
                    assertTrue("Swipe did not select $target: $details", committed)
                    assertTrue("Swipe selection disagrees with the visible page: $details", selected == target)
                    scenario.onActivity { pending = null }
                }
                File(activity.cacheDir, "navigation-motion-${if (playerMode) "player" else "editor"}-memory.json")
                    .writeText(JSONObject().put("initial_pss_kb", initialPssKb).put("after_visited_pss_kb", Debug.getPss())
                        .put("indexed_tracks", MediaLibraryRepository.getInstance(instrumentation.targetContext).getIndexedTracks().size)
                        .put("original_player_mode", originalMode).put("restore_player_mode", restoreMode)
                        .put("real_swipes_verified", 2).toString(2))
            }
        } finally {
            // The instrumentation process can exit before SharedPreferences.apply finishes.
            // Restore synchronously, including when a measured gesture fails.
            assertTrue(instrumentation.targetContext.getSharedPreferences("player_settings", 0)
                .edit().putBoolean("player_mode_enabled", restoreMode).commit())
        }
    }

    private fun findSemanticsOwners(view: View, result: MutableList<SemanticsOwner>) {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") {
            val getter = view.javaClass.methods.first { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            result += getter.invoke(view) as SemanticsOwner
        }
        if (view is ViewGroup) repeat(view.childCount) { findSemanticsOwners(view.getChildAt(it), result) }
    }

    private fun SemanticsNode.findTag(tag: String): SemanticsNode? {
        val ownTag = config.getOrNull(SemanticsProperties.TestTag)
        if (ownTag == tag) return this
        if (ownTag?.startsWith("main-page:") == true) return null
        return children.firstNotNullOfOrNull { it.findTag(tag) }
    }
}
