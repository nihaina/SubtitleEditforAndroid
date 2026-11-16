package com.subtitleedit.player

import android.graphics.Bitmap
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.mpv.MPVLib
import com.subtitleedit.MainActivity
import com.subtitleedit.R
import com.subtitleedit.EditorActivity
import com.subtitleedit.editor.EditorMediaType
import com.subtitleedit.editor.EditorPlaybackController
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SharedFileCache
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native output, input routing and session identity across the two presentations. */
@RunWith(AndroidJUnit4::class)
class PlayerSheetHostTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun audioInMainWindowRetainsQueueControlsAndCollapsedStateAcrossRecreation() {
        val preferences = PlayerPreferences.getInstance(app)
        val previousMode = preferences.playerModeEnabled
        preferences.playerModeEnabled = true
        val root = File(app.cacheDir, "player-sheet-audio-${System.nanoTime()}").apply { mkdirs() }
        val first = File(root, "first.wav")
        val second = File(root, "second.wav")
        val generated = FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i",
            "sine=frequency=440:duration=45", first.path))
        assertTrue(generated.getAllLogsAsString(), ReturnCode.isSuccess(generated.getReturnCode()))
        first.copyTo(second)
        val secondPreviewKey = requireNotNull(SharedFileCache.attributes.get(second)).cacheKey
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                lateinit var model: PlayerViewModel
                scenario.onActivity {
                    activity = it
                    model = ViewModelProvider(it)[PlayerViewModel::class.java]
                    model.openQueue(listOf(PlayerTrack(first.path, first.name, false),
                        PlayerTrack(second.path, second.name, false)), 0)
                }
                await("main window audio playback") { model.state.value.playing && model.state.value.positionMs > 300 }
                Thread.sleep(900)
                main { activity.onBackPressedDispatcher.onBackPressed() }
                await("main window back docks audio") { !model.expanded.value }
                Thread.sleep(1000)
                val output = main { findOutput(activity.window.decorView)!! }
                clickTag("mini-player-next")
                await("mini bar switches to the next file") {
                    model.state.value.currentTrack?.path == second.path && model.state.value.ready && model.state.value.playing
                }
                runBlocking { PlayerLibraryWriter.execute {} }
                assertEquals("Mini-bar track changes must not invalidate the song's file-row preview", secondPreviewKey,
                    SharedFileCache.attributes.get(second)?.cacheKey)
                assertSame(output, main { findOutput(activity.window.decorView) })
                clickTag("mini-player-play-pause")
                await("mini bar pauses the native output") {
                    !model.state.value.playing && MPVLib.getPropertyBoolean("pause") == true
                }
                // UI pause is optimistic; use the native acknowledgement as the position baseline.
                val pausedAt = main { (requireNotNull(MPVLib.getPropertyDouble("time-pos")) * 1000).toLong() }
                Thread.sleep(400)
                assertTrue("Paused progress must stay at the native position: $pausedAt -> " +
                    main { model.state.value.positionMs },
                    main { kotlin.math.abs(model.state.value.positionMs - pausedAt) < 200 })
                capture("player-sheet-main-audio.png")
                scenario.recreate()
                scenario.onActivity {
                    activity = it
                    assertSame(model, ViewModelProvider(it)[PlayerViewModel::class.java])
                }
                await("recreated mini bar preserves paused playback") { model.state.value.ready && !model.state.value.playing }
                assertFalse(main { model.expanded.value })
                assertTrue(main { kotlin.math.abs(model.state.value.positionMs - pausedAt) < 250 })
                Thread.sleep(700)
                clickTag("mini-player-play-pause")
                await("mini bar resumes audio") { model.state.value.playing }
                val newOutput = main { findOutput(activity.window.decorView)!! }
                main { model.openQueue(listOf(PlayerTrack(second.path, second.name, false)), 0) }
                await("a new selection replaces the queue in place") {
                    model.state.value.currentTrack?.path == second.path && model.state.value.ready && model.state.value.playing
                }
                assertSame(newOutput, main { findOutput(activity.window.decorView) })
                assertNotSame(output, newOutput)
                main { model.suspendOutput() }
                assertFalse(main { model.state.value.ready })
                main { model.resumeOutput() }
                await("output resumes after another mpv owner") { model.state.value.ready && model.state.value.playing }
                assertNotSame(newOutput, main { findOutput(activity.window.decorView) })

                // Exercise the actual shared native owner, including Android's return ordering.
                main {
                    activity.startActivity(Intent(activity, EditorActivity::class.java)
                        .putExtra(EditorActivity.EXTRA_FILE_PATH, first.path)
                        .putExtra(EditorActivity.EXTRA_MEDIA_TYPE, EditorMediaType.AUDIO.name))
                }
                await("editor receives exclusive native playback") {
                    val editor = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<EditorActivity>().firstOrNull()
                    val controller = editor?.let {
                        EditorActivity::class.java.getDeclaredField("playbackController")
                            .apply { isAccessible = true }.get(it) as? EditorPlaybackController
                    }
                    model.outputSuspended.value && controller != null && controller.durationMs > 0L
                }
                main {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<EditorActivity>().single().finish()
                }
                await("main player resumes after editor releases native playback") {
                    !model.outputSuspended.value && model.state.value.ready && model.state.value.playing &&
                        MPVLib.getPropertyString("path") == second.path
                }
                main { model.collapse() }
                Thread.sleep(900)
                clickTag("directory-player-mode")
                await("leaving audio mode removes the active session") { model.state.value.currentTrack == null }
                Thread.sleep(500)
                assertFalse(preferences.playerModeEnabled)
                assertFalse(main { model.state.value.playing })
                assertNull(main { findOutput(activity.window.decorView) })
                assertFalse(hasTag("mini-player"))
                assertFalse(hasTag("main-player-host"))
                clickTag("directory-player-mode")
                await("audio mode can be enabled again") { preferences.playerModeEnabled }
                main { model.openQueue(listOf(PlayerTrack(first.path, first.name, false)), 0) }
                await("re-enabled audio mode can start a new session") { model.state.value.ready && model.state.value.playing }
            }
        } finally {
            preferences.playerModeEnabled = previousMode
            root.deleteRecursively()
        }
    }

    @Test fun videoDocksWithoutReloadingAndBothSwipeDirectionsWork() {
        val root = File(app.cacheDir, "player-sheet-${System.nanoTime()}").apply { mkdirs() }
        val video = File(root, "sheet-video.mp4")
        val generated = FFmpegKit.executeWithArguments(arrayOf("-y", "-f", "lavfi", "-i",
            "color=c=red:s=320x180:r=10:d=90", "-f", "lavfi", "-i", "sine=frequency=440:duration=90",
            "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac", "-shortest", video.path))
        assertTrue(generated.getAllLogsAsString(), ReturnCode.isSuccess(generated.getReturnCode()))
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var activity: ComponentActivity
                lateinit var model: PlayerViewModel
                scenario.onActivity {
                    activity = it
                    model = ViewModelProvider(it)[PlayerViewModel::class.java]
                    model.openQueue(listOf(PlayerTrack(video.path, video.name, true)), 0)
                    it.setContent {
                        SubtitleEditComposeTheme {
                            MainPlayerHost(model, {}, {}, { _, _ -> }) { mini ->
                                Column(Modifier.fillMaxSize()) {
                                    Box(Modifier.weight(1f)) { Text("Main page") }
                                    mini()
                                    Box(Modifier.fillMaxWidth().height(60.dp)) { Text("Navigation") }
                                }
                            }
                        }
                    }
                }
                await("initial video playback") { model.state.value.ready && model.state.value.playing && model.state.value.positionMs > 300 }
                Thread.sleep(900)
                val output = main { findOutput(activity.window.decorView)!! }
                val initialSize = main { output.width to output.height }
                val position = main { model.state.value.positionMs }
                main { model.collapse() }
                Thread.sleep(1100)
                assertSame(output, main { findOutput(activity.window.decorView) })
                assertEquals(initialSize, main { output.width to output.height })
                assertTrue(main { model.state.value.positionMs > position + 400 && model.state.value.playing })
                assertEquals("gpu-next", main { MPVLib.getPropertyString("current-vo") })
                capture("player-sheet-collapsed.png")

                val content = main { activity.findViewById<View>(android.R.id.content) }
                val origin = IntArray(2).also { main { content.getLocationOnScreen(it) } }
                val width = main { content.width.toFloat() }
                val height = main { content.height.toFloat() }
                val density = activity.resources.displayMetrics.density
                val miniY = origin[1] + height - 96f * density
                swipe(origin[0] + width * 0.35f, miniY, origin[0] + width * 0.35f, origin[1] + height * 0.25f)
                await("mini player upward swipe expands") { model.expanded.value }
                Thread.sleep(900)
                assertSame(output, main { findOutput(activity.window.decorView) })
                capture("player-sheet-expanded.png")
                swipe(origin[0] + width * 0.5f, origin[1] + 80f * density,
                    origin[0] + width * 0.5f, origin[1] + height * 0.9f)
                await("top downward swipe collapses") { !model.expanded.value }
                Thread.sleep(900)
                assertSame(output, main { findOutput(activity.window.decorView) })
                assertTrue(main { model.state.value.playing })

                main { model.expand() }
                Thread.sleep(900)
                main { activity.onBackPressedDispatcher.onBackPressed() }
                await("system back collapses instead of finishing") { !model.expanded.value }
                assertFalse(main { activity.isFinishing })
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun findOutput(view: View): PlayerMpvView? {
        if (view is PlayerMpvView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findOutput(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun clickTag(tag: String) {
        val deadline = SystemClock.elapsedRealtime() + 5000L
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || node.viewIdResourceName == tag ||
                tag == "directory-player-mode" && node.contentDescription?.toString() == app.getString(R.string.player_mode_title)) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        while (SystemClock.elapsedRealtime() < deadline) {
            val node = find(instrumentation.uiAutomation.rootInActiveWindow)
            if (node != null) {
                val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                val down = SystemClock.uptimeMillis()
                val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                val release = MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                try { instrumentation.sendPointerSync(press); instrumentation.sendPointerSync(release) }
                finally { press.recycle(); release.recycle() }
                return
            }
            Thread.sleep(100)
        }
        capture("player-sheet-missing-control.png")
        fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): String {
            if (node == null) return "null"
            val text = "${" ".repeat(depth)}${node.viewIdResourceName} ${node.text} ${node.contentDescription}\n"
            return text + (0 until node.childCount).joinToString("") { describe(node.getChild(it), depth + 1) }
        }
        fail("Missing clickable node: $tag\n${describe(instrumentation.uiAutomation.rootInActiveWindow)}")
    }

    private fun hasTag(tag: String): Boolean {
        fun find(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.viewIdResourceName == tag) return true
            return (0 until node.childCount).any { find(node.getChild(it)) }
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun swipe(x: Float, y: Float, endX: Float, endY: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, at: Long, px: Float, py: Float) {
            val event = MotionEvent.obtain(down, at, action, px, py, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, down, x, y)
        for (step in 1..24) {
            Thread.sleep(12)
            val p = step / 24f
            send(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), x + (endX - x) * p, y + (endY - y) * p)
        }
        send(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), endX, endY)
    }

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main(condition)) return
            Thread.sleep(80)
        }
        fail("Timed out: $label")
    }

    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }
}
