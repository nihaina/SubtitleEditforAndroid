package com.subtitleedit

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.feature.ui.MainActivityScreenState
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.util.DirectoryWatcher
import com.subtitleedit.util.FavoriteDirectoryStore
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the Activity providers and navigation, rather than an isolated selection component. */
@RunWith(AndroidJUnit4::class)
class MainMediaSelectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun directoryTagMenuRejectsUnsupportedArtworkTargetsButKeepsTextTagEditing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        val previousMode = preferences.all["player_mode_enabled"] as? Boolean
        assertTrue(preferences.edit().putBoolean("player_mode_enabled", false).commit())
        val root = File(context.cacheDir, "tag-menu-selection-${System.nanoTime()}").apply { check(mkdirs()) }
        val wav = File(root, "source.WAV").apply { writeBytes(byteArrayOf(0)) }
        val mp3 = File(root, "source.mp3").apply { writeBytes(byteArrayOf(0)) }
        val cover = File(root, "cover.jpg").apply { writeBytes(byteArrayOf(0)) }
        val avi = File(root, "source.avi").apply { writeBytes(byteArrayOf(0)) }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var activity: MainActivity
            lateinit var model: MainViewModel
            scenario.onActivity {
                activity = it
                model = ViewModelProvider(it)[MainViewModel::class.java]
                model.documentState.viewMode = FileBrowserViewMode.DETAILED
                field<DirectoryWatcher>(it, "directoryWatcher").setEnabled(false)
                invoke(it, "showTopLevelPage", arrayOf(Int::class.javaPrimitiveType!!), R.id.nav_directory)
                assertTrue(invoke(it, "loadDirectory",
                    arrayOf(File::class.java, Boolean::class.javaPrimitiveType!!), root, true) as Boolean)
            }
            compose.waitUntil(5_000L) {
                onMain { screen(activity).currentDirectory == root && !screen(activity).isDirectoryLoading }
            }
            val selections = listOf(
                listOf(wav, cover) to false,
                listOf(cover, wav, mp3) to false,
                listOf(avi, cover) to false,
                listOf(mp3, cover) to true,
                listOf(wav) to true,
                listOf(wav, mp3) to true
            )
            selections.forEach { (files, expected) ->
                onMain {
                    model.documentState.selectedPaths.clear()
                    model.documentState.selectedPaths.addAll(files.map { it.path })
                    invoke(activity, "publishScreenState", emptyArray())
                    assertEquals(files.map { it.name }.toString(), expected, screen(activity).canEditTagsSelected)
                }
                compose.onNodeWithText("更多").performClick()
                val tags = compose.onNodeWithText(context.getString(R.string.player_info_edit_tags))
                if (expected) tags.assertIsDisplayed() else tags.assertDoesNotExist()
                pressBack()
            }
        } finally {
            scenario?.close()
            val restore = preferences.edit()
            if (previousMode == null) restore.remove("player_mode_enabled")
            else restore.putBoolean("player_mode_enabled", previousMode)
            assertTrue(restore.commit())
            root.deleteRecursively()
        }
    }

    @Test fun audioModeDirectoryKeepsOriginalFileSelectionAndCanCopyToAnotherFolder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        val previousMode = preferences.all["player_mode_enabled"] as? Boolean
        assertTrue(preferences.edit().putBoolean("player_mode_enabled", true).commit())
        val root = File(context.cacheDir, "directory-copy-selection-${System.nanoTime()}").apply { check(mkdirs()) }
        val audio = File(root, "a-audio.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val image = File(root, "b-cover.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val subtitle = File(root, "c-subtitle.srt").apply { writeText("Original subtitle") }
        val destination = File(root, "Destination").apply { check(mkdir()) }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var activity: MainActivity
            lateinit var model: MainViewModel
            scenario.onActivity {
                activity = it
                model = ViewModelProvider(it)[MainViewModel::class.java]
                model.documentState.viewMode = FileBrowserViewMode.DETAILED
                field<DirectoryWatcher>(it, "directoryWatcher").setEnabled(false)
                invoke(it, "showTopLevelPage", arrayOf(Int::class.javaPrimitiveType!!), R.id.nav_directory)
                assertTrue(invoke(it, "loadDirectory",
                    arrayOf(File::class.java, Boolean::class.javaPrimitiveType!!), root, true) as Boolean)
            }
            compose.waitUntil(5_000L) {
                onMain { screen(activity).currentDirectory == root && !screen(activity).isDirectoryLoading }
            }
            compose.onNodeWithText(audio.name).performTouchInput { longClick() }
            compose.onNodeWithText("复制").assertIsDisplayed()
            compose.onNodeWithText("移动").assertIsDisplayed()
            compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
            compose.onNodeWithText(subtitle.name).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.directory_select_range)).performClick()
            onMain {
                assertFalse(model.mediaSelection.active)
                assertTrue(setOf(audio.path, image.path, subtitle.path).all { it in model.selectedPaths })
            }
            compose.onNodeWithContentDescription(context.getString(R.string.source_selection_select_all)).performClick()
            onMain { assertEquals(screen(activity).files.filter { it.name != ".." }.map { it.path }.toSet(), model.selectedPaths.toSet()) }
            compose.onNodeWithContentDescription(context.getString(R.string.source_selection_select_all)).performClick()
            onMain { assertTrue(model.selectedPaths.isEmpty()); assertFalse(model.mediaSelection.active) }

            // Folders and nonmedia still start the original selection too.
            listOf(destination, subtitle, image).forEach { file ->
                compose.onNodeWithText(file.name).performTouchInput { longClick() }
                onMain { assertEquals(setOf(file.path), model.selectedPaths.toSet()); assertFalse(model.mediaSelection.active) }
                compose.onNodeWithText("复制").assertIsDisplayed()
                pressBack()
            }
            compose.onNodeWithText(audio.name).performTouchInput { longClick() }
            compose.onNodeWithText(image.name).performClick()
            compose.onNodeWithText("复制").performClick()
            onMain { assertEquals(FileOperation.COPY, model.pendingFileOperation) }
            compose.onNodeWithText(destination.name).performClick()
            compose.waitUntil(5_000L) {
                onMain { screen(activity).currentDirectory == destination && !screen(activity).isDirectoryLoading }
            }
            compose.onNodeWithText("复制到此处").performClick()
            compose.waitUntil(5_000L) {
                File(destination, audio.name).isFile && File(destination, image.name).isFile &&
                    onMain { model.pendingFileOperation == null && model.selectedPaths.isEmpty() }
            }
            assertTrue(audio.readBytes().contentEquals(File(destination, audio.name).readBytes()))
            assertTrue(image.readBytes().contentEquals(File(destination, image.name).readBytes()))
            onMain { assertFalse(model.mediaSelection.active); assertTrue(screen(activity).playerModeEnabled) }
        } finally {
            scenario?.close()
            val restore = preferences.edit()
            if (previousMode == null) restore.remove("player_mode_enabled")
            else restore.putBoolean("player_mode_enabled", previousMode)
            assertTrue(restore.commit())
            root.deleteRecursively()
        }
    }

    @Test fun selectionStartedInLibraryIsSharedWithDirectoryUntilItIsCancelled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        val previousMode = preferences.all["player_mode_enabled"] as? Boolean
        assertTrue(preferences.edit().putBoolean("player_mode_enabled", true).commit())
        val root = File(context.cacheDir, "main-media-selection-${System.nanoTime()}").apply { check(mkdirs()) }
        val audio = File(root, "selected-audio.m4a").apply {
            instrumentation.context.assets.open("media-tags/preservation.m4a")
                .use { input -> outputStream().use { input.copyTo(it) } }
        }
        val video = File(root, "selected-video.mp4").apply { writeBytes(byteArrayOf(0)) }
        val subtitle = File(root, "excluded.srt").apply { writeText("subtitle") }
        val folder = File(root, "excluded-folder.mp3").apply { check(mkdir()) }
        val directoryStore = FavoriteDirectoryStore(context)
        val previousDirectories = directoryStore.load()
        directoryStore.replace(setOf(root.path), setOf(root.path))
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var activity: MainActivity
            lateinit var model: MainViewModel
            scenario.onActivity {
                activity = it
                model = ViewModelProvider(it)[MainViewModel::class.java]
                model.documentState.viewMode = FileBrowserViewMode.DETAILED
                field<DirectoryWatcher>(it, "directoryWatcher").setEnabled(false)
                invoke(it, "showTopLevelPage", arrayOf(Int::class.javaPrimitiveType!!), R.id.nav_directory)
                assertTrue(invoke(it, "loadDirectory",
                    arrayOf(File::class.java, Boolean::class.javaPrimitiveType!!), root, true) as Boolean)
            }
            compose.waitUntil(5_000L) {
                onMain { screen(activity).currentDirectory == root && !screen(activity).isDirectoryLoading }
            }
            compose.onNode(hasText(context.getString(R.string.nav_library)) and hasClickAction()).performClick()
            compose.onNodeWithText(root.name).performClick()
            compose.onNodeWithTag("library-file:${audio.path}").performTouchInput { longClick() }
            compose.onNodeWithTag("library-selection-panel").assertIsDisplayed()
            compose.onNode(hasText(context.getString(R.string.nav_directory)) and hasClickAction()).performClick()
            compose.onNodeWithText("复制").assertDoesNotExist()
            compose.onNodeWithText(video.name).performClick()
            compose.onNodeWithText(subtitle.name).performClick()
            compose.onNodeWithText(folder.name).performTouchInput { longClick() }
            onMain {
                assertEquals(setOf(audio.path, video.path), model.mediaSelection.selectedPaths.toSet())
                assertTrue("Media selection must leave legacy editor selection untouched", model.selectedPaths.isEmpty())
            }

            listOf(R.id.nav_library to R.string.nav_library,
                R.id.nav_tools to R.string.media_categories_navigation,
                R.id.nav_directory to R.string.nav_directory).forEach { (id, label) ->
                compose.onNode(hasText(context.getString(label)) and hasClickAction()).performClick()
                compose.onNodeWithTag("main-page:$id").assertIsDisplayed()
                compose.onNodeWithTag("library-selection-panel").assertIsDisplayed()
                onMain {
                    assertEquals(id, screen(activity).selectedTopLevelItem)
                    assertEquals(setOf(audio.path, video.path), model.mediaSelection.selectedPaths.toSet())
                }
            }
            compose.onNodeWithText(audio.name).assertIsDisplayed()
            onMain { assertEquals(root, screen(activity).currentDirectory) }
            compose.onNodeWithTag("library-selection-options").performClick()
            compose.onNodeWithTag("media-selection-options-dialog").assertIsDisplayed()
            listOf("FILE_INFO", "LOCATE_DIRECTORY").forEach { action ->
                val tag = "media-selection-action:$action"
                compose.onNodeWithTag("media-selection-action-list").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsNotEnabled()
            }
            pressBack()
            compose.onNodeWithTag("media-selection-options-dialog").assertDoesNotExist()
            compose.onNodeWithTag("library-selection-panel").assertIsDisplayed()
            onMain { assertEquals(setOf(audio.path, video.path), model.mediaSelection.selectedPaths.toSet()) }
            compose.onNodeWithTag("library-selection-cancel").performClick()
            compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
            compose.onNodeWithText(audio.name).performTouchInput { longClick() }
            compose.onNodeWithText("复制").assertIsDisplayed()
            onMain {
                assertFalse(model.mediaSelection.active)
                assertEquals(setOf(audio.path), model.selectedPaths.toSet())
            }
        } finally {
            scenario?.close()
            directoryStore.replace(previousDirectories.paths, previousDirectories.mediaLibraryPaths)
            val restore = preferences.edit()
            if (previousMode == null) restore.remove("player_mode_enabled")
            else restore.putBoolean("player_mode_enabled", previousMode)
            assertTrue(restore.commit())
            root.deleteRecursively()
        }
    }

    private fun screen(activity: MainActivity): MainActivityScreenState =
        invoke(activity, "getScreenState", emptyArray()) as MainActivityScreenState

    private fun invoke(target: Any, name: String, types: Array<Class<*>>, vararg arguments: Any?): Any? =
        target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(target, *arguments)

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}
