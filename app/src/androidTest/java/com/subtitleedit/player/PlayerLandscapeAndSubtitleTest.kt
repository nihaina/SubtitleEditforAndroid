package com.subtitleedit.player

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.mpv.MPVLib
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sin

/** Tests real subtitle selection, rendered waveform persistence and portrait/landscape geometry. */
@RunWith(AndroidJUnit4::class)
class PlayerLandscapeAndSubtitleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun audioUsesFirstSidecarAndKeepsOnlyActualWaveformWhenPausedAndRotated() {
        Fixture("audio").use { fixture ->
            val audio = File(fixture.root, "audio.wav").apply { writeBytes(toneWav()) }
            File(fixture.root, "audio.srt").writeText("1\n00:00:00,000 --> 00:00:29,900\nfirst sidecar\n")
            File(fixture.root, "audio.vtt").writeText("WEBVTT\n\n00:00:00.000 --> 00:00:29.900\nsecond sidecar\n")
            openPlayer(audio).use { session ->
                val model = session.model
                session.rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
                awaitState(model, "first sidecar is selected automatically") {
                    it.ready && it.playing && it.selectedSubtitleId == "external:${File(fixture.root, "audio.srt").path}" &&
                        it.subtitleText.contains("first sidecar") && it.positionMs > 2_500L
                }
                assertEquals("Unconfigured player subtitle size defaults to 30", 30, state(model).subtitleFontSize)
                val portraitPlay = requiredBounds("player-play")
                val portraitDensity = mainValue { session.activity.resources.displayMetrics.density }
                assertTrue("Portrait play button is compact: $portraitPlay", portraitPlay.width() <= 61f * portraitDensity)
                assertToolsInControls(requiredBounds("player-controls"), requiredBounds("player-tools-row"))
                onMain { model.pause() }
                awaitState(model, "pause settles") { !it.playing }
                Thread.sleep(350L)
                val waveform = requiredBounds("player-loudness-waveform")
                val first = cropScreenshot(waveform)
                try {
                    assertTrue("Pausing must preserve the already measured bars", coloredPixelCount(first) > 15)
                    assertNoBackgroundBars(first)
                    Thread.sleep(400L)
                    val later = cropScreenshot(requiredBounds("player-loudness-waveform"))
                    try {
                        assertEquals(first.width, later.width)
                        assertEquals(first.height, later.height)
                        assertTrue("Waveform history must remain frozen during pause", first.sameAs(later))
                    } finally { later.recycle() }
                } finally { first.recycle() }
                captureIfRequested("player-paused-waveform-preview.png")
                session.rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                assertLandscapeGeometry(session)
                captureIfRequested("player-landscape-audio-preview.png")
                onMain { model.selectSubtitle(model.state.value.subtitleOptions.first { it.kind == PlayerSubtitleKind.NONE }) }
                awaitState(model, "manual subtitle disable") { it.selectedSubtitleId == "none" }
                session.rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
                awaitState(model, "rotation preserves manually disabled subtitles") { it.ready && it.selectedSubtitleId == "none" }
                assertToolsInControls(requiredBounds("player-controls"), requiredBounds("player-tools-row"))
            }
        }
    }

    @Test fun videoUsesFirstEmbeddedSubtitleAndLandscapeControlsLeaveTheViewportClear() {
        Fixture("video").use { fixture ->
            val video = File(fixture.root, "video.mkv")
            val first = File(fixture.root, "first.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:29,900\nfirst embedded\n")
            }
            val second = File(fixture.root, "second.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:29,900\nsecond embedded\n")
            }
            val generated = FFmpegKit.executeWithArguments(arrayOf(
                "-y", "-f", "lavfi", "-i", "color=c=red:s=320x180:r=10:d=30",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=30",
                "-i", first.path, "-i", second.path,
                "-map", "0:v", "-map", "1:a", "-map", "2:s", "-map", "3:s",
                "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac", "-c:s", "srt",
                "-disposition:s:0", "0", "-disposition:s:1", "default", video.path
            ))
            assertTrue(generated.getAllLogsAsString().takeLast(2000), ReturnCode.isSuccess(generated.getReturnCode()))
            openPlayer(video).use { session ->
                val model = session.model
                awaitState(model, "first embedded subtitle wins even when the second is container-default") {
                    it.ready && it.playing && it.subtitleOptions.count { option -> option.kind == PlayerSubtitleKind.INTERNAL } == 2 &&
                        it.selectedSubtitleId == it.subtitleOptions.first { option -> option.kind == PlayerSubtitleKind.INTERNAL }.id &&
                        it.subtitleText.contains("first embedded")
                }
                assertEquals(30, state(model).subtitleFontSize)
                assertEquals(30.0, mainValue { MPVLib.getPropertyDouble("sub-font-size") } ?: -1.0, 0.01)
                onMain { model.setSubtitleFontSize(60) }
                await("native subtitle maximum reaches 60") { mainValue { MPVLib.getPropertyDouble("sub-font-size") == 60.0 } }
                assertEquals(60, state(model).subtitleFontSize)
                onMain { model.setSubtitleFontSize(90) }
                assertEquals("Subtitle size clamps to the new upper limit", 60, state(model).subtitleFontSize)
                session.rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                assertLandscapeGeometry(session)
                captureIfRequested("player-landscape-video-controls-preview.png")
                onMain { model.setControlsVisible(false) }
                await("video controls finish fading away") { boundsOrNull("player-play") == null }
                Thread.sleep(100L)
                val clean = automation.takeScreenshot() ?: throw AssertionError("No video screenshot")
                try {
                    val pixel = clean.getPixel(clean.width / 2, clean.height / 2)
                    assertTrue("The central video viewport remains unobscured", Color.red(pixel) > 150 && Color.green(pixel) < 100 && Color.blue(pixel) < 100)
                    saveIfRequested(clean, "player-landscape-video-clear-preview.png")
                } finally { clean.recycle() }
                onMain { model.setControlsVisible(true) }
                await("controls return after fading in") { boundsOrNull("player-play") != null }
                assertLandscapeGeometry(session)
            }
        }
    }

    private fun assertLandscapeGeometry(session: Session) {
        val top = requiredBounds("player-top-bar")
        val tools = requiredBounds("player-tools-row")
        val controls = requiredBounds("player-controls")
        val seek = requiredBounds("player-seek")
        val play = requiredBounds("player-play")
        val density = mainValue { session.activity.resources.displayMetrics.density }
        val viewport = mainValue { Rect().also { session.activity.window.decorView.getGlobalVisibleRect(it) } }
        assertTrue("Landscape tools belong inside the top bar: top=$top tools=$tools", tools.top >= top.top - 2 && tools.bottom <= top.bottom + 2)
        assertTrue("Landscape tools must be above the seek bar: tools=$tools seek=$seek", tools.bottom < seek.top)
        assertTrue("All tools remain within the visible viewport: $tools vs $viewport", viewport.contains(tools))
        assertTrue("Landscape playback controls occupy at most a third of the screen: $controls vs $viewport", controls.height() < viewport.height() / 3)
        assertTrue("Landscape play button is smaller: $play", play.width() <= 49f * density && play.height() <= 49f * density)
    }

    private fun assertToolsInControls(controls: Rect, tools: Rect) {
        assertTrue("Portrait tools stay in the bottom controls: controls=$controls tools=$tools", tools.top >= controls.top && tools.bottom <= controls.bottom)
    }

    private fun coloredPixelCount(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) - minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 35) count++
        }
        return count
    }

    private fun assertNoBackgroundBars(bitmap: Bitmap) {
        // The fixed-level tone fills only the lower portion. The empty top row must contain
        // only the common page background, rather than full-height grey placeholder tracks.
        val y = (bitmap.height / 10).coerceAtMost(bitmap.height - 1)
        val reference = bitmap.getPixel(bitmap.width - 1, y)
        for (x in 0 until bitmap.width) {
            val actual = bitmap.getPixel(x, y)
            assertTrue("Empty waveform area must not paint grey placeholder bars at x=$x",
                abs(Color.red(actual) - Color.red(reference)) <= 3 && abs(Color.green(actual) - Color.green(reference)) <= 3 &&
                    abs(Color.blue(actual) - Color.blue(reference)) <= 3)
        }
    }

    private fun cropScreenshot(bounds: Rect): Bitmap {
        val screenshot = automation.takeScreenshot() ?: throw AssertionError("No screenshot")
        return try {
            val crop = Rect(bounds).apply { intersect(0, 0, screenshot.width, screenshot.height) }
            Bitmap.createBitmap(screenshot, crop.left, crop.top, crop.width(), crop.height())
        } finally { screenshot.recycle() }
    }

    private inner class Fixture(name: String) : Closeable {
        val root = File(app.cacheDir, "player-$name-layout-${System.nanoTime()}").apply { mkdirs() }
        val preferences = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val keys = listOf("default_play_mode", "default_speed", "resume_playback", "eq_enabled", "show_loudness_waveform", "subtitle_font_size")
        private val saved = keys.associateWith { preferences.all[it] }
        init {
            assertTrue(preferences.edit().putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false)
                .putBoolean("eq_enabled", false).putBoolean("show_loudness_waveform", true)
                .remove("subtitle_font_size").commit())
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
        val intent = Intent(app, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf(track.path))
            .putExtra(PlayerActivity.EXTRA_START_INDEX, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onMain { app.startActivity(intent) }
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
        }
        fun rotate(request: Int, expected: Int) {
            onMain { activity.requestedOrientation = request }
            await("player changes orientation") { mainValue { activity.resources.configuration.orientation == expected } }
            Thread.sleep(400L)
        }
        override fun close() {
            onMain { activity.requestedOrientation = oldOrientation; activity.finish() }
            await("player closes") { mainValue { activity.isDestroyed } }
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldAccessibilityFlags }
        }
    }

    private fun requiredBounds(tag: String): Rect {
        var result: Rect? = null
        await("visible $tag") { result = boundsOrNull(tag); result != null }
        return requireNotNull(result)
    }
    private fun boundsOrNull(tag: String): Rect? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        automation.windows.mapNotNull { it.root }.forEach { pending.addLast(it) }
        automation.rootInActiveWindow?.let { pending.addLast(it) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val id = node.viewIdResourceName.orEmpty()
            if (id == tag || id.endsWith(":id/$tag")) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty) return bounds
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let { pending.addLast(it) } }
        }
        return null
    }
    private fun state(model: PlayerViewModel) = mainValue { model.state.value }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun <T> mainValue(action: () -> T): T {
        val value = AtomicReference<T>()
        onMain { value.set(action()) }
        return value.get()
    }
    private fun awaitState(model: PlayerViewModel, label: String, predicate: (PlayerUiState) -> Boolean) = await(label) {
        val state = state(model)
        state.error?.let { throw AssertionError("$label: $it") }
        predicate(state)
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(75L)
        }
        throw AssertionError("Timed out: $label")
    }
    private fun captureIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("playerScreenshot") != "true") return
        val bitmap = automation.takeScreenshot() ?: throw AssertionError("No screenshot")
        try { saveIfRequested(bitmap, name) } finally { bitmap.recycle() }
    }
    private fun saveIfRequested(bitmap: Bitmap, name: String) {
        if (InstrumentationRegistry.getArguments().getString("playerScreenshot") != "true") return
        File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            else -> error("Unexpected preference value")
        }
    }
    private fun toneWav(): ByteArray {
        val sampleRate = 44_100
        val count = sampleRate * 30
        return ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
            repeat(count) { index -> putShort((sin(2 * Math.PI * 440 * index / sampleRate) * 2_500).toInt().toShort()) }
        }.array()
    }
}
