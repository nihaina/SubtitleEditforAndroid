package com.subtitleedit.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real pointer events exercise locking without replacing the player's animation clock. */
@RunWith(AndroidJUnit4::class)
class PlayerVideoLockTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun rightEdgeLockButtonCanFadeReappearAndUnlockByRealTouch() = Fixture().use { fixture ->
        openPlayer(listOf(fixture.video)).use { session ->
            session.pauseAt(10_000L)
            val model = session.model
            val button = requiredBounds(LOCK_TAG)
            val player = requiredBounds("player-screen")
            assertEquals("The video lock touch target reaches the player right edge", player.right, button.right)
            captureIfRequested("player-video-lock-open.png")
            tapLockButton(pressedScreenshot = "player-video-lock-pressed.png") {
                assertFalse("Lock only activates on release", state(model).controlsLocked)
            }
            awaitState(model, "lock hides playback controls") { it.controlsLocked && !it.controlsVisible }
            await("lock closes normal control overlays") { findTag("player-top-overlay") == null }
            captureIfRequested("player-video-lock-locked.png")
            await("locked icon fades after two seconds", timeoutMs = 5_000L) { findTag(LOCK_TAG) == null }

            tap(player.exactCenterX(), player.exactCenterY())
            await("locked screen tap reveals the unlock button") { findTag(LOCK_TAG) != null }
            assertTrue(state(model).controlsLocked)
            assertFalse("A screen tap must not reveal normal controls while locked", state(model).controlsVisible)
            assertNull(findTag("player-top-overlay"))
            tapLockButton()
            awaitState(model, "unlock restores playback controls") { !it.controlsLocked && it.controlsVisible }
            await("normal controls return after unlock") { findTag("player-top-overlay") != null }
        }
    }

    @Test fun lockedVideoRejectsSeekVolumeDoubleTapAndPanelOpeningUntilBackUnlocks() = Fixture().use { fixture ->
        openPlayer(listOf(fixture.video)).use { session ->
            session.pauseAt(10_000L)
            val model = session.model
            tapLockButton()
            awaitState(model, "video is locked") { it.controlsLocked && !it.controlsVisible }
            await("normal overlays finish hiding") { findTag("player-top-overlay") == null }
            val before = state(model)
            val area = requiredBounds("player-screen")
            val fromX = area.left + area.width() * 0.3f
            val toX = area.left + area.width() * 0.65f
            swipe(fromX, area.exactCenterY(), toX, area.exactCenterY())
            swipe(fromX, area.top + area.height() * 0.65f, fromX, area.top + area.height() * 0.3f)
            tap(fromX, area.exactCenterY())
            Thread.sleep(75L)
            tap(fromX, area.exactCenterY())
            onMain { model.setControlsVisible(true); model.openPanel(PlayerPanel.EQUALIZER) }
            Thread.sleep(350L)
            val after = state(model)
            assertTrue(after.controlsLocked)
            assertFalse(after.controlsVisible)
            assertFalse("Locked gestures must preserve pause", after.playing)
            assertNull("Locked gestures must not leave a seek preview", after.seekPreviewMs)
            assertTrue("Locked swipes and double taps must not seek: ${before.positionMs} -> ${after.positionMs}",
                abs(after.positionMs - before.positionMs) < 200L)
            assertEquals("Locked vertical swipes must not change volume", before.volumePercent, after.volumePercent)
            assertEquals("A stale UI callback cannot reopen a locked player's panel", PlayerPanel.NONE, after.panel)

            pressBack()
            awaitState(model, "Back unlocks before leaving playback") { !it.controlsLocked && it.controlsVisible }
            assertFalse(mainValue { session.activity.isFinishing })
        }
    }

    @Test fun recreationKeepsVideoLockedAndSwitchingToAudioClearsTheLock() = Fixture().use { fixture ->
        openPlayer(listOf(fixture.video, fixture.audio)).use { session ->
            session.pauseAt(10_000L)
            val model = session.model
            tapLockButton()
            awaitState(model, "video is locked before recreation") { it.controlsLocked && !it.controlsVisible }
            session.recreate()
            awaitState(model, "recreated video retains its lock and pause") {
                it.ready && it.isVideo && !it.playing && it.controlsLocked && !it.controlsVisible
            }
            await("recreated locked screen has no normal overlay") { findTag("player-top-overlay") == null }
            onMain { session.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("locked video rotates into full-screen landscape") {
                mainValue { session.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE } &&
                    requiredBounds("player-screen").let { it.width() > it.height() }
            }
            val area = requiredBounds("player-screen")
            tap(area.exactCenterX(), area.exactCenterY())
            await("recreated lock remains available") { findTag(LOCK_TAG) != null }
            assertEquals("Landscape lock remains at the right edge", area.right, requiredBounds(LOCK_TAG).right)
            captureIfRequested("player-video-lock-landscape.png")
            tapLockButton()
            awaitState(model, "recreated lock can still be unlocked") { !it.controlsLocked && it.controlsVisible }
            tapLockButton()
            awaitState(model, "lock again before external track change") { it.controlsLocked }
            onMain { model.playIndex(1) }
            awaitState(model, "switching to audio clears video-only lock") {
                it.currentIndex == 1 && it.ready && !it.isVideo && !it.controlsLocked && it.controlsVisible
            }
            await("audio does not display a video lock control") { findTag(LOCK_TAG) == null }
        }
    }

    @Test fun audioNeverOffersOrEnablesTheVideoLock() = Fixture().use { fixture ->
        openPlayer(listOf(fixture.audio)).use { session ->
            session.pauseAt(10_000L)
            assertFalse(state(session.model).isVideo)
            assertNull("Audio has no lock button", findTag(LOCK_TAG))
            onMain { session.model.setControlsVisible(false); session.model.setVideoControlsLocked(true) }
            assertFalse("The lock API is video-only", state(session.model).controlsLocked)
            assertFalse("Ignoring audio lock must preserve the existing controls state", state(session.model).controlsVisible)
            assertNull(findTag(LOCK_TAG))
            onMain { session.model.setControlsVisible(true) }
            await("normal audio controls remain usable") { findTag("player-top-overlay") != null }
        }
    }

    @Test fun nextVideoKeepsItsUnlockButtonRecoverableAfterThePreviousButtonFades() = Fixture().use { fixture ->
        val secondVideo = File(fixture.root, "next-video.mkv").also { fixture.video.copyTo(it) }
        openPlayer(listOf(fixture.video, secondVideo)).use { session ->
            session.pauseAt(10_000L)
            val model = session.model
            tapLockButton()
            awaitState(model, "first video is locked") { it.controlsLocked && !it.controlsVisible }
            onMain { model.playIndex(1) }
            awaitState(model, "next video retains the lock") {
                it.currentIndex == 1 && it.ready && it.isVideo && it.controlsLocked && !it.controlsVisible
            }
            onMain { model.pause() }
            await("previous track's unlock button finishes fading", timeoutMs = 5_000L) { findTag(LOCK_TAG) == null }
            val area = requiredBounds("player-screen")
            tap(area.exactCenterX(), area.exactCenterY())
            await("new track's screen tap reveals its own unlock button") { findTag(LOCK_TAG) != null }
            tapLockButton()
            awaitState(model, "next video can be unlocked without leaving playback") {
                it.currentIndex == 1 && !it.controlsLocked && it.controlsVisible
            }
        }
    }

    private fun tapLockButton(pressedScreenshot: String? = null, whilePressed: () -> Unit = {}) {
        val target = requiredBounds(LOCK_TAG)
        val player = requiredBounds("player-screen")
        // The accessibility target may expand, so use the visible 32dp circle's center.
        val x = player.right - 16f * app.resources.displayMetrics.density
        val y = target.exactCenterY()
        assertTrue("The visible lock center must receive touches", target.contains(x.toInt(), y.toInt()))
        val downTime = SystemClock.uptimeMillis()
        inject(downTime, MotionEvent.ACTION_DOWN, x, y)
        var released = false
        try {
            Thread.sleep(if (pressedScreenshot == null) 40L else 180L)
            pressedScreenshot?.let(::captureIfRequested)
            whilePressed()
            inject(downTime, MotionEvent.ACTION_UP, x, y)
            released = true
        } finally {
            if (!released) inject(downTime, MotionEvent.ACTION_CANCEL, x, y)
        }
    }

    private fun tap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        inject(downTime, MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(40L)
        inject(downTime, MotionEvent.ACTION_UP, x, y)
    }

    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val downTime = SystemClock.uptimeMillis()
        inject(downTime, MotionEvent.ACTION_DOWN, fromX, fromY)
        try {
            repeat(15) { index ->
                Thread.sleep(18L)
                val part = (index + 1) / 15f
                inject(downTime, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * part, fromY + (toY - fromY) * part)
            }
        } finally { inject(downTime, MotionEvent.ACTION_UP, toX, toY) }
    }

    private fun inject(downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("Inject real player touch", instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private inner class Fixture : Closeable {
        val root = File(app.cacheDir, "player-video-lock-${System.nanoTime()}").apply { mkdirs() }
        val audio by lazy { File(root, "audio.wav").apply { writeBytes(silenceWav()) } }
        val video by lazy {
            File(root, "video.mkv").also { file ->
                val generated = FFmpegKit.executeWithArguments(arrayOf(
                    "-y", "-f", "lavfi", "-i", "color=c=red:s=320x180:r=8:d=30",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=30",
                    "-map", "0:v", "-map", "1:a", "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac", file.path
                ))
                assertTrue(generated.getAllLogsAsString().takeLast(2000), ReturnCode.isSuccess(generated.getReturnCode()))
            }
        }
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
            assertTrue(editor.commit())
            root.deleteRecursively()
        }
    }

    private fun openPlayer(tracks: List<File>): Session {
        onMain { app.startActivity(Intent(app, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, ArrayList(tracks.map { it.path }))
            .putExtra(PlayerActivity.EXTRA_START_INDEX, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return Session(awaitActivity()).also { session ->
            onMain { session.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            awaitState(session.model, "fixture media is ready") { it.ready }
        }
    }

    private inner class Session(var activity: PlayerActivity) : Closeable {
        val model = mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] }
        fun pauseAt(position: Long) {
            onMain { model.pause(); model.seekTo(position); model.setControlsVisible(true) }
            awaitState(model, "fixture pause and seek settle") { !it.playing && abs(it.positionMs - position) < 150L }
            await("normal controls are available") { findTag("player-top-overlay") != null }
            Thread.sleep(300L)
        }
        fun recreate() {
            val previous = activity
            onMain { previous.recreate() }
            activity = awaitActivity(previous)
            assertSame(model, mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] })
        }
        override fun close() {
            onMain { activity.finish() }
            await("fixture player finishes") { mainValue { activity.isDestroyed } }
        }
    }

    private fun awaitActivity(previous: PlayerActivity? = null): PlayerActivity {
        var activity: PlayerActivity? = null
        await("player activity resumes") {
            activity = mainValue { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PlayerActivity>().firstOrNull { it !== previous } }
            activity != null
        }
        return requireNotNull(activity)
    }

    private fun findTag(tag: String): AccessibilityNodeInfo? {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let { nodes.addLast(it) }
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            val id = node.viewIdResourceName.orEmpty()
            if (id == tag || id.endsWith(":id/$tag")) return node
            repeat(node.childCount) { node.getChild(it)?.let(nodes::addLast) }
        }
        return null
    }
    private fun requiredBounds(tag: String): Rect {
        var bounds: Rect? = null
        await("visible $tag") {
            bounds = findTag(tag)?.let { node -> Rect().also(node::getBoundsInScreen) }
            bounds?.isEmpty == false
        }
        return requireNotNull(bounds)
    }
    private fun state(model: PlayerViewModel) = mainValue { model.state.value }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun <T> mainValue(action: () -> T): T {
        val value = AtomicReference<T>()
        onMain { value.set(action()) }
        return value.get()
    }
    private fun awaitState(model: PlayerViewModel, label: String, predicate: (PlayerUiState) -> Boolean) = await(label) {
        val current = state(model)
        current.error?.let { throw AssertionError("$label: $it") }
        predicate(current)
    }
    private fun await(label: String, timeoutMs: Long = 12_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(75L)
        }
        captureIfRequested("player-video-lock-failure.png")
        throw AssertionError("Timed out: $label")
    }
    private fun captureIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("playerScreenshot") != "true") return
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try { File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
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
        val sampleRate = 8_000
        val count = sampleRate * 30
        return ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
        }.array()
    }

    private companion object { const val LOCK_TAG = "player-video-lock" }
}
