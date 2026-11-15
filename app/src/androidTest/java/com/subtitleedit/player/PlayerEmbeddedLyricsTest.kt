package com.subtitleedit.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.subtitleedit.MediaConvertId3Lyrics
import com.subtitleedit.MediaConvertLyric
import com.subtitleedit.MediaTagNative
import com.subtitleedit.util.SubtitleParser
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real ID3/Vorbis lyric tags through the native player and its subtitle sheet. */
@RunWith(AndroidJUnit4::class)
class PlayerEmbeddedLyricsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun embeddedVersionsAppearBesideSidecarAndTimedTextFollowsSeeking() = withFixture { root ->
        val track = createWav(root, "versions.wav")
        addVersions(track)
        val sidecar = File(root, "versions.lrc").apply {
            writeText("[00:00.00]External sidecar\n[00:29.90]External end\n")
        }
        openPlayer(listOf(track)).use { session ->
            val model = session.model
            awaitState(model, "embedded version is automatically selected") {
                it.ready && it.selectedSubtitleId == embedded(it).firstOrNull()?.id && it.subtitleText.contains("Original first")
            }
            val options = embedded(state(model))
            assertEquals("Keep independently named lyric versions", 2, options.size)
            assertTrue(options.any { it.label.contains("Original") })
            assertTrue(options.any { it.label.contains("Translation") })
            assertTrue(state(model).subtitleOptions.any { it.path == sidecar.path })

            onMain { model.pause(); model.openPanel(PlayerPanel.SUBTITLE) }
            await("subtitle sheet contains each embedded label") {
                val visible = accessibleText(instrumentation.uiAutomation.rootInActiveWindow)
                options.all { it.label in visible }
            }
            captureIfRequested("player-embedded-lyrics-sheet.png")
            onMain { model.closePanel(); model.seekTo(10_250L) }
            awaitState(model, "embedded timestamps drive the displayed line") { it.subtitleText.contains("Original later") }
            val expectedEnd = SubtitleParser.parseLRC("[00:00.00]Original first\n[00:10.00]Original later\n").last().endTime
            onMain { model.seekTo(expectedEnd + 250L) }
            awaitState(model, "the final LRC line ends according to the shared parser") { it.subtitleText.isEmpty() }
            onMain { model.seekTo(10_250L) }

            val translated = options.single { it.label.contains("Translation") }
            onMain { model.selectSubtitle(translated) }
            awaitState(model, "switching embedded version updates the actual subtitle text") {
                it.selectedSubtitleId == translated.id && it.subtitleText.contains("Translation later")
            }
            val external = state(model).subtitleOptions.single { it.path == sidecar.path }
            onMain { model.selectSubtitle(external) }
            awaitState(model, "external subtitles remain selectable alongside embedded lyrics") {
                it.selectedSubtitleId == external.id && it.subtitleText.contains("External sidecar")
            }
        }
    }

    @Test fun recreationRetainsChosenVersionAndOffWhileChangingSongsClearsOldLyrics() = withFixture { root ->
        val tagged = createWav(root, "tagged.wav").also(::addVersions)
        val blank = createWav(root, "blank.wav")
        openPlayer(listOf(tagged, blank)).use { session ->
            val model = session.model
            awaitState(model, "tagged song is ready") { it.ready && embedded(it).size == 2 }
            val translated = embedded(state(model)).single { it.label.contains("Translation") }
            onMain { model.pause(); model.seekTo(10_250L); model.selectSubtitle(translated) }
            awaitState(model, "translated embedded text") { it.subtitleText.contains("Translation later") }
            session.recreate()
            awaitState(model, "recreated player retains the selected embedded document") {
                it.ready && !it.playing && it.subtitleText.contains("Translation later") &&
                    it.subtitleOptions.firstOrNull { option -> option.id == it.selectedSubtitleId }?.label?.contains("Translation") == true
            }
            onMain { model.selectSubtitle(model.state.value.subtitleOptions.first { it.kind == PlayerSubtitleKind.NONE }) }
            awaitState(model, "embedded lyrics can be turned off") { it.selectedSubtitleId == "none" && it.subtitleText.isEmpty() }
            session.recreate()
            awaitState(model, "explicit subtitle-off survives recreation") {
                it.ready && it.selectedSubtitleId == "none" && it.subtitleText.isEmpty() && embedded(it).size == 2
            }
            onMain { model.playIndex(1) }
            awaitState(model, "switching to a clean song discards old generated tracks") {
                it.currentIndex == 1 && it.ready && embedded(it).isEmpty() && it.selectedSubtitleId == "none" && it.subtitleText.isEmpty()
            }
            assertTrue(state(model).subtitleOptions.all { it.kind == PlayerSubtitleKind.NONE })
            onMain { model.playIndex(0) }
            awaitState(model, "returning to the tagged song rediscovers its own lyric documents") {
                it.currentIndex == 0 && it.ready && embedded(it).size == 2 && it.subtitleText.contains("Original first")
            }
        }
    }

    @Test fun plainEmbeddedLyricsRemainReadableWithoutInventedLineTiming() = withFixture { root ->
        val track = createWav(root, "plain.wav")
        val text = "Plain first line\nPlain second line"
        MediaConvertId3Lyrics.writeWav(track, listOf(MediaConvertLyric("Plain", text)))
        openPlayer(listOf(track)).use { session ->
            val model = session.model
            awaitState(model, "plain lyric document is detected and shown") {
                it.ready && embedded(it).size == 1 && it.subtitleText.contains("Plain first line") && it.subtitleText.contains("Plain second line")
            }
            onMain { model.pause(); model.seekTo(25_000L) }
            awaitState(model, "untimed lyrics remain one document near the end of the song") {
                it.subtitleText.contains("Plain first line") && it.subtitleText.contains("Plain second line")
            }
        }
    }

    @Test fun mp3SynchronizedFramesAndFlacLyricTagsAreRecognized() = withFixture { root ->
        val source = createWav(root, "source.wav")
        val mp3 = transcode(source, File(root, "synchronized.mp3"), "libmp3lame")
        MediaConvertId3Lyrics.writeMp3(mp3, listOf(MediaConvertLyric("MP3", "[00:00.00]MP3 first\n[00:10.00]MP3 later\n")))
        val flac = transcode(source, File(root, "vorbis.flac"), "flac")
        ParcelFileDescriptor.open(flac, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            assertTrue(MediaTagNative.write(descriptor.fd, arrayOf("LYRICS"),
                arrayOf("[00:00.00]FLAC first\n[00:10.00]FLAC later\n"), 0, null, ""))
        }
        openPlayer(listOf(mp3, flac)).use { session ->
            val model = session.model
            awaitState(model, "MP3 lyric tags produce selected text") {
                it.ready && embedded(it).isNotEmpty() && it.subtitleText.contains("MP3 first")
            }
            assertEquals("USLT/SYLT are one subtitle choice", 1, embedded(state(model)).size)
            onMain { model.pause(); model.seekTo(10_250L) }
            awaitState(model, "MP3 synchronized timing") { it.subtitleText.contains("MP3 later") }
            onMain { model.seekTo(25_000L) }
            awaitState(model, "MP3 follows shared parser end time") { it.subtitleText.isEmpty() }
            onMain { model.playIndex(1) }
            awaitState(model, "Vorbis comment lyric tag produces selected text") {
                it.currentIndex == 1 && it.ready && embedded(it).isNotEmpty() && it.subtitleText.contains("FLAC first")
            }
            onMain { model.pause(); model.seekTo(10_250L) }
            awaitState(model, "FLAC tag timing") { it.subtitleText.contains("FLAC later") }
        }
    }

    private fun embedded(state: PlayerUiState) = state.subtitleOptions.filter { it.label.startsWith("内嵌歌词") }

    private fun accessibleText(node: AccessibilityNodeInfo?): Set<String> {
        if (node == null) return emptySet()
        return buildSet {
            node.text?.toString()?.let(::add)
            repeat(node.childCount) { addAll(accessibleText(node.getChild(it))) }
        }
    }

    private fun addVersions(file: File) = MediaConvertId3Lyrics.writeWav(file, listOf(
        MediaConvertLyric("Original", "[00:00.00]Original first\n[00:10.00]Original later\n"),
        MediaConvertLyric("Translation", "[00:00.00]Translation first\n[00:10.00]Translation later\n")
    ))

    private fun transcode(source: File, target: File, codec: String): File {
        val result = FFmpegKit.executeWithArguments(arrayOf("-y", "-i", source.path, "-c:a", codec, target.path))
        assertTrue(result.getAllLogsAsString(), ReturnCode.isSuccess(result.getReturnCode()))
        return target
    }

    private fun withFixture(action: (File) -> Unit) {
        val root = File(app.cacheDir, "player-embedded-lyrics-${System.nanoTime()}").apply { mkdirs() }
        val settings = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        val keys = listOf("default_play_mode", "default_speed", "resume_playback", "eq_enabled", "show_loudness_waveform")
        val saved = keys.associateWith { settings.all[it] }
        try {
            assertTrue(settings.edit().putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false).putBoolean("eq_enabled", false)
                .putBoolean("show_loudness_waveform", false).commit())
            action(root)
        } finally {
            val editor = settings.edit()
            saved.forEach { (key, value) -> restore(editor, key, value) }
            root.listFiles().orEmpty().forEach { editor.remove("last_position_${it.path}") }
            assertTrue(editor.commit())
            root.deleteRecursively()
        }
    }

    private fun openPlayer(tracks: List<File>): Session {
        val intent = Intent(app, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, ArrayList(tracks.map { it.path }))
            .putExtra(PlayerActivity.EXTRA_START_INDEX, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onMain { app.startActivity(intent) }
        return Session(awaitActivity())
    }

    private inner class Session(var activity: PlayerActivity) : Closeable {
        val model = mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] }
        fun recreate() {
            val previous = activity
            onMain { previous.recreate() }
            activity = awaitActivity(previous)
            assertSame(model, mainValue { ViewModelProvider(activity)[PlayerViewModel::class.java] })
        }
        override fun close() {
            onMain { activity.finish() }
            await("player releases resources") { mainValue { activity.isDestroyed } }
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
        } catch (failure: AssertionError) {
            throw AssertionError("$label: ${state(model)}", failure)
        }
    }
    private fun await(label: String, matches: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (matches()) return
            Thread.sleep(75L)
        }
        captureIfRequested("player-embedded-lyrics-failure.png")
        throw AssertionError("$label timed out")
    }
    private fun captureIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("playerScreenshot") != "true") return
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            File(app.getExternalFilesDir(null), name).outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { screenshot.recycle() }
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
    private fun createWav(root: File, name: String): File = File(root, name).apply {
        val sampleRate = 8_000
        val samples = sampleRate * 30
        writeBytes(ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array())
    }
}
