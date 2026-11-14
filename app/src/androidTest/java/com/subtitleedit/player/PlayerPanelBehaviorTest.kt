package com.subtitleedit.player

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Exercises the immersive menus without waiting for the player's continuous animations to idle. */
@RunWith(AndroidJUnit4::class)
class PlayerPanelBehaviorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun playerModeIsOffByDefaultAndRemembersAnExplicitChoice() {
        val name = "player-mode-default-test-${System.nanoTime()}"
        try {
            val preferences = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertFalse(PlayerPreferences(preferences).playerModeEnabled)
            PlayerPreferences(preferences).playerModeEnabled = true
            assertTrue(PlayerPreferences(preferences).playerModeEnabled)
            PlayerPreferences(preferences).playerModeEnabled = false
            assertFalse(PlayerPreferences(preferences).playerModeEnabled)
        } finally {
            app.deleteSharedPreferences(name)
        }
    }

    @Test fun menusKeepSystemBarsHiddenAndExpandedEqualizerDoesNotBounceWhileScrolling() {
        val oldFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            Fixture().use { fixture ->
                openPlayer(fixture.track).use { session ->
                    val model = session.model
                    await("generated media is ready") { mainValue { model.state.value.ready } }
                    onMain { model.pause() }
                    await("player starts immersive") { barsHidden(session.activity) }
                    Thread.sleep(400L)
                    val topBefore = requiredBounds("player-top-bar")
                    val controlsBefore = requiredBounds("player-controls")
                    val decorBefore = mainValue {
                        Rect().also { session.activity.window.decorView.getGlobalVisibleRect(it) }
                    }
                    val tolerance = mainValue { (session.activity.resources.displayMetrics.density * 2f).toInt().coerceAtLeast(2) }

                    listOf(PlayerPanel.EQUALIZER, PlayerPanel.BOOKMARK, PlayerPanel.PLAYLIST,
                        PlayerPanel.SUBTITLE, PlayerPanel.TIMER, PlayerPanel.SPEED, PlayerPanel.SETTINGS).forEach { panel ->
                        onMain { model.openPanel(panel) }
                        val expanded = settledPanelBounds(tolerance)
                        if (panel == PlayerPanel.EQUALIZER) {
                            captureIfRequested("player-equalizer-open-preview.png")
                        }
                        assertTrue("$panel must remain anchored to the bottom of the player: $expanded vs $decorBefore",
                            abs(expanded.bottom - decorBefore.bottom) <= tolerance)
                        repeat(5) {
                            assertTrue("$panel must not reveal status/navigation bars", barsHidden(session.activity))
                            val decorNow = mainValue {
                                Rect().also { session.activity.window.decorView.getGlobalVisibleRect(it) }
                            }
                            assertRectNear("$panel must not resize the playback window", decorBefore, decorNow, tolerance)
                            // Modal windows may hide underlying accessibility descendants. When exposed,
                            // verify their actual bounds as well as the native window/insets above.
                            boundsOrNull("player-top-bar")?.let {
                                assertRectNear("$panel must not shift the top controls", topBefore, it, tolerance)
                            }
                            boundsOrNull("player-controls")?.let {
                                assertRectNear("$panel must not shift playback controls", controlsBefore, it, tolerance)
                            }
                            Thread.sleep(60L)
                        }
                        if (panel == PlayerPanel.EQUALIZER) {
                            repeat(4) { gesture ->
                                val tops = swipeUpAndSampleSheet(expanded, session.activity.resources.displayMetrics.density)
                                assertTrue("Expanded EQ moved while scrolling gesture $gesture: $tops",
                                    (tops.maxOrNull() ?: expanded.top) - (tops.minOrNull() ?: expanded.top) <= tolerance)
                                val settled = settledPanelBounds(tolerance)
                                assertRectNear("Scrolling EQ must not change the sheet's anchor", expanded, settled, tolerance)
                            }
                            captureIfRequested("player-equalizer-stable-preview.png")
                        }
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        await("Back closes $panel") { mainValue { model.state.value.panel == PlayerPanel.NONE } }
                        assertFalse("Back from a menu must keep the player open", mainValue { session.activity.isFinishing })
                        await("$panel dismissal stays immersive") { barsHidden(session.activity) }
                        assertRectNear("Top controls remain fixed after $panel", topBefore,
                            requiredBounds("player-top-bar"), tolerance)
                        assertRectNear("Playback controls remain fixed after $panel", controlsBefore,
                            requiredBounds("player-controls"), tolerance)
                    }
                }
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
        }
    }

    private fun swipeUpAndSampleSheet(bounds: Rect, density: Float): List<Int> {
        val x = bounds.left + 28f * density
        val fromY = bounds.bottom - 48f * density
        val toY = (bounds.top + 120f * density).coerceAtMost(fromY - 40f * density)
        val downTime = SystemClock.uptimeMillis()
        val tops = mutableListOf<Int>()
        injectTouch(downTime, MotionEvent.ACTION_DOWN, x, fromY)
        try {
            repeat(16) { step ->
                Thread.sleep(18L)
                injectTouch(downTime, MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * (step + 1) / 16f)
                boundsOrNull("player-panel-content")?.let { tops += it.top }
            }
        } finally {
            injectTouch(downTime, MotionEvent.ACTION_UP, x, toY)
        }
        repeat(6) {
            Thread.sleep(45L)
            tops += requiredBounds("player-panel-content").top
        }
        assertTrue("Sample actual sheet bounds during the drag", tops.size >= 8)
        return tops
    }

    private fun injectTouch(downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue("Inject player panel touch", automation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun settledPanelBounds(tolerance: Int): Rect {
        var latest: Rect? = null
        var stable = 0
        await("player panel expands and settles") {
            val next = boundsOrNull("player-panel-content")
            if (next != null && next.height() > 0 && latest?.let { abs(it.top - next.top) <= tolerance } == true) {
                stable++
            } else stable = 0
            latest = next
            stable >= 5
        }
        return requireNotNull(latest)
    }

    private fun barsHidden(activity: PlayerActivity): Boolean = mainValue {
        val windows = mutableListOf(activity.window.decorView)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            windows += android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }
        }
        windows.all { view ->
            val insets = ViewCompat.getRootWindowInsets(view) ?: return@all false
            !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun requiredBounds(tag: String): Rect {
        var bounds: Rect? = null
        await("visible $tag") { bounds = boundsOrNull(tag); bounds != null }
        return requireNotNull(bounds)
    }

    private fun boundsOrNull(tag: String): Rect? {
        val roots = automation.windows.mapNotNull { it.root }.toMutableList()
        automation.rootInActiveWindow?.let { roots += it }
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { pending.addLast(it) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val id = node.viewIdResourceName.orEmpty()
            if (id == tag || id.endsWith(":id/$tag")) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) return bounds
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let { pending.addLast(it) } }
        }
        return null
    }

    private fun assertRectNear(label: String, expected: Rect, actual: Rect, tolerance: Int) {
        assertTrue("$label: expected=$expected actual=$actual tolerance=$tolerance",
            abs(expected.left - actual.left) <= tolerance && abs(expected.top - actual.top) <= tolerance &&
                abs(expected.right - actual.right) <= tolerance && abs(expected.bottom - actual.bottom) <= tolerance)
    }

    private inner class Fixture : Closeable {
        val root = File(app.cacheDir, "player-panel-test-${System.nanoTime()}").apply { mkdirs() }
        val track = File(root, "panel-test.wav").apply { writeBytes(silenceWav()) }
        private val preferences = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val changed = listOf("default_play_mode", "default_speed", "resume_playback", "eq_enabled", "show_loudness_waveform")
        private val saved = changed.associateWith { preferences.all[it] }
        init {
            assertTrue(preferences.edit().putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false)
                .putBoolean("eq_enabled", false).putBoolean("show_loudness_waveform", false).commit())
        }
        override fun close() {
            val editor = preferences.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            editor.remove("last_position_${track.path}")
            assertTrue("Restore original player preferences", editor.commit())
            root.deleteRecursively()
        }
    }

    private fun openPlayer(track: File): Session {
        val intent = Intent(app, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(track.path))
            .putExtra(PlayerActivity.EXTRA_START_INDEX, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onMain { app.startActivity(intent) }
        var activity: PlayerActivity? = null
        await("player resumes") {
            activity = mainValue {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PlayerActivity>().firstOrNull()
            }
            activity != null
        }
        return Session(requireNotNull(activity))
    }

    private inner class Session(val activity: PlayerActivity) : Closeable {
        val model = mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] }
        override fun close() {
            onMain { activity.finish() }
            await("player releases its resources") { mainValue { activity.isDestroyed } }
        }
    }

    private fun captureIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("playerScreenshot") != "true") return
        val bitmap = automation.takeScreenshot()
        assertNotNull("Capture player regression screenshot", bitmap)
        try {
            File(app.getExternalFilesDir(null), name).outputStream().use {
                bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap?.recycle() }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun <T> mainValue(action: () -> T): T {
        val value = AtomicReference<T>()
        onMain { value.set(action()) }
        return value.get()
    }

    private fun await(label: String, timeoutMs: Long = 12_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(60L)
        }
        throw AssertionError("Timed out: $label")
    }

    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            else -> error("Unsupported preference for $key")
        }
    }

    private fun silenceWav(): ByteArray {
        val sampleRate = 16_000
        val samples = sampleRate * 30
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2)
            put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples * 2)
        }.array()
    }
}
