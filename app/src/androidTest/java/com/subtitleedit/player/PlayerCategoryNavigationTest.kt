package com.subtitleedit.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.subtitleedit.EditorActivity
import com.subtitleedit.MainActivity
import com.subtitleedit.MediaConvertMetadata
import com.subtitleedit.MediaConvertMetadataField
import com.subtitleedit.MediaTagAudioRepository
import com.subtitleedit.MediaTagEditorActivity
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.PlayerTrackMetadataReader
import com.subtitleedit.mpv.MPVLib
import com.subtitleedit.ui.MediaCategory
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real info-link navigation must preserve playback and reveal the same file in each category. */
@RunWith(AndroidJUnit4::class)
class PlayerCategoryNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun eachClassificationLinkOpensItsSongWithoutEditingTagsOrChangingPlayback() = Fixture().use { f ->
        val track = f.wav("category-target.wav")
        f.writeTags(track)
        f.open(track).use { session ->
            val destinations = listOf(
                Destination(MediaCategory.GENRES, "genre", R.string.player_info_genre, "Reveal Genre"),
                Destination(MediaCategory.SINGERS, "singer", R.string.player_info_artist, "Reveal Singer"),
                Destination(MediaCategory.ALBUMS, "album", R.string.player_info_album, "Reveal Album"),
                Destination(MediaCategory.ARTISTS, "artist", R.string.player_info_album_artist, "Reveal Artist"),
                Destination(MediaCategory.COMPOSERS, "composer", R.string.player_info_composer, "Reveal Composer")
            )
            destinations.forEach { destination ->
                openInfo(session.model)
                val before = state(session.model).positionMs
                clickInfoLink(destination)
                awaitState(session.model, "info link docks the player without interrupting its song") {
                    !session.model.expanded.value && !it.showTrackInfoDialog &&
                        it.currentTrack?.path == track.path && it.ready && it.playing && it.positionMs > before + 200L
                }
                await("${destination.prefix} details reveal the requested file") {
                    findTag("${destination.prefix}-detail") != null &&
                        visibleInTrackViewport(destination.prefix, track.path) && findText(destination.title) != null
                }
                if (destination.category == MediaCategory.GENRES &&
                    InstrumentationRegistry.getArguments().getString("playerScreenshot") == "true") {
                    Thread.sleep(350L)
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    try { bitmap?.let { image -> File(app.getExternalFilesDir(null), "player-category-revealed-song.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    } } } finally { bitmap?.recycle() }
                }
                assertEquals(track.path, mainValue { MPVLib.getPropertyString("path") })
                assertFalse("A category link must not open either editor", mainValue {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .any { it is EditorActivity || it is MediaTagEditorActivity }
                })
                assertEquals("Navigation must not add a favorite directory", emptySet<String>(), f.favoritePaths())
                assertTrue("Grouping remains enabled after locating a file", f.groupingStores.all {
                    it.getBoolean("group_tracks", false)
                })
            }
        }
    }

    @Test fun missingGenreStillOpensTheUnknownGenreAndLocatesThePlayingFile() = Fixture().use { f ->
        val track = f.wav("unknown-genre-target.wav")
        f.open(track).use { session ->
            openInfo(session.model)
            awaitState(session.model, "the file has an empty genre tag") { it.trackInfo?.genre == "" }
            clickInfoLink(Destination(MediaCategory.GENRES, "genre", R.string.player_info_genre,
                app.getString(R.string.media_genre_unknown)))
            await("unknown genre contains the selected song") {
                findTag("genre-detail") != null && findText(app.getString(R.string.media_genre_unknown)) != null &&
                    visibleInTrackViewport("genre", track.path)
            }
            awaitState(session.model, "unknown-category navigation preserves audio") {
                it.currentTrack?.path == track.path && it.ready && it.playing && !it.showTrackInfoDialog &&
                    !session.model.expanded.value
            }
            assertEquals(emptySet<String>(), f.favoritePaths())
        }
    }

    private data class Destination(val category: MediaCategory, val prefix: String, val fieldLabel: Int, val title: String)

    private fun openInfo(model: PlayerViewModel) {
        onMain { model.expand(); model.openTrackInfoDialog() }
        awaitState(model, "song information loads") { it.showTrackInfoDialog && it.trackInfo != null && !it.trackInfoLoading }
        await("song info dialog becomes interactive") {
            findTag("player-track-info-dialog") != null || findText(app.getString(R.string.player_info_title)) != null
        }
    }

    private fun clickInfoLink(destination: Destination) {
        val description = app.getString(R.string.player_info_open_category, app.getString(destination.fieldLabel))
        await("${destination.category} info action is reachable") {
            val target = findTag("player-info-open:${destination.category.name}") ?: findNode {
                it.contentDescription?.toString() == description
            }
            if (target != null && target.isVisibleToUser && target.isEnabled) {
                target.refresh()
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@await true
            }
            (findTag("player-track-info-fields") ?: findNode { it.isScrollable && it.isVisibleToUser })
                ?.apply { refresh() }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            false
        }
    }

    private fun visibleInTrackViewport(prefix: String, path: String): Boolean {
        val row = findTag("$prefix-track:$path") ?: return false
        val list = findTag("$prefix-tracks") ?: return false
        val rowBounds = Rect().also(row::getBoundsInScreen)
        val listBounds = Rect().also(list::getBoundsInScreen)
        return row.isVisibleToUser && !rowBounds.isEmpty && rowBounds.top >= listBounds.top &&
            rowBounds.bottom <= listBounds.bottom && Rect.intersects(rowBounds, listBounds)
    }

    private fun findTag(tag: String): AccessibilityNodeInfo? = findNode {
        it.viewIdResourceName == tag || it.viewIdResourceName?.endsWith(":id/$tag") == true
    }
    private fun findText(text: String): AccessibilityNodeInfo? = findNode { it.text?.toString() == text }
    private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val automation = instrumentation.uiAutomation
        val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { it.refresh(); nodes.addLast(it) }
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (!node.refresh()) continue
            if (node.isVisibleToUser && matches(node)) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let { nodes.addLast(it) } }
        }
        return null
    }

    private inner class Fixture : Closeable {
        val root = File(app.cacheDir, "player-category-navigation-${System.nanoTime()}").apply { check(mkdirs()) }
        private val player = app.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        private val keys = listOf("player_mode_enabled", "default_play_mode", "default_speed", "resume_playback",
            "eq_enabled", "show_loudness_waveform", "end_behavior_enabled")
        private val previousPlayer = keys.associateWith { player.all[it] }
        private val favorites = app.getSharedPreferences("favorite_directories", Context.MODE_PRIVATE)
        private val previousFavoritePaths = favorites.all["paths"]
        val groupingStores = listOf("media_album_view", "media_singer_view", "media_artist_view",
            "media_composer_view", "media_genre_view").map { app.getSharedPreferences(it, Context.MODE_PRIVATE) }
        private val previousGrouping = groupingStores.map { it.all["group_tracks"] }
        private val repository = MediaLibraryRepository.getInstance(app)
        private val previousQueue = runBlocking { PlayerLibraryWriter.execute { repository.getCurrentQueue() } }

        init {
            assertTrue(player.edit().putBoolean("player_mode_enabled", true).putString("default_play_mode", PlayerPlayMode.SEQUENCE.name)
                .putFloat("default_speed", 1f).putBoolean("resume_playback", false).putBoolean("eq_enabled", false)
                .putBoolean("show_loudness_waveform", false).putBoolean("end_behavior_enabled", false).commit())
            assertTrue(favorites.edit().putStringSet("paths", emptySet()).commit())
            groupingStores.forEach { assertTrue(it.edit().putBoolean("group_tracks", true).commit()) }
        }

        fun favoritePaths(): Set<String> = favorites.getStringSet("paths", emptySet()).orEmpty()
        fun wav(name: String): File = File(root, name).apply {
            val rate = 16_000
            val count = rate * 60
            writeBytes(ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(count * 2)
            }.array())
        }
        fun writeTags(file: File) {
            MediaTagAudioRepository.write(file, MediaConvertMetadata(title = "Reveal Title", artist = "Reveal Singer",
                album = "Reveal Album", albumArtist = "Reveal Artist", composer = "Reveal Composer", genre = "Reveal Genre",
                editedFields = setOf(MediaConvertMetadataField.TITLE, MediaConvertMetadataField.ARTIST,
                    MediaConvertMetadataField.ALBUM, MediaConvertMetadataField.ALBUM_ARTIST,
                    MediaConvertMetadataField.COMPOSER, MediaConvertMetadataField.GENRE)))
            PlayerTrackMetadataReader.invalidate(file.path)
        }
        fun open(track: File): Session = Session(ActivityScenario.launch(MainActivity::class.java)).also { session ->
            onMain { session.model.openQueue(listOf(PlayerTrack(track.path, track.name, false)), 0) }
            awaitState(session.model, "main audio playback starts") { it.ready && it.playing && it.positionMs > 250L }
        }
        override fun close() {
            val paths = root.walkTopDown().filter { it.isFile }.map { it.path }.toSet()
            runBlocking { PlayerLibraryWriter.execute {} }
            root.deleteRecursively()
            runBlocking { PlayerLibraryWriter.execute {
                repository.updateObservedPaths(paths)
                paths.forEach(repository::removePlaybackHistory)
                repository.saveCurrentQueue(previousQueue.first.map { it.path }, previousQueue.second)
            } }
            val playerEditor = player.edit()
            previousPlayer.forEach { (key, value) -> restore(playerEditor, key, value) }
            paths.forEach { playerEditor.remove("last_position_$it"); PlayerTrackMetadataReader.invalidate(it) }
            assertTrue(playerEditor.commit())
            assertTrue(favorites.edit().also { restore(it, "paths", previousFavoritePaths) }.commit())
            groupingStores.zip(previousGrouping).forEach { (store, value) ->
                assertTrue(store.edit().also { restore(it, "group_tracks", value) }.commit())
            }
        }
    }

    private inner class Session(val scenario: ActivityScenario<MainActivity>) : Closeable {
        lateinit var activity: MainActivity
        lateinit var model: PlayerViewModel
        init { scenario.onActivity { activity = it; model = ViewModelProvider(it)[PlayerViewModel::class.java] } }
        override fun close() {
            try { onMain { model.closeSession() } }
            finally { scenario.close() }
        }
    }
    private fun state(model: PlayerViewModel) = mainValue { model.state.value }
    private fun awaitState(model: PlayerViewModel, label: String, predicate: (PlayerUiState) -> Boolean) = await(label) {
        mainValue {
            val current = model.state.value
            current.error?.let { throw AssertionError("$label: $it") }
            predicate(current)
        }
    }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun <T> mainValue(action: () -> T): T {
        val value = AtomicReference<T>()
        onMain { value.set(action()) }
        return value.get()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100L)
        }
        if (label.contains("info action")) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            try { bitmap?.let { image -> File(app.getExternalFilesDir(null), "player-category-link-failure.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } } } finally { bitmap?.recycle() }
            fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): String {
                if (node == null) return ""
                val bounds = Rect().also(node::getBoundsInScreen)
                return "${" ".repeat(depth)}${node.viewIdResourceName} text=${node.text} desc=${node.contentDescription} " +
                    "visible=${node.isVisibleToUser} scroll=${node.isScrollable} bounds=$bounds\n" +
                    (0 until node.childCount).joinToString("") { describe(node.getChild(it), depth + 1) }
            }
            File(app.getExternalFilesDir(null), "player-category-link-failure.txt")
                .writeText(describe(instrumentation.uiAutomation.rootInActiveWindow))
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
            is Long -> editor.putLong(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("Unexpected fixture preference for $key")
        }
    }
}
