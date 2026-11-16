package com.subtitleedit.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.longClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.dragQueueWithLongPress
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The same queue UI runs in the media library and the player sheet. */
@RunWith(AndroidJUnit4::class)
class PlaybackQueueContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var directory: File? = null

    @After fun cleanup() { directory?.deleteRecursively() }

    @Test fun explicitSelectionOnlyMarksSelectedRowsAndBatchRemovalOnlyChangesTheQueue() {
        val fixture = showQueue()
        val first = fixture.original.first()
        compose.onNodeWithTag("test-queue-remove:${first.path}").assertIsDisplayed()
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        compose.onNodeWithTag("test-queue-row:${first.path}").performTouchInput { longClick() }
        compose.onNodeWithTag("library-selection-panel").assertIsDisplayed()
        compose.onNodeWithTag("test-queue-remove:${first.path}").assertDoesNotExist()
        compose.onNodeWithTag("test-queue-check:${first.path}").assertIsSelected()
        compose.onNodeWithTag("test-queue-check:${fixture.original[1].path}").assertDoesNotExist()
        compose.onNodeWithTag("test-queue-row:${fixture.original[1].path}").assertIsNotSelected()
        compose.onNodeWithTag("test-queue-row:${fixture.original[1].path}").performClick()
        compose.onNodeWithTag("test-queue-check:${fixture.original[1].path}").assertIsSelected()
        compose.onNodeWithTag("library-selection-options").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.player_queue_remove_selected)).performClick()
        compose.runOnIdle {
            assertEquals(fixture.original.drop(2).map { it.path }, fixture.tracks.value.map { it.path })
            assertEquals(fixture.original.take(2).map { it.path }.toSet(), fixture.removed.single())
            assertTrue(fixture.original.all { it.file.exists() })
            assertTrue(fixture.played.isEmpty())
        }
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        compose.onNodeWithTag("test-queue-remove:${fixture.original.last().path}").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(fixture.original[2]), fixture.tracks.value)
            assertEquals(setOf(fixture.original.last().path), fixture.removed.last())
            assertTrue("The trailing X must not also play the row", fixture.played.isEmpty())
            assertTrue(fixture.original.all { it.file.exists() })
        }
    }

    @Test fun selectionPersistsAtZeroAndAdvancedChoicesUseCurrentDisplayOrder() {
        val fixture = showQueue()
        compose.onNodeWithTag("test-queue-row:${fixture.original[0].path}").performTouchInput { longClick() }
        compose.onNodeWithTag("test-queue-check:${fixture.original[2].path}").assertDoesNotExist()
        compose.onNodeWithTag("test-queue-row:${fixture.original[2].path}").performClick()
        selectAdvanced(R.string.media_lib_selection_range)
        fixture.original.take(3).forEach { compose.onNodeWithTag("test-queue-check:${it.path}").assertIsSelected() }
        selectAdvanced(R.string.media_lib_selection_invert)
        fixture.original.take(3).forEach {
            compose.onNodeWithTag("test-queue-check:${it.path}").assertDoesNotExist()
            compose.onNodeWithTag("test-queue-row:${it.path}").assertIsNotSelected()
        }
        compose.onNodeWithTag("test-queue-check:${fixture.original.last().path}").assertIsSelected().performClick()
        compose.onNodeWithTag("library-selection-panel").assertIsDisplayed()
        selectAdvanced(R.string.media_lib_selection_all)
        compose.onNodeWithTag("library-selection-options").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.player_action_add_to_playlist)).performClick()
        compose.runOnIdle { assertEquals(fixture.original.map { it.path }, fixture.playlisted.single()) }
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()

        compose.onNodeWithTag("test-queue-row:${fixture.original[0].path}").performTouchInput { longClick() }
        pressBack()
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        compose.onNodeWithTag("test-queue-remove:${fixture.original.first().path}").assertIsDisplayed()
    }

    @Test fun sortingUsesImmediateHandlesAndANewQueueClearsLocalSelection() {
        val fixture = showQueue()
        val first = fixture.original.first()
        val third = fixture.original[2]
        compose.onNodeWithTag("test-queue-sort").performClick().assertIsSelected()
        compose.onNodeWithTag("test-queue-remove:${first.path}").assertDoesNotExist()
        val start = compose.onNodeWithTag("test-queue-drag:${first.path}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        val end = compose.onNodeWithTag("test-queue-drag:${third.path}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        dragQueueWithLongPress(compose, compose.activity, start, end, holdBeforeMoveMs = 0L)
        compose.runOnIdle {
            assertEquals(listOf(fixture.original[1], third, first, fixture.original.last()), fixture.tracks.value)
            assertTrue("Dragging must never start playback", fixture.played.isEmpty())
        }
        compose.onNodeWithTag("test-queue-sort").performClick()
        compose.onNodeWithTag("test-queue-row:${first.path}").performTouchInput { longClick() }
        compose.runOnIdle { fixture.version.value++ }
        compose.onNodeWithTag("library-selection-panel").assertDoesNotExist()
        compose.onNodeWithTag("test-queue-remove:${first.path}").assertIsDisplayed()
    }

    @Test fun repeatedFilesRenderPlaySortAndRemoveAsSeparateOccurrences() {
        val root = File(compose.activity.cacheDir, "queue-copies-${System.nanoTime()}").apply { check(mkdirs()) }
        directory = root
        val repeated = File(root, "repeated.wav").apply { writeBytes(byteArrayOf(0)) }
        val other = File(root, "other.wav").apply { writeBytes(byteArrayOf(0)) }
        val first = PlayerTrack(repeated.path, "Repeated", false)
        val second = first.newQueueEntry()
        val current = PlayerTrack(other.path, "Other", false)
        val entries = mutableStateOf(listOf(first, current, second))
        val played = mutableListOf<String>()
        val removed = mutableListOf<String>()
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    PlaybackQueueContent(
                        tracks = entries.value.map { MediaLibraryTrack(it.path, it.name) },
                        entryIds = entries.value.map { it.queueEntryId },
                        currentEntryId = current.queueEntryId,
                        currentPath = current.path,
                        onPlay = { played += it },
                        onMove = { from, to ->
                            val queue = entries.value
                            entries.value = requireNotNull(PlayerQueueEdits.moveEntries(queue,
                                queue.indexOfFirst { it.queueEntryId == current.queueEntryId },
                                queue.indices.toList(), from, to)).playlist
                        },
                        onRemoveEntry = { id -> removed += id; entries.value = entries.value.filterNot { it.queueEntryId == id } },
                        onRemove = { error("An occurrence X must not remove all copies by path") },
                        onFavorite = {}, onAddToPlaylist = {}, onShowInfo = {},
                        tagPrefix = "copies", modifier = Modifier.fillMaxSize()
                    ) { sort -> Row(Modifier.fillMaxWidth()) { Text("Queue", Modifier.weight(1f)); sort() } }
                }
            }
        }
        val firstTag = "${repeated.path}:${first.queueEntryId}"
        val secondTag = "${repeated.path}:${second.queueEntryId}"
        compose.onNodeWithTag("copies-row:$firstTag").assertIsDisplayed()
        compose.onNodeWithTag("copies-row:$secondTag").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(second.queueEntryId), played) }
        compose.onNodeWithTag("copies-sort").performClick()
        val start = compose.onNodeWithTag("copies-drag:$secondTag", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        val end = compose.onNodeWithTag("copies-drag:$firstTag", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        dragQueueWithLongPress(compose, compose.activity, start, end, holdBeforeMoveMs = 0L)
        compose.runOnIdle { assertEquals(listOf(second, first, current), entries.value) }
        val repeatedStart = compose.onNodeWithTag("copies-drag:$secondTag", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        val repeatedEnd = compose.onNodeWithTag("copies-drag:$firstTag", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        dragQueueWithLongPress(compose, compose.activity, repeatedStart, repeatedEnd, holdBeforeMoveMs = 0L)
        compose.runOnIdle {
            assertEquals("Swapping adjacent copies must notify state observers even when paths are unchanged",
                listOf(first, second, current), entries.value)
        }
        compose.onNodeWithTag("copies-sort").performClick()
        compose.onNodeWithTag("copies-remove:$firstTag").performClick()
        compose.runOnIdle {
            assertEquals(listOf(first.queueEntryId), removed)
            assertEquals(listOf(second, current), entries.value)
            assertTrue(repeated.isFile)
            assertEquals("The X must not also play its row", listOf(second.queueEntryId), played)
        }
        compose.onNodeWithTag("copies-row:${repeated.path}").assertIsDisplayed()
    }

    @Test fun draggingAtTheBottomEdgeMovesTheHeldTrackBeyondTheInitialViewport() {
        val fixture = showQueue(count = 40)
        val first = fixture.original.first()
        compose.onNodeWithTag("test-queue-sort").performClick()
        val queue = compose.onNodeWithTag("test-queue-list")
        val list = queue.fetchSemanticsNode().boundsInRoot
        val firstRow = compose.onNodeWithTag("test-queue-row:${first.path}").fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithTag("test-queue-drag:${first.path}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        val initialViewportCapacity = (list.height / firstRow.height).toInt()
        dragQueueWithLongPress(compose, compose.activity, start, Offset(start.x, list.bottom - 2f), 1_500L, holdBeforeMoveMs = 0L)
        compose.runOnIdle {
            val index = fixture.tracks.value.indexOfFirst { it.path == first.path }
            assertTrue("Edge scrolling must move the held item beyond the initially visible rows", index > initialViewportCapacity)
            assertEquals(fixture.original.map { it.path }.toSet(), fixture.tracks.value.map { it.path }.toSet())
            assertTrue("Dragging must not start playback", fixture.played.isEmpty())
        }
    }

    @Test fun draggingAboveTheTopEdgeKeepsMovingUpWithoutViewportSnapBack() {
        val fixture = showQueue(count = 60)
        compose.onNodeWithTag("test-queue-sort").performClick()
        val queue = compose.onNodeWithTag("test-queue-list")
        queue.performScrollToIndex(30)
        val held = fixture.original[32]
        val bounds = queue.fetchSemanticsNode().boundsInRoot
        val scrollRange = queue.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val start = compose.onNodeWithTag("test-queue-drag:${held.path}", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center
        val viewportPositions = mutableListOf<Float>()
        val heldIndices = mutableListOf<Int>()
        compose.runOnIdle {
            viewportPositions += scrollRange.value()
            heldIndices += fixture.tracks.value.indexOfFirst { it.path == held.path }
        }
        dragQueueWithLongPress(compose, compose.activity, start, Offset(start.x, bounds.top - 24f),
            holdAtEndMs = 1_500L, holdBeforeMoveMs = 0L, onFrame = {
                viewportPositions += scrollRange.value()
                heldIndices += fixture.tracks.value.indexOfFirst { it.path == held.path }
            })
        compose.runOnIdle {
            val finalIndex = fixture.tracks.value.indexOfFirst { it.path == held.path }
            assertTrue("Holding above the top must move beyond the original viewport", finalIndex < 28)
            assertTrue("The viewport must never jump back down during an upward drag: $viewportPositions",
                viewportPositions.zipWithNext().all { (before, after) -> after <= before + 0.01f })
            assertTrue("The held row must not move back toward the bottom: $heldIndices",
                heldIndices.zipWithNext().all { (before, after) -> after <= before })
            assertEquals(fixture.original.map { it.path }.toSet(), fixture.tracks.value.map { it.path }.toSet())
            assertTrue("Dragging must not start playback", fixture.played.isEmpty())
        }
    }

    @Test fun ordinarySwipesScrollAndHoldingWithoutMovingNeverStartsPlayback() {
        val fixture = showQueue(count = 40)
        val first = fixture.original.first()
        compose.onNodeWithTag("test-queue-row:${first.path}").performTouchInput { longClick() }
        compose.onNodeWithTag("test-queue-check:${first.path}").assertIsSelected()
        pressBack()
        compose.onNodeWithTag("test-queue-list").performTouchInput { swipeUp(durationMillis = 200L) }
        compose.runOnIdle {
            assertEquals(fixture.original, fixture.tracks.value)
            assertTrue(fixture.played.isEmpty())
        }
        compose.onNodeWithTag("test-queue-row:${first.path}").assertDoesNotExist()
    }

    private fun selectAdvanced(label: Int) {
        compose.onNodeWithTag("library-selection-advanced").performClick()
        compose.onNodeWithText(compose.activity.getString(label)).performClick()
    }

    private fun showQueue(count: Int = 4): Fixture {
        val root = File(compose.activity.cacheDir, "queue-content-${System.nanoTime()}").apply { check(mkdirs()) }
        directory = root
        val fixture = Fixture((1..count).map { index ->
            val file = File(root, "$index.wav").apply { writeBytes(byteArrayOf(0)) }
            MediaLibraryTrack(file.path, "Queue $index", durationMs = index * 1000L)
        })
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) {
                    PlaybackQueueContent(
                        tracks = fixture.tracks.value,
                        currentPath = fixture.original.first().path,
                        onPlay = { fixture.played += it },
                        onMove = { from, to ->
                            val tracks = fixture.tracks.value.toMutableList()
                            val source = tracks.indexOfFirst { it.path == from }
                            val target = tracks.indexOfFirst { it.path == to }
                            if (source >= 0 && target >= 0) tracks.add(target, tracks.removeAt(source))
                            fixture.tracks.value = tracks
                        },
                        onRemove = { removed -> fixture.removed += removed; fixture.tracks.value = fixture.tracks.value.filterNot { it.path in removed } },
                        onFavorite = { fixture.favorited += it.map { track -> track.path } },
                        onAddToPlaylist = { fixture.playlisted += it.map { track -> track.path } },
                        onShowInfo = { fixture.information += it.path },
                        queueVersion = fixture.version.value,
                        tagPrefix = "test-queue",
                        modifier = Modifier.fillMaxSize()
                    ) { select ->
                        Row(Modifier.fillMaxWidth()) { Text("Queue", Modifier.weight(1f)); select() }
                    }
                }
            }
        }
        return fixture
    }

    private class Fixture(val original: List<MediaLibraryTrack>) {
        val tracks: MutableState<List<MediaLibraryTrack>> = mutableStateOf(original)
        val version = mutableStateOf(1)
        val played = mutableListOf<String>()
        val removed = mutableListOf<Set<String>>()
        val favorited = mutableListOf<List<String>>()
        val playlisted = mutableListOf<List<String>>()
        val information = mutableListOf<String>()
    }
}
