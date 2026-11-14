package com.subtitleedit.player

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Verifies the actual touch path, including blank areas of both control overlays. */
@RunWith(AndroidJUnit4::class)
class PlayerSeekGestureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun audioDoesNotSwipeSeekButTheSeekSliderStillWorks() {
        Fixture().use { fixture ->
            val audio = File(fixture.root, "audio.wav").apply { writeBytes(silenceWav()) }
            openPlayer(audio).use { session ->
                session.pauseAt(10_000L)
                val model = session.model
                val area = session.viewport()
                val top = requiredBounds("player-top-overlay")
                val bottom = requiredBounds("player-bottom-overlay")
                val y = (top.bottom + bottom.top) / 2f
                assertNoSwipeSeek(session, "Visible audio artwork must not swipe-seek",
                    listOf(Point(area.width() * 0.3f, y), Point(area.width() * 0.6f, y)))
                onMain { model.setControlsVisible(false) }
                await("audio controls hide") { boundsOrNull("player-play") == null }
                assertNoSwipeSeek(session, "Hidden audio controls must not enable swipe seeking",
                    listOf(Point(area.width() * 0.3f, y), Point(area.width() * 0.6f, y)))

                onMain { model.setControlsVisible(true) }
                val slider = requiredBounds("player-seek")
                Thread.sleep(250L)
                swipe(listOf(Point(slider.left + slider.width() * 0.25f, slider.exactCenterY()),
                    Point(slider.left + slider.width() * 0.62f, slider.exactCenterY())))
                awaitState(model, "The explicit audio seek slider remains usable") {
                    !it.playing && it.seekPreviewMs == null && it.positionMs > 18_000L
                }
            }
        }
    }

    @Test fun videoSwipeMustStartOutsideMasksAndContinuesWhenEnteringOrReleasingInside() {
        Fixture().use { fixture ->
            val video = File(fixture.root, "video.mkv")
            val generated = FFmpegKit.executeWithArguments(arrayOf(
                "-y", "-f", "lavfi", "-i", "color=c=red:s=320x180:r=8:d=40",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=40",
                "-map", "0:v", "-map", "1:a", "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac", video.path
            ))
            assertTrue(generated.getAllLogsAsString().takeLast(2000), ReturnCode.isSuccess(generated.getReturnCode()))
            openPlayer(video).use { session ->
                session.pauseAt(10_000L)
                val model = session.model
                val viewport = session.viewport()
                val top = requiredBounds("player-top-overlay")
                val bottom = requiredBounds("player-bottom-overlay")
                val centerY = (top.bottom + bottom.top) / 2f
                val fromX = viewport.left + viewport.width() * 0.3f
                val toX = viewport.left + viewport.width() * 0.6f

                assertNoSwipeSeek(session, "Blank top-overlay space must not seek",
                    listOf(Point(fromX, top.exactCenterY()), Point(toX, top.exactCenterY())))
                // Stay on the time-label row, between its edge labels and above the seek slider.
                val density = mainValue { session.activity.resources.displayMetrics.density }
                val bottomBlankY = bottom.top + 5f * density
                assertNoSwipeSeek(session, "Blank bottom-overlay space must not seek",
                    listOf(Point(fromX, bottomBlankY), Point(toX, bottomBlankY)))
                assertNoSwipeSeek(session, "A drag beginning in a mask must not begin seeking after leaving it",
                    listOf(Point(fromX, top.exactCenterY()), Point(viewport.width() * 0.42f, top.exactCenterY()),
                        Point(toX, centerY)))

                var sawPreview = false
                swipe(listOf(Point(fromX, centerY), Point(toX, centerY))) {
                    if (state(model).seekPreviewMs != null) sawPreview = true
                }
                assertTrue("An exposed video swipe must preview the seek before release", sawPreview)
                awaitState(model, "An exposed video swipe commits its seek") {
                    !it.playing && it.seekPreviewMs == null && it.positionMs > 18_000L
                }

                session.pauseAt(10_000L)
                var previewBeforeEnteringMask = false
                var finalCrossingPreview: Long? = null
                swipe(listOf(Point(fromX, centerY), Point(viewport.width() * 0.42f, centerY),
                    Point(toX, top.exactCenterY()))) {
                    if (state(model).seekPreviewMs != null) previewBeforeEnteringMask = true
                    finalCrossingPreview = state(model).seekPreviewMs
                }
                assertTrue("The crossing fixture must first enter normal video seek handling", previewBeforeEnteringMask)
                assertNotNull("An accepted drag must keep its preview after entering a mask", finalCrossingPreview)
                awaitState(model, "Moving into a visible mask must still commit an accepted seek") {
                    !it.playing && it.seekPreviewMs == null && it.positionMs > 18_000L &&
                        abs(it.positionMs - requireNotNull(finalCrossingPreview)) < 200L
                }

                session.pauseAt(10_000L)
                var previewBeforeRelease = false
                swipe(listOf(Point(fromX, centerY), Point(toX, centerY)),
                    releaseAt = Point(toX, top.exactCenterY())) {
                    if (state(model).seekPreviewMs != null) previewBeforeRelease = true
                }
                assertTrue("The release-boundary fixture must first preview a seek", previewBeforeRelease)
                awaitState(model, "Releasing inside a mask must commit a drag that began outside") {
                    !it.playing && it.seekPreviewMs == null && it.positionMs > 18_000L
                }

                session.pauseAt(10_000L)
                onMain { model.setControlsVisible(false) }
                await("video controls finish hiding") { boundsOrNull("player-play") == null }
                Thread.sleep(100L)
                sawPreview = false
                swipe(listOf(Point(fromX, top.exactCenterY()), Point(toX, top.exactCenterY()))) {
                    if (state(model).seekPreviewMs != null) sawPreview = true
                }
                assertTrue("A former mask area becomes video space after controls hide", sawPreview)
                awaitState(model, "Hidden masks no longer block video swipe seeking") {
                    !it.playing && it.seekPreviewMs == null && it.positionMs > 18_000L
                }
            }
        }
    }

    private fun assertNoSwipeSeek(session: Session, label: String, points: List<Point>) {
        val before = state(session.model).positionMs
        var sawPreview = false
        swipe(points) { if (state(session.model).seekPreviewMs != null) sawPreview = true }
        assertFalse("$label must not create a preview", sawPreview)
        assertUnchangedPosition(session.model, before, label)
    }

    private fun assertUnchangedPosition(model: PlayerViewModel, before: Long, label: String) {
        Thread.sleep(300L)
        val after = state(model)
        assertNull("$label must leave no pending preview", after.seekPreviewMs)
        assertFalse("$label must preserve pause", after.playing)
        assertTrue("$label: before=$before after=${after.positionMs}", abs(after.positionMs - before) < 200L)
    }

    private data class Point(val x: Float, val y: Float)

    private fun swipe(points: List<Point>, releaseAt: Point = points.last(), afterMove: () -> Unit = {}) {
        require(points.size >= 2)
        val downTime = SystemClock.uptimeMillis()
        injectTouch(downTime, MotionEvent.ACTION_DOWN, points.first())
        try {
            points.zipWithNext().forEach { (from, to) ->
                repeat(14) { step ->
                    Thread.sleep(18L)
                    val fraction = (step + 1) / 14f
                    injectTouch(downTime, MotionEvent.ACTION_MOVE,
                        Point(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction))
                    afterMove()
                }
            }
        } finally {
            injectTouch(downTime, MotionEvent.ACTION_UP, releaseAt)
        }
    }

    private fun injectTouch(downTime: Long, action: Int, point: Point) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("Inject player swipe", automation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private inner class Fixture : Closeable {
        val root = File(app.cacheDir, "player-seek-gesture-${System.nanoTime()}").apply { mkdirs() }
        private val preferences = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val keys = listOf("default_play_mode", "default_speed", "resume_playback", "eq_enabled", "show_loudness_waveform")
        private val saved = keys.associateWith { preferences.all[it] }
        init {
            assertTrue(preferences.edit().putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false)
                .putBoolean("eq_enabled", false).putBoolean("show_loudness_waveform", false).commit())
        }
        override fun close() {
            val editor = preferences.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            root.listFiles().orEmpty().forEach { editor.remove("last_position_${it.path}") }
            assertTrue("Restore user player preferences", editor.commit())
            root.deleteRecursively()
        }
    }

    private fun openPlayer(track: File): Session {
        onMain { app.startActivity(Intent(app, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(track.path))
            .putExtra(PlayerActivity.EXTRA_START_INDEX, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        var activity: PlayerActivity? = null
        await("player resumes") {
            activity = mainValue { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PlayerActivity>().firstOrNull() }
            activity != null
        }
        return Session(requireNotNull(activity))
    }

    private inner class Session(val activity: PlayerActivity) : Closeable {
        val model = mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] }
        private val oldOrientation = mainValue { activity.requestedOrientation }
        private val oldAccessibilityFlags = automation.serviceInfo.flags
        init {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            onMain { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("portrait gesture fixture") {
                mainValue { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            }
            awaitState(model, "fixture media is ready") { it.ready }
            Thread.sleep(350L)
        }
        fun pauseAt(position: Long) {
            onMain { model.pause(); model.seekTo(position); model.setControlsVisible(true) }
            awaitState(model, "paused seek settles") { !it.playing && abs(it.positionMs - position) < 150L }
            Thread.sleep(350L)
        }
        fun viewport() = mainValue { Rect().also { activity.window.decorView.getGlobalVisibleRect(it) } }
        override fun close() {
            onMain { activity.requestedOrientation = oldOrientation; activity.finish() }
            await("player releases fixture") { mainValue { activity.isDestroyed } }
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldAccessibilityFlags }
        }
    }

    private fun requiredBounds(tag: String): Rect {
        var bounds: Rect? = null
        await("visible $tag") { bounds = boundsOrNull(tag); bounds != null }
        return requireNotNull(bounds)
    }
    private fun boundsOrNull(tag: String): Rect? {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        automation.windows.mapNotNull { it.root }.forEach { nodes.addLast(it) }
        automation.rootInActiveWindow?.let { nodes.addLast(it) }
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            val id = node.viewIdResourceName.orEmpty()
            if (id == tag || id.endsWith(":id/$tag")) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty) return bounds
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let { nodes.addLast(it) } }
        }
        return null
    }
    private fun state(model: PlayerViewModel) = mainValue { model.state.value }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun <T> mainValue(action: () -> T): T {
        val result = AtomicReference<T>()
        onMain { result.set(action()) }
        return result.get()
    }
    private fun awaitState(model: PlayerViewModel, label: String, predicate: (PlayerUiState) -> Boolean) = await(label) {
        val current = state(model)
        current.error?.let { throw AssertionError("$label: $it") }
        predicate(current)
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(75L)
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
            else -> error("Unexpected fixture preference value")
        }
    }
    private fun silenceWav(): ByteArray {
        val sampleRate = 16_000
        val count = sampleRate * 40
        return ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
        }.array()
    }
}
