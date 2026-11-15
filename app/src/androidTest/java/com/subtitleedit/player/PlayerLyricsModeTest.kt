package com.subtitleedit.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
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
import com.subtitleedit.MediaConvertCoverMode
import com.subtitleedit.MediaConvertId3Lyrics
import com.subtitleedit.MediaConvertLyric
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaTagAudioRepository
import com.subtitleedit.R
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.filepicker.FilePickerActivity
import com.subtitleedit.filepicker.FilePickerPolicy
import com.subtitleedit.filepicker.FilePickerViewModel
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real activity, subtitle parser and mpv regressions for the cover/lyrics and file picker flow. */
@RunWith(AndroidJUnit4::class)
class PlayerLyricsModeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun selectedSubtitleUsesTheSameEntriesAsTheProjectParser() = withFixture { root ->
        val track = createWav(root, "content.wav")
        val text = "1\n00:00:00,000 --> 00:00:04,000\n  <i>First</i> &amp; value\n\nInside blank lines\n\n2\n00:00:05,000 --> 00:00:07,000\n\n\n3\n00:00:08,000 --> 00:00:29,000\nFinal\n"
        val manual = File(root, "original-content.srt").apply { writeText(text) }
        val expected = SubtitleParser.parseSRT(text).map { PlayerLyricLine(it.startTime, it.endTime, it.text) }
        assertTrue("Fixture includes an empty cue", expected.any { it.text.isEmpty() })
        openPlayer(listOf(track)).use { session ->
            awaitState(session.model, "media is ready") { it.ready }
            onMain { session.model.pause(); session.model.importSubtitle(manual, track.path) }
            awaitState(session.model, "original parsed entries reach the lyric view unchanged") {
                it.selectedSubtitleId == "external:${manual.path}" && !it.lyricsLoading && it.lyricLines == expected
            }
        }
    }

    @Test fun tappingCoverShowsTimedContextAndTappingALineSeeksPlayback() = withFixture { root ->
        val track = createWav(root, "covered.wav")
        attachCover(track, root)
        MediaConvertId3Lyrics.writeWav(track, listOf(MediaConvertLyric("Original", TIMED_LYRICS)))
        openPlayer(listOf(track)).use { session ->
            val model = session.model
            awaitState(model, "embedded timeline loads") { it.ready && it.lyricLines.size == 3 }
            onMain { model.pause() }
            clickTag("player-audio-cover")
            awaitState(model, "cover opens the lyric page") { it.lyricsVisible }
            await("lyric list is visible") { findTag("player-lyrics-list") != null }
            assertSubtitleSourceAboveProgress(landscape = false)
            assertEquals(listOf("First lyric", "Middle lyric", "Final lyric"), state(model).lyricLines.map { it.text })

            clickTag("player-lyrics-line:1")
            awaitState(model, "clicked line seeks mpv") {
                !it.playing && it.positionMs in 9_800L..10_200L && it.subtitleText.contains("Middle lyric")
            }
            await("current lyric is centered in the context list") {
                val active = findTag("player-lyrics-line:1") ?: return@await false
                val list = findTag("player-lyrics-list") ?: return@await false
                val rowBounds = Rect().also(active::getBoundsInScreen)
                val listBounds = Rect().also(list::getBoundsInScreen)
                kotlin.math.abs(rowBounds.centerY() - listBounds.centerY()) <= rowBounds.height()
            }
            captureIfRequested("player-lyrics-context.png")
            capturePressedInfoIfRequested()
            onMain { session.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("landscape lyric viewport stays clear of playback controls") {
                val list = findTag("player-lyrics-list") ?: return@await false
                val controls = findTag("player-bottom-overlay") ?: return@await false
                val screen = findTag("player-screen") ?: return@await false
                val listBounds = Rect().also(list::getBoundsInScreen)
                val controlBounds = Rect().also(controls::getBoundsInScreen)
                val screenBounds = Rect().also(screen::getBoundsInScreen)
                session.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                    screenBounds.width() > screenBounds.height() &&
                    listBounds.width() >= screenBounds.width() * 0.9f &&
                    listBounds.height() > 0 && listBounds.bottom <= controlBounds.top
            }
            instrumentation.uiAutomation.waitForIdle(500L, 5_000L)
            assertSubtitleSourceAboveProgress(landscape = true)
            captureIfRequested("player-lyrics-landscape.png")
            clickTag("player-lyrics-close")
            awaitState(model, "close returns to the cover") { !it.lyricsVisible }
            await("cover is visible again") { findTag("player-audio-cover") != null }
        }
    }

    @Test fun importedDifferentNameSubtitleSurvivesRecreationAndDoesNotLeakToAnotherSong() = withFixture { root ->
        val first = createWav(root, "first.wav")
        val second = createWav(root, "second.wav")
        val manual = File(File(root, "elsewhere").apply { mkdirs() }, "selected-version.lrc").apply {
            writeText("[00:00.00]Imported first\n[00:10.00]Imported later\n")
        }
        openPlayer(listOf(first, second)).use { session ->
            val model = session.model
            awaitState(model, "initial song is ready") { it.ready }
            onMain { model.pause(); model.importSubtitle(manual, first.path) }
            awaitState(model, "manual file is selected and parsed") {
                it.selectedSubtitleId == "external:${manual.path}" && it.lyricLines.size == 2 &&
                    it.subtitleText.contains("Imported first")
            }
            onMain { model.setLyricsVisible(true); model.seekTo(12_000L) }
            awaitState(model, "imported timed line updates") { it.subtitleText.contains("Imported later") }
            session.recreate()
            awaitState(model, "recreation preserves chosen subtitle and lyrics mode") {
                it.ready && !it.playing && it.lyricsVisible && it.selectedSubtitleId == "external:${manual.path}" &&
                    it.lyricLines.size == 2 && it.subtitleText.contains("Imported later")
            }
            onMain { model.playIndex(1) }
            awaitState(model, "another song has no stale timeline") {
                it.currentIndex == 1 && it.ready && it.lyricLines.isEmpty() && it.selectedSubtitleId == "none"
            }
            onMain { model.importSubtitle(manual, first.path) }
            Thread.sleep(200L)
            assertEquals("Late selection for the old song is ignored", "none", state(model).selectedSubtitleId)
            assertTrue(state(model).lyricLines.isEmpty())
            onMain { model.playIndex(0) }
            awaitState(model, "returning to the song retains its manually chosen file") {
                it.currentIndex == 0 && it.ready && it.selectedSubtitleId == "external:${manual.path}" && it.lyricLines.size == 2
            }
            onMain { model.playIndex(1) }
            awaitState(model, "second song is ready before removing the old subtitle") { it.currentIndex == 1 && it.ready }
            assertTrue(manual.delete())
            onMain { model.playIndex(0) }
            awaitState(model, "missing imported subtitle never prevents audio playback") {
                it.currentIndex == 0 && it.ready && it.error == null && it.selectedSubtitleId == "none" && it.lyricLines.isEmpty()
            }
        }
    }

    @Test fun subtitleMenuOpensSongDirectoryAndSelectedFileIsReadWhileCancelPreservesIt() = withFixture { root ->
        val track = createWav(root, "song.wav")
        File(root, "song.srt").writeText("1\n00:00:00,000 --> 00:00:29,900\nOriginal sidecar\n")
        val nested = File(root, "Manual subtitles").apply { mkdirs() }
        val manual = File(nested, "different-name.vtt").apply {
            writeText("WEBVTT\n\n00:00:00.000 --> 00:00:29.900\nChosen from directory\n")
        }
        openPlayer(listOf(track)).use { session ->
            val model = session.model
            awaitState(model, "automatic sidecar is active") { it.ready && it.subtitleText.contains("Original sidecar") }
            onMain { model.pause(); model.openPanel(PlayerPanel.SUBTITLE) }
            clickTag("player-subtitle-pick-file")
            val picker = awaitActivity(FilePickerActivity::class.java)
            val pickerModel = mainValue { ViewModelProvider(picker)[FilePickerViewModel::class.java] }
            await("picker starts at the current song directory") {
                mainValue { pickerModel.state.value.directory.canonicalPath == root.canonicalPath }
            }
            clickText(nested.name)
            clickText(manual.name)
            awaitActivity(PlayerActivity::class.java)
            awaitState(model, "chosen directory file reaches mpv and the lyric timeline") {
                it.selectedSubtitleId == "external:${manual.path}" && it.lyricLines.singleOrNull()?.text == "Chosen from directory" &&
                    it.subtitleText.contains("Chosen from directory")
            }
            val selected = state(model).selectedSubtitleId
            onMain { model.openPanel(PlayerPanel.SUBTITLE) }
            clickTag("player-subtitle-pick-file")
            awaitActivity(FilePickerActivity::class.java)
            clickText(app.getString(R.string.cancel))
            awaitActivity(PlayerActivity::class.java)
            awaitState(model, "cancel preserves the existing subtitle and pause state") {
                it.ready && !it.playing && it.selectedSubtitleId == selected && it.lyricLines.singleOrNull()?.text == "Chosen from directory"
            }
        }
    }

    @Test fun invalidImportPreservesTheWorkingSubtitleAndBackClosesPanelBeforeLyrics() = withFixture { root ->
        val track = createWav(root, "song.wav")
        val working = File(root, "song.lrc").apply { writeText(TIMED_LYRICS) }
        val invalid = File(root, "broken.srt").apply { writeText("This is not timed subtitle data") }
        openPlayer(listOf(track)).use { session ->
            val model = session.model
            awaitState(model, "working subtitle loads") { it.ready && it.selectedSubtitleId == "external:${working.path}" && it.lyricLines.size == 3 }
            onMain { model.pause(); model.importSubtitle(invalid, track.path) }
            awaitState(model, "invalid file reports failure without replacing subtitles") { it.gestureHint != null }
            assertEquals("external:${working.path}", state(model).selectedSubtitleId)
            assertEquals(3, state(model).lyricLines.size)
            onMain { model.clearHint(); model.setLyricsVisible(true); model.openPanel(PlayerPanel.SUBTITLE) }
            await("subtitle panel is present") { findTag("player-subtitle-pick-file") != null }
            pressBack()
            awaitState(model, "back first dismisses the subtitle sheet") { it.panel == PlayerPanel.NONE && it.lyricsVisible }
            pressBack()
            awaitState(model, "second back returns from lyrics to artwork") { !it.lyricsVisible && it.ready }
            assertFalse(mainValue { session.activity.isFinishing })
        }
    }

    private fun withFixture(action: (File) -> Unit) {
        val root = File(app.cacheDir, "player-lyrics-mode-${System.nanoTime()}").apply { mkdirs() }
        val settings = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        val keys = listOf("default_play_mode", "default_speed", "resume_playback", "eq_enabled", "show_loudness_waveform")
        val saved = keys.associateWith { settings.all[it] }
        val pickerSettings = app.getSharedPreferences(FilePickerPolicy.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val previousDirectory = pickerSettings.all[FilePickerPolicy.LAST_DIRECTORY]
        try {
            assertTrue(settings.edit().putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false).putBoolean("eq_enabled", false)
                .putBoolean("show_loudness_waveform", false).commit())
            action(root)
        } finally {
            val editor = settings.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            root.walkTopDown().filter { it.isFile }.forEach { editor.remove("last_position_${it.path}") }
            assertTrue(editor.commit())
            val pickerEditor = pickerSettings.edit()
            restore(pickerEditor, FilePickerPolicy.LAST_DIRECTORY, previousDirectory)
            assertTrue(pickerEditor.commit())
            root.deleteRecursively()
        }
    }

    private fun openPlayer(tracks: List<File>): Session {
        val intent = Intent(app, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, ArrayList(tracks.map { it.path }))
            .putExtra(PlayerActivity.EXTRA_START_INDEX, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onMain { app.startActivity(intent) }
        return Session(awaitActivity(PlayerActivity::class.java)).also { session ->
            onMain { session.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }

    private inner class Session(var activity: PlayerActivity) : Closeable {
        val model = mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] }
        fun recreate() {
            val previous = activity
            onMain { previous.recreate() }
            activity = awaitActivity(PlayerActivity::class.java, previous)
            assertSame(model, mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] })
        }
        override fun close() {
            onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<FilePickerActivity>().forEach { it.finish() }
                activity.finish()
            }
            await("player releases resources") { mainValue { activity.isDestroyed } }
        }
    }

    private fun <T : Activity> awaitActivity(type: Class<T>, previous: T? = null): T {
        var activity: T? = null
        await("${type.simpleName} resumes") {
            activity = mainValue { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { type.isInstance(it) && it !== previous }?.let(type::cast) }
            activity != null
        }
        return requireNotNull(activity)
    }

    private fun findTag(tag: String) = findNode(instrumentation.uiAutomation.rootInActiveWindow) {
        it.viewIdResourceName == tag || it.viewIdResourceName?.substringAfter(":id/") == tag ||
            (tag == "player-lyrics-source-label" && it.text?.toString() == "内嵌歌词 · Original") ||
            (tag == "player-lyrics-close" && it.contentDescription?.toString() == app.getString(R.string.player_lyrics_close))
    }

    private fun assertSubtitleSourceAboveProgress(landscape: Boolean) {
        try {
            await("subtitle source stays on the right above the progress bar") {
                fun bounds(tag: String): Rect? = findTag(tag)?.let { Rect().also(it::getBoundsInScreen) }
                val label = findTag("player-lyrics-source-label") ?: return@await false
                val labelBounds = Rect().also(label::getBoundsInScreen)
                val close = bounds("player-lyrics-close") ?: return@await false
                val source = bounds("player-lyrics-source") ?: Rect(labelBounds).apply { union(close) }
                val seek = bounds("player-seek") ?: return@await false
                val topBar = bounds("player-top-bar") ?: return@await false
                if (label.text?.toString() != "内嵌歌词 · Original" || source.top < topBar.bottom ||
                    labelBounds.centerX() < seek.centerX() || source.centerY() >= seek.centerY()) return@await false
                if (landscape) {
                    source.bottom < seek.centerY()
                } else {
                    val add = bounds("player-quick-add-playlist") ?: return@await false
                    val favorite = bounds("player-quick-favorite") ?: return@await false
                    val info = bounds("player-quick-info") ?: return@await false
                    val aligned = listOf(favorite, info, close, labelBounds).all {
                        kotlin.math.abs(it.exactCenterY() - add.exactCenterY()) <= 2f
                    }
                    aligned && source.left >= add.centerX() + add.width() / 4 &&
                        source.top < add.bottom && source.bottom > add.top
                }
            }
        } catch (failure: AssertionError) {
            val positions = listOf("player-quick-favorite", "player-quick-info", "player-quick-add-playlist",
                "player-lyrics-close", "player-lyrics-source-label", "player-lyrics-source", "player-seek").associateWith { tag ->
                findTag(tag)?.let { Rect().also(it::getBoundsInScreen) }
            }
            throw AssertionError("Alignment bounds: $positions", failure)
        }
    }
    private fun findNode(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (matches(node)) return node
        repeat(node.childCount) { index -> findNode(node.getChild(index), matches)?.let { return it } }
        return null
    }
    private fun clickTag(tag: String) = clickNode("tag $tag") { findTag(tag) }
    private fun clickText(text: String) = clickNode(text) {
        findNode(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text }
    }
    private fun clickNode(label: String, locate: () -> AccessibilityNodeInfo?) {
        await("clickable $label appears") {
            var node = locate()
            while (node != null) {
                if (node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@await true
                node = node.parent
            }
            false
        }
    }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun <T> mainValue(action: () -> T): T {
        val result = AtomicReference<T>()
        onMain { result.set(action()) }
        return result.get()
    }
    private fun state(model: PlayerViewModel) = mainValue { model.state.value }
    private fun awaitState(model: PlayerViewModel, label: String, matches: (PlayerUiState) -> Boolean) {
        try {
            await(label) {
                val current = state(model)
                if (current.error != null) throw AssertionError("$label: ${current.error}")
                matches(current)
            }
        } catch (failure: AssertionError) { throw AssertionError("$label: ${state(model)}", failure) }
    }
    private fun await(label: String, matches: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (matches()) return
            Thread.sleep(75L)
        }
        captureIfRequested("player-lyrics-mode-failure.png")
        throw AssertionError("$label timed out")
    }
    private fun captureIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("playerScreenshot") != "true") return
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        try { File(app.getExternalFilesDir(null), name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
    }
    private fun capturePressedInfoIfRequested() {
        if (InstrumentationRegistry.getArguments().getString("playerScreenshot") != "true") return
        val info = findTag("player-quick-info") ?: return
        val bounds = Rect().also(info::getBoundsInScreen)
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN)
        try {
            Thread.sleep(150L)
            captureIfRequested("player-info-feedback-alignment.png")
        } finally { touch(MotionEvent.ACTION_CANCEL) }
    }
    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            else -> error("Unsupported preference value for $key")
        }
    }
    private fun attachCover(track: File, root: File) {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val cover = File(root, "fixture-cover.png")
        try { cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        MediaTagAudioRepository.write(track, MediaConvertMetadata(cover = MediaConvertCoverMode.CUSTOM, coverPath = cover.path))
    }
    private fun createWav(root: File, name: String): File = File(root, name).apply {
        val sampleRate = 8_000
        val samples = sampleRate * 30
        writeBytes(ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array())
    }

    private companion object {
        const val TIMED_LYRICS = "[00:00.00]First lyric\n[00:10.00]Middle lyric\n[00:20.00]Final lyric\n"
    }
}
