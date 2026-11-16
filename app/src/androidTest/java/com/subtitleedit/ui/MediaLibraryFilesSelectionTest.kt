package com.subtitleedit.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryFilesSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var fixtureDirectory: File? = null

    @After fun removeFixture() {
        fixtureDirectory?.deleteRecursively()
    }

    @Test fun longPressSelectsAndExpandedPanelRemovesWithoutPlaying() {
        val harness = showFiles(3)
        row(harness.files[0]).performTouchInput { longClick() }
        row(harness.files[1]).performClick()
        compose.runOnIdle {
            assertEquals(harness.files.take(2).map { it.absolutePath }.toSet(), harness.selected.value)
            assertTrue(harness.playedPaths.isEmpty())
        }

        compose.onNodeWithTag("library-selection-expand").performClick()
        compose.onNodeWithTag("library-selection-remove:${harness.files[0].absolutePath}").performClick()
        compose.runOnIdle { assertEquals(setOf(harness.files[1].absolutePath), harness.selected.value) }

        compose.onNodeWithTag("library-selection-cancel").performClick()
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        row(harness.files[1]).performClick()
        compose.runOnIdle { assertEquals(listOf(harness.files[1].absolutePath), harness.playedPaths) }
    }

    @Test fun advancedSelectionUsesVisibleFileOrderAndExcludesFolders() {
        val harness = showFiles(4)
        row(harness.files[0]).performTouchInput { longClick() }
        row(harness.files[2]).performClick()
        compose.onNodeWithTag("library-selection-advanced").performClick()
        compose.onNodeWithText("全选").assertIsDisplayed()
        compose.onNodeWithText("区间选择").assertIsDisplayed()
        compose.onNodeWithText("反选").assertIsDisplayed()
        compose.onNodeWithText("不选择这个列表的所有项目").assertDoesNotExist()
        compose.onNodeWithText("区间选择").performClick()
        compose.runOnIdle {
            assertEquals(harness.files.take(3).map { it.absolutePath }.toSet(), harness.selected.value)
        }
        advanced(R.string.media_lib_selection_invert)
        compose.runOnIdle { assertEquals(setOf(harness.files.last().absolutePath), harness.selected.value) }
        advanced(R.string.media_lib_selection_all)
        compose.runOnIdle {
            assertEquals(harness.files.map { it.absolutePath }.toSet(), harness.selected.value)
            assertTrue(harness.playedPaths.isEmpty())
        }
        compose.onNodeWithTag("library-selection-cancel").performClick()
        compose.runOnIdle { assertTrue(harness.selected.value.isEmpty()) }
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
    }

    @Test fun optionsKeepFavoritePlaylistAndInformationActionsAvailable() {
        val harness = showFiles(2)
        row(harness.files.first()).performTouchInput { longClick() }
        listOf(
            R.string.player_action_favorite,
            R.string.player_action_add_to_playlist,
            R.string.media_lib_files_info
        ).forEach { label ->
            compose.onNodeWithTag("library-selection-options").performClick()
            compose.onNodeWithText(compose.activity.getString(label)).performClick()
        }
        compose.runOnIdle { assertEquals(listOf("favorite", "playlist", "info"), harness.optionActions) }
    }

    @Test fun portraitKeepsLastFileAboveExpandedPanel() =
        checkExpandedPanelLayout(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)

    @Test fun landscapeKeepsLastFileAboveExpandedPanel() =
        checkExpandedPanelLayout(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)

    private fun checkExpandedPanelLayout(request: Int, expected: Int) {
        val previous = compose.activity.requestedOrientation
        compose.activityRule.scenario.onActivity { it.requestedOrientation = request }
        compose.waitUntil(5_000L) { compose.activity.resources.configuration.orientation == expected }
        try {
            val harness = showFiles(24)
            row(harness.files.first()).performTouchInput { longClick() }
            compose.onNodeWithTag("library-selection-expand").performClick()
            compose.onNodeWithTag("library-selection-options").assertIsDisplayed()
            compose.onNodeWithTag("library-selection-advanced").assertIsDisplayed()
            compose.onNodeWithTag("library-selection-cancel").assertIsDisplayed()
            compose.onNodeWithTag("library-files-list").performScrollToIndex(harness.entries.lastIndex)
            val lastRow = row(harness.files.last()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val panel = compose.onNodeWithTag("library-selection-panel").fetchSemanticsNode().boundsInRoot
            assertTrue("Last file must remain above the floating panel", lastRow.bottom <= panel.top + 1f)
            row(harness.files.last()).performClick()
            compose.runOnIdle { assertTrue(harness.files.last().absolutePath in harness.selected.value) }
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = previous }
        }
    }

    private fun advanced(labelRes: Int) {
        compose.onNodeWithTag("library-selection-advanced").performClick()
        compose.onNodeWithText(compose.activity.getString(labelRes)).performClick()
    }

    private fun row(file: File) = compose.onNodeWithTag("library-file:${file.absolutePath}")

    private fun showFiles(count: Int): Harness {
        val directory = File(compose.activity.cacheDir, "library-selection-test-${UUID.randomUUID()}").apply { mkdirs() }
        fixtureDirectory = directory
        val audio = InstrumentationRegistry.getInstrumentation().context.assets
            .open("media-tags/preservation.m4a").use { it.readBytes() }
        val files = (1..count).map { index ->
            File(directory, "Track ${index.toString().padStart(2, '0')}.m4a").apply { writeBytes(audio) }
        }
        val folder = File(directory, "Subfolder").apply { mkdirs() }
        val entries = listOf(MediaFolderEntry(folder.absolutePath, folder.name, true, 0, 0L)) +
            files.map { MediaFolderEntry(it.absolutePath, it.name, false, 1, 1_000L) }
        val harness = Harness(files, entries)
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    FilesTabContent(
                        roots = listOf(directory),
                        rootPath = directory.absolutePath,
                        currentPath = directory.absolutePath,
                        entries = entries,
                        rootMediaCounts = mapOf(directory.absolutePath to files.size),
                        selectedPaths = harness.selected.value,
                        selectedDurationMs = harness.selected.value.size * 1_000L,
                        onOpenRoot = {},
                        onOpenFolder = {},
                        onPlayFile = { harness.playedPaths += it.path },
                        onLongPressFolder = {},
                        onLongPressFile = { harness.selected.value += it },
                        onToggleSelection = { path ->
                            harness.selected.value = if (path in harness.selected.value) {
                                harness.selected.value - path
                            } else harness.selected.value + path
                        },
                        onClearSelection = { harness.selected.value = emptySet() },
                        onAdvancedSelection = { action ->
                            harness.selected.value = updateMediaLibrarySelection(
                                files.map { it.absolutePath }, harness.selected.value, action
                            )
                        },
                        onFavoriteSelection = { harness.optionActions += "favorite" },
                        onAddSelectionToPlaylist = { harness.optionActions += "playlist" },
                        onShowInfo = { harness.optionActions += "info" }
                    )
                }
            }
        }
        return harness
    }

    private class Harness(
        val files: List<File>,
        val entries: List<MediaFolderEntry>,
        val selected: MutableState<Set<String>> = mutableStateOf(emptySet()),
        val playedPaths: MutableList<String> = mutableListOf(),
        val optionActions: MutableList<String> = mutableListOf()
    )
}
