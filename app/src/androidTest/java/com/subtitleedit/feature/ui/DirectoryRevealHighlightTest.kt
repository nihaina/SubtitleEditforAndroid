package com.subtitleedit.feature.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.DirectoryScrollPosition
import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.model.FileBrowserViewMode
import com.subtitleedit.ui.MediaTrackRevealOpacity
import com.subtitleedit.ui.MediaTrackRevealPulse
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryRevealHighlightTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val roots = mutableListOf<File>()

    @After fun cleanup() { roots.forEach(File::deleteRecursively) }

    @Test fun loadingListWaitsForTheTargetThenFlashesExactlyThreeTimesWithoutSelectingIt() {
        val snapshot = directory(FileBrowserViewMode.DETAILED)
        val target = snapshot.files.last().path
        val selected = setOf(snapshot.files.first().path)
        val state = mutableStateOf(snapshot.copy(
            isDirectoryLoading = true,
            selectedPaths = selected,
            scrollRequest = MainDirectoryScrollRequest(11L, snapshot.currentDirectory!!.path,
                DirectoryScrollPosition(target, snapshot.files.lastIndex, 0, snapshot.viewMode)),
            directoryRevealRequest = MainDirectoryRevealRequest(target, 101L)
        ))
        val finished = mutableListOf<Long>()
        show(state, finished)
        compose.mainClock.advanceTimeBy(5_000L)
        compose.runOnIdle {
            assertTrue("Loading must not consume the request", finished.isEmpty())
        }
        assertEquals(0, pulse(target))

        compose.runOnIdle { state.value = state.value.copy(isDirectoryLoading = false) }
        assertThreePulses(target, 101L, finished)
        compose.runOnIdle { assertEquals(selected, state.value.selectedPaths) }
        compose.mainClock.advanceTimeBy(5_000L)
        compose.runOnIdle { assertEquals(listOf(101L), finished) }
    }

    @Test fun gridScrollsToADistantFileAndANewTokenCanLocateTheSameFileAgain() {
        val snapshot = directory(FileBrowserViewMode.GRID)
        val target = snapshot.files.last().path
        val state = mutableStateOf(snapshot.copy(directoryRevealRequest = MainDirectoryRevealRequest(target, 201L)))
        val finished = mutableListOf<Long>()
        show(state, finished)
        assertThreePulses(target, 201L, finished)
        compose.runOnIdle {
            assertTrue(state.value.selectedPaths.isEmpty())
            state.value = state.value.copy(directoryRevealRequest = MainDirectoryRevealRequest(target, 202L))
        }
        assertThreePulses(target, 202L, finished)
        compose.mainClock.advanceTimeBy(5_000L)
        compose.runOnIdle { assertEquals(listOf(201L, 202L), finished) }
    }

    @Test fun leavingThePageCancelsItsPulseAndCannotConsumeANewerDirectoryRequest() {
        val first = directory(FileBrowserViewMode.DETAILED)
        val second = directory(FileBrowserViewMode.DETAILED)
        val oldTarget = first.files.last().path
        val newTarget = second.files.last().path
        val state = mutableStateOf(first.copy(directoryRevealRequest = MainDirectoryRevealRequest(oldTarget, 301L)))
        val active = mutableStateOf(true)
        val finished = mutableListOf<Long>()
        show(state, finished, active)
        advanceUntil { pulse(oldTarget) == 1 && opacity(oldTarget) >= 0.9f }
        compose.runOnIdle { active.value = false }
        compose.mainClock.advanceTimeBy(5_000L)
        compose.runOnIdle {
            assertTrue("An inactive page must not consume its locating request", finished.isEmpty())
        }
        assertEquals(0, pulse(oldTarget))
        compose.runOnIdle {
            state.value = second.copy(directoryRevealRequest = MainDirectoryRevealRequest(newTarget, 302L))
            active.value = true
        }
        assertThreePulses(newTarget, 302L, finished)
        compose.runOnIdle { assertEquals(listOf(302L), finished) }
    }

    @Test fun reorderingTheDirectoryDuringARevealDoesNotRestartItsThreePulses() {
        val snapshot = directory(FileBrowserViewMode.DETAILED)
        val target = snapshot.files.last().path
        val state = mutableStateOf(snapshot.copy(directoryRevealRequest = MainDirectoryRevealRequest(target, 401L)))
        val finished = mutableListOf<Long>()
        show(state, finished)
        advanceUntil { pulse(target) == 2 && opacity(target) >= 0.9f }
        compose.runOnIdle { state.value = state.value.copy(files = state.value.files.reversed()) }
        advanceUntil {
            assertTrue("A directory reorder must not restart the current request at pulse one", pulse(target) != 1)
            401L in finished
        }
        compose.runOnIdle { assertEquals(listOf(401L), finished) }
    }

    private fun show(
        state: MutableState<MainActivityScreenState>,
        finished: MutableList<Long>,
        active: MutableState<Boolean> = mutableStateOf(true)
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.requiredSize(360.dp, 420.dp)) {
                    DirectoryBrowserContent(
                        state = state.value,
                        onScrollPosition = {},
                        onDirectoryBreadcrumbClick = { false },
                        onFileClick = { fail("Locating must not play or open the file") },
                        onFileLongClick = { fail("Locating must not enter selection") },
                        isActive = active.value,
                        onRevealFinished = { token ->
                            finished += token
                            if (state.value.directoryRevealRequest?.token == token) {
                                state.value = state.value.copy(directoryRevealRequest = null)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun assertThreePulses(path: String, token: Long, finished: List<Long>) {
        for (expected in 1..3) {
            advanceUntil { pulse(path) == expected && opacity(path) >= 0.9f }
            compose.onNodeWithTag(tag(path)).assertIsDisplayed()
            compose.runOnIdle { assertFalse("Only the completed third pulse consumes the request", token in finished) }
        }
        advanceUntil { token in finished }
        advanceUntil { pulse(path) == 0 }
        compose.runOnIdle { assertEquals(1, finished.count { it == token }) }
    }

    private fun directory(mode: FileBrowserViewMode): MainActivityScreenState {
        val root = File(compose.activity.cacheDir, "directory-reveal-${System.nanoTime()}").apply { check(mkdirs()) }
        roots += root
        val samples = 800
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array()
        val files = (0 until 40).map { index -> File(root, "track-${index.toString().padStart(2, '0')}.wav").apply { writeBytes(wav) } }
        return MainActivityScreenState(currentDirectory = root, canonicalDirectoryPath = root.path,
            files = files, viewMode = mode, fileMetadata = files.associate {
                it.path to BrowserFileMetadata(it, isDirectory = false, size = wav.size.toLong(), modifiedTimeMillis = 1_000L)
            })
    }

    private fun tag(path: String) = "directory-file:$path"
    private fun pulse(path: String): Int = compose.onAllNodesWithTag(tag(path)).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(MediaTrackRevealPulse) ?: 0
    private fun opacity(path: String): Float = compose.onAllNodesWithTag(tag(path)).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(MediaTrackRevealOpacity) ?: 0f

    private fun advanceUntil(condition: () -> Boolean) {
        compose.waitUntil(12_000L) {
            compose.mainClock.advanceTimeByFrame()
            condition()
        }
    }
}
