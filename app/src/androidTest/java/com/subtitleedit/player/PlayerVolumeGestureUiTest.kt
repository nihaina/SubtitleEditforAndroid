package com.subtitleedit.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real pointer routing with an exact fake media stream; native playback and device volume stay untouched. */
@RunWith(AndroidJUnit4::class)
class PlayerVolumeGestureUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixture: Fixture? = null

    @After fun removeFixture() {
        fixture?.let { fixture ->
            compose.runOnIdle { fixture.store.clear() }
            runBlocking { PlayerLibraryWriter.execute {} }
            fixture.repository.close()
            compose.activity.deleteDatabase(fixture.database)
            fixture.directory.deleteRecursively()
        }
    }

    @Test fun volumeStartsOutsideTheControlBarsAndAudioEdgeButtonOnly() {
        val fixture = showPlayer(video = false, volume = 50)
        val root = bounds("player-screen")
        val top = bounds("player-top-overlay")
        val bottom = bounds("player-bottom-overlay")
        val density = compose.activity.resources.displayMetrics.density

        // Start in the blank side of each bar, then leave the bar during the drag.
        swipeFromRoot(Offset(root.left + 8f * density, top.center.y),
            Offset(root.left + 8f * density, top.bottom + 100f))
        assertVolume(fixture, 50)
        swipeFromRoot(Offset(root.left + 8f * density, bottom.bottom - 4f * density),
            Offset(root.left + 8f * density, bottom.top - 100f))
        assertVolume(fixture, 50)

        val edge = bounds("player-audio-hide-controls")
        swipeFromRoot(edge.center, edge.center.copy(y = edge.center.y - 150f))
        assertVolume(fixture, 50)
        compose.runOnIdle { assertTrue(fixture.model.state.value.controlsVisible) }

        val start = freePoint()
        swipeFromRoot(start, start.copy(y = start.y - 140f))
        compose.runOnIdle {
            assertTrue("An upward drag in the exposed player area adjusts volume",
                fixture.model.state.value.volumePercent > 50)
            assertFalse(fixture.model.state.value.volumeGestureActive)
        }
    }

    @Test fun videoEdgeLockButtonCannotStartVolumeAdjustment() {
        val fixture = showPlayer(video = true, volume = 50)
        val edge = bounds("player-video-lock")
        swipeFromRoot(edge.center, edge.center.copy(y = edge.center.y - 150f))
        compose.runOnIdle {
            assertEquals(50, fixture.model.state.value.volumePercent)
            assertFalse("Dragging the lock button must not activate its click", fixture.model.state.value.controlsLocked)
        }
        val start = freePoint()
        swipeFromRoot(start, start.copy(y = start.y - 140f))
        compose.runOnIdle { assertTrue(fixture.model.state.value.volumePercent > 50) }
    }

    @Test fun warningPinsUnityForTheHeldGestureAndOnlyReleaseAllowsTheNextUpwardDrag() {
        val fixture = showPlayer(video = false, volume = 100)
        val start = freePoint()
        val end = start.copy(y = start.y - 160f)
        beginDrag(start, end)
        compose.runOnIdle {
            assertEquals(100, fixture.model.state.value.volumePercent)
            assertEquals(compose.activity.getString(R.string.player_volume_boost_warning),
                fixture.model.state.value.gestureHint?.text)
            assertTrue(fixture.model.state.value.volumeGestureActive)
        }
        continueDrag(end, end.copy(y = end.y - 80f))
        assertVolume(fixture, 100)
        finishDrag()
        compose.runOnIdle { assertFalse(fixture.model.state.value.volumeGestureActive) }

        swipeFromRoot(start, end)
        compose.runOnIdle {
            assertTrue("A new upward gesture after the warning may enter software boost",
                fixture.model.state.value.volumePercent > 100)
        }
    }

    @Test fun aCancelledWarningGestureDoesNotAuthorizeBoostAndBothLimitsClamp() {
        val fixture = showPlayer(video = false, volume = 100)
        val start = freePoint()
        val up = start.copy(y = start.y - 160f)
        beginDrag(start, up)
        assertVolume(fixture, 100)
        finishDrag(cancelled = true)
        swipeFromRoot(start, up)
        assertVolume(fixture, 100)
        compose.runOnIdle {
            assertEquals(compose.activity.getString(R.string.player_volume_boost_warning),
                fixture.model.state.value.gestureHint?.text)
            fixture.model.setVolume(5)
        }
        swipeFromRoot(start, start.copy(y = start.y + 160f))
        assertVolume(fixture, 0)

        compose.runOnIdle { fixture.model.setVolume(195) }
        beginDrag(start, up)
        assertVolume(fixture, 200)
        continueDrag(up, up.copy(y = up.y - 80f))
        assertVolume(fixture, 200)
        finishDrag()
    }

    private fun showPlayer(video: Boolean, volume: Int): Fixture {
        val fixture = Fixture(video, volume).also { this.fixture = it }
        compose.runOnIdle {
            fixture.model.configure(listOf(PlayerTrack(fixture.file.path, fixture.file.name, video)), 0)
        }
        compose.setContent {
            SubtitleEditComposeTheme {
                Surface(Modifier.fillMaxSize()) { PlayerScreen(fixture.model, embedded = true) }
            }
        }
        compose.waitForIdle()
        return fixture
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun freePoint(): Offset {
        val root = bounds("player-screen")
        val top = bounds("player-top-overlay")
        val bottom = bounds("player-bottom-overlay")
        val density = compose.activity.resources.displayMetrics.density
        // The audio artwork's tap target is centered; stay in its left margin and below the collapse handle.
        val freeTop = maxOf(top.bottom, root.top + 112f * density)
        return Offset(root.left + 16f * density, (freeTop + bottom.top) / 2f)
    }

    private fun local(point: Offset): Offset = point - bounds("player-screen").topLeft

    private fun beginDrag(from: Offset, to: Offset) {
        val localFrom = local(from)
        val localTo = local(to)
        compose.onNodeWithTag("player-screen").performTouchInput {
            down(localFrom)
            repeat(12) { step -> moveTo(localFrom + (localTo - localFrom) * ((step + 1) / 12f), 16L) }
        }
        compose.waitForIdle()
    }

    private fun continueDrag(from: Offset, to: Offset) {
        val localFrom = local(from)
        val localTo = local(to)
        compose.onNodeWithTag("player-screen").performTouchInput {
            repeat(8) { step -> moveTo(localFrom + (localTo - localFrom) * ((step + 1) / 8f), 16L) }
        }
        compose.waitForIdle()
    }

    private fun finishDrag(cancelled: Boolean = false) {
        compose.onNodeWithTag("player-screen").performTouchInput { if (cancelled) cancel() else up() }
        compose.waitForIdle()
    }

    private fun swipeFromRoot(from: Offset, to: Offset) {
        beginDrag(from, to)
        finishDrag()
    }

    private fun assertVolume(fixture: Fixture, expected: Int) {
        compose.runOnIdle { assertEquals(expected, fixture.model.state.value.volumePercent) }
    }

    private inner class Fixture(video: Boolean, volume: Int) {
        val database = "player-volume-gesture-ui-${System.nanoTime()}.db"
        val directory = File(compose.activity.cacheDir, "player-volume-gesture-ui-${System.nanoTime()}")
            .apply { check(mkdirs()) }
        val file = File(directory, if (video) "fixture.mp4" else "fixture.wav").apply { writeBytes(byteArrayOf(0)) }
        val repository = MediaLibraryRepository.open(compose.activity, database) { metadata, duration ->
            MediaLibraryTrack(metadata.path, metadata.file.name, durationMs = duration)
        }
        val store = ViewModelStore()
        private val stream = object : PlayerSystemVolume {
            var value = SystemMediaVolume(volume, 0, 100, muted = volume == 0)
            override fun read() = value
            override fun setIndex(index: Int) {
                value = value.copy(index = index.coerceIn(0, 100), muted = index == 0)
            }
        }
        val model = compose.runOnIdle {
            ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlayerViewModel(compose.activity.application as Application, repository, stream) as T
            })[PlayerViewModel::class.java]
        }
    }
}
